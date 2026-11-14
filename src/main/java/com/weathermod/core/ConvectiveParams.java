package com.weathermod.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;

/**
 * The {@code convective} block of a weather type: how a thunderstorm of that type strikes lightning, hails, throws
 * downbursts, moves and breeds vortices. Every field is optional in JSON; a type of the convective family without
 * the block behaves as {@link #NONE}, a storm that only rains. Read by the convective family system on the server
 * and by the client for the storm's outflow wind.
 *
 * @param lightningPerMinute     strikes per minute at intensity 1
 * @param lightningFireChance    chance that a ground strike starts a fire, 0 to 1
 * @param hail                   hail strength during a burst at intensity 1, 0 to 1; 0 means the type never hails
 * @param hailDamage             damage points of one hail hit
 * @param hailBurstSeconds       the range the length of one hail burst is picked from, in seconds
 * @param hailGapSeconds         the range the pause between two bursts is picked from, in seconds
 * @param downburstsPerMinute    downbursts per minute at intensity 1
 * @param downburstRadius        radius of a downburst, in blocks
 * @param downburstSpeed         peak outflow of a downburst, in blocks per second
 * @param outflowSpeed           straight wind added along the storm heading at full coverage, in blocks per second
 * @param driftMultiplier        multiplier on the configured storm drift factor, 0.25 to 4
 * @param tornadoChance          chance that one storm of this type produces a tornado, 0 to 1
 * @param maxTornadoTier         highest EF tier a tornado of this type may grow to, 0 to 5
 * @param smallVortex            id of the weather type of the small vortices the storm spawns, if any
 * @param smallVorticesPerMinute small vortices per minute at intensity 1
 * @param minSpeed               slowest ground speed of the storm, in blocks per second
 */
public record ConvectiveParams(float lightningPerMinute, float lightningFireChance, float hail, float hailDamage,
                               WeatherType.Range hailBurstSeconds, WeatherType.Range hailGapSeconds,
                               float downburstsPerMinute, float downburstRadius, float downburstSpeed,
                               float outflowSpeed, float driftMultiplier, float tornadoChance, int maxTornadoTier,
                               Optional<ResourceLocation> smallVortex, float smallVorticesPerMinute,
                               float minSpeed) {
    /** The all-defaults value: no lightning, no hail, no downbursts, no outflow, no tornado, no small vortices. */
    public static final ConvectiveParams NONE = new ConvectiveParams(0.0F, 0.0F, 0.0F, 0.0F,
        new WeatherType.Range(30.0F, 90.0F), new WeatherType.Range(60.0F, 180.0F), 0.0F, 24.0F, 20.0F, 0.0F, 1.0F,
        0.0F, 5, Optional.empty(), 0.0F, 0.0F);

    private static final float MIN_DRIFT_MULTIPLIER = 0.25F;
    private static final float MAX_DRIFT_MULTIPLIER = 4.0F;
    private static final int HIGHEST_TIER = 5;

    private static final Codec<Float> NON_NEGATIVE = Codec.floatRange(0.0F, Float.MAX_VALUE);
    private static final Codec<Float> FRACTION = Codec.floatRange(0.0F, 1.0F);
    private static final Codec<WeatherType.Range> POSITIVE_SECONDS =
        WeatherType.Range.CODEC.validate(ConvectiveParams::validateSeconds);

    /**
     * JSON codec. Every key is optional and falls back to the value of {@link #NONE}; defaults are omitted when
     * encoding. Keys are snake case: {@code lightning_per_minute}, {@code hail_burst_seconds} and so on. The group
     * holds 16 fields, the most the codec library allows: a further setting needs a nested record.
     */
    public static final Codec<ConvectiveParams> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        NON_NEGATIVE.optionalFieldOf("lightning_per_minute", NONE.lightningPerMinute())
            .forGetter(ConvectiveParams::lightningPerMinute),
        FRACTION.optionalFieldOf("lightning_fire_chance", NONE.lightningFireChance())
            .forGetter(ConvectiveParams::lightningFireChance),
        FRACTION.optionalFieldOf("hail", NONE.hail()).forGetter(ConvectiveParams::hail),
        NON_NEGATIVE.optionalFieldOf("hail_damage", NONE.hailDamage()).forGetter(ConvectiveParams::hailDamage),
        POSITIVE_SECONDS.optionalFieldOf("hail_burst_seconds", NONE.hailBurstSeconds())
            .forGetter(ConvectiveParams::hailBurstSeconds),
        POSITIVE_SECONDS.optionalFieldOf("hail_gap_seconds", NONE.hailGapSeconds())
            .forGetter(ConvectiveParams::hailGapSeconds),
        NON_NEGATIVE.optionalFieldOf("downbursts_per_minute", NONE.downburstsPerMinute())
            .forGetter(ConvectiveParams::downburstsPerMinute),
        ExtraCodecs.POSITIVE_FLOAT.optionalFieldOf("downburst_radius", NONE.downburstRadius())
            .forGetter(ConvectiveParams::downburstRadius),
        NON_NEGATIVE.optionalFieldOf("downburst_speed", NONE.downburstSpeed())
            .forGetter(ConvectiveParams::downburstSpeed),
        NON_NEGATIVE.optionalFieldOf("outflow_speed", NONE.outflowSpeed())
            .forGetter(ConvectiveParams::outflowSpeed),
        Codec.floatRange(MIN_DRIFT_MULTIPLIER, MAX_DRIFT_MULTIPLIER)
            .optionalFieldOf("drift_multiplier", NONE.driftMultiplier())
            .forGetter(ConvectiveParams::driftMultiplier),
        FRACTION.optionalFieldOf("tornado_chance", NONE.tornadoChance())
            .forGetter(ConvectiveParams::tornadoChance),
        Codec.intRange(0, HIGHEST_TIER).optionalFieldOf("max_tornado_tier", NONE.maxTornadoTier())
            .forGetter(ConvectiveParams::maxTornadoTier),
        ResourceLocation.CODEC.optionalFieldOf("small_vortex").forGetter(ConvectiveParams::smallVortex),
        NON_NEGATIVE.optionalFieldOf("small_vortices_per_minute", NONE.smallVorticesPerMinute())
            .forGetter(ConvectiveParams::smallVorticesPerMinute),
        NON_NEGATIVE.optionalFieldOf("min_speed", NONE.minSpeed()).forGetter(ConvectiveParams::minSpeed)
    ).apply(instance, ConvectiveParams::new));

    public ConvectiveParams {
        Objects.requireNonNull(hailBurstSeconds, "hailBurstSeconds");
        Objects.requireNonNull(hailGapSeconds, "hailGapSeconds");
        Objects.requireNonNull(smallVortex, "smallVortex");
    }

    private static DataResult<WeatherType.Range> validateSeconds(WeatherType.Range seconds) {
        if (seconds.min() <= 0.0F) {
            return DataResult.error(() -> "a range of seconds must be positive, got " + seconds);
        }
        return DataResult.success(seconds);
    }
}
