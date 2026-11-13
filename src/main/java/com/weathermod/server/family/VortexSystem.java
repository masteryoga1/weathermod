package com.weathermod.server.family;

import com.weathermod.block.DeflectorMath;
import com.weathermod.core.FunnelSpine;
import com.weathermod.core.Headings;
import com.weathermod.core.VortexField;
import com.weathermod.core.VortexKind;
import com.weathermod.core.VortexParams;
import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.core.WeatherTypes;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.impact.BlockRipper;
import com.weathermod.server.impact.GroundFinder;
import com.weathermod.server.impact.ProtectedActions;
import com.weathermod.server.impact.TemporaryFires;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The vortex family: tornadoes, landspouts, waterspouts, gustnadoes, dust devils and fire whirls. Every tick it
 * lets the funnel come down and a natural tornado grow, keeps a tornado on the flank of the storm that spawned
 * it, holds the vortex out of weather deflectors, follows the ground under the base, builds the funnel's spine and
 * publishes the vortex in the dimension's runtime, from where the entity forces, the block ripper and the network
 * read it. It then applies the rules of the kind (a waterspout ends over land, a fire whirl lights fires) and lets
 * the ripper work.
 *
 * <p>The simulation runs in every destruction mode; only the fires and the ripping ask the mode. A vortex over
 * ground that cannot be sampled (its chunk is not loaded) is still published and synced, with a guessed ground
 * height; it only stops acting on the world.
 */
public final class VortexSystem implements FamilySystem {
    /** A fire whirl lights no column whose ground lies farther than this from the ground under its base. */
    private static final double FIRE_GROUND_TOLERANCE_BLOCKS = 3.0;
    private static final float TWO_PI = (float) (Math.PI * 2.0);

    /** Creates the system; it keeps no state of its own, everything lives in the storms and the runtime. */
    public VortexSystem() {
    }

    @Override
    public WeatherFamily family() {
        return WeatherFamily.VORTEX;
    }

    /**
     * Runs one tick of a vortex storm: seeds its state once, computes the descent and lets a natural tornado grow
     * a tier, leads a child on its parent's leash, pushes and slides it around weather deflectors, samples the
     * ground and the dig reference, builds the spine and publishes the {@link ActiveVortex}, and, while the vortex
     * acts on the world, applies the waterspout and fire whirl rules and calls the block ripper. A type without a
     * vortex block does nothing.
     */
    @Override
    public void tick(StormInstance storm, WeatherType type, FamilyContext context) {
        Optional<VortexParams> block = type.vortex();
        if (block.isEmpty()) {
            return;
        }
        VortexParams params = block.get();
        VortexState state = storm.familyState(VortexState.class, VortexState::load);

        seed(state, context.random());
        float descent = VortexMath.descent(storm.intensity(), storm.ageTicks(), state.touchdownDelayTicks);
        grow(storm, params, state, descent, context.level());
        followParent(storm, type, params, state, context.data());
        avoidDeflectors(storm, params, context.data());
        trackGround(storm, params, state, context);
        ActiveVortex vortex = publish(storm, params, state, descent, context);

        if (vortex.actsOnWorld()) {
            if (params.kind() == VortexKind.WATERSPOUT) {
                endOverLand(storm, state, vortex, context.level());
            }
            if (params.kind().setsFire()) {
                lightFire(state, vortex, context);
            }
        }

        if (state.launchCooldown > 0) {
            state.launchCooldown--;
        }
        if (vortex.actsOnWorld() && WeatherManager.destructionMode().ripsBlocks() && params.ripShare() > 0.0F) {
            BlockRipper.tick(vortex, storm, context);
        }
    }

    /** Step 1: rolls the wander period, the height fraction and the wander phase of a new vortex, once. */
    private static void seed(VortexState state, RandomSource random) {
        if (state.seeded) {
            return;
        }
        int span = VortexState.MAX_WOBBLE_PERIOD_TICKS - VortexState.MIN_WOBBLE_PERIOD_TICKS;
        state.wobblePeriodTicks = VortexState.MIN_WOBBLE_PERIOD_TICKS + random.nextInt(span + 1);
        state.heightFraction = random.nextFloat();
        state.wobblePhase = random.nextFloat() * TWO_PI;
        state.seeded = true;
    }

