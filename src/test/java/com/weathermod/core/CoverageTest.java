package com.weathermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Checks the shared storm coverage curve: full inside the inner fraction, smooth to zero at the radius. */
class CoverageTest {
    private static final float EPSILON = 1.0E-6F;
    private static final float RADIUS = 100.0F;

    @Test
    void fullFractionIsSixtyPercentOfTheRadius() {
        assertEquals(0.6F, Coverage.FULL_FRACTION, EPSILON);
    }

    @Test
    void fullInsideTheInnerFraction() {
        assertEquals(1.0F, Coverage.falloff(0.0D, RADIUS), EPSILON);
        assertEquals(1.0F, Coverage.falloff(30.0D, RADIUS), EPSILON);
        assertEquals(1.0F, Coverage.falloff(59.9D, RADIUS), EPSILON);
        assertEquals(1.0F, Coverage.falloff(60.0D, RADIUS), EPSILON, "the inner edge is still full");
        assertEquals(1.0F, Coverage.falloff(-10.0D, RADIUS), EPSILON, "a negative distance counts as the centre");
    }

    @Test
    void zeroAtAndBeyondTheRadius() {
        assertEquals(0.0F, Coverage.falloff(100.0D, RADIUS), EPSILON);
        assertEquals(0.0F, Coverage.falloff(100.001D, RADIUS), EPSILON);
        assertEquals(0.0F, Coverage.falloff(1.0E6D, RADIUS), EPSILON);
        assertEquals(0.0F, Coverage.falloff(Double.POSITIVE_INFINITY, RADIUS), EPSILON);
    }

    @Test
    void halfWayThroughTheShoulderIsHalf() {
        assertEquals(0.5F, Coverage.falloff(80.0D, RADIUS), 1.0E-5F);
    }

    @Test
    void shoulderIsSymmetricAndStrictlyDecreasing() {
        float previous = Coverage.falloff(60.0D, RADIUS);
        for (double distance = 61.0D; distance <= 100.0D; distance += 1.0D) {
            float current = Coverage.falloff(distance, RADIUS);
            assertTrue(current < previous, "falloff must decrease at distance " + distance);
            assertTrue(current >= 0.0F && current <= 1.0F, "falloff stays in 0..1 at distance " + distance);
            previous = current;
        }
        for (double offset = 0.0D; offset <= 20.0D; offset += 2.5D) {
            float inner = Coverage.falloff(60.0D + offset, RADIUS);
            float outer = Coverage.falloff(100.0D - offset, RADIUS);
            assertEquals(1.0F, inner + outer, 1.0E-5F, "the shoulder is symmetric about its middle at offset " + offset);
        }
    }

    @Test
    void shoulderScalesWithTheRadius() {
        assertEquals(Coverage.falloff(80.0D, 100.0F), Coverage.falloff(160.0D, 200.0F), EPSILON);
        assertEquals(Coverage.falloff(70.0D, 100.0F), Coverage.falloff(7.0D, 10.0F), EPSILON);
        assertEquals(1.0F, Coverage.falloff(119.0D, 200.0F), EPSILON);
        assertEquals(0.0F, Coverage.falloff(200.0D, 200.0F), EPSILON);
    }

    @Test
    void nonPositiveRadiusCoversNothing() {
        assertEquals(0.0F, Coverage.falloff(0.0D, 0.0F), EPSILON);
        assertEquals(0.0F, Coverage.falloff(0.0D, -5.0F), EPSILON);
        assertEquals(0.0F, Coverage.falloff(0.0D, Float.NaN), EPSILON);
        assertEquals(0.0F, Coverage.coverage(1.0F, 0.0D, 0.0F), EPSILON);
    }

    @Test
    void notANumberInputsCoverNothing() {
        assertEquals(0.0F, Coverage.falloff(Double.NaN, RADIUS), EPSILON);
        assertEquals(0.0F, Coverage.coverage(Float.NaN, 0.0D, RADIUS), EPSILON);
        assertEquals(0.0F, Coverage.coverage(1.0F, Double.NaN, RADIUS), EPSILON);
    }

    @Test
    void coverageScalesByIntensity() {
        assertEquals(0.5F, Coverage.coverage(0.5F, 0.0D, RADIUS), EPSILON);
        assertEquals(1.0F, Coverage.coverage(1.0F, 50.0D, RADIUS), EPSILON);
        assertEquals(0.4F, Coverage.coverage(0.8F, 80.0D, RADIUS), 1.0E-5F);
        assertEquals(0.0F, Coverage.coverage(1.0F, 100.0D, RADIUS), EPSILON);
        assertEquals(0.0F, Coverage.coverage(0.0F, 0.0D, RADIUS), EPSILON);
    }

    @Test
    void coverageIsClampedToTheUnitInterval() {
        assertEquals(1.0F, Coverage.coverage(1.5F, 0.0D, RADIUS), EPSILON);
        assertEquals(0.0F, Coverage.coverage(-0.5F, 0.0D, RADIUS), EPSILON);
        assertEquals(0.75F, Coverage.coverage(1.5F, 80.0D, RADIUS), 1.0E-5F, "over-intensity still fades at the edge");
    }
}
