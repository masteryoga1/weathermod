package com.weathermod.server.impact;

import com.weathermod.core.FunnelSpine;
import com.weathermod.core.ModTags;
import com.weathermod.core.VortexParams;
import com.weathermod.entity.FlyingBlockEntity;
import com.weathermod.entity.FlyingBlockMath;
import com.weathermod.server.StormInstance;
import com.weathermod.server.StormRuntime;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.family.ActiveVortex;
import com.weathermod.server.family.FamilyContext;
import com.weathermod.server.family.VortexState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Tears blocks out of the world around the base of a vortex: samples columns in the grab radius, picks what the
 * vortex's tier can take, and throws it as a flying block or destroys it. From EF3 up it also scours the grass
 * inside the core to dirt, which leaves the brown damage path.
 *
 * <p>Everything is budgeted: a vortex takes its share of the configured blocks per tick, the level as a whole
 * takes what {@code StormRuntime.tryRip()} grants, and flying blocks are capped per storm and per level. Every
 * block is asked for through {@link ProtectedActions}; no chunk is loaded, no game rule is touched and nothing is
 * ripped inside a Create contraption, next to glue or within reach of a weather deflector.
 */
public final class BlockRipper {
    private static final int SCOUR_DIRT_MIN_TIER = 5;
    private static final int CEILING_START_ABOVE_GROUND_BLOCKS = 3;
    private static final int CEILING_EXTRA_POSITIONS = 3;

    private BlockRipper() {
    }

    /**
     * Rips, scours and throws for one vortex for one tick. Returns the number of blocks removed; scoured blocks
     * are converted, not removed, and are not counted.
     *
     * @param vortex  the vortex as published this tick
     * @param storm   the storm it belongs to; its vortex state holds the launch cooldown
     * @param context the tick's level, data, tick counter and random source
     */
    public static int tick(ActiveVortex vortex, StormInstance storm, FamilyContext context) {
        if (!WeatherManager.destructionMode().ripsBlocks() || !vortex.actsOnWorld()) {
            return 0;
        }
        VortexParams params = vortex.params();
        FunnelSpine spine = vortex.spine();
        int rips = RipMath.ripsPerTick(WeatherManager.blockBudgetPerTick(), params.ripShare() * spine.strength());
        if (rips <= 0) {
            return 0;
        }
        ServerLevel level = context.level();
        StormRuntime runtime = context.data().runtime();
        ShieldVolumes shields = runtime.shields();
        shields.refreshIfDue(level, context.tick(), runtime.vortices());
        if (shields.isOverflowing()) {
            return 0;
        }

        Pass pass = new Pass(vortex, storm, context, rips);
        RandomSource random = context.random();
        ServerChunkCache chunks = level.getChunkSource();
        double baseX = vortex.baseX();
        double baseZ = vortex.baseZ();
        float baseRadius = spine.baseRadius();
        float ripRadius = RipMath.ripRadius(baseRadius);
        int attempts = RipMath.attemptsPerTick(rips);
        for (int attempt = 0; attempt < attempts && pass.budgetLeft(); attempt++) {
            double distance = ripRadius > 0.0F ? ripRadius * Math.sqrt(random.nextDouble()) : 0.0;
            double angle = random.nextDouble() * (Math.PI * 2.0);
            double x = baseX + Math.cos(angle) * distance;
            double z = baseZ + Math.sin(angle) * distance;
            int blockX = Mth.floor(x);
            int blockZ = Mth.floor(z);
            LevelChunk chunk = chunks.getChunkNow(blockX >> 4, blockZ >> 4);
            if (chunk == null || ProtectedActions.deflected(level, x, z)) {
                continue;
            }
            pass.column(chunk, blockX, blockZ, distance <= baseRadius);
        }
        return pass.removed;
    }

    /** The state of one call of {@link BlockRipper#tick}: what it works with and what it has used up. */
    private static final class Pass {
        private final ActiveVortex vortex;
        private final ServerLevel level;
        private final StormRuntime runtime;
        private final ShieldVolumes shields;
        private final RandomSource random;
        private final VortexState vortexState;
        private final int tier;
        private final int rips;
        private final int depth;
        private final boolean ceiling;
        private final int flyingBlockCap;
        private final double dropChance;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final Vector3f axis = new Vector3f();
        private final Vector3d velocity = new Vector3d();
        private int removed;
        private int scours;
        private int drops;
        private boolean levelBudgetSpent;

        Pass(ActiveVortex vortex, StormInstance storm, FamilyContext context, int rips) {
            this.vortex = vortex;
            this.level = context.level();
            this.runtime = context.data().runtime();
            this.shields = runtime.shields();
            this.random = context.random();
            this.vortexState = storm.familyState(VortexState.class, VortexState::load);
            this.tier = vortex.params().tier();
            this.rips = rips;
            this.depth = RipMath.sampleDepth(tier);
            this.ceiling = level.dimensionType().hasCeiling();
            this.flyingBlockCap = RipMath.flyingBlockCap(WeatherManager.maxFlyingBlocksPerStorm(),
                vortex.params().flyingBlockShare());
            this.dropChance = WeatherManager.rippedBlockDropChance();
        }

        /** Whether another block may be removed: the vortex's share is not used up and the level's was not refused. */
        boolean budgetLeft() {
            return removed < rips && !levelBudgetSpent;
        }

