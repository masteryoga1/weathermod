package com.weathermod.server.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the director's state through NBT, checks the defaults a pre-director tag falls back to and the
 * re-arm arithmetic the spawn timer runs on. Needs no Minecraft bootstrap.
 */
class DirectorStateNbtTest {
    private static final String KEY_TICKS_UNTIL_ATTEMPT = "TicksUntilAttempt";
    private static final String KEY_PAUSED = "Paused";
    private static final String KEY_LAST_SPAWN_GAME_TIME = "LastSpawnGameTime";
    private static final String KEY_NATURAL_SPAWNS = "NaturalSpawns";

    @Test
    void freshStateIsDueUnpausedAndEmpty() {
        DirectorState state = new DirectorState();

        assertEquals(0, state.ticksUntilAttempt, "the first attempt is due at the first director tick");
        assertFalse(state.paused);
        assertEquals(0L, state.lastSpawnGameTime);
        assertEquals(0, state.naturalSpawns);
        assertFalse(state.hasSpawned());
        assertEquals(0, state.secondsUntilAttempt());
    }

    @Test
    void saveThenLoadRestoresEveryField() {
        DirectorState state = new DirectorState(4321, true, 987654321L, 17);

        CompoundTag tag = state.save();
        DirectorState loaded = DirectorState.load(tag);

        assertEquals(4321, loaded.ticksUntilAttempt);
        assertTrue(loaded.paused);
        assertEquals(987654321L, loaded.lastSpawnGameTime);
        assertEquals(17, loaded.naturalSpawns);
        assertEquals(state, loaded);
        assertEquals(state.hashCode(), loaded.hashCode());
    }

    @Test
    void saveWritesTheAgreedKeysAndIsStableAcrossASecondRoundTrip() {
        CompoundTag first = new DirectorState(100, false, 5L, 2).save();

        assertEquals(100, first.getInt(KEY_TICKS_UNTIL_ATTEMPT));
        assertFalse(first.getBoolean(KEY_PAUSED));
        assertEquals(5L, first.getLong(KEY_LAST_SPAWN_GAME_TIME));
        assertEquals(2, first.getInt(KEY_NATURAL_SPAWNS));

        CompoundTag second = DirectorState.load(first).save();
        assertEquals(first, second);
    }

    @Test
    void emptyTagLoadsTheFreshState() {
        DirectorState loaded = DirectorState.load(new CompoundTag());

        assertEquals(new DirectorState(), loaded, "a world saved before the director existed starts fresh");
        assertFalse(loaded.paused);
    }

    @Test
    void partialTagKeepsWhatItHasAndDefaultsTheRest() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(KEY_PAUSED, true);
        tag.putInt(KEY_NATURAL_SPAWNS, 3);

        DirectorState loaded = DirectorState.load(tag);

        assertTrue(loaded.paused);
        assertEquals(3, loaded.naturalSpawns);
        assertTrue(loaded.hasSpawned());
        assertEquals(0, loaded.ticksUntilAttempt);
        assertEquals(0L, loaded.lastSpawnGameTime);
    }

    @Test
    void negativeCountersAreClampedOnLoad() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_TICKS_UNTIL_ATTEMPT, -500);
        tag.putLong(KEY_LAST_SPAWN_GAME_TIME, -1L);
        tag.putInt(KEY_NATURAL_SPAWNS, -2);

        DirectorState loaded = DirectorState.load(tag);

        assertEquals(0, loaded.ticksUntilAttempt);
        assertEquals(0L, loaded.lastSpawnGameTime);
        assertEquals(0, loaded.naturalSpawns);
    }

    @Test
    void recordSpawnCountsAndStampsTheTime() {
        DirectorState state = new DirectorState();

        state.recordSpawn(24000L);
        state.recordSpawn(48000L);

        assertEquals(2, state.naturalSpawns);
        assertEquals(48000L, state.lastSpawnGameTime);
        assertTrue(state.hasSpawned());
    }

    @Test
    void secondsUntilAttemptRoundsUpToWholeSeconds() {
        assertEquals(0, new DirectorState(0, false, 0L, 0).secondsUntilAttempt());
        assertEquals(1, new DirectorState(1, false, 0L, 0).secondsUntilAttempt());
        assertEquals(1, new DirectorState(20, false, 0L, 0).secondsUntilAttempt());
        assertEquals(2, new DirectorState(21, false, 0L, 0).secondsUntilAttempt());
        assertEquals(240, new DirectorState(4800, false, 0L, 0).secondsUntilAttempt());
    }

    @Test
    void rearmTicksStayInsideHalfToOneAndAHalfTimesTheInterval() {
        RandomSource random = RandomSource.create(1234L);
        int base = 240 * 20;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int i = 0; i < 5000; i++) {
            int ticks = DirectorState.rearmTicks(240, 1.0D, random);
            assertTrue(ticks >= base / 2, "re-arm below half the interval: " + ticks);
            assertTrue(ticks <= base * 3 / 2, "re-arm above one and a half intervals: " + ticks);
            lowest = Math.min(lowest, ticks);
            highest = Math.max(highest, ticks);
        }
        assertTrue(lowest < base * 0.6D, "the lower part of the band is used, lowest was " + lowest);
        assertTrue(highest > base * 1.4D, "the upper part of the band is used, highest was " + highest);
    }

    @Test
    void rearmTicksScaleInverselyWithTheFrequency() {
        int atOne = DirectorState.rearmTicks(240, 1.0D, RandomSource.create(7L));
        int atTwo = DirectorState.rearmTicks(240, 2.0D, RandomSource.create(7L));
        int atHalf = DirectorState.rearmTicks(240, 0.5D, RandomSource.create(7L));

        assertEquals(atOne, atTwo * 2, 1, "doubling the frequency halves the wait for the same roll");
        assertEquals(atOne * 2, atHalf, 1, "halving the frequency doubles the wait for the same roll");
    }

    @Test
    void rearmTicksSurviveDegenerateInputs() {
        assertTrue(DirectorState.rearmTicks(240, 0.0D, RandomSource.create(1L)) >= 1,
            "a zero frequency is clamped instead of dividing by zero");
        assertTrue(DirectorState.rearmTicks(240, -3.0D, RandomSource.create(1L)) >= 1);
        assertEquals(1, DirectorState.rearmTicks(0, 1.0D, RandomSource.create(1L)),
            "a zero interval still waits one tick so the timer always counts down");
        assertTrue(DirectorState.rearmTicks(86400, DirectorState.MIN_FREQUENCY, RandomSource.create(1L)) > 0,
            "the longest configurable wait at the smallest frequency fits an int");
    }

    @Test
    void rearmSetsTheCountdownAndReturnsIt() {
        DirectorState state = new DirectorState();
        RandomSource random = RandomSource.create(99L);

        int ticks = state.rearm(240, 1.0D, random);

        assertEquals(ticks, state.ticksUntilAttempt);
        assertTrue(ticks >= 2400 && ticks <= 7200);
        assertNotEquals(0, state.secondsUntilAttempt());
    }
}
