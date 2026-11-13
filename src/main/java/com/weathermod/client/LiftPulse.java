package com.weathermod.client;

/**
 * Keeps a lifted player, or the vehicle the player steers, from being kicked for floating. It mirrors the
 * server's own rule: the server counts consecutive ticks whose move packet did not descend by more than 0.03125
 * blocks and kicks above a limit that depends on the body's gravity. The pulse counts the same streak with a
 * safety margin and withholds lift from half the limit until the body has really descended.
 *
 * <p>Pure arithmetic with two fields of state; one pulse follows one body.
 */
public final class LiftPulse {
    /** A tick counts as a descent when the body moved down by at least this (the server asks for 0.03125). */
    public static final double DESCENT_PER_TICK = 0.05;
    /** Lift is withheld once the streak reaches this share of the server's limit. */
    public static final double LIMIT_SHARE = 0.5;

    /** Ticks the server lets a body with the gravity of a player float. */
    private static final double BASE_LIMIT_TICKS = 80.0;
    /** The gravity that limit is meant for; a lighter body is given proportionally longer. */
    private static final double BASE_GRAVITY = 0.08;
    /** Below this gravity the server never kicks. It compares against a float constant, as this does. */
    private static final double NO_GRAVITY = 1.0E-5F;

    private int streak;
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
     * Counts one client tick of the body and says whether lift may be applied in it. On the ground the streak
     * and the withheld flag are cleared and lift is allowed. A move of {@code -DESCENT_PER_TICK} or lower clears
     * both as well. Any other tick extends the streak; when it reaches
     * {@code floor(serverLimitTicks(gravity) * LIMIT_SHARE)} lift becomes withheld and stays so, with no time-out,
     * until a descending tick or the ground. A body the server never limits (see {@link #serverLimitTicks}) is
     * never refused, though its streak is still counted.
     *
     * @param onGround         whether the body rests on something, which ends the server's count as well
     * @param lastVerticalMove the body's y minus its y one tick ago
     * @param gravity          the body's gravity in blocks per tick squared
     * @return whether lift may be applied this tick
     */
    public boolean allowLift(boolean onGround, double lastVerticalMove, double gravity) {
        if (onGround || lastVerticalMove <= -DESCENT_PER_TICK) {
            reset();
            return true;
        }
        if (streak < Integer.MAX_VALUE) {
            streak++;
        }
        int limit = serverLimitTicks(gravity);
        if (limit == Integer.MAX_VALUE) {
            return true;
        }
        if (streak >= (int) Math.floor(limit * LIMIT_SHARE)) {
            withheld = true;
        }
        return !withheld;
    }

    /** Consecutive ticks counted since the body last stood on the ground or descended. */
    public int streak() {
        return streak;
    }

    /** Forgets the streak and allows lift again: for a body that changed or that no vortex concerns any more. */
    public void reset() {
        streak = 0;
        withheld = false;
    }
}
