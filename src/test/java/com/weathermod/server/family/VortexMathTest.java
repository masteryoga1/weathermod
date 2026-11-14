package com.weathermod.server.family;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.Headings;
import com.weathermod.core.VortexKind;
import com.weathermod.core.VortexParams;
import com.weathermod.core.WeatherType;
import org.junit.jupiter.api.Test;

/**
 * Pins the pure rules of the vortex family: where a tornado sits under its parent, how the tracked ground follows
 * the terrain, how tall a funnel is, when it has touched down, how a leashed child is steered and when it is lost,
 * and how the dig reference follows the terrain. Needs no Minecraft bootstrap.
 */
class VortexMathTest {
    private static final double TOLERANCE = 1.0E-4D;
    private static final float PARENT_RADIUS = 200.0F;

    private static VortexParams vortex(VortexKind kind, WeatherType.Range height) {
        return new VortexParams(kind, 0, 3.0F, 10.0F, 24.0F, height, 8.0F, 0.034F, 0.05F, 0.15F, 4.0F, 0.0F, 0.0F,
            6.0F, 0.0F, WeatherType.UNLIMITED_SPEED);
    }

    @Test
    void theConstantsAreTheAgreedOnes() {
        assertEquals(0.3F, VortexMath.GROUNDED_INTENSITY);
        assertEquals(400, VortexMath.TOUCHDOWN_DELAY_MIN_TICKS);
        assertEquals(600, VortexMath.TOUCHDOWN_DELAY_MAX_TICKS);
        assertEquals(400, VortexMath.TIER_STEP_TICKS);
        assertEquals(40, VortexMath.MIN_FUNNEL_HEIGHT_BLOCKS);
        assertEquals(0.35F, VortexMath.TORNADO_OFFSET_FRACTION);
        assertEquals(135.0F, VortexMath.TORNADO_OFFSET_ANGLE_DEGREES);
        assertEquals(0.25F, VortexMath.LEASH_FRACTION);
        assertEquals(3.0F, VortexMath.LEASH_TURN_DEGREES_PER_TICK);
        assertEquals(0.5D, VortexMath.GROUND_FOLLOW_BLOCKS_PER_TICK);
        assertEquals(100, VortexMath.OFF_SURFACE_TICKS);
        assertEquals(10, VortexMath.FIRE_ATTEMPT_INTERVAL_TICKS);
    }

    @Test
    void theAnchorOfANorthboundParentLiesToItsSouthEast() {
        double[] anchor = new double[2];

        VortexMath.tornadoAnchor(1000.0D, -2000.0D, 0.0F, PARENT_RADIUS, anchor);

        double leg = 0.35D * 200.0D * Math.sqrt(0.5D);
        assertEquals(49.4975D, leg, TOLERANCE);
        assertEquals(1000.0D + leg, anchor[0], TOLERANCE, "east of the centre: the right flank");
        assertEquals(-2000.0D + leg, anchor[1], TOLERANCE, "south of the centre: the rear");
    }

    @Test
    void theAnchorIsBehindAndToTheRightForEveryHeading() {
        double[] anchor = new double[2];
        double expected = 0.35D * PARENT_RADIUS;
        for (float heading : new float[] {0.0F, 45.0F, 90.0F, 180.0F, 270.0F, 300.0F, 359.0F}) {
            VortexMath.tornadoAnchor(-40.0D, 70.0D, heading, PARENT_RADIUS, anchor);
            double dx = anchor[0] + 40.0D;
            double dz = anchor[1] - 70.0D;

            assertEquals(expected, Math.hypot(dx, dz), TOLERANCE, "0.35 of the radius away, heading " + heading);
            double ahead = dx * Headings.directionX(heading) + dz * Headings.directionZ(heading);
            double right = dx * Headings.directionX(heading + 90.0F) + dz * Headings.directionZ(heading + 90.0F);
            assertEquals(-expected * Math.sqrt(0.5D), ahead, TOLERANCE, "behind the centre, heading " + heading);
            assertEquals(expected * Math.sqrt(0.5D), right, TOLERANCE, "right of the centre, heading " + heading);
        }
    }

