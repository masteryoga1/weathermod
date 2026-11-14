package com.weathermod.server.director;

import com.weathermod.WeatherMod;
import com.weathermod.core.Trigger;
import com.weathermod.core.TriggerContext;
import com.weathermod.core.VortexParams;
import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.WeatherManager;
import com.weathermod.server.WindState;
import com.weathermod.server.climate.ClimateField;
import com.weathermod.server.climate.ClimateSample;
import com.weathermod.server.family.FamilySystems;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Decides when and where natural storms form and when they fade: every {@link #INTERVAL_TICKS} it keeps the
 * severe-weather cooldown running, dissipates storms nobody has been near for a while, bends the heading of
 * wind-driven storms toward the nearest player, and when its timer runs out spawns a type that fits the climate,
 * the wind, the time of day and the front upwind of the least covered player. Also ends the rain over players who
 * slept through the night.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class StormDirector {
    /** Simulation ticks between director passes. */
    public static final int INTERVAL_TICKS = 100;
    /** Largest turn a wind-driven storm's heading gets toward the nearest player, in degrees. */
    public static final float HEADING_BIAS_DEGREES = 20.0F;
    /** Ticks without a player near a storm before it is told to ramp down. */
    public static final int UNSEEN_TICKS_BEFORE_DISSIPATION = 2400;
    /** A storm counts as seen while some player is within this many interest radii of its centre. */
    public static final float UNSEEN_INTEREST_FACTOR = 1.5F;
    /** Interest radius used for the provisional plan that samples the climate before a type is chosen. */
    public static final float PLANNING_INTEREST_RADIUS = 640.0F;

    private StormDirector() {
    }

    /**
     * One director pass; called by the simulator every tick and returning at once unless {@code tick} is a multiple
     * of {@link #INTERVAL_TICKS}. The severe-weather cooldown is kept running on every pass: while a severe storm
     * lives, natural or spawned by command, the mark from which the next one may form is pushed ahead, so the
     * cooldown counts from the moment the last severe storm ends. Beyond that the pass does nothing while paused,
     * without players, or with a storm frequency of zero.
     */
    public static void tick(ServerLevel level, DimensionWeatherData data, int tick) {
        if (tick % INTERVAL_TICKS != 0) {
            return;
        }
        DirectorState state = data.director();
        state.severeReadyGameTime = SevereCooldown.update(state.severeReadyGameTime, level.getGameTime(),
            severeAlive(level, data), WeatherManager.severeWeatherCooldownSeconds());
        if (state.paused) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        double frequency = WeatherManager.stormFrequency();
        if (!(frequency > 0.0D)) {
            return;
        }

        WindState wind = data.wind();
        tendStorms(data, players, wind);

        state.ticksUntilAttempt -= INTERVAL_TICKS;
        if (state.ticksUntilAttempt <= 0) {
            state.rearm(WeatherManager.naturalSpawnIntervalSeconds(), frequency, level.getRandom());
            attemptSpawn(level, data, state, players, wind, null, null);
        }
        data.setDirty();
    }

    /**
     * Spawns a storm through the natural planner right now, anchored on a player: the timer is ignored but the storm
     * cap still applies. With a type the storm is that type whatever the climate, the type's trigger and the
     * severe-weather cooldown say; without one the type is picked exactly as a natural spawn picks it, and the
     * result is empty when nothing fits.
     *
     * @return the new storm, or empty when the dimension is full or no type fits
     */
    public static Optional<StormInstance> forceSpawn(ServerLevel level, ServerPlayer anchor,
                                                     @Nullable Holder<WeatherType> type) {
        DimensionWeatherData data = WeatherManager.get(level);
        return attemptSpawn(level, data, data.director(), level.players(), data.wind(), anchor, type);
    }

    /** Pauses or resumes the director in a level. A paused director neither spawns nor steers nor dissipates. */
    public static void setPaused(ServerLevel level, boolean paused) {
        DimensionWeatherData data = WeatherManager.get(level);
        DirectorState state = data.director();
        if (state.paused != paused) {
            state.paused = paused;
            data.setDirty();
        }
    }

    /** Whether the director is paused in a level. */
    public static boolean isPaused(ServerLevel level) {
        return WeatherManager.get(level).director().paused;
    }

    /** Whole seconds until the next spawn attempt in a level, rounded up; zero when one is due. */
    public static int secondsUntilNextAttempt(ServerLevel level) {
        return WeatherManager.get(level).director().secondsUntilAttempt();
    }

    /**
     * Whole seconds until a severe storm may form naturally in a level, rounded up; zero exactly when one may form
     * now. While a severe storm lives this is the whole configured cooldown, and at least one second, because the
     * cooldown only starts to count down when that storm ends.
     */
    public static int severeCooldownSeconds(ServerLevel level) {
        DimensionWeatherData data = WeatherManager.get(level);
        long now = level.getGameTime();
        boolean alive = severeAlive(level, data);
        long ready = SevereCooldown.update(data.director().severeReadyGameTime, now, alive,
            WeatherManager.severeWeatherCooldownSeconds());
        int seconds = SevereCooldown.secondsLeft(ready, now);
        return alive ? Math.max(1, seconds) : seconds;
    }

    /**
     * The front lying over a horizontal position in a level, as the director sees it when it picks a type there:
     * {@code ""} for none, {@link Trigger#FRONT_COLD} or {@link Trigger#FRONT_WARM}. A dimension whose climate does
     * not follow the sky has no fronts.
     */
    public static String frontAt(ServerLevel level, double x, double z) {
        return frontAt(level, WeatherManager.get(level), x, z);
    }

    /**
     * Ends, by ramping them down, every precipitating storm that covers a sleeping player, so the night a player
     * skips takes its rain with it. Does nothing unless {@code WeatherManager.endStormsOnSleep()} is on.
     */
    public static void onSleepFinished(ServerLevel level) {
        if (!WeatherManager.endStormsOnSleep()) {
            return;
        }
        DimensionWeatherData data = WeatherManager.get(level);
        if (data.storms().isEmpty()) {
            return;
        }
        List<ServerPlayer> sleepers = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSleeping()) {
                sleepers.add(player);
            }
        }
        if (sleepers.isEmpty()) {
            return;
        }

        int ended = 0;
        for (StormInstance storm : data.storms()) {
            if (!WeatherManager.type(level, storm).map(WeatherType::precipitates).orElse(false)) {
                continue;
            }
            for (ServerPlayer sleeper : sleepers) {
                if (covers(storm, sleeper.getX(), sleeper.getZ())) {
                    storm.endSoon();
                    ended++;
                    break;
                }
            }
        }
        if (ended > 0) {
            data.setDirty();
            WeatherMod.LOGGER.debug("Ending {} storm(s) over sleeping players in {}", ended,
                level.dimension().location());
        }
    }

    @SubscribeEvent
    public static void onSleepFinishedTime(SleepFinishedTimeEvent event) {
        if (event.getLevel() instanceof ServerLevel level && WeatherManager.isEnabled(level)) {
            onSleepFinished(level);
        }
    }

    /**
     * Dissipation and heading bias for every wind-driven storm: a storm with no player within
     * {@link #UNSEEN_INTEREST_FACTOR} interest radii accumulates unseen ticks and is told to end once they reach
     * {@link #UNSEEN_TICKS_BEFORE_DISSIPATION}; a storm with a player near resets them. The heading bias turns the
     * storm toward the nearest player by at most {@link #HEADING_BIAS_DEGREES}.
     */
    private static void tendStorms(DimensionWeatherData data, List<ServerPlayer> players, WindState wind) {
        float windDirection = wind.directionDegrees();
        for (StormInstance storm : data.storms()) {
            if (storm.isSteered()) {
                continue;
            }
            ServerPlayer nearest = nearestPlayer(players, storm.x(), storm.z());
            if (nearest == null) {
                storm.setTicksUnseen(storm.ticksUnseen() + INTERVAL_TICKS);
                storm.setHeadingBias(0.0F);
            } else {
                double limit = storm.interestRadius() * (double) UNSEEN_INTEREST_FACTOR;
                boolean seen = storm.distanceToSqr(nearest.getX(), nearest.getZ()) <= limit * limit;
                storm.setTicksUnseen(seen ? 0 : storm.ticksUnseen() + INTERVAL_TICKS);
                float biased = SpawnPlanner.biasHeading(windDirection, storm.x(), storm.z(), nearest.getX(),
                    nearest.getZ(), HEADING_BIAS_DEGREES);
                storm.setHeadingBias(SpawnPlanner.signedDifference(windDirection, biased));
            }
            if (storm.ticksUnseen() >= UNSEEN_TICKS_BEFORE_DISSIPATION) {
                storm.endSoon();
            }
        }
    }

    /**
     * The front at a position from the temperature anomaly {@link FrontMath#SAMPLE_OFFSET_BLOCKS} upwind and
     * downwind of it, along the dimension's wind at this moment.
     */
    private static String frontAt(ServerLevel level, DimensionWeatherData data, double x, double z) {
        if (!ClimateField.followsSky(level.dimensionType())) {
            return "";
        }
        ClimateField field = data.climate(level);
        float windDirection = data.wind().directionDegrees();
        long gameTime = level.getGameTime();
        float upwind = field.temperatureAnomaly(FrontMath.upwindX(x, windDirection),
            FrontMath.upwindZ(z, windDirection), gameTime);
        float downwind = field.temperatureAnomaly(FrontMath.downwindX(x, windDirection),
            FrontMath.downwindZ(z, windDirection), gameTime);
        return FrontMath.frontAt(upwind, downwind);
    }

    /** Whether a storm lives in the dimension whose type, as far as it can be resolved, counts as severe weather. */
    private static boolean severeAlive(ServerLevel level, DimensionWeatherData data) {
        if (data.storms().isEmpty()) {
            return false;
        }
        Optional<Registry<WeatherType>> registry = WeatherRegistries.optionalRegistry(level.registryAccess());
        if (registry.isEmpty()) {
            return false;
        }
        Registry<WeatherType> types = registry.get();
        for (StormInstance storm : data.storms()) {
            WeatherType type = types.get(storm.type());
            if (type != null && type.severe()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The shared spawn path of the timer and {@link #forceSpawn}: respects the storm cap, anchors on the given
     * player or the least covered one, picks a type at a provisional upwind position unless one is forced, re-plans
     * with that type's interest radius and spawns through {@code WeatherManager.spawnNatural}.
     *
     * <p>A type with a vortex block is small: planned as far upwind as other storms it would live and die unseen.
     * It is planned within sight of the anchor instead, see {@link SpawnPlanner#nearUpwind}. Because that position
     * differs from the provisional one a picked type was picked at, the type's biome rule is checked again there
     * and the attempt is given up when it fails, so a waterspout planned from mid-ocean does not land on a beach.
     */
    private static Optional<StormInstance> attemptSpawn(ServerLevel level, DimensionWeatherData data,
                                                        DirectorState state, List<ServerPlayer> players,
                                                        WindState wind, @Nullable ServerPlayer anchorOverride,
                                                        @Nullable Holder<WeatherType> forcedType) {
        if (data.isFull() || data.naturalStormCount() >= WeatherManager.maxStormsPerDimension()) {
            return Optional.empty();
        }
        RandomSource random = level.getRandom();
        ServerPlayer anchor = anchorOverride != null
            ? anchorOverride
            : leastCoveredPlayer(players, data.storms(), random);
        if (anchor == null) {
            return Optional.empty();
        }
        float windDirection = wind.directionDegrees();
        float driftSpeed = wind.speed() * WeatherManager.stormDriftFactor();

        Holder<WeatherType> type = forcedType;
        if (type == null) {
            SpawnPlanner.Plan provisional = SpawnPlanner.upwind(anchor.getX(), anchor.getZ(), windDirection,
                PLANNING_INTEREST_RADIUS, 0.0F, driftSpeed, random);
            Optional<Holder<WeatherType>> picked = pickType(level, data, state, wind, provisional, random);
            if (picked.isEmpty()) {
                return Optional.empty();
            }
            type = picked.get();
        }

        SpawnPlanner.Plan plan;
        Optional<VortexParams> vortex = type.value().vortex();
        if (vortex.isPresent()) {
            plan = SpawnPlanner.nearUpwind(anchor.getX(), anchor.getZ(), windDirection,
                vortex.get().influenceRadius(), random);
            if (forcedType == null && !type.value().allowsBiome(biomeAt(level, plan.x(), plan.z()))) {
                return Optional.empty();
            }
        } else {
            plan = SpawnPlanner.upwind(anchor.getX(), anchor.getZ(), windDirection,
                type.value().interestRadius(), type.value().radius(), driftSpeed, random);
        }
        StormInstance storm = WeatherManager.spawnNatural(level, type, plan.x(), plan.z());
        if (storm == null) {
            return Optional.empty();
        }
        state.recordSpawn(level.getGameTime());
        data.setDirty();
        WeatherMod.LOGGER.debug("Director spawned {} storm {} at ({}, {}) upwind of {} in {}",
            storm.typeId(), storm.id(), Math.round(plan.x()), Math.round(plan.z()),
            anchor.getScoreboardName(), level.dimension().location());
        return Optional.of(storm);
    }

    /**
     * Picks a natural type for a planned position: the climate is sampled there, the biome is read at sea level
     * there without loading a chunk, the trigger context is built there from the gust speed, the clock and the
     * front, and every natural type that passes {@link TypeSelector#eligible} enters a pick weighted by the type's
     * spawn weight as its trigger scales it. The gust speed is the sustained speed when no gust blows. Severe types
     * only pass while no severe storm lives and the severe-weather cooldown has run out.
     */
    private static Optional<Holder<WeatherType>> pickType(ServerLevel level, DimensionWeatherData data,
                                                          DirectorState state, WindState wind,
                                                          SpawnPlanner.Plan plan, RandomSource random) {
        ClimateSample climate = data.climate(level).sample(level, plan.x(), plan.z());
        Holder<Biome> biome = biomeAt(level, plan.x(), plan.z());
        Set<WeatherFamily> supported = FamilySystems.supported();
        boolean day = TriggerContext.isDay(level.getDayTime(), level.dimensionType().hasFixedTime());
        TriggerContext context = new TriggerContext(wind.gustSpeed(), day, frontAt(level, data, plan.x(), plan.z()));
        boolean severeAllowed = SevereCooldown.allows(state.severeReadyGameTime, level.getGameTime(),
            severeAlive(level, data));

        List<TypeSelector.Candidate<Holder<WeatherType>>> candidates = new ArrayList<>();
        WeatherRegistries.naturalTypes(level.registryAccess()).forEach(holder -> {
            WeatherType type = holder.value();
            if (TypeSelector.eligible(type, level.dimension(), biome, climate, supported, context, severeAllowed)) {
                candidates.add(new TypeSelector.Candidate<Holder<WeatherType>>(holder,
                    type.trigger().weight(type.weight(), context)));
            }
        });
        return TypeSelector.pick(candidates, random);
    }

    /** The biome at sea level at a horizontal position, read without loading a chunk. */
    private static Holder<Biome> biomeAt(ServerLevel level, double x, double z) {
        int seaLevel = level.getChunkSource().getGenerator().getSeaLevel();
        return level.getBiome(BlockPos.containing(x, seaLevel, z));
    }

    /** The player closest to a horizontal position, or {@code null} when the list is empty. */
    @Nullable
    private static ServerPlayer nearestPlayer(List<ServerPlayer> players, double x, double z) {
        ServerPlayer nearest = null;
        double nearestSqr = Double.POSITIVE_INFINITY;
        for (ServerPlayer player : players) {
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            double distanceSqr = dx * dx + dz * dz;
            if (distanceSqr < nearestSqr) {
                nearestSqr = distanceSqr;
                nearest = player;
            }
        }
        return nearest;
    }

    /**
     * The player with the fewest storms covering them, ties broken at random, or {@code null} when the list is
     * empty. Spreads new storms over everyone online instead of piling them on one player.
     */
    @Nullable
    private static ServerPlayer leastCoveredPlayer(List<ServerPlayer> players, List<StormInstance> storms,
                                                   RandomSource random) {
        List<ServerPlayer> best = new ArrayList<>();
        int fewest = Integer.MAX_VALUE;
        for (ServerPlayer player : players) {
            int covering = coveringStormCount(storms, player.getX(), player.getZ());
            if (covering < fewest) {
                fewest = covering;
                best.clear();
            }
            if (covering == fewest) {
                best.add(player);
            }
        }
        if (best.isEmpty()) {
            return null;
        }
        return best.size() == 1 ? best.get(0) : best.get(random.nextInt(best.size()));
    }

    /** How many storms cover a horizontal position. */
    private static int coveringStormCount(List<StormInstance> storms, double x, double z) {
        int count = 0;
        for (StormInstance storm : storms) {
            if (covers(storm, x, z)) {
                count++;
            }
        }
        return count;
    }

    /** Whether a horizontal position lies inside the storm's effect radius. */
    private static boolean covers(StormInstance storm, double x, double z) {
        double radius = storm.radius();
        return radius > 0.0D && storm.distanceToSqr(x, z) <= radius * radius;
    }
}
