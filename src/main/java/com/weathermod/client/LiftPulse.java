package com.weathermod.client;

/**
 * Keeps a lifted player, or the vehicle the player steers, from being kicked for floating. The server counts the
 * consecutive ticks in which the body's last move packet was a floating one and kicks above a limit that depends
 * on the body's gravity. What it takes for floating differs:
 * <ul>
 * <li>a steered vehicle floats in the air unless its packet descended by more than 0.03125 blocks. The server
 * looks at the last packet once per tick, so a single descending packet can pass unseen when two packets reach it
 * between two of its ticks;</li>
 * <li>a player on foot floats on every packet in the air, whatever the vertical motion: the handler tests a
 * vertical move it has just set to zero, so only resting on something ends the count.</li>
 * </ul>
 * The pulse counts the same streak with a safety margin and withholds lift from a share of the limit: for a
 * vehicle until the body has descended for {@link #DESCENT_RUN_TICKS} ticks in a row, for a player on foot until
 * the body has come to rest.
 *
 * <p>Pure arithmetic with three fields of state; one pulse follows one body.
 */
public final class LiftPulse {
    /** A tick counts as a descent when the body moved down by at least this (the server asks for 0.03125). */
    public static final double DESCENT_PER_TICK = 0.05;
    /**
     * A vehicle's streak ends after this many descending ticks in a row, so that the server sees a descending
     * packet on one of its own ticks unless it handles more than this many packets between two of them.
     */
    public static final int DESCENT_RUN_TICKS = 3;
    /** Lift is withheld from a vehicle once the streak reaches this share of the server's limit. */
    public static final double LIMIT_SHARE = 0.5;
    /**
     * Lift is withheld from a player on foot once the streak reaches this share of the server's limit. The whole
     * hop has to fit under the limit: the lift, the rest of the rise and the fall back to the ground.
     */
    public static final double ON_FOOT_LIMIT_SHARE = 0.25;

    /** Ticks the server lets a body with the gravity of a player float. */
    private static final double BASE_LIMIT_TICKS = 80.0;
    /** The gravity that limit is meant for; a lighter body is given proportionally longer. */
    private static final double BASE_GRAVITY = 0.08;
    /** Below this gravity the server never kicks. It compares against a float constant, as this does. */
    private static final double NO_GRAVITY = 1.0E-5F;

    private int streak;
    private int descentRun;
    private boolean withheld;

    /** A pulse with no streak that allows lift. */
    public LiftPulse() {
    }

    /**
     * The number of consecutive floating ticks above which the server kicks a body of the given gravity, as
     * vanilla's {@code getMaximumFlyingTicks} computes it: {@code ceil(80 * max(0.08 / gravity, 1))}, and
     * {@link Integer#MAX_VALUE} when the gravity is below {@code 1e-5}.
     */
    public static int serverLimitTicks(double gravity) {
        if (gravity < NO_GRAVITY) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(BASE_LIMIT_TICKS * Math.max(BASE_GRAVITY / gravity, 1.0));
    }

    /**
     * Counts one client tick of a steered vehicle and says whether lift may be applied in it. On the ground the
     * streak and the withheld flag are cleared and lift is allowed. The {@link #DESCENT_RUN_TICKS}th move in a row
     * of {@code -DESCENT_PER_TICK} or lower clears both as well. Any other tick extends the streak, the earlier
     * ticks of such a run included; when it reaches {@code floor(serverLimitTicks(gravity) * LIMIT_SHARE)} lift
     * becomes withheld and stays so, with no time-out, until a full run of descending ticks or the ground. A body
     * the server never limits (see {@link #serverLimitTicks}) is never refused, though its streak is still
     * counted.
     *
     * @param onGround         whether the body rests on something, which ends the server's count as well
     * @param lastVerticalMove the body's y minus its y one tick ago
     * @param gravity          the body's gravity in blocks per tick squared
     * @return whether lift may be applied this tick
     */
    public boolean allowLift(boolean onGround, double lastVerticalMove, double gravity) {
        if (onGround) {
            reset();
            return true;
        }
        if (lastVerticalMove <= -DESCENT_PER_TICK) {
            descentRun++;
            if (descentRun >= DESCENT_RUN_TICKS) {
                reset();
                return true;
            }
        } else {
            descentRun = 0;
        }
        return count(gravity, LIMIT_SHARE);
    }

    /**
     * Counts one client tick of a player on foot and says whether lift may be applied in it. While the body rests
     * the streak and the withheld flag are cleared and lift is allowed. Every other tick extends the streak,
     * rising or falling; when it reaches {@code floor(serverLimitTicks(gravity) * ON_FOOT_LIMIT_SHARE)} lift
     * becomes withheld and stays so until the body rests again. A body the server never limits is never refused,
     * though its streak is still counted.
     *
     * @param resting whether the body rests on something, or is in a state in which the server does not count it
     *                at all; either ends the server's count as well
     * @param gravity the body's gravity in blocks per tick squared
     * @return whether lift may be applied this tick
     */
    public boolean allowLiftOnFoot(boolean resting, double gravity) {
        if (resting) {
            reset();
            return true;
        }
        descentRun = 0;
        return count(gravity, ON_FOOT_LIMIT_SHARE);
    }

    /** Extends the streak by one tick and withholds lift once it reaches the given share of the server's limit. */
    private boolean count(double gravity, double limitShare) {
        if (streak < Integer.MAX_VALUE) {
            streak++;
        }
        int limit = serverLimitTicks(gravity);
        if (limit == Integer.MAX_VALUE) {
            return true;
        }
        if (streak >= (int) Math.floor(limit * limitShare)) {
            withheld = true;
        }
        return !withheld;
    }

    /** Consecutive ticks counted since the streak was last cleared: by the ground, a run of descents or a reset. */
    public int streak() {
        return streak;
    }

    /** Forgets the streak and allows lift again: for a body that changed or that no vortex concerns any more. */
    public void reset() {
        streak = 0;
        descentRun = 0;
        withheld = false;
    }
}
