package com.weathermod.server;

import com.weathermod.config.CommonConfig;
import com.weathermod.config.ServerConfig;
import com.weathermod.core.WeatherType;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.WeatherNetwork;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Entry point for everything outside the simulation that wants to query or change a dimension's weather.
 * Wraps config lookups with safe defaults and pairs storm changes with the network messages they require.
 */
public final class WeatherManager {
    /** Sync interval used before the server config has loaded. */
    public static final int DEFAULT_SYNC_INTERVAL_TICKS = 10;
    /** Storm cap used before the server config has loaded. */
    public static final int DEFAULT_MAX_STORMS_PER_DIMENSION = 8;

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