    @Test
    void theAnchorScalesWithTheParentRadius() {
        double[] anchor = new double[2];

        VortexMath.tornadoAnchor(0.0D, 0.0D, 90.0F, 100.0F, anchor);

        assertEquals(-35.0D * Math.sqrt(0.5D), anchor[0], TOLERANCE, "an eastbound parent has its rear to the west");
        assertEquals(35.0D * Math.sqrt(0.5D), anchor[1], TOLERANCE, "and its right flank to the south");
    }

    @Test
    void approachMovesByAtMostTheStep() {
        assertEquals(10.5D, VortexMath.approach(10.0D, 12.0D, 0.5D));
        assertEquals(9.5D, VortexMath.approach(10.0D, 8.0D, 0.5D));
        assertEquals(10.3D, VortexMath.approach(10.0D, 10.3D, 0.5D), "a target within reach is taken exactly");
        assertEquals(9.7D, VortexMath.approach(10.0D, 9.7D, 0.5D));
        assertEquals(10.5D, VortexMath.approach(10.0D, 10.5D, 0.5D));
        assertEquals(10.0D, VortexMath.approach(10.0D, 10.0D, 0.5D));
        assertEquals(10.0D, VortexMath.approach(10.0D, 12.0D, 0.0D));
        assertEquals(10.0D, VortexMath.approach(10.0D, 12.0D, -1.0D), "a negative step counts as zero");
    }

    @Test
    void approachGuardsAgainstValuesThatAreNotNumbers() {
        assertEquals(10.0D, VortexMath.approach(10.0D, Double.NaN, 0.5D), "no target: stay");
        assertEquals(12.0D, VortexMath.approach(Double.NaN, 12.0D, 0.5D), "no current value: jump to the target");
        assertTrue(Double.isNaN(VortexMath.approach(Double.NaN, Double.NaN, 0.5D)));
    }

    @Test
    void approachReachesASampleTenBlocksUpInTwentyTicks() {
        double ground = 64.0D;
        for (int tick = 0; tick < 19; tick++) {
            ground = VortexMath.approach(ground, 74.0D, VortexMath.GROUND_FOLLOW_BLOCKS_PER_TICK);
        }
        assertEquals(73.5D, ground);
        ground = VortexMath.approach(ground, 74.0D, VortexMath.GROUND_FOLLOW_BLOCKS_PER_TICK);
        assertEquals(74.0D, ground);
        assertEquals(74.0D, VortexMath.approach(ground, 74.0D, VortexMath.GROUND_FOLLOW_BLOCKS_PER_TICK));
    }

    @Test
    void aFunnelThatReachesTheCloudBaseEndsThereOrFortyBlocksUp() {
        VortexParams tornado = vortex(VortexKind.TORNADO, WeatherType.Range.of(0.0F));
        assertTrue(tornado.reachesCloudBase());

        assertEquals(160.0D, VortexMath.topY(tornado, 64.0D, 160.0D, 0.5F, 1.0F));
        assertEquals(160.0D, VortexMath.topY(tornado, 64.0D, 160.0D, 0.9F, 0.3F), "fraction and strength do not count");
        assertEquals(160.0D, VortexMath.topY(tornado, 120.0D, 160.0D, 0.5F, 1.0F), "exactly 40 blocks are enough");
        assertEquals(170.0D, VortexMath.topY(tornado, 130.0D, 160.0D, 0.5F, 1.0F),
            "on a mountain the funnel keeps its 40 blocks and pokes into the cloud");
        assertEquals(240.0D, VortexMath.topY(tornado, 200.0D, 160.0D, 0.5F, 1.0F), "also above the cloud base");
    }

