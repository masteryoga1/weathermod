package com.weathermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Pins the geometry helpers of the funnel spine: the height fraction, the interpolated offset, the flared radius
 * profile, the blend between two spines and the constructor's checks. Pure arithmetic, no Minecraft bootstrap.
 */
class FunnelSpineTest {
    private static final float EPSILON = 1.0E-5F;

    /** A straight spine from y 64 to y 160 with base radius 3 and top radius 10, on the ground at full strength. */
    private static FunnelSpine upright() {
        return new FunnelSpine(List.of(new Vector3f(0.0F, 64.0F, 0.0F), new Vector3f(0.0F, 160.0F, 0.0F)),
            3.0F, 10.0F, 1.0F, 1.0F, true);
    }

    /** A bent spine of three unevenly spaced points: ground at y 64, a knee at y 100, top at y 160. */
    private static FunnelSpine bent() {
        return new FunnelSpine(List.of(new Vector3f(0.0F, 64.0F, 0.0F), new Vector3f(4.0F, 100.0F, -2.0F),
            new Vector3f(10.0F, 160.0F, 6.0F)), 2.0F, 8.0F, 0.5F, 1.0F, true);
    }

    private static FunnelSpine twoPoints(float descent) {
        return new FunnelSpine(List.of(new Vector3f(0.0F, 64.0F, 0.0F), new Vector3f(0.0F, 160.0F, 0.0F)),
            3.0F, 10.0F, 1.0F, descent, false);
    }

    @Test
    void constantsMatchTheWireFormat() {
        assertEquals(24, FunnelSpine.POINTS);
        assertEquals(32, FunnelSpine.MAX_POINTS);
        assertTrue(FunnelSpine.POINTS <= FunnelSpine.MAX_POINTS, "a server spine must fit the decode limit");
        assertEquals(2.2F, FunnelSpine.FLARE_EXPONENT, EPSILON);
    }

    @Test
    void groundAndTopAreTheFirstAndLastPoint() {
        FunnelSpine spine = bent();
        assertEquals(64.0F, spine.groundY(), EPSILON);
        assertEquals(160.0F, spine.topY(), EPSILON);
        assertEquals(3, spine.points().size());
    }

    @Test
    void fractionAtClampsBelowTheGroundAndAboveTheTop() {
        FunnelSpine spine = upright();

        assertEquals(0.0F, spine.fractionAt(-64.0D), EPSILON);
        assertEquals(0.0F, spine.fractionAt(63.999D), EPSILON);
        assertEquals(0.0F, spine.fractionAt(64.0D), EPSILON, "the ground point itself is fraction 0");
        assertEquals(0.25F, spine.fractionAt(88.0D), EPSILON);
        assertEquals(0.5F, spine.fractionAt(112.0D), EPSILON);
        assertEquals(0.75F, spine.fractionAt(136.0D), EPSILON);
        assertEquals(1.0F, spine.fractionAt(160.0D), EPSILON, "the top point itself is fraction 1");
        assertEquals(1.0F, spine.fractionAt(160.001D), EPSILON);
        assertEquals(1.0F, spine.fractionAt(320.0D), EPSILON);
    }

    @Test
    void fractionAtGoesByHeightNotByPointIndex() {
        FunnelSpine spine = bent();
        assertEquals(0.375F, spine.fractionAt(100.0D), EPSILON,
            "the knee is the middle point but sits 36 of 96 blocks up");
    }

    @Test
    void fractionAtOfAFlatSpineNeverDivides() {
        FunnelSpine flat = new FunnelSpine(List.of(new Vector3f(0.0F, 70.0F, 0.0F), new Vector3f(1.0F, 70.0F, 1.0F)),
            3.0F, 10.0F, 1.0F, 1.0F, true);

        assertEquals(0.0F, flat.fractionAt(69.0D), EPSILON);
        assertEquals(0.0F, flat.fractionAt(70.0D), EPSILON);
        assertEquals(1.0F, flat.fractionAt(70.5D), EPSILON);
        assertFalse(Float.isNaN(flat.radiusAt(70.0D)));
    }

