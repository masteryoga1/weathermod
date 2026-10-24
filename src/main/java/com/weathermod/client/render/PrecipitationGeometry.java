package com.weathermod.client.render;

import com.weathermod.core.Headings;

/**
 * The arithmetic of the precipitation renderer, kept free of any level or render state so it can be unit-tested:
 * wind tilt, the vanilla distance fades, column seeds and hashes, texture scrolling, which part of a column is in
 * view and how far upwind the top of a streak leans. The constants and formulas marked vanilla are copied from
 * {@code LevelRenderer.renderSnowAndRain}.
 */
public final class PrecipitationGeometry {
    /** Horizontal radius in blocks drawn with fast graphics (vanilla). */
    public static final int FAST_RADIUS = 5;
    /** Horizontal radius in blocks drawn with fancy graphics (vanilla). */
    public static final int FANCY_RADIUS = 10;
    /** Horizontal lean of a rain streak per block of height and per block per second of wind. */
    public static final float RAIN_TILT_PER_SPEED = 0.035F;
    /** Largest horizontal lean of a rain streak per block of height. */
    public static final float RAIN_MAX_TILT = 0.6F;
    /** Snow leans this much more than rain in the same wind. */
    public static final float SNOW_TILT_FACTOR = 1.6F;
    /** Largest horizontal lean of a snow streak per block of height. */
    public static final float SNOW_MAX_TILT = 0.9F;
    /** Alpha multiplier of the rain pass of sleet. */
    public static final float SLEET_RAIN_ALPHA = 0.75F;
    /** Alpha multiplier of the snow pass of sleet. */
    public static final float SLEET_SNOW_ALPHA = 0.6F;
    /** Texture v per block of height: one texture repeat covers four blocks (vanilla). */
    public static final float RAIN_UV_PER_BLOCK = 0.25F;
    /** Mask on the tick counter before the rain scroll is computed (vanilla). */
    public static final int RAIN_SCROLL_TICK_MASK = 131071;
    /** Mask on the tick counter before the snow scroll is computed (vanilla). */
    public static final int SNOW_SCROLL_TICK_MASK = 511;
    /** Rain scroll divisor and wrap period in texture repeats (vanilla). */
    public static final float RAIN_SCROLL_PERIOD = 32.0F;
    /** Snow scroll divisor: the texture drifts one repeat every 512 ticks (vanilla). */
    public static final float SNOW_SCROLL_PERIOD = 512.0F;

    private PrecipitationGeometry() {
    }

    /**
     * Horizontal lean of a rain streak per block of height: {@code windSpeed * RAIN_TILT_PER_SPEED} capped at
     * {@link #RAIN_MAX_TILT}, zero for no wind or a negative speed.
     */
    public static float rainTilt(float windSpeed) {
        if (!(windSpeed > 0.0F)) {
            return 0.0F;
        }
        return Math.min(RAIN_MAX_TILT, windSpeed * RAIN_TILT_PER_SPEED);
    }

    /**
     * Horizontal lean of a snow streak per block of height: the rain tilt times {@link #SNOW_TILT_FACTOR}, capped at
     * {@link #SNOW_MAX_TILT}.
     */
    public static float snowTilt(float windSpeed) {
        return Math.min(SNOW_MAX_TILT, rainTilt(windSpeed) * SNOW_TILT_FACTOR);
    }

    /**
     * Vertex alpha of a rain column at a fraction of the draw radius: {@code ((1 - d*d) * 0.5 + 0.5) * level}, so
     * the centre column is at the full level and the edge column at half of it (vanilla).
     */
    public static float rainAlpha(float distanceFraction, float level) {
        return ((1.0F - distanceFraction * distanceFraction) * 0.5F + 0.5F) * level;
    }

    /**
     * Vertex alpha of a snow column at a fraction of the draw radius: {@code ((1 - d*d) * 0.3 + 0.5) * level}
     * (vanilla).
     */
    public static float snowAlpha(float distanceFraction, float level) {
        return ((1.0F - distanceFraction * distanceFraction) * 0.3F + 0.5F) * level;
    }

