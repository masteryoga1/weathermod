package com.weathermod.client.sound;

import com.weathermod.core.PrecipitationKind;
import java.util.Objects;
import net.minecraft.util.Mth;

/**
 * The arithmetic behind the weather ambience: which of the five loops should play, how loud and at what pitch for
 * the rain level, the precipitation kind, a roof over the camera and the wind, plus the easing primitives the loops
 * use. Pure functions on floats so the sound tick allocates one small record and every rule can be unit-tested.
 */
public final class AmbienceMath {
    /** Rain level at which the heavy loop starts to replace the light one. */
    public static final float HEAVY_START = 0.55F;
    /** Rain level at which only the heavy loop plays. */
    public static final float HEAVY_FULL = 1.0F;
    /** What is left of the outside rain loops under a roof. */
    public static final float ROOF_OUTSIDE_FACTOR = 0.25F;
    /** Volume of the roof loop relative to the rain level under a roof. */
    public static final float ROOF_LOOP_FACTOR = 0.9F;
    /** How much of the rain loops sleet keeps. */
    public static final float SLEET_RAIN_FACTOR = 0.8F;
    /** How much of the snow loop sleet keeps. */
    public static final float SLEET_SNOW_FACTOR = 0.5F;
    /** Volume of the snow loop relative to the rain level: snow is quiet. */
    public static final float SNOW_FACTOR = 0.6F;
    /** Wind speed in blocks per second below which the wind loop is silent. */
    public static final float WIND_MIN = 3.0F;
    /** Wind speed range above {@link #WIND_MIN} over which the wind loop reaches full volume. */
    public static final float WIND_RANGE = 12.0F;
    /** Volume of the wind loop at full wind. */
    public static final float WIND_MAX_VOLUME = 0.8F;
    /** A gust counts for this fraction of its speed when it exceeds the sustained wind. */
    public static final float WIND_GUST_WEIGHT = 0.8F;
    /** Largest change of a loop's volume per tick: a full fade takes 25 ticks. */
    public static final float VOLUME_STEP = 0.04F;
    /** Largest change of a loop's pitch per tick: the whole gust swing takes 10 ticks. */
    public static final float PITCH_STEP = 0.01F;
    /** A loop that has been this quiet with a target this low for {@link #STOP_AFTER_TICKS} ticks is stopped. */
    public static final float SILENCE = 0.005F;
    /** Ticks of silence before a loop is stopped rather than kept running at zero volume. */
    public static final int STOP_AFTER_TICKS = 20;
    /** Pitch of the wind loop without a gust. */
    public static final float REST_PITCH = 0.95F;
    /** How far above {@link #REST_PITCH} a full gust takes the wind loop. */
    public static final float GUST_PITCH_RANGE = 0.1F;
    /** Gust excess over the sustained wind, in blocks per second, that reaches the top of the pitch range. */
    public static final float GUST_PITCH_SPEED = 6.0F;

    private AmbienceMath() {
    }

    /**
     * Target volumes of the five loops, each in {@code [0, 1]}, and the pitch of the wind loop.
     *
     * @param rainLight volume of the light rain loop
     * @param rainHeavy volume of the heavy rain loop
     * @param rainRoof  volume of the rain-on-a-roof loop
     * @param snow      volume of the snow loop
     * @param wind      volume of the wind loop
     * @param windPitch pitch of the wind loop, {@link #REST_PITCH} without a gust
     */
    public record Targets(float rainLight, float rainHeavy, float rainRoof, float snow, float wind, float windPitch) {
        /** Every loop silent and the wind at its rest pitch. */
        public static final Targets SILENT = new Targets(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, REST_PITCH);

        /** True when no loop is above {@link #SILENCE}. */
        public boolean isSilent() {
            return rainLight <= SILENCE && rainHeavy <= SILENCE && rainRoof <= SILENCE && snow <= SILENCE
                && wind <= SILENCE;
        }
    }

    /**
     * The Hermite smoothstep of {@code x} between two edges: 0 at or below {@code edge0}, 1 at or above
     * {@code edge1} and {@code t * t * (3 - 2 * t)} in between. Equal edges give a step at that edge; a NaN input is
     * read as 0.
     */
    public static float smoothstep(float edge0, float edge1, float x) {
        if (Float.isNaN(x)) {
            return 0.0F;
        }
        if (edge0 == edge1) {
            return x < edge0 ? 0.0F : 1.0F;
        }
        float t = clampUnit((x - edge0) / (edge1 - edge0));
        return t * t * (3.0F - 2.0F * t);
    }

