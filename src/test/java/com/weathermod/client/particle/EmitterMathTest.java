package com.weathermod.client.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.Headings;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.junit.jupiter.api.Test;

/**
 * Checks the particle emitter arithmetic: stochastic counts, the wind thresholds and caps, ring placement, wind
 * velocity and the alpha envelopes. Needs no Minecraft bootstrap.
 */
class EmitterMathTest {
    private static final float EPSILON = 1.0E-5F;
    private static final double TOLERANCE = 1.0E-9D;
    /** The drift factors are floats, so a velocity carries float precision. */
    private static final double VELOCITY_TOLERANCE = 1.0E-6D;

    @Test
    void stochasticCountAveragesToTheExpectationOverManyDraws() {
        RandomSource random = RandomSource.create(20241117L);
        int draws = 200_000;
        long total = 0L;
        for (int i = 0; i < draws; i++) {
            int count = EmitterMath.stochasticCount(1.5F, random);
            assertTrue(count == 1 || count == 2, "1.5 expected gives 1 or 2, got " + count);
            total += count;
        }
        assertEquals(1.5D, total / (double) draws, 0.01D);

        total = 0L;
        for (int i = 0; i < draws; i++) {
            int count = EmitterMath.stochasticCount(0.25F, random);
            assertTrue(count == 0 || count == 1, "0.25 expected gives 0 or 1, got " + count);
            total += count;
        }
        assertEquals(0.25D, total / (double) draws, 0.01D);
    }

