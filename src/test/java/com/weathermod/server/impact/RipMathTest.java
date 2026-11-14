package com.weathermod.server.impact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.server.family.FunnelSpineMath;
import com.weathermod.server.family.VortexMath;
import org.junit.jupiter.api.Test;

/**
 * Pins the arithmetic of block ripping: the budget a tier takes, which blocks a tier takes at all, the worked
 * chances of the design, the dig limit and the flying block cap. Pure arithmetic, so no Minecraft bootstrap is
 * needed.
 */
class RipMathTest {
    private static final float EPSILON = 1.0E-6F;
    /** The rip shares of tornado_ef0 to tornado_ef5. */
    private static final float[] RIP_SHARES = {0.125F, 0.25F, 0.375F, 0.625F, 0.875F, 1.0F};
    /** The flying block shares of tornado_ef0 to tornado_ef5. */
    private static final float[] FLYING_BLOCK_SHARES = {0.125F, 0.25F, 0.5F, 0.75F, 1.0F, 1.0F};
    /** The core radii of tornado_ef0 to tornado_ef5. */
    private static final float[] CORE_RADII = {3.0F, 5.0F, 8.0F, 12.0F, 18.0F, 26.0F};

    private static final float LEAVES = 0.2F;
    private static final float GLASS = 0.3F;
    private static final float DIRT = 0.5F;
    private static final float STONE = 1.5F;
    private static final float PLANKS = 2.0F;
    private static final float OBSIDIAN = 50.0F;

    @Test
    void theConstantsAreTheAgreedOnes() {
        assertArrayEquals(new float[] {0.0F, 0.3F, 0.8F, 2.0F, 3.5F, 50.0F}, RipMath.HARDNESS_CEILING);
        assertArrayEquals(new float[] {0.0F, 0.0F, 0.1F, 0.15F, 0.4F, 1.0F}, RipMath.GROUND_CHANCE_FACTOR);
        assertArrayEquals(new int[] {0, 0, 1, 2, 4, 6}, RipMath.MAX_DIG_BLOCKS);
        assertEquals(4, RipMath.MAX_ATTEMPT_FACTOR);
        assertEquals(1.25F, RipMath.RIP_RADIUS_FACTOR);
        assertEquals(0.75F, RipMath.RESISTANCE_WEIGHT);
        assertEquals(0.25F, RipMath.MIN_RIP_CHANCE);
        assertEquals(0.5F, RipMath.TAGGED_MIN_RIP_CHANCE);
        assertEquals(4, RipMath.LAUNCH_INTERVAL_TICKS);
        assertEquals(3, RipMath.SCOUR_MIN_TIER);
        assertEquals(0.6F, RipMath.SCOUR_CHANCE);
        assertEquals(4, RipMath.MAX_SCOURS_PER_STORM_TICK);
        assertEquals(2, RipMath.MAX_DROPS_PER_STORM_TICK);
    }

    @Test
    void ripsPerTickFollowTheTierShares() {
        int[] atDefault = {2, 4, 6, 10, 14, 16};
        int[] atMost = {32, 64, 96, 160, 224, 256};
        for (int tier = 0; tier < RIP_SHARES.length; tier++) {
            assertEquals(1, RipMath.ripsPerTick(1, RIP_SHARES[tier]), "budget 1, tier " + tier);
            assertEquals(atDefault[tier], RipMath.ripsPerTick(16, RIP_SHARES[tier]), "budget 16, tier " + tier);
            assertEquals(atMost[tier], RipMath.ripsPerTick(256, RIP_SHARES[tier]), "budget 256, tier " + tier);
        }
    }

    @Test
    void aVortexWithAShareRipsAtLeastOneBlock() {
        assertEquals(1, RipMath.ripsPerTick(16, 0.01F), "16 * 0.01 rounds to nothing, one block all the same");
        assertEquals(1, RipMath.ripsPerTick(16, 0.125F * 0.3F), "an EF0 at the weakest grounded strength");
        assertEquals(5, RipMath.ripsPerTick(16, 0.625F * 0.5F), "an EF3 at half strength: 16 * 0.3125 = 5");
    }

