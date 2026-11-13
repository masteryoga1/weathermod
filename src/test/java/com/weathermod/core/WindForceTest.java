package com.weathermod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Pins the push of straight wind: the threshold, slope and cap of the acceleration, the envelope and the radial
 * profile of a downburst, how ambient wind and storm outflow add up as vectors, and where a downburst pushes
 * outward and where down. Needs no Minecraft bootstrap.
 */
class WindForceTest {
    private static final float EPSILON = 1.0E-6F;
    private static final double TOLERANCE = 1.0E-6D;
    private static final float RADIUS = 20.0F;
    private static final float SPEED = 20.0F;
    private static final double CENTRE_X = 100.0D;
    private static final double CENTRE_Z = 200.0D;

    /** The acceleration a downburst of {@link #RADIUS} and {@link #SPEED} at the centre adds to a body at rest. */
    private static Vector3d downburstAt(float envelope, double x, double z) {
        Vector3d out = new Vector3d();
        WindForce.addDownburst(CENTRE_X, CENTRE_Z, RADIUS, SPEED, envelope, x, z, out);
        return out;
    }

    @Test
    void constantsAreTheSpecifiedOnes() {
        assertEquals(10.0F, WindForce.PUSH_THRESHOLD_BLOCKS_PER_SECOND);
        assertEquals(0.004F, WindForce.PUSH_ACCEL_PER_BLOCK_PER_SECOND);
        assertEquals(0.06F, WindForce.MAX_PUSH_ACCEL);
        assertEquals(0.3F, WindForce.DOWNBURST_CORE_FRACTION);
        assertEquals(0.04F, WindForce.DOWNBURST_DOWN_ACCEL);
        assertEquals(1.5F, WindForce.DOWNBURST_REACH_FACTOR);
        assertEquals(20, WindForce.DOWNBURST_RAMP_TICKS);
        assertEquals(40, WindForce.DOWNBURST_DECAY_TICKS);
    }

    @Test
    void windUpToTheThresholdPushesNothing() {
        assertEquals(0.0F, WindForce.pushAcceleration(0.0F));
        assertEquals(0.0F, WindForce.pushAcceleration(9.99F));
        assertEquals(0.0F, WindForce.pushAcceleration(10.0F), "the threshold itself does not push yet");
        assertEquals(0.0F, WindForce.pushAcceleration(-30.0F));
        assertEquals(0.0F, WindForce.pushAcceleration(Float.NaN), "a speed that is not a number pushes nothing");
    }

    @Test
    void accelerationRisesLinearlyAboveTheThreshold() {
        assertEquals(0.004F, WindForce.pushAcceleration(11.0F), EPSILON);
        assertEquals(0.02F, WindForce.pushAcceleration(15.0F), EPSILON);
        assertEquals(0.04F, WindForce.pushAcceleration(20.0F), EPSILON);
        assertEquals(0.056F, WindForce.pushAcceleration(24.0F), EPSILON);
    }

    @Test
    void accelerationIsCapped() {
        assertEquals(0.06F, WindForce.pushAcceleration(25.0F), EPSILON, "the cap is reached at 25 blocks per second");
        assertEquals(0.06F, WindForce.pushAcceleration(30.0F));
        assertEquals(0.06F, WindForce.pushAcceleration(1000.0F));
        assertEquals(0.06F, WindForce.pushAcceleration(Float.POSITIVE_INFINITY));
    }

    @Test
    void envelopeRisesHoldsAndFalls() {
        int duration = 100;
        assertEquals(0.0F, WindForce.downburstEnvelope(0, duration), EPSILON, "a downburst starts from nothing");
        assertEquals(0.25F, WindForce.downburstEnvelope(5, duration), EPSILON);
        assertEquals(0.5F, WindForce.downburstEnvelope(10, duration), EPSILON);
        assertEquals(1.0F, WindForce.downburstEnvelope(20, duration), EPSILON, "full after the ramp");
        assertEquals(1.0F, WindForce.downburstEnvelope(40, duration), EPSILON);
        assertEquals(1.0F, WindForce.downburstEnvelope(60, duration), EPSILON, "still full where the decay begins");
        assertEquals(0.5F, WindForce.downburstEnvelope(80, duration), EPSILON);
        assertEquals(0.25F, WindForce.downburstEnvelope(90, duration), EPSILON);
        assertEquals(0.025F, WindForce.downburstEnvelope(99, duration), EPSILON);
    }

