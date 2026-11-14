package com.weathermod.server.family;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.ConvectiveParams;
import com.weathermod.core.Headings;
import com.weathermod.core.WeatherType;
import com.weathermod.server.impact.HailSystem;
import java.util.Optional;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Pins the arithmetic of the convective family: strike intervals for the shipped lightning rates, the strike
 * countdown, the chance of memoryless events, the hail schedule, every clause of the tornado roll and the spot of
 * small vortices. The rule that spares livestock from hail is pinned here as well. Needs no Minecraft bootstrap.
 */
class ConvectiveMathTest {
    /** Strikes per minute of thunderstorm, severe thunderstorm, hailstorm, squall line, derecho and supercell. */
    private static final float[] SHIPPED_RATES = {5.5F, 11.0F, 4.0F, 6.0F, 8.0F, 15.0F};
    private static final int SAMPLES = 20_000;
    private static final float EPSILON = 1.0E-6F;

    private static ConvectiveParams hailParams(float hail, WeatherType.Range burstSeconds,
                                               WeatherType.Range gapSeconds) {
        return new ConvectiveParams(0.0F, 0.0F, hail, 2.0F, burstSeconds, gapSeconds, 0.0F, 24.0F, 20.0F, 0.0F,
            1.0F, 0.0F, 5, Optional.empty(), 0.0F, 0.0F);
    }

    /** Asserts that an interval lies within the jitter around its base, allowing for rounding to whole ticks. */
    private static void assertWithinJitter(long base, int ticks) {
        double low = Math.floor(base * (1.0 - ConvectiveMath.STRIKE_JITTER));
        double high = Math.ceil(base * (1.0 + ConvectiveMath.STRIKE_JITTER));
        assertTrue(ticks >= low && ticks <= high,
            "an interval between " + low + " and " + high + " around " + base + ", got " + ticks);
    }

    private static void assertNeverBurstAndGap(ConvectiveState state, boolean running) {
        assertFalse(state.hailBurstTicks > 0 && state.hailGapTicks > 0, "burst and gap count down together: " + state);
        assertEquals(running, state.hailBurstTicks > 0, "the answer and the burst counter disagree: " + state);
    }

    @Test
    void theConstantsAreTheAgreedOnes() {
        assertEquals(1200, ConvectiveMath.TICKS_PER_MINUTE);
        assertEquals(0.3F, ConvectiveMath.MIN_STRIKE_INTENSITY);
        assertEquals(0.27F, ConvectiveMath.STRIKE_JITTER);
        assertEquals(0.4F, ConvectiveMath.MIN_HAIL_INTENSITY);
        assertEquals(100, ConvectiveMath.FIRST_HAIL_GAP_MIN_TICKS);
        assertEquals(300, ConvectiveMath.FIRST_HAIL_GAP_MAX_TICKS);
        assertEquals(1200, ConvectiveMath.TORNADO_MIN_AGE_TICKS);
        assertEquals(0.6F, ConvectiveMath.TORNADO_MIN_INTENSITY);
        assertEquals(256.0, ConvectiveMath.TORNADO_SIGHT_BLOCKS);
        assertEquals(2, ConvectiveMath.MAX_TORNADOES_PER_STORM);
        assertEquals(0.5F, ConvectiveMath.SECOND_TORNADO_CHANCE_FACTOR);
        assertEquals(3600, ConvectiveMath.MIN_PARENT_TICKS_LEFT);
        assertEquals(0.5F, ConvectiveMath.LEADING_MIN_FRACTION);
        assertEquals(0.8F, ConvectiveMath.LEADING_MAX_FRACTION);
        assertEquals(60.0F, ConvectiveMath.LEADING_HALF_ANGLE_DEGREES);
    }