    @Test
    void stochasticCountIsExactForWholeExpectations() {
        RandomSource random = RandomSource.create(1L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, EmitterMath.stochasticCount(0.0F, random));
            assertEquals(2, EmitterMath.stochasticCount(2.0F, random));
            assertEquals(5, EmitterMath.stochasticCount(5.0F, random));
        }
    }

    @Test
    void stochasticCountRoundsUpOnlyWhenTheDrawFallsUnderTheFraction() {
        assertEquals(2, EmitterMath.stochasticCount(1.5F, new FixedRandom(0.2F)));
        assertEquals(1, EmitterMath.stochasticCount(1.5F, new FixedRandom(0.7F)));
        assertEquals(1, EmitterMath.stochasticCount(1.5F, new FixedRandom(0.5F)),
            "a draw equal to the fraction does not round up");
        assertEquals(1, EmitterMath.stochasticCount(0.9F, new FixedRandom(0.0F)));
        assertEquals(0, EmitterMath.stochasticCount(0.9F, new FixedRandom(0.95F)));
    }

    @Test
    void stochasticCountIgnoresNonPositiveAndNotANumberExpectations() {
        RandomSource random = new FixedRandom(0.0F);
        assertEquals(0, EmitterMath.stochasticCount(-1.0F, random));
        assertEquals(0, EmitterMath.stochasticCount(-0.5F, random));
        assertEquals(0, EmitterMath.stochasticCount(Float.NaN, random));
    }

    @Test
    void hazeExpectedScalesWithHazeStrengthAndDensity() {
        assertEquals(1.5F, EmitterMath.hazeExpected(1.0F, 1.0D, 1.0D), EPSILON);
        assertEquals(0.75F, EmitterMath.hazeExpected(0.5F, 1.0D, 1.0D), EPSILON);
        assertEquals(3.0F, EmitterMath.hazeExpected(1.0F, 2.0D, 1.0D), EPSILON);
        assertEquals(0.75F, EmitterMath.hazeExpected(1.0F, 1.0D, 0.5D), EPSILON);
        assertEquals(6.0F, EmitterMath.hazeExpected(1.0F, 2.0D, 2.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.hazeExpected(0.0F, 1.0D, 1.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.hazeExpected(1.0F, 0.0D, 1.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.hazeExpected(1.0F, 1.0D, 0.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.hazeExpected(-0.3F, 1.0D, 1.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.hazeExpected(Float.NaN, 1.0D, 1.0D), EPSILON);
    }

    @Test
    void dustExpectedStartsAtTheMinimumWindAndCapsAtTheFullWind() {
        assertEquals(0.0F, EmitterMath.dustExpected(0.0F, 0.0F, 1.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.dustExpected(4.9F, 4.9F, 1.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.dustExpected(EmitterMath.DUST_MIN_WIND, EmitterMath.DUST_MIN_WIND, 1.0D),
            EPSILON, "nothing at the threshold itself");
        assertEquals(2.0F, EmitterMath.dustExpected(10.0F, 10.0F, 1.0D), EPSILON, "half way up the ramp");
        assertEquals(EmitterMath.DUST_PER_TICK,
            EmitterMath.dustExpected(EmitterMath.DUST_FULL_WIND, EmitterMath.DUST_FULL_WIND, 1.0D), EPSILON);
        assertEquals(EmitterMath.DUST_PER_TICK, EmitterMath.dustExpected(40.0F, 40.0F, 1.0D), EPSILON,
            "a gale is capped at the full rate");
    }

    @Test
    void dustExpectedUsesTheStrongerOfWindAndGustAndScalesWithDensity() {
        assertEquals(EmitterMath.DUST_PER_TICK, EmitterMath.dustExpected(0.0F, 15.0F, 1.0D), EPSILON,
            "a gust alone raises dust");
        assertEquals(2.8F, EmitterMath.dustExpected(10.0F, 12.0F, 1.0D), 1.0E-4F);
        assertEquals(2.0F, EmitterMath.dustExpected(15.0F, 15.0F, 0.5D), EPSILON);
        assertEquals(8.0F, EmitterMath.dustExpected(15.0F, 15.0F, 2.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.dustExpected(15.0F, 15.0F, 0.0D), EPSILON);
    }

    @Test
    void leafChanceStartsAtTheMinimumWindAndCapsAtOne() {
        assertEquals(0.0F, EmitterMath.leafChance(0.0F, 1.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.leafChance(EmitterMath.LEAF_MIN_WIND, 1.0D), EPSILON);
        assertEquals(0.5F, EmitterMath.leafChance(9.0F, 1.0D), EPSILON, "half way up the ramp");
        assertEquals(1.0F, EmitterMath.leafChance(EmitterMath.LEAF_FULL_WIND, 1.0D), EPSILON);
        assertEquals(1.0F, EmitterMath.leafChance(30.0F, 1.0D), EPSILON, "a gale is capped at certainty");
        assertEquals(1.0F, EmitterMath.leafChance(9.0F, 2.0D), EPSILON, "density doubles the chance up to 1");
        assertEquals(1.0F, EmitterMath.leafChance(15.0F, 2.0D), EPSILON, "never above 1");
        assertEquals(0.25F, EmitterMath.leafChance(9.0F, 0.5D), EPSILON);
        assertEquals(0.0F, EmitterMath.leafChance(15.0F, 0.0D), EPSILON);
    }

    @Test
    void ringOffsetsStayInsideTheDistanceBandAndCoverEveryQuadrant() {
        RandomSource random = RandomSource.create(7L);
        int draws = 20_000;
        int[] quadrants = new int[4];
        double totalDistance = 0.0D;
        for (int i = 0; i < draws; i++) {
            double[] offset = EmitterMath.ringOffset(random, EmitterMath.HAZE_MIN_DISTANCE,
                EmitterMath.HAZE_MAX_DISTANCE);
            assertEquals(2, offset.length);
            double distance = Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]);
            assertTrue(distance >= EmitterMath.HAZE_MIN_DISTANCE - TOLERANCE,
                "offset too close at " + distance);
            assertTrue(distance <= EmitterMath.HAZE_MAX_DISTANCE + TOLERANCE,
                "offset too far at " + distance);
            totalDistance += distance;
            quadrants[(offset[0] >= 0.0D ? 0 : 1) + (offset[1] >= 0.0D ? 0 : 2)]++;
        }
        double meanDistance = totalDistance / draws;
        assertEquals((EmitterMath.HAZE_MIN_DISTANCE + EmitterMath.HAZE_MAX_DISTANCE) / 2.0D, meanDistance, 0.3D,
            "distances are uniform across the band");
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            assertTrue(quadrants[quadrant] > draws / 8, "quadrant " + quadrant + " only got " + quadrants[quadrant]);
        }
    }

    @Test
    void ringOffsetHonoursAnyBand() {
        RandomSource random = RandomSource.create(99L);
        for (int i = 0; i < 5_000; i++) {
            double[] offset = EmitterMath.ringOffset(random, EmitterMath.DUST_MIN_DISTANCE,
                EmitterMath.DUST_MAX_DISTANCE);
            double distance = Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]);
            assertTrue(distance >= EmitterMath.DUST_MIN_DISTANCE - TOLERANCE);
            assertTrue(distance <= EmitterMath.DUST_MAX_DISTANCE + TOLERANCE);
        }
        double[] fixed = EmitterMath.ringOffset(new FixedRandom(0.0F), 12.0D, 30.0D);
        assertEquals(12.0D, Math.sqrt(fixed[0] * fixed[0] + fixed[1] * fixed[1]), TOLERANCE,
            "a zero draw lands on the inner edge");
    }

    @Test
    void windVelocityFollowsTheHeadingAtTheScaledSpeed() {
        double[] east = EmitterMath.windVelocity(90.0F, 10.0F, EmitterMath.HAZE_DRIFT);
        assertEquals(0.175D, east[0], VELOCITY_TOLERANCE, "10 b/s east at 35 % is 0.175 blocks per tick along +x");
        assertEquals(0.0D, east[1], VELOCITY_TOLERANCE);

        double[] north = EmitterMath.windVelocity(0.0F, 20.0F, EmitterMath.DUST_DRIFT);
        assertEquals(0.0D, north[0], VELOCITY_TOLERANCE);
        assertEquals(-1.0D, north[1], VELOCITY_TOLERANCE, "north is toward negative z");

        double[] south = EmitterMath.windVelocity(180.0F, 20.0F, EmitterMath.LEAF_DRIFT);
        assertEquals(0.0D, south[0], VELOCITY_TOLERANCE);
        assertEquals(0.8D, south[1], VELOCITY_TOLERANCE);

        double[] west = EmitterMath.windVelocity(270.0F, 5.0F, 1.0F);
        assertEquals(-0.25D, west[0], VELOCITY_TOLERANCE);
        assertEquals(0.0D, west[1], VELOCITY_TOLERANCE);
    }

    @Test
    void windVelocityMagnitudeIsSpeedOverTwentyTimesTheFactor() {
        float direction = 37.0F;
        float speed = 12.0F;
        float factor = EmitterMath.LEAF_DRIFT;
        double[] velocity = EmitterMath.windVelocity(direction, speed, factor);
        double expectedMagnitude = speed / 20.0D * factor;
        assertEquals(expectedMagnitude, Math.sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1]),
            VELOCITY_TOLERANCE);
        assertEquals(Headings.directionX(direction) * expectedMagnitude, velocity[0], VELOCITY_TOLERANCE);
        assertEquals(Headings.directionZ(direction) * expectedMagnitude, velocity[1], VELOCITY_TOLERANCE);
    }

    @Test
    void windVelocityIsZeroWithoutWind() {
        double[] still = EmitterMath.windVelocity(123.0F, 0.0F, 1.0F);
        assertEquals(0.0D, still[0], VELOCITY_TOLERANCE);
        assertEquals(0.0D, still[1], VELOCITY_TOLERANCE);
        double[] negative = EmitterMath.windVelocity(45.0F, -3.0F, 1.0F);
        assertEquals(0.0D, negative[0], VELOCITY_TOLERANCE);
        assertEquals(0.0D, negative[1], VELOCITY_TOLERANCE);
    }

    @Test
    void hazeAlphaFadesInHoldsThePeakAndFadesOut() {
        int lifetime = 200;
        assertEquals(0.0F, EmitterMath.hazeAlpha(0, lifetime), EPSILON, "born invisible");
        assertEquals(EmitterMath.HAZE_PEAK_ALPHA * 0.5F, EmitterMath.hazeAlpha(10, lifetime), EPSILON);
        assertEquals(EmitterMath.HAZE_PEAK_ALPHA, EmitterMath.hazeAlpha(EmitterMath.HAZE_FADE_IN_TICKS, lifetime),
            EPSILON);
        assertEquals(EmitterMath.HAZE_PEAK_ALPHA, EmitterMath.hazeAlpha(100, lifetime), EPSILON);
        assertEquals(EmitterMath.HAZE_PEAK_ALPHA,
            EmitterMath.hazeAlpha(lifetime - EmitterMath.HAZE_FADE_OUT_TICKS, lifetime), EPSILON);
        assertEquals(EmitterMath.HAZE_PEAK_ALPHA * 0.5F, EmitterMath.hazeAlpha(180, lifetime), EPSILON);
        assertEquals(0.0F, EmitterMath.hazeAlpha(lifetime, lifetime), EPSILON, "dead is invisible");
        assertEquals(0.0F, EmitterMath.hazeAlpha(5, 0), EPSILON);
        assertEquals(0.0F, EmitterMath.hazeAlpha(-1, lifetime), EPSILON);
        assertEquals(0.45F, EmitterMath.HAZE_PEAK_ALPHA, EPSILON);
        float previous = 0.0F;
        for (int age = 1; age <= EmitterMath.HAZE_FADE_IN_TICKS; age++) {
            float current = EmitterMath.hazeAlpha(age, lifetime);
            assertTrue(current > previous, "fade-in rises at age " + age);
            previous = current;
        }
    }

    @Test
    void hazeAlphaOfAShortLifeTakesTheLowerRamp() {
        assertEquals(EmitterMath.HAZE_PEAK_ALPHA * (15.0F / EmitterMath.HAZE_FADE_OUT_TICKS),
            EmitterMath.hazeAlpha(15, 30), EPSILON, "15 ticks before death the fade-out is the lower ramp");
        assertEquals(EmitterMath.HAZE_PEAK_ALPHA * (5.0F / EmitterMath.HAZE_FADE_IN_TICKS),
            EmitterMath.hazeAlpha(5, 30), EPSILON, "5 ticks after birth the fade-in is the lower ramp");
    }

    @Test
    void dustAlphaHoldsThenFadesOverTheLastThird() {
        int lifetime = 30;
        assertEquals(EmitterMath.DUST_ALPHA, EmitterMath.dustAlpha(0, lifetime), EPSILON);
        assertEquals(EmitterMath.DUST_ALPHA, EmitterMath.dustAlpha(10, lifetime), EPSILON);
        assertEquals(EmitterMath.DUST_ALPHA, EmitterMath.dustAlpha(20, lifetime), EPSILON, "the fade starts here");
        assertEquals(EmitterMath.DUST_ALPHA * 0.5F, EmitterMath.dustAlpha(25, lifetime), EPSILON);
        assertEquals(EmitterMath.DUST_ALPHA * 0.1F, EmitterMath.dustAlpha(29, lifetime), EPSILON);
        assertEquals(0.0F, EmitterMath.dustAlpha(lifetime, lifetime), EPSILON);
        assertEquals(0.0F, EmitterMath.dustAlpha(3, 0), EPSILON);
        assertEquals(EmitterMath.DUST_ALPHA, EmitterMath.dustAlpha(-2, lifetime), EPSILON);
        assertEquals(0.7F, EmitterMath.DUST_ALPHA, EPSILON);
    }

    @Test
    void leafRollStepGrowsWithTheWind() {
        assertEquals(0.1F, EmitterMath.leafRollStep(0.0F), EPSILON);
        assertEquals(0.2F, EmitterMath.leafRollStep(10.0F), EPSILON);
        assertEquals(0.3F, EmitterMath.leafRollStep(20.0F), EPSILON);
        assertEquals(0.1F, EmitterMath.leafRollStep(-5.0F), EPSILON, "a negative speed counts as still air");
    }

    @Test
    void designNumbersMatchTheSpecification() {
        assertEquals(1.5F, EmitterMath.HAZE_PER_TICK, EPSILON);
        assertEquals(16.0D, EmitterMath.HAZE_MIN_DISTANCE, TOLERANCE);
        assertEquals(27.0D, EmitterMath.HAZE_MAX_DISTANCE, TOLERANCE);
        assertTrue(Math.hypot(EmitterMath.HAZE_MAX_DISTANCE, EmitterMath.HAZE_ABOVE_CAMERA) < 32.0D,
            "every haze puff stays inside the 32-block vanilla particle limiter");
        assertEquals(5.0F, EmitterMath.DUST_MIN_WIND, EPSILON);
        assertEquals(15.0F, EmitterMath.DUST_FULL_WIND, EPSILON);
        assertEquals(4.0F, EmitterMath.DUST_PER_TICK, EPSILON);
        assertEquals(3.0F, EmitterMath.LEAF_MIN_WIND, EPSILON);
        assertEquals(15.0F, EmitterMath.LEAF_FULL_WIND, EPSILON);
        assertEquals(6, EmitterMath.LEAF_TRIES);
        assertEquals(20.0D, EmitterMath.LEAF_DISTANCE, TOLERANCE);
        assertEquals(0.35F, EmitterMath.HAZE_DRIFT, EPSILON);
        assertEquals(1.0F, EmitterMath.DUST_DRIFT, EPSILON);
        assertEquals(0.8F, EmitterMath.LEAF_DRIFT, EPSILON);
    }

    /** A random source whose float and double draws always return the same value, for the threshold checks. */
    private static final class FixedRandom implements RandomSource {
        private final float value;

        FixedRandom(float value) {
            this.value = value;
        }

        @Override
        public RandomSource fork() {
            return new FixedRandom(value);
        }

        @Override
        public PositionalRandomFactory forkPositional() {
            throw new UnsupportedOperationException("not needed by the emitter");
        }

        @Override
        public void setSeed(long seed) {
        }

        @Override
        public int nextInt() {
            return 0;
        }

        @Override
        public int nextInt(int bound) {
            return Math.min(bound - 1, (int) (value * bound));
        }

        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public boolean nextBoolean() {
            return value >= 0.5F;
        }

        @Override
        public float nextFloat() {
            return value;
        }

        @Override
        public double nextDouble() {
            return value;
        }

        @Override
        public double nextGaussian() {
            return 0.0D;
        }
    }
}