    @Test
    void offsetAtInterpolatesBetweenNeighbouringPoints() {
        FunnelSpine spine = bent();
        Vector3f out = new Vector3f(99.0F, 99.0F, 99.0F);

        spine.offsetAt(82.0D, out);
        assertEquals(2.0F, out.x(), EPSILON, "half way up the lower segment");
        assertEquals(-1.0F, out.z(), EPSILON);
        assertEquals(99.0F, out.y(), EPSILON, "only x and z are written");

        spine.offsetAt(100.0D, out);
        assertEquals(4.0F, out.x(), EPSILON, "exactly on the knee");
        assertEquals(-2.0F, out.z(), EPSILON);

        spine.offsetAt(130.0D, out);
        assertEquals(7.0F, out.x(), EPSILON, "half way up the upper segment");
        assertEquals(2.0F, out.z(), EPSILON);

        spine.offsetAt(145.0D, out);
        assertEquals(8.5F, out.x(), EPSILON, "three quarters up the upper segment");
        assertEquals(4.0F, out.z(), EPSILON);
        assertEquals(99.0F, out.y(), EPSILON);
    }

    @Test
    void offsetAtHoldsTheEndPointsOutsideTheSpine() {
        FunnelSpine leaning = new FunnelSpine(List.of(new Vector3f(1.5F, 64.0F, -0.5F),
            new Vector3f(4.0F, 100.0F, -2.0F), new Vector3f(10.0F, 160.0F, 6.0F)), 2.0F, 8.0F, 0.5F, 1.0F, true);
        Vector3f out = new Vector3f();

        leaning.offsetAt(0.0D, out);
        assertEquals(1.5F, out.x(), EPSILON, "below the ground the ground point's offset holds");
        assertEquals(-0.5F, out.z(), EPSILON);

        leaning.offsetAt(64.0D, out);
        assertEquals(1.5F, out.x(), EPSILON);
        assertEquals(-0.5F, out.z(), EPSILON);

        leaning.offsetAt(160.0D, out);
        assertEquals(10.0F, out.x(), EPSILON);
        assertEquals(6.0F, out.z(), EPSILON);

        leaning.offsetAt(400.0D, out);
        assertEquals(10.0F, out.x(), EPSILON, "above the top the top point's offset holds");
        assertEquals(6.0F, out.z(), EPSILON);
    }

    @Test
    void radiusAtFollowsTheFlareProfile() {
        FunnelSpine spine = upright();

        assertEquals(3.0F, spine.radiusAt(0.0D), EPSILON, "the base radius at and below the ground");
        assertEquals(3.0F, spine.radiusAt(64.0D), EPSILON);
        assertEquals(10.0F, spine.radiusAt(160.0D), EPSILON, "the top radius at and above the top");
        assertEquals(10.0F, spine.radiusAt(500.0D), EPSILON);

        float quarter = 3.0F + 7.0F * (float) Math.pow(0.25D, 2.2D);
        float half = 3.0F + 7.0F * (float) Math.pow(0.5D, 2.2D);
        float threeQuarters = 3.0F + 7.0F * (float) Math.pow(0.75D, 2.2D);
        assertEquals(quarter, spine.radiusAt(88.0D), EPSILON);
        assertEquals(half, spine.radiusAt(112.0D), EPSILON);
        assertEquals(threeQuarters, spine.radiusAt(136.0D), EPSILON);

        assertTrue(spine.radiusAt(112.0D) < 6.5F, "the funnel stays narrower than a cone at half height");
        assertTrue(spine.radiusAt(88.0D) < spine.radiusAt(112.0D) && spine.radiusAt(112.0D) < spine.radiusAt(136.0D),
            "the radius grows with height");
        assertTrue(spine.radiusAt(136.0D) - spine.radiusAt(112.0D) > spine.radiusAt(112.0D) - spine.radiusAt(88.0D),
            "it flares: the upper half widens faster than the lower");
    }

