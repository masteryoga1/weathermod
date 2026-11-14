package com.weathermod.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.Headings;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Checks the geometry of the weather deflector: a storm centre inside a circle is pushed straight out to one block
 * beyond it, overlapping circles cannot hold the call up, and a storm heading into a circle is turned onto the
 * tangent nearer to its heading. A deflector at block {@code (0, y, 0)} has its circle around {@code (0.5, 0.5)}.
 * Needs no Minecraft bootstrap.
 */
class DeflectorMathTest {
    private static final double EPSILON = 1.0E-9;
    private static final float DEGREES_EPSILON = 1.0E-3F;
    /** The default of the {@code deflectorRadius} server setting. */
    private static final double RADIUS_BLOCKS = 48.0;
    private static final double MARGIN_BLOCKS = 4.0;
    /** How far from a deflector's centre a pushed point ends: 48 + 4 + 1. */
    private static final double PUSHED_BLOCKS = RADIUS_BLOCKS + MARGIN_BLOCKS + DeflectorMath.EDGE_MARGIN_BLOCKS;
    private static final double CENTRE = 0.5;
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    private static final List<BlockPos> AT_ORIGIN = List.of(ORIGIN);

    @Test
    void constantsAreTheNumbersOfTheDesign() {
        assertEquals(1.0, DeflectorMath.EDGE_MARGIN_BLOCKS);
        assertEquals(4, DeflectorMath.MAX_PUSH_ITERATIONS);
        assertEquals(2.0, DeflectorMath.SLIDE_BAND_BLOCKS);
        assertEquals(53.0, PUSHED_BLOCKS);
    }

