package com.weathermod.server.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.Headings;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Checks the upwind placement band, the sideways jitter and the bounded heading bias of the spawn planner. Needs
 * no Minecraft bootstrap: only {@code RandomSource} and plain trigonometry are involved.
 */
class SpawnPlannerTest {
    private static final float EPSILON = 1.0E-4F;
    private static final float RADIUS = 512.0F;

    @Test
    void distanceFractionsAgreeWithTheDesign() {
        assertEquals(0.55F, SpawnPlanner.MIN_DISTANCE_FRACTION, EPSILON);
        assertEquals(0.80F, SpawnPlanner.MAX_DISTANCE_FRACTION, EPSILON);
        assertEquals(0.30F, SpawnPlanner.LATERAL_JITTER_FRACTION, EPSILON);
    }

    @Test
    void calmAirPullsTheSpawnInToTheEdgeOfFullCoverage() {
        RandomSource random = RandomSource.create(7L);
        for (int i = 0; i < 100; i++) {
            SpawnPlanner.Plan plan = SpawnPlanner.upwind(0.0D, 0.0D, 90.0F, RADIUS, 128.0F, 0.0F, random);

            double west = -plan.x();
            assertEquals(0.6D * 128.0D, west, 1.0E-3D, "no drift: the storm forms at 60 percent of its own radius");
            assertTrue(Math.abs(plan.z()) <= 0.3D * RADIUS * (west / (0.55D * RADIUS)) + 1.0E-3D,
                "the sideways offset shrinks with the distance: " + plan.z());
        }
    }

    @Test
    void slowDriftCapsTheDistanceByTheArrivalTime() {
        RandomSource random = RandomSource.create(11L);
        float drift = 0.6F;
        double reach = 0.6D * 128.0D + drift * SpawnPlanner.MAX_ARRIVAL_SECONDS;
        for (int i = 0; i < 100; i++) {
            SpawnPlanner.Plan plan = SpawnPlanner.upwind(0.0D, 0.0D, 90.0F, RADIUS, 128.0F, drift, random);
            double west = -plan.x();
            assertTrue(west <= reach + 1.0E-3D, "the storm must arrive within the arrival time: " + west);
            assertTrue(west >= 0.6D * 128.0D - 1.0E-3D, "but never inside the edge of full coverage: " + west);
        }
    }

    @Test
    void fastDriftKeepsTheDesignedBand() {
        RandomSource band = RandomSource.create(3L);
        RandomSource capped = RandomSource.create(3L);
        for (int i = 0; i < 100; i++) {
            SpawnPlanner.Plan expected = SpawnPlanner.upwind(50.0D, 60.0D, 200.0F, RADIUS, band);
            SpawnPlanner.Plan plan = SpawnPlanner.upwind(50.0D, 60.0D, 200.0F, RADIUS, 128.0F, 4.0F, capped);
            assertEquals(expected.x(), plan.x(), 1.0E-6D, "4 blocks per second reaches the whole band in time");
            assertEquals(expected.z(), plan.z(), 1.0E-6D);
            assertEquals(expected.headingDegrees(), plan.headingDegrees(), EPSILON);
        }
    }

    @Test
    void eastWindPlacesTheStormWestOfTheAnchor() {
        RandomSource random = RandomSource.create(42L);
        for (int i = 0; i < 200; i++) {
            SpawnPlanner.Plan plan = SpawnPlanner.upwind(1000.0D, -250.0D, 90.0F, RADIUS, random);

            double west = 1000.0D - plan.x();
            assertTrue(west >= 0.55D * RADIUS - 1.0E-6D, "storm too close upwind: " + west);
            assertTrue(west <= 0.80D * RADIUS + 1.0E-6D, "storm too far upwind: " + west);
            assertTrue(Math.abs(plan.z() + 250.0D) <= 0.3D * RADIUS + 1.0E-6D, "lateral jitter too wide: " + plan.z());
            assertEquals(90.0F, plan.headingDegrees(), EPSILON, "the storm heads where the wind blows");
        }
    }

