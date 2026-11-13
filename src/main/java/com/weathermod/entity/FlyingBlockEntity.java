package com.weathermod.entity;

import com.weathermod.core.ModTags;
import com.weathermod.core.VortexField;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormRuntime;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.family.ActiveVortex;
import com.weathermod.server.impact.ProtectedActions;
import com.weathermod.server.impact.ShieldVolumes;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * A block torn loose by a vortex: orbits, is released, then lands as a block or shatters.
 *
 * <p>While it orbits, the entity is moved by the same force model that moves everything else in the funnel and
 * passes through blocks, because it starts inside the terrain it was part of. When its orbit time is over, or its
 * vortex is gone or has left the ground, it is released: from then on it collides, falls like a vanilla falling
 * block and, where it comes to rest, is put back as a block or shatters. It is never saved; a block in flight when
 * its chunk unloads is lost.
 *
 * <p>The server alone simulates it. The client only follows the positions the server sends, one every two ticks,
 * and draws the block.
 */
public class FlyingBlockEntity extends Entity {
    /** Hard life limit, the same vanilla falling blocks have. */
    public static final int MAX_LIFE_TICKS = 600;
    /** Free-flight gravity, as vanilla falling blocks, in blocks per tick squared. */
    public static final double GRAVITY = 0.04;
    /** Share of its velocity a flying block keeps every tick, as vanilla falling blocks. */
    public static final double DRAG = 0.98;
    /** It hurts entities at most this often. */
    public static final int DAMAGE_INTERVAL_TICKS = 10;

    private static final EntityDataAccessor<BlockState> DATA_BLOCK_STATE =
        SynchedEntityData.defineId(FlyingBlockEntity.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Integer> DATA_STORM_ID =
        SynchedEntityData.defineId(FlyingBlockEntity.class, EntityDataSerializers.INT);
    private static final int NO_STORM_ID = -1;
    private static final float FULL_EXPOSURE = 1.0F;

    private final Vector3d acceleration = new Vector3d();
    private int ageTicks;
    private int orbitTicks;
    private boolean released;
    private boolean countedIn;
    private boolean countedOut;
    private int countedStormId = NO_STORM_ID;
    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;

    /**
     * Creates a flying block that carries no block yet, on either side. It passes through blocks until the server
     * releases it; the client never moves it by itself.
     */
    public FlyingBlockEntity(EntityType<? extends FlyingBlockEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * Creates and adds one at the centre of a block position, with {@code noPhysics} set. Null when the level cap
     * ({@code runtime.maxFlyingBlocks()}) is reached or the entity could not be added. The storm's count is taken
     * by the entity itself when the level accepts it.
     *
     * @param level      the level to add it to
     * @param from       the position of the block that was torn loose
     * @param state      the block it carries
     * @param stormId    the id of the storm whose vortex threw it
     * @param velocity   its first velocity, in blocks per tick
     * @param orbitTicks ticks it stays in orbit before it is released
     */
    @Nullable
    public static FlyingBlockEntity launch(ServerLevel level, BlockPos from, BlockState state, int stormId,
                                           Vec3 velocity, int orbitTicks) {
        StormRuntime runtime = DimensionWeatherData.get(level).runtime();
        if (runtime.flyingBlocksTotal() >= runtime.maxFlyingBlocks()) {
            return null;
        }
        FlyingBlockEntity entity = new FlyingBlockEntity(WeatherEntities.FLYING_BLOCK.get(), level);
        entity.entityData.set(DATA_BLOCK_STATE, state);
        entity.entityData.set(DATA_STORM_ID, stormId);
        entity.orbitTicks = orbitTicks;
        entity.moveTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, 0.0F, 0.0F);
        entity.setDeltaMovement(velocity);
        if (!level.addFreshEntity(entity)) {
            return null;
        }
        return entity;
    }

    /** The block this entity carries; air until the server's data has arrived. */
    public BlockState blockState() {
        return entityData.get(DATA_BLOCK_STATE);
    }

    /** The id of the storm whose vortex threw this block. */
    public int stormId() {
        return entityData.get(DATA_STORM_ID);
    }

    /**
     * Whether the block has left its orbit and now falls with collisions. Known to the server only; a client
     * always answers false.
     */
    public boolean isReleased() {
        return released;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_BLOCK_STATE, Blocks.AIR.defaultBlockState());
        builder.define(DATA_STORM_ID, NO_STORM_ID);
    }

    /**
     * One tick. The server ages the block, moves it with the vortex or lets it fall, and ends it when it lands or
     * its time is up; a client walks toward the last position the server sent. Neither side runs the vanilla base
     * tick: the block does not burn, swim, ride or use portals.
     */
    @Override
    public void tick() {
        if (level() instanceof ServerLevel serverLevel) {
            serverTick(serverLevel);
        } else if (lerpSteps > 0) {
            lerpPositionAndRotationStep(lerpSteps, lerpX, lerpY, lerpZ, getYRot(), getXRot());
            lerpSteps--;
        }
    }