    @Test
    void aPointInsideEndsOneBlockOutsideTheCircleOnTheSameBearing() {
        double[] out = new double[2];

        // Ten blocks east of the centre: straight on to the east.
        assertTrue(DeflectorMath.pushOut(10.5, 0.5, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(53.5, out[0], EPSILON);
        assertEquals(0.5, out[1], EPSILON);

        // Three east and four south of the centre, five away: the bearing (0.6, 0.8) is kept.
        assertTrue(DeflectorMath.pushOut(3.5, 4.5, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(CENTRE + 0.6 * PUSHED_BLOCKS, out[0], EPSILON);
        assertEquals(CENTRE + 0.8 * PUSHED_BLOCKS, out[1], EPSILON);
        assertEquals(PUSHED_BLOCKS, Math.hypot(out[0] - CENTRE, out[1] - CENTRE), EPSILON);

        // Six west and eight north of the centre, ten away.
        assertTrue(DeflectorMath.pushOut(-5.5, -7.5, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(-31.3, out[0], EPSILON);
        assertEquals(-41.9, out[1], EPSILON);
    }

    @Test
    void aPointOnOrOutsideTheCircleIsLeftAlone() {
        double[] out = {Double.NaN, Double.NaN};
        assertFalse(DeflectorMath.pushOut(60.5, 0.5, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(60.5, out[0]);
        assertEquals(0.5, out[1]);

        // Exactly 52 blocks from the centre is on the circle, which is not inside it.
        assertFalse(DeflectorMath.pushOut(52.5, 0.5, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(52.5, out[0]);
        assertEquals(0.5, out[1]);
        assertFalse(DeflectorMath.pushOut(0.5, -51.5, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(0.5, out[0]);
        assertEquals(-51.5, out[1]);

        // Just inside it is pushed.
        assertTrue(DeflectorMath.pushOut(52.25, 0.5, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(53.5, out[0], EPSILON);
    }

    @Test
    void theMarginWidensTheCircle() {
        double[] out = new double[2];
        // 50 blocks from the centre: outside the bare 48, inside 48 + 4.
        assertFalse(DeflectorMath.pushOut(50.5, 0.5, 0.0, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(50.5, out[0]);
        assertTrue(DeflectorMath.pushOut(50.5, 0.5, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(53.5, out[0], EPSILON);
    }

    @Test
    void heightPlaysNoPart() {
        double[] low = new double[2];
        double[] high = new double[2];
        assertTrue(DeflectorMath.pushOut(3.5, 4.5, MARGIN_BLOCKS, List.of(new BlockPos(0, -64, 0)), RADIUS_BLOCKS,
            low));
        assertTrue(DeflectorMath.pushOut(3.5, 4.5, MARGIN_BLOCKS, List.of(new BlockPos(0, 319, 0)), RADIUS_BLOCKS,
            high));
        assertEquals(low[0], high[0]);
        assertEquals(low[1], high[1]);
        assertEquals(CENTRE + 0.6 * PUSHED_BLOCKS, low[0], EPSILON);
    }

    @Test
    void aPointOnTheCentreMovesTowardPositiveX() {
        double[] out = new double[2];
        assertTrue(DeflectorMath.pushOut(0.5, 0.5, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(53.5, out[0], EPSILON);
        assertEquals(0.5, out[1], EPSILON);
    }

    @Test
    void theNearestCircleIsLeftFirst() {
        // Centres at x = 0.5 and x = 10.5; the point at x = 8.5 is two blocks from the second and eight from the
        // first. Away from the second it goes west to 10.5 - 53 = -42.5, which is 43 from the first, and from
        // there on west to 0.5 - 53 = -52.5. Leaving the first circle first would have ended in the east.
        BlockPos second = new BlockPos(10, 64, 0);
        double[] out = new double[2];
        assertTrue(DeflectorMath.pushOut(8.5, 0.5, MARGIN_BLOCKS, List.of(ORIGIN, second), RADIUS_BLOCKS, out));
        assertEquals(-52.5, out[0], EPSILON);
        assertEquals(0.5, out[1], EPSILON);
        assertTrue(DeflectorMath.pushOut(8.5, 0.5, MARGIN_BLOCKS, List.of(second, ORIGIN), RADIUS_BLOCKS, out));
        assertEquals(-52.5, out[0], EPSILON);
        assertEquals(0.5, out[1], EPSILON);
    }

    @Test
    void leavingOneCircleIntoAnotherGoesOnThroughIt() {
        // Centres at x = 0.5 and x = 40.5. From x = 15.5 the point leaves the first circle at 53.5, which is 13
        // beyond the second centre, and leaves the second at 40.5 + 53 = 93.5, outside both.
        List<BlockPos> deflectors = List.of(ORIGIN, new BlockPos(40, 64, 0));
        double[] out = new double[2];
        assertTrue(DeflectorMath.pushOut(15.5, 0.5, MARGIN_BLOCKS, deflectors, RADIUS_BLOCKS, out));
        assertEquals(93.5, out[0], EPSILON);
        assertEquals(0.5, out[1], EPSILON);
        assertFalse(DeflectorMath.pushOut(out[0], out[1], MARGIN_BLOCKS, deflectors, RADIUS_BLOCKS, new double[2]),
            "the point is outside every circle now");
    }

    @Test
    void circlesThatPassAPointBackAndForthStopAtTheIterationCap() {
        // Centres at x = 0.5 and x = 100.5, circles of 52: they overlap between 48.5 and 52.5, and one block
        // outside either circle lies inside the other. The point goes 53.5, 47.5, 53.5, 47.5 and the call ends
        // after its four steps instead of looping.
        List<BlockPos> deflectors = List.of(ORIGIN, new BlockPos(100, 64, 0));
        double[] out = new double[2];
        assertTrue(DeflectorMath.pushOut(30.5, 0.5, MARGIN_BLOCKS, deflectors, RADIUS_BLOCKS, out));
        assertEquals(100.5 - PUSHED_BLOCKS, out[0], EPSILON);
        assertEquals(0.5, out[1], EPSILON);
    }

    @Test
    void noDeflectorOrNoRadiusLeavesThePointAlone() {
        double[] out = {Double.NaN, Double.NaN};
        assertFalse(DeflectorMath.pushOut(10.5, 0.5, MARGIN_BLOCKS, List.of(), RADIUS_BLOCKS, out));
        assertEquals(10.5, out[0]);
        assertEquals(0.5, out[1]);

        out[0] = Double.NaN;
        out[1] = Double.NaN;
        assertFalse(DeflectorMath.pushOut(0.5, 0.5, 0.0, AT_ORIGIN, 0.0, out));
        assertEquals(0.5, out[0]);
        assertEquals(0.5, out[1]);
        assertFalse(DeflectorMath.pushOut(10.5, 0.5, -RADIUS_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS, out));
        assertEquals(10.5, out[0]);
    }

    @Test
    void aStormHeadingStraightInTurnsToItsRight() {
        // Each storm is 50 blocks from the centre, inside the band that ends at 48 + 1 + 2.
        assertEquals(90.0F, slide(0.5, 50.5, 0.0F), DEGREES_EPSILON, "from the south heading north: east");
        assertEquals(270.0F, slide(0.5, -49.5, 180.0F), DEGREES_EPSILON, "from the north heading south: west");
        assertEquals(0.0F, slide(50.5, 0.5, 270.0F), DEGREES_EPSILON, "from the east heading west: north");
        assertEquals(180.0F, slide(-49.5, 0.5, 90.0F), DEGREES_EPSILON, "from the west heading east: south");
    }

    @Test
    void aStormInTheBandTakesTheTangentNearerToItsHeading() {
        // South of the centre the tangents point east and west.
        assertEquals(90.0F, slide(0.5, 50.5, 20.0F), DEGREES_EPSILON);
        assertEquals(270.0F, slide(0.5, 50.5, 340.0F), DEGREES_EPSILON);
        assertEquals(90.0F, slide(0.5, 50.5, 80.0F), DEGREES_EPSILON);
        assertEquals(270.0F, slide(0.5, 50.5, 280.0F), DEGREES_EPSILON);

        // 30 east and 40 south of the centre the centre lies toward (-30, -40); the tangents are (40, -30), a
        // heading of atan2(40, 30) = 53.13 degrees, and its opposite.
        float right = (float) Math.toDegrees(Math.atan2(40.0, 30.0));
        assertEquals(right, slide(30.5, 40.5, 0.0F), DEGREES_EPSILON, "heading north leans to the right-hand one");
        assertEquals(right + 180.0F, slide(30.5, 40.5, 270.0F), DEGREES_EPSILON,
            "heading west leans to the left-hand one");
    }

    @Test
    void aStormHeadingAwayKeepsItsHeading() {
        assertEquals(180.0F, slide(0.5, 50.5, 180.0F));
        assertEquals(135.0F, slide(0.5, 50.5, 135.0F));
        assertEquals(225.5F, slide(0.5, 50.5, 225.5F));
        assertEquals(180.0F, slide(0.5, 30.5, 180.0F), "also from inside the circle");
    }

    @Test
    void aStormOutsideTheBandKeepsItsHeading() {
        // 52 blocks from the centre is beyond 48 + 1 + 2.
        assertEquals(0.0F, slide(0.5, 52.5, 0.0F));
        assertEquals(20.0F, slide(0.5, 52.5, 20.0F));
        assertEquals(20.0F, slide(0.5, 600.5, 20.0F));
        // With a margin of four the band ends at 55.
        assertEquals(90.0F, DeflectorMath.slideHeading(0.5, 52.5, 20.0F, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS),
            DEGREES_EPSILON);
        assertEquals(20.0F, DeflectorMath.slideHeading(0.5, 56.5, 20.0F, MARGIN_BLOCKS, AT_ORIGIN, RADIUS_BLOCKS));
    }

    @Test
    void aStormInsideTheCircleHeadingFurtherInIsTurnedToo() {
        assertEquals(90.0F, slide(0.5, 30.5, 0.0F), DEGREES_EPSILON);
        assertEquals(270.0F, slide(0.5, 30.5, 340.0F), DEGREES_EPSILON);
    }

    @Test
    void withoutDeflectorsTheHeadingIsReturnedAsGiven() {
        assertEquals(123.5F, DeflectorMath.slideHeading(0.5, 50.5, 123.5F, 0.0, List.of(), RADIUS_BLOCKS));
        assertEquals(0.0F, DeflectorMath.slideHeading(0.5, 50.5, 0.0F, 0.0, List.of(), RADIUS_BLOCKS));
    }

    @Test
    void theNearestDeflectorAheadDecides() {
        // One deflector 50 blocks north of the storm, one 50 blocks south: the one ahead counts.
        List<BlockPos> northAndSouth = List.of(ORIGIN, new BlockPos(0, 64, 100));
        assertEquals(90.0F, DeflectorMath.slideHeading(0.5, 50.5, 0.0F, 0.0, northAndSouth, RADIUS_BLOCKS),
            DEGREES_EPSILON);
        assertEquals(270.0F, DeflectorMath.slideHeading(0.5, 50.5, 180.0F, 0.0, northAndSouth, RADIUS_BLOCKS),
            DEGREES_EPSILON);
        assertEquals(90.0F, DeflectorMath.slideHeading(0.5, 50.5, 160.0F, 0.0, northAndSouth, RADIUS_BLOCKS),
            DEGREES_EPSILON);

        // Both ahead of a storm heading north: the one 30 blocks due north is nearer than the one 50 blocks to
        // the north-west, whose tangent would be 53.13 degrees.
        List<BlockPos> twoAhead = List.of(ORIGIN, new BlockPos(30, 64, 10));
        assertEquals(90.0F, DeflectorMath.slideHeading(30.5, 40.5, 0.0F, 0.0, twoAhead, RADIUS_BLOCKS),
            DEGREES_EPSILON);
    }

    @Test
    void aTurnedHeadingIsATangentWithinAQuarterTurnOfTheOldOneAndWrapped() {
        double[][] storms = {{0.5, 50.5}, {50.5, 0.5}, {0.5, -49.5}, {-49.5, 0.5}, {30.5, 40.5}, {-29.5, -39.5}};
        for (double[] storm : storms) {
            double towardX = CENTRE - storm[0];
            double towardZ = CENTRE - storm[1];
            for (int degrees = 0; degrees < 360; degrees += 15) {
                float heading = degrees;
                double approach = Headings.directionX(heading) * towardX + Headings.directionZ(heading) * towardZ;
                if (Math.abs(approach) < 1.0E-6) {
                    // Along the tangent already: rounding decides whether this counts as heading in.
                    continue;
                }
                float result = slide(storm[0], storm[1], heading);
                String label = "storm " + storm[0] + "," + storm[1] + " heading " + degrees;
                if (approach < 0.0) {
                    assertEquals(heading, result, label + " heads away");
                    continue;
                }
                assertTrue(result >= 0.0F && result < 360.0F, label + " gave " + result);
                double resultX = Headings.directionX(result);
                double resultZ = Headings.directionZ(result);
                assertEquals(0.0, resultX * towardX + resultZ * towardZ, 1.0E-3, label + " must run along the circle");
                double cosine = resultX * Headings.directionX(heading) + resultZ * Headings.directionZ(heading);
                assertTrue(cosine > -1.0E-6, label + " turned by more than a quarter turn to " + result);
            }
        }
    }

    /** {@link DeflectorMath#slideHeading} for the deflector at the origin with the default radius and no margin. */
    private static float slide(double x, double z, float headingDegrees) {
        return DeflectorMath.slideHeading(x, z, headingDegrees, 0.0, AT_ORIGIN, RADIUS_BLOCKS);
    }
}