    @Test
    void anyWindDirectionPlacesTheStormInsideTheUpwindBand() {
        RandomSource random = RandomSource.create(7L);
        for (int step = 0; step < 72; step++) {
            float wind = step * 5.0F;
            double upwindX = Headings.directionX(wind + 180.0F);
            double upwindZ = Headings.directionZ(wind + 180.0F);
            double sideX = Headings.directionX(wind + 90.0F);
            double sideZ = Headings.directionZ(wind + 90.0F);

            for (int i = 0; i < 20; i++) {
                SpawnPlanner.Plan plan = SpawnPlanner.upwind(0.0D, 0.0D, wind, RADIUS, random);

                double along = plan.x() * upwindX + plan.z() * upwindZ;
                double across = plan.x() * sideX + plan.z() * sideZ;
                assertTrue(along >= 0.55D * RADIUS - 1.0E-6D, "wind " + wind + " placed the storm too close: " + along);
                assertTrue(along <= 0.80D * RADIUS + 1.0E-6D, "wind " + wind + " placed the storm too far: " + along);
                assertTrue(Math.abs(across) <= 0.3D * RADIUS + 1.0E-6D, "wind " + wind + " jittered too far: " + across);
                assertEquals(Headings.wrapDegrees(wind), plan.headingDegrees(), EPSILON);
            }
        }
    }

    @Test
    void upwindUsesTheWholeBandAndBothSides() {
        RandomSource random = RandomSource.create(2024L);
        double nearest = Double.POSITIVE_INFINITY;
        double farthest = 0.0D;
        double leftmost = 0.0D;
        double rightmost = 0.0D;
        for (int i = 0; i < 2000; i++) {
            SpawnPlanner.Plan plan = SpawnPlanner.upwind(0.0D, 0.0D, 0.0F, RADIUS, random);
            double south = plan.z();
            nearest = Math.min(nearest, south);
            farthest = Math.max(farthest, south);
            leftmost = Math.min(leftmost, plan.x());
            rightmost = Math.max(rightmost, plan.x());
        }
        assertTrue(nearest < 0.58D * RADIUS, "the near end of the band is used, nearest was " + nearest);
        assertTrue(farthest > 0.77D * RADIUS, "the far end of the band is used, farthest was " + farthest);
        assertTrue(leftmost < -0.25D * RADIUS, "storms jitter to the west of a north wind, leftmost was " + leftmost);
        assertTrue(rightmost > 0.25D * RADIUS, "storms jitter to the east of a north wind, rightmost was " + rightmost);
    }

    @Test
    void upwindWrapsTheHeadingAndSurvivesAZeroRadius() {
        SpawnPlanner.Plan wrapped = SpawnPlanner.upwind(0.0D, 0.0D, 450.0F, RADIUS, RandomSource.create(1L));
        assertEquals(90.0F, wrapped.headingDegrees(), EPSILON);

        SpawnPlanner.Plan pinned = SpawnPlanner.upwind(12.5D, -3.5D, 135.0F, 0.0F, RandomSource.create(1L));
        assertEquals(12.5D, pinned.x(), 1.0E-9D);
        assertEquals(-3.5D, pinned.z(), 1.0E-9D);

        SpawnPlanner.Plan negative = SpawnPlanner.upwind(12.5D, -3.5D, 135.0F, -100.0F, RandomSource.create(1L));
        assertEquals(12.5D, negative.x(), 1.0E-9D, "a negative radius counts as zero");
        assertEquals(-3.5D, negative.z(), 1.0E-9D);
    }

    @Test
    void upwindIsDeterministicForASeed() {
        SpawnPlanner.Plan first = SpawnPlanner.upwind(100.0D, 200.0D, 33.0F, RADIUS, RandomSource.create(555L));
        SpawnPlanner.Plan second = SpawnPlanner.upwind(100.0D, 200.0D, 33.0F, RADIUS, RandomSource.create(555L));
        assertEquals(first, second);
    }

