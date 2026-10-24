package com.weathermod.client.particle;

import com.weathermod.core.Headings;
import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Pure arithmetic behind the weather particle emitter and the particles themselves: how many particles a tick
 * should spawn, where in a ring around the camera they go, how fast the wind carries them and how their alpha
 * envelopes run. Nothing here touches a level, so every rule is unit-tested.
 */
public final class EmitterMath {
    /** Expected haze puffs per tick at haze 1, haze strength 1 and particle density 1. */
    public static final float HAZE_PER_TICK = 1.5F;
    /** Nearest horizontal distance from the camera a haze puff appears at. */
    public static final double HAZE_MIN_DISTANCE = 16.0D;
    /**
     * Farthest horizontal distance from the camera a haze puff appears at. The vanilla particle path silently drops
     * a particle that is not forced past the limiter when it is more than 32 blocks from the camera, so this and
     * {@link #HAZE_ABOVE_CAMERA} are chosen to keep every puff inside that distance ({@code sqrt(27^2 + 16^2) < 32})
     * rather than spend spawn attempts on puffs that never exist.
     */
    public static final double HAZE_MAX_DISTANCE = 27.0D;
    /** Lowest height above the heightmap a haze puff appears at. */
    public static final int HAZE_RISE_MIN = 1;
    /** Highest height above the heightmap a haze puff appears at. */
    public static final int HAZE_RISE_MAX = 10;
    /** A haze puff never appears more than this far below the camera. */
    public static final int HAZE_BELOW_CAMERA = 6;
    /** A haze puff never appears more than this far above the camera. */
    public static final int HAZE_ABOVE_CAMERA = 16;
    /** Wind speed in blocks per second from which dust starts to blow. */
    public static final float DUST_MIN_WIND = 5.0F;
    /** Wind speed in blocks per second at which dust blows at its full rate. */
    public static final float DUST_FULL_WIND = 15.0F;
    /** Expected dust specks per tick at full wind and density 1. */
    public static final float DUST_PER_TICK = 4.0F;
    /** Nearest horizontal distance from the camera a dust speck appears at. */
    public static final double DUST_MIN_DISTANCE = 6.0D;
    /** Farthest horizontal distance from the camera a dust speck appears at. */
    public static final double DUST_MAX_DISTANCE = 18.0D;
    /** Wind speed in blocks per second from which leaves start to come loose. */
    public static final float LEAF_MIN_WIND = 3.0F;
    /** Wind speed in blocks per second at which every try that finds leaves succeeds at density 1. */
    public static final float LEAF_FULL_WIND = 15.0F;
    /** Random positions tested for leaves each tick. */
    public static final int LEAF_TRIES = 6;
    /** Horizontal half-extent around the camera the leaf tries fall in. */
    public static final double LEAF_DISTANCE = 20.0D;
    /** Fraction of the wind speed a haze puff drifts at. */
    public static final float HAZE_DRIFT = 0.35F;
    /** Fraction of the wind speed a dust speck moves at. */
    public static final float DUST_DRIFT = 1.0F;
    /** Fraction of the wind speed a leaf moves at. */
    public static final float LEAF_DRIFT = 0.8F;

    /** Ticks a haze puff takes to fade from nothing to its peak alpha. */
    public static final int HAZE_FADE_IN_TICKS = 20;
    /** Ticks before its death over which a haze puff fades back to nothing. */
    public static final int HAZE_FADE_OUT_TICKS = 40;
    /** Peak vertex alpha of a haze puff; the texture centre is 0.9, so the core stays above the 0.1 discard. */
    public static final float HAZE_PEAK_ALPHA = 0.45F;
    /** Vertex alpha of a dust speck until it starts to fade. */
    public static final float DUST_ALPHA = 0.7F;
    /** Fraction of a dust speck's lifetime, at the end, over which it fades to nothing. */
    public static final float DUST_FADE_FRACTION = 1.0F / 3.0F;
    /** Radians a leaf rolls per tick in still air. */
    public static final float LEAF_ROLL_BASE = 0.1F;
    /** Wind speed in blocks per second that doubles the leaf roll rate. */
    public static final float LEAF_ROLL_WIND_SCALE = 10.0F;

    private static final double FULL_CIRCLE = 2.0D * Math.PI;

    private EmitterMath() {
    }

    /**
     * Turns an expected count into a whole number of particles: the whole part always, plus one more with a
     * probability equal to the fractional part, so the long-run average equals the expectation. A count that is not
     * positive, or not a number, gives 0.
     */
    public static int stochasticCount(float expected, RandomSource random) {
        if (!(expected > 0.0F)) {
            return 0;
        }
        int whole = (int) expected;
        float fraction = expected - whole;
        return whole + (random.nextFloat() < fraction ? 1 : 0);
    }

