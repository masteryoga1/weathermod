package com.weathermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

/**
 * Checks the natural-formation trigger of a weather type: what {@link Trigger#accepts} lets through, how
 * {@link Trigger#weight} reacts to a cold front, what {@link Trigger#CODEC} rejects and omits, and the day test of
 * {@link TriggerContext}. Needs no Minecraft bootstrap.
 */
class TriggerTest {
    private static final float EPSILON = 1.0e-6F;
    private static final String NO_FRONT = "";

    private static DataResult<Trigger> tryParse(String json) {
        return Trigger.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static Trigger parse(String json) {
        return tryParse(json).getOrThrow(message -> new AssertionError("Failed to parse " + json + ": " + message));
    }

    private static JsonObject encode(Trigger trigger) {
        return Trigger.CODEC.encodeStart(JsonOps.INSTANCE, trigger)
            .getOrThrow(message -> new AssertionError("Failed to encode trigger: " + message))
            .getAsJsonObject();
    }

    private static TriggerContext context(float wind, boolean day, String front) {
        return new TriggerContext(wind, day, front);
    }

    @Test
    void anyHasNoCondition() {
        assertEquals(0.0F, Trigger.ANY.minWind(), EPSILON);
        assertEquals(64.0F, Trigger.ANY.maxWind(), EPSILON);
        assertSame(Trigger.TimeOfDay.ANY, Trigger.ANY.time());
        assertEquals(NO_FRONT, Trigger.ANY.front());
        assertEquals(1, Trigger.ANY.frontWeight());
    }

    @Test
    void anyAcceptsEverything() {
        float[] winds = {0.0F, 3.0F, 16.0F, 30.4F, 64.0F};
        String[] fronts = {NO_FRONT, Trigger.FRONT_COLD, Trigger.FRONT_WARM};
        for (float wind : winds) {
            for (String front : fronts) {
                assertTrue(Trigger.ANY.accepts(context(wind, true, front)), wind + " by day, front '" + front + "'");
                assertTrue(Trigger.ANY.accepts(context(wind, false, front)), wind + " by night, front '" + front + "'");
            }
        }
    }

    @Test
    void windBoundsAreInclusive() {
        Trigger trigger = new Trigger(5.0F, 11.0F, Trigger.TimeOfDay.ANY, NO_FRONT, 1);

        assertFalse(trigger.accepts(context(4.99F, true, NO_FRONT)));
        assertTrue(trigger.accepts(context(5.0F, true, NO_FRONT)));
        assertTrue(trigger.accepts(context(8.0F, true, NO_FRONT)));
        assertTrue(trigger.accepts(context(11.0F, true, NO_FRONT)));
        assertFalse(trigger.accepts(context(11.01F, true, NO_FRONT)));
    }

    @Test
    void timeOfDayMustMatch() {
        Trigger byDay = new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.DAY, NO_FRONT, 1);
        Trigger byNight = new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.NIGHT, NO_FRONT, 1);

        assertTrue(byDay.accepts(context(2.0F, true, NO_FRONT)));
        assertFalse(byDay.accepts(context(2.0F, false, NO_FRONT)));
        assertFalse(byNight.accepts(context(2.0F, true, NO_FRONT)));
        assertTrue(byNight.accepts(context(2.0F, false, NO_FRONT)));