    @Test
    void radiusAtOfAnInvertedProfileShrinksUpward() {
        FunnelSpine spine = new FunnelSpine(List.of(new Vector3f(0.0F, 0.0F, 0.0F), new Vector3f(0.0F, 10.0F, 0.0F)),
            8.0F, 4.0F, 1.0F, 1.0F, true);

        assertEquals(8.0F, spine.radiusAt(0.0D), EPSILON);
        assertEquals(8.0F - 4.0F * (float) Math.pow(0.5D, 2.2D), spine.radiusAt(5.0D), EPSILON);
        assertEquals(4.0F, spine.radiusAt(10.0D), EPSILON);
    }

    @Test
    void lerpAtZeroKeepsTheOriginButTakesGroundedFromTheTarget() {
        FunnelSpine from = new FunnelSpine(List.of(new Vector3f(0.0F, 64.0F, 0.0F), new Vector3f(0.0F, 160.0F, 0.0F)),
            2.0F, 8.0F, 0.25F, 0.0F, false);
        FunnelSpine to = new FunnelSpine(List.of(new Vector3f(4.0F, 70.0F, -4.0F), new Vector3f(8.0F, 170.0F, 2.0F)),
            4.0F, 12.0F, 0.75F, 1.0F, true);

        FunnelSpine start = FunnelSpine.lerp(from, to, 0.0F);

        assertEquals(from.points(), start.points());
        assertEquals(2.0F, start.baseRadius(), EPSILON);
        assertEquals(8.0F, start.topRadius(), EPSILON);
        assertEquals(0.25F, start.strength(), EPSILON);
        assertEquals(0.0F, start.descent(), EPSILON);
        assertTrue(start.grounded(), "grounded comes from the target whatever t is");
        assertNotSame(from.points().get(0), start.points().get(0), "the blend owns its vectors");

        assertEquals(start, FunnelSpine.lerp(from, to, -3.0F), "t is clamped at 0");
        assertEquals(start, FunnelSpine.lerp(from, to, Float.NaN), "a t that is not a number counts as 0");
    }

    @Test
    void lerpAtOneIsTheTarget() {
        FunnelSpine from = new FunnelSpine(List.of(new Vector3f(0.1F, 64.3F, 0.7F), new Vector3f(0.9F, 160.1F, 0.3F)),
            2.1F, 8.3F, 0.2F, 0.3F, true);
        FunnelSpine to = new FunnelSpine(List.of(new Vector3f(4.7F, 70.9F, -4.1F), new Vector3f(8.3F, 170.7F, 2.9F)),
            4.3F, 12.7F, 0.6F, 0.7F, false);

        assertSame(to, FunnelSpine.lerp(from, to, 1.0F), "the end of the blend is the target itself, bit for bit");
        assertSame(to, FunnelSpine.lerp(from, to, 7.5F), "t is clamped at 1");
        assertFalse(FunnelSpine.lerp(from, to, 0.5F).grounded(), "grounded comes from the target");
    }

    @Test
    void lerpMidpointInterpolatesEveryComponentIncludingTheDescent() {
        FunnelSpine from = new FunnelSpine(List.of(new Vector3f(0.0F, 64.0F, 0.0F), new Vector3f(0.0F, 160.0F, 0.0F)),
            2.0F, 8.0F, 0.25F, 0.0F, false);
        FunnelSpine to = new FunnelSpine(List.of(new Vector3f(4.0F, 70.0F, -4.0F), new Vector3f(8.0F, 170.0F, 2.0F)),
            4.0F, 12.0F, 0.75F, 1.0F, true);

        FunnelSpine middle = FunnelSpine.lerp(from, to, 0.5F);

        assertEquals(List.of(new Vector3f(2.0F, 67.0F, -2.0F), new Vector3f(4.0F, 165.0F, 1.0F)), middle.points());
        assertEquals(3.0F, middle.baseRadius(), EPSILON);
        assertEquals(10.0F, middle.topRadius(), EPSILON);
        assertEquals(0.5F, middle.strength(), EPSILON);
        assertEquals(0.5F, middle.descent(), EPSILON, "the descent interpolates like everything else");
        assertTrue(middle.grounded(), "grounded comes from the target, even while the descent is below 1");

        FunnelSpine quarter = FunnelSpine.lerp(from, to, 0.25F);
        assertEquals(List.of(new Vector3f(1.0F, 65.5F, -1.0F), new Vector3f(2.0F, 162.5F, 0.5F)), quarter.points());
        assertEquals(2.5F, quarter.baseRadius(), EPSILON);
        assertEquals(9.0F, quarter.topRadius(), EPSILON);
        assertEquals(0.375F, quarter.strength(), EPSILON);
        assertEquals(0.25F, quarter.descent(), EPSILON);
    }