    /**
     * Step 2: a grounded tornado below its target tier counts toward its next tier step and, when the step is due,
     * becomes the tornado type one tier up. The family state is kept; the simulator resolves the new type from the
     * next tick on. When that type is missing, disabled or no vortex, the tornado stops growing.
     */
    private static void grow(StormInstance storm, VortexParams params, VortexState state, float descent,
                             ServerLevel level) {
        if (params.kind() != VortexKind.TORNADO || state.targetTier <= params.tier()
            || !VortexMath.grounded(descent)) {
            return;
        }
        state.tierStepTicks++;
        if (state.tierStepTicks < VortexMath.TIER_STEP_TICKS) {
            return;
        }
        state.tierStepTicks = 0;
        ResourceKey<WeatherType> nextKey = WeatherTypes.tornado(params.tier() + 1);
        Optional<Holder.Reference<WeatherType>> next = WeatherRegistries.holder(level.registryAccess(), nextKey);
        if (next.isPresent() && next.get().value().enabled() && next.get().value().vortex().isPresent()) {
            WeatherType nextType = next.get().value();
            storm.retype(nextKey, nextType.radius(), nextType.interestRadius());
        } else {
            state.targetTier = params.tier();
        }
    }

    /**
     * Step 3: a child that follows its parent is led toward its anchor on the parent's flank with a motion request
     * for the next tick; it is never moved directly. A child whose parent is gone, or has left it more than a
     * parent radius behind, starts to fade out, once.
     */
    private static void followParent(StormInstance storm, WeatherType type, VortexParams params, VortexState state,
                                     DimensionWeatherData data) {
        if (!storm.isChild() || !params.kind().followsParent() || storm.isSteered()) {
            return;
        }
        Optional<StormInstance> found = data.find(storm.parentId());
        if (found.isEmpty()) {
            orphan(storm, state);
            return;
        }
        StormInstance parent = found.get();
        double[] anchor = new double[2];
        VortexMath.tornadoAnchor(parent.x(), parent.z(), parent.headingDegrees(), parent.radius(), anchor);
        if (!state.orphaned && VortexMath.lostParent(storm.x(), storm.z(), anchor[0], anchor[1], parent.radius())) {
            orphan(storm, state);
            return;
        }
        float[] motion = new float[2];
        VortexMath.childMotion(storm.x(), storm.z(), storm.headingDegrees(), anchor[0], anchor[1],
            parent.headingDegrees(), parent.speed(), parent.radius(), type.maxSpeed(), motion);
        storm.setMotion(motion[0], motion[1]);
    }

    /** Lets a child that lost its parent rope out instead of vanishing; does nothing the second time. */
    private static void orphan(StormInstance storm, VortexState state) {
        if (!state.orphaned) {
            storm.endSoon();
            state.orphaned = true;
        }
    }

    /**
     * Step 4: keeps the lifting core out of every weather deflector's circle and turns an unsteered vortex that
     * heads into one onto the tangent, at its own speed. A steered vortex is held out by the push alone.
     */
    private static void avoidDeflectors(StormInstance storm, VortexParams params, DimensionWeatherData data) {
        double margin = (double) VortexField.LIFT_RADIUS_FACTOR * params.coreRadius()
            * VortexField.scale(storm.intensity());
        double deflectorRadius = WeatherManager.deflectorRadius();
        List<BlockPos> near = data.deflectors().covering(storm.x(), storm.z(),
            deflectorRadius + margin + DeflectorMath.SLIDE_BAND_BLOCKS);
        if (near.isEmpty()) {
            return;
        }
        double[] out = new double[2];
        if (DeflectorMath.pushOut(storm.x(), storm.z(), margin, near, deflectorRadius, out)) {
            storm.setPosition(out[0], out[1]);
        }
        if (storm.isSteered()) {
            return;
        }
        float heading = DeflectorMath.slideHeading(storm.x(), storm.z(), storm.headingDegrees(), margin, near,
            deflectorRadius);
        if (heading != storm.headingDegrees()) {
            storm.setMotion(heading, storm.speed());
        }
    }

