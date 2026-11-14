package com.weathermod.server.impact;

import com.weathermod.config.FallDamageMode;
import com.weathermod.core.FunnelSpine;
import com.weathermod.core.ModTags;
import com.weathermod.server.WeatherManager;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.PushReaction;
import org.joml.Vector3f;

/**
 * The rules both sides share when weather pushes an entity: who may be pushed at all, how much of a push reaches
 * a heavy or a sheltered body, how a push is written to an entity so that the entity tracker is not flooded, and
 * what a fall after a ride in a vortex costs. The server uses all of it for the entities it simulates; the client
 * uses the pure helpers and the {@link Level}-based ones for its own player. Nothing here refers to a server-only
 * class.
 */
public final class ForceRules {
    /** Entities one vortex or one downburst may push in one tick, so one storm cannot take the whole level budget. */
    public static final int MAX_FORCED_ENTITIES_PER_STORM_TICK = 128;
    /** Ambient wind only moves entities this near a player. */
    public static final double WIND_FORCE_PLAYER_RANGE_BLOCKS = 48.0;
    /** Ambient wind is applied every this many ticks, with the force multiplied to match. */
    public static final int WIND_FORCE_INTERVAL_TICKS = 2;
    /** A pushed entity raises hasImpulse (one position and one motion packet per watcher) every this many ticks. */
    public static final int FORCE_SYNC_INTERVAL_TICKS = 5;
    /**
     * Where the dimension has no sky light, a body is sheltered by a motion-blocking block this near above its
     * head.
     */
    public static final int ROOF_PROBE_BLOCKS = 6;
    /** A fall within this long after leaving a vortex counts as caused by it. */
    public static final int VORTEX_FALL_GRACE_TICKS = 200;
    /** Share of the fall damage left by {@link FallDamageMode#REDUCED}. */
    public static final float REDUCED_FALL_FACTOR = 0.5F;
    /** Key in {@code Entity.getPersistentData()} holding the game time (long) of the last vortex contact. */
    public static final String VORTEX_STAMP_KEY = "weathermod:vortex_tick";
    /** An entity inside the funnel of a fire whirl burns at least this long. */
    public static final int FIRE_WHIRL_BURN_TICKS = 80;
    /**
     * Knockback resistance lowers the push to no less than this share:
     * {@code mass = max(MIN_MASS, 1 - MASS_RESISTANCE_WEIGHT * resistance)}.
     */
    public static final float MIN_MASS = 0.25F;
    /** Share of the push a full knockback resistance takes away. */
    public static final float MASS_RESISTANCE_WEIGHT = 0.75F;

    private ForceRules() {
    }

