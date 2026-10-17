package com.weathermod.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

/**
 * Round-trips a dimension's weather through the saved-data tag and checks the repairs {@code load} applies to a
 * damaged tag. Needs no Minecraft bootstrap: the registry lookup is never used by save or load.
 */
class DimensionWeatherDataNbtTest {
    private static final float EPSILON = 1.0E-5F;

    private static final String KEY_STORMS = "Storms";
    private static final String KEY_NEXT_STORM_ID = "NextStormId";
    private static final String KEY_WIND = "Wind";
    private static final String KEY_TICK_COUNTER = "TickCounter";

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
    void saveThenLoadRestoresStormsWindAndCounters() {
        StormInstance rain = storm(42, "rain", 123.5D, -77.25D);
        StormInstance steered = storm(43, "placeholder", -10.0D, 20.0D);
        steered.steer(90.0F, 2.0F);
        WindState wind = new WindState(215.5F, 3.25F);

        CompoundTag tag = new CompoundTag();
        tag.put(KEY_STORMS, listOf(rain, steered));
        tag.putInt(KEY_NEXT_STORM_ID, 100);
        tag.put(KEY_WIND, wind.save());
        tag.putInt(KEY_TICK_COUNTER, 1234);

        DimensionWeatherData data = DimensionWeatherData.load(tag, null);

        assertEquals(2, data.storms().size());
        assertEquals(42, data.storms().get(0).id());
        assertEquals(WeatherTypes.RAIN, data.storms().get(0).type());
        assertEquals(123.5D, data.storms().get(0).x());
        assertEquals(43, data.storms().get(1).id());
        assertEquals(WeatherTypes.PLACEHOLDER, data.storms().get(1).type());
        assertTrue(data.storms().get(1).isSteered());
        assertEquals(100, data.nextStormId(), "a counter already ahead of every storm is kept");
        assertEquals(215.5F, data.wind().directionDegrees(), EPSILON);
        assertEquals(3.25F, data.wind().speed(), EPSILON);
        assertEquals(1234, data.tickCounter());
        assertFalse(data.isDirty(), "freshly loaded data has nothing to save");

        CompoundTag saved = data.save(new CompoundTag(), null);
        assertEquals(tag, saved);
        assertEquals(saved, DimensionWeatherData.load(saved, null).save(new CompoundTag(), null));
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
    }
}