    @Test
    void aFunnelWithAHeightRangeScalesWithItsFractionAndStrength() {
        VortexParams dustDevil = vortex(VortexKind.DUST_DEVIL, new WeatherType.Range(8.0F, 20.0F));
        assertFalse(dustDevil.reachesCloudBase());

        assertEquals(78.0D, VortexMath.topY(dustDevil, 64.0D, 160.0D, 0.5F, 1.0F), TOLERANCE, "64 + 14 * 1");
        assertEquals(71.0D, VortexMath.topY(dustDevil, 64.0D, 160.0D, 0.5F, 0.0F), TOLERANCE, "64 + 14 * 0.5");
        assertEquals(72.0D, VortexMath.topY(dustDevil, 64.0D, 160.0D, 0.0F, 1.0F), TOLERANCE, "the low end");
        assertEquals(84.0D, VortexMath.topY(dustDevil, 64.0D, 160.0D, 1.0F, 1.0F), TOLERANCE, "the high end");
        assertEquals(73.1D, VortexMath.topY(dustDevil, 64.0D, 160.0D, 0.5F, 0.3F), TOLERANCE, "64 + 14 * 0.65");
        assertEquals(84.0D, VortexMath.topY(dustDevil, 64.0D, 160.0D, 2.0F, 1.0F), TOLERANCE, "fraction clamped");
        assertEquals(72.0D, VortexMath.topY(dustDevil, 64.0D, 160.0D, -1.0F, 1.0F), TOLERANCE, "fraction clamped");
        assertEquals(72.0D, VortexMath.topY(dustDevil, 64.0D, 160.0D, Float.NaN, 1.0F), TOLERANCE);
        assertEquals(34.0D, VortexMath.topY(dustDevil, 20.0D, 10.0D, 0.5F, 1.0F), TOLERANCE, "no cloud base needed");
    }

    @Test
    void theDescentFollowsTheIntensityUpToTheGroundedIntensity() {
        assertEquals(0.0F, VortexMath.descent(0.0F, 1000, 0));
        assertEquals(0.5F, VortexMath.descent(0.15F, 1000, 0), 1.0E-6F);
        assertEquals(1.0F, VortexMath.descent(0.3F, 1000, 0));
        assertEquals(1.0F, VortexMath.descent(0.9F, 1000, 0), "clamped at the top");
        assertEquals(1.0F, VortexMath.descent(5.0F, 1000, 0));
        assertEquals(0.0F, VortexMath.descent(-0.5F, 1000, 0), "clamped at the bottom");
    }

    @Test
    void theDescentIsHeldBackByTheTouchdownDelay() {
        assertEquals(0.0F, VortexMath.descent(1.0F, 0, 400));
        assertEquals(0.5F, VortexMath.descent(1.0F, 200, 400));
        assertEquals(0.9975F, VortexMath.descent(1.0F, 399, 400), 1.0E-6F);
        assertEquals(1.0F, VortexMath.descent(1.0F, 400, 400));
        assertEquals(1.0F, VortexMath.descent(1.0F, 800, 400), "clamped at the top");
        assertEquals(0.0F, VortexMath.descent(1.0F, -5, 400), "clamped at the bottom");
    }

    @Test
    void withoutADelayOnlyTheIntensityCounts() {
        assertEquals(1.0F, VortexMath.descent(0.3F, 0, 0), "a vortex spawned by command is down at once");
        assertEquals(1.0F, VortexMath.descent(1.0F, 0, -10), "a negative delay is no delay");
        assertEquals(0.5F, VortexMath.descent(0.15F, 0, 0), 1.0E-6F);
    }

    @Test
    void theDescentIsTheSmallerOfBothLimits() {
        assertEquals(0.25F, VortexMath.descent(0.15F, 100, 400), 1.0E-6F, "the delay holds it back");
        assertEquals(0.2F, VortexMath.descent(0.06F, 300, 400), 1.0E-6F, "the intensity holds it back");
    }

    @Test
    void aFunnelIsGroundedOnlyAtFullDescent() {
        assertTrue(VortexMath.grounded(1.0F));
        assertFalse(VortexMath.grounded(0.999F));
        assertFalse(VortexMath.grounded(0.0F));
        assertFalse(VortexMath.grounded(Float.NaN));

        assertTrue(VortexMath.grounded(VortexMath.descent(0.3F, 400, 400)));
        assertFalse(VortexMath.grounded(VortexMath.descent(0.29F, 400, 400)), "too weak to reach the ground");
        assertFalse(VortexMath.grounded(VortexMath.descent(1.0F, 399, 400)), "still held aloft");
    }

    @Test
    void turnTowardTakesTheShortWayAcrossNorth() {
        assertEquals(353.0F, VortexMath.turnToward(350.0F, 10.0F, 3.0F), "clockwise across the seam");
        assertEquals(1.0F, VortexMath.turnToward(358.0F, 4.0F, 3.0F), "and through it");
        assertEquals(7.0F, VortexMath.turnToward(10.0F, 350.0F, 3.0F), "anticlockwise across the seam");
        assertEquals(359.0F, VortexMath.turnToward(2.0F, 356.0F, 3.0F), "and through it");
        assertEquals(93.0F, VortexMath.turnToward(90.0F, 200.0F, 3.0F));
        assertEquals(87.0F, VortexMath.turnToward(90.0F, 300.0F, 3.0F), "210 degrees clockwise is 150 the other way");
    }

