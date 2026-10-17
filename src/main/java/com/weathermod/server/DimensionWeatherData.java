package com.weathermod.server;

import com.weathermod.WeatherMod;
import com.weathermod.core.WeatherType;
import com.weathermod.network.StormSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * The weather of one dimension, persisted with the level so storms survive restarts: the storm list, the id
 * counter, the wind and the simulation tick counter.
 */
public final class DimensionWeatherData extends SavedData {
    /** File name under the dimension's {@code data} folder, without extension. */
    public static final String DATA_NAME = "weathermod_weather";
    /** Factory handed to {@code DimensionDataStorage}; no DataFixer type because the format is ours. */
    public static final SavedData.Factory<DimensionWeatherData> FACTORY =
        new SavedData.Factory<>(DimensionWeatherData::new, DimensionWeatherData::load, null);

    private static final String KEY_STORMS = "Storms";
    private static final String KEY_NEXT_STORM_ID = "NextStormId";
    private static final String KEY_WIND = "Wind";
    private static final String KEY_TICK_COUNTER = "TickCounter";

    private final List<StormInstance> storms = new ArrayList<>();
    private final List<StormInstance> stormsView = Collections.unmodifiableList(storms);
    private int nextStormId = 1;
    private WindState wind;
    private int tickCounter;

    /** Creates empty data with a random wind, as used for a dimension that has no saved weather yet. */
    public DimensionWeatherData() {
        this.wind = WindState.random(RandomSource.create());
    }

    /** Returns the weather data of a level, loading or creating it on first access. */
    public static DimensionWeatherData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (StormInstance storm : storms) {
            list.add(storm.save());
        }
        tag.put(KEY_STORMS, list);
        tag.putInt(KEY_NEXT_STORM_ID, nextStormId);
        tag.put(KEY_WIND, wind.save());
        tag.putInt(KEY_TICK_COUNTER, tickCounter);
        return tag;
    }

    /** Reads data written by {@link #save(CompoundTag, HolderLookup.Provider)}; the id counter is repaired if it lags behind the stored storms. */
    public static DimensionWeatherData load(CompoundTag tag, HolderLookup.Provider registries) {
        DimensionWeatherData data = new DimensionWeatherData();
        int highestId = 0;
        ListTag list = tag.getList(KEY_STORMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            StormInstance storm = StormInstance.load(list.getCompound(i));
            data.storms.add(storm);
            highestId = Math.max(highestId, storm.id());
        }
        data.nextStormId = Math.max(Math.max(1, tag.getInt(KEY_NEXT_STORM_ID)), highestId + 1);
        if (tag.contains(KEY_WIND, Tag.TAG_COMPOUND)) {
            data.wind = WindState.load(tag.getCompound(KEY_WIND));
        }
        data.tickCounter = Math.max(0, tag.getInt(KEY_TICK_COUNTER));
        WeatherMod.LOGGER.debug("Loaded {} storm(s) from {}", data.storms.size(), DATA_NAME);
        return data;
    }

    /**
     * Spawns a storm of the given type at a horizontal position and marks the data dirty.
     *
     * @return the new storm, or {@code null} when the dimension already holds the configured maximum
     */
    @Nullable
    public StormInstance spawn(Holder<WeatherType> type, double x, double z, RandomSource random) {
        if (isFull()) {
            return null;
        }
        StormInstance storm = StormInstance.create(nextStormId++, type, x, z, random);
        storms.add(storm);
        setDirty();
        return storm;
    }

    /** Whether {@link #spawn} would be refused because the configured storm cap is reached. */
    public boolean isFull() {
        return storms.size() >= WeatherManager.maxStormsPerDimension();
    }

    /** Removes the storm with the given id. Returns whether one was removed. */
    public boolean remove(int id) {
        Iterator<StormInstance> iterator = storms.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().id() == id) {
                iterator.remove();
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** Removes every expired storm and returns them so callers can notify clients. */
    public List<StormInstance> removeExpired() {
        List<StormInstance> expired = new ArrayList<>();
        Iterator<StormInstance> iterator = storms.iterator();
        while (iterator.hasNext()) {
            StormInstance storm = iterator.next();
            if (storm.isExpired()) {
                iterator.remove();
                expired.add(storm);
            }
        }
        if (!expired.isEmpty()) {
            setDirty();
        }
        return expired;
    }

    /** Removes every storm. The id counter keeps counting so ids are never reused within a world. */
    public void clear() {
        if (!storms.isEmpty()) {
            storms.clear();
            setDirty();
        }
    }

    /** Finds a storm by id. */
    public Optional<StormInstance> find(int id) {
        for (StormInstance storm : storms) {
            if (storm.id() == id) {
                return Optional.of(storm);
            }
        }
        return Optional.empty();
    }

    /** Read-only view of the live storms, in spawn order. */
    public List<StormInstance> storms() {
        return stormsView;
    }

    /** Snapshots of the storms whose interest radius covers the given horizontal position. */
    public List<StormSnapshot> snapshotsNear(double x, double z) {
        List<StormSnapshot> result = new ArrayList<>();
        for (StormInstance storm : storms) {
            if (storm.isWithinInterest(x, z)) {
                result.add(storm.toSnapshot());
            }
        }
        return result;
    }

    /** The dimension's wind. Mutating it is allowed; the simulator persists it every tick. */
    public WindState wind() {
        return wind;
    }

    /** The id the next spawned storm will receive. */
    public int nextStormId() {
        return nextStormId;
    }

    /** Number of simulation ticks this dimension has run. */
    public int tickCounter() {
        return tickCounter;
    }

    /** Increments the tick counter and returns the new value. Wraps back to zero before it can overflow. */
    public int advanceTick() {
        if (tickCounter == Integer.MAX_VALUE) {
            tickCounter = 0;
        }
        return ++tickCounter;
    }
}
