package com.weathermod.server.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * Round-trips the director's state through NBT, checks the defaults a tag of an earlier milestone falls back to,
 * the severe-weather mark that is only written when set, and the re-arm arithmetic the spawn timer runs on. Needs
 * no Minecraft bootstrap.
 */
class DirectorStateNbtTest {
    private static final String KEY_TICKS_UNTIL_ATTEMPT = "TicksUntilAttempt";
    private static final String KEY_PAUSED = "Paused";
    private static final String KEY_LAST_SPAWN_GAME_TIME = "LastSpawnGameTime";
    private static final String KEY_NATURAL_SPAWNS = "NaturalSpawns";
    private static final String KEY_SEVERE_READY_GAME_TIME = "SevereReadyGameTime";

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

    @Test
    void severeReadyGameTimeRoundTrips() {
        DirectorState state = new DirectorState(4321, true, 987654321L, 17, 1234567L);

        CompoundTag tag = state.save();
        DirectorState loaded = DirectorState.load(tag);

        assertTrue(tag.contains(KEY_SEVERE_READY_GAME_TIME, Tag.TAG_LONG), "the mark is written as a long");
        assertEquals(1234567L, tag.getLong(KEY_SEVERE_READY_GAME_TIME));
        assertEquals(1234567L, loaded.severeReadyGameTime);
        assertEquals(4321, loaded.ticksUntilAttempt, "the older fields load as before");
        assertEquals(state, loaded);
        assertEquals(state.hashCode(), loaded.hashCode());
        assertEquals(tag, loaded.save());
    }

    @Test
    void severeReadyGameTimeDefaultsToZeroAndIsNotWrittenThen() {
        assertEquals(0L, new DirectorState().severeReadyGameTime, "a fresh world may have a severe storm at once");
        assertEquals(0L, new DirectorState(100, false, 5L, 2).severeReadyGameTime,
            "the four-argument constructor leaves the mark unset");
        assertEquals(new DirectorState(100, false, 5L, 2), new DirectorState(100, false, 5L, 2, 0L));

        CompoundTag secondMilestone = new CompoundTag();
        secondMilestone.putInt(KEY_TICKS_UNTIL_ATTEMPT, 100);
        secondMilestone.putBoolean(KEY_PAUSED, false);
        secondMilestone.putLong(KEY_LAST_SPAWN_GAME_TIME, 5L);
        secondMilestone.putInt(KEY_NATURAL_SPAWNS, 2);

        DirectorState loaded = DirectorState.load(secondMilestone);

        assertEquals(0L, loaded.severeReadyGameTime, "a tag without the key loads an unset mark");
        assertEquals(secondMilestone, loaded.save(), "a tag of the second milestone is saved back unchanged");
        assertFalse(loaded.save().contains(KEY_SEVERE_READY_GAME_TIME));
        assertEquals(4, loaded.save().size());

        loaded.severeReadyGameTime = 18000L;
        assertEquals(18000L, loaded.save().getLong(KEY_SEVERE_READY_GAME_TIME), "the live field is saved in place");
        assertEquals(5, loaded.save().size());
        loaded.severeReadyGameTime = 0L;
        assertFalse(loaded.save().contains(KEY_SEVERE_READY_GAME_TIME), "and dropped again once it is zero");
    }

    @Test
    void severeReadyGameTimeTakesPartInEqualityAndIsClamped() {
        DirectorState state = new DirectorState(1, false, 2L, 3, 400L);

        assertEquals(state, new DirectorState(1, false, 2L, 3, 400L));
        assertNotEquals(state, new DirectorState(1, false, 2L, 3, 401L));
        assertNotEquals(state, new DirectorState(1, false, 2L, 3));
        assertNotEquals(state.hashCode(), new DirectorState(1, false, 2L, 3, 401L).hashCode());
        assertTrue(state.toString().contains("severeReadyGameTime=400"), state.toString());

        assertEquals(0L, new DirectorState(1, false, 2L, 3, -9L).severeReadyGameTime, "a negative mark is no mark");
        CompoundTag tag = new CompoundTag();
        tag.putLong(KEY_SEVERE_READY_GAME_TIME, -1L);
        assertEquals(0L, DirectorState.load(tag).severeReadyGameTime);
        tag.putString(KEY_SEVERE_READY_GAME_TIME, "soon");
        assertEquals(0L, DirectorState.load(tag).severeReadyGameTime, "a mark of the wrong tag type is ignored");
    }
}
