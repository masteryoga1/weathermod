package com.weathermod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the pause that keeps a lifted body from being kicked for floating: the server's limit by gravity, the
 * tick on which lift is withheld and what gives it back, for a steered vehicle and for a player on foot, and, by
 * simulating long rides in the strongest tornado, that the streak of floating ticks the server counts stays below
 * its limit. The lift and the rise cap of an EF5 are repeated here as constants. Needs no Minecraft bootstrap.
 */
class LiftPulseTest {
    /** Gravity of a player or a mount and of a boat, in blocks per tick squared. */
    private static final double PLAYER_GRAVITY = 0.08D;
    private static final double BOAT_GRAVITY = 0.04D;
    /** A living entity keeps this share of its vertical speed per tick; a boat keeps all of it. */
    private static final double PLAYER_VERTICAL_DRAG = 0.98D;
    private static final double NO_DRAG = 1.0D;
    /** Lift and rise cap of an EF5 tornado at full strength. */
    private static final double EF5_LIFT = 0.22D;
    private static final double EF5_RISE_CAP = 0.5D;
    /** The server counts a vehicle's move packet as floating unless it descends by more than this. */
    private static final double SERVER_DESCENT_PER_TICK = 0.03125D;
    private static final int RIDE_TICKS = 2000;

    /** What a simulated ride showed. */
    private record Ride(int longestServerStreak, int refusedTicks, double highest, int landings) {
    }

    /**
     * Rides the core of an EF5 for {@link #RIDE_TICKS} ticks from the ground: every tick the pulse is asked, lift
     * is added when it agrees and the body rises slower than the rise cap, the body moves, and gravity and drag
     * act as vanilla applies them.
     *
     * <p>Counts the consecutive ticks the server would take for floating. A steered vehicle floats in the air
     * unless its packet descended by more than {@link #SERVER_DESCENT_PER_TICK}; a player on foot floats on every
     * packet in the air. The server looks at the last packet once per tick of its own: with
     * {@code packetsPerLook} above 1 the packets reach it in groups of that size, so it sees the last packet of
     * each group only and counts what that one said until the next group.
     *
     * @param onFoot         whether the body is a player on foot, with the rule the server and the pulse have for
     *                       one, or a steered vehicle
     * @param packetsPerLook how many packets reach the server between two of its looks
     * @param phase          which packet of a group is the one the server sees, from 0
     */
    private static Ride ride(double gravity, double verticalDrag, boolean onFoot, int packetsPerLook, int phase) {
        LiftPulse pulse = new LiftPulse();
        double y = 0.0D;
        double velocity = 0.0D;
        double lastMove = 0.0D;
        boolean onGround = true;
        boolean serverSawFloating = false;
        int serverStreak = 0;
        int longest = 0;
        int refused = 0;
        int landings = 0;
        double highest = 0.0D;
        for (int tick = 0; tick < RIDE_TICKS; tick++) {
            boolean allowed = onFoot
                ? pulse.allowLiftOnFoot(onGround, gravity)
                : pulse.allowLift(onGround, lastMove, gravity);
            if (!allowed) {
                refused++;
            } else if (velocity < EF5_RISE_CAP) {
                velocity += EF5_LIFT;
            }
            double next = y + velocity;
            boolean wasOnGround = onGround;
            onGround = next <= 0.0D;
            if (onGround) {
                next = 0.0D;
                velocity = 0.0D;
                if (!wasOnGround) {
                    landings++;
                }
            }
            lastMove = next - y;
            y = next;
            velocity = (velocity - gravity) * verticalDrag;
            boolean floating = !onGround && (onFoot || lastMove >= -SERVER_DESCENT_PER_TICK);
            if (tick % packetsPerLook == phase) {
                serverSawFloating = floating;
            }
            if (serverSawFloating) {
                serverStreak++;
            } else {
                serverStreak = 0;
            }
            longest = Math.max(longest, serverStreak);
            highest = Math.max(highest, y);
        }
        return new Ride(longest, refused, highest, landings);
    }

