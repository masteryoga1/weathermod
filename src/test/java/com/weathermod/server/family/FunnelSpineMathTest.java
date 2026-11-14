package com.weathermod.server.family;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.FunnelSpine;
import com.weathermod.core.VortexKind;
import com.weathermod.core.VortexParams;
import com.weathermod.core.WeatherType;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Pins the shape of a funnel's spine: its point count and heights, how far the base wanders, how far the top leans
 * ahead, the sway between them, the radii, and that the descent is carried along without moving a point. Needs no
 * Minecraft bootstrap.
 */
class FunnelSpineMathTest {
    private static final double GROUND_Y = 64.0D;
    private static final double TOP_Y = 160.0D;
    private static final double TOLERANCE = 1.0E-4D;

    private static final VortexParams EF0 = vortex(VortexKind.TORNADO, 0, 3.0F, 10.0F, 24.0F);
    private static final VortexParams EF3 = vortex(VortexKind.TORNADO, 3, 12.0F, 30.0F, 64.0F);
    private static final VortexParams DUST_DEVIL = vortex(VortexKind.DUST_DEVIL, 0, 1.8F, 3.0F, 8.0F);
    private static final VortexParams THIN_WHIRL = vortex(VortexKind.GUSTNADO, 0, 0.6F, 2.0F, 6.0F);

    private static VortexParams vortex(VortexKind kind, int tier, float coreRadius, float topRadius,
                                       float influenceRadius) {
        return new VortexParams(kind, tier, coreRadius, topRadius, influenceRadius, WeatherType.Range.of(0.0F), 8.0F,
            0.034F, 0.05F, 0.15F, 4.0F, 0.0F, 0.0F, 6.0F, 0.0F, WeatherType.UNLIMITED_SPEED);
    }

    /** A grounded spine from {@link #GROUND_Y} to {@link #TOP_Y} at tick 0 with seed 0. */
    private static FunnelSpine spine(VortexParams params, float strength, float headingDegrees, float speed,
                                     float wobblePhase) {
        return FunnelSpineMath.build(params, strength, 1.0F, GROUND_Y, TOP_Y, headingDegrees, speed, wobblePhase, 0,
            0);
    }

    private static double offset(Vector3f point) {
        return Math.hypot(point.x(), point.z());
    }

    @Test
    void theConstantsAreTheAgreedOnes() {
        assertEquals(2.0F, FunnelSpineMath.WOBBLE_MIN_RATED_BLOCKS);
        assertEquals(0.5F, FunnelSpineMath.WOBBLE_MIN_SMALL_BLOCKS);
        assertEquals(0.5F, FunnelSpineMath.WOBBLE_CORE_FRACTION);
        assertEquals(3.0F, FunnelSpineMath.LEAN_SECONDS);
        assertEquals(0.3F, FunnelSpineMath.LEAN_MAX_FRACTION);
        assertEquals(0.15F, FunnelSpineMath.SWAY_FRACTION);
        assertEquals(137, FunnelSpineMath.SWAY_PERIOD_A_TICKS);
        assertEquals(211, FunnelSpineMath.SWAY_PERIOD_B_TICKS);
    }

    @Test
    void aSpineHasTwentyFourPointsRisingFromTheGroundToTheTop() {
        FunnelSpine spine = FunnelSpineMath.build(EF3, 0.8F, 1.0F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 1234, 7);
        List<Vector3f> points = spine.points();

        assertEquals(24, FunnelSpine.POINTS);
        assertEquals(FunnelSpine.POINTS, points.size());
        assertEquals(GROUND_Y, points.get(0).y(), "the first point is the ground point");
        assertEquals(TOP_Y, points.get(points.size() - 1).y(), "the last point is the top");
        assertEquals(GROUND_Y, spine.groundY());
        assertEquals(TOP_Y, spine.topY());
        for (int i = 1; i < points.size(); i++) {
            assertTrue(points.get(i).y() > points.get(i - 1).y(), "point " + i + " lies above the one before");
            assertEquals(GROUND_Y + (TOP_Y - GROUND_Y) * i / 23.0D, points.get(i).y(), TOLERANCE,
                "the points are spread evenly over the height");
        }
    }