    @Test
    void noShareOrNoBudgetRipsNothing() {
        assertEquals(0, RipMath.ripsPerTick(16, 0.0F));
        assertEquals(0, RipMath.ripsPerTick(16, -0.5F));
        assertEquals(0, RipMath.ripsPerTick(16, Float.NaN));
        assertEquals(0, RipMath.ripsPerTick(0, 1.0F));
        assertEquals(0, RipMath.ripsPerTick(-4, 1.0F));
    }

    @Test
    void attemptsAreFourTimesTheRips() {
        assertEquals(0, RipMath.attemptsPerTick(0));
        assertEquals(0, RipMath.attemptsPerTick(-3));
        assertEquals(4, RipMath.attemptsPerTick(1));
        assertEquals(64, RipMath.attemptsPerTick(16));
        assertEquals(1024, RipMath.attemptsPerTick(256));
        assertEquals(Integer.MAX_VALUE, RipMath.attemptsPerTick(Integer.MAX_VALUE), "saturates instead of wrapping");
    }

    @Test
    void sampleDepthIsOnePlusTheTier() {
        for (int tier = 0; tier <= 5; tier++) {
            assertEquals(1 + tier, RipMath.sampleDepth(tier), "tier " + tier);
        }
        assertEquals(1, RipMath.sampleDepth(-2), "below the scale counts as EF0");
        assertEquals(6, RipMath.sampleDepth(9), "above the scale counts as EF5");
    }

    @Test
    void theGrabRadiusIsOneAndAQuarterBaseRadii() {
        float[] expected = {3.75F, 6.25F, 10.0F, 15.0F, 22.5F, 32.5F};
        for (int tier = 0; tier < CORE_RADII.length; tier++) {
            assertEquals(expected[tier], RipMath.ripRadius(CORE_RADII[tier]), EPSILON, "tier " + tier);
        }
        assertEquals(0.0F, RipMath.ripRadius(0.0F));
    }

    @Test
    void theDigProbeStaysOutsideTheRipReach() {
        assertTrue(RipMath.RIP_RADIUS_FACTOR + FunnelSpineMath.WOBBLE_CORE_FRACTION
                < VortexMath.DIG_PROBE_RADIUS_FACTOR,
            "the grab radius plus the wobble of the base must stay inside the dig probe distance");
    }

    @Test
    void aBlockThatCannotBeBrokenIsNeverTaken() {
        for (int tier = 0; tier <= 5; tier++) {
            assertFalse(RipMath.tierAllows(tier, true, true, -1.0F), "tagged bedrock, tier " + tier);
            assertFalse(RipMath.tierAllows(tier, false, false, -1.0F), "untagged bedrock, tier " + tier);
        }
        assertFalse(RipMath.tierAllows(5, true, true, Float.NaN));
    }

    @Test
    void aBlockInTheTiersTagIsTaken() {
        assertTrue(RipMath.tierAllows(0, true, true, LEAVES));
        assertTrue(RipMath.tierAllows(3, true, true, PLANKS));
        assertTrue(RipMath.tierAllows(1, true, true, 5.0F), "the tag decides, not the ceiling of 0.3");
        assertTrue(RipMath.tierAllows(5, true, true, OBSIDIAN));
        assertTrue(RipMath.tierAllows(0, true, true, 0.0F));
    }

    @Test
    void aBlockOnlyAHigherTierNamesIsLeft() {
        assertFalse(RipMath.tierAllows(2, false, true, 0.1F), "softer than the EF2 ceiling, but tagged for later");
        assertFalse(RipMath.tierAllows(0, false, true, 0.0F));
        assertFalse(RipMath.tierAllows(4, false, true, OBSIDIAN));
    }