    /**
     * Step 5: samples the ground under the base every {@link GroundFinder#SAMPLE_INTERVAL_TICKS} (and at once for a
     * vortex that has no ground height yet), then moves the tracked height toward the last sample by at most
     * {@link VortexMath#GROUND_FOLLOW_BLOCKS_PER_TICK}. A guessed height does not move: the first real sample
     * replaces it.
     */
    private static void trackGround(StormInstance storm, VortexParams params, VortexState state,
                                    FamilyContext context) {
        if (context.tick() % GroundFinder.SAMPLE_INTERVAL_TICKS == 0 || Double.isNaN(state.groundY)) {
            sampleGround(storm, params, state, context);
        }
        if (!state.groundProvisional) {
            state.groundY = VortexMath.approach(state.groundY, state.groundTargetY,
                VortexMath.GROUND_FOLLOW_BLOCKS_PER_TICK);
        }
    }

    /**
     * One ground sample at the base (the storm centre plus the ground offset of the spine published last), with
     * the guess for a vortex that still has no ground, and the dig reference of a vortex that rips.
     */
    private static void sampleGround(StormInstance storm, VortexParams params, VortexState state,
                                     FamilyContext context) {
        ServerLevel level = context.level();
        ActiveVortex last = context.data().runtime().vortex(storm.id());
        double baseX = last != null ? storm.x() + last.spine().points().get(0).x() : storm.x();
        double baseZ = last != null ? storm.z() + last.spine().points().get(0).z() : storm.z();

        boolean hadGround = !Double.isNaN(state.groundY) && !state.groundProvisional;
        double guess = hadGround ? state.groundY : guessGround(level, baseX, baseZ);
        OptionalInt sample = GroundFinder.groundY(level, Mth.floor(baseX), Mth.floor(baseZ), Mth.floor(guess));
        if (sample.isPresent()) {
            state.groundKnown = true;
            state.groundTargetY = sample.getAsInt();
            if (!hadGround) {
                state.groundY = sample.getAsInt();
                state.groundProvisional = false;
            }
        } else {
            state.groundKnown = false;
            state.groundTargetY = Double.NaN;
            if (Double.isNaN(state.groundY)) {
                state.groundY = guess;
                state.groundProvisional = true;
            }
        }

        if (params.ripShare() > 0.0F && sample.isPresent()) {
            double baseRadius = (double) params.coreRadius() * VortexField.scale(storm.intensity());
            double ahead = VortexMath.DIG_PROBE_RADIUS_FACTOR * baseRadius + VortexMath.DIG_PROBE_MARGIN_BLOCKS;
            double probeX = storm.x() + Headings.directionX(storm.headingDegrees()) * ahead;
            double probeZ = storm.z() + Headings.directionZ(storm.headingDegrees()) * ahead;
            OptionalInt probe = GroundFinder.groundY(level, Mth.floor(probeX), Mth.floor(probeZ),
                Mth.floor(state.groundY));
            double travelled = (double) storm.speed() / SharedConstants.TICKS_PER_SECOND
                * GroundFinder.SAMPLE_INTERVAL_TICKS;
            state.digRefY = VortexMath.digReference(state.digRefY,
                probe.isPresent() ? probe.getAsInt() : Double.NaN, travelled);
            if (Double.isNaN(state.digRefY)) {
                state.digRefY = sample.getAsInt();
            }
        }
    }

