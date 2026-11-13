package com.weathermod.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Checks the rules of the tornado siren: the range is measured from the storm's edge and includes its bound, the
 * hold counter runs down and is armed again by a new trigger, and redstone power sounds the siren whatever the
 * counter says. Needs no Minecraft bootstrap.
 */
class SirenMathTest {
    /** The default of the {@code sirenRange} server setting. */
    private static final int RANGE_BLOCKS = 256;
    /** A storm radius that makes the reach a round 300 blocks. */
    private static final float STORM_RADIUS_BLOCKS = 44.0F;

    @Test
    void constantsAreTheNumbersOfTheDesign() {
        assertEquals(20, SirenMath.CHECK_INTERVAL_TICKS);
        assertEquals(200, SirenMath.HOLD_TICKS);
        assertEquals(128.0F, SirenMath.AUDIBLE_BLOCKS);
    }

    @Test
    void rangeIsMeasuredFromTheStormsEdgeAndIncludesTheBound() {
        // 256 blocks of range and 44 of storm reach 300 blocks from the storm's centre.
        assertTrue(SirenMath.inRange(0.0, 0.0, 300.0, 0.0, STORM_RADIUS_BLOCKS, RANGE_BLOCKS));
        assertFalse(SirenMath.inRange(0.0, 0.0, 300.5, 0.0, STORM_RADIUS_BLOCKS, RANGE_BLOCKS));
        // 180 and 240 are the legs of a triangle with a hypotenuse of exactly 300.
        assertTrue(SirenMath.inRange(0.0, 0.0, 180.0, 240.0, STORM_RADIUS_BLOCKS, RANGE_BLOCKS));
        assertFalse(SirenMath.inRange(0.0, 0.0, 180.0, 240.0, STORM_RADIUS_BLOCKS - 1.0F, RANGE_BLOCKS));
        assertFalse(SirenMath.inRange(0.0, 0.0, 180.0, 240.0, STORM_RADIUS_BLOCKS, RANGE_BLOCKS - 1));
        // A storm without a radius is in range up to the range itself.
        assertTrue(SirenMath.inRange(0.0, 0.0, 0.0, -256.0, 0.0F, RANGE_BLOCKS));
        assertFalse(SirenMath.inRange(0.0, 0.0, 0.0, -257.0, 0.0F, RANGE_BLOCKS));
    }

    @Test
    void rangeIsRelativeToTheSiren() {
        assertTrue(SirenMath.inRange(-100.5, 40.5, 79.5, -199.5, STORM_RADIUS_BLOCKS, RANGE_BLOCKS));
        assertFalse(SirenMath.inRange(-100.5, 40.5, 79.5, -200.5, STORM_RADIUS_BLOCKS, RANGE_BLOCKS));
        // Siren and storm may change places: the distance is the same.
        assertTrue(SirenMath.inRange(79.5, -199.5, -100.5, 40.5, STORM_RADIUS_BLOCKS, RANGE_BLOCKS));
        assertFalse(SirenMath.inRange(79.5, -200.5, -100.5, 40.5, STORM_RADIUS_BLOCKS, RANGE_BLOCKS));
    }

    @Test
    void aStormOverTheSirenIsInRangeUnlessTheReachIsNegativeOrUndefined() {
        assertTrue(SirenMath.inRange(5.0, 5.0, 5.0, 5.0, 0.0F, 0));
        assertFalse(SirenMath.inRange(5.0, 5.0, 5.0, 5.0, -1.0F, 0));
        assertFalse(SirenMath.inRange(5.0, 5.0, 5.0, 5.0, Float.NaN, RANGE_BLOCKS));
    }

    @Test
    void aTriggerArmsTheFullHold() {
        assertEquals(SirenMath.HOLD_TICKS, SirenMath.nextHold(0, true, SirenMath.CHECK_INTERVAL_TICKS));
        assertEquals(SirenMath.HOLD_TICKS, SirenMath.nextHold(57, true, SirenMath.CHECK_INTERVAL_TICKS));
        assertEquals(SirenMath.HOLD_TICKS, SirenMath.nextHold(SirenMath.HOLD_TICKS, true, 0));
    }

    @Test
    void theHoldCountsDownByTheElapsedTicksAndStopsAtZero() {
        assertEquals(180, SirenMath.nextHold(200, false, 20));
        assertEquals(1, SirenMath.nextHold(21, false, 20));
        assertEquals(0, SirenMath.nextHold(20, false, 20));
        assertEquals(0, SirenMath.nextHold(15, false, 20));
        assertEquals(0, SirenMath.nextHold(0, false, 20));
        assertEquals(0, SirenMath.nextHold(-3, false, 0), "a counter below zero is not kept");
        assertEquals(0, SirenMath.nextHold(5, false, Integer.MAX_VALUE));
        assertEquals(0, SirenMath.nextHold(Integer.MIN_VALUE, false, 20));
        assertEquals(50, SirenMath.nextHold(50, false, -5), "elapsed ticks below zero count as zero");
    }

    @Test
    void aSirenSoundsOnForTheHoldTimeAfterTheLastTrigger() {
        int hold = SirenMath.nextHold(0, true, SirenMath.CHECK_INTERVAL_TICKS);
        int checks = 0;
        while (SirenMath.active(false, hold)) {
            hold = SirenMath.nextHold(hold, false, SirenMath.CHECK_INTERVAL_TICKS);
            checks++;
        }
        // 200 ticks of hold, checked every 20 ticks: silent at the tenth check without a tornado.
        assertEquals(SirenMath.HOLD_TICKS / SirenMath.CHECK_INTERVAL_TICKS, checks);
        assertEquals(0, hold);
    }

    @Test
    void aNewTriggerDuringTheHoldArmsItAgain() {
        int hold = SirenMath.nextHold(0, true, SirenMath.CHECK_INTERVAL_TICKS);
        for (int check = 0; check < 3; check++) {
            hold = SirenMath.nextHold(hold, false, SirenMath.CHECK_INTERVAL_TICKS);
        }
        assertEquals(140, hold);
        hold = SirenMath.nextHold(hold, true, SirenMath.CHECK_INTERVAL_TICKS);
        assertEquals(SirenMath.HOLD_TICKS, hold);
    }

    @Test
    void poweredOverridesTheHoldCounter() {
        assertTrue(SirenMath.active(true, 0));
        assertTrue(SirenMath.active(true, SirenMath.HOLD_TICKS));
        assertFalse(SirenMath.active(false, 0));
        assertFalse(SirenMath.active(false, -1));
        assertTrue(SirenMath.active(false, 1));
        assertTrue(SirenMath.active(false, SirenMath.HOLD_TICKS));
    }
}
