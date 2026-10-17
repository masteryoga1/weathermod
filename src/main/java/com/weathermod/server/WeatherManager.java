package com.weathermod.server;

import com.weathermod.config.CommonConfig;
import com.weathermod.config.DestructionMode;
import com.weathermod.config.ServerConfig;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.WeatherNetwork;
import com.weathermod.server.climate.ClimateField;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Entry point for everything outside the simulation that wants to query or change a dimension's weather.
 * Wraps config lookups with safe defaults for before the server config loads and pairs storm changes with the
 * network messages they require.
 */
public final class WeatherManager {
    /** Sync interval used before the server config has loaded. */
    public static final int DEFAULT_SYNC_INTERVAL_TICKS = 10;
    /** Storm cap used before the server config has loaded. */
    public static final int DEFAULT_MAX_STORMS_PER_DIMENSION = 8;
    /** Natural spawn frequency multiplier used before the server config has loaded. */
    public static final double DEFAULT_STORM_FREQUENCY = 1.0D;
    /** Seconds between natural spawn attempts at frequency one, used before the server config has loaded. */
    public static final int DEFAULT_NATURAL_SPAWN_INTERVAL_SECONDS = 240;
    /** Whether the vanilla rain bridge is on before the server config has loaded. */
    public static final boolean DEFAULT_VANILLA_WEATHER_BRIDGE = true;
    /** Whether snow storms lay snow before the server config has loaded. */
    public static final boolean DEFAULT_SNOW_ACCUMULATION = true;
    /** Whether snow storms freeze water before the server config has loaded. */
    public static final boolean DEFAULT_ICE_FORMATION = true;
    /** Deposition attempts per precipitation storm per tick before the server config has loaded. */
    public static final int DEFAULT_DEPOSITION_BLOCKS_PER_TICK = 4;
    /** Length of the built-in seasonal year in days before the server config has loaded. */
    public static final int DEFAULT_BUILT_IN_YEAR_LENGTH_DAYS = 28;
    /** Whether sleeping ends the rain before the server config has loaded. */
    public static final boolean DEFAULT_END_STORMS_ON_SLEEP = true;
    /** Destruction mode assumed before the server config has loaded. */
    public static final DestructionMode DEFAULT_DESTRUCTION_MODE = DestructionMode.FULL;

    private WeatherManager() {
    }

    /** Whether the weather simulation runs in this level, per {@code CommonConfig.enabledDimensions}. */
    public static boolean isEnabled(ServerLevel level) {
        return CommonConfig.isDimensionEnabled(level.dimension());
    }

    /** The level's weather data, created on first access. */
    public static DimensionWeatherData get(ServerLevel level) {
        return DimensionWeatherData.get(level);
    }

    /** The level's climate field, built on first access, see {@link DimensionWeatherData#climate(ServerLevel)}. */
    public static ClimateField climate(ServerLevel level) {
        return get(level).climate(level);
    }

