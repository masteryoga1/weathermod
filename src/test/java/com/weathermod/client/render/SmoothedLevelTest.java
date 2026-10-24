package com.weathermod.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Steps a smoothed level toward targets and checks the per-tick cap in both directions, the exact landing on the
 * target, the previous-tick memory, snapping, resetting and partial-tick interpolation. Needs no Minecraft
 * bootstrap: only arithmetic is used.
 */
class SmoothedLevelTest {
    private static final float EPSILON = 1.0E-6F;
    private static final float STEP = 0.1F;

    @Test
    void startsAtZeroWithoutMovement() {
        SmoothedLevel level = new SmoothedLevel(STEP);

        assertEquals(0.0F, level.current(), 0.0F);
        assertEquals(0.0F, level.previous(), 0.0F);
        assertEquals(0.0F, level.get(0.5F), 0.0F);
        assertEquals(STEP, level.maxStep(), 0.0F);
    }

    @Test
    void approachMovesUpByAtMostOneStep() {
        SmoothedLevel level = new SmoothedLevel(STEP);

        level.approach(1.0F);

        assertEquals(STEP, level.current(), EPSILON);
        assertEquals(0.0F, level.previous(), 0.0F);
    }

    @Test
    void approachMovesDownByAtMostOneStep() {
        SmoothedLevel level = new SmoothedLevel(STEP);
        level.snap(1.0F);

        level.approach(0.0F);

        assertEquals(0.9F, level.current(), EPSILON);
        assertEquals(1.0F, level.previous(), 0.0F);
    }

    @Test
    void approachLandsExactlyOnTargetWithinOneStep() {
        SmoothedLevel level = new SmoothedLevel(STEP);
        level.snap(0.95F);

        level.approach(1.0F);
        assertEquals(1.0F, level.current(), 0.0F);
        assertEquals(0.95F, level.previous(), 0.0F);

        level.approach(1.0F);
        assertEquals(1.0F, level.current(), 0.0F);
        assertEquals(1.0F, level.previous(), 0.0F);
    }

    @Test
    void approachMovesTheWholeStepWhenTheTargetIsExactlyOneStepAway() {
        SmoothedLevel level = new SmoothedLevel(0.25F);

        level.approach(0.25F);

        assertEquals(0.25F, level.current(), 0.0F);
    }

    @Test
    void longRampNeverOvershootsAndEndsOnTheTarget() {
        SmoothedLevel level = new SmoothedLevel(0.01F);
        float target = 0.37F;

        for (int tick = 0; tick < 100; tick++) {
            level.approach(target);
            assertTrue(level.current() <= target + EPSILON, "overshoot at tick " + tick + ": " + level.current());
            assertTrue(level.current() >= level.previous() - EPSILON, "moved backwards at tick " + tick);
        }

        assertEquals(target, level.current(), 0.0F);
        assertEquals(target, level.previous(), 0.0F);
    }

    @Test
    void longRampDownNeverUndershoots() {
        SmoothedLevel level = new SmoothedLevel(0.01F);
        level.snap(1.0F);

        for (int tick = 0; tick < 150; tick++) {
            level.approach(0.0F);
            assertTrue(level.current() >= -EPSILON, "undershoot at tick " + tick + ": " + level.current());
        }

        assertEquals(0.0F, level.current(), 0.0F);
    }

    @Test
    void previousHoldsTheValueBeforeTheLastApproach() {
        SmoothedLevel level = new SmoothedLevel(STEP);

        level.approach(1.0F);
        level.approach(1.0F);

        assertEquals(STEP, level.previous(), EPSILON);
        assertEquals(2.0F * STEP, level.current(), EPSILON);
    }

    @Test
    void changingTargetMidRampTurnsAroundWithinOneStep() {
        SmoothedLevel level = new SmoothedLevel(STEP);
        level.approach(1.0F);
        level.approach(1.0F);
        level.approach(1.0F);

        level.approach(0.0F);

        assertEquals(3.0F * STEP, level.previous(), EPSILON);
        assertEquals(2.0F * STEP, level.current(), EPSILON);
    }

    @Test
    void snapSetsBothValues() {
        SmoothedLevel level = new SmoothedLevel(STEP);
        level.approach(1.0F);

        level.snap(0.6F);

        assertEquals(0.6F, level.current(), 0.0F);
        assertEquals(0.6F, level.previous(), 0.0F);
        assertEquals(0.6F, level.get(0.3F), EPSILON);
    }

    @Test
    void resetReturnsToZero() {
        SmoothedLevel level = new SmoothedLevel(STEP);
        level.snap(0.8F);
        level.approach(1.0F);

        level.reset();

        assertEquals(0.0F, level.current(), 0.0F);
        assertEquals(0.0F, level.previous(), 0.0F);
        assertEquals(0.0F, level.get(0.75F), 0.0F);
    }

    @Test
    void getInterpolatesBetweenPreviousAndCurrent() {
        SmoothedLevel level = new SmoothedLevel(STEP);
        level.snap(0.2F);

        level.approach(1.0F);

        assertEquals(0.2F, level.get(0.0F), EPSILON);
        assertEquals(0.3F, level.get(1.0F), EPSILON);
        assertEquals(0.25F, level.get(0.5F), EPSILON);
        assertEquals(0.225F, level.get(0.25F), EPSILON);
    }

    @Test
    void getInterpolatesDownwardMovementToo() {
        SmoothedLevel level = new SmoothedLevel(STEP);
        level.snap(0.5F);

        level.approach(0.0F);

        assertEquals(0.5F, level.get(0.0F), EPSILON);
        assertEquals(0.4F, level.get(1.0F), EPSILON);
        assertEquals(0.45F, level.get(0.5F), EPSILON);
    }

    @Test
    void rejectsNonPositiveOrNonFiniteStep() {
        assertThrows(IllegalArgumentException.class, () -> new SmoothedLevel(0.0F));
        assertThrows(IllegalArgumentException.class, () -> new SmoothedLevel(-0.1F));
        assertThrows(IllegalArgumentException.class, () -> new SmoothedLevel(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new SmoothedLevel(Float.POSITIVE_INFINITY));
    }

    @Test
    void rejectsNanTargetAndValue() {
        SmoothedLevel level = new SmoothedLevel(STEP);
        level.snap(0.4F);

        assertThrows(IllegalArgumentException.class, () -> level.approach(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> level.snap(Float.NaN));

        assertEquals(0.4F, level.current(), 0.0F);
        assertEquals(0.4F, level.previous(), 0.0F);
    }
}
