package com.weathermod.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherTypes;
import com.weathermod.server.director.DirectorState;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Round-trips a dimension's weather through the saved-data tag and checks the repairs {@code load} applies to a
 * damaged tag, the defaults a tag of an earlier milestone falls back to, the headroom for child storms, the queue
 * of children and what removing a storm does to the runtime. Needs no Minecraft bootstrap: the registry lookup is
 * never used by save or load, the climate field is only built on demand and the weather types are hand-written
 * holders.
 */
class DimensionWeatherDataNbtTest {
    private static final float EPSILON = 1.0E-5F;

    private static final String KEY_STORMS = "Storms";
    private static final String KEY_NEXT_STORM_ID = "NextStormId";
    private static final String KEY_WIND = "Wind";
    private static final String KEY_TICK_COUNTER = "TickCounter";
    private static final String KEY_DIRECTOR = "Director";
    private static final String KEY_DEFLECTORS = "Deflectors";
    private static final String KEY_TEMP_FIRES = "TempFires";

    private static StormInstance storm(int id, String type, double x, double z) {
        return new StormInstance(id, WeatherRegistries.key(type), x, 160.0D, z, 215.5F, 3.25F, 0.8F, 600, 2400,
            96.0F, 512.0F);
    }

    private static ListTag listOf(StormInstance... storms) {
        ListTag list = new ListTag();
        for (StormInstance storm : storms) {
            list.add(storm.save());
        }
        return list;
    }

    @Test
    void saveThenLoadRestoresStormsWindCountersAndDirector() {
        StormInstance rain = storm(42, "rain", 123.5D, -77.25D);
        rain.markNatural();
        rain.setTicksUnseen(200);
        rain.setHeadingBias(12.5F);
        StormInstance steered = storm(43, "placeholder", -10.0D, 20.0D);
        steered.steer(90.0F, 2.0F);
        WindState wind = new WindState(215.5F, 3.25F);
        DirectorState director = new DirectorState(777, true, 123456L, 3);

        CompoundTag tag = new CompoundTag();
        tag.put(KEY_STORMS, listOf(rain, steered));
        tag.putInt(KEY_NEXT_STORM_ID, 100);
        tag.put(KEY_WIND, wind.save());
        tag.putInt(KEY_TICK_COUNTER, 1234);
        tag.put(KEY_DIRECTOR, director.save());

        DimensionWeatherData data = DimensionWeatherData.load(tag, null);

        assertEquals(2, data.storms().size());
        assertEquals(42, data.storms().get(0).id());
        assertEquals(WeatherTypes.RAIN, data.storms().get(0).type());
        assertEquals(123.5D, data.storms().get(0).x());
        assertTrue(data.storms().get(0).isNatural());
        assertEquals(200, data.storms().get(0).ticksUnseen());
        assertEquals(12.5F, data.storms().get(0).headingBiasDegrees(), EPSILON);
        assertEquals(43, data.storms().get(1).id());
        assertEquals(WeatherTypes.PLACEHOLDER, data.storms().get(1).type());
        assertTrue(data.storms().get(1).isSteered());
        assertFalse(data.storms().get(1).isNatural());
        assertEquals(1, data.naturalStormCount(), "only the director's storm counts as natural");
        assertEquals(100, data.nextStormId(), "a counter already ahead of every storm is kept");
        assertEquals(215.5F, data.wind().directionDegrees(), EPSILON);
        assertEquals(3.25F, data.wind().speed(), EPSILON);
        assertEquals(1234, data.tickCounter());
        assertEquals(director, data.director());
        assertEquals(777, data.director().ticksUntilAttempt);
        assertTrue(data.director().paused);
        assertEquals(123456L, data.director().lastSpawnGameTime);
        assertEquals(3, data.director().naturalSpawns);
        assertFalse(data.isDirty(), "freshly loaded data has nothing to save");

        CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(tag, saved);
        assertEquals(saved, DimensionWeatherData.load(saved, null).save(new CompoundTag(), null));
    }

    @Test
    void firstMilestoneTagLoadsWithAFreshDirector() {
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_STORMS, listOf(storm(1, "rain", 0.0D, 0.0D)));
        tag.putInt(KEY_NEXT_STORM_ID, 2);
        tag.put(KEY_WIND, new WindState(90.0F, 4.0F).save());
        tag.putInt(KEY_TICK_COUNTER, 50);
        assertFalse(tag.contains(KEY_DIRECTOR), "the first milestone never wrote a director entry");

        DimensionWeatherData data = DimensionWeatherData.load(tag, null);