    @Test
    void anUntaggedBlockIsTakenUpToTheTiersCeiling() {
        assertFalse(RipMath.tierAllows(0, false, false, 0.0F), "EF0 takes tagged blocks and nothing else");
        assertFalse(RipMath.tierAllows(0, false, false, 0.2F));
        assertTrue(RipMath.tierAllows(1, false, false, 0.0F));
        assertTrue(RipMath.tierAllows(1, false, false, 0.3F), "at the ceiling");
        assertFalse(RipMath.tierAllows(1, false, false, 0.31F), "just above the ceiling");
        assertTrue(RipMath.tierAllows(3, false, false, 2.0F));
        assertFalse(RipMath.tierAllows(3, false, false, 2.5F));
        assertTrue(RipMath.tierAllows(4, false, false, 3.5F));
        assertFalse(RipMath.tierAllows(4, false, false, 5.0F));
        assertTrue(RipMath.tierAllows(5, false, false, 50.0F));
        assertFalse(RipMath.tierAllows(5, false, false, 55.0F), "reinforced deepslate");
    }

    @Test
    void tierAllowsClampsTheTier() {
        assertFalse(RipMath.tierAllows(-1, false, false, 0.1F), "below the scale counts as EF0");
        assertTrue(RipMath.tierAllows(7, false, false, 50.0F), "above the scale counts as EF5");
    }

    @Test
    void theWorkedChancesOfTheDesign() {
        assertEquals(0.5F, RipMath.ripChance(3, PLANKS, true, false), EPSILON, "planks or logs at EF3");
        assertEquals(0.5F, RipMath.ripChance(2, PLANKS, true, false), EPSILON, "planks at EF2");
        assertEquals(0.5F, RipMath.ripChance(1, GLASS, true, false), EPSILON, "glass at EF1");
        assertEquals(0.5F, RipMath.ripChance(5, OBSIDIAN, true, false), EPSILON, "obsidian at EF5");
        assertEquals(0.25F, RipMath.ripChance(5, 50.0F, false, false), EPSILON, "untagged, hardness 50, at EF5");
        assertEquals(0.121875F, RipMath.ripChance(3, DIRT, true, true), EPSILON,
            "dirt at EF3: max(0.5, 1 - 0.75 * 0.5 / 2.0) * 0.15");
        assertEquals((1.0F - 0.75F * 1.5F / 3.5F) * 0.4F, RipMath.ripChance(4, STONE, true, true), EPSILON,
            "stone at EF4: max(0.5, 1 - 0.75 * 1.5 / 3.5) * 0.4");
        assertEquals(0.2714F, RipMath.ripChance(4, STONE, true, true), 1.0E-4F, "stone at EF4, as a number");
    }

    @Test
    void softBlocksAreTakenAlmostAlways() {
        for (int tier = 0; tier <= 5; tier++) {
            assertEquals(1.0F, RipMath.ripChance(tier, 0.0F, true, false), "a plant or torch, tier " + tier);
        }
        assertEquals(1.0F, RipMath.ripChance(0, LEAVES, true, false), "leaves at EF0: no ceiling, no resistance");
        assertEquals(0.5F, RipMath.ripChance(1, LEAVES, true, false), EPSILON, "1 - 0.75 * 0.2 / 0.3");
        assertEquals(0.8125F, RipMath.ripChance(2, LEAVES, true, false), EPSILON, "1 - 0.75 * 0.2 / 0.8");
        assertEquals(0.925F, RipMath.ripChance(3, LEAVES, true, false), EPSILON, "1 - 0.75 * 0.2 / 2.0");
        assertEquals(0.997F, RipMath.ripChance(5, LEAVES, true, false), EPSILON, "1 - 0.75 * 0.2 / 50");
    }

    @Test
    void anUntaggedBlockHasTheLowerFloor() {
        assertEquals(0.25F, RipMath.ripChance(3, 2.0F, false, false), EPSILON, "at the ceiling, untagged");
        assertEquals(0.25F, RipMath.ripChance(3, 9.0F, false, false), EPSILON, "hardness counts up to the ceiling");
        assertEquals(0.625F, RipMath.ripChance(3, 1.0F, false, false), EPSILON, "1 - 0.75 * 1.0 / 2.0");
    }