    @Test
    void envelopeIsZeroOutsideTheDownburst() {
        assertEquals(0.0F, WindForce.downburstEnvelope(-1, 100));
        assertEquals(0.0F, WindForce.downburstEnvelope(100, 100), "over from its last tick on");
        assertEquals(0.0F, WindForce.downburstEnvelope(5000, 100));
        assertEquals(0.0F, WindForce.downburstEnvelope(0, 0));
        assertEquals(0.0F, WindForce.downburstEnvelope(10, -5));
    }

    @Test
    void envelopeStaysBetweenZeroAndOne() {
        for (int age = -10; age <= 110; age++) {
            float envelope = WindForce.downburstEnvelope(age, 100);
            assertTrue(envelope >= 0.0F && envelope <= 1.0F, "envelope out of range at age " + age);
        }
    }

    @Test
    void shortDownburstNeverReachesFullStrength() {
        int duration = 30;
        float strongest = 0.0F;
        for (int age = 0; age < duration; age++) {
            strongest = Math.max(strongest, WindForce.downburstEnvelope(age, duration));
        }
        assertEquals(0.5F, strongest, EPSILON, "rise and fall meet at age 10");
        assertEquals(0.5F, WindForce.downburstEnvelope(10, duration), EPSILON);
    }

    @Test
    void outflowProfileRisesToHalfTheRadiusAndFadesBeyondIt() {
        assertEquals(0.0F, WindForce.downburstSpeedAt(0.0D, RADIUS, SPEED, 1.0F), "calm at the centre");
        assertEquals(10.0F, WindForce.downburstSpeedAt(5.0D, RADIUS, SPEED, 1.0F), EPSILON);
        assertEquals(20.0F, WindForce.downburstSpeedAt(10.0D, RADIUS, SPEED, 1.0F), EPSILON, "full at half the radius");
        assertEquals(20.0F, WindForce.downburstSpeedAt(15.0D, RADIUS, SPEED, 1.0F), EPSILON);
        assertEquals(20.0F, WindForce.downburstSpeedAt(20.0D, RADIUS, SPEED, 1.0F), EPSILON, "full at the radius");
        assertEquals(10.0F, WindForce.downburstSpeedAt(25.0D, RADIUS, SPEED, 1.0F), EPSILON);
        assertEquals(0.0F, WindForce.downburstSpeedAt(30.0D, RADIUS, SPEED, 1.0F), "nothing at one and a half radii");
        assertEquals(0.0F, WindForce.downburstSpeedAt(45.0D, RADIUS, SPEED, 1.0F));
    }

    @Test
    void outflowScalesWithSpeedAndEnvelope() {
        assertEquals(10.0F, WindForce.downburstSpeedAt(20.0D, RADIUS, SPEED, 0.5F), EPSILON);
        assertEquals(28.0F, WindForce.downburstSpeedAt(16.0D, 32.0F, 28.0F, 1.0F), EPSILON);
        assertEquals(0.0F, WindForce.downburstSpeedAt(20.0D, RADIUS, SPEED, 0.0F));
    }

    @Test
    void outflowOfADegenerateDownburstIsZero() {
        assertEquals(0.0F, WindForce.downburstSpeedAt(5.0D, 0.0F, SPEED, 1.0F));
        assertEquals(0.0F, WindForce.downburstSpeedAt(5.0D, -4.0F, SPEED, 1.0F));
        assertEquals(0.0F, WindForce.downburstSpeedAt(Double.NaN, RADIUS, SPEED, 1.0F));
        assertEquals(0.0F, WindForce.downburstSpeedAt(-5.0D, RADIUS, SPEED, 1.0F));
    }

    @Test
    void windIsAddedAlongItsCompassDirection() {
        Vector3d wind = new Vector3d(0.0D, 7.0D, 0.0D);
        WindForce.addWind(90.0F, 12.0F, wind);
        assertEquals(12.0D, wind.x, TOLERANCE, "90 degrees blows toward the east");
        assertEquals(0.0D, wind.z, TOLERANCE);
        WindForce.addWind(0.0F, 5.0F, wind);
        assertEquals(12.0D, wind.x, TOLERANCE);
        assertEquals(-5.0D, wind.z, TOLERANCE, "0 degrees blows toward the north");
        assertEquals(7.0D, wind.y, "the height component is left alone");
    }