    @Test
    void turnTowardStopsOnTheTargetAndWrapsIt() {
        assertEquals(1.0F, VortexMath.turnToward(359.0F, 1.0F, 3.0F), "two degrees away is within a step of three");
        assertEquals(100.0F, VortexMath.turnToward(100.0F, 100.0F, 3.0F));
        assertEquals(103.0F, VortexMath.turnToward(100.0F, 103.0F, 3.0F), "exactly a step away");
        assertEquals(1.0F, VortexMath.turnToward(359.0F, 361.0F, 3.0F), "the result is wrapped into 0..360");
        assertEquals(270.0F, VortexMath.turnToward(269.0F, -90.0F, 3.0F));
        assertEquals(93.0F, VortexMath.turnToward(90.0F, 460.0F, 3.0F), "an unwrapped target is understood");
        assertEquals(40.0F, VortexMath.turnToward(40.0F, 90.0F, 0.0F), "no step, no turn");
    }

    @Test
    void turnTowardAnOppositeHeadingGoesAnticlockwise() {
        assertEquals(357.0F, VortexMath.turnToward(0.0F, 180.0F, 3.0F));
        assertEquals(87.0F, VortexMath.turnToward(90.0F, 270.0F, 3.0F));
    }

    @Test
    void aChildInsideTheLeashMovesWithItsParent() {
        float[] motion = new float[2];

        VortexMath.childMotion(0.0D, 0.0D, 90.0F, 10.0D, 0.0D, 90.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(90.0F, motion[0], "the parent's heading");
        assertEquals(4.0F, motion[1], "the parent's speed, not the child's maximum");

        VortexMath.childMotion(0.0D, 0.0D, 200.0F, 30.0D, 40.0D, 200.0F, 2.5F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(200.0F, motion[0], "50 blocks out is still on the leash of a 200 block parent");
        assertEquals(2.5F, motion[1]);

        VortexMath.childMotion(5.0D, 5.0D, 10.0F, 5.0D, 5.0D, 10.0F, 0.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(10.0F, motion[0], "on the anchor itself");
        assertEquals(0.0F, motion[1], "a parked parent parks its child");
    }

    @Test
    void aChildOutsideTheLeashHeadsForItsAnchorAtFullSpeed() {
        float[] motion = new float[2];

        VortexMath.childMotion(0.0D, 0.0D, 0.0F, 0.0D, -100.0D, 77.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(0.0F, motion[0], "an anchor to the north");
        assertEquals(9.0F, motion[1], "the child's maximum speed");

        VortexMath.childMotion(0.0D, 0.0D, 90.0F, 100.0D, 0.0D, 77.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(90.0F, motion[0], "an anchor to the east");

        VortexMath.childMotion(0.0D, 0.0D, 180.0F, 0.0D, 100.0D, 77.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(180.0F, motion[0], "an anchor to the south");

        VortexMath.childMotion(0.0D, 0.0D, 270.0F, -100.0D, 0.0D, 77.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(270.0F, motion[0], "an anchor to the west");

        VortexMath.childMotion(20.0D, -20.0D, 44.0F, 120.0D, -120.0D, 77.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(45.0F, motion[0], 1.0E-4F, "an anchor to the north-east, one degree off the current heading");
        assertEquals(9.0F, motion[1]);

        VortexMath.childMotion(0.0D, 0.0D, 90.0F, 50.5D, 0.0D, 77.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(9.0F, motion[1], "just beyond a quarter of the parent's radius the child hurries");
    }

    @Test
    void aLeashedChildTurnsThreeDegreesATick() {
        float[] motion = new float[2];

        VortexMath.childMotion(0.0D, 0.0D, 0.0F, 100.0D, 0.0D, 0.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(3.0F, motion[0], "one step toward the anchor in the east");

        VortexMath.childMotion(0.0D, 0.0D, 0.0F, -100.0D, 0.0D, 0.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(357.0F, motion[0], "one step toward the anchor in the west, across north");

        float heading = 0.0F;
        for (int tick = 1; tick <= 30; tick++) {
            VortexMath.childMotion(0.0D, 0.0D, heading, 100.0D, 0.0D, 0.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
            heading = motion[0];
            assertEquals(3.0F * tick, heading, 1.0E-4F, "tick " + tick);
        }
        VortexMath.childMotion(0.0D, 0.0D, heading, 100.0D, 0.0D, 0.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(90.0F, motion[0], 1.0E-4F, "once it points at the anchor it stays on it");

        VortexMath.childMotion(0.0D, 0.0D, 30.0F, 10.0D, 0.0D, 90.0F, 4.0F, PARENT_RADIUS, 9.0F, motion);
        assertEquals(33.0F, motion[0], "inside the leash the turn to the parent's heading is limited as well");
        assertEquals(4.0F, motion[1]);
    }

    @Test
    void aChildIsLostBeyondTheParentRadius() {
        assertFalse(VortexMath.lostParent(0.0D, 0.0D, 0.0D, 0.0D, PARENT_RADIUS));
        assertFalse(VortexMath.lostParent(0.0D, 0.0D, 199.0D, 0.0D, PARENT_RADIUS));
        assertFalse(VortexMath.lostParent(0.0D, 0.0D, 200.0D, 0.0D, PARENT_RADIUS), "at the radius is not lost yet");
        assertFalse(VortexMath.lostParent(10.0D, 10.0D, 130.0D, 170.0D, PARENT_RADIUS), "120 and 160 make 200");
        assertTrue(VortexMath.lostParent(0.0D, 0.0D, 200.5D, 0.0D, PARENT_RADIUS));
        assertTrue(VortexMath.lostParent(0.0D, 0.0D, 150.0D, -150.0D, PARENT_RADIUS), "the limit is round");
        assertTrue(VortexMath.lostParent(-300.0D, 0.0D, 0.0D, 0.0D, PARENT_RADIUS));
    }

    @Test
    void theDigReferenceIgnoresMissingValues() {
        assertEquals(70.0D, VortexMath.digReference(70.0D, Double.NaN, 1.0D), "no sample: keep what is known");
        assertEquals(66.0D, VortexMath.digReference(Double.NaN, 66.0D, 1.0D), "the first sample is taken as it is");
        assertTrue(Double.isNaN(VortexMath.digReference(Double.NaN, Double.NaN, 1.0D)));
    }

    @Test
    void theDigReferenceRisesAtOnce() {
        assertEquals(75.0D, VortexMath.digReference(70.0D, 75.0D, 0.0D));
        assertEquals(90.0D, VortexMath.digReference(70.0D, 90.0D, 0.25D));
        assertEquals(70.0D, VortexMath.digReference(70.0D, 70.0D, 0.25D));
    }

    @Test
    void theDigReferenceFallsOnlyAsFarAsTheStormTravelled() {
        assertEquals(69.75D, VortexMath.digReference(70.0D, 60.0D, 0.25D));
        assertEquals(69.9D, VortexMath.digReference(70.0D, 69.9D, 0.25D), "a gentle slope is followed exactly");
        assertEquals(60.0D, VortexMath.digReference(70.0D, 60.0D, 25.0D));

        double reference = 70.0D;
        for (int sample = 0; sample < 19; sample++) {
            reference = VortexMath.digReference(reference, 50.0D, 1.0D);
        }
        assertEquals(51.0D, reference, "a block down for every block travelled over a cliff");
        reference = VortexMath.digReference(reference, 50.0D, 1.0D);
        assertEquals(50.0D, reference);
        assertEquals(50.0D, VortexMath.digReference(reference, 50.0D, 1.0D));
    }

    @Test
    void theDigReferenceOfAParkedVortexIsFrozen() {
        double reference = 70.0D;
        for (int sample = 0; sample < 1000; sample++) {
            reference = VortexMath.digReference(reference, 40.0D, 0.0D);
        }
        assertEquals(70.0D, reference, "a hole dug under a parked vortex never lowers its own limit");
        assertEquals(70.0D, VortexMath.digReference(70.0D, 40.0D, -3.0D), "negative travel counts as none");
    }
}