        assertNotNull(data.director());
        assertEquals(new DirectorState(), data.director(), "no director entry means a fresh director");
        assertEquals(0, data.director().ticksUntilAttempt, "the first attempt is due at once");
        assertFalse(data.director().paused);
        assertEquals(0, data.director().naturalSpawns);
        assertEquals(0L, data.director().lastSpawnGameTime);
        assertEquals(1, data.storms().size());
        assertEquals(0, data.naturalStormCount(), "storms from before the director are not natural");
        assertEquals(50, data.tickCounter());

        CompoundTag saved = data.save(new CompoundTag(), null);
        assertTrue(saved.contains(KEY_DIRECTOR, Tag.TAG_COMPOUND), "the director is written from now on");
        assertEquals(new DirectorState().save(), saved.getCompound(KEY_DIRECTOR));
    }

    @Test
    void directorStateIsLiveAndSavedInPlace() {
        DimensionWeatherData data = new DimensionWeatherData();
        assertEquals(new DirectorState(), data.director(), "new data starts with a fresh director");

        data.director().paused = true;
        data.director().ticksUntilAttempt = 4000;
        data.director().recordSpawn(999L);

        CompoundTag saved = data.save(new CompoundTag(), null);
        DirectorState loaded = DirectorState.load(saved.getCompound(KEY_DIRECTOR));
        assertTrue(loaded.paused);
        assertEquals(4000, loaded.ticksUntilAttempt);
        assertEquals(999L, loaded.lastSpawnGameTime);
        assertEquals(1, loaded.naturalSpawns);
    }

    @Test
    void idCounterIsRepairedWhenItLagsBehindTheStoredStorms() {
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_STORMS, listOf(storm(42, "rain", 0.0D, 0.0D), storm(7, "placeholder", 0.0D, 0.0D)));
        tag.putInt(KEY_NEXT_STORM_ID, 1);

        DimensionWeatherData data = DimensionWeatherData.load(tag, null);

        assertEquals(43, data.nextStormId(), "the next id must be above every stored storm");
        assertEquals(2, data.storms().size());

        assertEquals(1, DimensionWeatherData.load(new CompoundTag(), null).nextStormId(),
            "an empty tag starts counting at 1");
    }

    @Test
    void missingWindAndNegativeTickCounterFallBack() {
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_STORMS, new ListTag());
        tag.putInt(KEY_NEXT_STORM_ID, 5);
        tag.putInt(KEY_TICK_COUNTER, -5);

        DimensionWeatherData data = DimensionWeatherData.load(tag, null);

        WindState wind = data.wind();
        assertNotNull(wind, "a tag without wind gets a fresh random wind");
        assertTrue(wind.directionDegrees() >= 0.0F && wind.directionDegrees() < 360.0F);
        assertTrue(wind.speed() >= WindState.MIN_SPEED && wind.speed() <= WindState.MAX_SPEED);
        assertEquals(0, data.tickCounter(), "a negative tick counter is clamped to zero");
        assertEquals(5, data.nextStormId());
        assertTrue(data.storms().isEmpty());
        assertEquals(0, data.naturalStormCount());
        assertEquals(new DirectorState(), data.director());
    }

    @Test
    void naturalStormCountFollowsTheLiveList() {
        CompoundTag tag = new CompoundTag();
        StormInstance first = storm(1, "rain", 0.0D, 0.0D);
        first.markNatural();
        StormInstance second = storm(2, "snow", 50.0D, 0.0D);
        second.markNatural();
        StormInstance third = storm(3, "placeholder", 100.0D, 0.0D);
        tag.put(KEY_STORMS, listOf(first, second, third));

        DimensionWeatherData data = DimensionWeatherData.load(tag, null);
        assertEquals(2, data.naturalStormCount());

        assertTrue(data.remove(2));
        assertEquals(1, data.naturalStormCount(), "a removed natural storm no longer counts");

        assertTrue(data.remove(3));
        assertEquals(1, data.naturalStormCount(), "removing a commanded storm changes nothing");

        data.clear();
        assertEquals(0, data.naturalStormCount());
    }

    @Test
    void secondMilestoneTagIsSavedBackWithoutTheNewKeys() {
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_STORMS, listOf(storm(1, "rain", 0.0D, 0.0D), storm(2, "fog", 40.0D, -8.0D)));
        tag.putInt(KEY_NEXT_STORM_ID, 3);
        tag.put(KEY_WIND, new WindState(90.0F, 4.0F).save());
        tag.putInt(KEY_TICK_COUNTER, 50);
        tag.put(KEY_DIRECTOR, new DirectorState(1200, false, 4000L, 2).save());

        DimensionWeatherData data = DimensionWeatherData.load(tag, null);

        assertEquals(0, data.deflectors().size(), "a tag without deflectors loads an empty index");
        assertTrue(data.temporaryFires().isEmpty(), "a tag without temporary fires loads an empty list");
        assertEquals(0L, data.director().severeReadyGameTime);
        assertFalse(data.storms().get(0).isChild());

        CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(tag, saved, "a tag of the second milestone is saved back unchanged");
        assertEquals(Set.of(KEY_STORMS, KEY_NEXT_STORM_ID, KEY_WIND, KEY_TICK_COUNTER, KEY_DIRECTOR),
            saved.getAllKeys());
        assertFalse(saved.contains(KEY_DEFLECTORS));
        assertFalse(saved.contains(KEY_TEMP_FIRES));
    }

    @Test
    void deflectorsAndTemporaryFiresSurviveASaveAndLoad() {
        DimensionWeatherData data = new DimensionWeatherData();
        BlockPos deflector = new BlockPos(100, 70, -200);
        BlockPos fire = new BlockPos(-3, 40, 12);
        assertTrue(data.deflectors().add(deflector));
        assertTrue(data.temporaryFires().add(fire));

        CompoundTag saved = data.save(new CompoundTag(), null);
        assertTrue(saved.contains(KEY_DEFLECTORS, Tag.TAG_LONG_ARRAY));
        assertArrayEquals(new long[] {deflector.asLong()}, saved.getLongArray(KEY_DEFLECTORS));
        assertTrue(saved.contains(KEY_TEMP_FIRES, Tag.TAG_COMPOUND));

        DimensionWeatherData loaded = DimensionWeatherData.load(saved, null);
        assertEquals(1, loaded.deflectors().size());
        assertTrue(loaded.deflectors().contains(deflector));
        assertEquals(1, loaded.temporaryFires().size());
        assertFalse(loaded.temporaryFires().add(fire), "the loaded list already holds the fire");

        CompoundTag resaved = loaded.save(new CompoundTag(), null);
        assertEquals(saved.get(KEY_DEFLECTORS), resaved.get(KEY_DEFLECTORS));
        assertEquals(saved.get(KEY_TEMP_FIRES), resaved.get(KEY_TEMP_FIRES));

        assertTrue(loaded.deflectors().remove(deflector));
        loaded.temporaryFires().remove(fire);
        CompoundTag emptied = loaded.save(new CompoundTag(), null);
        assertFalse(emptied.contains(KEY_DEFLECTORS), "an empty index is not written");
        assertFalse(emptied.contains(KEY_TEMP_FIRES), "an empty fire list is not written");
    }

    @Test
    void deflectorsAndTemporaryFiresOfTheWrongTagTypeAreIgnored() {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_DEFLECTORS, "not a long array");
        tag.putInt(KEY_TEMP_FIRES, 3);

        DimensionWeatherData data = DimensionWeatherData.load(tag, null);

        assertEquals(0, data.deflectors().size());
        assertTrue(data.temporaryFires().isEmpty());
    }

    @Test
    void spawnChildRespectsTheHeadroom() {
        DimensionWeatherData data = new DimensionWeatherData();
        StormInstanceNbtTest.KeyedHolder holder = StormInstanceNbtTest.vortexHolder("tornado_ef0");
        RandomSource random = RandomSource.create(5L);
        int max = WeatherManager.maxStormsPerDimension();
        assertEquals(WeatherManager.DEFAULT_MAX_STORMS_PER_DIMENSION, max, "no config is loaded in a unit test");

        for (int i = 0; i < max; i++) {
            assertNotNull(data.spawn(holder, i * 10.0D, 0.0D, random));
        }
        assertTrue(data.isFull());
        assertNull(data.spawn(holder, 0.0D, 0.0D, random), "an ordinary storm is refused at the cap");

        for (int i = 0; i < DimensionWeatherData.CHILD_STORM_HEADROOM; i++) {
            StormInstance child = data.spawnChild(holder, 5.0D, 6.0D, i < 3 ? 1 : 2, random);
            assertNotNull(child, "a child still fits into the headroom");
            assertEquals(max + 1 + i, child.id(), "children take their ids from the same counter");
            assertTrue(child.isChild());
            assertFalse(child.isNatural());
            assertEquals(5.0D, child.x());
            assertEquals(6.0D, child.z());
        }
        assertEquals(max + DimensionWeatherData.CHILD_STORM_HEADROOM, data.storms().size());
        assertTrue(data.isDirty());

        int nextId = data.nextStormId();
        assertNull(data.spawnChild(holder, 0.0D, 0.0D, 1, random), "the headroom is a hard limit");
        assertEquals(nextId, data.nextStormId(), "a refused child uses up no id");

        assertEquals(3, data.childCount(1));
        assertEquals(1, data.childCount(2));
        assertEquals(0, data.childCount(3));
        assertEquals(0, data.childCount(0), "no storm has the id 0, so nothing is its child");
        assertEquals(0, data.naturalStormCount(), "children are not natural storms");

        assertTrue(data.remove(max + 1));
        assertEquals(2, data.childCount(1), "a removed child no longer counts");
        assertNotNull(data.spawnChild(holder, 0.0D, 0.0D, 2, random), "and frees its place in the headroom");
        assertEquals(2, data.childCount(2));
    }

    @Test
    void childQueueDrainsOnce() {
        DimensionWeatherData data = new DimensionWeatherData();
        assertTrue(data.drainChildQueue().isEmpty(), "nothing is queued in new data");

        ChildSpawn first = new ChildSpawn(3, WeatherRegistries.key("tornado_ef0"), 1.0D, 2.0D);
        CompoundTag family = new CompoundTag();
        family.putInt("Count", 2);
        ChildSpawn second = new ChildSpawn(4, WeatherRegistries.key("waterspout"), -5.0D, 9.0D, 600, family);
        assertEquals(0, first.lifetimeTicks(), "the short form keeps the lifetime the type picks");
        assertTrue(first.familyTag().isEmpty(), "and the family's default state");
        assertEquals(600, second.lifetimeTicks());
        assertEquals(0, new ChildSpawn(1, WeatherRegistries.key("waterspout"), 0.0D, 0.0D, -40, family)
            .lifetimeTicks(), "a negative lifetime is no lifetime");

        data.queueChild(first);
        data.queueChild(second);
        assertTrue(data.storms().isEmpty(), "queueing a child spawns nothing by itself");
        CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(Set.of(KEY_STORMS, KEY_NEXT_STORM_ID, KEY_WIND, KEY_TICK_COUNTER, KEY_DIRECTOR),
            saved.getAllKeys(), "the queue is never saved");

        assertEquals(List.of(first, second), data.drainChildQueue(), "children come out in the order they went in");
        assertTrue(data.drainChildQueue().isEmpty(), "the queue drains once");

        assertThrows(NullPointerException.class, () -> data.queueChild(null));
        assertThrows(NullPointerException.class, () -> new ChildSpawn(1, null, 0.0D, 0.0D));
        assertThrows(NullPointerException.class,
            () -> new ChildSpawn(1, WeatherRegistries.key("waterspout"), 0.0D, 0.0D, 0, null));
    }

    @Test
    void removalPathsForgetTheRuntimeVortex() {
        StormInstance expiring = new StormInstance(2, WeatherRegistries.key("placeholder"), 0.0D, 160.0D, 0.0D,
            0.0F, 0.0F, 1.0F, 99, 100, 64.0F, 512.0F);
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_STORMS, listOf(storm(1, "rain", 0.0D, 0.0D), expiring, storm(3, "rain", 0.0D, 0.0D),
            storm(4, "rain", 0.0D, 0.0D)));
        DimensionWeatherData data = DimensionWeatherData.load(tag, null);
        StormRuntime runtime = data.runtime();
        assertSame(runtime, data.runtime(), "one runtime per data object");
        for (int id = 1; id <= 4; id++) {
            runtime.putVortex(StormRuntimeTest.vortex(id));
        }

        assertTrue(data.remove(1));
        assertNull(runtime.vortex(1), "a removed storm takes its vortex with it");
        assertNotNull(runtime.vortex(2));
        assertFalse(data.remove(1));

        data.storms().get(0).tick(new WindState(0.0F, 0.0F), 1.0F);
        List<StormInstance> expired = data.removeExpired();
        assertEquals(1, expired.size());
        assertEquals(2, expired.get(0).id());
        assertNull(runtime.vortex(2), "an expired storm takes its vortex with it");
        assertNotNull(runtime.vortex(3));
        assertNotNull(runtime.vortex(4));

        data.clear();
        assertNull(runtime.vortex(3));
        assertNull(runtime.vortex(4));
        assertTrue(runtime.vortices().isEmpty(), "clearing the storms clears their vortices");
    }
}