    @Test
    void theBaseWandersByTheWobbleAmplitude() {
        assertEquals(6.0D, offset(spine(EF3, 1.0F, 0.0F, 0.0F, 0.7F).points().get(0)), TOLERANCE,
            "half the base radius of a full EF3");
        assertEquals(3.9D, offset(spine(EF3, 0.3F, 0.0F, 0.0F, 0.7F).points().get(0)), TOLERANCE,
            "half the base radius of a weak EF3: 12 * 0.65 / 2");
        assertEquals(2.0D, offset(spine(EF0, 1.0F, 0.0F, 0.0F, 0.7F).points().get(0)), TOLERANCE,
            "a rated tornado wanders at least two blocks");
        assertEquals(2.0D, offset(spine(EF0, 0.0F, 0.0F, 0.0F, 0.7F).points().get(0)), TOLERANCE);
        assertEquals(0.9D, offset(spine(DUST_DEVIL, 1.0F, 0.0F, 0.0F, 0.7F).points().get(0)), TOLERANCE,
            "half the base radius of a dust devil");
        assertEquals(0.5D, offset(spine(THIN_WHIRL, 1.0F, 0.0F, 0.0F, 0.7F).points().get(0)), TOLERANCE,
            "a small vortex wanders at least half a block");
    }

    @Test
    void theBaseStaysWithinTheWobbleAmplitudeForEveryPhaseSpeedAndTick() {
        for (int step = 0; step < 64; step++) {
            float phase = (float) (step * Math.PI / 32.0D);
            FunnelSpine spine = FunnelSpineMath.build(EF3, 1.0F, 1.0F, GROUND_Y, TOP_Y, step * 11.0F, step * 0.5F,
                phase, step * 37, step);

            assertTrue(offset(spine.points().get(0)) <= 6.0D + TOLERANCE,
                "the base is " + offset(spine.points().get(0)) + " from the centre at phase " + phase);
        }
    }

    @Test
    void theWobblePhaseIsTheAngleOfTheBase() {
        Vector3f east = spine(EF3, 1.0F, 0.0F, 0.0F, 0.0F).points().get(0);
        assertEquals(6.0D, east.x(), TOLERANCE);
        assertEquals(0.0D, east.z(), TOLERANCE);

        Vector3f south = spine(EF3, 1.0F, 0.0F, 0.0F, (float) (Math.PI / 2.0D)).points().get(0);
        assertEquals(0.0D, south.x(), TOLERANCE);
        assertEquals(6.0D, south.z(), TOLERANCE);

        Vector3f west = spine(EF3, 1.0F, 0.0F, 0.0F, (float) Math.PI).points().get(0);
        assertEquals(-6.0D, west.x(), TOLERANCE);
        assertEquals(0.0D, west.z(), TOLERANCE);
    }

    @Test
    void theTopLeansAheadByThreeSecondsOfTravel() {
        Vector3f eastbound = spine(EF3, 1.0F, 90.0F, 2.0F, 0.0F).points().get(23);
        assertEquals(6.0D, eastbound.x(), TOLERANCE, "two blocks a second for three seconds, to the east");
        assertEquals(0.0D, eastbound.z(), TOLERANCE);

        Vector3f northbound = spine(EF3, 1.0F, 0.0F, 1.0F, 2.0F).points().get(23);
        assertEquals(0.0D, northbound.x(), TOLERANCE);
        assertEquals(-3.0D, northbound.z(), TOLERANCE, "north is toward negative z");

        Vector3f parked = spine(EF3, 1.0F, 90.0F, 0.0F, 0.0F).points().get(23);
        assertEquals(0.0D, offset(parked), TOLERANCE, "a parked funnel stands straight under its top");

        Vector3f backwards = spine(EF3, 1.0F, 90.0F, -4.0F, 0.0F).points().get(23);
        assertEquals(0.0D, offset(backwards), TOLERANCE, "a negative speed is no speed");
    }

    @Test
    void theLeanIsCappedAtThreeTenthsOfTheTopRadius() {
        assertEquals(9.0D, offset(spine(EF3, 1.0F, 200.0F, 10.0F, 0.0F).points().get(23)), TOLERANCE,
            "30 blocks of travel, capped at 0.3 * 30");
        assertEquals(9.0D, offset(spine(EF3, 1.0F, 200.0F, 3.0F, 0.0F).points().get(23)), TOLERANCE,
            "exactly at the cap");
        assertEquals(5.85D, offset(spine(EF3, 0.3F, 200.0F, 10.0F, 0.0F).points().get(23)), TOLERANCE,
            "a weak funnel is narrower and leans less: 0.3 * 30 * 0.65");
        assertEquals(0.9D, offset(spine(DUST_DEVIL, 1.0F, 200.0F, 10.0F, 0.0F).points().get(23)), TOLERANCE);
    }