    private void serverTick(ServerLevel level) {
        BlockState state = blockState();
        if (state.isAir()) {
            discard();
            return;
        }
        ageTicks++;
        if (ageTicks >= MAX_LIFE_TICKS) {
            shatter(level, blockPosition(), state);
            return;
        }
        checkBelowWorld();
        if (isRemoved()) {
            return;
        }

        Vec3 motion = getDeltaMovement();
        double vx = motion.x;
        double vy = motion.y;
        double vz = motion.z;
        if (!released) {
            ActiveVortex vortex = DimensionWeatherData.get(level).runtime().vortex(stormId());
            if (ageTicks < orbitTicks && vortex != null && vortex.actsOnWorld()) {
                VortexField.acceleration(vortex.params(), vortex.spine(), vortex.x(), vortex.z(),
                    getX(), getY(), getZ(), vx, vy, vz, FULL_EXPOSURE, acceleration);
                vx += acceleration.x;
                vy += acceleration.y;
                vz += acceleration.z;
            } else {
                released = true;
                noPhysics = false;
                BlockPos inside = blockPosition();
                if (level.getBlockState(inside).blocksMotion()) {
                    shatter(level, inside, state);
                    return;
                }
            }
        }
        vy -= GRAVITY;

        if (!level.isPositionEntityTicking(BlockPos.containing(getX() + vx, getY() + vy, getZ() + vz))) {
            discard();
            return;
        }
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        setDeltaMovement(vx, vy, vz);
        move(MoverType.SELF, getDeltaMovement());
        if (isRemoved()) {
            return;
        }
        if (ageTicks % DAMAGE_INTERVAL_TICKS == 0) {
            hurtWhatItTouches(level, speed);
        }
        if (released && onGround()) {
            land(level, state);
            return;
        }
        setDeltaMovement(getDeltaMovement().scale(DRAG));
    }

    /** Hurts the living entities its box touches when it is fast enough and the config lets storms hurt. */
    private void hurtWhatItTouches(ServerLevel level, double speed) {
        if (speed < FlyingBlockMath.DAMAGE_MIN_SPEED || !WeatherManager.destructionMode().affectsEntities()) {
            return;
        }
        List<Entity> touched = level.getEntities(this, getBoundingBox(), EntitySelector.LIVING_ENTITY_STILL_ALIVE);
        if (touched.isEmpty()) {
            return;
        }
        DamageSource source = damageSources().fallingBlock(this);
        float damage = FlyingBlockMath.impactDamage(speed);
        for (Entity entity : touched) {
            entity.hurt(source, damage);
        }
    }

    /** Puts the block back where the entity rests, or shatters it when it may not land there; then it is gone. */
    private void land(ServerLevel level, BlockState state) {
        BlockPos pos = blockPosition();
        if (mayLand(level, pos, state) && ProtectedActions.place(level, pos, state)) {
            discard();
            return;
        }
        shatter(level, pos, state);
    }

    private boolean mayLand(ServerLevel level, BlockPos pos, BlockState state) {
        if (!WeatherManager.destructionMode().ripsBlocks()
            || !(random.nextFloat() < WeatherManager.flyingBlockLandChance())) {
            return false;
        }
        BlockState target = level.getBlockState(pos);
        if (!target.canBeReplaced() || !target.getFluidState().isEmpty() || !state.canSurvive(level, pos)) {
            return false;
        }
        if (ProtectedActions.deflected(level, pos.getX() + 0.5, pos.getZ() + 0.5)) {
            return false;
        }
        AABB guarded = new AABB(pos).inflate(ShieldVolumes.PADDING_BLOCKS);
        return level.getEntities(this, guarded, entity -> entity.getType().is(ModTags.EntityTypes.RIP_SHIELD))
            .isEmpty();
    }

    /** Ends the block without leaving anything but, budget permitting, its break sound and particles. */
    private void shatter(ServerLevel level, BlockPos pos, BlockState state) {
        ProtectedActions.breakEffect(level, pos, state);
        discard();
    }

    /**
     * Counts the block into its storm's flying blocks when a server level accepts it, at most once. This hook and
     * {@link #onRemovedFromLevel()} see every way into and out of a level, a chunk unload included.
     */
    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (!countedIn && level() instanceof ServerLevel serverLevel) {
            countedIn = true;
            countedStormId = stormId();
            DimensionWeatherData.get(serverLevel).runtime().flyingBlockAdded(countedStormId);
        }
    }

    /** Counts the block out of its storm's flying blocks when it leaves a server level, at most once. */
    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (countedIn && !countedOut && level() instanceof ServerLevel serverLevel) {
            countedOut = true;
            DimensionWeatherData.get(serverLevel).runtime().flyingBlockRemoved(countedStormId);
        }
    }

    /**
     * Client side: remembers where the server says the block is and in how many ticks to be there; {@link #tick()}
     * walks toward it, so motion stays smooth although a position arrives only every second tick.
     */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpSteps = Math.max(1, steps);
    }

    @Override
    public double lerpTargetX() {
        return lerpSteps > 0 ? lerpX : getX();
    }

    @Override
    public double lerpTargetY() {
        return lerpSteps > 0 ? lerpY : getY();
    }

    @Override
    public double lerpTargetZ() {
        return lerpSteps > 0 ? lerpZ : getZ();
    }

    /** Pistons, and Create's contraption collider with them, leave the block alone. */
    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    /** Never: the block is counted against a storm of the level it was thrown in. */
    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    /**
     * Nothing hurts a flying block, and nothing marks it as hurt either: a marked entity has its velocity sent to
     * every watcher, which this entity's network budget does not allow for.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    /** Debris does not press plates or trip wires. */
    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    /** Debris makes no step sounds and no vibrations of its own. */
    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    /** Nothing to read: the entity type is never saved. */
    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
    }

    /** Nothing to write: the entity type is never saved. */
    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
    }
}