    /** Ticks between storm delta packets, from the server config or the default before it loads. */
    public static int syncIntervalTicks() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.syncIntervalTicks.get() : DEFAULT_SYNC_INTERVAL_TICKS;
    }

    /** Maximum simultaneous storms in one dimension, from the server config or the default before it loads. */
    public static int maxStormsPerDimension() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.maxStormsPerDimension.get()
            : DEFAULT_MAX_STORMS_PER_DIMENSION;
    }

    /** Multiplier on the natural spawn rate; zero disables natural storms. From the config or the default before it loads. */
    public static double stormFrequency() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.stormFrequency.get() : DEFAULT_STORM_FREQUENCY;
    }

    /** Seconds between natural spawn attempts at frequency one, from the config or the default before it loads. */
    public static int naturalSpawnIntervalSeconds() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.naturalSpawnIntervalSeconds.get()
            : DEFAULT_NATURAL_SPAWN_INTERVAL_SECONDS;
    }

    /** Whether vanilla rain and thunder follow the storms, from the config or the default before it loads. */
    public static boolean vanillaWeatherBridge() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.vanillaWeatherBridge.get()
            : DEFAULT_VANILLA_WEATHER_BRIDGE;
    }

    /** Whether snow storms lay snow layers, from the config or the default before it loads. */
    public static boolean snowAccumulation() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.snowAccumulation.get() : DEFAULT_SNOW_ACCUMULATION;
    }

    /** Whether snow storms freeze still water, from the config or the default before it loads. */
    public static boolean iceFormation() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.iceFormation.get() : DEFAULT_ICE_FORMATION;
    }

    /** Deposition attempts per precipitation storm per tick, from the config or the default before it loads. */
    public static int depositionBlocksPerTick() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.depositionBlocksPerTick.get()
            : DEFAULT_DEPOSITION_BLOCKS_PER_TICK;
    }

    /** Days in the built-in seasonal year, zero disabling it, from the config or the default before it loads. */
    public static int builtInYearLengthDays() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.builtInYearLengthDays.get()
            : DEFAULT_BUILT_IN_YEAR_LENGTH_DAYS;
    }

    /** Whether sleeping through the night ends the rain over the sleepers, from the config or the default before it loads. */
    public static boolean endStormsOnSleep() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.endStormsOnSleep.get() : DEFAULT_END_STORMS_ON_SLEEP;
    }

    /** How much storms may change the world, from the config or the default before it loads. */
    public static DestructionMode destructionMode() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.destructionMode.get() : DEFAULT_DESTRUCTION_MODE;
    }

    /**
     * Whether precipitation storms may lay snow or freeze water: the destruction mode is not
     * {@link DestructionMode#VISUAL_ONLY} and at least one of the two deposition toggles is on.
     */
    public static boolean depositionEnabled() {
        return destructionMode() != DestructionMode.VISUAL_ONLY && (snowAccumulation() || iceFormation());
    }

    /**
     * Spawns a storm, pins its peak intensity when one is given, and immediately sends it to every player within
     * its interest radius.
     *
     * @param peakIntensity the peak intensity to pin, or {@code null} to keep the one rolled from the type
     * @return the new storm, or {@code null} when the dimension is full
     */
    @Nullable
    public static StormInstance spawn(ServerLevel level, Holder<WeatherType> type, double x, double z,
                                      @Nullable Float peakIntensity) {
        StormInstance storm = get(level).spawn(type, x, z, level.getRandom());
        if (storm != null) {
            if (peakIntensity != null) {
                storm.setPeakIntensity(peakIntensity);
            }
            sync(level, storm);
        }
        return storm;
    }

    /**
     * Spawns a storm on behalf of the director: it is marked natural, so it counts toward the natural storm cap,
     * and sent at once to every player within its interest radius.
     *
     * @return the new storm, or {@code null} when the dimension is full
     */
    @Nullable
    public static StormInstance spawnNatural(ServerLevel level, Holder<WeatherType> type, double x, double z) {
        StormInstance storm = get(level).spawn(type, x, z, level.getRandom());
        if (storm != null) {
            storm.markNatural();
            sync(level, storm);
        }
        return storm;
    }

    /** Resolves a storm's type in the level's registry; empty when the type is unknown, as after a datapack change. */
    public static Optional<WeatherType> type(ServerLevel level, StormInstance storm) {
        return WeatherRegistries.optionalRegistry(level.registryAccess())
            .map(registry -> registry.get(storm.type()));
    }

    /**
     * Sends a changed storm to every player within its interest radius right away, clears its dirty flag and marks
     * the dimension's data for saving. Every storm change made outside the simulator's own tick goes through here.
     */
    public static void sync(ServerLevel level, StormInstance storm) {
        WeatherNetwork.sendStormToNearby(level, storm);
        storm.clearDirty();
        get(level).setDirty();
    }

    /** Removes a storm and tells every player in the dimension to drop it. Returns whether it existed. */
    public static boolean remove(ServerLevel level, int id) {
        if (!get(level).remove(id)) {
            return false;
        }
        WeatherNetwork.sendToDimension(level, new StormRemovedPayload(id));
        return true;
    }

    /** Removes every storm in the dimension, notifying players of each. Returns how many were removed. */
    public static int clear(ServerLevel level) {
        DimensionWeatherData data = get(level);
        List<Integer> ids = new ArrayList<>(data.storms().size());
        for (StormInstance storm : data.storms()) {
            ids.add(storm.id());
        }
        data.clear();
        for (int id : ids) {
            WeatherNetwork.sendToDimension(level, new StormRemovedPayload(id));
        }
        return ids.size();
    }
}