    @Test
    void theTopStaysWithinLeanPlusSwayAndEveryPointWithinAllThree() {
        double wobble = 6.0D;
        double lean = 9.0D;
        double sway = 0.15D * 30.0D * Math.sqrt(2.0D);
        for (int step = 0; step < 64; step++) {
            FunnelSpine spine = FunnelSpineMath.build(EF3, 1.0F, 1.0F, GROUND_Y, TOP_Y, step * 23.0F, step * 0.4F,
                step * 0.3F, step * 53, step * 3);
            List<Vector3f> points = spine.points();

            assertTrue(offset(points.get(23)) <= lean + sway + TOLERANCE, "the top at step " + step);
            for (int i = 0; i < points.size(); i++) {
                assertTrue(offset(points.get(i)) <= wobble + lean + sway + TOLERANCE,
                    "point " + i + " at step " + step + " is " + offset(points.get(i)) + " from the centre");
            }
        }
    }

    @Test
    void thePointsFollowTheDocumentedFormula() {
        float strength = 0.8F;
        float heading = 130.0F;
        float speed = 1.5F;
        float phase = 2.2F;
        int tick = 4321;
        int seed = 17;
        FunnelSpine spine = FunnelSpineMath.build(EF3, strength, 1.0F, GROUND_Y, TOP_Y, heading, speed, phase, tick,
            seed);

        double scale = 0.5D + 0.5D * 0.8D;
        double baseRadius = 12.0D * scale;
        double topRadius = 30.0D * scale;
        double wobble = Math.max(2.0D, 0.5D * baseRadius);
        double lean = Math.min(0.3D * topRadius, speed * 3.0D);
        double sway = 0.15D * topRadius;
        double leanX = Math.sin(Math.toRadians(heading)) * lean;
        double leanZ = -Math.cos(Math.toRadians(heading)) * lean;
        double swayX = sway * Math.sin(2.0D * Math.PI * tick / 137.0D + seed);
        double swayZ = sway * Math.cos(2.0D * Math.PI * tick / 211.0D + seed);
        for (int i = 0; i < FunnelSpine.POINTS; i++) {
            double t = i / 23.0D;
            double base = (1.0D - t) * (1.0D - t);
            double top = Math.pow(t, 1.5D);
            double middle = Math.sin(Math.PI * t);
            Vector3f point = spine.points().get(i);

            assertEquals(wobble * Math.cos(phase) * base + leanX * top + swayX * middle, point.x(), TOLERANCE,
                "x of point " + i);
            assertEquals(wobble * Math.sin(phase) * base + leanZ * top + swayZ * middle, point.z(), TOLERANCE,
                "z of point " + i);
        }
    }

    @Test
    void theMiddleSwaysWithTheTickAndTheSeedButTheEndsDoNot() {
        FunnelSpine first = FunnelSpineMath.build(EF3, 1.0F, 1.0F, GROUND_Y, TOP_Y, 90.0F, 2.0F, 0.5F, 0, 0);
        FunnelSpine later = FunnelSpineMath.build(EF3, 1.0F, 1.0F, GROUND_Y, TOP_Y, 90.0F, 2.0F, 0.5F, 40, 0);
        FunnelSpine other = FunnelSpineMath.build(EF3, 1.0F, 1.0F, GROUND_Y, TOP_Y, 90.0F, 2.0F, 0.5F, 0, 1);

        Vector3f middle = first.points().get(12);
        assertTrue(middle.distance(later.points().get(12)) > 0.5F, "the middle moves with the tick");
        assertTrue(middle.distance(other.points().get(12)) > 0.5F, "two storms do not sway alike");

        for (FunnelSpine changed : List.of(later, other)) {
            assertEquals(0.0F, first.points().get(0).distance(changed.points().get(0)), 1.0E-4F,
                "the base does not sway");
            assertEquals(0.0F, first.points().get(23).distance(changed.points().get(23)), 1.0E-4F,
                "the top does not sway");
        }
    }

