package com.weathermod.server.family;

import com.weathermod.core.ConvectiveParams;
import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.core.WeatherTypes;
import com.weathermod.network.DownburstPayload;
import com.weathermod.network.WeatherNetwork;
import com.weathermod.server.ChildSpawn;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.StormRuntime;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.impact.ActiveDownburst;
import com.weathermod.server.impact.DepositionSystem;
import com.weathermod.server.impact.HailSystem;
import com.weathermod.server.impact.LightningStrikes;
import com.weathermod.server.impact.StrikeTargeting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The convective family: thunderstorms, hailstorms, squall lines, derechos and supercells. On top of the rain the
 * bridge switches on, a convective storm lays snow where it is cold, strikes lightning, hails in bursts, throws
 * downbursts and spawns its children: the tornado of a supercell and the small vortices along a gust front.
 *
 * <p>Every step runs in every destruction mode. The mode gates the effects where they act (lightning, hail, the
 * forces, the ripper), never the simulation, so a storm that is only shown still flashes, hails and spins up its
 * funnel.
 */
public final class ConvectiveSystem implements FamilySystem {
    /** A storm spawns no further small vortex while this many of its children are alive. */
    private static final int MAX_LIVE_CHILDREN_FOR_SMALL_VORTEX = 2;

    /** Creates the system; it keeps no state of its own. */
    public ConvectiveSystem() {
    }

    @Override
    public WeatherFamily family() {
        return WeatherFamily.CONVECTIVE;
    }

    /**
     * One tick of a convective storm, in this order: deposition as the precipitation family does it, lightning,
     * hail, downbursts, the tornado roll and small vortices. A type without a convective block behaves as
     * {@link ConvectiveParams#NONE}: it only rains.
     */
    @Override
    public void tick(StormInstance storm, WeatherType type, FamilyContext context) {
        ConvectiveParams params = type.convective().orElse(ConvectiveParams.NONE);
        ConvectiveState state = storm.familyState(ConvectiveState.class, ConvectiveState::load);
        tickDeposition(storm, type, context);
        tickLightning(storm, params, state, context);
        tickHail(storm, params, state, context);
        tickDownbursts(storm, params, context);
        tickTornado(storm, params, state, context);
        tickSmallVortices(storm, params, context);
    }