    @Test
    void strikeIntervalsOfEveryShippedRateHaveTheirMeanAndBounds() {
        for (int index = 0; index < SHIPPED_RATES.length; index++) {
            float rate = SHIPPED_RATES[index];
            // 218, 109, 300, 200, 150 and 80 ticks.
            long base = Math.round(1200.0 / rate);
            RandomSource random = RandomSource.create(1000L + index);
            long sum = 0L;
            int shortest = Integer.MAX_VALUE;
            int longest = 0;
            for (int i = 0; i < SAMPLES; i++) {
                int ticks = ConvectiveMath.strikeIntervalTicks(rate, 1.0F, 1.0, random);
                assertWithinJitter(base, ticks);
                sum += ticks;
                shortest = Math.min(shortest, ticks);
                longest = Math.max(longest, ticks);
            }
            double mean = sum / (double) SAMPLES;
            assertEquals(base, mean, base * 0.02, "mean interval at " + rate + " strikes per minute");
            // The jitter is used to both of its ends, not only allowed.
            assertTrue(shortest <= base * (1.0 - ConvectiveMath.STRIKE_JITTER) + 2.0,
                "shortest interval at " + rate + " strikes per minute was " + shortest);
            assertTrue(longest >= base * (1.0 + ConvectiveMath.STRIKE_JITTER) - 2.0,
                "longest interval at " + rate + " strikes per minute was " + longest);
        }
    }

    @Test
    void anElevenSecondIntervalBecomesEightToFourteenSeconds() {
        RandomSource random = RandomSource.create(77L);
        for (int i = 0; i < SAMPLES; i++) {
            // 5.5 strikes per minute: 218 ticks, varied to 159..277.
            int ticks = ConvectiveMath.strikeIntervalTicks(5.5F, 1.0F, 1.0, random);
            assertTrue(ticks >= 159 && ticks <= 277, "an interval of 8 to 14 seconds, got " + ticks + " ticks");
        }
    }

    @Test
    void frequencyTwoHalvesTheInterval() {
        for (float rate : SHIPPED_RATES) {
            long halfBase = Math.round(600.0 / rate);
            for (long seed = 0L; seed < 200L; seed++) {
                int once = ConvectiveMath.strikeIntervalTicks(rate, 1.0F, 1.0, RandomSource.create(seed));
                int twice = ConvectiveMath.strikeIntervalTicks(rate, 1.0F, 2.0, RandomSource.create(seed));
                assertWithinJitter(halfBase, twice);
                // The same roll at twice the frequency; whole ticks leave up to three ticks of difference.
                assertTrue(Math.abs(once - 2 * twice) <= 3,
                    "half of " + once + " ticks at " + rate + " strikes per minute, got " + twice);
            }
        }
    }

    @Test
    void aWeakerStormStrikesLessOften() {
        RandomSource random = RandomSource.create(5L);
        for (int i = 0; i < 1000; i++) {
            // 6 strikes per minute at half intensity are 3 per minute: 400 ticks.
            assertWithinJitter(400L, ConvectiveMath.strikeIntervalTicks(6.0F, 0.5F, 1.0, random));
        }
    }

    @Test
    void thereIsNoIntervalBelowTheIntensityFloorOrWithoutARate() {
        RandomSource random = RandomSource.create(1L);
        assertEquals(Integer.MAX_VALUE, ConvectiveMath.strikeIntervalTicks(11.0F, 0.29F, 1.0, random));
        assertEquals(Integer.MAX_VALUE, ConvectiveMath.strikeIntervalTicks(11.0F, 0.0F, 1.0, random));
        assertEquals(Integer.MAX_VALUE, ConvectiveMath.strikeIntervalTicks(11.0F, Float.NaN, 1.0, random));
        assertEquals(Integer.MAX_VALUE, ConvectiveMath.strikeIntervalTicks(11.0F, 1.0F, 0.0, random));
        assertEquals(Integer.MAX_VALUE, ConvectiveMath.strikeIntervalTicks(11.0F, 1.0F, -1.0, random));
        assertEquals(Integer.MAX_VALUE, ConvectiveMath.strikeIntervalTicks(0.0F, 1.0F, 1.0, random));
        assertEquals(Integer.MAX_VALUE, ConvectiveMath.strikeIntervalTicks(-2.0F, 1.0F, 1.0, random));
        // The floor itself strikes: 11 per minute at 0.3 are 3.3 per minute, 364 ticks.
        assertWithinJitter(364L,
            ConvectiveMath.strikeIntervalTicks(11.0F, ConvectiveMath.MIN_STRIKE_INTENSITY, 1.0, random));
    }

    @Test
    void anIntervalIsNeverShorterThanOneTick() {
        RandomSource random = RandomSource.create(2L);
        for (int i = 0; i < 100; i++) {
            assertEquals(1, ConvectiveMath.strikeIntervalTicks(100_000.0F, 1.0F, 10.0, random));
        }
    }

