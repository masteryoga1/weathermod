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
    /** Every weather type file the mod ships, 26 in all. */
    private static final List<String> SHIPPED_TYPES = List.of(
        "placeholder", "overcast", "drizzle", "rain", "downpour", "snow", "sleet", "fog", "thunderstorm", "heat_haze",
        "severe_thunderstorm", "hailstorm", "squall_line", "derecho", "supercell",
        "tornado_ef0", "tornado_ef1", "tornado_ef2", "tornado_ef3", "tornado_ef4", "tornado_ef5",
        "landspout", "waterspout", "gustnado", "dust_devil", "fire_whirl");
    private static final int SHIPPED_CONVECTIVE_TYPES = 6;
    private static final int SHIPPED_VORTEX_TYPES = 11;
    /** A convective type that sets every field the third milestone added. */
    private static final String FULL_CONVECTIVE_JSON = """
        {
          "family": "convective",
          "severe": true,
          "trigger": {"min_wind": 5.0, "max_wind": 20.0, "time": "night", "front": "cold", "front_weight": 3},
          "convective": {
            "lightning_per_minute": 11.0,
            "lightning_fire_chance": 0.1,
            "hail": 0.5,
            "hail_damage": 1.5,
            "hail_burst_seconds": {"min": 45.0, "max": 120.0},
            "hail_gap_seconds": {"min": 30.0, "max": 90.0},
            "downbursts_per_minute": 0.6,
            "downburst_radius": 28.0,
            "downburst_speed": 22.0,
            "outflow_speed": 8.0,
            "drift_multiplier": 1.6,
            "tornado_chance": 0.6,
            "max_tornado_tier": 3,
            "small_vortex": "weathermod:gustnado",
            "small_vortices_per_minute": 0.1,
            "min_speed": 1.0
          }
        }
        """;
    /** A vortex type that sets every field of its vortex block. */
    private static final String FULL_VORTEX_JSON = """
        {
          "family": "vortex",
          "radius": 64.0,
          "interest_radius": 768.0,
          "precipitation": "none",
          "severe": true,
          "trigger": {"max_wind": 6.0, "time": "day"},
          "vortex": {
            "kind": "fire_whirl",
            "tier": 3,
            "core_radius": 12.0,
            "top_radius": 30.0,
            "influence_radius": 64.0,
            "height": {"min": 10.0, "max": 50.0},
            "wind_speed": 23.0,
            "pull": 0.102,
            "lift": 0.17,
            "rise_cap": 0.4,
            "eject_height": 40.0,
            "rip_share": 0.625,
            "flying_block_share": 0.75,
            "throw_speed": 16.0,
            "min_speed": 0.8,
            "max_speed": 2.9
          }
        }
        """;

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
        assertFalse(type.severe());
        assertEquals(Optional.empty(), type.convective());
        assertEquals(Optional.empty(), type.vortex());
        assertEquals(Trigger.ANY, type.trigger());
        assertEquals(1.0F, type.driftMultiplier(), EPSILON);
        assertEquals(0.0F, type.minSpeed(), EPSILON);
        assertEquals(WeatherType.UNLIMITED_SPEED, type.maxSpeed(), EPSILON);
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
        assertTrue(type.precipitates(), "a convective type with a precipitation mode drops something");
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
    void precipitatesNeedsAPrecipitatingFamilyAndAMode() {
        assertTrue(parse("{\"family\": \"precipitation\"}").precipitates());
        assertTrue(parse("{\"family\": \"precipitation\", \"precipitation\": \"auto\"}").precipitates());
        assertTrue(parse("{\"family\": \"precipitation\", \"precipitation\": \"rain\"}").precipitates());
        assertTrue(parse("{\"family\": \"precipitation\", \"precipitation\": \"snow\"}").precipitates());
        assertTrue(parse("{\"family\": \"precipitation\", \"precipitation\": \"sleet\"}").precipitates());
        assertFalse(parse("{\"family\": \"precipitation\", \"precipitation\": \"none\"}").precipitates());
        assertFalse(parse("{\"family\": \"obscurant\"}").precipitates());
        assertFalse(parse("{\"family\": \"obscurant\", \"precipitation\": \"rain\"}").precipitates());
        assertTrue(parse("{\"family\": \"convective\", \"precipitation\": \"auto\"}").precipitates());
        assertTrue(parse("{\"family\": \"convective\"}").precipitates());
        assertFalse(parse("{\"family\": \"convective\", \"precipitation\": \"none\"}").precipitates());
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
        assertTrue(thunderstorm.precipitates(), "a thunderstorm rains: convective types precipitate");
    }

    @Test
    void fullConvectiveJsonParsesEveryNewField() {
        WeatherType type = parse(FULL_CONVECTIVE_JSON);

        assertSame(WeatherFamily.CONVECTIVE, type.family());
        assertTrue(type.severe());
        assertEquals(new Trigger(5.0F, 20.0F, Trigger.TimeOfDay.NIGHT, Trigger.FRONT_COLD, 3), type.trigger());
        assertEquals(Optional.of(new ConvectiveParams(11.0F, 0.1F, 0.5F, 1.5F, new WeatherType.Range(45.0F, 120.0F),
            new WeatherType.Range(30.0F, 90.0F), 0.6F, 28.0F, 22.0F, 8.0F, 1.6F, 0.6F, 3,
            Optional.of(ResourceLocation.fromNamespaceAndPath("weathermod", "gustnado")), 0.1F, 1.0F)),
            type.convective());
        assertEquals(Optional.empty(), type.vortex());
        assertEquals(1.6F, type.driftMultiplier(), EPSILON);
        assertEquals(1.0F, type.minSpeed(), EPSILON);
        assertEquals(64.0F, type.maxSpeed(), EPSILON, "a type without a vortex block has no speed limit of its own");
        assertTrue(type.precipitates());
    }

    @Test
    void fullVortexJsonParsesEveryNewField() {
        WeatherType type = parse(FULL_VORTEX_JSON);

        assertSame(WeatherFamily.VORTEX, type.family());
        assertTrue(type.severe());
        assertEquals(new Trigger(0.0F, 6.0F, Trigger.TimeOfDay.DAY, "", 1), type.trigger());
        assertEquals(Optional.empty(), type.convective());
        assertEquals(Optional.of(new VortexParams(VortexKind.FIRE_WHIRL, 3, 12.0F, 30.0F, 64.0F,
            new WeatherType.Range(10.0F, 50.0F), 23.0F, 0.102F, 0.17F, 0.4F, 40.0F, 0.625F, 0.75F, 16.0F, 0.8F,
            2.9F)), type.vortex());
        assertEquals(1.0F, type.driftMultiplier(), EPSILON, "only a convective block changes the drift");
        assertEquals(0.8F, type.minSpeed(), EPSILON);
        assertEquals(2.9F, type.maxSpeed(), EPSILON);
        assertFalse(type.precipitates());
    }

    @Test
    void everyNewFieldRoundTripsThroughJson() {
        for (String json : List.of(FULL_CONVECTIVE_JSON, FULL_VORTEX_JSON)) {
            WeatherType original = parse(json);

            JsonObject encoded = encode(original).getAsJsonObject();
            assertTrue(encoded.get("severe").getAsBoolean(), "severe is written when set: " + encoded);
            assertTrue(encoded.has("trigger"), "a trigger other than the default is written: " + encoded);
            assertEquals(original.convective().isPresent(), encoded.has("convective"), encoded.toString());
            assertEquals(original.vortex().isPresent(), encoded.has("vortex"), encoded.toString());

            WeatherType decoded = WeatherType.CODEC.parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(message -> new AssertionError("Failed to re-parse weather type: " + message));
            assertEquals(original, decoded);
        }
    }

    @Test
    void everyNewFieldRoundTripsThroughTheStreamCodec() {
        for (String json : List.of(FULL_CONVECTIVE_JSON, FULL_VORTEX_JSON)) {
            WeatherType original = parse(json);

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
    }

    @Test
    void emptyConvectiveBlockIsKeptApartFromAMissingOne() {
        WeatherType type = parse("{\"family\": \"convective\", \"convective\": {}}");

        assertEquals(Optional.of(ConvectiveParams.NONE), type.convective());
        JsonObject encoded = encode(type).getAsJsonObject();
        assertEquals(2, encoded.size(), "family and the empty block are written: " + encoded);
        assertEquals(0, encoded.getAsJsonObject("convective").size(), "defaults inside the block are omitted");
        assertEquals(type, WeatherType.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void vortexBlockOnAnotherFamilyIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"precipitation\", \"vortex\": {\"kind\": \"tornado\"}}"));
        assertTrue(result.error().isPresent(), "a vortex block needs the vortex family");

        DataResult<WeatherType> convective = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"convective\", \"vortex\": {\"kind\": \"tornado\"}}"));
        assertTrue(convective.error().isPresent(), "a convective type is not a vortex either");
    }

    @Test
    void vortexFamilyWithoutAVortexBlockIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"vortex\"}"));
        assertTrue(result.error().isPresent(), "a vortex type must say what vortex it is");

        WeatherType minimal = parse("{\"family\": \"vortex\", \"vortex\": {\"kind\": \"tornado\"}}");
        assertSame(VortexKind.TORNADO, minimal.vortex().orElseThrow().kind());
    }

    @Test
    void convectiveBlockOnAnotherFamilyIsRejected() {
        DataResult<WeatherType> result = WeatherType.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"family\": \"precipitation\", \"convective\": {}}"));
        assertTrue(result.error().isPresent(), "a convective block needs the convective family");

        DataResult<WeatherType> vortex = WeatherType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
            "{\"family\": \"vortex\", \"vortex\": {\"kind\": \"tornado\"}, \"convective\": {}}"));
        assertTrue(vortex.error().isPresent(), "a vortex type takes no convective block");
    }

    @Test
    void invalidNestedBlocksAreRejected() {
        DataResult<WeatherType> badConvective = WeatherType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
            "{\"family\": \"convective\", \"convective\": {\"hail\": 1.5}}"));
        assertTrue(badConvective.error().isPresent(), "hail above 1 must fail the whole type");

        DataResult<WeatherType> badVortex = WeatherType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
            "{\"family\": \"vortex\", \"vortex\": {\"kind\": \"tornado\", \"tier\": 6}}"));
        assertTrue(badVortex.error().isPresent(), "tier 6 must fail the whole type");

        DataResult<WeatherType> badTrigger = WeatherType.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
            "{\"family\": \"precipitation\", \"trigger\": {\"front\": \"occluded\"}}"));
        assertTrue(badTrigger.error().isPresent(), "an unknown front must fail the whole type");
    }

    @Test
    void twelveArgumentConstructorEqualsTheParsedDefaults() {
        WeatherType built = new WeatherType(WeatherFamily.PRECIPITATION, WeatherType.DEFAULT_INTENSITY,
            WeatherType.Climate.ANY, WeatherType.DEFAULT_DURATION_SECONDS, WeatherType.DEFAULT_RADIUS,
            WeatherType.DEFAULT_INTEREST_RADIUS, WeatherType.DEFAULT_WEIGHT, WeatherType.Visuals.NONE,
            WeatherType.DEFAULT_PRECIPITATION, Optional.empty(), WeatherType.DEFAULT_DIMENSIONS, true);

        assertEquals(parse("{\"family\": \"precipitation\"}"), built);
        assertFalse(built.severe());
        assertEquals(Optional.empty(), built.convective());
        assertEquals(Optional.empty(), built.vortex());
        assertSame(Trigger.ANY, built.trigger());
        assertEquals(1, encode(built).getAsJsonObject().size(), "the new defaults are omitted when encoding");
    }

    @Test
    void speedLimitsPreferTheVortexBlock() {
        ConvectiveParams convective = new ConvectiveParams(0.0F, 0.0F, 0.0F, 0.0F, new WeatherType.Range(30.0F, 90.0F),
            new WeatherType.Range(60.0F, 180.0F), 0.0F, 24.0F, 20.0F, 0.0F, 2.0F, 0.0F, 5, Optional.empty(), 0.0F,
            1.5F);
        VortexParams vortex = new VortexParams(VortexKind.TORNADO, 0, 3.0F, 10.0F, 24.0F, WeatherType.Range.of(0.0F),
            8.0F, 0.034F, 0.05F, 0.15F, 4.0F, 0.0F, 0.0F, 6.0F, 0.8F, 3.5F);
        WeatherType both = new WeatherType(WeatherFamily.VORTEX, WeatherType.DEFAULT_INTENSITY,
            WeatherType.Climate.ANY, WeatherType.DEFAULT_DURATION_SECONDS, 24.0F, 512.0F, 0, WeatherType.Visuals.NONE,
            PrecipitationMode.NONE, Optional.empty(), WeatherType.DEFAULT_DIMENSIONS, true, false,
            Optional.of(convective), Optional.of(vortex), Trigger.ANY);
        WeatherType convectiveOnly = new WeatherType(WeatherFamily.CONVECTIVE, WeatherType.DEFAULT_INTENSITY,
            WeatherType.Climate.ANY, WeatherType.DEFAULT_DURATION_SECONDS, 24.0F, 512.0F, 0, WeatherType.Visuals.NONE,
            PrecipitationMode.AUTO, Optional.empty(), WeatherType.DEFAULT_DIMENSIONS, true, false,
            Optional.of(convective), Optional.empty(), Trigger.ANY);

        assertEquals(0.8F, both.minSpeed(), EPSILON, "the vortex block's minimum wins");
        assertEquals(3.5F, both.maxSpeed(), EPSILON);
        assertEquals(2.0F, both.driftMultiplier(), EPSILON);
        assertEquals(1.5F, convectiveOnly.minSpeed(), EPSILON);
        assertEquals(WeatherType.UNLIMITED_SPEED, convectiveOnly.maxSpeed(), EPSILON);
        assertEquals(64.0F, WeatherType.UNLIMITED_SPEED, EPSILON);
    }

    @Test
    void everyShippedTypeParses() throws IOException {
        assertEquals(26, SHIPPED_TYPES.size());
        int convective = 0;
        int vortex = 0;
        for (String name : SHIPPED_TYPES) {
            WeatherType type = shipped(name);
            if (type.family() == WeatherFamily.CONVECTIVE) {
                convective++;
                assertTrue(type.precipitates(), name + " is convective and must precipitate");
                assertTrue(type.convective().isPresent(), name + " ships a convective block");
                assertEquals(Optional.empty(), type.vortex(), name);
            } else if (type.family() == WeatherFamily.VORTEX) {
                vortex++;
                assertFalse(type.precipitates(), name + " is a vortex and must not precipitate");
                assertTrue(type.vortex().isPresent(), name + " ships a vortex block");
                assertEquals(Optional.empty(), type.convective(), name);
            } else {
                assertEquals(Optional.empty(), type.convective(), name);
                assertEquals(Optional.empty(), type.vortex(), name);
                assertFalse(type.severe(), name + " predates severe weather");
                assertEquals(Trigger.ANY, type.trigger(), name + " predates triggers");
            }
        }
        assertEquals(SHIPPED_CONVECTIVE_TYPES, convective, "shipped convective types");
        assertEquals(SHIPPED_VORTEX_TYPES, vortex, "shipped vortex types");
    }

    @Test
    void shippedThunderstormStrikesAndSpawnsLandspouts() throws IOException {
        WeatherType thunderstorm = shipped("thunderstorm");

        assertEquals(6, thunderstorm.weight());
        assertTrue(thunderstorm.spawnsNaturally());
        assertFalse(thunderstorm.severe());
        ConvectiveParams params = thunderstorm.convective().orElseThrow();
        assertEquals(5.5F, params.lightningPerMinute(), EPSILON);
        assertEquals(0.05F, params.lightningFireChance(), EPSILON);
        assertEquals(0.0F, params.hail(), EPSILON, "an ordinary thunderstorm does not hail");
        assertEquals(Optional.of(ResourceLocation.fromNamespaceAndPath("weathermod", "landspout")),
            params.smallVortex());
        assertEquals(0.02F, params.smallVorticesPerMinute(), EPSILON);
        assertEquals(1.0F, thunderstorm.driftMultiplier(), EPSILON);
    }
}
