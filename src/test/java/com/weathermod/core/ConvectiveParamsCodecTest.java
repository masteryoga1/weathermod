package com.weathermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Parses the {@code convective} block of a weather type through {@link ConvectiveParams#CODEC}: the defaults, the
 * range of every field and the round trip. Needs no Minecraft bootstrap.
 */
class ConvectiveParamsCodecTest {
    private static final float EPSILON = 1.0e-6F;
    private static final String FULL_JSON = """
        {
          "lightning_per_minute": 15.0,
          "lightning_fire_chance": 0.1,
          "hail": 0.8,
          "hail_damage": 2.0,
          "hail_burst_seconds": {"min": 45.0, "max": 120.0},
          "hail_gap_seconds": {"min": 30.0, "max": 90.0},
          "downbursts_per_minute": 0.5,
          "downburst_radius": 28.0,
          "downburst_speed": 24.0,
          "outflow_speed": 6.0,
          "drift_multiplier": 0.8,
          "tornado_chance": 0.6,
          "max_tornado_tier": 4,
          "small_vortex": "weathermod:gustnado",
          "small_vortices_per_minute": 0.2,
          "min_speed": 1.0
        }
        """;
    private static final List<String> KEYS = List.of(
        "lightning_per_minute", "lightning_fire_chance", "hail", "hail_damage", "hail_burst_seconds",
        "hail_gap_seconds", "downbursts_per_minute", "downburst_radius", "downburst_speed", "outflow_speed",
        "drift_multiplier", "tornado_chance", "max_tornado_tier", "small_vortex", "small_vortices_per_minute",
        "min_speed");

    private static DataResult<ConvectiveParams> tryParse(String json) {
        return ConvectiveParams.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static ConvectiveParams parse(String json) {
        return tryParse(json).getOrThrow(message -> new AssertionError("Failed to parse " + json + ": " + message));
    }

    private static JsonObject encode(ConvectiveParams params) {
        return ConvectiveParams.CODEC.encodeStart(JsonOps.INSTANCE, params)
            .getOrThrow(message -> new AssertionError("Failed to encode convective block: " + message))
            .getAsJsonObject();
    }

    private static void assertRejected(String json) {
        assertTrue(tryParse(json).error().isPresent(), json + " must be rejected");
    }

    @Test
    void emptyObjectDecodesToTheDefaults() {
        ConvectiveParams params = parse("{}");

        assertEquals(ConvectiveParams.NONE, params);
        assertEquals(0.0F, params.lightningPerMinute(), EPSILON);
        assertEquals(0.0F, params.lightningFireChance(), EPSILON);
        assertEquals(0.0F, params.hail(), EPSILON);
        assertEquals(0.0F, params.hailDamage(), EPSILON);
        assertEquals(new WeatherType.Range(30.0F, 90.0F), params.hailBurstSeconds());
        assertEquals(new WeatherType.Range(60.0F, 180.0F), params.hailGapSeconds());
        assertEquals(0.0F, params.downburstsPerMinute(), EPSILON);
        assertEquals(24.0F, params.downburstRadius(), EPSILON);
        assertEquals(20.0F, params.downburstSpeed(), EPSILON);
        assertEquals(0.0F, params.outflowSpeed(), EPSILON);
        assertEquals(1.0F, params.driftMultiplier(), EPSILON);
        assertEquals(0.0F, params.tornadoChance(), EPSILON);
        assertEquals(5, params.maxTornadoTier());
        assertEquals(Optional.empty(), params.smallVortex());
        assertEquals(0.0F, params.smallVorticesPerMinute(), EPSILON);
        assertEquals(0.0F, params.minSpeed(), EPSILON);
    }

    @Test
    void defaultsEncodeToAnEmptyObject() {
        JsonObject encoded = encode(ConvectiveParams.NONE);

        assertEquals(0, encoded.size(), "no optional key is written for a default: " + encoded);
    }

    @Test
    void onlyTheChangedKeyIsWritten() {
        ConvectiveParams params = parse("{\"hail\": 0.5}");

        JsonObject encoded = encode(params);
        assertEquals(1, encoded.size(), "only hail is written: " + encoded);
        assertEquals(0.5F, encoded.get("hail").getAsFloat(), EPSILON);
    }

    @Test
    void fullObjectParsesEveryField() {
        ConvectiveParams params = parse(FULL_JSON);

        assertEquals(new ConvectiveParams(15.0F, 0.1F, 0.8F, 2.0F, new WeatherType.Range(45.0F, 120.0F),
            new WeatherType.Range(30.0F, 90.0F), 0.5F, 28.0F, 24.0F, 6.0F, 0.8F, 0.6F, 4,
            Optional.of(ResourceLocation.fromNamespaceAndPath("weathermod", "gustnado")), 0.2F, 1.0F), params);
    }

    @Test
    void fullObjectRoundTrips() {
        ConvectiveParams original = parse(FULL_JSON);

        JsonObject encoded = encode(original);
        assertEquals(KEYS.size(), encoded.size(), "every key is written: " + encoded);
        for (String key : KEYS) {
            assertTrue(encoded.has(key), "missing " + key + " in " + encoded);
        }
        assertEquals("weathermod:gustnado", encoded.get("small_vortex").getAsString());
        assertEquals(original, ConvectiveParams.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void singleNumberIsAFixedRangeOfSeconds() {
        ConvectiveParams params = parse("{\"hail_burst_seconds\": 45, \"hail_gap_seconds\": 20}");

        assertEquals(WeatherType.Range.of(45.0F), params.hailBurstSeconds());
        assertEquals(WeatherType.Range.of(20.0F), params.hailGapSeconds());
    }

    @Test
    void ratesAndSpeedsMustNotBeNegative() {
        assertRejected("{\"lightning_per_minute\": -0.1}");
        assertRejected("{\"hail_damage\": -1.0}");
        assertRejected("{\"downbursts_per_minute\": -0.5}");
        assertRejected("{\"downburst_speed\": -1.0}");
        assertRejected("{\"outflow_speed\": -1.0}");
        assertRejected("{\"small_vortices_per_minute\": -0.1}");
        assertRejected("{\"min_speed\": -0.5}");
    }

    @Test
    void chancesAndHailMustLieWithinTheUnitInterval() {
        assertRejected("{\"lightning_fire_chance\": -0.1}");
        assertRejected("{\"lightning_fire_chance\": 1.1}");
        assertRejected("{\"hail\": -0.1}");
        assertRejected("{\"hail\": 1.1}");
        assertRejected("{\"tornado_chance\": -0.1}");
        assertRejected("{\"tornado_chance\": 1.1}");

        ConvectiveParams edges = parse("{\"lightning_fire_chance\": 1.0, \"hail\": 1.0, \"tornado_chance\": 1.0}");
        assertEquals(1.0F, edges.lightningFireChance(), EPSILON);
        assertEquals(1.0F, edges.hail(), EPSILON);
        assertEquals(1.0F, edges.tornadoChance(), EPSILON);
    }

    @Test
    void hailTimesMustBePositiveRanges() {
        assertRejected("{\"hail_burst_seconds\": {\"min\": 0.0, \"max\": 10.0}}");
        assertRejected("{\"hail_burst_seconds\": 0}");
        assertRejected("{\"hail_burst_seconds\": {\"min\": 20.0, \"max\": 10.0}}");
        assertRejected("{\"hail_gap_seconds\": {\"min\": -5.0, \"max\": 10.0}}");
        assertRejected("{\"hail_gap_seconds\": 0}");
        assertRejected("{\"hail_gap_seconds\": {\"min\": 90.0, \"max\": 30.0}}");
    }

    @Test
    void downburstRadiusMustBePositive() {
        assertRejected("{\"downburst_radius\": 0.0}");
        assertRejected("{\"downburst_radius\": -4.0}");
        assertEquals(0.5F, parse("{\"downburst_radius\": 0.5}").downburstRadius(), EPSILON);
    }

    @Test
    void driftMultiplierIsLimitedToAQuarterToFour() {
        assertRejected("{\"drift_multiplier\": 0.2}");
        assertRejected("{\"drift_multiplier\": 4.5}");
        assertEquals(0.25F, parse("{\"drift_multiplier\": 0.25}").driftMultiplier(), EPSILON);
        assertEquals(4.0F, parse("{\"drift_multiplier\": 4.0}").driftMultiplier(), EPSILON);
    }

    @Test
    void maxTornadoTierIsLimitedToTheSixTiers() {
        assertRejected("{\"max_tornado_tier\": -1}");
        assertRejected("{\"max_tornado_tier\": 6}");
        assertEquals(0, parse("{\"max_tornado_tier\": 0}").maxTornadoTier());
        assertEquals(5, parse("{\"max_tornado_tier\": 5}").maxTornadoTier());
    }

    @Test
    void smallVortexMustBeAnId() {
        assertRejected("{\"small_vortex\": \"Not An Id\"}");
        assertEquals(Optional.of(ResourceLocation.fromNamespaceAndPath("weathermod", "landspout")),
            parse("{\"small_vortex\": \"weathermod:landspout\"}").smallVortex());
    }

    @Test
    void zeroRatesAreAllowed() {
        ConvectiveParams params = parse("""
            {"lightning_per_minute": 0.0, "hail_damage": 0.0, "downbursts_per_minute": 0.0, "downburst_speed": 0.0,
             "outflow_speed": 0.0, "small_vortices_per_minute": 0.0, "min_speed": 0.0}
            """);

        assertEquals(0.0F, params.lightningPerMinute(), EPSILON);
        assertEquals(0.0F, params.hailDamage(), EPSILON);
        assertEquals(0.0F, params.downburstsPerMinute(), EPSILON);
        assertEquals(0.0F, params.downburstSpeed(), EPSILON, "0 is allowed although the default is 20");
        assertEquals(0.0F, params.outflowSpeed(), EPSILON);
        assertEquals(0.0F, params.smallVorticesPerMinute(), EPSILON);
        assertEquals(0.0F, params.minSpeed(), EPSILON);

        JsonObject encoded = encode(params);
        assertFalse(encoded.has("lightning_per_minute"), "a value equal to the default is omitted");
        assertEquals(1, encoded.size(), "only downburst_speed differs from its default: " + encoded);
        assertEquals(0.0F, encoded.get("downburst_speed").getAsFloat(), EPSILON);
    }
}