    @Test
    void theStrikeCountdownStrikesWhenItRunsOutAndStartsAgain() {
        ConvectiveState state = new ConvectiveState();
        RandomSource random = RandomSource.create(7L);
        // A storm that is still forming is not scheduled, however long it is asked.
        for (int i = 0; i < 50; i++) {
            assertFalse(ConvectiveMath.advanceStrike(state, 6.0F, 0.1F, 1.0, random));
            assertEquals(ConvectiveState.NOT_SCHEDULED, state.strikeTicks);
        }
        // Strong enough: the first call schedules, and the strike comes exactly when the countdown runs out.
        assertFalse(ConvectiveMath.advanceStrike(state, 6.0F, 1.0F, 1.0, random));
        int first = state.strikeTicks;
        assertWithinJitter(200L, first);
        for (int i = 1; i < first; i++) {
            assertFalse(ConvectiveMath.advanceStrike(state, 6.0F, 1.0F, 1.0, random), "struck " + i + " ticks in");
        }
        assertTrue(ConvectiveMath.advanceStrike(state, 6.0F, 1.0F, 1.0, random));
        int second = state.strikeTicks;
        assertWithinJitter(200L, second);
        for (int i = 1; i < second; i++) {
            assertFalse(ConvectiveMath.advanceStrike(state, 6.0F, 1.0F, 1.0, random), "struck " + i + " ticks in");
        }
        assertTrue(ConvectiveMath.advanceStrike(state, 6.0F, 1.0F, 1.0, random));
    }

    @Test
    void aCountdownThatRunsOutUnderADyingStormStrikesNothing() {
        ConvectiveState state = new ConvectiveState();
        RandomSource random = RandomSource.create(8L);
        assertFalse(ConvectiveMath.advanceStrike(state, 6.0F, 1.0F, 1.0, random));
        int interval = state.strikeTicks;
        for (int i = 1; i < interval; i++) {
            assertFalse(ConvectiveMath.advanceStrike(state, 6.0F, 1.0F, 1.0, random));
        }
        assertEquals(1, state.strikeTicks);
        assertFalse(ConvectiveMath.advanceStrike(state, 6.0F, 0.2F, 1.0, random));
        assertEquals(ConvectiveState.NOT_SCHEDULED, state.strikeTicks);
        // Once it is strong again the storm is scheduled anew.
        assertFalse(ConvectiveMath.advanceStrike(state, 6.0F, 1.0F, 1.0, random));
        assertWithinJitter(200L, state.strikeTicks);
    }

    @Test
    void aFrequencyOfZeroStopsTheBoltsAtOnce() {
        ConvectiveState state = new ConvectiveState();
        state.strikeTicks = 1;
        RandomSource random = RandomSource.create(9L);
        assertFalse(ConvectiveMath.advanceStrike(state, 15.0F, 1.0F, 0.0, random));
        assertEquals(ConvectiveState.NOT_SCHEDULED, state.strikeTicks);
        for (int i = 0; i < 100; i++) {
            assertFalse(ConvectiveMath.advanceStrike(state, 15.0F, 1.0F, 0.0, random));
        }
        assertEquals(ConvectiveState.NOT_SCHEDULED, state.strikeTicks);
    }

    @Test
    void theChancePerTickIsTheRateSpreadOverAMinute() {
        assertEquals(1.0F / 1200.0F, ConvectiveMath.chancePerTick(1.0F, 1.0F, 1.0), EPSILON);
        assertEquals(0.5F / 1200.0F, ConvectiveMath.chancePerTick(2.0F, 0.5F, 0.5), EPSILON);
        assertEquals(0.01F, ConvectiveMath.chancePerTick(2.0F, 1.0F, 6.0), EPSILON);
        assertEquals(0.5F, ConvectiveMath.chancePerTick(600.0F, 1.0F, 1.0), EPSILON);
    }

