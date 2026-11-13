package com.weathermod.server.director;

import net.minecraft.SharedConstants;

/**
 * Pure rules of the severe-weather cooldown, in game ticks. The director keeps one mark per dimension, the game
 * time from which the next natural severe storm may form, and pushes it ahead for as long as a severe storm lives,
 * so the cooldown counts from the moment the last severe storm ends.
 */
public final class SevereCooldown {
    private SevereCooldown() {
    }

    /**
     * The game time from which the next natural severe storm may form: while a severe storm is alive the mark is
     * pushed to {@code now + cooldownSeconds * 20}, otherwise it is left alone. A negative cooldown counts as zero.
     *
     * @param readyGameTime   the mark so far
     * @param now             the level's game time
     * @param severeAlive     whether a severe storm lives in the dimension
     * @param cooldownSeconds the configured cooldown in real seconds
     */
    public static long update(long readyGameTime, long now, boolean severeAlive, int cooldownSeconds) {
        if (!severeAlive) {
            return readyGameTime;
        }
        return now + (long) Math.max(0, cooldownSeconds) * SharedConstants.TICKS_PER_SECOND;
    }

    /** True when no severe storm is alive and {@code now >= readyGameTime}. */
    public static boolean allows(long readyGameTime, long now, boolean severeAlive) {
        return !severeAlive && now >= readyGameTime;
    }

    /** Whole seconds until {@link #allows} can be true, 0 when ready; rounded up. */
    public static int secondsLeft(long readyGameTime, long now) {
        long ticks = readyGameTime - now;
        if (ticks <= 0L) {
            return 0;
        }
        long seconds = ticks / SharedConstants.TICKS_PER_SECOND;
        if (ticks % SharedConstants.TICKS_PER_SECOND != 0L) {
            seconds++;
        }
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }
}