    /** A ride of a steered vehicle of which the server sees every packet. */
    private static Ride vehicleRide(double gravity, double verticalDrag) {
        return ride(gravity, verticalDrag, false, 1, 0);
    }

    /** Feeds the pulse the given number of airborne ticks of a vehicle that do not descend. */
    private static void hover(LiftPulse pulse, int ticks, double gravity) {
        for (int i = 0; i < ticks; i++) {
            pulse.allowLift(false, 0.0D, gravity);
        }
    }

    /** Feeds the pulse the given number of ticks a player on foot spends in the air. */
    private static void hoverOnFoot(LiftPulse pulse, int ticks, double gravity) {
        for (int i = 0; i < ticks; i++) {
            pulse.allowLiftOnFoot(false, gravity);
        }
    }

    @Test
    void constantsAreTheSpecifiedOnes() {
        assertEquals(0.05D, LiftPulse.DESCENT_PER_TICK);
        assertEquals(3, LiftPulse.DESCENT_RUN_TICKS);
        assertEquals(0.5D, LiftPulse.LIMIT_SHARE);
        assertEquals(0.25D, LiftPulse.ON_FOOT_LIMIT_SHARE);
        assertTrue(LiftPulse.DESCENT_PER_TICK > SERVER_DESCENT_PER_TICK, "stricter than the server's own rule");
    }

    @Test
    void serverLimitFollowsVanilla() {
        assertEquals(80, LiftPulse.serverLimitTicks(0.08D), "a player");
        assertEquals(160, LiftPulse.serverLimitTicks(0.04D), "a boat");
        assertEquals(80, LiftPulse.serverLimitTicks(0.16D), "a heavier body gets no less than the base limit");
        assertEquals(640, LiftPulse.serverLimitTicks(0.01D), "a slowly falling player");
        assertEquals(214, LiftPulse.serverLimitTicks(0.03D), "rounded up");
    }

    @Test
    void serverLimitIsUnboundedWithoutGravity() {
        assertEquals(Integer.MAX_VALUE, LiftPulse.serverLimitTicks(0.0D));
        assertEquals(Integer.MAX_VALUE, LiftPulse.serverLimitTicks(1.0E-6D));
        assertEquals(Integer.MAX_VALUE, LiftPulse.serverLimitTicks(-0.08D));
    }

    @Test
    void liftIsAllowedForThirtyNineNonDescendingTicksAndRefusedFromTheFortieth() {
        LiftPulse pulse = new LiftPulse();
        assertEquals(0, pulse.streak());
        for (int tick = 1; tick <= 39; tick++) {
            assertTrue(pulse.allowLift(false, 0.3D, PLAYER_GRAVITY), "lift allowed on tick " + tick);
            assertEquals(tick, pulse.streak());
        }
        assertFalse(pulse.allowLift(false, 0.3D, PLAYER_GRAVITY), "lift refused on tick 40");
        assertEquals(40, pulse.streak());
    }