    @Test
    void bearingFollowsTheCompass() {
        assertEquals(0.0F, SpawnPlanner.bearing(0.0D, 0.0D, 0.0D, -10.0D), EPSILON, "north is toward negative z");
        assertEquals(90.0F, SpawnPlanner.bearing(0.0D, 0.0D, 10.0D, 0.0D), EPSILON, "east is toward positive x");
        assertEquals(180.0F, SpawnPlanner.bearing(0.0D, 0.0D, 0.0D, 10.0D), EPSILON, "south is toward positive z");
        assertEquals(270.0F, SpawnPlanner.bearing(0.0D, 0.0D, -10.0D, 0.0D), EPSILON, "west is toward negative x");
        assertEquals(45.0F, SpawnPlanner.bearing(5.0D, 5.0D, 15.0D, -5.0D), EPSILON);
        assertEquals(0.0F, SpawnPlanner.bearing(3.0D, 3.0D, 3.0D, 3.0D), EPSILON, "no direction to the same point");
    }

    @Test
    void signedDifferenceTakesTheShortWayRound() {
        assertEquals(20.0F, SpawnPlanner.signedDifference(350.0F, 10.0F), EPSILON);
        assertEquals(-20.0F, SpawnPlanner.signedDifference(10.0F, 350.0F), EPSILON);
        assertEquals(0.0F, SpawnPlanner.signedDifference(90.0F, 450.0F), EPSILON);
        assertEquals(-180.0F, SpawnPlanner.signedDifference(0.0F, 180.0F), EPSILON, "opposite headings give -180, never 180");
        assertEquals(90.0F, SpawnPlanner.signedDifference(0.0F, 90.0F), EPSILON);
        assertEquals(-90.0F, SpawnPlanner.signedDifference(0.0F, 270.0F), EPSILON);
    }

    @Test
    void biasHeadingTurnsTowardTheTargetButNoFurtherThanTheLimit() {
        assertEquals(20.0F, SpawnPlanner.biasHeading(0.0F, 0.0D, 0.0D, 100.0D, 0.0D, 20.0F), EPSILON,
            "a target due east of a north-bound storm is capped at the limit");
        assertEquals(340.0F, SpawnPlanner.biasHeading(0.0F, 0.0D, 0.0D, -100.0D, 0.0D, 20.0F), EPSILON,
            "a target due west turns the other way and wraps");
        assertEquals(10.0F, SpawnPlanner.biasHeading(0.0F, 0.0D, 0.0D, 100.0D * Math.sin(Math.toRadians(10.0D)),
            -100.0D * Math.cos(Math.toRadians(10.0D)), 20.0F), 1.0E-3F,
            "a target inside the limit is reached exactly");
        assertEquals(0.0F, SpawnPlanner.biasHeading(0.0F, 0.0D, 0.0D, 0.0D, -100.0D, 20.0F), EPSILON,
            "a target straight ahead changes nothing");
    }

    @Test
    void biasHeadingHandlesTargetsBehindAndWrapAround() {
        assertEquals(20.0F, Math.abs(SpawnPlanner.signedDifference(0.0F,
            SpawnPlanner.biasHeading(0.0F, 0.0D, 0.0D, 0.0D, 100.0D, 20.0F))), EPSILON,
            "a target directly behind still turns by the full limit");
        assertEquals(10.0F, SpawnPlanner.biasHeading(350.0F, 0.0D, 0.0D, 100.0D * Math.sin(Math.toRadians(10.0D)),
            -100.0D * Math.cos(Math.toRadians(10.0D)), 20.0F), 1.0E-3F,
            "crossing north from 350 to 10 is a twenty degree turn, not three hundred and forty");
        assertEquals(25.0F, SpawnPlanner.biasHeading(365.0F, 0.0D, 0.0D, 100.0D, 0.0D, 20.0F), EPSILON,
            "the input heading is wrapped to 5 before the turn toward the east is capped");
    }

    @Test
    void biasHeadingLeavesTheHeadingAloneWithoutATargetOrALimit() {
        assertEquals(123.0F, SpawnPlanner.biasHeading(123.0F, 4.0D, 5.0D, 4.0D, 5.0D, 20.0F), EPSILON,
            "the storm sitting on the target has no bearing to turn toward");
        assertEquals(123.0F, SpawnPlanner.biasHeading(123.0F, 0.0D, 0.0D, 100.0D, 100.0D, 0.0F), EPSILON,
            "a zero limit allows no turn");
        assertEquals(20.0F, SpawnPlanner.biasHeading(0.0F, 0.0D, 0.0D, 100.0D, 0.0D, -20.0F), EPSILON,
            "a negative limit is read as its magnitude");
    }
}