    /**
     * Seed of the per-column random source: {@code (x*x*3121 + x*45238971) ^ (z*z*418711 + z*13761)} in wrapping
     * int arithmetic, widened to a long (vanilla).
     */
    public static long columnSeed(int x, int z) {
        return (long) (x * x * 3121 + x * 45238971 ^ z * z * 418711 + z * 13761);
    }

    /**
     * Per-column phase of the rain scroll in {@code [0, 255]}:
     * {@code (x*x*3121 + x*45238971 + z*z*418711 + z*13761) & 0xFF} (vanilla).
     */
    public static int columnHash(int x, int z) {
        return (x * x * 3121 + x * 45238971 + z * z * 418711 + z * 13761) & 0xFF;
    }

    /**
     * Texture v offset of a rain column this frame:
     * {@code (-((ticks & 131071) + hash + partialTick) / 32 * speed) % 32}, always in {@code (-32, 0]} so the
     * streaks run downward (vanilla).
     *
     * @param speed the column's fall speed, {@code 3 + random} in vanilla
     */
    public static float rainScroll(int ticks, int columnHash, float partialTick, float speed) {
        float travel = -((float) ((ticks & RAIN_SCROLL_TICK_MASK) + columnHash) + partialTick)
            / RAIN_SCROLL_PERIOD * speed;
        return travel % RAIN_SCROLL_PERIOD;
    }

    /**
     * Texture v offset of every snow column this frame: {@code -((ticks & 511) + partialTick) / 512}, in
     * {@code (-1, 0]} (vanilla).
     */
    public static float snowScroll(int ticks, float partialTick) {
        return -((float) (ticks & SNOW_SCROLL_TICK_MASK) + partialTick) / SNOW_SCROLL_PERIOD;
    }

    /** Lowest block of a column's streak: the camera height minus the radius, raised to the ground height (vanilla). */
    public static int columnBottom(int camY, int groundHeight, int radius) {
        return Math.max(camY - radius, groundHeight);
    }

    /** Highest block of a column's streak: the camera height plus the radius, raised to the ground height (vanilla). */
    public static int columnTop(int camY, int groundHeight, int radius) {
        return Math.max(camY + radius, groundHeight);
    }

    /** Height the column's light is sampled at: the ground, or the camera height when the ground is below it (vanilla). */
    public static int columnLightY(int camY, int groundHeight) {
        return Math.max(groundHeight, camY);
    }

    /**
     * The {@code {bottom, top, lightY}} of a column from {@link #columnBottom}, {@link #columnTop} and
     * {@link #columnLightY}, or {@code null} when the ground is at or above the top of the window so nothing of the
     * column is in view (vanilla, where the column is skipped when the two heights coincide).
     *
     * @param camY         the camera's block y
     * @param groundHeight the motion-blocking height of the column
     * @param radius       the draw radius
     */
    public static int[] columnBounds(int camY, int groundHeight, int radius) {
        int bottom = columnBottom(camY, groundHeight, radius);
        int top = columnTop(camY, groundHeight, radius);
        if (bottom == top) {
            return null;
        }
        return new int[] {bottom, top, columnLightY(camY, groundHeight)};
    }

    /**
     * Horizontal offset {@code {dx, dz}} of the top of a streak relative to its bottom: upwind, opposite the
     * direction the wind blows toward, by {@code tilt * height} blocks, using the compass convention of
     * {@link Headings}.
     *
     * @param windDirectionDegrees the direction the wind blows toward, 0 north, 90 east
     * @param tilt                 horizontal blocks of lean per block of height
     * @param height               the streak height in blocks
     */
    public static double[] topOffset(float windDirectionDegrees, float tilt, float height) {
        double lean = (double) tilt * height;
        if (lean == 0.0D) {
            return new double[] {0.0D, 0.0D};
        }
        return new double[] {
            -Headings.directionX(windDirectionDegrees) * lean,
            -Headings.directionZ(windDirectionDegrees) * lean
        };
    }
}
