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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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
    private static final String SHIPPED_DIRECTORY = "data/weathermod/weathermod/weather_type/";
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation THE_NETHER = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    private static WeatherType parse(String json) {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
        return result.getOrThrow(message -> new AssertionError("Failed to parse weather type: " + message));
    }

    private static JsonElement encode(WeatherType type) {
        return WeatherType.CODEC.encodeStart(JsonOps.INSTANCE, type)
            .getOrThrow(message -> new AssertionError("Failed to encode weather type: " + message));
    }

    /**
     * Loads one of the weather types shipped under {@code src/main/resources}, from the classpath when Gradle put the
     * resources there and from the source tree otherwise.
     */
    private static WeatherType shipped(String name) throws IOException {
        String resource = SHIPPED_DIRECTORY + name + ".json";
        try (InputStream stream = WeatherTypeCodecTest.class.getResourceAsStream("/" + resource)) {
            if (stream != null) {
                return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        Path path = Path.of("src", "main", "resources").resolve(resource);
        assertTrue(Files.isRegularFile(path),
            "shipped weather type " + name + " is neither on the classpath nor at " + path.toAbsolutePath());
        return parse(Files.readString(path, StandardCharsets.UTF_8));
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
        assertEquals(2.5F, type.climate().maxTemperature(), EPSILON);
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
        assertSame(WeatherType.DEFAULT_PRECIPITATION, type.precipitation());
        assertSame(PrecipitationMode.AUTO, type.precipitation());
        assertTrue(type.precipitates(), "a precipitation type in auto mode drops something");
        assertEquals(Optional.empty(), type.biomes());
        assertEquals(List.of(OVERWORLD, THE_NETHER), type.dimensions());
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
              "precipitation": "snow",
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
        assertSame(PrecipitationMode.SNOW, type.precipitation());
        assertFalse(type.precipitates(), "only the precipitation family precipitates, whatever the mode says");
        assertEquals(Optional.of(TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("weathermod", "has_weather/convective"))), type.biomes());
        assertEquals(List.of(OVERWORLD), type.dimensions());
        assertFalse(type.enabled());
        assertFalse(type.spawnsNaturally());
        assertTrue(type.allowsDimension(OVERWORLD));
        assertFalse(type.allowsDimension(THE_NETHER));
        assertTrue(type.allowsClimate(1.0F, 0.8F));
        assertFalse(type.allowsClimate(0.0F, 0.8F));
    }

    @Test
    void partialClimateKeepsRemainingDefaults() {
        WeatherType type = parse("{\"family\": \"obscurant\", \"climate\": {\"min_temperature\": 1.5}}");

        assertEquals(1.5F, type.climate().minTemperature(), EPSILON);
        assertEquals(2.5F, type.climate().maxTemperature(), EPSILON);
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
        assertFalse(object.has("precipitation"), "the auto precipitation default is omitted when encoding");

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
    void fixedPrecipitationModeEncodesAsItsName() {
        WeatherType type = parse("{\"family\": \"precipitation\", \"precipitation\": \"sleet\"}");

        assertSame(PrecipitationMode.SLEET, type.precipitation());
        JsonObject encoded = encode(type).getAsJsonObject();
        assertEquals(2, encoded.size(), "family and precipitation are written: " + encoded);
        assertEquals("sleet", encoded.get("precipitation").getAsString());
        assertEquals(type, WeatherType.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void precipitatesNeedsThePrecipitationFamilyAndAMode() {
        assertTrue(parse("{\"family\": \"precipitation\"}").precipitates());
        assertTrue(parse("{\"family\": \"precipitation\", \"precipitation\": \"auto\"}").precipitates());
        assertTrue(parse("{\"family\": \"precipitation\", \"precipitation\": \"rain\"}").precipitates());
        assertTrue(parse("{\"family\": \"precipitation\", \"precipitation\": \"snow\"}").precipitates());
        assertTrue(parse("{\"family\": \"precipitation\", \"precipitation\": \"sleet\"}").precipitates());
        assertFalse(parse("{\"family\": \"precipitation\", \"precipitation\": \"none\"}").precipitates());
        assertFalse(parse("{\"family\": \"obscurant\"}").precipitates());
        assertFalse(parse("{\"family\": \"obscurant\", \"precipitation\": \"rain\"}").precipitates());
        assertFalse(parse("{\"family\": \"convective\", \"precipitation\": \"auto\"}").precipitates());
        assertFalse(parse("{\"family\": \"winter\", \"precipitation\": \"snow\"}").precipitates());
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
              "precipitation": "snow",
              "biomes": "weathermod:has_weather/precipitation",
              "enabled": false
            }
            """);

        ByteBuf buffer = Unpooled.buffer();
        try {
            WeatherType.STREAM_CODEC.encode(buffer, original);
            WeatherType decoded = WeatherType.STREAM_CODEC.decode(buffer);
            assertEquals(original, decoded);
            assertSame(PrecipitationMode.SNOW, decoded.precipitation());
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

            ByteBuf buffer = Unpooled.buffer();
            try {
                WeatherFamily.STREAM_CODEC.encode(buffer, family);
                assertSame(family, WeatherFamily.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void precipitationModeCodecsRoundTrip() {
        for (PrecipitationMode mode : PrecipitationMode.values()) {
            JsonElement encoded = PrecipitationMode.CODEC.encodeStart(JsonOps.INSTANCE, mode).getOrThrow();
            assertEquals(mode.getSerializedName(), encoded.getAsString());
            assertEquals(mode.name().toLowerCase(Locale.ROOT), encoded.getAsString());
            assertSame(mode, PrecipitationMode.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());

            ByteBuf buffer = Unpooled.buffer();
            try {
                PrecipitationMode.STREAM_CODEC.encode(buffer, mode);
                assertEquals(1, buffer.readableBytes(), "a small ordinal is a single VarInt byte");
                assertSame(mode, PrecipitationMode.STREAM_CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes(), "every byte is consumed");
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void unknownFamilyIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"volcanic\"}"));
        assertTrue(result.error().isPresent(), "unknown family must fail");
    }

    @Test
    void unknownPrecipitationModeIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"precipitation\", \"precipitation\": \"hail\"}"));
        assertTrue(result.error().isPresent(), "unknown precipitation mode must fail");
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
    void directConstructionMatchesParsedJson() {
        WeatherType built = new WeatherType(WeatherFamily.OBSCURANT, WeatherType.DEFAULT_INTENSITY,
            WeatherType.Climate.ANY, WeatherType.Range.of(120.0F), 64.0F, 512.0F, 0,
            new WeatherType.Visuals(0.0F, 0.0F, 0.3F), PrecipitationMode.NONE, Optional.empty(),
            WeatherType.DEFAULT_DIMENSIONS, true);
        WeatherType parsed = parse("""
            {
              "family": "obscurant",
              "weight": 0,
              "radius": 64.0,
              "interest_radius": 512.0,
              "duration_seconds": {"min": 120.0, "max": 120.0},
              "visuals": {"rain_level": 0.0, "thunder_level": 0.0, "haze_strength": 0.3},
              "precipitation": "none"
            }
            """);

        assertEquals(parsed, built);
        assertFalse(built.spawnsNaturally());
        assertFalse(built.precipitates());
    }

    @Test
    void shippedTypesParseWithTheirPrecipitationModes() throws IOException {
        WeatherType placeholder = shipped("placeholder");
        assertSame(WeatherFamily.OBSCURANT, placeholder.family());
        assertSame(PrecipitationMode.NONE, placeholder.precipitation());
        assertFalse(placeholder.spawnsNaturally());
        assertFalse(placeholder.precipitates());

        WeatherType overcast = shipped("overcast");
        assertSame(WeatherFamily.PRECIPITATION, overcast.family());
        assertSame(PrecipitationMode.NONE, overcast.precipitation());
        assertFalse(overcast.precipitates(), "overcast is dry cloud cover");
        assertEquals(0.0F, overcast.visuals().rainLevel(), EPSILON);
        assertEquals(0.25F, overcast.visuals().hazeStrength(), EPSILON);

        WeatherType drizzle = shipped("drizzle");
        assertSame(WeatherFamily.PRECIPITATION, drizzle.family());
        assertSame(PrecipitationMode.AUTO, drizzle.precipitation());
        assertTrue(drizzle.precipitates());
        assertEquals(0.35F, drizzle.visuals().rainLevel(), EPSILON);

        WeatherType rain = shipped("rain");
        assertSame(WeatherFamily.PRECIPITATION, rain.family());
        assertSame(PrecipitationMode.AUTO, rain.precipitation());
        assertTrue(rain.precipitates());

        WeatherType downpour = shipped("downpour");
        assertSame(WeatherFamily.PRECIPITATION, downpour.family());
        assertSame(PrecipitationMode.AUTO, downpour.precipitation());
        assertTrue(downpour.precipitates());
        assertEquals(new WeatherType.Range(0.7F, 1.0F), downpour.intensity());
        assertEquals(1.0F, downpour.visuals().rainLevel(), EPSILON);
        assertEquals(0.5F, downpour.visuals().hazeStrength(), EPSILON);

        WeatherType snow = shipped("snow");
        assertSame(WeatherFamily.PRECIPITATION, snow.family());
        assertSame(PrecipitationMode.SNOW, snow.precipitation());
        assertTrue(snow.precipitates());
        assertEquals(0.3F, snow.climate().maxTemperature(), EPSILON);
        assertSame(PrecipitationKind.SNOW, PrecipitationKind.resolve(snow.precipitation(), 0.8F),
            "a snow type snows wherever it forms");

        WeatherType sleet = shipped("sleet");
        assertSame(WeatherFamily.PRECIPITATION, sleet.family());
        assertSame(PrecipitationMode.SLEET, sleet.precipitation());
        assertTrue(sleet.precipitates());
        assertEquals(0.0F, sleet.climate().minTemperature(), EPSILON);
        assertEquals(0.35F, sleet.climate().maxTemperature(), EPSILON);

        WeatherType fog = shipped("fog");
        assertSame(WeatherFamily.OBSCURANT, fog.family());
        assertSame(PrecipitationMode.NONE, fog.precipitation());
        assertFalse(fog.precipitates());

        WeatherType heatHaze = shipped("heat_haze");
        assertSame(WeatherFamily.OBSCURANT, heatHaze.family());
        assertSame(PrecipitationMode.NONE, heatHaze.precipitation());
        assertFalse(heatHaze.precipitates());
        assertEquals(1.2F, heatHaze.climate().minTemperature(), EPSILON);
        assertEquals(2.5F, heatHaze.climate().maxTemperature(), EPSILON, "reaches the hottest Nether sample");
        assertEquals(6, heatHaze.weight());
        assertEquals(List.of(OVERWORLD, THE_NETHER), heatHaze.dimensions());

        WeatherType thunderstorm = shipped("thunderstorm");
        assertSame(WeatherFamily.CONVECTIVE, thunderstorm.family());
        assertSame(PrecipitationMode.AUTO, thunderstorm.precipitation());
        assertFalse(thunderstorm.precipitates(), "convective storms are not driven by the precipitation family yet");
    }
}