    /**
     * Where the ground is assumed while it cannot be sampled: the feet height of the nearest player of the
     * dimension who is not a spectator, or the sea level without one.
     */
    private static double guessGround(ServerLevel level, double x, double z) {
        ServerPlayer nearest = null;
        double nearestSqr = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            double distanceSqr = dx * dx + dz * dz;
            if (distanceSqr < nearestSqr) {
                nearestSqr = distanceSqr;
                nearest = player;
            }
        }
        return nearest != null ? nearest.getY() : level.getSeaLevel();
    }

    /** Step 6: advances the wander of the base, builds this tick's spine and publishes the vortex. */
    private static ActiveVortex publish(StormInstance storm, VortexParams params, VortexState state, float descent,
                                        FamilyContext context) {
        state.wobblePhase = (state.wobblePhase + TWO_PI / Math.max(1, state.wobblePeriodTicks)) % TWO_PI;
        double topY = VortexMath.topY(params, state.groundY, storm.y(), state.heightFraction, storm.intensity());
        FunnelSpine spine = FunnelSpineMath.build(params, storm.intensity(), descent, state.groundY, topY,
            storm.headingDegrees(), storm.speed(), state.wobblePhase, context.tick(), storm.id());
        ActiveVortex vortex = new ActiveVortex(storm.id(), params, storm.x(), storm.z(), spine, state.groundKnown,
            state.digRefY);
        context.data().runtime().putVortex(vortex);
        return vortex;
    }

    /** Step 7, waterspouts: one that has stood over anything but water for long enough ends. */
    private static void endOverLand(StormInstance storm, VortexState state, ActiveVortex vortex, ServerLevel level) {
        double surfaceY = Double.isNaN(state.groundTargetY) ? state.groundY : state.groundTargetY;
        if (GroundFinder.isWater(level, Mth.floor(vortex.baseX()), Mth.floor(surfaceY), Mth.floor(vortex.baseZ()))) {
            state.offSurfaceTicks = 0;
            return;
        }
        if (state.offSurfaceTicks < VortexMath.OFF_SURFACE_TICKS) {
            state.offSurfaceTicks++;
        }
        if (state.offSurfaceTicks >= VortexMath.OFF_SURFACE_TICKS) {
            storm.endSoon();
        }
    }

    /**
     * Step 7, fire whirls: every {@link VortexMath#FIRE_ATTEMPT_INTERVAL_TICKS}, when blocks may change, the
     * {@code vortexFire} option is on and the {@code doFireTick} rule is true (read, never written), tries to
     * light one random column within the base radius. Fire that would never burn out by itself (on a fire source
     * such as netherrack, or soul fire) is placed only while the dimension's temporary fire list takes it, which
     * puts it out again later. Columns at the edge of the loaded world are left alone, because looking at a fire's
     * neighbours there would load a chunk.
     */
    private static void lightFire(VortexState state, ActiveVortex vortex, FamilyContext context) {
        if (context.tick() % VortexMath.FIRE_ATTEMPT_INTERVAL_TICKS != 0) {
            return;
        }
        ServerLevel level = context.level();
        if (!WeatherManager.destructionMode().ripsBlocks() || !WeatherManager.vortexFire()
            || !level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK)) {
            return;
        }
        RandomSource random = context.random();
        double distance = vortex.spine().baseRadius() * Math.sqrt(random.nextFloat());
        double angle = random.nextFloat() * TWO_PI;
        int x = Mth.floor(vortex.baseX() + Math.cos(angle) * distance);
        int z = Mth.floor(vortex.baseZ() + Math.sin(angle) * distance);
        OptionalInt ground = GroundFinder.groundY(level, x, z, Mth.floor(state.groundY));
        if (ground.isEmpty() || Math.abs(ground.getAsInt() - state.groundY) > FIRE_GROUND_TOLERANCE_BLOCKS
            || ProtectedActions.deflected(level, x + 0.5, z + 0.5) || !neighboursLoaded(level, x, z)) {
            return;
        }
        BlockPos pos = new BlockPos(x, ground.getAsInt(), z);
        if (!level.getBlockState(pos).isAir()) {
            return;
        }
        BlockState fire = BaseFireBlock.getState(level, pos);
        if (!fire.canSurvive(level, pos)) {
            return;
        }
        BlockPos below = pos.below();
        boolean burnsForever = !fire.is(Blocks.FIRE)
            || level.getBlockState(below).isFireSource(level, below, Direction.UP);
        if (!burnsForever) {
            ProtectedActions.place(level, pos, fire);
            return;
        }
        TemporaryFires fires = context.data().temporaryFires();
        if (fires.add(pos) && !ProtectedActions.place(level, pos, fire)) {
            fires.remove(pos);
        }
    }

    /** Whether the four columns next to a column are in loaded chunks; never loads one. */
    private static boolean neighboursLoaded(ServerLevel level, int x, int z) {
        ServerChunkCache chunks = level.getChunkSource();
        return chunks.getChunkNow((x - 1) >> 4, z >> 4) != null
            && chunks.getChunkNow((x + 1) >> 4, z >> 4) != null
            && chunks.getChunkNow(x >> 4, (z - 1) >> 4) != null
            && chunks.getChunkNow(x >> 4, (z + 1) >> 4) != null;
    }
}
