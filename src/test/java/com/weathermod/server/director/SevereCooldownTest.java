package com.weathermod.server.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Checks the rules of the severe-weather cooldown: when the mark moves, when a severe storm may form and how the
 * time left is reported. Needs no Minecraft bootstrap: the rules are plain arithmetic on game ticks.
 */
class SevereCooldownTest {
    /** The default of the config key {@code severeWeatherCooldownSeconds}. */
    private static final int COOLDOWN_SECONDS = 900;
    private static final long COOLDOWN_TICKS = 18000L;

    @Test
    void updateLeavesTheMarkAloneWithoutASevereStorm() {
        assertEquals(500L, SevereCooldown.update(500L, 1000L, false, COOLDOWN_SECONDS));
        assertEquals(0L, SevereCooldown.update(0L, 1000L, false, COOLDOWN_SECONDS), "a fresh world stays ready");
        assertEquals(50000L, SevereCooldown.update(50000L, 1000L, false, COOLDOWN_SECONDS),
            "a mark still ahead keeps counting down by itself");
    }

    @Test
    void updatePushesTheMarkWhileASevereStormLives() {
        assertEquals(1000L + COOLDOWN_TICKS, SevereCooldown.update(0L, 1000L, true, COOLDOWN_SECONDS));
        assertEquals(1100L + COOLDOWN_TICKS, SevereCooldown.update(1000L + COOLDOWN_TICKS, 1100L, true,
            COOLDOWN_SECONDS), "every pass with a severe storm alive pushes the mark ahead again");
        assertEquals(1000L + 60L * 20L, SevereCooldown.update(99999L, 1000L, true, 60),
            "the mark follows the configured cooldown as it is now");
    }

    @Test
    void theCooldownCountsFromTheLastPassThatSawTheStorm() {
        long mark = 0L;
        for (long now = 1000L; now <= 1500L; now += 100L) {
            mark = SevereCooldown.update(mark, now, true, COOLDOWN_SECONDS);
            assertFalse(SevereCooldown.allows(mark, now, true));
        }
        assertEquals(1500L + COOLDOWN_TICKS, mark);

        mark = SevereCooldown.update(mark, 1600L, false, COOLDOWN_SECONDS);
        assertEquals(1500L + COOLDOWN_TICKS, mark, "once the storm is gone the mark stands still");
        assertFalse(SevereCooldown.allows(mark, 1600L, false));
        assertFalse(SevereCooldown.allows(mark, 1499L + COOLDOWN_TICKS, false));
        assertTrue(SevereCooldown.allows(mark, 1500L + COOLDOWN_TICKS, false), "the cooldown ends on the mark");
    }

    @Test
    void aCooldownOfZeroOnlyWaitsForTheStormToEnd() {
        long mark = SevereCooldown.update(500L, 1000L, true, 0);

        assertEquals(1000L, mark);
        assertFalse(SevereCooldown.allows(mark, 1000L, true), "one severe storm at a time, whatever the cooldown");
        assertTrue(SevereCooldown.allows(mark, 1000L, false), "with no cooldown the next may form at once");
        assertEquals(0, SevereCooldown.secondsLeft(mark, 1000L));
        assertEquals(1000L, SevereCooldown.update(500L, 1000L, true, -30), "a negative cooldown counts as zero");
    }

    @Test
    void updateDoesNotOverflowForTheLongestCooldown() {
        long now = 1_000_000_000_000L;
        assertEquals(now + 20L * Integer.MAX_VALUE, SevereCooldown.update(0L, now, true, Integer.MAX_VALUE));
    }

    @Test
    void allowsNeedsNoSevereStormAndAReachedMark() {
        assertTrue(SevereCooldown.allows(0L, 0L, false), "a fresh world may have its first severe storm");
        assertTrue(SevereCooldown.allows(19000L, 19000L, false), "the mark itself is inclusive");
        assertTrue(SevereCooldown.allows(19000L, 30000L, false));
        assertFalse(SevereCooldown.allows(19000L, 18999L, false));
        assertFalse(SevereCooldown.allows(19000L, 30000L, true), "never while a severe storm is alive");
        assertFalse(SevereCooldown.allows(0L, 0L, true));
    }

    @Test
    void secondsLeftRoundsUpToWholeSeconds() {
        assertEquals(900, SevereCooldown.secondsLeft(19000L, 1000L));
        assertEquals(0, SevereCooldown.secondsLeft(1000L, 1000L), "ready on the mark");
        assertEquals(0, SevereCooldown.secondsLeft(1000L, 2000L), "and ever after");
        assertEquals(1, SevereCooldown.secondsLeft(1001L, 1000L), "one tick left still shows as a second");
        assertEquals(1, SevereCooldown.secondsLeft(1020L, 1000L));
        assertEquals(2, SevereCooldown.secondsLeft(1021L, 1000L));
        assertEquals(Integer.MAX_VALUE, SevereCooldown.secondsLeft(Long.MAX_VALUE, 0L),
            "an absurd mark is capped instead of overflowing");
    }
}
