package com.weathermod.client.particle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.Headings;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.junit.jupiter.api.Test;

/**
 * Checks the particle emitter arithmetic: stochastic counts, the wind thresholds and caps, ring placement, wind
 * velocity, the pull of the wind on a leaf, the alpha and size envelopes and the dust colour rule. Needs no
 * Minecraft bootstrap.
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
        assertEquals(3.0F, EmitterMath.dustExpected(10.0F, 10.0F, 1.0D), EPSILON, "half way up the ramp");
        assertEquals(EmitterMath.DUST_PER_TICK,
            EmitterMath.dustExpected(EmitterMath.DUST_FULL_WIND, EmitterMath.DUST_FULL_WIND, 1.0D), EPSILON);
        assertEquals(EmitterMath.DUST_PER_TICK, EmitterMath.dustExpected(40.0F, 40.0F, 1.0D), EPSILON,
            "a gale is capped at the full rate");
    }

    @Test
    void dustExpectedUsesTheStrongerOfWindAndGustAndScalesWithDensity() {
        assertEquals(EmitterMath.DUST_PER_TICK, EmitterMath.dustExpected(0.0F, 15.0F, 1.0D), EPSILON,
            "a gust alone raises dust");
        assertEquals(4.2F, EmitterMath.dustExpected(10.0F, 12.0F, 1.0D), 1.0E-4F);
        assertEquals(3.0F, EmitterMath.dustExpected(15.0F, 15.0F, 0.5D), EPSILON);
        assertEquals(12.0F, EmitterMath.dustExpected(15.0F, 15.0F, 2.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.dustExpected(15.0F, 15.0F, 0.0D), EPSILON);
    }

    @Test
    void leafExpectedStartsAtTheMinimumWindAndRisesWithTheSquareOfTheRamp() {
        assertEquals(0.0F, EmitterMath.leafExpected(0.0F, 0.0F, 1.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.leafExpected(EmitterMath.LEAF_MIN_WIND, EmitterMath.LEAF_MIN_WIND, 1.0D),
            EPSILON, "nothing at the threshold itself");
        assertEquals(0.75F, EmitterMath.leafExpected(9.0F, 9.0F, 1.0D), EPSILON,
            "half way up the ramp is a quarter of the full rate");
        assertEquals(EmitterMath.LEAF_PER_TICK,
            EmitterMath.leafExpected(EmitterMath.LEAF_FULL_WIND, EmitterMath.LEAF_FULL_WIND, 1.0D), EPSILON);
        assertEquals(EmitterMath.LEAF_PER_TICK, EmitterMath.leafExpected(30.0F, 30.0F, 1.0D), EPSILON,
            "a gale is capped at the full rate");
        float gale = EmitterMath.leafExpected(14.0F, 14.0F, 1.0D);
        assertEquals(3.0F * (11.0F / 12.0F) * (11.0F / 12.0F), gale, EPSILON);
        assertTrue(gale > 2.5F, "a 14 blocks/s gale sheds more than fifty leaves a second, got " + gale * 20.0F);
        float breeze = EmitterMath.leafExpected(4.5F, 4.5F, 1.0D);
        assertTrue(breeze > 0.0F && breeze < 0.1F, "an everyday breeze sheds only the odd leaf, got " + breeze);
    }

    @Test
    void leafExpectedUsesTheStrongerOfWindAndGustAndScalesWithDensity() {
        assertEquals(EmitterMath.LEAF_PER_TICK, EmitterMath.leafExpected(0.0F, 15.0F, 1.0D), EPSILON,
            "a gust alone tears leaves off");
        assertEquals(0.75F, EmitterMath.leafExpected(4.0F, 9.0F, 1.0D), EPSILON);
        assertEquals(1.5F, EmitterMath.leafExpected(15.0F, 15.0F, 0.5D), EPSILON);
        assertEquals(6.0F, EmitterMath.leafExpected(15.0F, 15.0F, 2.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.leafExpected(15.0F, 15.0F, 0.0D), EPSILON);
        assertEquals(0.0F, EmitterMath.leafExpected(15.0F, 15.0F, -1.0D), EPSILON);
        assertEquals(0, EmitterMath.stochasticCount(EmitterMath.leafExpected(Float.NaN, 15.0F, 1.0D),
            new FixedRandom(0.0F)), "a wind that is not a number sheds nothing");
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
        assertEquals(-0.5D, north[1], VELOCITY_TOLERANCE, "north is toward negative z, at half the wind speed");

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
    void driftPerTickIsTheSpeedPerTickTimesTheFactor() {
        assertEquals(0.56D, EmitterMath.driftPerTick(14.0F, EmitterMath.LEAF_DRIFT), VELOCITY_TOLERANCE);
        assertEquals(0.35D, EmitterMath.driftPerTick(14.0F, EmitterMath.DUST_DRIFT), VELOCITY_TOLERANCE);
        assertEquals(1.0D, EmitterMath.driftPerTick(20.0F, 1.0F), VELOCITY_TOLERANCE);
        assertEquals(0.0D, EmitterMath.driftPerTick(0.0F, 1.0F), TOLERANCE);
        assertEquals(0.0D, EmitterMath.driftPerTick(-4.0F, 1.0F), TOLERANCE);
        assertEquals(0.0D, EmitterMath.driftPerTick(Float.NaN, 1.0F), TOLERANCE);
        double[] velocity = EmitterMath.windVelocity(90.0F, 14.0F, EmitterMath.LEAF_DRIFT);
        assertEquals(EmitterMath.driftPerTick(14.0F, EmitterMath.LEAF_DRIFT), velocity[0], VELOCITY_TOLERANCE,
            "the wind velocity is the drift along the heading");
    }

    @Test
    void leafPullClosesAFixedShareOfTheGapToTheWind() {
        assertEquals(0.08D, EmitterMath.leafPull(0.0D, 1.0D), VELOCITY_TOLERANCE, "a still leaf picks up 8 %");
        assertEquals(0.56D, EmitterMath.leafPull(0.56D, 0.56D), TOLERANCE, "a leaf at the wind velocity stays there");
        assertEquals(0.92D, EmitterMath.leafPull(1.0D, 0.0D), VELOCITY_TOLERANCE, "a leaf slows when the wind drops");
        assertEquals(-0.08D, EmitterMath.leafPull(0.0D, -1.0D), VELOCITY_TOLERANCE, "the pull follows the sign");
        assertEquals(0.08F, EmitterMath.LEAF_WIND_PULL, EPSILON);
    }

    @Test
    void aLeafSettlesNearTheWindVelocityInsteadOfSlowingToAStop() {
        double vanillaFriction = 0.98D;
        double target = EmitterMath.driftPerTick(14.0F, EmitterMath.LEAF_DRIFT);
        double fromRest = 0.0D;
        double fromFull = target;
        for (int tick = 0; tick < 200; tick++) {
            fromRest = EmitterMath.leafPull(fromRest, target) * vanillaFriction;
            fromFull = EmitterMath.leafPull(fromFull, target) * vanillaFriction;
        }
        double pull = EmitterMath.LEAF_WIND_PULL;
        double settled = target * vanillaFriction * pull / (1.0D - vanillaFriction * (1.0D - pull));
        assertEquals(settled, fromRest, 1.0E-6D, "a leaf released by an obstacle picks up speed again");
        assertEquals(settled, fromFull, 1.0E-6D, "a leaf torn off at the wind velocity keeps most of it");
        assertTrue(settled > 0.75D * target && settled < 0.85D * target,
            "the settled speed is about 0.8 of the carrying velocity, got " + settled / target);
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
        assertEquals(0.675F, EmitterMath.HAZE_PEAK_ALPHA, EPSILON);
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
    void hazePeakAlphaIsFullUnderAShaderPackAndKeepsTheLookWithoutOne() {
        assertEquals(0.675F, EmitterMath.hazePeakAlpha(false), EPSILON);
        assertEquals(1.0F, EmitterMath.hazePeakAlpha(true), EPSILON,
            "a pack that darkens particles by their vertex alpha must see a full one");
        assertEquals(0.6F, EmitterMath.HAZE_SPRITE_CENTRE_ALPHA, EPSILON);
        assertEquals(0.405F, EmitterMath.HAZE_SPRITE_CENTRE_ALPHA * EmitterMath.HAZE_PEAK_ALPHA, 1.0E-6F,
            "without a pack the centre of a puff is as opaque as it was with the 0.9 sprite at 0.45");
    }

    @Test
    void hazeAlphaScalesWithTheGivenPeak() {
        int lifetime = 200;
        float packPeak = EmitterMath.hazePeakAlpha(true);
        assertEquals(0.0F, EmitterMath.hazeAlpha(0, lifetime, packPeak), EPSILON);
        assertEquals(0.5F, EmitterMath.hazeAlpha(10, lifetime, packPeak), EPSILON);
        assertEquals(1.0F, EmitterMath.hazeAlpha(100, lifetime, packPeak), EPSILON);
        assertEquals(0.5F, EmitterMath.hazeAlpha(180, lifetime, packPeak), EPSILON);
        assertEquals(0.0F, EmitterMath.hazeAlpha(lifetime, lifetime, packPeak), EPSILON);
        for (int age = 0; age <= lifetime; age += 7) {
            assertEquals(EmitterMath.hazeAlpha(age, lifetime),
                EmitterMath.hazeAlpha(age, lifetime, EmitterMath.HAZE_PEAK_ALPHA), EPSILON,
                "the two-argument form uses the peak without a pack");
        }
    }

    @Test
    void sizeEnvelopeGrowsHoldsAndShrinks() {
        int lifetime = 40;
        assertEquals(0.0F, EmitterMath.sizeEnvelope(0.0F, lifetime, 4.0F, 10.0F), EPSILON, "born with no size");
        assertEquals(0.5F, EmitterMath.sizeEnvelope(2.0F, lifetime, 4.0F, 10.0F), EPSILON);
        assertEquals(1.0F, EmitterMath.sizeEnvelope(4.0F, lifetime, 4.0F, 10.0F), EPSILON);
        assertEquals(1.0F, EmitterMath.sizeEnvelope(30.0F, lifetime, 4.0F, 10.0F), EPSILON, "the shrink starts here");
        assertEquals(0.5F, EmitterMath.sizeEnvelope(35.0F, lifetime, 4.0F, 10.0F), EPSILON);
        assertEquals(0.05F, EmitterMath.sizeEnvelope(39.5F, lifetime, 4.0F, 10.0F), EPSILON);
        assertEquals(0.0F, EmitterMath.sizeEnvelope(40.0F, lifetime, 4.0F, 10.0F), EPSILON, "dead has no size");
        assertEquals(0.0F, EmitterMath.sizeEnvelope(41.0F, lifetime, 4.0F, 10.0F), EPSILON);
        assertEquals(0.0F, EmitterMath.sizeEnvelope(-3.0F, lifetime, 4.0F, 10.0F), EPSILON,
            "a negative age counts as birth");
        assertEquals(0.0F, EmitterMath.sizeEnvelope(1.0F, 0, 4.0F, 10.0F), EPSILON);
        assertEquals(0.0F, EmitterMath.sizeEnvelope(Float.NaN, lifetime, 4.0F, 10.0F), EPSILON);
        assertEquals(1.0F, EmitterMath.sizeEnvelope(0.0F, lifetime, 0.0F, 10.0F), EPSILON, "no grow ramp");
        assertEquals(1.0F, EmitterMath.sizeEnvelope(39.9F, lifetime, 4.0F, 0.0F), EPSILON, "no shrink ramp");
        assertEquals(0.25F, EmitterMath.sizeEnvelope(1.0F, 6, 4.0F, 10.0F), EPSILON, "a short life grows first");
        assertEquals(0.2F, EmitterMath.sizeEnvelope(4.0F, 6, 4.0F, 10.0F), EPSILON, "then the shrink is lower");
    }

    @Test
    void dustScaleGrowsInAndShrinksOverTheLastThird() {
        int lifetime = 30;
        assertEquals(0.0F, EmitterMath.dustScale(0.0F, lifetime), EPSILON);
        assertEquals(0.5F, EmitterMath.dustScale(1.5F, lifetime), EPSILON);
        assertEquals(1.0F, EmitterMath.dustScale(EmitterMath.DUST_GROW_TICKS, lifetime), EPSILON);
        assertEquals(1.0F, EmitterMath.dustScale(10.0F, lifetime), EPSILON);
        assertEquals(1.0F, EmitterMath.dustScale(20.0F, lifetime), EPSILON, "the shrink starts here");
        assertEquals(0.5F, EmitterMath.dustScale(25.0F, lifetime), EPSILON);
        assertEquals(0.1F, EmitterMath.dustScale(29.0F, lifetime), EPSILON);
        assertEquals(0.0F, EmitterMath.dustScale(lifetime, lifetime), EPSILON);
        assertEquals(0.0F, EmitterMath.dustScale(3.0F, 0), EPSILON);
        assertEquals(3.0F, EmitterMath.DUST_GROW_TICKS, EPSILON);
        assertEquals(1.0F / 3.0F, EmitterMath.DUST_SHRINK_FRACTION, EPSILON);
        float previous = 1.0F;
        for (float age = 20.5F; age <= lifetime; age += 0.5F) {
            float current = EmitterMath.dustScale(age, lifetime);
            assertTrue(current < previous, "the shrink falls at age " + age);
            previous = current;
        }
    }

    @Test
    void leafScaleGrowsInAndShrinksOverItsLastTicks() {
        int lifetime = 90;
        assertEquals(0.0F, EmitterMath.leafScale(0.0F, lifetime), EPSILON);
        assertEquals(0.5F, EmitterMath.leafScale(1.0F, lifetime), EPSILON);
        assertEquals(1.0F, EmitterMath.leafScale(EmitterMath.LEAF_GROW_TICKS, lifetime), EPSILON);
        assertEquals(1.0F, EmitterMath.leafScale(80.0F, lifetime), EPSILON, "the shrink starts here");
        assertEquals(0.5F, EmitterMath.leafScale(85.0F, lifetime), EPSILON);
        assertEquals(0.0F, EmitterMath.leafScale(90.0F, lifetime), EPSILON);
        int age = 37;
        int cutShort = age + (int) EmitterMath.LEAF_SHRINK_TICKS;
        assertEquals(1.0F, EmitterMath.leafScale(age, cutShort), EPSILON,
            "a life cut to the shrink ticks from now starts the shrink at full size, without a jump");
        assertEquals(0.9F, EmitterMath.leafScale(age + 1, cutShort), EPSILON);
    }

    @Test
    void dustColorIsPaleOverDarkGroundAndDarkOverLightGround() {
        assertRgb(209, 213, 161, EmitterMath.dustColor(0x7FB238), "grass");
        assertRgb(215, 195, 166, EmitterMath.dustColor(0x976D4D), "dirt");
        assertRgb(205, 196, 175, EmitterMath.dustColor(0x707070), "stone");
        assertRgb(183, 174, 153, EmitterMath.dustColor(0x191919), "black concrete");
        assertRgb(161, 151, 106, EmitterMath.dustColor(0xF7E9A3), "sand");
        assertRgb(166, 166, 166, EmitterMath.dustColor(0xFFFFFF), "snow");
        assertRgb(104, 104, 104, EmitterMath.dustColor(0xA0A0FF), "ice: darkened, and blue capped at red");
    }

    @Test
    void dustColorTreatsAGroundWithoutAColourAsDirtAndIgnoresHighBits() {
        assertEquals(EmitterMath.dustColor(EmitterMath.DUST_DEFAULT_GROUND_RGB), EmitterMath.dustColor(0));
        assertEquals(EmitterMath.dustColor(EmitterMath.DUST_DEFAULT_GROUND_RGB), EmitterMath.dustColor(0xFF000000));
        assertEquals(EmitterMath.dustColor(0x7FB238), EmitterMath.dustColor(0xFF7FB238));
        assertEquals(0, EmitterMath.dustColor(0xFF7FB238) >>> 24, "the result carries no alpha bits");
        assertEquals(0x976D4D, EmitterMath.DUST_DEFAULT_GROUND_RGB);
        assertEquals(0xECE0C4, EmitterMath.DUST_PALE_RGB);
    }

    @Test
    void dustColorAlwaysStandsOutFromItsGroundAndIsNeverBluish() {
        for (int red = 0; red <= 255; red += 15) {
            for (int green = 0; green <= 255; green += 15) {
                for (int blue = 0; blue <= 255; blue += 15) {
                    int ground = red << 16 | green << 8 | blue;
                    if (ground == 0) {
                        continue;
                    }
                    int dust = EmitterMath.dustColor(ground);
                    float groundLuma = luma(ground);
                    float dustLuma = luma(dust);
                    String label = "ground " + Integer.toHexString(ground) + " dust " + Integer.toHexString(dust);
                    if (groundLuma >= EmitterMath.DUST_LIGHT_GROUND_LUMA) {
                        assertTrue(dustLuma <= groundLuma - 0.2F, "dust is clearly darker than light ground: " + label);
                    } else {
                        assertTrue(dustLuma >= groundLuma + 0.15F, "dust is lighter than dark ground: " + label);
                        assertTrue(dustLuma >= 0.6F, "dust over dark ground is pale: " + label);
                    }
                    assertTrue((dust & 0xFF) <= (dust >> 16 & 0xFF), "dust is never bluer than it is red: " + label);
                }
            }
        }
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
        assertEquals(6.0F, EmitterMath.DUST_PER_TICK, EPSILON);
        assertEquals(4.0D, EmitterMath.DUST_MIN_DISTANCE, TOLERANCE);
        assertEquals(16.0D, EmitterMath.DUST_MAX_DISTANCE, TOLERANCE);
        assertEquals(24.0D, EmitterMath.DUST_VERTICAL_REACH, TOLERANCE);
        assertEquals(3.0F, EmitterMath.LEAF_MIN_WIND, EPSILON);
        assertEquals(15.0F, EmitterMath.LEAF_FULL_WIND, EPSILON);
        assertEquals(3.0F, EmitterMath.LEAF_PER_TICK, EPSILON);
        assertEquals(6, EmitterMath.LEAF_TRIES_PER_LEAF);
        assertEquals(2.0D, EmitterMath.LEAF_MIN_DISTANCE, TOLERANCE);
        assertEquals(20.0D, EmitterMath.LEAF_DISTANCE, TOLERANCE);
        assertEquals(8, EmitterMath.LEAF_BELOW_CAMERA);
        assertEquals(16, EmitterMath.LEAF_ABOVE_CAMERA);
        assertEquals(0.5F, EmitterMath.LEAF_TOP_SHARE, EPSILON);
        assertEquals(0.35F, EmitterMath.HAZE_DRIFT, EPSILON);
        assertEquals(0.5F, EmitterMath.DUST_DRIFT, EPSILON);
        assertEquals(0.8F, EmitterMath.LEAF_DRIFT, EPSILON);
        assertEquals(2.0F, EmitterMath.LEAF_GROW_TICKS, EPSILON);
        assertEquals(10.0F, EmitterMath.LEAF_SHRINK_TICKS, EPSILON);
    }

    @Test
    void everySpawnPointStaysInsideTheVanillaParticleLimiter() {
        double limiter = 32.0D;
        assertTrue(Math.hypot(EmitterMath.HAZE_MAX_DISTANCE, EmitterMath.HAZE_ABOVE_CAMERA) < limiter);
        assertTrue(Math.hypot(EmitterMath.DUST_MAX_DISTANCE, EmitterMath.DUST_VERTICAL_REACH) < limiter,
            "a dust puff over a cliff or a valley is still created");
        double leafReach = Math.max(EmitterMath.LEAF_BELOW_CAMERA, EmitterMath.LEAF_ABOVE_CAMERA);
        double leafInset = Math.hypot(CanopyMath.LEAF_EDGE_MARGIN, CanopyMath.LEAF_EDGE_MARGIN);
        assertTrue(Math.hypot(EmitterMath.LEAF_DISTANCE + leafInset, leafReach) < limiter,
            "a leaf off the farthest, highest canopy is still created");
        assertTrue(EmitterMath.DUST_MIN_DISTANCE < EmitterMath.DUST_MAX_DISTANCE);
        assertTrue(EmitterMath.LEAF_MIN_DISTANCE < EmitterMath.LEAF_DISTANCE);
    }

    @Test
    void particleCountsAreBoundedByTheNamedConstants() {
        assertEquals(420.0F, EmitterMath.HAZE_PER_TICK * HazeParticle.MAX_LIFETIME, EPSILON,
            "haze puffs alive at haze, strength and density 1");
        assertEquals(300.0F, EmitterMath.DUST_PER_TICK * WindDustParticle.MAX_LIFETIME, EPSILON,
            "dust puffs alive at density 1");
        assertEquals(360.0F, EmitterMath.LEAF_PER_TICK * WindLeafParticle.MAX_LIFETIME, EPSILON,
            "leaves alive at density 1");
        RandomSource always = new FixedRandom(0.0F);
        double maxDensity = 2.0D;
        assertEquals(12, EmitterMath.stochasticCount(EmitterMath.dustExpected(60.0F, 114.0F, maxDensity), always),
            "no wind raises more dust per tick than the full rate times the density");
        assertEquals(6, EmitterMath.stochasticCount(EmitterMath.leafExpected(60.0F, 114.0F, maxDensity), always),
            "no wind sheds more leaves per tick than the full rate times the density");
    }

    @Test
    void particleDesignNumbersMatchTheSpecification() {
        assertEquals(30, WindDustParticle.MIN_LIFETIME);
        assertEquals(50, WindDustParticle.MAX_LIFETIME);
        assertEquals(0.12F, WindDustParticle.MIN_SIZE, EPSILON);
        assertEquals(0.22F, WindDustParticle.MAX_SIZE, EPSILON);
        assertEquals(0.1F, WindDustParticle.COLLISION_SIZE, EPSILON);
        assertEquals(0.02F, WindDustParticle.GRAVITY, EPSILON);
        assertEquals(0.08F, WindDustParticle.COLOR_JITTER, EPSILON);
        assertEquals(0.9F, WindDustParticle.MIN_SPEED_SCALE, EPSILON);
        assertEquals(1.2F, WindDustParticle.MAX_SPEED_SCALE, EPSILON);
        assertEquals(0.0D, WindDustParticle.MIN_LIFT, TOLERANCE);
        assertEquals(0.04D, WindDustParticle.MAX_LIFT, TOLERANCE);
        assertEquals(1.0E-3D, WindDustParticle.STALLED_STEP, TOLERANCE);
        assertEquals(0.25D, WeatherParticleEmitter.DUST_MIN_HEIGHT, TOLERANCE);
        assertEquals(1.25D, WeatherParticleEmitter.DUST_HEIGHT_RANGE, TOLERANCE);
        assertTrue(WeatherParticleEmitter.DUST_MIN_HEIGHT >= WindDustParticle.MAX_SIZE,
            "a dust puff is drawn around its position and must start clear of the ground");
        double slowestPuff = EmitterMath.driftPerTick(EmitterMath.DUST_MIN_WIND, EmitterMath.DUST_DRIFT)
            * WindDustParticle.MIN_SPEED_SCALE * Math.pow(0.98D, WindDustParticle.MAX_LIFETIME);
        assertTrue(slowestPuff > 10.0D * WindDustParticle.STALLED_STEP,
            "the slowest free puff is never mistaken for a stalled one, it still covers " + slowestPuff);
        assertEquals(60, WindLeafParticle.MIN_LIFETIME);
        assertEquals(120, WindLeafParticle.MAX_LIFETIME);
        assertEquals(0.14F, WindLeafParticle.MIN_SIZE, EPSILON);
        assertEquals(0.20F, WindLeafParticle.MAX_SIZE, EPSILON);
        assertEquals(0.1F, WindLeafParticle.COLLISION_SIZE, EPSILON);
        assertEquals(0.02F, WindLeafParticle.GRAVITY, EPSILON);
        assertEquals(0.65F, WindLeafParticle.MIN_SHADE, EPSILON);
        assertEquals(1.0F, WindLeafParticle.MAX_SHADE, EPSILON);
        assertEquals(0.02F, WindLeafParticle.FLUTTER_AMPLITUDE, EPSILON);
        assertEquals(0.5F, WindLeafParticle.FLUTTER_RATE, EPSILON);
        assertEquals(12, WindLeafParticle.PINNED_TICKS);
        assertTrue(CanopyMath.LEAF_DROP > WindLeafParticle.COLLISION_SIZE,
            "an underside leaf starts with its collision box below the leaf block");
        assertTrue(CanopyMath.LEAF_DROP > WindLeafParticle.MAX_SIZE,
            "an underside leaf is drawn below the leaf block, not through it");
        assertTrue(CanopyMath.LEAF_EDGE_MARGIN >= WindLeafParticle.COLLISION_SIZE / 2.0F,
            "a leaf's collision box stays inside the column that was checked");
        assertEquals(0.04D, WeatherParticleEmitter.LEAF_KICK_MIN, TOLERANCE);
        assertEquals(0.10D, WeatherParticleEmitter.LEAF_KICK_MAX, TOLERANCE);
        assertEquals(-0.02D, WeatherParticleEmitter.LEAF_FALL_SPEED, TOLERANCE);
        assertTrue(WeatherParticleEmitter.LEAF_KICK_MIN > 0.04D * WindLeafParticle.GRAVITY,
            "the weakest kick outweighs the first tick of gravity, so a top leaf starts by rising clear of the canopy");
    }

    @Test
    void hazeParticleNumbersMatchTheSpecification() {
        assertEquals(160, HazeParticle.MIN_LIFETIME);
        assertEquals(280, HazeParticle.MAX_LIFETIME);
        assertEquals(3.0F, HazeParticle.MIN_SIZE, EPSILON);
        assertEquals(6.0F, HazeParticle.MAX_SIZE, EPSILON);
        assertEquals(0.85F, HazeParticle.MIN_GREY, EPSILON);
        assertEquals(0.95F, HazeParticle.MAX_GREY, EPSILON);
        assertTrue(3.0F * HazeParticle.MIN_GREY * HazeParticle.MIN_GREY > 1.0F,
            "shader packs halve the alpha of a translucent particle whose squared channels sum to less than 1");
        assertTrue(HazeParticle.MAX_GREY < 1.0F,
            "shader packs take a pure white translucent particle for another mod's snow");
        assertEquals(0.002D, WeatherParticleEmitter.HAZE_RISE, TOLERANCE);
        assertEquals(1, EmitterMath.HAZE_RISE_MIN);
        assertEquals(10, EmitterMath.HAZE_RISE_MAX);
        assertEquals(6, EmitterMath.HAZE_BELOW_CAMERA);
        assertEquals(16, EmitterMath.HAZE_ABOVE_CAMERA);
        assertEquals(20, EmitterMath.HAZE_FADE_IN_TICKS);
        assertEquals(40, EmitterMath.HAZE_FADE_OUT_TICKS);
        assertEquals(1.0F, EmitterMath.HAZE_PEAK_ALPHA_SHADER_PACK, EPSILON);
    }

    private static void assertRgb(int red, int green, int blue, int actual, String label) {
        assertEquals(red, actual >> 16 & 0xFF, 1.0D, label + " red");
        assertEquals(green, actual >> 8 & 0xFF, 1.0D, label + " green");
        assertEquals(blue, actual & 0xFF, 1.0D, label + " blue");
    }

    private static float luma(int rgb) {
        return (0.299F * (rgb >> 16 & 0xFF) + 0.587F * (rgb >> 8 & 0xFF) + 0.114F * (rgb & 0xFF)) / 255.0F;
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
