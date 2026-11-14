package com.weathermod.server;

import com.weathermod.WeatherMod;
import com.weathermod.block.DeflectorIndex;
import com.weathermod.core.WeatherType;
import com.weathermod.network.StormSnapshot;
import com.weathermod.server.climate.ClimateField;
import com.weathermod.server.climate.SeasonProviders;
import com.weathermod.server.director.DirectorState;
import com.weathermod.server.impact.TemporaryFires;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
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
 * counter, the wind, the storm director's state, the simulation tick counter, the positions of the weather
 * deflectors and the fires the mod must put out again. The climate field is derived from the level and the config,
 * so it is built on first use instead of being saved. The {@link StormRuntime} and the queue of children waiting to
 * be spawned are transient and never saved.
 */
public final class DimensionWeatherData extends SavedData {
    /** File name under the dimension's {@code data} folder, without extension. */
    public static final String DATA_NAME = "weathermod_weather";
    /** Factory handed to {@code DimensionDataStorage}; no DataFixer type because the format is ours. */
    public static final SavedData.Factory<DimensionWeatherData> FACTORY =
        new SavedData.Factory<>(DimensionWeatherData::new, DimensionWeatherData::load, null);
    /** Children may exceed the configured storm cap by this many, so a full dimension still gets its tornado. */
    public static final int CHILD_STORM_HEADROOM = 4;

    private static final String KEY_STORMS = "Storms";
    private static final String KEY_NEXT_STORM_ID = "NextStormId";
    private static final String KEY_WIND = "Wind";
    private static final String KEY_TICK_COUNTER = "TickCounter";
    private static final String KEY_DIRECTOR = "Director";
    private static final String KEY_DEFLECTORS = "Deflectors";
    private static final String KEY_TEMP_FIRES = "TempFires";

    private final List<StormInstance> storms = new ArrayList<>();
    private final List<StormInstance> stormsView = Collections.unmodifiableList(storms);
    private final List<ChildSpawn> childQueue = new ArrayList<>();
    private final StormRuntime runtime;
    private int nextStormId = 1;
    private WindState wind;
    private int tickCounter;
    private DirectorState director = new DirectorState();
    private DeflectorIndex deflectors = new DeflectorIndex();
    private TemporaryFires temporaryFires = new TemporaryFires();
    @Nullable
    private ClimateField climate;

    /**
     * Creates empty data with a random wind, a fresh director and a fresh runtime, as used for a dimension that has
     * no saved weather yet.
     */
    public DimensionWeatherData() {
        this.wind = WindState.random(RandomSource.create());
        this.runtime = new StormRuntime();
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
        tag.put(KEY_DIRECTOR, director.save());
        if (deflectors.size() > 0) {
            tag.putLongArray(KEY_DEFLECTORS, deflectors.toLongArray());
        }
        if (!temporaryFires.isEmpty()) {
            tag.put(KEY_TEMP_FIRES, temporaryFires.save());
        }
        return tag;
    }

    /**
     * Reads data written by {@link #save(CompoundTag, HolderLookup.Provider)}; the id counter is repaired if it lags
     * behind the stored storms, and a tag without a director entry, as saved before the director existed, gets a
     * fresh director state. The deflector positions and the temporary fires are saved only when there are any, so
     * a tag without them loads an empty index and an empty fire list.
     */
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
        if (tag.contains(KEY_DIRECTOR, Tag.TAG_COMPOUND)) {
            data.director = DirectorState.load(tag.getCompound(KEY_DIRECTOR));
        }
        if (tag.contains(KEY_DEFLECTORS, Tag.TAG_LONG_ARRAY)) {
            data.deflectors = DeflectorIndex.fromLongArray(tag.getLongArray(KEY_DEFLECTORS));
        }
        if (tag.contains(KEY_TEMP_FIRES, Tag.TAG_COMPOUND)) {
            data.temporaryFires = TemporaryFires.load(tag.getCompound(KEY_TEMP_FIRES));
        }
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

