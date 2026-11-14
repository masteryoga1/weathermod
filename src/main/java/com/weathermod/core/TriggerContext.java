package com.weathermod.core;

import java.util.Objects;

/**
 * What the director knows at a planned spawn position, for {@link Trigger#accepts} and {@link Trigger#weight}.
 *
 * @param windSpeed the wind there, gusts included, in blocks per second
 * @param day       whether it is day by the clock, see {@link #isDay}
 * @param front     the front lying over the position: {@code ""}, {@link Trigger#FRONT_COLD} or
 *                  {@link Trigger#FRONT_WARM}
 */
public record TriggerContext(float windSpeed, boolean day, String front) {
    private static final long DAY_LENGTH_TICKS = 24000L;
    private static final long DAYLIGHT_TICKS = 12000L;

    public TriggerContext {
        Objects.requireNonNull(front, "front");
    }

    /**
     * Day by the clock, independent of the weather: not a fixed-time dimension and {@code dayTime} mod 24000 below
     * 12000. The level's own day test turns false under any thunderstorm, which would end a daytime trigger the
     * moment the sky darkens; a fixed-time dimension (the Nether, the End) has no day at all.
     *
     * @param dayTime   the level's day time in ticks
     * @param fixedTime whether the dimension has a fixed time
     */
    public static boolean isDay(long dayTime, boolean fixedTime) {
        return !fixedTime && Math.floorMod(dayTime, DAY_LENGTH_TICKS) < DAYLIGHT_TICKS;
    }
}