        /**
         * Walks one column from its top down over {@code depth} blocks that are not air and acts on those that
         * pass. A scour ends the column whether or not it was allowed.
         */
        void column(LevelChunk chunk, int blockX, int blockZ, boolean insideCore) {
            int minY = level.getMinBuildHeight();
            int y;
            int positionsLeft;
            if (ceiling) {
                y = Mth.floor(vortex.spine().groundY()) + CEILING_START_ABOVE_GROUND_BLOCKS + tier;
                positionsLeft = 2 * depth + CEILING_EXTRA_POSITIONS;
            } else {
                y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, blockX & 15, blockZ & 15);
                positionsLeft = Integer.MAX_VALUE;
            }
            y = Math.min(y, level.getMaxBuildHeight() - 1);
            int examined = 0;
            for (; y >= minY && positionsLeft > 0 && examined < depth && budgetLeft(); y--, positionsLeft--) {
                cursor.set(blockX, y, blockZ);
                BlockState state = chunk.getBlockState(cursor);
                if (state.isAir()) {
                    continue;
                }
                examined++;
                if (!state.getFluidState().isEmpty() || state.is(ModTags.Blocks.NEVER_RIP)) {
                    continue;
                }
                float hardness = state.getDestroySpeed(level, cursor);
                boolean inTierTag = state.is(ModTags.Blocks.rippable(tier));
                if (!RipMath.tierAllows(tier, inTierTag, state.is(ModTags.Blocks.RIPPABLE_EF5), hardness)) {
                    continue;
                }
                if (state.hasBlockEntity() && !state.is(ModTags.Blocks.RIPPABLE_WITH_BLOCK_ENTITY)) {
                    continue;
                }
                if (shields.contains(blockX + 0.5, y + 0.5, blockZ + 0.5)) {
                    continue;
                }
                if (insideCore && scour(state)) {
                    return;
                }
                boolean ground = state.is(ModTags.Blocks.NATURAL_GROUND);
                if (ground && !RipMath.digAllowed(tier, vortex.digRefY(), y)) {
                    continue;
                }
                if (random.nextFloat() >= RipMath.ripChance(tier, hardness, inTierTag, ground)) {
                    continue;
                }
                BlockPos pos = cursor.immutable();
                if (!ProtectedActions.mayBreak(level, pos, state)) {
                    continue;
                }
                if (!runtime.tryRip()) {
                    levelBudgetSpent = true;
                    return;
                }
                remove(pos, state);
            }
        }

        /**
         * The scour rule for the block under the cursor, which lies inside the core: grass becomes dirt and, under
         * an EF5, dirt becomes coarse dirt. Returns whether the rule took the block, allowed or not.
         */
        private boolean scour(BlockState state) {
            if (tier < RipMath.SCOUR_MIN_TIER || scours >= RipMath.MAX_SCOURS_PER_STORM_TICK) {
                return false;
            }
            BlockState scoured;
            if (state.is(Blocks.GRASS_BLOCK)) {
                scoured = Blocks.DIRT.defaultBlockState();
            } else if (tier >= SCOUR_DIRT_MIN_TIER && state.is(Blocks.DIRT)) {
                scoured = Blocks.COARSE_DIRT.defaultBlockState();
            } else {
                return false;
            }
            if (!(random.nextFloat() < RipMath.SCOUR_CHANCE)) {
                return false;
            }
            if (ProtectedActions.convert(level, cursor.immutable(), state, scoured)) {
                scours++;
            }
            return true;
        }

        /** Throws the block when it can fly and the caps allow one more, otherwise destroys it. */
        private void remove(BlockPos pos, BlockState state) {
            boolean hasBlockEntity = state.hasBlockEntity();
            int stormId = vortex.stormId();
            boolean thrown = !hasBlockEntity
                && state.isCollisionShapeFullBlock(level, pos)
                && vortexState.launchCooldown == 0
                && level.isPositionEntityTicking(pos)
                && runtime.flyingBlocks(stormId) < flyingBlockCap
                && runtime.flyingBlocksTotal() < runtime.maxFlyingBlocks();
            if (thrown) {
                if (!ProtectedActions.destroy(level, pos, state, false)) {
                    return;
                }
                removed++;
                launch(pos, state, stormId);
                vortexState.launchCooldown = RipMath.LAUNCH_INTERVAL_TICKS;
                return;
            }
            boolean drop = hasBlockEntity
                || drops < RipMath.MAX_DROPS_PER_STORM_TICK && random.nextFloat() < dropChance;
            if (ProtectedActions.destroy(level, pos, state, drop)) {
                removed++;
                if (drop) {
                    drops++;
                }
            }
        }

        /** Starts a flying block along the tangent of its circle around the funnel's axis at its height. */
        private void launch(BlockPos pos, BlockState state, int stormId) {
            VortexParams params = vortex.params();
            vortex.spine().offsetAt(pos.getY() + 0.5, axis);
            double dx = pos.getX() + 0.5 - (vortex.x() + axis.x);
            double dz = pos.getZ() + 0.5 - (vortex.z() + axis.z);
            float up = Mth.lerp(random.nextFloat(), FlyingBlockMath.LAUNCH_UP_MIN, FlyingBlockMath.LAUNCH_UP_MAX);
            FlyingBlockMath.launchVelocity(dx, dz, params.throwSpeed(), up, velocity);
            FlyingBlockEntity.launch(level, pos, state, stormId, new Vec3(velocity.x, velocity.y, velocity.z),
                FlyingBlockMath.orbitTicks(tier, random));
        }
    }
}
