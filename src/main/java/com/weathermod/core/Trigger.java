package com.weathermod.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import net.minecraft.util.StringRepresentable;

/**
 * Conditions beyond climate and biome under which a type may form naturally: a wind window, a time of day and a
 * front. The director builds a {@link TriggerContext} at the planned spawn position and asks {@link #accepts} and
 * {@link #weight}; storms spawned by command ignore the trigger.
 *
 * @param minWind     slowest wind the type forms in, in blocks per second
 * @param maxWind     fastest wind the type forms in, in blocks per second
 * @param time        the time of day the type forms at
 * @param front       the front the type needs, {@link #FRONT_COLD} or {@link #FRONT_WARM}; empty for none
 * @param frontWeight multiplier on the type's spawn weight along a cold front, 1 to 16
 */
public record Trigger(float minWind, float maxWind, TimeOfDay time, String front, int frontWeight) {
    /** The part of the day a type may form in, by the clock and independent of the weather. */
    public enum TimeOfDay implements StringRepresentable {
        /** Day and night alike. */
        ANY("any"),
        /** Only while the sun is up. */
        DAY("day"),
        /** Only while the sun is down. */
        NIGHT("night");

        /** Serializes a time of day as its lower-case name, for example {@code "night"}. */
        public static final Codec<TimeOfDay> CODEC = StringRepresentable.fromEnum(TimeOfDay::values);

        private final String serializedName;

        TimeOfDay(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }

        /** True when this time of day covers a moment that is day ({@code true}) or night ({@code false}). */
        public boolean matches(boolean day) {
            return this == ANY || (this == DAY) == day;
        }
    }

    /** Name of a cold front, one of the front names the director knows: colder air is arriving with the wind. */
    public static final String FRONT_COLD = "cold";
    /** Name of a warm front, one of the front names the director knows: warmer air is arriving with the wind. */
    public static final String FRONT_WARM = "warm";

    /** No condition: wind 0..64 blocks per second, any time, no front, front weight 1. */
    public static final Trigger ANY = new Trigger(0.0F, 64.0F, TimeOfDay.ANY, "", 1);

    private static final int MAX_FRONT_WEIGHT = 16;

    private static final Codec<String> FRONT_CODEC = Codec.STRING.validate(Trigger::validateFront);

    /**
     * JSON codec with the keys {@code min_wind}, {@code max_wind}, {@code time}, {@code front} and
     * {@code front_weight}. All are optional, fall back to the values of {@link #ANY} and are omitted when encoding
     * those. A front other than {@code ""}, {@code "cold"} or {@code "warm"}, a front weight outside 1..16 and a
     * minimum wind above the maximum are rejected.
     */
    public static final Codec<Trigger> CODEC = RecordCodecBuilder.<Trigger>create(instance -> instance.group(
        Codec.FLOAT.optionalFieldOf("min_wind", ANY.minWind()).forGetter(Trigger::minWind),
        Codec.FLOAT.optionalFieldOf("max_wind", ANY.maxWind()).forGetter(Trigger::maxWind),
        TimeOfDay.CODEC.optionalFieldOf("time", ANY.time()).forGetter(Trigger::time),
        FRONT_CODEC.optionalFieldOf("front", ANY.front()).forGetter(Trigger::front),
        Codec.intRange(1, MAX_FRONT_WEIGHT).optionalFieldOf("front_weight", ANY.frontWeight())
            .forGetter(Trigger::frontWeight)
    ).apply(instance, Trigger::new)).validate(Trigger::validate);

    public Trigger {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(front, "front");
    }

    private static DataResult<String> validateFront(String front) {
        if (front.isEmpty() || FRONT_COLD.equals(front) || FRONT_WARM.equals(front)) {
            return DataResult.success(front);
        }
        return DataResult.error(() -> "front must be \"\", \"" + FRONT_COLD + "\" or \"" + FRONT_WARM
            + "\", got \"" + front + "\"");
    }

    private static DataResult<Trigger> validate(Trigger trigger) {
        if (Float.isNaN(trigger.minWind) || Float.isNaN(trigger.maxWind)) {
            return DataResult.error(() -> "min_wind and max_wind must be numbers");
        }
        if (trigger.minWind > trigger.maxWind) {
            return DataResult.error(() -> "min_wind " + trigger.minWind
                + " is greater than max_wind " + trigger.maxWind);
        }
        return DataResult.success(trigger);
    }

    /**
     * True when the wind lies in {@code [minWind, maxWind]}, the time matches, and {@code front} is empty or
     * equals {@code context.front()}.
     */
    public boolean accepts(TriggerContext context) {
        return context.windSpeed() >= minWind && context.windSpeed() <= maxWind
            && time.matches(context.day())
            && (front.isEmpty() || front.equals(context.front()));
    }

    /**
     * The selection weight under this context: {@code baseWeight * frontWeight} along a cold front, otherwise
     * {@code baseWeight}. The product saturates at {@link Integer#MAX_VALUE}.
     */
    public int weight(int baseWeight, TriggerContext context) {
        if (!FRONT_COLD.equals(context.front())) {
            return baseWeight;
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (long) baseWeight * frontWeight));
    }
}