    /**
     * Spawns a storm as the child of another one and marks the data dirty. Children may exceed the configured
     * storm cap by {@link #CHILD_STORM_HEADROOM}. Only for the simulator, which calls it after the family loop:
     * family systems ask for a child with {@link #queueChild}.
     *
     * @param parentId id of the storm that asked for the child
     * @return the new storm, or {@code null} when the dimension already holds the configured maximum plus the
     *         headroom
     */
    @Nullable
    public StormInstance spawnChild(Holder<WeatherType> type, double x, double z, int parentId,
                                    RandomSource random) {
        if (storms.size() >= (long) WeatherManager.maxStormsPerDimension() + CHILD_STORM_HEADROOM) {
            return null;
        }
        StormInstance storm = StormInstance.createChild(nextStormId++, type, x, z, parentId, random);
        storms.add(storm);
        setDirty();
        return storm;
    }

    /**
     * Asks for a child storm to be spawned once the family loop of this tick is over. Family systems call this
     * instead of spawning, because the storm list must not change while it is being ticked.
     */
    public void queueChild(ChildSpawn spawn) {
        childQueue.add(Objects.requireNonNull(spawn, "spawn"));
    }

    /** Returns the queued children in the order they were asked for and empties the queue. Simulator only. */
    public List<ChildSpawn> drainChildQueue() {
        if (childQueue.isEmpty()) {
            return List.of();
        }
        List<ChildSpawn> drained = List.copyOf(childQueue);
        childQueue.clear();
        return drained;
    }

    /** How many live storms the storm with the given id spawned. Zero for the id 0, which no storm has. */
    public int childCount(int parentId) {
        if (parentId == 0) {
            return 0;
        }
        int count = 0;
        for (StormInstance storm : storms) {
            if (storm.parentId() == parentId) {
                count++;
            }
        }
        return count;
    }

    /** Removes the storm with the given id and its runtime state. Returns whether one was removed. */
    public boolean remove(int id) {
        Iterator<StormInstance> iterator = storms.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().id() == id) {
                iterator.remove();
                runtime.forgetStorm(id);
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** Removes every expired storm with its runtime state and returns them so callers can notify clients. */
    public List<StormInstance> removeExpired() {
        List<StormInstance> expired = new ArrayList<>();
        Iterator<StormInstance> iterator = storms.iterator();
        while (iterator.hasNext()) {
            StormInstance storm = iterator.next();
            if (storm.isExpired()) {
                iterator.remove();
                runtime.forgetStorm(storm.id());
                expired.add(storm);
            }
        }
        if (!expired.isEmpty()) {
            setDirty();
        }
        return expired;
    }

    /**
     * Removes every storm with its runtime state. The id counter keeps counting so ids are never reused within a
     * world.
     */
    public void clear() {
        if (!storms.isEmpty()) {
            for (StormInstance storm : storms) {
                runtime.forgetStorm(storm.id());
            }
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

    /** How many live storms the director spawned, see {@link StormInstance#isNatural()}. */
    public int naturalStormCount() {
        int count = 0;
        for (StormInstance storm : storms) {
            if (storm.isNatural()) {
                count++;
            }
        }
        return count;
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

    /** The storm director's state for this dimension, persisted under {@code "Director"}. Mutating it is allowed. */
    public DirectorState director() {
        return director;
    }

    /**
     * The transient state the systems that act on the world share: published vortices and downbursts, per-tick
     * budgets, flying block counts, recent strikes and shield volumes. One per data object; never saved.
     */
    public StormRuntime runtime() {
        return runtime;
    }

    /**
     * Every weather deflector of this dimension, persisted under {@code "Deflectors"} so a deflector keeps storms
     * out while its chunk is unloaded and from the first tick after a restart. Whoever changes the index outside
     * the weather tick calls {@link #setDirty()}.
     */
    public DeflectorIndex deflectors() {
        return deflectors;
    }

    /** The fires the mod lit where fire never burns out by itself, persisted under {@code "TempFires"}. */
    public TemporaryFires temporaryFires() {
        return temporaryFires;
    }

    /**
     * The dimension's climate field, built on first use from the level's seed and the configured season provider
     * and kept for the life of this data. It is derived, not saved: a reloaded world rebuilds it.
     */
    public ClimateField climate(ServerLevel level) {
        ClimateField field = climate;
        if (field == null) {
            field = ClimateField.forLevel(level, SeasonProviders.create(WeatherManager.builtInYearLengthDays()));
            climate = field;
            WeatherMod.LOGGER.debug("Built climate field {} for {}", field, level.dimension().location());
        }
        return field;
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