    @Test
    void lerpOfMismatchedPointCountsReturnsTheTarget() {
        FunnelSpine two = upright();
        FunnelSpine three = bent();

        assertSame(three, FunnelSpine.lerp(two, three, 0.0F));
        assertSame(three, FunnelSpine.lerp(two, three, 0.5F));
        assertSame(two, FunnelSpine.lerp(three, two, 0.5F));
        assertThrows(NullPointerException.class, () -> FunnelSpine.lerp(null, two, 0.5F));
        assertThrows(NullPointerException.class, () -> FunnelSpine.lerp(two, null, 0.5F));
    }

    @Test
    void fewerThanTwoPointsAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new FunnelSpine(List.of(), 3.0F, 10.0F, 1.0F, 1.0F, true));
        assertThrows(IllegalArgumentException.class,
            () -> new FunnelSpine(List.of(new Vector3f(0.0F, 64.0F, 0.0F)), 3.0F, 10.0F, 1.0F, 1.0F, true));
        assertThrows(NullPointerException.class, () -> new FunnelSpine(null, 3.0F, 10.0F, 1.0F, 1.0F, true));
        assertEquals(2, upright().points().size(), "two points are enough");
    }

    @Test
    void descentIsClampedToTheUnitRange() {
        assertEquals(0.0F, twoPoints(-0.5F).descent(), EPSILON);
        assertEquals(0.0F, twoPoints(0.0F).descent(), EPSILON);
        assertEquals(0.4F, twoPoints(0.4F).descent(), EPSILON);
        assertEquals(1.0F, twoPoints(1.0F).descent(), EPSILON);
        assertEquals(1.0F, twoPoints(1.5F).descent(), EPSILON);
        assertEquals(0.0F, twoPoints(Float.NaN).descent(), EPSILON, "a descent that is not a number is no descent");
        assertFalse(twoPoints(1.5F).grounded(), "the flag is taken as given; the builder derives it from the descent");
    }

    @Test
    void thePointListIsCopiedAndCannotBeChanged() {
        List<Vector3f> source = new ArrayList<>();
        source.add(new Vector3f(0.0F, 64.0F, 0.0F));
        source.add(new Vector3f(0.0F, 100.0F, 0.0F));
        source.add(new Vector3f(0.0F, 160.0F, 0.0F));
        FunnelSpine spine = new FunnelSpine(source, 3.0F, 10.0F, 1.0F, 1.0F, true);

        source.remove(2);
        source.add(new Vector3f(0.0F, 999.0F, 0.0F));

        assertEquals(3, spine.points().size());
        assertEquals(160.0F, spine.topY(), EPSILON, "the spine keeps its own list");
        assertThrows(UnsupportedOperationException.class, () -> spine.points().add(new Vector3f()));
        assertThrows(UnsupportedOperationException.class, () -> spine.points().remove(0));
    }

    @Test
    void spinesWithEqualComponentsAreEqual() {
        assertEquals(upright(), upright());
        assertEquals(upright().hashCode(), upright().hashCode());
        assertFalse(upright().equals(twoPoints(1.0F)), "grounded is part of the value");
        assertFalse(upright().equals(bent()));
    }
}