    @Test
    void theSwayIsLargestInTheMiddle() {
        FunnelSpine spine = FunnelSpineMath.build(EF0, 1.0F, 1.0F, GROUND_Y, TOP_Y, 0.0F, 0.0F, 0.0F, 0, 0);
        // At tick 0 with seed 0 the sway points along +z only: 0.15 * 10 * sin(pi * t).
        double sway = 0.15D * 10.0D;

        assertEquals(0.0D, spine.points().get(0).z(), TOLERANCE);
        assertEquals(sway * Math.sin(Math.PI * 6.0D / 23.0D), spine.points().get(6).z(), TOLERANCE);
        assertEquals(sway * Math.sin(Math.PI * 12.0D / 23.0D), spine.points().get(12).z(), TOLERANCE);
        assertEquals(0.0D, spine.points().get(23).z(), TOLERANCE);
        assertTrue(spine.points().get(12).z() > spine.points().get(6).z());
        assertTrue(spine.points().get(12).z() > spine.points().get(20).z());
    }

    @Test
    void theRadiiScaleWithTheStrength() {
        FunnelSpine full = spine(EF3, 1.0F, 0.0F, 0.0F, 0.0F);
        assertEquals(12.0F, full.baseRadius());
        assertEquals(30.0F, full.topRadius());
        assertEquals(1.0F, full.strength());

        FunnelSpine half = spine(EF3, 0.5F, 0.0F, 0.0F, 0.0F);
        assertEquals(9.0F, half.baseRadius());
        assertEquals(22.5F, half.topRadius());
        assertEquals(0.5F, half.strength());

        FunnelSpine none = spine(EF3, 0.0F, 0.0F, 0.0F, 0.0F);
        assertEquals(6.0F, none.baseRadius(), "half the size is kept at zero strength");
        assertEquals(15.0F, none.topRadius());

        FunnelSpine weak = spine(EF3, 0.3F, 0.0F, 0.0F, 0.0F);
        assertEquals(7.8F, weak.baseRadius(), 1.0E-5F);
        assertEquals(19.5F, weak.topRadius(), 1.0E-5F);
    }

    @Test
    void descentAndGroundedAreCarriedAsGiven() {
        FunnelSpine aloft = FunnelSpineMath.build(EF3, 0.8F, 0.4F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 100, 7);
        assertEquals(0.4F, aloft.descent());
        assertFalse(aloft.grounded());

        FunnelSpine almost = FunnelSpineMath.build(EF3, 0.8F, 0.999F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 100, 7);
        assertEquals(0.999F, almost.descent());
        assertFalse(almost.grounded());

        FunnelSpine down = FunnelSpineMath.build(EF3, 0.8F, 1.0F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 100, 7);
        assertEquals(1.0F, down.descent());
        assertTrue(down.grounded());

        FunnelSpine stub = FunnelSpineMath.build(EF3, 0.8F, 0.0F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 100, 7);
        assertEquals(0.0F, stub.descent());
        assertFalse(stub.grounded());
    }

    @Test
    void thePointsDoNotMoveWithTheDescent() {
        FunnelSpine stub = FunnelSpineMath.build(EF3, 0.8F, 0.0F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 100, 7);
        FunnelSpine aloft = FunnelSpineMath.build(EF3, 0.8F, 0.4F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 100, 7);
        FunnelSpine down = FunnelSpineMath.build(EF3, 0.8F, 1.0F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 100, 7);

        assertEquals(down.points(), aloft.points());
        assertEquals(down.points(), stub.points());
        assertEquals(GROUND_Y, stub.groundY(), "a funnel aloft still knows the ground it will reach");
        assertEquals(down.baseRadius(), stub.baseRadius());
        assertEquals(down.topRadius(), stub.topRadius());
    }

    @Test
    void buildingIsDeterministic() {
        FunnelSpine first = FunnelSpineMath.build(EF3, 0.8F, 1.0F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 100, 7);
        FunnelSpine second = FunnelSpineMath.build(EF3, 0.8F, 1.0F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 100, 7);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, FunnelSpineMath.build(EF3, 0.8F, 1.0F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.0F, 101, 7));
        assertNotEquals(first, FunnelSpineMath.build(EF3, 0.8F, 1.0F, GROUND_Y, TOP_Y, 40.0F, 3.0F, 1.5F, 100, 7));
    }

    @Test
    void aFlatFunnelStillHasItsPoints() {
        FunnelSpine flat = FunnelSpineMath.build(DUST_DEVIL, 1.0F, 1.0F, 70.0D, 70.0D, 0.0F, 0.0F, 0.0F, 0, 0);

        assertEquals(FunnelSpine.POINTS, flat.points().size());
        for (Vector3f point : flat.points()) {
            assertEquals(70.0F, point.y());
            assertTrue(point.isFinite());
        }
    }
}