    /**
     * Expected haze puffs this tick: the haze level times {@link #HAZE_PER_TICK}, the haze strength and the
     * density; nothing when any of them is not positive.
     */
    public static float hazeExpected(float haze, double hazeStrength, double density) {
        if (!(haze > 0.0F) || !(hazeStrength > 0.0D) || !(density > 0.0D)) {
            return 0.0F;
        }
        return (float) (haze * HAZE_PER_TICK * hazeStrength * density);
    }

    /**
     * Expected dust specks this tick: nothing up to {@link #DUST_MIN_WIND}, rising linearly to {@link #DUST_PER_TICK}
     * at {@link #DUST_FULL_WIND} and capped there, using the stronger of the sustained and the gust speed, times the
     * density.
     */
    public static float dustExpected(float windSpeed, float gustSpeed, double density) {
        if (!(density > 0.0D)) {
            return 0.0F;
        }
        float wind = Math.max(windSpeed, gustSpeed);
        float ramp = Mth.clamp((wind - DUST_MIN_WIND) / (DUST_FULL_WIND - DUST_MIN_WIND), 0.0F, 1.0F);
        return (float) (ramp * DUST_PER_TICK * density);
    }

    /**
     * Chance that one leaf try that found leaves spawns a leaf: nothing up to {@link #LEAF_MIN_WIND}, rising linearly
     * to 1 at {@link #LEAF_FULL_WIND}, times the density, never above 1.
     */
    public static float leafChance(float windSpeed, double density) {
        if (!(density > 0.0D)) {
            return 0.0F;
        }
        float ramp = Mth.clamp((windSpeed - LEAF_MIN_WIND) / (LEAF_FULL_WIND - LEAF_MIN_WIND), 0.0F, 1.0F);
        return (float) Math.min(1.0D, ramp * density);
    }

    /**
     * A random horizontal offset in the ring between two distances: the angle is uniform around the circle and the
     * distance uniform in {@code [minDistance, maxDistance]}.
     *
     * @return {@code {dx, dz}}
     */
    public static double[] ringOffset(RandomSource random, double minDistance, double maxDistance) {
        double angle = random.nextDouble() * FULL_CIRCLE;
        double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
        return new double[] {Math.cos(angle) * distance, Math.sin(angle) * distance};
    }

    /**
     * The per-tick velocity of something carried by the wind: the heading's unit vector (through
     * {@link Headings}) times the speed in blocks per tick times the drift factor; zero for no wind.
     *
     * @return {@code {vx, vz}} in blocks per tick
     */
    public static double[] windVelocity(float directionDegrees, float speedBlocksPerSecond, float factor) {
        if (!(speedBlocksPerSecond > 0.0F)) {
            return new double[] {0.0D, 0.0D};
        }
        double perTick = speedBlocksPerSecond / (double) SharedConstants.TICKS_PER_SECOND * factor;
        return new double[] {Headings.directionX(directionDegrees) * perTick,
            Headings.directionZ(directionDegrees) * perTick};
    }

    /**
     * Vertex alpha of a haze puff at an age: 0 at birth rising to {@link #HAZE_PEAK_ALPHA} over
     * {@link #HAZE_FADE_IN_TICKS}, the peak through the middle, and back to 0 over the last
     * {@link #HAZE_FADE_OUT_TICKS}; the lower of the two ramps wins when a short life makes them overlap.
     */
    public static float hazeAlpha(int age, int lifetime) {
        if (age <= 0 || lifetime <= 0 || age >= lifetime) {
            return 0.0F;
        }
        float fadeIn = Math.min(1.0F, age / (float) HAZE_FADE_IN_TICKS);
        float fadeOut = Math.min(1.0F, (lifetime - age) / (float) HAZE_FADE_OUT_TICKS);
        return HAZE_PEAK_ALPHA * Math.min(fadeIn, fadeOut);
    }

    /**
     * Vertex alpha of a dust speck at an age: {@link #DUST_ALPHA} until the last {@link #DUST_FADE_FRACTION} of its
     * life, then a straight fade to 0 at death.
     */
    public static float dustAlpha(int age, int lifetime) {
        if (lifetime <= 0 || age >= lifetime) {
            return 0.0F;
        }
        float fadeTicks = lifetime * DUST_FADE_FRACTION;
        float remaining = lifetime - Math.max(age, 0);
        if (remaining >= fadeTicks) {
            return DUST_ALPHA;
        }
        return DUST_ALPHA * remaining / fadeTicks;
    }

    /** Radians a leaf rolls per tick: {@link #LEAF_ROLL_BASE} times {@code 1 + windSpeed / LEAF_ROLL_WIND_SCALE}. */
    public static float leafRollStep(float windSpeed) {
        float wind = Math.max(0.0F, windSpeed);
        return LEAF_ROLL_BASE * (1.0F + wind / LEAF_ROLL_WIND_SCALE);
    }
}
