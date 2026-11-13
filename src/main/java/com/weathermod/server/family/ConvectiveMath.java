package com.weathermod.server.family;

import com.weathermod.core.ConvectiveParams;
import com.weathermod.core.Headings;
import com.weathermod.core.WeatherType;
import net.minecraft.SharedConstants;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The arithmetic of the convective family: how often a storm strikes, when it hails, when a tornado roll is due and
 * where its small vortices appear. Pure functions over numbers, the storm's {@link ConvectiveState} and a random
 * source, so every rule can be tested without a level.
 */
public final class ConvectiveMath {
    /** Ticks in a minute; rates of the weather types are given per minute. */
    public static final int TICKS_PER_MINUTE = 1200;
    /** No lightning below this intensity: a forming or dying storm only rains. */
    public static final float MIN_STRIKE_INTENSITY = 0.3F;
    /** Strike intervals vary by this fraction either way (11 s becomes 8 to 14 s). */
    public static final float STRIKE_JITTER = 0.27F;
    /** No hail below this intensity. */
    public static final float MIN_HAIL_INTENSITY = 0.4F;
    /** The first burst of a storm starts after 5 to 15 s so a spawned hailstorm hails at once. */
    public static final int FIRST_HAIL_GAP_MIN_TICKS = 100;
    /** The longest wait for the first burst of a storm, see {@link #FIRST_HAIL_GAP_MIN_TICKS}. */
    public static final int FIRST_HAIL_GAP_MAX_TICKS = 300;
    /** A storm must have lived this long (60 s) before a tornado may be rolled. */
    public static final int TORNADO_MIN_AGE_TICKS = 1200;
    /** A storm weaker than this rolls no tornado. */
    public static final float TORNADO_MIN_INTENSITY = 0.6F;
    /** A tornado is only rolled while a player is this near the spot where it would form. */
    public static final double TORNADO_SIGHT_BLOCKS = 256.0;
    /** A storm produces at most this many tornadoes in its life. */
    public static final int MAX_TORNADOES_PER_STORM = 2;
    /** The second tornado of a storm is rolled at this fraction of the type's chance. */
    public static final float SECOND_TORNADO_CHANCE_FACTOR = 0.5F;
    /** No tornado is rolled when the parent has less life left than this (180 s). */
    public static final int MIN_PARENT_TICKS_LEFT = 3600;
    /** Small vortices appear 0.5 to 0.8 radii ahead of the centre, within 60 degrees of the heading. */
    public static final float LEADING_MIN_FRACTION = 0.5F;
    /** The far end of the band small vortices appear in, see {@link #LEADING_MIN_FRACTION}. */
    public static final float LEADING_MAX_FRACTION = 0.8F;
    /** Small vortices appear within this angle either side of the heading. */
    public static final float LEADING_HALF_ANGLE_DEGREES = 60.0F;

    private ConvectiveMath() {
    }

    /**
     * The ticks from one strike to the next: {@code round(1200 / (perMinute * intensity * frequency))}, varied
     * uniformly by {@link #STRIKE_JITTER} either way and never less than one tick. {@link Integer#MAX_VALUE}, which
     * stands for "never", when {@code perMinute <= 0}, {@code frequency <= 0} or
     * {@code intensity < MIN_STRIKE_INTENSITY}; the random source is not read then.
     *
     * @param perMinute the weather type's strikes per minute at intensity 1
     * @param intensity the storm's current intensity
     * @param frequency the configured multiplier on the strike rate
     */
    public static int strikeIntervalTicks(float perMinute, float intensity, double frequency, RandomSource random) {
        if (!(perMinute > 0.0F) || !(frequency > 0.0) || !(intensity >= MIN_STRIKE_INTENSITY)) {
            return Integer.MAX_VALUE;
        }
        long base = Math.round(TICKS_PER_MINUTE / ((double) perMinute * intensity * frequency));
        double factor = 1.0 + STRIKE_JITTER * (2.0 * random.nextDouble() - 1.0);
        long ticks = Math.round((double) base * factor);
        return (int) Mth.clamp(ticks, 1L, (long) Integer.MAX_VALUE);
    }

    /**
     * Advances a storm's strike countdown by one tick and returns whether the storm strikes now. A countdown that
     * is not running is started when the storm may strike at all; a storm that may not (too weak, a type without
     * lightning, lightning switched off) stays unscheduled and is asked again next tick, so a storm that is still
     * forming begins to strike as soon as it is strong enough. When the countdown reaches zero the next interval
     * is drawn; the storm strikes only when there is one, so a dying storm and a frequency of zero stop the bolts
     * at once.
     */
    static boolean advanceStrike(ConvectiveState state, float perMinute, float intensity, double frequency,
                                 RandomSource random) {
        if (state.strikeTicks < 0) {
            state.strikeTicks = scheduled(strikeIntervalTicks(perMinute, intensity, frequency, random));
            return false;
        }
        if (state.strikeTicks > 0) {
            state.strikeTicks--;
        }
        if (state.strikeTicks > 0) {
            return false;
        }
        int next = strikeIntervalTicks(perMinute, intensity, frequency, random);
        state.strikeTicks = scheduled(next);
        return next != Integer.MAX_VALUE;
    }

