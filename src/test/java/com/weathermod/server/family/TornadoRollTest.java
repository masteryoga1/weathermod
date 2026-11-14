package com.weathermod.server.family;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Pins the dice of tornado formation: the weights of the EF tiers over many seeded rolls, the highest tier a parent
 * allows, and the roll that decides whether a storm produces a tornado at all. Needs no Minecraft bootstrap.
 */
class TornadoRollTest {
    private static final int ROLLS = 100_000;
    /** A share may be this far from its weight: one percentage point. */
    private static final double SHARE_TOLERANCE = 0.01D;

    private static int[] counts(long seed, int maxTier, int rolls) {
        RandomSource random = RandomSource.create(seed);
        int[] counts = new int[TornadoRoll.TIER_WEIGHTS.length];
        for (int i = 0; i < rolls; i++) {
            int tier = TornadoRoll.tier(random, maxTier);
            assertTrue(tier >= 0 && tier < counts.length, "a tier between 0 and 5, got " + tier);
            counts[tier]++;
        }
        return counts;
    }

    private static double share(long seed, float typeChance, double frequency, int rolls) {
        RandomSource random = RandomSource.create(seed);
        int hits = 0;
        for (int i = 0; i < rolls; i++) {
            if (TornadoRoll.happens(typeChance, frequency, random)) {
                hits++;
            }
        }
        return hits / (double) rolls;
    }

    @Test
    void theWeightsAreTheAgreedOnesAndAddUpToAHundred() {
        assertArrayEquals(new int[] {40, 28, 16, 9, 5, 2}, TornadoRoll.TIER_WEIGHTS);
        int total = 0;
        for (int weight : TornadoRoll.TIER_WEIGHTS) {
            total += weight;
        }
        assertEquals(100, total);
    }

    @Test
    void aHundredThousandRollsFollowTheWeights() {
        for (long seed : new long[] {1L, 20240517L, -77L}) {
            int[] counts = counts(seed, 5, ROLLS);
            for (int tier = 0; tier < counts.length; tier++) {
                double share = counts[tier] / (double) ROLLS;
                double expected = TornadoRoll.TIER_WEIGHTS[tier] / 100.0D;
                assertEquals(expected, share, SHARE_TOLERANCE, "share of EF" + tier + " with seed " + seed);
            }
        }
    }

    @Test
    void theHighestTierAllowedIsRespected() {
        for (int maxTier = 0; maxTier <= 5; maxTier++) {
            int[] counts = counts(99L + maxTier, maxTier, 10_000);
            for (int tier = 0; tier < counts.length; tier++) {
                if (tier <= maxTier) {
                    assertTrue(counts[tier] > 0, "EF" + tier + " is rolled when up to EF" + maxTier + " is allowed");
                } else {
                    assertEquals(0, counts[tier], "no EF" + tier + " when only up to EF" + maxTier + " is allowed");
                }
            }
        }
    }

    @Test
    void theAllowedTiersKeepTheirRelativeWeights() {
        int[] counts = counts(4711L, 2, ROLLS);

        assertEquals(40.0D / 84.0D, counts[0] / (double) ROLLS, SHARE_TOLERANCE);
        assertEquals(28.0D / 84.0D, counts[1] / (double) ROLLS, SHARE_TOLERANCE);
        assertEquals(16.0D / 84.0D, counts[2] / (double) ROLLS, SHARE_TOLERANCE);
        assertEquals(ROLLS, counts[0] + counts[1] + counts[2]);
    }

    @Test
    void aHighestTierOutsideTheScaleIsClamped() {
        int[] negative = counts(5L, -3, 1_000);
        assertEquals(1_000, negative[0], "below EF0 there is only EF0");

        assertArrayEquals(counts(5L, 5, 10_000), counts(5L, 9, 10_000), "above EF5 there is only EF5");
        assertArrayEquals(counts(5L, 5, 10_000), counts(5L, Integer.MAX_VALUE, 10_000));
    }

    @Test
    void rollingATierIsDeterministicAndReadsTheRandomSourceOnce() {
        assertArrayEquals(counts(31L, 5, 1_000), counts(31L, 5, 1_000));

        RandomSource random = RandomSource.create(8L);
        RandomSource twin = RandomSource.create(8L);
        for (int maxTier = 0; maxTier <= 5; maxTier++) {
            int total = 0;
            for (int tier = 0; tier <= maxTier; tier++) {
                total += TornadoRoll.TIER_WEIGHTS[tier];
            }
            TornadoRoll.tier(random, maxTier);
            twin.nextInt(total);
            assertEquals(twin.nextLong(), random.nextLong(), "one bounded draw for up to EF" + maxTier);
        }
    }

    @Test
    void aChanceOrFrequencyOfZeroNeverHappensAndReadsNoRandomNumber() {
        RandomSource random = RandomSource.create(3L);
        RandomSource untouched = RandomSource.create(3L);
        for (int i = 0; i < 1_000; i++) {
            assertFalse(TornadoRoll.happens(0.0F, 1.0D, random));
            assertFalse(TornadoRoll.happens(0.5F, 0.0D, random));
            assertFalse(TornadoRoll.happens(0.0F, 0.0D, random));
            assertFalse(TornadoRoll.happens(-0.5F, 2.0D, random));
            assertFalse(TornadoRoll.happens(0.5F, -2.0D, random));
            assertFalse(TornadoRoll.happens(Float.NaN, 1.0D, random));
            assertFalse(TornadoRoll.happens(0.5F, Double.NaN, random));
        }
        assertEquals(untouched.nextLong(), random.nextLong(), "the random source was not read");
    }

    @Test
    void aChanceOfOneAlwaysHappens() {
        assertEquals(1.0D, share(11L, 1.0F, 1.0D, 10_000));
        assertEquals(1.0D, share(12L, 0.5F, 2.0D, 10_000), "half the chance at twice the frequency is certain");
        assertEquals(1.0D, share(13L, 0.9F, 10.0D, 10_000), "the product is capped at one");
    }

    @Test
    void theChanceIsTheTypeChanceTimesTheFrequency() {
        assertEquals(0.5D, share(21L, 0.5F, 1.0D, ROLLS), SHARE_TOLERANCE);
        assertEquals(0.5D, share(22L, 0.25F, 2.0D, ROLLS), SHARE_TOLERANCE);
        assertEquals(0.1D, share(23L, 0.02F, 5.0D, ROLLS), SHARE_TOLERANCE);
        assertEquals(0.03D, share(24L, 0.3F, 0.1D, ROLLS), SHARE_TOLERANCE);
    }

    @Test
    void aRollThatCanHappenReadsOneDouble() {
        RandomSource random = RandomSource.create(40L);
        RandomSource twin = RandomSource.create(40L);
        for (int i = 0; i < 100; i++) {
            assertEquals(twin.nextDouble() < 0.25D, TornadoRoll.happens(0.25F, 1.0D, random), "roll " + i);
        }
        assertEquals(twin.nextLong(), random.nextLong());
    }
}