    @Test
    void outflowIsAddedAlongTheHeadingScaledByCoverage() {
        Vector3d wind = new Vector3d(0.0D, 7.0D, 0.0D);
        WindForce.addOutflow(180.0F, 20.0F, 0.5F, wind);
        assertEquals(0.0D, wind.x, TOLERANCE);
        assertEquals(10.0D, wind.z, TOLERANCE, "a storm heading south blows south at half coverage with half speed");
        assertEquals(7.0D, wind.y, "the height component is left alone");
        WindForce.addOutflow(270.0F, 14.0F, 1.0F, wind);
        assertEquals(-14.0D, wind.x, TOLERANCE);
        assertEquals(10.0D, wind.z, TOLERANCE);
        WindForce.addOutflow(90.0F, 22.0F, 0.0F, wind);
        assertEquals(-14.0D, wind.x, TOLERANCE, "a storm that does not cover the point adds nothing");
    }

    @Test
    void windAndOutflowAddAsVectors() {
        Vector3d wind = new Vector3d();
        WindForce.addWind(90.0F, 12.0F, wind);
        WindForce.addOutflow(0.0F, 12.0F, 1.0F, wind);
        assertEquals(12.0D, wind.x, TOLERANCE);
        assertEquals(-12.0D, wind.z, TOLERANCE);

        Vector3d push = new Vector3d();
        WindForce.toAcceleration(wind, push);
        double speed = Math.sqrt(288.0D);
        double expected = (speed - 10.0D) * 0.004D;
        assertEquals(expected * Math.sqrt(0.5D), push.x, TOLERANCE, "the push points north-east");
        assertEquals(-expected * Math.sqrt(0.5D), push.z, TOLERANCE);
        assertEquals(0.0D, push.y);
        assertEquals(WindForce.pushAcceleration((float) speed), push.length(), TOLERANCE);
    }

    @Test
    void opposingWindAndOutflowCancel() {
        Vector3d wind = new Vector3d();
        WindForce.addWind(90.0F, 12.0F, wind);
        WindForce.addOutflow(270.0F, 12.0F, 1.0F, wind);
        Vector3d push = new Vector3d(9.0D, 9.0D, 9.0D);
        WindForce.toAcceleration(wind, push);
        assertEquals(new Vector3d(), push, "two gales against each other push nothing");
    }

    @Test
    void accelerationFollowsTheHorizontalWindOnly() {
        Vector3d push = new Vector3d(9.0D, 9.0D, 9.0D);
        WindForce.toAcceleration(new Vector3d(9.0D, 50.0D, 12.0D), push);
        assertEquals(0.012D, push.x, TOLERANCE, "15 blocks per second push with 0.02 along (0.6, 0.8)");
        assertEquals(0.0D, push.y, "the height component of the wind is ignored");
        assertEquals(0.016D, push.z, TOLERANCE);

        WindForce.toAcceleration(new Vector3d(6.0D, 50.0D, 8.0D), push);
        assertEquals(new Vector3d(), push, "10 blocks per second are not above the threshold");

        WindForce.toAcceleration(new Vector3d(), push);
        assertEquals(new Vector3d(), push, "calm air has no direction and pushes nothing");
    }

    @Test
    void accelerationMayBeWrittenOverTheWind() {
        Vector3d wind = new Vector3d(-18.0D, 3.0D, 24.0D);
        WindForce.toAcceleration(wind, wind);
        assertEquals(-0.036D, wind.x, TOLERANCE, "30 blocks per second push with the cap along (-0.6, 0.8)");
        assertEquals(0.0D, wind.y);
        assertEquals(0.048D, wind.z, TOLERANCE);
    }

    @Test
    void downburstPushesOutwardFromItsCentre() {
        Vector3d east = downburstAt(1.0F, CENTRE_X + 20.0D, CENTRE_Z);
        assertEquals(0.04D, east.x, TOLERANCE, "20 blocks per second at the radius");
        assertEquals(0.0D, east.y, "no downdraught outside the core");
        assertEquals(0.0D, east.z, TOLERANCE);

        Vector3d north = downburstAt(1.0F, CENTRE_X, CENTRE_Z - 15.0D);
        assertEquals(0.0D, north.x, TOLERANCE);
        assertEquals(-0.04D, north.z, TOLERANCE);

        Vector3d southWest = downburstAt(1.0F, CENTRE_X - 12.0D, CENTRE_Z + 16.0D);
        assertEquals(-0.024D, southWest.x, TOLERANCE, "along (-0.6, 0.8) at the radius");
        assertEquals(0.032D, southWest.z, TOLERANCE);
    }

