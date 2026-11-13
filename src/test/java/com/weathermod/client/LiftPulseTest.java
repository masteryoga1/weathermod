package com.weathermod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the pause that keeps a lifted body from being kicked for floating: the server's limit by gravity, the
 * tick on which lift is withheld and what gives it back, and, by simulating long rides in the strongest tornado,
 * that the streak of floating ticks the server counts stays below its limit. The lift and the rise cap of an EF5
 * are repeated here as constants. Needs no Minecraft bootstrap.
 */
class LiftPulseTest {
    /** Gravity of a player and of a boat, in blocks per tick squared. */
    private static final double PLAYER_GRAVITY = 0.08D;
    private static final double BOAT_GRAVITY = 0.04D;
    /** A living entity keeps this share of its vertical speed per tick; a boat keeps all of it. */
    private static final double PLAYER_VERTICAL_DRAG = 0.98D;
    private static final double NO_DRAG = 1.0D;
    /** Lift and rise cap of an EF5 tornado at full strength. */
    private static final double EF5_LIFT = 0.22D;
    private static final double EF5_RISE_CAP = 0.5D;
    /** The server counts a move packet as floating unless it descends by more than this. */
    private static final double SERVER_DESCENT_PER_TICK = 0.03125D;
    private static final int RIDE_TICKS = 2000;

    /** What a simulated ride showed. */
    private record Ride(int longestServerStreak, int refusedTicks, double highest) {
    }

    /**
     * Rides the core of an EF5 for {@link #RIDE_TICKS} ticks from the ground: every tick the pulse is asked, lift
     * is added when it agrees and the body rises slower than the rise cap, the body moves, and gravity and drag
     * act as vanilla applies them. Counts the consecutive ticks the server would take for floating: in the air
     * and not descending by more than {@link #SERVER_DESCENT_PER_TICK}.
     */
    private static Ride ride(double gravity, double verticalDrag) {
        LiftPulse pulse = new LiftPulse();
        double y = 0.0D;
        double velocity = 0.0D;
        double lastMove = 0.0D;
        boolean onGround = true;
        int serverStreak = 0;
        int longest = 0;
        int refused = 0;
        double highest = 0.0D;
        for (int tick = 0; tick < RIDE_TICKS; tick++) {
            boolean allowed = pulse.allowLift(onGround, lastMove, gravity);
            if (!allowed) {
                refused++;
            } else if (velocity < EF5_RISE_CAP) {
                velocity += EF5_LIFT;
            }
            double next = y + velocity;
            onGround = next <= 0.0D;
            if (onGround) {
                next = 0.0D;
                velocity = 0.0D;
            }
            lastMove = next - y;
            y = next;
            velocity = (velocity - gravity) * verticalDrag;
            if (!onGround && lastMove >= -SERVER_DESCENT_PER_TICK) {
                serverStreak++;
            } else {
                serverStreak = 0;
            }
            longest = Math.max(longest, serverStreak);
            highest = Math.max(highest, y);
        }
        return new Ride(longest, refused, highest);
    }

    /** Feeds the pulse the given number of airborne ticks that do not descend. */
    private static void hover(LiftPulse pulse, int ticks, double gravity) {
        for (int i = 0; i < ticks; i++) {
            pulse.allowLift(false, 0.0D, gravity);
        }
    }

    @Test
    void constantsAreTheSpecifiedOnes() {
        assertEquals(0.05D, LiftPulse.DESCENT_PER_TICK);
        assertEquals(0.5D, LiftPulse.LIMIT_SHARE);
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
    void aDescendingTickGivesLiftBack() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 500, PLAYER_GRAVITY);
        assertFalse(pulse.allowLift(false, -0.04D, PLAYER_GRAVITY), "a descent the server might still count");
        assertFalse(pulse.allowLift(false, -0.049D, PLAYER_GRAVITY));
        assertTrue(pulse.allowLift(false, -0.05D, PLAYER_GRAVITY), "a descent of DESCENT_PER_TICK clears the pause");
        assertEquals(0, pulse.streak());
        for (int tick = 1; tick <= 39; tick++) {
            assertTrue(pulse.allowLift(false, 0.0D, PLAYER_GRAVITY), "a new pulse, tick " + tick);
        }
        assertFalse(pulse.allowLift(false, 0.0D, PLAYER_GRAVITY));
    }

    @Test
    void aDescendingTickClearsAStreakBeforeItIsWithheld() {
        LiftPulse pulse = new LiftPulse();
        hover(pulse, 30, PLAYER_GRAVITY);
        assertEquals(30, pulse.streak());
        assertTrue(pulse.allowLift(false, -0.4D, PLAYER_GRAVITY));
        assertEquals(0, pulse.streak());
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
        assertTrue(pulse.allowLift(false, -0.2D, BOAT_GRAVITY));
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
        assertTrue(pulse.allowLift(false, -0.3D, PLAYER_GRAVITY));
    }

    @Test
    void aPlayerRidingAnEf5IsNeverTakenForFloating() {
        Ride ride = ride(PLAYER_GRAVITY, PLAYER_VERTICAL_DRAG);
        int limit = LiftPulse.serverLimitTicks(PLAYER_GRAVITY);
        assertEquals(80, limit);
        assertTrue(ride.longestServerStreak() < limit,
            "the server counted " + ride.longestServerStreak() + " floating ticks in a row");
        assertTrue(ride.longestServerStreak() <= 55, "the streak peaks near 50, not near the limit");
        assertTrue(ride.refusedTicks() > 0, "the pulse paused the lift");
        assertTrue(ride.highest() > 30.0D, "the player was carried aloft all the same");
    }

    @Test
    void aBoatRidingAnEf5IsNeverTakenForFloating() {
        Ride ride = ride(BOAT_GRAVITY, NO_DRAG);
        int limit = LiftPulse.serverLimitTicks(BOAT_GRAVITY);
        assertEquals(160, limit);
        assertTrue(ride.longestServerStreak() < limit,
            "the server counted " + ride.longestServerStreak() + " floating ticks in a row");
        assertTrue(ride.longestServerStreak() <= 110, "the streak peaks near 100, not near the limit");
        assertTrue(ride.refusedTicks() > 0, "the pulse paused the lift");
        assertTrue(ride.highest() > 30.0D, "the boat was carried aloft all the same");
    }

    @Test
    void aBodyWithoutGravityRidesUnpaused() {
        Ride ride = ride(0.0D, PLAYER_VERTICAL_DRAG);
        assertEquals(0, ride.refusedTicks(), "never withheld");
        assertEquals(Integer.MAX_VALUE, LiftPulse.serverLimitTicks(0.0D));
        assertTrue(ride.longestServerStreak() < LiftPulse.serverLimitTicks(0.0D), "the server sets no limit");
        assertTrue(ride.longestServerStreak() >= RIDE_TICKS - 1, "it never descends");
    }
}
