package com.weathermod.core;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * One data-driven weather type: the family that simulates it plus the ranges, climate limits and visuals
 * that tune it. Instances are immutable and loaded from datapack JSON through {@link #CODEC}.
 *
 * @param family          the simulation family
 * @param intensity       the range the initial intensity is picked from, 0 to 1
 * @param climate         the temperature and humidity window the type may form in
 * @param durationSeconds the range the lifetime is picked from, in real seconds
 * @param radius          the radius of the storm's effects, in blocks
 * @param interestRadius  players farther than this from the centre never receive the storm, in blocks
 * @param weight          the natural spawn weight, 0 meaning the type only spawns through commands
 * @param visuals         the vanilla rain, thunder and haze levels the client writes at full intensity
 * @param precipitation   what falls from the storm: chosen by temperature, fixed, or nothing
 * @param biomes          the biome tag the type may form in, empty meaning any biome
 * @param dimensions      the dimension ids the type may form in
 * @param enabled         whether the type is used at all
 */
public record WeatherType(
    WeatherFamily family,
    Range intensity,
    Climate climate,
    Range durationSeconds,
    float radius,
    float interestRadius,
    int weight,
    Visuals visuals,
    PrecipitationMode precipitation,
    Optional<TagKey<Biome>> biomes,
    List<ResourceLocation> dimensions,
    boolean enabled
) {
    public static final Range DEFAULT_INTENSITY = new Range(0.2F, 1.0F);
    public static final Range DEFAULT_DURATION_SECONDS = new Range(300.0F, 1200.0F);
    public static final float DEFAULT_RADIUS = 96.0F;
    public static final float DEFAULT_INTEREST_RADIUS = 512.0F;
    public static final int DEFAULT_WEIGHT = 10;
    public static final PrecipitationMode DEFAULT_PRECIPITATION = PrecipitationMode.AUTO;
    public static final List<ResourceLocation> DEFAULT_DIMENSIONS = List.of(
        ResourceLocation.withDefaultNamespace("overworld"),
        ResourceLocation.withDefaultNamespace("the_nether"));

    /**
     * JSON codec. Every field except {@code family} is optional and falls back to the defaults above.
     * Keys are snake case: {@code duration_seconds}, {@code interest_radius} and so on.
     */
    public static final Codec<WeatherType> CODEC = RecordCodecBuilder.<WeatherType>create(instance -> instance.group(
        WeatherFamily.CODEC.fieldOf("family").forGetter(WeatherType::family),
        Range.CODEC.optionalFieldOf("intensity", DEFAULT_INTENSITY).forGetter(WeatherType::intensity),
        Climate.CODEC.optionalFieldOf("climate", Climate.ANY).forGetter(WeatherType::climate),
        Range.CODEC.optionalFieldOf("duration_seconds", DEFAULT_DURATION_SECONDS).forGetter(WeatherType::durationSeconds),
        ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(WeatherType::radius),
        ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf("interest_radius", DEFAULT_INTEREST_RADIUS).forGetter(WeatherType::interestRadius),
        ExtraCodecs.NON_NEGATIVE_INT.optionalFieldOf("weight", DEFAULT_WEIGHT).forGetter(WeatherType::weight),
        Visuals.CODEC.optionalFieldOf("visuals", Visuals.NONE).forGetter(WeatherType::visuals),
        PrecipitationMode.CODEC.optionalFieldOf("precipitation", DEFAULT_PRECIPITATION).forGetter(WeatherType::precipitation),
        TagKey.codec(Registries.BIOME).optionalFieldOf("biomes").forGetter(WeatherType::biomes),
        ResourceLocation.CODEC.listOf().optionalFieldOf("dimensions", DEFAULT_DIMENSIONS).forGetter(WeatherType::dimensions),
        Codec.BOOL.optionalFieldOf("enabled", true).forGetter(WeatherType::enabled)
    ).apply(instance, WeatherType::new)).validate(WeatherType::validate);

    /** Network codec used for registry sync and payloads. Nothing in a weather type needs registry access. */
    public static final StreamCodec<ByteBuf, WeatherType> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    public WeatherType {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(intensity, "intensity");
        Objects.requireNonNull(climate, "climate");
        Objects.requireNonNull(durationSeconds, "durationSeconds");
        Objects.requireNonNull(visuals, "visuals");
        Objects.requireNonNull(precipitation, "precipitation");
        Objects.requireNonNull(biomes, "biomes");
        dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
    }

    private static DataResult<WeatherType> validate(WeatherType type) {
        if (type.intensity.min() < 0.0F || type.intensity.max() > 1.0F) {
            return DataResult.error(() -> "intensity must lie within 0..1, got " + type.intensity);
        }
        if (type.durationSeconds.min() <= 0.0F) {
            return DataResult.error(() -> "duration_seconds must be positive, got " + type.durationSeconds);
        }
        if (type.interestRadius < type.radius) {
            return DataResult.error(() -> "interest_radius " + type.interestRadius
                + " must be at least radius " + type.radius);
        }
        return DataResult.success(type);
    }

    /** True when the type is enabled and has a positive spawn weight. */
    public boolean spawnsNaturally() {
        return enabled && weight > 0;
    }

    /**
     * True when storms of this type drop something: the family is {@link WeatherFamily#PRECIPITATION} and the
     * precipitation mode is not {@link PrecipitationMode#NONE}. Only such types switch vanilla rain on.
     */
    public boolean precipitates() {
        return family == WeatherFamily.PRECIPITATION && precipitation != PrecipitationMode.NONE;
    }

    /** True when the type may form in the given dimension. */
    public boolean allowsDimension(ResourceKey<Level> dimension) {
        return allowsDimension(dimension.location());
    }

    /** True when the type may form in the dimension with the given id. */
    public boolean allowsDimension(ResourceLocation dimensionId) {
        return dimensions.contains(dimensionId);
    }

    /** True when the type may form in the given biome, which is always the case without a biome tag. */
    public boolean allowsBiome(Holder<Biome> biome) {
        return biomes.map(biome::is).orElse(true);
    }

    /** True when the given biome temperature and humidity fall inside the climate window. */
    public boolean allowsClimate(float temperature, float humidity) {
        return climate.accepts(temperature, humidity);
    }

    /** Picks a starting intensity from the intensity range. */
    public float pickIntensity(RandomSource random) {
        return intensity.sample(random);
    }

    /** Picks a lifetime from the duration range and converts it to game ticks, never less than one tick. */
    public int pickDurationTicks(RandomSource random) {
        return Math.max(1, Math.round(durationSeconds.sample(random) * SharedConstants.TICKS_PER_SECOND));
    }

    /**
     * A closed interval of floats. In JSON it is either an object {@code {"min": a, "max": b}} or a single
     * number, which stands for a fixed value.
     */
    public record Range(float min, float max) {
        private static final Codec<Range> OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("min").forGetter(Range::min),
            Codec.FLOAT.fieldOf("max").forGetter(Range::max)
        ).apply(instance, Range::new));

        public static final Codec<Range> CODEC = Codec.either(Codec.FLOAT, OBJECT_CODEC)
            .xmap(
                either -> either.map(Range::of, Function.identity()),
                (Range range) -> Either.right(range))
            .validate(Range::validate);

        /** A range whose min and max are the same value. */
        public static Range of(float value) {
            return new Range(value, value);
        }

        private static DataResult<Range> validate(Range range) {
            if (Float.isNaN(range.min) || Float.isNaN(range.max)) {
                return DataResult.error(() -> "Range bounds must be numbers, got " + range);
            }
            if (range.min > range.max) {
                return DataResult.error(() -> "Range min " + range.min + " is greater than max " + range.max);
            }
            return DataResult.success(range);
        }

        /** True when min equals max. */
        public boolean isFixed() {
            return min == max;
        }

        /** The distance between max and min. */
        public float span() {
            return max - min;
        }

        /** Linear interpolation, {@code t = 0} giving min and {@code t = 1} giving max. */
        public float lerp(float t) {
            return min + (max - min) * t;
        }

        /** Clamps a value into the range. */
        public float clamp(float value) {
            return Math.max(min, Math.min(max, value));
        }

        /** True when the value lies inside the closed interval. */
        public boolean contains(float value) {
            return value >= min && value <= max;
        }

        /** Picks a uniformly distributed value from the range. */
        public float sample(RandomSource random) {
            return isFixed() ? min : lerp(random.nextFloat());
        }

        @Override
        public String toString() {
            return min + ".." + max;
        }
    }

    /**
     * The biome climate window a type may form in. Temperature uses vanilla biome temperature
     * (roughly -0.7 for frozen peaks to 2.0 for deserts), humidity uses vanilla downfall (0 to 1).
     */
    public record Climate(float minTemperature, float maxTemperature, float minHumidity, float maxHumidity) {
        /** The whole vanilla climate space; the default when a type gives no climate. */
        public static final Climate ANY = new Climate(-1.0F, 2.0F, 0.0F, 1.0F);

        public static final Codec<Climate> CODEC = RecordCodecBuilder.<Climate>create(instance -> instance.group(
            Codec.FLOAT.optionalFieldOf("min_temperature", ANY.minTemperature()).forGetter(Climate::minTemperature),
            Codec.FLOAT.optionalFieldOf("max_temperature", ANY.maxTemperature()).forGetter(Climate::maxTemperature),
            Codec.FLOAT.optionalFieldOf("min_humidity", ANY.minHumidity()).forGetter(Climate::minHumidity),
            Codec.FLOAT.optionalFieldOf("max_humidity", ANY.maxHumidity()).forGetter(Climate::maxHumidity)
        ).apply(instance, Climate::new)).validate(Climate::validate);

        private static DataResult<Climate> validate(Climate climate) {
            if (climate.minTemperature > climate.maxTemperature) {
                return DataResult.error(() -> "min_temperature " + climate.minTemperature
                    + " is greater than max_temperature " + climate.maxTemperature);
            }
            if (climate.minHumidity > climate.maxHumidity) {
                return DataResult.error(() -> "min_humidity " + climate.minHumidity
                    + " is greater than max_humidity " + climate.maxHumidity);
            }
            return DataResult.success(climate);
        }

        /** True when both values fall inside the window. */
        public boolean accepts(float temperature, float humidity) {
            return temperature >= minTemperature && temperature <= maxTemperature
                && humidity >= minHumidity && humidity <= maxHumidity;
        }

        /** The temperature bounds as a range. */
        public Range temperature() {
            return new Range(minTemperature, maxTemperature);
        }

        /** The humidity bounds as a range. */
        public Range humidity() {
            return new Range(minHumidity, maxHumidity);
        }
    }

    /**
     * The vanilla weather levels the client writes while a storm of this type is at full intensity overhead.
     * Each level is 0 to 1 and is scaled by the current intensity.
     */
    public record Visuals(float rainLevel, float thunderLevel, float hazeStrength) {
        /** No visible effect at all. */
        public static final Visuals NONE = new Visuals(0.0F, 0.0F, 0.0F);

        private static final Codec<Float> LEVEL = Codec.floatRange(0.0F, 1.0F);

        public static final Codec<Visuals> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            LEVEL.optionalFieldOf("rain_level", 0.0F).forGetter(Visuals::rainLevel),
            LEVEL.optionalFieldOf("thunder_level", 0.0F).forGetter(Visuals::thunderLevel),
            LEVEL.optionalFieldOf("haze_strength", 0.0F).forGetter(Visuals::hazeStrength)
        ).apply(instance, Visuals::new));

        /** The levels multiplied by an intensity, which is clamped to 0..1 first. */
        public Visuals scaled(float intensity) {
            float factor = Math.max(0.0F, Math.min(1.0F, intensity));
            return new Visuals(rainLevel * factor, thunderLevel * factor, hazeStrength * factor);
        }

        /** True when every level is zero. */
        public boolean isNone() {
            return rainLevel == 0.0F && thunderLevel == 0.0F && hazeStrength == 0.0F;
        }
    }
}