    @Test
    void naturalGroundIsThinnedOutByTier() {
        for (float hardness : new float[] {0.0F, DIRT, STONE}) {
            assertEquals(0.0F, RipMath.ripChance(0, hardness, true, true), "EF0 leaves the ground alone");
            assertEquals(0.0F, RipMath.ripChance(1, hardness, true, true), "EF1 leaves the ground alone");
        }
        assertEquals(0.053125F, RipMath.ripChance(2, DIRT, true, true), EPSILON, "(1 - 0.75 * 0.5 / 0.8) * 0.1");
        assertEquals(RipMath.ripChance(5, DIRT, true, false), RipMath.ripChance(5, DIRT, true, true),
            "an EF5 digs at the full chance");
    }

    @Test
    void ripChanceClampsTheTier() {
        assertEquals(RipMath.ripChance(0, PLANKS, true, false), RipMath.ripChance(-4, PLANKS, true, false));
        assertEquals(RipMath.ripChance(5, PLANKS, true, true), RipMath.ripChance(8, PLANKS, true, true));
    }

    @Test
    void maxDigFollowsTheTableAndClamps() {
        int[] expected = {0, 0, 1, 2, 4, 6};
        for (int tier = 0; tier <= 5; tier++) {
            assertEquals(expected[tier], RipMath.maxDig(tier), "tier " + tier);
        }
        assertEquals(0, RipMath.maxDig(-3));
        assertEquals(6, RipMath.maxDig(6));
        assertEquals(6, RipMath.maxDig(99));
    }

    @Test
    void withoutADigReferenceEverythingMayBeDug() {
        for (int tier = 0; tier <= 5; tier++) {
            assertTrue(RipMath.digAllowed(tier, Double.NaN, -64), "tier " + tier);
            assertTrue(RipMath.digAllowed(tier, Double.NaN, 300), "tier " + tier);
        }
    }

    @Test
    void theGroundIsDugDownToTheLimitAndNoFarther() {
        assertTrue(RipMath.digAllowed(3, 64.0, 64), "at the reference");
        assertTrue(RipMath.digAllowed(3, 64.0, 62), "two below: the EF3 limit");
        assertFalse(RipMath.digAllowed(3, 64.0, 61), "one below the limit");
        assertTrue(RipMath.digAllowed(5, 64.0, 58), "six below: the EF5 limit");
        assertFalse(RipMath.digAllowed(5, 64.0, 57));
        assertTrue(RipMath.digAllowed(3, 64.0, 90), "above the reference there is no limit");
        assertTrue(RipMath.digAllowed(2, 64.5, 64), "a reference between two blocks: 64 >= 63.5");
        assertFalse(RipMath.digAllowed(2, 64.5, 63), "63 < 63.5");
    }

    @Test
    void weakTornadoesDigNothingBelowTheReference() {
        for (int tier = 0; tier <= 1; tier++) {
            assertTrue(RipMath.digAllowed(tier, 64.0, 64), "tier " + tier);
            assertFalse(RipMath.digAllowed(tier, 64.0, 63), "tier " + tier);
        }
    }

    @Test
    void theFlyingBlockCapIsTheRoundedShare() {
        int[] expected = {6, 12, 24, 36, 48, 48};
        for (int tier = 0; tier < FLYING_BLOCK_SHARES.length; tier++) {
            assertEquals(expected[tier], RipMath.flyingBlockCap(48, FLYING_BLOCK_SHARES[tier]), "tier " + tier);
        }
        assertEquals(3, RipMath.flyingBlockCap(48, 0.06F), "a fire whirl: 2.88 rounds up");
        assertEquals(3, RipMath.flyingBlockCap(10, 0.25F), "2.5 rounds up");
        assertEquals(1, RipMath.flyingBlockCap(10, 0.125F), "1.25 rounds down");
        assertEquals(0, RipMath.flyingBlockCap(3, 0.125F), "0.375 rounds to none");
    }

    @Test
    void noShareOrNoCapKeepsNothingInTheAir() {
        assertEquals(0, RipMath.flyingBlockCap(0, 1.0F));
        assertEquals(0, RipMath.flyingBlockCap(48, 0.0F));
        assertEquals(0, RipMath.flyingBlockCap(48, -1.0F));
        assertEquals(0, RipMath.flyingBlockCap(-48, 1.0F));
        assertEquals(0, RipMath.flyingBlockCap(48, Float.NaN));
    }
}