    @Test
    void liftStaysRefusedHoweverLongTheBodyDoesNotDescend() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 39, PLAYER_GRAVITY);
        for (int tick = 40; tick <= 10_000; tick++) {
            assertFalse(pulse.allowLift(false, 0.0D, PLAYER_GRAVITY), "no time-out on tick " + tick);
        }
        assertEquals(10_000, pulse.streak());
    }

    @Test
    void threeDescendingTicksInARowGiveLiftBack() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 500, PLAYER_GRAVITY);
        assertFalse(pulse.allowLift(false, -0.04D, PLAYER_GRAVITY), "a descent the server might still count");
        assertFalse(pulse.allowLift(false, -0.049D, PLAYER_GRAVITY));
        assertFalse(pulse.allowLift(false, -0.05D, PLAYER_GRAVITY), "the server may not have seen one descent");
        assertFalse(pulse.allowLift(false, -0.05D, PLAYER_GRAVITY), "nor two");
        assertEquals(504, pulse.streak(), "the ticks of an unfinished run are counted on");
        assertTrue(pulse.allowLift(false, -0.05D, PLAYER_GRAVITY),
            "the third descent of DESCENT_PER_TICK in a row clears the pause");
        assertEquals(0, pulse.streak());
        for (int tick = 1; tick <= 39; tick++) {
            assertTrue(pulse.allowLift(false, 0.0D, PLAYER_GRAVITY), "a new pulse, tick " + tick);
        }
        assertFalse(pulse.allowLift(false, 0.0D, PLAYER_GRAVITY));
    }

    @Test
    void aRunOfDescentsStartsAgainAfterATickThatDoesNotDescend() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 100, PLAYER_GRAVITY);
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
        assertFalse(pulse.allowLift(false, 0.02D, PLAYER_GRAVITY), "a rise interrupts the run");
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY), "the descents before the rise do not count");
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
        assertEquals(105, pulse.streak());
        assertTrue(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY), "three in a row");
        assertEquals(0, pulse.streak());
    }

    @Test
    void aRunOfDescentsClearsAStreakBeforeItIsWithheld() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 30, PLAYER_GRAVITY);
        assertEquals(30, pulse.streak());
        assertTrue(pulse.allowLift(false, -0.4D, PLAYER_GRAVITY), "not withheld yet, so lift goes on");
        assertEquals(31, pulse.streak(), "a single descent clears nothing");
        assertTrue(pulse.allowLift(false, -0.4D, PLAYER_GRAVITY));
        assertEquals(32, pulse.streak());
        assertTrue(pulse.allowLift(false, -0.4D, PLAYER_GRAVITY));
        assertEquals(0, pulse.streak(), "the third descent in a row clears the streak");
    }

    @Test
    void anUnfinishedRunOfDescentsDoesNotPutThePauseOff() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 38, PLAYER_GRAVITY);
        assertTrue(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
        assertEquals(39, pulse.streak());
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY), "tick 40 is refused in the middle of a run");
        assertTrue(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY), "and the run still ends the pause");
    }

    @Test
    void slowSinkingAndBrokenNumbersCountAsNotDescending() {
        LiftPulse pulse = new LiftPulse();
        assertTrue(pulse.allowLift(false, -0.04D, PLAYER_GRAVITY));
        assertTrue(pulse.allowLift(false, -0.0D, PLAYER_GRAVITY));
        assertTrue(pulse.allowLift(false, Double.NaN, PLAYER_GRAVITY));
        assertEquals(3, pulse.streak());
    }

    @Test
    void theGroundResetsThePulse() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 200, PLAYER_GRAVITY);
        assertFalse(pulse.allowLift(false, 0.0D, PLAYER_GRAVITY));
        assertTrue(pulse.allowLift(true, 0.0D, PLAYER_GRAVITY), "on the ground lift is allowed again");
        assertEquals(0, pulse.streak());
        assertTrue(pulse.allowLift(true, 0.3D, PLAYER_GRAVITY), "ticks on the ground are not counted");
        assertEquals(0, pulse.streak());
        assertTrue(pulse.allowLift(false, 0.3D, PLAYER_GRAVITY));
        assertEquals(1, pulse.streak());
    }

    @Test
    void theGroundForgetsAnUnfinishedRunOfDescents() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 200, PLAYER_GRAVITY);
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
        assertTrue(pulse.allowLift(true, 0.0D, PLAYER_GRAVITY));
        hover(pulse, 39, PLAYER_GRAVITY);
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY), "the two descents before the ground are gone");
        assertEquals(40, pulse.streak());
    }

    @Test
    void resetForgetsTheStreakAndThePause() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 200, PLAYER_GRAVITY);
        pulse.reset();
        assertEquals(0, pulse.streak());
        assertTrue(pulse.allowLift(false, 0.0D, PLAYER_GRAVITY));
        assertEquals(1, pulse.streak());
    }

    @Test
    void aBoatIsGivenTwiceAsLong() {
        LiftPulse pulse = new LiftPulse();
        for (int tick = 1; tick <= 79; tick++) {
            assertTrue(pulse.allowLift(false, 0.1D, BOAT_GRAVITY), "lift allowed on tick " + tick);
        }
        assertFalse(pulse.allowLift(false, 0.1D, BOAT_GRAVITY), "lift refused on tick 80");
        assertFalse(pulse.allowLift(false, 0.1D, BOAT_GRAVITY));
        assertFalse(pulse.allowLift(false, -0.2D, BOAT_GRAVITY));
        assertFalse(pulse.allowLift(false, -0.2D, BOAT_GRAVITY));
        assertTrue(pulse.allowLift(false, -0.2D, BOAT_GRAVITY), "given back by the third descent in a row");
    }

    @Test
    void aBodyWithoutGravityIsNeverRefused() {
        LiftPulse pulse = new LiftPulse();
        for (int tick = 1; tick <= RIDE_TICKS; tick++) {
            assertTrue(pulse.allowLift(false, 0.5D, 0.0D), "never withheld, tick " + tick);
        }
        assertEquals(RIDE_TICKS, pulse.streak(), "the ticks are counted all the same");
    }

    @Test
    void gravityThatComesBackFindsTheStreakStillCounted() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 60, PLAYER_GRAVITY);
        assertTrue(pulse.allowLift(false, 0.0D, 0.0D), "weightless: the server has no limit");
        assertFalse(pulse.allowLift(false, 0.0D, PLAYER_GRAVITY), "the server counted the weightless ticks too");
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
        assertFalse(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
        assertTrue(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
    }

    @Test
    void onFootLiftIsAllowedForNineteenTicksInTheAirAndRefusedFromTheTwentieth() {
        LiftPulse pulse = new LiftPulse();
        for (int tick = 1; tick <= 19; tick++) {
            assertTrue(pulse.allowLiftOnFoot(false, PLAYER_GRAVITY), "lift allowed on tick " + tick);
            assertEquals(tick, pulse.streak());
        }
        assertFalse(pulse.allowLiftOnFoot(false, PLAYER_GRAVITY), "lift refused on tick 20");
        assertEquals(20, pulse.streak());
    }

    @Test
    void onFootOnlyRestingGivesLiftBack() {
        LiftPulse pulse = new LiftPulse();
        hoverOnFoot(pulse, 19, PLAYER_GRAVITY);
        for (int tick = 20; tick <= 10_000; tick++) {
            assertFalse(pulse.allowLiftOnFoot(false, PLAYER_GRAVITY), "no time-out and no descent, tick " + tick);
        }
        assertEquals(10_000, pulse.streak(), "every tick in the air is counted, as the server counts it");
        assertTrue(pulse.allowLiftOnFoot(true, PLAYER_GRAVITY), "at rest lift is allowed again");
        assertEquals(0, pulse.streak());
        assertTrue(pulse.allowLiftOnFoot(true, PLAYER_GRAVITY), "ticks at rest are not counted");
        assertEquals(0, pulse.streak());
        assertTrue(pulse.allowLiftOnFoot(false, PLAYER_GRAVITY));
        assertEquals(1, pulse.streak());
    }

    @Test
    void onFootABodyWithoutGravityIsNeverRefused() {
        LiftPulse pulse = new LiftPulse();
        for (int tick = 1; tick <= RIDE_TICKS; tick++) {
            assertTrue(pulse.allowLiftOnFoot(false, 0.0D), "never withheld, tick " + tick);
        }
        assertEquals(RIDE_TICKS, pulse.streak(), "the ticks are counted all the same");
        assertFalse(pulse.allowLiftOnFoot(false, PLAYER_GRAVITY), "gravity that comes back finds them counted");
    }

    @Test
    void aPlayerOnFootInAnEf5LandsBeforeTheServerLimit() {
        Ride ride = ride(PLAYER_GRAVITY, PLAYER_VERTICAL_DRAG, true, 1, 0);
        int limit = LiftPulse.serverLimitTicks(PLAYER_GRAVITY);
        assertEquals(80, limit);
        assertTrue(ride.longestServerStreak() < limit,
            "the server counted " + ride.longestServerStreak() + " ticks in the air in a row");
        assertTrue(ride.longestServerStreak() <= 50,
            "a hop takes about 47 ticks from the ground to the ground, not " + ride.longestServerStreak());
        assertTrue(ride.refusedTicks() > 0, "the pulse paused the lift");
        assertTrue(ride.landings() >= 30, "the ride is a row of hops, " + ride.landings() + " landings");
        assertTrue(ride.highest() > 10.0D, "each hop still takes the player " + ride.highest() + " blocks up");
    }

    @Test
    void aMountRidingAnEf5IsNeverTakenForFloating() {
        Ride ride = vehicleRide(PLAYER_GRAVITY, PLAYER_VERTICAL_DRAG);
        int limit = LiftPulse.serverLimitTicks(PLAYER_GRAVITY);
        assertEquals(80, limit);
        assertTrue(ride.longestServerStreak() < limit,
            "the server counted " + ride.longestServerStreak() + " floating ticks in a row");
        assertTrue(ride.longestServerStreak() <= 55, "the streak peaks near 50, not near the limit");
        assertTrue(ride.refusedTicks() > 0, "the pulse paused the lift");
        assertTrue(ride.highest() > 30.0D, "the mount was carried aloft all the same");
    }

    @Test
    void aBoatRidingAnEf5IsNeverTakenForFloating() {
        Ride ride = vehicleRide(BOAT_GRAVITY, NO_DRAG);
        int limit = LiftPulse.serverLimitTicks(BOAT_GRAVITY);
        assertEquals(160, limit);
        assertTrue(ride.longestServerStreak() < limit,
            "the server counted " + ride.longestServerStreak() + " floating ticks in a row");
        assertTrue(ride.longestServerStreak() <= 110, "the streak peaks near 100, not near the limit");
        assertTrue(ride.refusedTicks() > 0, "the pulse paused the lift");
        assertTrue(ride.highest() > 30.0D, "the boat was carried aloft all the same");
    }

    @Test
    void aVehicleIsNotTakenForFloatingWhenTheServerSeesEverySecondPacket() {
        for (int phase = 0; phase < 2; phase++) {
            Ride boat = ride(BOAT_GRAVITY, NO_DRAG, false, 2, phase);
            assertTrue(boat.longestServerStreak() <= 110, "a boat, the server seeing packet " + phase
                + " of every two: " + boat.longestServerStreak() + " floating ticks in a row of 160");
            assertTrue(boat.highest() > 30.0D, "the boat was carried aloft all the same");
            Ride mount = ride(PLAYER_GRAVITY, PLAYER_VERTICAL_DRAG, false, 2, phase);
            assertTrue(mount.longestServerStreak() <= 55, "a mount, the server seeing packet " + phase
                + " of every two: " + mount.longestServerStreak() + " floating ticks in a row of 80");
            assertTrue(mount.highest() > 30.0D, "the mount was carried aloft all the same");
        }
    }

    @Test
    void aBodyWithoutGravityRidesUnpaused() {
        Ride ride = vehicleRide(0.0D, PLAYER_VERTICAL_DRAG);
        assertEquals(0, ride.refusedTicks(), "never withheld");
        assertEquals(Integer.MAX_VALUE, LiftPulse.serverLimitTicks(0.0D));
        assertTrue(ride.longestServerStreak() < LiftPulse.serverLimitTicks(0.0D), "the server sets no limit");
        assertTrue(ride.longestServerStreak() >= RIDE_TICKS - 1, "it never descends");
    }
}
