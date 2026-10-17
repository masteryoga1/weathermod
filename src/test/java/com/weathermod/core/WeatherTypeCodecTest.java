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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.junit.jupiter.api.Test;

/**
 * Parses weather type JSON through {@link WeatherType#CODEC} without a Minecraft bootstrap and checks
 * defaults, validation and round trips.
 */
class WeatherTypeCodecTest {
    private static final float EPSILON = 1.0e-6F;

    private static WeatherType parse(String json) {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        return result.getOrThrow(message -> new AssertionError("Failed to parse weather type: " + message));
    }

    private static JsonElement encode(WeatherType type) {
        return WeatherType.CODEC.encodeStart(JsonOps.INSTANCE, type)
            .getOrThrow(message -> new AssertionError("Failed to encode weather type: " + message));
    }

    @Test
    void minimalJsonUsesDefaults() {
        WeatherType type = parse("{\"family\": \"precipitation\"}");

        assertSame(WeatherFamily.PRECIPITATION, type.family());
        assertEquals(WeatherType.DEFAULT_INTENSITY, type.intensity());
        assertEquals(0.2F, type.intensity().min(), EPSILON);
        assertEquals(1.0F, type.intensity().max(), EPSILON);
        assertEquals(WeatherType.Climate.ANY, type.climate());
        assertEquals(-1.0F, type.climate().minTemperature(), EPSILON);
        assertEquals(2.0F, type.climate().maxTemperature(), EPSILON);
        assertEquals(0.0F, type.climate().minHumidity(), EPSILON);
        assertEquals(1.0F, type.climate().maxHumidity(), EPSILON);
        assertEquals(WeatherType.DEFAULT_DURATION_SECONDS, type.durationSeconds());
        assertEquals(300.0F, type.durationSeconds().min(), EPSILON);
        assertEquals(1200.0F, type.durationSeconds().max(), EPSILON);
        assertEquals(96.0F, type.radius(), EPSILON);
        assertEquals(512.0F, type.interestRadius(), EPSILON);
        assertEquals(10, type.weight());
        assertEquals(WeatherType.Visuals.NONE, type.visuals());
        assertTrue(type.visuals().isNone());
        assertEquals(Optional.empty(), type.biomes());
        assertEquals(List.of(
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether")), type.dimensions());
        assertTrue(type.enabled());
        assertTrue(type.spawnsNaturally());
    }

    @Test
    void fullJsonParsesEveryField() {
        WeatherType type = parse("""
            {
              "family": "convective",
              "intensity": {"min": 0.5, "max": 1.0},
              "climate": {"min_temperature": 0.3, "max_temperature": 1.5, "min_humidity": 0.4, "max_humidity": 1.0},
              "duration_seconds": {"min": 300, "max": 900},
              "radius": 160,
              "interest_radius": 768,
              "weight": 0,
              "visuals": {"rain_level": 1.0, "thunder_level": 1.0, "haze_strength": 0.4},
              "biomes": "weathermod:has_weather/convective",
              "dimensions": ["minecraft:overworld"],
              "enabled": false
            }
            """);

        assertSame(WeatherFamily.CONVECTIVE, type.family());
        assertEquals(new WeatherType.Range(0.5F, 1.0F), type.intensity());
        assertEquals(new WeatherType.Climate(0.3F, 1.5F, 0.4F, 1.0F), type.climate());
        assertEquals(new WeatherType.Range(300.0F, 900.0F), type.durationSeconds());
        assertEquals(160.0F, type.radius(), EPSILON);
        assertEquals(768.0F, type.interestRadius(), EPSILON);
        assertEquals(0, type.weight());
        assertEquals(new WeatherType.Visuals(1.0F, 1.0F, 0.4F), type.visuals());
        assertEquals(Optional.of(TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("weathermod", "has_weather/convective"))), type.biomes());
        assertEquals(List.of(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")), type.dimensions());
        assertFalse(type.enabled());
        assertFalse(type.spawnsNaturally());
        assertTrue(type.allowsDimension(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")));
        assertFalse(type.allowsDimension(ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether")));
        assertTrue(type.allowsClimate(1.0F, 0.8F));
        assertFalse(type.allowsClimate(0.0F, 0.8F));
    }

    @Test
    void partialClimateKeepsRemainingDefaults() {
        WeatherType type = parse("{\"family\": \"obscurant\", \"climate\": {\"min_temperature\": 1.5}}");

        assertEquals(1.5F, type.climate().minTemperature(), EPSILON);
        assertEquals(2.0F, type.climate().maxTemperature(), EPSILON);
        assertEquals(0.0F, type.climate().minHumidity(), EPSILON);
        assertEquals(1.0F, type.climate().maxHumidity(), EPSILON);
    }

    @Test
    void singleNumberIsAFixedRange() {
        WeatherType type = parse("{\"family\": \"obscurant\", \"duration_seconds\": 120, \"intensity\": 1}");

        assertEquals(WeatherType.Range.of(120.0F), type.durationSeconds());
        assertTrue(type.durationSeconds().isFixed());
        assertEquals(WeatherType.Range.of(1.0F), type.intensity());
    }

    @Test
    void jsonRoundTripIsLossless() {
        WeatherType original = parse("""
            {
              "family": "precipitation",
              "intensity": {"min": 0.4, "max": 1.0},
              "climate": {"min_temperature": 0.15, "max_temperature": 1.0, "min_humidity": 0.3, "max_humidity": 1.0},
              "duration_seconds": {"min": 300, "max": 1200},
              "radius": 128,
              "interest_radius": 640,
              "weight": 10,
              "visuals": {"rain_level": 1.0, "haze_strength": 0.3},
              "biomes": "weathermod:has_weather/precipitation",
              "dimensions": ["minecraft:overworld", "minecraft:the_nether"]
            }
            """);

        JsonElement encoded = encode(original);
        assertTrue(encoded.isJsonObject());
        JsonObject object = encoded.getAsJsonObject();
        assertEquals("precipitation", object.get("family").getAsString());
        assertEquals("weathermod:has_weather/precipitation", object.get("biomes").getAsString());
        assertFalse(object.has("enabled"), "default values are omitted when encoding");

        WeatherType decoded = WeatherType.CODEC.parse(JsonOps.INSTANCE, encoded)
            .getOrThrow(message -> new AssertionError("Failed to re-parse weather type: " + message));
        assertEquals(original, decoded);
    }

    @Test
    void defaultsOnlyTypeEncodesToFamilyAlone() {
        WeatherType type = parse("{\"family\": \"fantasy\"}");

        JsonObject encoded = encode(type).getAsJsonObject();
        assertEquals(1, encoded.size(), "only the family is written: " + encoded);
        assertEquals("fantasy", encoded.get("family").getAsString());
    }

    @Test
    void streamCodecRoundTripIsLossless() {
        WeatherType original = parse("""
            {
              "family": "winter",
              "intensity": {"min": 0.3, "max": 0.9},
              "duration_seconds": {"min": 60, "max": 61.5},
              "radius": 50.25,
              "visuals": {"rain_level": 1.0, "haze_strength": 0.4},
              "biomes": "weathermod:has_weather/precipitation",
              "enabled": false
            }
            """);

        ByteBuf buffer = Unpooled.buffer();
        try {
            WeatherType.STREAM_CODEC.encode(buffer, original);
            WeatherType decoded = WeatherType.STREAM_CODEC.decode(buffer);
            assertEquals(original, decoded);
            assertEquals(0, buffer.readableBytes(), "every byte is consumed");
        } finally {
            buffer.release();
        }
    }

    @Test
    void familyCodecsRoundTrip() {
        for (WeatherFamily family : WeatherFamily.values()) {
            JsonElement encoded = WeatherFamily.CODEC.encodeStart(JsonOps.INSTANCE, family).getOrThrow();
            assertEquals(family.getSerializedName(), encoded.getAsString());
            assertSame(family, WeatherFamily.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
            assertEquals(Optional.of(family), WeatherFamily.byName(family.getSerializedName()));

            ByteBuf buffer = Unpooled.buffer();
            try {
                WeatherFamily.STREAM_CODEC.encode(buffer, family);
                assertSame(family, WeatherFamily.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
        assertEquals(Optional.empty(), WeatherFamily.byName("blizzard"));
    }

    @Test
    void unknownFamilyIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"volcanic\"}"));
        assertTrue(result.error().isPresent(), "unknown family must fail");
    }

    @Test
    void missingFamilyIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}"));
        assertTrue(result.error().isPresent(), "family is required");
    }

    @Test
    void invertedRangeIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"precipitation\", \"intensity\": {\"min\": 0.9, \"max\": 0.1}}"));
        assertTrue(result.error().isPresent(), "min above max must fail");
    }

    @Test
    void intensityOutsideUnitIntervalIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"precipitation\", \"intensity\": {\"min\": 0.5, \"max\": 1.5}}"));
        assertTrue(result.error().isPresent(), "intensity above 1 must fail");
    }

    @Test
    void interestRadiusSmallerThanRadiusIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"precipitation\", \"radius\": 200, \"interest_radius\": 100}"));
        assertTrue(result.error().isPresent(), "interest radius below radius must fail");
    }

    @Test
    void visualsOutsideUnitIntervalAreRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"precipitation\", \"visuals\": {\"rain_level\": 1.5}}"));
        assertTrue(result.error().isPresent(), "rain level above 1 must fail");
    }

    @Test
    void builderMatchesParsedJson() {
        WeatherType built = WeatherType.builder(WeatherFamily.OBSCURANT)
            .weight(0)
            .radius(64.0F)
            .interestRadius(512.0F)
            .durationSeconds(120.0F, 120.0F)
            .visuals(0.0F, 0.0F, 0.3F)
            .build();
        WeatherType parsed = parse("""
            {
              "family": "obscurant",
              "weight": 0,
              "radius": 64.0,
              "interest_radius": 512.0,
              "duration_seconds": {"min": 120.0, "max": 120.0},
              "visuals": {"rain_level": 0.0, "thunder_level": 0.0, "haze_strength": 0.3}
            }
            """);

        assertEquals(parsed, built);
        assertFalse(built.spawnsNaturally());
    }
}