    @Test
    void theChancePerTickIsClampedToZeroAndOne() {
        assertEquals(1.0F, ConvectiveMath.chancePerTick(1200.0F, 1.0F, 1.0));
        assertEquals(1.0F, ConvectiveMath.chancePerTick(5000.0F, 1.0F, 1.0));
        assertEquals(1.0F, ConvectiveMath.chancePerTick(600.0F, 1.0F, 10.0));
        assertEquals(0.0F, ConvectiveMath.chancePerTick(0.0F, 1.0F, 1.0));
        assertEquals(0.0F, ConvectiveMath.chancePerTick(1.0F, 0.0F, 1.0));
        assertEquals(0.0F, ConvectiveMath.chancePerTick(1.0F, 1.0F, 0.0));
        assertEquals(0.0F, ConvectiveMath.chancePerTick(1.0F, 1.0F, -3.0));
        assertEquals(0.0F, ConvectiveMath.chancePerTick(1.0F, -1.0F, 1.0));
        assertEquals(0.0F, ConvectiveMath.chancePerTick(Float.NaN, 1.0F, 1.0));
    }

    @Test
    void sampledSecondsBecomeTicksAndNeverLessThanOne() {
        RandomSource random = RandomSource.create(3L);
        assertEquals(900, ConvectiveMath.sampleTicks(WeatherType.Range.of(45.0F), random));
        assertEquals(1, ConvectiveMath.sampleTicks(WeatherType.Range.of(0.0F), random));
        assertEquals(1, ConvectiveMath.sampleTicks(WeatherType.Range.of(0.01F), random));
        WeatherType.Range range = new WeatherType.Range(30.0F, 90.0F);
        for (int i = 0; i < 1000; i++) {
            int ticks = ConvectiveMath.sampleTicks(range, random);
            assertTrue(ticks >= 600 && ticks <= 1800, "30 to 90 seconds in ticks, got " + ticks);
        }
    }

    @Test
    void hailNeedsARunningBurstAndEnoughIntensity() {
        assertEquals(0.8F, ConvectiveMath.hailStrength(0.8F, 1.0F, true), EPSILON);
        assertEquals(0.4F, ConvectiveMath.hailStrength(0.8F, 0.5F, true), EPSILON);
        assertEquals(0.8F * 0.4F, ConvectiveMath.hailStrength(0.8F, ConvectiveMath.MIN_HAIL_INTENSITY, true), EPSILON);
        assertEquals(0.0F, ConvectiveMath.hailStrength(0.8F, 0.39F, true));
        assertEquals(0.0F, ConvectiveMath.hailStrength(0.8F, 1.0F, false));
        assertEquals(0.0F, ConvectiveMath.hailStrength(0.0F, 1.0F, true));
        assertEquals(0.0F, ConvectiveMath.hailStrength(0.8F, Float.NaN, true));
    }

    @Test
    void theFirstHailGapLastsFiveToFifteenSeconds() {
        ConvectiveParams params = hailParams(1.0F, new WeatherType.Range(45.0F, 120.0F),
            new WeatherType.Range(30.0F, 90.0F));
        int shortest = Integer.MAX_VALUE;
        int longest = 0;
        for (long seed = 0L; seed < 500L; seed++) {
            ConvectiveState state = new ConvectiveState();
            RandomSource random = RandomSource.create(seed);
            int gap = 0;
            while (!ConvectiveMath.advanceHail(state, params, random)) {
                gap++;
                assertTrue(gap <= 300, "the first burst is late");
            }
            assertTrue(gap >= 100, "the first burst came after only " + gap + " ticks");
            shortest = Math.min(shortest, gap);
            longest = Math.max(longest, gap);
        }
        // The range is used toward both of its ends over 500 storms.
        assertTrue(shortest <= 130, "shortest first gap " + shortest);
        assertTrue(longest >= 270, "longest first gap " + longest);
    }

    @Test
    void theHailScheduleRunsBurstThenGapAndNeverBoth() {
        // The hailstorm's ranges: bursts of 45 to 120 seconds, gaps of 30 to 90 seconds.
        ConvectiveParams params = hailParams(1.0F, new WeatherType.Range(45.0F, 120.0F),
            new WeatherType.Range(30.0F, 90.0F));
        ConvectiveState state = new ConvectiveState();
        RandomSource random = RandomSource.create(11L);
        boolean running = ConvectiveMath.advanceHail(state, params, random);
        while (!running) {
            assertNeverBurstAndGap(state, false);
            running = ConvectiveMath.advanceHail(state, params, random);
        }
        for (int cycle = 0; cycle < 20; cycle++) {
            int burst = 0;
            while (running) {
                burst++;
                assertNeverBurstAndGap(state, true);
                running = ConvectiveMath.advanceHail(state, params, random);
            }
            assertTrue(burst >= 900 && burst <= 2400, "a burst of 45 to 120 seconds, got " + burst + " ticks");
            int gap = 0;
            while (!running) {
                gap++;
                assertNeverBurstAndGap(state, false);
                running = ConvectiveMath.advanceHail(state, params, random);
            }
            assertTrue(gap >= 600 && gap <= 1800, "a gap of 30 to 90 seconds, got " + gap + " ticks");
        }
    }

