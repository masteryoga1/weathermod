package com.weathermod.server.director;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.weathermod.core.Trigger;
import org.junit.jupiter.api.Test;

/**
 * Checks the front trigger: which anomaly differences count as a cold or a warm front, and where the two samples
 * lie around a planned position. Needs no Minecraft bootstrap: only plain trigonometry is involved.
 */
class FrontMathTest {
    private static final double EPSILON = 1.0E-9D;
    private static final double X = 1000.0D;
    private static final double Z = -250.0D;

    @Test
    void constantsMatchTheDesign() {
        assertEquals(256.0D, FrontMath.SAMPLE_OFFSET_BLOCKS);
        assertEquals(0.08F, FrontMath.FRONT_THRESHOLD);
        assertEquals("cold", Trigger.FRONT_COLD);
        assertEquals("warm", Trigger.FRONT_WARM);
    }

    @Test
    void colderAirArrivingIsAColdFront() {
        assertEquals(Trigger.FRONT_COLD, FrontMath.frontAt(0.0F, 0.08F), "the threshold itself counts");
        assertEquals(Trigger.FRONT_COLD, FrontMath.frontAt(-0.25F, 0.25F));
        assertEquals(Trigger.FRONT_COLD, FrontMath.frontAt(-0.2F, 0.0F));
        assertEquals("", FrontMath.frontAt(0.0F, 0.0799F), "just under the threshold is no front");
    }

    @Test
    void warmerAirArrivingIsAWarmFront() {
        assertEquals(Trigger.FRONT_WARM, FrontMath.frontAt(0.08F, 0.0F), "the threshold itself counts");
        assertEquals(Trigger.FRONT_WARM, FrontMath.frontAt(0.25F, -0.25F));
        assertEquals(Trigger.FRONT_WARM, FrontMath.frontAt(0.0F, -0.2F));
        assertEquals("", FrontMath.frontAt(0.0799F, 0.0F), "just under the threshold is no front");
    }

    @Test
    void evenAirIsNoFront() {
        assertEquals("", FrontMath.frontAt(0.0F, 0.0F));
        assertEquals("", FrontMath.frontAt(0.2F, 0.2F), "the same anomaly on both sides, however large");
        assertEquals("", FrontMath.frontAt(-0.1F, -0.05F));
        assertEquals("", FrontMath.frontAt(Float.NaN, 0.0F), "an anomaly that is not a number is no front");
        assertEquals("", FrontMath.frontAt(0.0F, Float.NaN));
    }

    @Test
    void northWindSamplesSouthAndNorthOfThePosition() {
        assertEquals(X, FrontMath.upwindX(X, 0.0F), EPSILON);
        assertEquals(Z + 256.0D, FrontMath.upwindZ(Z, 0.0F), EPSILON, "air heading north comes from the south, +Z");
        assertEquals(X, FrontMath.downwindX(X, 0.0F), EPSILON);
        assertEquals(Z - 256.0D, FrontMath.downwindZ(Z, 0.0F), EPSILON);
    }

    @Test
    void eastWindSamplesWestAndEastOfThePosition() {
        assertEquals(X - 256.0D, FrontMath.upwindX(X, 90.0F), EPSILON, "air heading east comes from the west, -X");
        assertEquals(Z, FrontMath.upwindZ(Z, 90.0F), EPSILON);
        assertEquals(X + 256.0D, FrontMath.downwindX(X, 90.0F), EPSILON);
        assertEquals(Z, FrontMath.downwindZ(Z, 90.0F), EPSILON);
    }

    @Test
    void southWindSamplesNorthAndSouthOfThePosition() {
        assertEquals(X, FrontMath.upwindX(X, 180.0F), EPSILON);
        assertEquals(Z - 256.0D, FrontMath.upwindZ(Z, 180.0F), EPSILON, "air heading south comes from the north, -Z");
        assertEquals(X, FrontMath.downwindX(X, 180.0F), EPSILON);
        assertEquals(Z + 256.0D, FrontMath.downwindZ(Z, 180.0F), EPSILON);
    }

    @Test
    void westWindSamplesEastAndWestOfThePosition() {
        assertEquals(X + 256.0D, FrontMath.upwindX(X, 270.0F), EPSILON, "air heading west comes from the east, +X");
        assertEquals(Z, FrontMath.upwindZ(Z, 270.0F), EPSILON);
        assertEquals(X - 256.0D, FrontMath.downwindX(X, 270.0F), EPSILON);
        assertEquals(Z, FrontMath.downwindZ(Z, 270.0F), EPSILON);
    }

    @Test
    void anyWindKeepsBothSamplesTheOffsetAwayOnOppositeSides() {
        for (int step = 0; step < 72; step++) {
            float wind = step * 5.0F + 2.5F;
            double upX = FrontMath.upwindX(X, wind);
            double upZ = FrontMath.upwindZ(Z, wind);
            double downX = FrontMath.downwindX(X, wind);
            double downZ = FrontMath.downwindZ(Z, wind);

            assertEquals(256.0D, Math.hypot(upX - X, upZ - Z), EPSILON, "upwind sample for wind " + wind);
            assertEquals(256.0D, Math.hypot(downX - X, downZ - Z), EPSILON, "downwind sample for wind " + wind);
            assertEquals(X, (upX + downX) / 2.0D, EPSILON, "the position lies midway between the samples");
            assertEquals(Z, (upZ + downZ) / 2.0D, EPSILON);
        }
    }
}