        assertTrue(Trigger.TimeOfDay.ANY.matches(true));
        assertTrue(Trigger.TimeOfDay.ANY.matches(false));
        assertTrue(Trigger.TimeOfDay.DAY.matches(true));
        assertFalse(Trigger.TimeOfDay.DAY.matches(false));
        assertFalse(Trigger.TimeOfDay.NIGHT.matches(true));
        assertTrue(Trigger.TimeOfDay.NIGHT.matches(false));
    }

    @Test
    void coldFrontTriggerNeedsAColdFront() {
        Trigger trigger = new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.ANY, Trigger.FRONT_COLD, 1);

        assertFalse(trigger.accepts(context(8.0F, true, NO_FRONT)));
        assertFalse(trigger.accepts(context(8.0F, true, Trigger.FRONT_WARM)));
        assertTrue(trigger.accepts(context(8.0F, true, Trigger.FRONT_COLD)));
    }

    @Test
    void warmFrontTriggerNeedsAWarmFront() {
        Trigger trigger = new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.ANY, Trigger.FRONT_WARM, 1);

        assertFalse(trigger.accepts(context(8.0F, false, NO_FRONT)));
        assertFalse(trigger.accepts(context(8.0F, false, Trigger.FRONT_COLD)));
        assertTrue(trigger.accepts(context(8.0F, false, Trigger.FRONT_WARM)));
    }

    @Test
    void everyConditionMustHoldAtOnce() {
        Trigger trigger = new Trigger(8.0F, 64.0F, Trigger.TimeOfDay.NIGHT, Trigger.FRONT_COLD, 1);

        assertTrue(trigger.accepts(context(9.0F, false, Trigger.FRONT_COLD)));
        assertFalse(trigger.accepts(context(7.0F, false, Trigger.FRONT_COLD)), "too little wind");
        assertFalse(trigger.accepts(context(9.0F, true, Trigger.FRONT_COLD)), "wrong time");
        assertFalse(trigger.accepts(context(9.0F, false, NO_FRONT)), "no front");
    }

    @Test
    void weightIsMultipliedAlongAColdFrontOnly() {
        Trigger trigger = new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.ANY, NO_FRONT, 3);

        assertEquals(6, trigger.weight(2, context(4.0F, true, Trigger.FRONT_COLD)));
        assertEquals(2, trigger.weight(2, context(4.0F, true, NO_FRONT)));
        assertEquals(2, trigger.weight(2, context(4.0F, true, Trigger.FRONT_WARM)));
        assertEquals(0, trigger.weight(0, context(4.0F, true, Trigger.FRONT_COLD)), "weight 0 stays command-only");
    }

    @Test
    void defaultFrontWeightLeavesTheWeightAlone() {
        assertEquals(2, Trigger.ANY.weight(2, context(4.0F, true, Trigger.FRONT_COLD)));
        assertEquals(2, Trigger.ANY.weight(2, context(4.0F, true, NO_FRONT)));
    }

    @Test
    void weightSaturatesInsteadOfOverflowing() {
        Trigger trigger = new Trigger(0.0F, 64.0F, Trigger.TimeOfDay.ANY, NO_FRONT, 16);

        assertEquals(Integer.MAX_VALUE, trigger.weight(Integer.MAX_VALUE, context(4.0F, true, Trigger.FRONT_COLD)));
        assertEquals(1600, trigger.weight(100, context(4.0F, true, Trigger.FRONT_COLD)));
    }

    @Test
    void emptyObjectDecodesToAny() {
        assertEquals(Trigger.ANY, parse("{}"));
    }

    @Test
    void anyEncodesToAnEmptyObject() {
        assertEquals(0, encode(Trigger.ANY).size(), "no key is written for a default");
    }

    @Test
    void fullObjectRoundTrips() {
        Trigger original = parse("""
            {"min_wind": 8.0, "max_wind": 30.0, "time": "night", "front": "cold", "front_weight": 3}
            """);

        assertEquals(new Trigger(8.0F, 30.0F, Trigger.TimeOfDay.NIGHT, Trigger.FRONT_COLD, 3), original);
        JsonObject encoded = encode(original);
        assertEquals(5, encoded.size(), "every key is written: " + encoded);
        assertEquals("night", encoded.get("time").getAsString());
        assertEquals("cold", encoded.get("front").getAsString());
        assertEquals(3, encoded.get("front_weight").getAsInt());
        assertEquals(original, Trigger.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void frontWeightOfOneIsOmittedOnEncode() {
        Trigger trigger = parse("{\"min_wind\": 5.0, \"front\": \"cold\", \"front_weight\": 1}");

        JsonObject encoded = encode(trigger);
        assertFalse(encoded.has("front_weight"), "the default front weight is omitted: " + encoded);
        assertTrue(encoded.has("min_wind"));
        assertTrue(encoded.has("front"));
        assertFalse(encoded.has("max_wind"), "the default wind limit is omitted: " + encoded);
        assertFalse(encoded.has("time"), "the default time is omitted: " + encoded);
        assertEquals(trigger, Trigger.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void unknownFrontIsRejected() {
        assertTrue(tryParse("{\"front\": \"occluded\"}").error().isPresent(), "an unknown front must fail");
        assertEquals(Trigger.FRONT_COLD, parse("{\"front\": \"cold\"}").front());
        assertEquals(Trigger.FRONT_WARM, parse("{\"front\": \"warm\"}").front());
        assertEquals(NO_FRONT, parse("{\"front\": \"\"}").front());
    }

    @Test
    void frontWeightIsLimitedToOneToSixteen() {
        assertTrue(tryParse("{\"front_weight\": 0}").error().isPresent(), "front weight 0 must fail");
        assertTrue(tryParse("{\"front_weight\": -3}").error().isPresent(), "a negative front weight must fail");
        assertTrue(tryParse("{\"front_weight\": 17}").error().isPresent(), "front weight 17 must fail");
        assertEquals(1, parse("{\"front_weight\": 1}").frontWeight());
        assertEquals(16, parse("{\"front_weight\": 16}").frontWeight());
    }

    @Test
    void invertedWindWindowIsRejected() {
        assertTrue(tryParse("{\"min_wind\": 9.0, \"max_wind\": 6.0}").error().isPresent(),
            "min_wind above max_wind must fail");
        assertTrue(tryParse("{\"min_wind\": 65.0}").error().isPresent(),
            "min_wind above the default max_wind must fail");

        Trigger fixed = parse("{\"min_wind\": 6.0, \"max_wind\": 6.0}");
        assertEquals(6.0F, fixed.minWind(), EPSILON);
        assertEquals(6.0F, fixed.maxWind(), EPSILON);
    }

    @Test
    void unknownTimeOfDayIsRejected() {
        assertTrue(tryParse("{\"time\": \"dusk\"}").error().isPresent(), "an unknown time of day must fail");
        for (Trigger.TimeOfDay time : Trigger.TimeOfDay.values()) {
            JsonElement encoded = Trigger.TimeOfDay.CODEC.encodeStart(JsonOps.INSTANCE, time).getOrThrow();
            assertEquals(time.getSerializedName(), encoded.getAsString());
            assertSame(time, Trigger.TimeOfDay.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        }
    }

    @Test
    void frontNamesAreTheOnesTheDataUses() {
        assertEquals("cold", Trigger.FRONT_COLD);
        assertEquals("warm", Trigger.FRONT_WARM);
    }

    @Test
    void dayIsReadFromTheClock() {
        assertTrue(TriggerContext.isDay(0L, false));
        assertTrue(TriggerContext.isDay(11999L, false));
        assertTrue(TriggerContext.isDay(24000L + 6000L, false));
        assertFalse(TriggerContext.isDay(12000L, false));
        assertFalse(TriggerContext.isDay(23999L, false));
        assertFalse(TriggerContext.isDay(6000L, true), "a fixed-time dimension has no day");
    }

    @Test
    void dayTestWrapsEveryDayAndToleratesNegativeTimes() {
        assertFalse(TriggerContext.isDay(24000L + 18000L, false));
        assertTrue(TriggerContext.isDay(100L * 24000L + 1L, false));
        assertFalse(TriggerContext.isDay(-1L, false), "one tick before day 0 is the end of a night");
        assertTrue(TriggerContext.isDay(-24000L, false));
        assertFalse(TriggerContext.isDay(0L, true));
    }

    @Test
    void contextKeepsWhatItIsGiven() {
        TriggerContext context = new TriggerContext(7.5F, true, Trigger.FRONT_WARM);

        assertEquals(7.5F, context.windSpeed(), EPSILON);
        assertTrue(context.day());
        assertEquals(Trigger.FRONT_WARM, context.front());
    }
}