    @Test
    void downburstFadesBeyondItsRadiusAndEndsAtItsReach() {
        Vector3d fading = downburstAt(1.0F, CENTRE_X + 25.0D, CENTRE_Z);
        assertEquals(0.0D, fading.x, TOLERANCE, "10 blocks per second are not above the threshold");
        Vector3d strongFading = new Vector3d();
        WindForce.addDownburst(CENTRE_X, CENTRE_Z, RADIUS, 28.0F, 1.0F, CENTRE_X + 25.0D, CENTRE_Z, strongFading);
        assertEquals(0.016D, strongFading.x, TOLERANCE, "14 blocks per second half way between radius and reach");
        assertEquals(new Vector3d(), downburstAt(1.0F, CENTRE_X + 30.0D, CENTRE_Z));
        assertEquals(new Vector3d(), downburstAt(1.0F, CENTRE_X - 200.0D, CENTRE_Z + 200.0D));
    }

    @Test
    void downburstPressesDownInItsCore() {
        Vector3d edgeOfCore = downburstAt(1.0F, CENTRE_X + 6.0D, CENTRE_Z);
        assertEquals(0.008D, edgeOfCore.x, TOLERANCE, "12 blocks per second at 0.3 radii");
        assertEquals(-0.04D, edgeOfCore.y, TOLERANCE, "the core reaches to 0.3 radii");
        assertEquals(0.0D, edgeOfCore.z, TOLERANCE);

        Vector3d centre = downburstAt(1.0F, CENTRE_X, CENTRE_Z);
        assertEquals(0.0D, centre.x, "nothing has a direction at the centre");
        assertEquals(-0.04D, centre.y, TOLERANCE);
        assertEquals(0.0D, centre.z);

        Vector3d justOutside = downburstAt(1.0F, CENTRE_X + 6.5D, CENTRE_Z);
        assertEquals(0.0D, justOutside.y, "no downdraught beyond the core");
        assertTrue(justOutside.x > 0.0D);
    }

    @Test
    void downburstScalesWithItsEnvelope() {
        Vector3d half = downburstAt(0.5F, CENTRE_X + 3.0D, CENTRE_Z);
        assertEquals(-0.02D, half.y, TOLERANCE, "half the downdraught at half strength");
        assertEquals(0.0D, half.x, "3 blocks per second of outflow are below the threshold");

        Vector3d halfAtTheRadius = new Vector3d();
        WindForce.addDownburst(CENTRE_X, CENTRE_Z, RADIUS, 30.0F, 0.5F, CENTRE_X + 20.0D, CENTRE_Z, halfAtTheRadius);
        assertEquals(0.02D, halfAtTheRadius.x, TOLERANCE, "15 blocks per second at half strength");

        assertEquals(new Vector3d(), downburstAt(0.0F, CENTRE_X + 3.0D, CENTRE_Z), "a downburst that is over");
        assertEquals(new Vector3d(), downburstAt(Float.NaN, CENTRE_X + 3.0D, CENTRE_Z));
    }

    @Test
    void downburstAddsToWhatIsAlreadyThere() {
        Vector3d out = new Vector3d(1.0D, 2.0D, 3.0D);
        WindForce.addDownburst(CENTRE_X, CENTRE_Z, RADIUS, SPEED, 1.0F, CENTRE_X + 20.0D, CENTRE_Z, out);
        WindForce.addDownburst(CENTRE_X + 20.0D, CENTRE_Z, RADIUS, SPEED, 1.0F, CENTRE_X + 20.0D, CENTRE_Z, out);
        assertEquals(1.04D, out.x, TOLERANCE, "pushed east by the first, not sideways by the second");
        assertEquals(1.96D, out.y, TOLERANCE, "pressed down in the core of the second");
        assertEquals(3.0D, out.z, TOLERANCE);
    }

    @Test
    void downburstWithoutARadiusDoesNothing() {
        Vector3d out = new Vector3d();
        WindForce.addDownburst(CENTRE_X, CENTRE_Z, 0.0F, SPEED, 1.0F, CENTRE_X, CENTRE_Z, out);
        WindForce.addDownburst(CENTRE_X, CENTRE_Z, -8.0F, SPEED, 1.0F, CENTRE_X + 1.0D, CENTRE_Z, out);
        assertEquals(new Vector3d(), out);
    }
}