    /**
     * Starts a downburst now: adds an {@link ActiveDownburst} of {@link ActiveDownburst#DURATION_TICKS} to the
     * dimension's runtime and sends a {@link DownburstPayload} to the players near it. Finished downbursts are
     * pruned on the way, so the list also stays short when only commands add to it. A downburst without a
     * position, a radius or a speed is not started.
     *
     * @param radius radius in blocks
     * @param speed  peak outflow in blocks per second
     */
    public static void startDownburst(ServerLevel level, double x, double z, float radius, float speed) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !(radius > 0.0F) || !(speed > 0.0F)) {
            return;
        }
        StormRuntime runtime = DimensionWeatherData.get(level).runtime();
        int now = runtime.tick();
        pruneDownbursts(runtime.downbursts(), now);
        runtime.downbursts().add(new ActiveDownburst(x, z, radius, speed, now, ActiveDownburst.DURATION_TICKS));
        WeatherNetwork.sendDownburst(level,
            new DownburstPayload(x, z, radius, speed, ActiveDownburst.DURATION_TICKS));
    }

    private static void tickDeposition(StormInstance storm, WeatherType type, FamilyContext context) {
        if (context.tick() % PrecipitationSystem.DEPOSITION_INTERVAL_TICKS != 0) {
            return;
        }
        if (!type.precipitates() || storm.intensity() <= 0.0F) {
            return;
        }
        if (!WeatherManager.depositionEnabled()) {
            return;
        }
        DepositionSystem.deposit(storm, type, context, WeatherManager.depositionBlocksPerTick());
    }

    private static void tickLightning(StormInstance storm, ConvectiveParams params, ConvectiveState state,
                                      FamilyContext context) {
        if (!context.level().dimensionType().hasSkyLight()) {
            return;
        }
        boolean strikes = ConvectiveMath.advanceStrike(state, params.lightningPerMinute(), storm.intensity(),
            WeatherManager.lightningFrequency(), context.random());
        if (strikes) {
            LightningStrikes.strikeFromStorm(storm, params, context);
        }
    }

    private static void tickHail(StormInstance storm, ConvectiveParams params, ConvectiveState state,
                                 FamilyContext context) {
        boolean burst = ConvectiveMath.advanceHail(state, params, context.random());
        storm.setHail(ConvectiveMath.hailStrength(params.hail(), storm.intensity(), burst));
        HailSystem.tick(storm, params, context);
    }

    private static void tickDownbursts(StormInstance storm, ConvectiveParams params, FamilyContext context) {
        RandomSource random = context.random();
        float chance = ConvectiveMath.chancePerTick(params.downburstsPerMinute(), storm.intensity(), 1.0);
        if (chance > 0.0F && random.nextFloat() < chance) {
            double[] spot = new double[2];
            StrikeTargeting.pick(storm.x(), storm.z(), storm.radius(), playersInside(context.level(), storm),
                random, spot);
            startDownburst(context.level(), spot[0], spot[1], params.downburstRadius(),
                params.downburstSpeed() * storm.intensity());
        }
        pruneDownbursts(context.data().runtime().downbursts(), context.tick());
    }

    private static void tickTornado(StormInstance storm, ConvectiveParams params, ConvectiveState state,
                                    FamilyContext context) {
        if (!(params.tornadoChance() > 0.0F)) {
            return;
        }
        if (state.tornadoRollFailed || state.tornadoesSpawned >= ConvectiveMath.MAX_TORNADOES_PER_STORM) {
            return;
        }
        ServerLevel level = context.level();
        double[] spot = new double[2];
        VortexMath.tornadoAnchor(storm.x(), storm.z(), storm.headingDegrees(), storm.radius(), spot);
        double nearestPlayerBlocks = nearestPlayerBlocks(level, spot[0], spot[1]);
        if (!ConvectiveMath.tornadoRollDue(state, storm.ageTicks(), storm.intensity(), storm.remainingTicks(),
                context.data().childCount(storm.id()), nearestPlayerBlocks)) {
            return;
        }
        RandomSource random = context.random();
        float chance = params.tornadoChance();
        if (state.tornadoesSpawned == 1) {
            chance *= ConvectiveMath.SECOND_TORNADO_CHANCE_FACTOR;
        }
        if (!TornadoRoll.happens(chance, WeatherManager.tornadoFrequency(), random)) {
            state.tornadoRollFailed = true;
            return;
        }
        int targetTier = TornadoRoll.tier(random, Math.min(params.maxTornadoTier(), WeatherManager.maxTornadoTier()));
        int delay = Mth.nextInt(random, VortexMath.TOUCHDOWN_DELAY_MIN_TICKS, VortexMath.TOUCHDOWN_DELAY_MAX_TICKS);
        Holder<WeatherType> targetType =
            WeatherRegistries.holder(level.registryAccess(), WeatherTypes.tornado(targetTier)).orElse(null);
        int lifetime = targetType == null ? 0 : delay + targetType.value().pickDurationTicks(random);
        context.data().queueChild(new ChildSpawn(storm.id(), WeatherTypes.tornado(0), spot[0], spot[1], lifetime,
            VortexState.forNaturalTornado(targetTier, delay).save()));
        state.tornadoesSpawned++;
    }

    private static void tickSmallVortices(StormInstance storm, ConvectiveParams params, FamilyContext context) {
        if (params.smallVortex().isEmpty()) {
            return;
        }
        ResourceLocation vortexType = params.smallVortex().get();
        RandomSource random = context.random();
        float chance = ConvectiveMath.chancePerTick(params.smallVorticesPerMinute(), storm.intensity(),
            WeatherManager.tornadoFrequency());
        if (!(chance > 0.0F) || random.nextFloat() >= chance) {
            return;
        }
        if (context.data().childCount(storm.id()) >= MAX_LIVE_CHILDREN_FOR_SMALL_VORTEX) {
            return;
        }
        double[] spot = new double[2];
        ConvectiveMath.leadingEdgeSpot(storm.x(), storm.z(), storm.headingDegrees(), storm.radius(), random, spot);
        context.data().queueChild(new ChildSpawn(storm.id(), WeatherRegistries.key(vortexType), spot[0], spot[1]));
    }

    private static void pruneDownbursts(List<ActiveDownburst> downbursts, int now) {
        if (!downbursts.isEmpty()) {
            downbursts.removeIf(downburst -> downburst.isOver(now));
        }
    }

    /** The positions of the living players who are not spectators and stand inside the storm's radius. */
    private static List<Vec3> playersInside(ServerLevel level, StormInstance storm) {
        List<Vec3> inside = new ArrayList<>();
        double radiusSqr = (double) storm.radius() * storm.radius();
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && !player.isSpectator()
                    && storm.distanceToSqr(player.getX(), player.getZ()) <= radiusSqr) {
                inside.add(player.position());
            }
        }
        return inside;
    }

    /** Horizontal distance from a spot to the nearest player who is not a spectator; MAX_VALUE without one. */
    private static double nearestPlayerBlocks(ServerLevel level, double x, double z) {
        double nearestSqr = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            nearestSqr = Math.min(nearestSqr, dx * dx + dz * dz);
        }
        return nearestSqr == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(nearestSqr);
    }
}