    private static int scheduled(int intervalTicks) {
        return intervalTicks == Integer.MAX_VALUE ? ConvectiveState.NOT_SCHEDULED : intervalTicks;
    }

    /** Chance per tick of a memoryless event: {@code perMinute * intensity * multiplier / 1200}, clamped to 0..1. */
    public static float chancePerTick(float perMinute, float intensity, double multiplier) {
        double chance = (double) perMinute * intensity * multiplier / TICKS_PER_MINUTE;
        if (!(chance > 0.0)) {
            return 0.0F;
        }
        return (float) Math.min(1.0, chance);
    }

    /** Seconds picked from a range, as ticks, at least 1. */
    public static int sampleTicks(WeatherType.Range seconds, RandomSource random) {
        return Math.max(1, Math.round(seconds.sample(random) * SharedConstants.TICKS_PER_SECOND));
    }

    /**
     * The hail strength a storm shows: {@code typeHail * intensity}, at most 1, while a burst runs and
     * {@code intensity >= MIN_HAIL_INTENSITY}; otherwise 0.
     */
    public static float hailStrength(float typeHail, float intensity, boolean burstRunning) {
        if (!burstRunning || !(typeHail > 0.0F) || !(intensity >= MIN_HAIL_INTENSITY)) {
            return 0.0F;
        }
        return Math.min(1.0F, typeHail * intensity);
    }

    /**
     * Advances the hail schedule by one tick and returns whether a burst is running afterwards. The first gap of a
     * storm lasts {@link #FIRST_HAIL_GAP_MIN_TICKS} to {@link #FIRST_HAIL_GAP_MAX_TICKS}; after it bursts of the
     * type's burst length and gaps of its gap length follow one another, a burst of {@code n} ticks giving
     * {@code true} exactly {@code n} times in a row. A burst and a gap never count down together. A type without
     * hail has no schedule: nothing is drawn from the random source and the answer is always false.
     */
    public static boolean advanceHail(ConvectiveState state, ConvectiveParams params, RandomSource random) {
        if (!(params.hail() > 0.0F)) {
            state.hailBurstTicks = 0;
            return false;
        }
        if (state.hailBurstTicks > 0) {
            state.hailBurstTicks--;
            if (state.hailBurstTicks > 0) {
                state.hailGapTicks = 0;
                return true;
            }
            state.hailGapTicks = sampleTicks(params.hailGapSeconds(), random);
            return false;
        }
        if (state.hailGapTicks < 0) {
            state.hailGapTicks = Mth.nextInt(random, FIRST_HAIL_GAP_MIN_TICKS, FIRST_HAIL_GAP_MAX_TICKS);
            return false;
        }
        if (state.hailGapTicks > 0) {
            state.hailGapTicks--;
        }
        if (state.hailGapTicks > 0) {
            return false;
        }
        state.hailBurstTicks = sampleTicks(params.hailBurstSeconds(), random);
        return true;
    }

    /**
     * True when a tornado roll is due: no failed roll, fewer than {@link #MAX_TORNADOES_PER_STORM} spawned, age at
     * least {@link #TORNADO_MIN_AGE_TICKS}, intensity at least {@link #TORNADO_MIN_INTENSITY}, at least
     * {@link #MIN_PARENT_TICKS_LEFT} of life left, no child alive, and a player within
     * {@link #TORNADO_SIGHT_BLOCKS} of the spot. A roll that is not due is postponed, never used up.
     *
     * @param liveChildren        how many storms this storm spawned are alive
     * @param nearestPlayerBlocks horizontal distance from the spot where the tornado would form to the nearest
     *                            player; {@link Double#MAX_VALUE} without players
     */
    public static boolean tornadoRollDue(ConvectiveState state, int ageTicks, float intensity, int remainingTicks,
                                         int liveChildren, double nearestPlayerBlocks) {
        return !state.tornadoRollFailed
            && state.tornadoesSpawned < MAX_TORNADOES_PER_STORM
            && ageTicks >= TORNADO_MIN_AGE_TICKS
            && intensity >= TORNADO_MIN_INTENSITY
            && remainingTicks >= MIN_PARENT_TICKS_LEFT
            && liveChildren <= 0
            && nearestPlayerBlocks <= TORNADO_SIGHT_BLOCKS;
    }

    /**
     * A point ahead of the storm for a small vortex: {@link #LEADING_MIN_FRACTION} to
     * {@link #LEADING_MAX_FRACTION} of the radius from the centre, within {@link #LEADING_HALF_ANGLE_DEGREES} of
     * the heading. Writes {@code out[0] = x} and {@code out[1] = z}.
     */
    public static void leadingEdgeSpot(double x, double z, float headingDegrees, float radius, RandomSource random,
                                       double[] out) {
        float bearing = headingDegrees + (2.0F * random.nextFloat() - 1.0F) * LEADING_HALF_ANGLE_DEGREES;
        float fraction = Mth.lerp(random.nextFloat(), LEADING_MIN_FRACTION, LEADING_MAX_FRACTION);
        double distance = (double) Math.max(0.0F, radius) * fraction;
        out[0] = x + Headings.directionX(bearing) * distance;
        out[1] = z + Headings.directionZ(bearing) * distance;
    }
}
