package com.weathermod.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherTypes;
import com.weathermod.server.director.DirectorState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * Round-trips a dimension's weather through the saved-data tag and checks the repairs {@code load} applies to a
 * damaged tag and the defaults a first-milestone tag falls back to. Needs no Minecraft bootstrap: the registry
 * lookup is never used by save or load and the climate field is only built on demand.
 */
class DimensionWeatherDataNbtTest {
    private static final float EPSILON = 1.0E-5F;

    private static final String KEY_STORMS = "Storms";
    private static final String KEY_NEXT_STORM_ID = "NextStormId";
    private static final String KEY_WIND = "Wind";
    private static final String KEY_TICK_COUNTER = "TickCounter";
    private static final String KEY_DIRECTOR = "Director";

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
}