    @Test
    void fixedBurstAndGapLengthsAreKeptToTheTick() {
        // Bursts of 5 seconds and gaps of 2 seconds: 100 ticks of hail, 40 without, again and again.
        ConvectiveParams params = hailParams(0.5F, WeatherType.Range.of(5.0F), WeatherType.Range.of(2.0F));
        ConvectiveState state = new ConvectiveState();
        RandomSource random = RandomSource.create(4L);
        boolean running = ConvectiveMath.advanceHail(state, params, random);
        while (!running) {
            running = ConvectiveMath.advanceHail(state, params, random);
        }
        for (int cycle = 0; cycle < 5; cycle++) {
            assertTrue(running);
            for (int tick = 1; tick < 100; tick++) {
                assertTrue(ConvectiveMath.advanceHail(state, params, random), "burst ended after " + tick + " ticks");
            }
            for (int tick = 0; tick < 40; tick++) {
                assertFalse(ConvectiveMath.advanceHail(state, params, random), "gap ended after " + tick + " ticks");
            }
            running = ConvectiveMath.advanceHail(state, params, random);
        }
    }

    @Test
    void aSavedBurstGoesOnWhereItStopped() {
        ConvectiveParams params = hailParams(1.0F, WeatherType.Range.of(5.0F), WeatherType.Range.of(2.0F));
        ConvectiveState state = new ConvectiveState();
        state.hailBurstTicks = 3;
        state.hailGapTicks = 0;
        RandomSource random = RandomSource.create(6L);
        ConvectiveState loaded = ConvectiveState.load(state.save());
        assertTrue(ConvectiveMath.advanceHail(loaded, params, random));
        assertTrue(ConvectiveMath.advanceHail(loaded, params, random));
        assertFalse(ConvectiveMath.advanceHail(loaded, params, random));
        assertEquals(0, loaded.hailBurstTicks);
        assertEquals(40, loaded.hailGapTicks);
    }

    @Test
    void aTypeWithoutHailNeverBursts() {
        ConvectiveState state = new ConvectiveState();
        RandomSource random = RandomSource.create(12L);
        for (int i = 0; i < 2000; i++) {
            assertFalse(ConvectiveMath.advanceHail(state, ConvectiveParams.NONE, random));
        }
        assertEquals(0, state.hailBurstTicks);
        assertEquals(ConvectiveState.NOT_SCHEDULED, state.hailGapTicks);
    }

