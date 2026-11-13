package com.weathermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the geometry of the hail core: where its centre lies ahead of the storm, how wide it is and how the hail
 * strength falls off from its full zone to its edge. Needs no Minecraft bootstrap.
 */
class HailCoreTest {
    private static final float RADIUS = 100.0F;
    private static final float HAIL = 0.8F;
    /** Positions are compared to a ten-thousandth of a block; the fractions are floats. */
    private static final double POSITION_EPSILON = 1.0E-4D;
    private static final float STRENGTH_EPSILON = 1.0E-3F;

    /** Strength at a point for a storm at the origin that heads north, so its core centre is (0, -30). */
    private static float northbound(double x, double z) {
        return HailCore.strengthAt(0.0, 0.0, 0.0F, RADIUS, HAIL, x, z);
    }

    @Test
    void theFractionsAreTheAgreedOnes() {
        assertEquals(0.4F, HailCore.CORE_RADIUS_FRACTION);
        assertEquals(0.3F, HailCore.CORE_OFFSET_FRACTION);
        assertEquals(0.7F, HailCore.FULL_FRACTION);
    }

    @Test
    void theCentreLiesAheadOfTheStormAlongItsHeading() {
        // North is -Z, east is +X, south is +Z, west is -X; the offset is 0.3 of the radius of 100.
        assertEquals(10.0, HailCore.centreX(10.0, 0.0F, RADIUS), POSITION_EPSILON);
        assertEquals(20.0 - 30.0, HailCore.centreZ(20.0, 0.0F, RADIUS), POSITION_EPSILON);
        assertEquals(10.0 + 30.0, HailCore.centreX(10.0, 90.0F, RADIUS), POSITION_EPSILON);
        assertEquals(20.0, HailCore.centreZ(20.0, 90.0F, RADIUS), POSITION_EPSILON);
        assertEquals(10.0, HailCore.centreX(10.0, 180.0F, RADIUS), POSITION_EPSILON);
        assertEquals(20.0 + 30.0, HailCore.centreZ(20.0, 180.0F, RADIUS), POSITION_EPSILON);
        assertEquals(10.0 - 30.0, HailCore.centreX(10.0, 270.0F, RADIUS), POSITION_EPSILON);
        assertEquals(20.0, HailCore.centreZ(20.0, 270.0F, RADIUS), POSITION_EPSILON);
    }

    @Test
    void theOffsetGrowsWithTheRadius() {
        assertEquals(60.0, HailCore.centreX(0.0, 90.0F, 200.0F), POSITION_EPSILON);
        assertEquals(5.0, HailCore.centreX(5.0, 90.0F, 0.0F), POSITION_EPSILON);
    }

    @Test
    void theCoreRadiusIsAFractionOfTheStormRadius() {
        assertEquals(40.0F, HailCore.coreRadius(RADIUS), 1.0E-4F);
        assertEquals(80.0F, HailCore.coreRadius(200.0F), 1.0E-4F);
        assertEquals(0.0F, HailCore.coreRadius(0.0F));
        assertEquals(0.0F, HailCore.coreRadius(-5.0F));
    }

    @Test
    void hailIsFullInsideSevenTenthsOfTheCore() {
        // The core has a radius of 40 around (0, -30); it is full to 28 blocks from that centre.
        assertEquals(HAIL, northbound(0.0, -30.0), STRENGTH_EPSILON);
        assertEquals(HAIL, northbound(27.9, -30.0), STRENGTH_EPSILON);
        assertEquals(HAIL, northbound(-27.9, -30.0), STRENGTH_EPSILON);
        assertEquals(HAIL, northbound(0.0, -57.9), STRENGTH_EPSILON);
        assertEquals(HAIL, northbound(0.0, -2.1), STRENGTH_EPSILON);
        assertEquals(HAIL, northbound(19.0, -49.0), STRENGTH_EPSILON);
    }

    @Test
    void hailFallsLinearlyFromTheFullZoneToTheEdge() {
        // Between 28 and 40 blocks from the centre the strength drops by a twelfth of the hail per block.
        assertEquals(HAIL * 0.5F, northbound(34.0, -30.0), STRENGTH_EPSILON);
        assertEquals(HAIL * 0.75F, northbound(-31.0, -30.0), STRENGTH_EPSILON);
        assertEquals(HAIL / 12.0F, northbound(0.0, -69.0), STRENGTH_EPSILON);
        // The storm centre itself is 30 blocks behind the core's centre: ten of the twelve blocks of falloff left.
        assertEquals(HAIL * 10.0F / 12.0F, northbound(0.0, 0.0), STRENGTH_EPSILON);
    }

    @Test
    void hailIsZeroAtTheEdgeAndOutsideTheCore() {
        // The edge is 40 blocks from the centre; across the heading that is exactly x = 40 and x = -40.
        assertEquals(0.0F, northbound(40.0, -30.0));
        assertEquals(0.0F, northbound(-40.0, -30.0));
        assertEquals(0.0F, northbound(0.0, -70.01));
        assertEquals(0.0F, northbound(0.0, 10.01));
        assertEquals(0.0F, northbound(50.0, -30.0));
        assertEquals(0.0F, northbound(0.0, 90.0));
        assertEquals(0.0F, northbound(1000.0, 1000.0));
    }

    @Test
    void theCoreTurnsWithTheHeading() {
        // Heading east the core's centre is (30, 0): full there, nothing where the northbound core was.
        assertEquals(HAIL, HailCore.strengthAt(0.0, 0.0, 90.0F, RADIUS, HAIL, 30.0, 0.0), STRENGTH_EPSILON);
        assertEquals(0.0F, HailCore.strengthAt(0.0, 0.0, 90.0F, RADIUS, HAIL, 0.0, -30.0));
        // A storm elsewhere carries its core along.
        assertEquals(HAIL, HailCore.strengthAt(500.0, -300.0, 180.0F, RADIUS, HAIL, 500.0, -270.0),
            STRENGTH_EPSILON);
    }

    @Test
    void theStrengthNeverRisesWithTheDistanceFromTheCentre() {
        float previous = northbound(0.0, -30.0);
        for (int step = 1; step <= 500; step++) {
            float strength = northbound(step * 0.1, -30.0);
            assertTrue(strength <= previous + 1.0E-6F, "strength rose at " + step * 0.1 + " blocks");
            assertTrue(strength >= 0.0F && strength <= HAIL, "strength out of range: " + strength);
            previous = strength;
        }
        assertEquals(0.0F, previous);
    }

    @Test
    void aStormWithoutHailOrWithoutARadiusHasNoCore() {
        assertEquals(0.0F, HailCore.strengthAt(0.0, 0.0, 0.0F, RADIUS, 0.0F, 0.0, -30.0));
        assertEquals(0.0F, HailCore.strengthAt(0.0, 0.0, 0.0F, RADIUS, -1.0F, 0.0, -30.0));
        assertEquals(0.0F, HailCore.strengthAt(0.0, 0.0, 0.0F, RADIUS, Float.NaN, 0.0, -30.0));
        assertEquals(0.0F, HailCore.strengthAt(0.0, 0.0, 0.0F, 0.0F, HAIL, 0.0, 0.0));
    }

    @Test
    void aStrengthAboveOneCountsAsOne() {
        assertEquals(1.0F, HailCore.strengthAt(0.0, 0.0, 0.0F, RADIUS, 1.5F, 0.0, -30.0));
    }
}
