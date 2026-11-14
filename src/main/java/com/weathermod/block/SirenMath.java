package com.weathermod.block;

/**
 * The level-free rules of the tornado siren: how near a tornado must be, how long the siren keeps sounding after
 * the last trigger and when it counts as active. Everything here is pure and unit tested; the block entity only
 * feeds it the storms of its dimension.
 */
public final class SirenMath {
    /** A siren looks for tornadoes once in this many ticks. */
    public static final int CHECK_INTERVAL_TICKS = 20;
    /** A siren keeps sounding this long after the last trigger, so it does not flicker at the edge of its range. */
    public static final int HOLD_TICKS = 200;
    /** How far the siren loop is heard, in blocks. Read by the client sound code. */
    public static final float AUDIBLE_BLOCKS = 128.0F;

    private SirenMath() {
    }

    /**
     * Whether a storm sets a siren off: the horizontal distance from the siren to the storm centre is at most
     * {@code sirenRange + stormRadius}, so the range is measured from the storm's edge. The bound itself is in
     * range.
     *
     * @param sirenX      the siren's position along {@code X}
     * @param sirenZ      the siren's position along {@code Z}
     * @param stormX      the storm centre along {@code X}
     * @param stormZ      the storm centre along {@code Z}
     * @param stormRadius the storm's radius in blocks
     * @param sirenRange  the configured range in blocks, measured from the storm's edge
     */
    public static boolean inRange(double sirenX, double sirenZ, double stormX, double stormZ, float stormRadius,
                                  int sirenRange) {
        double reach = (double) sirenRange + stormRadius;
        if (!(reach >= 0.0D)) {
            return false;
        }
        double dx = stormX - sirenX;
        double dz = stormZ - sirenZ;
        return dx * dx + dz * dz <= reach * reach;
    }

    /**
     * The hold counter after one check: {@link #HOLD_TICKS} when the siren was triggered, otherwise what was left
     * minus the ticks that passed since the last check, never below zero. Elapsed ticks below zero count as zero.
     *
     * @param holdTicks    the counter before the check
     * @param triggered    whether a tornado is in range now
     * @param elapsedTicks ticks since the last check
     */
    public static int nextHold(int holdTicks, boolean triggered, int elapsedTicks) {
        if (triggered) {
            return HOLD_TICKS;
        }
        int elapsed = Math.max(0, elapsedTicks);
        return holdTicks > elapsed ? holdTicks - elapsed : 0;
    }

    /** Whether the siren sounds: it is powered by redstone, or its hold counter has not run out. */
    public static boolean active(boolean powered, int holdTicks) {
        return powered || holdTicks > 0;
    }
}