    @Test
    void aTornadoRollIsDueWhenEveryClauseHolds() {
        ConvectiveState state = new ConvectiveState();
        // Every number at its limit.
        assertTrue(ConvectiveMath.tornadoRollDue(state, 1200, 0.6F, 3600, 0, 256.0));
        assertTrue(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, 0.0));
    }

    @Test
    void aTornadoRollWaitsForAgeIntensityAndLifeLeft() {
        ConvectiveState state = new ConvectiveState();
        assertFalse(ConvectiveMath.tornadoRollDue(state, 1199, 0.6F, 3600, 0, 256.0));
        assertFalse(ConvectiveMath.tornadoRollDue(state, 1200, 0.59F, 3600, 0, 256.0));
        assertFalse(ConvectiveMath.tornadoRollDue(state, 1200, 0.6F, 3599, 0, 256.0));
        // A roll that was not due is not used up.
        assertFalse(state.tornadoRollFailed);
        assertEquals(0, state.tornadoesSpawned);
        assertTrue(ConvectiveMath.tornadoRollDue(state, 1200, 0.6F, 3600, 0, 256.0));
    }

    @Test
    void aTornadoRollWaitsWhileAChildLives() {
        ConvectiveState state = new ConvectiveState();
        assertFalse(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 1, 10.0));
        assertFalse(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 2, 10.0));
        assertTrue(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, 10.0));
    }

    @Test
    void aTornadoRollNeedsAPlayerWithinSight() {
        ConvectiveState state = new ConvectiveState();
        assertTrue(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, 256.0));
        assertFalse(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, 257.0));
        assertFalse(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, 256.001));
        assertFalse(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, Double.MAX_VALUE));
    }

    @Test
    void aFailedRollEndsTheMatterForTheStorm() {
        ConvectiveState state = new ConvectiveState();
        state.tornadoRollFailed = true;
        assertFalse(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, 10.0));
    }

    @Test
    void aStormRollsForAtMostTwoTornadoes() {
        ConvectiveState state = new ConvectiveState();
        state.tornadoesSpawned = 1;
        assertTrue(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, 10.0));
        state.tornadoesSpawned = 2;
        assertFalse(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, 10.0));
        state.tornadoesSpawned = 3;
        assertFalse(ConvectiveMath.tornadoRollDue(state, 5000, 1.0F, 9000, 0, 10.0));
    }

    @Test
    void smallVorticesAppearInTheSectorAheadOfTheStorm() {
        RandomSource random = RandomSource.create(5L);
        double[] out = new double[2];
        float radius = 200.0F;
        float[] headings = {0.0F, 90.0F, 215.0F, 359.0F};
        for (float heading : headings) {
            double nearest = Double.MAX_VALUE;
            double farthest = 0.0;
            double widest = 1.0;
            for (int i = 0; i < 2000; i++) {
                ConvectiveMath.leadingEdgeSpot(100.0, -50.0, heading, radius, random, out);
                double dx = out[0] - 100.0;
                double dz = out[1] + 50.0;
                double distance = Math.sqrt(dx * dx + dz * dz);
                // 0.5 to 0.8 radii from the centre: 100 to 160 blocks.
                assertTrue(distance >= 100.0 - 1.0E-3 && distance <= 160.0 + 1.0E-3,
                    "a spot 100 to 160 blocks ahead, got " + distance);
                // Within 60 degrees of the heading: the cosine of the angle between them is at least one half.
                double along = (dx * Headings.directionX(heading) + dz * Headings.directionZ(heading)) / distance;
                assertTrue(along >= 0.5 - 1.0E-4, "a spot within 60 degrees of heading " + heading);
                nearest = Math.min(nearest, distance);
                farthest = Math.max(farthest, distance);
                widest = Math.min(widest, along);
            }
            // The whole sector is used, not only its middle.
            assertTrue(nearest < 103.0, "nearest spot " + nearest);
            assertTrue(farthest > 157.0, "farthest spot " + farthest);
            assertTrue(widest < 0.55, "widest spot has cosine " + widest);
        }
    }

    @Test
    void aStormWithoutARadiusPutsItsVorticesAtItsCentre() {
        double[] out = new double[2];
        ConvectiveMath.leadingEdgeSpot(7.0, 9.0, 45.0F, 0.0F, RandomSource.create(1L), out);
        assertEquals(7.0, out[0], 1.0E-9);
        assertEquals(9.0, out[1], 1.0E-9);
    }

    @Test
    void hailHurtsAChickenOnceAndThenSparesIt() {
        // A chicken has 4 health and hail does 2: the first hit leaves one heart, a second would kill.
        assertTrue(HailSystem.mayHit(false, 4.0F, 2.0F, false));
        assertFalse(HailSystem.mayHit(false, 2.0F, 2.0F, false));
        assertFalse(HailSystem.mayHit(false, 3.9F, 2.0F, false));
        assertFalse(HailSystem.mayHit(false, 1.0F, 2.0F, false));
        assertEquals(2.0F, HailSystem.HAIL_MOB_HEALTH_FLOOR);
    }

    @Test
    void hailNeverSparesAPlayer() {
        assertTrue(HailSystem.mayHit(true, 20.0F, 2.0F, false));
        assertTrue(HailSystem.mayHit(true, 2.0F, 2.0F, false));
        assertTrue(HailSystem.mayHit(true, 0.5F, 2.0F, false));
    }

    @Test
    void hailSparesNothingWhenItMayKillMobs() {
        assertTrue(HailSystem.mayHit(false, 4.0F, 2.0F, true));
        assertTrue(HailSystem.mayHit(false, 2.0F, 2.0F, true));
        assertTrue(HailSystem.mayHit(false, 0.5F, 2.0F, true));
        assertTrue(HailSystem.mayHit(true, 0.5F, 2.0F, true));
    }
}