    /**
     * One easing step: the current value moved toward the target by at most {@code step}, landing exactly on the
     * target once it lies within one step so a fade ends at its target and not a rounding error beside it; this is
     * {@link Mth#approach}, which reads a negative step as its magnitude, with a NaN target leaving the value where
     * it is.
     */
    public static float approach(float current, float target, float step) {
        return Float.isNaN(target) ? current : Mth.approach(current, target, step);
    }

    /**
     * The loop volumes and the wind pitch for the weather at the camera. The rain level is split between the light
     * and heavy loops by {@link #smoothstep}({@link #HEAVY_START}, {@link #HEAVY_FULL}, rain), scaled by the kind
     * (rain 1, sleet {@link #SLEET_RAIN_FACTOR}, otherwise 0); under a roof the outside loops drop to
     * {@link #ROOF_OUTSIDE_FACTOR} and the roof loop plays at {@link #ROOF_LOOP_FACTOR} of the rain; the snow loop is
     * the rain level times {@link #SNOW_FACTOR} (snow 1, sleet {@link #SLEET_SNOW_FACTOR}, otherwise 0); the wind is
     * {@link #windVolume} and {@link #windPitch}. Every volume is scaled by the config volume, the pitch is not.
     *
     * @param rain         the smoothed rain level, clamped to {@code [0, 1]}
     * @param kind         what falls at the camera
     * @param roofed       whether a motion-blocking block is above the camera
     * @param windSpeed    sustained wind speed in blocks per second
     * @param gustSpeed    momentary gust speed in blocks per second
     * @param volumeConfig the {@code soundVolume} config value, clamped to {@code [0, 1]}
     */
    public static Targets targets(float rain, PrecipitationKind kind, boolean roofed, float windSpeed,
                                  float gustSpeed, double volumeConfig) {
        Objects.requireNonNull(kind, "kind");
        float level = clampUnit(rain);
        float volume = clampUnit((float) volumeConfig);
        float rainFactor = switch (kind) {
            case RAIN -> 1.0F;
            case SLEET -> SLEET_RAIN_FACTOR;
            default -> 0.0F;
        };
        float snowFactor = switch (kind) {
            case SNOW -> 1.0F;
            case SLEET -> SLEET_SNOW_FACTOR;
            default -> 0.0F;
        };
        float heavyMix = smoothstep(HEAVY_START, HEAVY_FULL, level);
        float wet = rainFactor * level;
        float light = wet * (1.0F - heavyMix);
        float heavy = wet * heavyMix;
        float roof = 0.0F;
        if (roofed) {
            light *= ROOF_OUTSIDE_FACTOR;
            heavy *= ROOF_OUTSIDE_FACTOR;
            roof = wet * ROOF_LOOP_FACTOR;
        }
        float snow = level * SNOW_FACTOR * snowFactor;
        return new Targets(light * volume, heavy * volume, roof * volume, snow * volume,
            windVolume(windSpeed, gustSpeed) * volume, windPitch(windSpeed, gustSpeed));
    }

    /**
     * Volume of the wind loop before the config: the effective wind, {@code max(windSpeed, gustSpeed *
     * WIND_GUST_WEIGHT)}, mapped from {@link #WIND_MIN} to {@code WIND_MIN + WIND_RANGE} onto 0 to
     * {@link #WIND_MAX_VOLUME}. Negative or NaN speeds count as calm.
     */
    public static float windVolume(float windSpeed, float gustSpeed) {
        float effective = Math.max(nonNegative(windSpeed), nonNegative(gustSpeed) * WIND_GUST_WEIGHT);
        return clampUnit((effective - WIND_MIN) / WIND_RANGE) * WIND_MAX_VOLUME;
    }

    /**
     * Pitch of the wind loop: {@link #REST_PITCH} without a gust, rising by up to {@link #GUST_PITCH_RANGE} as the
     * gust exceeds the sustained wind by {@link #GUST_PITCH_SPEED} blocks per second.
     */
    public static float windPitch(float windSpeed, float gustSpeed) {
        float excess = nonNegative(gustSpeed) - nonNegative(windSpeed);
        return REST_PITCH + GUST_PITCH_RANGE * clampUnit(excess / GUST_PITCH_SPEED);
    }

    private static float nonNegative(float value) {
        return value > 0.0F ? value : 0.0F;
    }

    private static float clampUnit(float value) {
        if (!(value > 0.0F)) {
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }
}