    /**
     * The server-side filter of what weather may push: the entity is alive, not a spectator, has physics, is not
     * a passenger (its vehicle is pushed instead), is neither a player nor controlled by one (their own client
     * pushes those), is not a projectile, is not attached to a block, does not ignore pistons, and neither its
     * type nor the type of anything riding it is in {@code #weathermod:wind_immune}.
     */
    public static boolean mayBePushed(Entity entity) {
        if (!entity.isAlive() || entity.isSpectator() || entity.noPhysics || entity.isPassenger()) {
            return false;
        }
        if (entity instanceof Player || entity.getControllingPassenger() instanceof Player) {
            return false;
        }
        if (entity instanceof Projectile || entity instanceof BlockAttachedEntity) {
            return false;
        }
        if (entity.getPistonPushReaction() == PushReaction.IGNORE
            || entity.getType().is(ModTags.EntityTypes.WIND_IMMUNE)) {
            return false;
        }
        if (entity.isVehicle()) {
            for (Entity passenger : entity.getIndirectPassengers()) {
                if (passenger.getType().is(ModTags.EntityTypes.WIND_IMMUNE)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether the server config lets weather push this kind of entity: {@code pushItems} decides for dropped
     * items and experience orbs, {@code pushMobs} for everything else.
     */
    public static boolean categoryEnabled(Entity entity) {
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return WeatherManager.pushItems();
        }
        return WeatherManager.pushMobs();
    }

    /**
     * The share of a push that moves a body of the given knockback resistance:
     * {@code max(MIN_MASS, 1 - MASS_RESISTANCE_WEIGHT * knockbackResistance)}, the resistance clamped to 0..1 (a
     * resistance that is not a number counts as 0). The floor lets the strongest vortices move even an iron
     * golem, slowly.
     */
    public static float mass(double knockbackResistance) {
        double resistance = Double.isNaN(knockbackResistance) ? 0.0 : Mth.clamp(knockbackResistance, 0.0, 1.0);
        return Math.max(MIN_MASS, (float) (1.0 - MASS_RESISTANCE_WEIGHT * resistance));
    }

    /**
     * {@link #mass(double)} of the knockback resistance attribute for a living entity, 1 for everything else and
     * for a living entity without that attribute.
     */
    public static float mass(Entity entity) {
        if (entity instanceof LivingEntity living
            && living.getAttributes().hasAttribute(Attributes.KNOCKBACK_RESISTANCE)) {
            return mass(living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        }
        return 1.0F;
    }

    /**
     * True when {@code solidAt} is false for every y in {@code headY + 1 .. headY + blocks}: nothing solid lies
     * within that many blocks above a head.
     *
     * @param solidAt whether the block at a height counts as a roof
     */
    public static boolean openAbove(IntPredicate solidAt, int headY, int blocks) {
        for (int offset = 1; offset <= blocks; offset++) {
            if (solidAt.test(headY + offset)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether weather reaches the entity. In a dimension with sky light: the level can see the sky at its eye
     * position. Without sky light (the Nether, where no position ever sees the sky): none of the
     * {@link #ROOF_PROBE_BLOCKS} blocks above its eye position blocks motion; a position whose chunk is not
     * loaded counts as open. Never loads a chunk.
     */
    public static boolean underOpenSky(Level level, Entity entity) {
        BlockPos eye = BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ());
        if (level.dimensionType().hasSkyLight()) {
            return level.canSeeSky(eye);
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(eye.getX()),
            SectionPos.blockToSectionCoord(eye.getZ()));
        if (chunk == null) {
            return true;
        }
        BlockPos.MutableBlockPos cursor = eye.mutable();
        return openAbove(y -> chunk.getBlockState(cursor.setY(y)).blocksMotion(), eye.getY(), ROOF_PROBE_BLOCKS);
    }

    /**
     * Whether {@code (x, y, z)} is inside the funnel itself: the height lies between the spine's ground point and
     * its top, and the point is within {@code spine.radiusAt(y)} of the axis at that height.
     *
     * @param centreX the storm centre the spine's offsets are relative to, along {@code X}
     * @param centreZ the storm centre along {@code Z}
     */
    public static boolean insideFunnel(FunnelSpine spine, double centreX, double centreZ, double x, double y,
                                       double z) {
        if (!(y >= spine.groundY()) || !(y <= spine.topY())) {
            return false;
        }
        Vector3f offset = new Vector3f();
        spine.offsetAt(y, offset);
        double dx = x - (centreX + offset.x);
        double dz = z - (centreZ + offset.z);
        double radius = spine.radiusAt(y);
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * Whether a pushed entity tells its watchers about its motion this tick:
     * {@code (tick + entityId) % FORCE_SYNC_INTERVAL_TICKS == 0}, so each entity does on one tick in
     * {@link #FORCE_SYNC_INTERVAL_TICKS} and entities with neighbouring ids on different ticks.
     */
    public static boolean syncDue(int tick, int entityId) {
        return (tick + entityId) % FORCE_SYNC_INTERVAL_TICKS == 0;
    }

    /**
     * Adds an acceleration, in blocks per tick squared, to an entity's velocity without a packet, and raises
     * {@code hasImpulse} when {@link #syncDue(int, int)} says so: the entity tracker then resends position and
     * motion on that tick only. Every push the weather gives an entity on the server goes through here; never
     * {@code Entity.push}, which would cost two packets per watcher on every tick.
     *
     * @param tick the weather tick of the dimension
     */
    public static void apply(Entity entity, double ax, double ay, double az, int tick) {
        entity.setDeltaMovement(entity.getDeltaMovement().add(ax, ay, az));
        if (syncDue(tick, entity.getId())) {
            entity.hasImpulse = true;
        }
    }

    /**
     * Whether a vortex touched an entity at most {@link #VORTEX_FALL_GRACE_TICKS} game ticks ago. A stamp that
     * lies in the future does not count.
     *
     * @param stampGameTime the game time stored under {@link #VORTEX_STAMP_KEY}
     * @param now           the game time now
     */
    public static boolean recentlyInVortex(long stampGameTime, long now) {
        long elapsed = now - stampGameTime;
        return elapsed >= 0L && elapsed <= VORTEX_FALL_GRACE_TICKS;
    }

    /**
     * The fall damage multiplier after the policy for falls a vortex caused: unchanged for
     * {@link FallDamageMode#FULL}, times {@link #REDUCED_FALL_FACTOR} for {@link FallDamageMode#REDUCED}, 0 for
     * {@link FallDamageMode#NONE}.
     */
    public static float fallMultiplier(FallDamageMode mode, float multiplier) {
        return switch (mode) {
            case FULL -> multiplier;
            case REDUCED -> multiplier * REDUCED_FALL_FACTOR;
            case NONE -> 0.0F;
        };
    }
}
