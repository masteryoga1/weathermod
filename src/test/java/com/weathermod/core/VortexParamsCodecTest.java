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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parses the {@code vortex} block of a weather type through {@link VortexParams#CODEC}: the required kind, the
 * defaults, the range of every field, the rules between fields and the round trip; also pins what each
 * {@link VortexKind} answers. Needs no Minecraft bootstrap.
 */
class VortexParamsCodecTest {
    private static final float EPSILON = 1.0e-6F;
    private static final String FULL_JSON = """
        {
          "kind": "gustnado",
          "tier": 2,
          "core_radius": 8.0,
          "top_radius": 22.0,
          "influence_radius": 48.0,
          "height": {"min": 8.0, "max": 20.0},
          "wind_speed": 18.0,
          "pull": 0.079,
          "lift": 0.14,
          "rise_cap": 0.3,
          "eject_height": 20.0,
          "rip_share": 0.375,
          "flying_block_share": 0.5,
          "throw_speed": 13.0,
          "min_speed": 0.8,
          "max_speed": 3.1
        }
        """;
    private static final List<String> KEYS = List.of(
        "kind", "tier", "core_radius", "top_radius", "influence_radius", "height", "wind_speed", "pull", "lift",
        "rise_cap", "eject_height", "rip_share", "flying_block_share", "throw_speed", "min_speed", "max_speed");

    private static DataResult<VortexParams> tryParse(String json) {
        return VortexParams.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static VortexParams parse(String json) {
        return tryParse(json).getOrThrow(message -> new AssertionError("Failed to parse " + json + ": " + message));
    }

    private static JsonObject encode(VortexParams params) {
        return VortexParams.CODEC.encodeStart(JsonOps.INSTANCE, params)
            .getOrThrow(message -> new AssertionError("Failed to encode vortex block: " + message))
            .getAsJsonObject();
    }

    /** Asserts that a vortex block of kind tornado with the given extra fields fails to parse. */
    private static void assertRejected(String fields) {
        String json = "{\"kind\": \"tornado\", " + fields + "}";
        assertTrue(tryParse(json).error().isPresent(), json + " must be rejected");
    }

    /** Parses a vortex block of kind tornado with the given extra fields. */
    private static VortexParams tornado(String fields) {
        return parse("{\"kind\": \"tornado\", " + fields + "}");
    }

    @Test
    void kindAloneDecodesToTheDefaults() {
        VortexParams params = parse("{\"kind\": \"tornado\"}");

        assertSame(VortexKind.TORNADO, params.kind());
        assertEquals(0, params.tier());
        assertEquals(3.0F, params.coreRadius(), EPSILON);
        assertEquals(10.0F, params.topRadius(), EPSILON);
        assertEquals(24.0F, params.influenceRadius(), EPSILON);
        assertEquals(new WeatherType.Range(0.0F, 0.0F), params.height());
        assertTrue(params.reachesCloudBase(), "without a height the funnel reaches the cloud base");
        assertEquals(8.0F, params.windSpeed(), EPSILON);
        assertEquals(0.034F, params.pull(), EPSILON);
        assertEquals(0.05F, params.lift(), EPSILON);
        assertEquals(0.15F, params.riseCap(), EPSILON);
        assertEquals(4.0F, params.ejectHeight(), EPSILON);
        assertEquals(0.0F, params.ripShare(), EPSILON);
        assertEquals(0.0F, params.flyingBlockShare(), EPSILON);
        assertEquals(6.0F, params.throwSpeed(), EPSILON);
        assertEquals(0.0F, params.minSpeed(), EPSILON);
        assertEquals(64.0F, params.maxSpeed(), EPSILON);
        assertEquals(WeatherType.UNLIMITED_SPEED, params.maxSpeed(), EPSILON);
        assertEquals(new VortexParams(VortexKind.TORNADO, 0, 3.0F, 10.0F, 24.0F, WeatherType.Range.of(0.0F), 8.0F,
            0.034F, 0.05F, 0.15F, 4.0F, 0.0F, 0.0F, 6.0F, 0.0F, 64.0F), params);
    }

    @Test
    void kindIsRequired() {
        assertTrue(tryParse("{}").error().isPresent(), "a vortex block without a kind must fail");
        assertTrue(tryParse("{\"tier\": 2}").error().isPresent(), "a vortex block without a kind must fail");
        assertTrue(tryParse("{\"kind\": \"hurricane\"}").error().isPresent(), "an unknown kind must fail");
    }

    @Test
    void defaultsEncodeToTheKindAlone() {
        JsonObject encoded = encode(parse("{\"kind\": \"landspout\"}"));

        assertEquals(1, encoded.size(), "no optional key is written for a default: " + encoded);
        assertEquals("landspout", encoded.get("kind").getAsString());
    }

    @Test
    void fullObjectParsesEveryField() {
        VortexParams params = parse(FULL_JSON);

        assertEquals(new VortexParams(VortexKind.GUSTNADO, 2, 8.0F, 22.0F, 48.0F, new WeatherType.Range(8.0F, 20.0F),
            18.0F, 0.079F, 0.14F, 0.3F, 20.0F, 0.375F, 0.5F, 13.0F, 0.8F, 3.1F), params);
        assertFalse(params.reachesCloudBase(), "a funnel with its own height does not reach the cloud base");
    }

    @Test
    void fullObjectRoundTrips() {
        VortexParams original = parse(FULL_JSON);

        JsonObject encoded = encode(original);
        assertEquals(KEYS.size(), encoded.size(), "every key is written: " + encoded);
        for (String key : KEYS) {
            assertTrue(encoded.has(key), "missing " + key + " in " + encoded);
        }
        assertEquals(original, VortexParams.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void tierIsLimitedToTheSixTiers() {
        assertRejected("\"tier\": -1");
        assertRejected("\"tier\": 6");
        assertEquals(0, tornado("\"tier\": 0").tier());
        assertEquals(5, tornado("\"tier\": 5").tier());
    }

    @Test
    void radiiMustBePositiveAndGrowOutward() {
        assertRejected("\"core_radius\": 0.0");
        assertRejected("\"core_radius\": -3.0");
        assertRejected("\"top_radius\": 0.0");
        assertRejected("\"influence_radius\": 0.0");
        assertRejected("\"core_radius\": 5.0, \"top_radius\": 4.0");
        assertRejected("\"core_radius\": 12.0");
        assertRejected("\"top_radius\": 10.0, \"influence_radius\": 9.0");
        assertRejected("\"top_radius\": 30.0");

        VortexParams cylinder = tornado("\"core_radius\": 5.0, \"top_radius\": 5.0, \"influence_radius\": 5.0");
        assertEquals(5.0F, cylinder.coreRadius(), EPSILON);
        assertEquals(5.0F, cylinder.topRadius(), EPSILON);
        assertEquals(5.0F, cylinder.influenceRadius(), EPSILON);
    }

    @Test
    void heightMustBeANonNegativeRange() {
        assertRejected("\"height\": {\"min\": -1.0, \"max\": 5.0}");
        assertRejected("\"height\": -2.0");
        assertRejected("\"height\": {\"min\": 30.0, \"max\": 6.0}");

        assertFalse(tornado("\"height\": {\"min\": 6.0, \"max\": 30.0}").reachesCloudBase());
        assertFalse(tornado("\"height\": {\"min\": 0.0, \"max\": 30.0}").reachesCloudBase());
        assertFalse(tornado("\"height\": 12.0").reachesCloudBase());
        assertTrue(tornado("\"height\": {\"min\": 0.0, \"max\": 0.0}").reachesCloudBase());
        assertTrue(tornado("\"height\": 0").reachesCloudBase());
    }

    @Test
    void forcesAndSpeedsMustNotBeNegative() {
        assertRejected("\"wind_speed\": -1.0");
        assertRejected("\"pull\": -0.01");
        assertRejected("\"lift\": -0.01");
        assertRejected("\"rise_cap\": -0.1");
        assertRejected("\"eject_height\": -1.0");
        assertRejected("\"throw_speed\": -1.0");
        assertRejected("\"min_speed\": -0.5");
        assertRejected("\"max_speed\": -0.5");

        VortexParams still = tornado("""
            "wind_speed": 0.0, "pull": 0.0, "lift": 0.0, "rise_cap": 0.0, "eject_height": 0.0, "throw_speed": 0.0,
            "min_speed": 0.0, "max_speed": 0.0""");
        assertEquals(0.0F, still.windSpeed(), EPSILON);
        assertEquals(0.0F, still.pull(), EPSILON);
        assertEquals(0.0F, still.lift(), EPSILON);
        assertEquals(0.0F, still.riseCap(), EPSILON);
        assertEquals(0.0F, still.ejectHeight(), EPSILON, "0 stands for the funnel top");
        assertEquals(0.0F, still.throwSpeed(), EPSILON);
        assertEquals(0.0F, still.maxSpeed(), EPSILON);
    }

    @Test
    void sharesMustLieWithinTheUnitInterval() {
        assertRejected("\"rip_share\": -0.1");
        assertRejected("\"rip_share\": 1.1");
        assertRejected("\"flying_block_share\": -0.1");
        assertRejected("\"flying_block_share\": 1.1");

        VortexParams whole = tornado("\"rip_share\": 1.0, \"flying_block_share\": 1.0");
        assertEquals(1.0F, whole.ripShare(), EPSILON);
        assertEquals(1.0F, whole.flyingBlockShare(), EPSILON);
    }

    @Test
    void maxSpeedMustNotBeBelowMinSpeed() {
        assertRejected("\"min_speed\": 3.0, \"max_speed\": 2.0");
        assertRejected("\"min_speed\": 65.0");

        VortexParams fixed = tornado("\"min_speed\": 2.0, \"max_speed\": 2.0");
        assertEquals(2.0F, fixed.minSpeed(), EPSILON);
        assertEquals(2.0F, fixed.maxSpeed(), EPSILON);
    }

    @Test
    void kindsAnswerTheirRules() {
        for (VortexKind kind : VortexKind.values()) {
            assertEquals(kind == VortexKind.TORNADO, kind.isRated(), kind + " rated");
            assertEquals(kind == VortexKind.TORNADO, kind.followsParent(), kind + " follows its parent");
            assertEquals(kind == VortexKind.FIRE_WHIRL, kind.setsFire(), kind + " sets fire");
        }
        assertEquals(6, VortexKind.values().length);
    }

    @Test
    void kindCodecRoundTrips() {
        assertEquals(List.of("tornado", "landspout", "waterspout", "gustnado", "dust_devil", "fire_whirl"),
            List.of(VortexKind.values()).stream().map(VortexKind::getSerializedName).toList());
        for (VortexKind kind : VortexKind.values()) {
            JsonElement encoded = VortexKind.CODEC.encodeStart(JsonOps.INSTANCE, kind).getOrThrow();
            assertEquals(kind.getSerializedName(), encoded.getAsString());
            assertSame(kind, VortexKind.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        }
    }
}
