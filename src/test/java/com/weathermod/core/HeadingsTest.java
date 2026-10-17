package com.weathermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Checks the compass convention both sides of the mod predict storm movement with. Needs no Minecraft bootstrap. */
class HeadingsTest {
    private static final float EPSILON = 1.0E-4F;

    @Test
    void wrapDegreesLandsInZeroToThreeSixty() {
        assertEquals(0.0F, Headings.wrapDegrees(0.0F), EPSILON);
        assertEquals(270.0F, Headings.wrapDegrees(-90.0F), EPSILON);
        assertEquals(5.0F, Headings.wrapDegrees(725.0F), EPSILON);
        assertEquals(0.0F, Headings.wrapDegrees(360.0F), EPSILON);
        assertEquals(0.0F, Headings.wrapDegrees(-720.0F), EPSILON);
        assertEquals(359.5F, Headings.wrapDegrees(-0.5F), EPSILON);
    }

    @Test
    void directionVectorsFollowTheCompass() {
        assertEquals(0.0D, Headings.directionX(0.0F), 1.0E-9D);
        assertEquals(-1.0D, Headings.directionZ(0.0F), 1.0E-9D, "heading 0 is north, toward negative z");
        assertEquals(1.0D, Headings.directionX(90.0F), 1.0E-9D, "heading 90 is east, toward positive x");
        assertEquals(0.0D, Headings.directionZ(90.0F), 1.0E-9D);
        assertEquals(0.0D, Headings.directionX(180.0F), 1.0E-9D);
        assertEquals(1.0D, Headings.directionZ(180.0F), 1.0E-9D, "heading 180 is south, toward positive z");
        assertEquals(-1.0D, Headings.directionX(270.0F), 1.0E-9D, "heading 270 is west, toward negative x");
        assertEquals(0.0D, Headings.directionZ(270.0F), 1.0E-9D);
    }
}
