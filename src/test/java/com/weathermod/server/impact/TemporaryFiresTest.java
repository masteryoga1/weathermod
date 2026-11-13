package com.weathermod.server.impact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * Pins the list of temporary fires as far as it can be tested without a level: its capacity, that a position is
 * listed once, the countdown, and what is saved and loaded. Putting the fires out needs a level and is part of the
 * smoke test.
 */
class TemporaryFiresTest {
    private static final BlockPos FIRST = new BlockPos(12, 70, -340);
    private static final BlockPos SECOND = new BlockPos(-5, 33, 8);

    private static void advance(TemporaryFires fires, int calls) {
        for (int i = 0; i < calls; i++) {
            fires.advance();
        }
    }

    @Test
    void theConstantsAreTheAgreedOnes() {
        assertEquals(64, TemporaryFires.MAX_TEMPORARY_FIRES);
        assertEquals(200, TemporaryFires.LIFE_TICKS);
    }

    @Test
    void aNewListIsEmpty() {
        TemporaryFires fires = new TemporaryFires();
        assertTrue(fires.isEmpty());
        assertEquals(0, fires.size());
        assertEquals(List.of(), fires.advance());
    }

    @Test
    void positionsAreAddedUntilTheListIsFull() {
        TemporaryFires fires = new TemporaryFires();
        for (int i = 0; i < TemporaryFires.MAX_TEMPORARY_FIRES; i++) {
            assertTrue(fires.add(new BlockPos(i, 64, 0)), "fire " + i);
        }
        assertEquals(64, fires.size());
        assertFalse(fires.add(new BlockPos(1000, 64, 0)), "the sixty-fifth is refused");
        assertEquals(64, fires.size());

        fires.remove(new BlockPos(3, 64, 0));
        assertEquals(63, fires.size());
        assertTrue(fires.add(new BlockPos(1000, 64, 0)), "a removed fire makes room");
    }

    @Test
    void aPositionIsListedOnce() {
        TemporaryFires fires = new TemporaryFires();
        assertTrue(fires.add(FIRST));
        assertFalse(fires.add(FIRST));
        assertFalse(fires.add(new BlockPos(12, 70, -340)), "an equal position, not only the same object");
        assertEquals(1, fires.size());
        assertFalse(fires.isEmpty());
    }

    @Test
    void aDuplicateDoesNotRestartTheCountdown() {
        TemporaryFires fires = new TemporaryFires();
        fires.add(FIRST);
        advance(fires, 150);
        assertFalse(fires.add(FIRST));
        advance(fires, 49);
        assertEquals(List.of(FIRST), fires.advance(), "due 200 ticks after the first add");
    }

    @Test
    void removingForgetsAPosition() {
        TemporaryFires fires = new TemporaryFires();
        fires.add(FIRST);
        fires.remove(SECOND);
        assertEquals(1, fires.size(), "removing a position that is not listed changes nothing");
        fires.remove(FIRST);
        assertTrue(fires.isEmpty());
        assertTrue(fires.add(FIRST), "a removed position can be listed again");
    }

    @Test
    void aPositionIsDueAfterExactlyItsLifeTime() {
        TemporaryFires fires = new TemporaryFires();
        fires.add(FIRST);
        for (int call = 1; call < TemporaryFires.LIFE_TICKS; call++) {
            assertEquals(List.of(), fires.advance(), "call " + call);
        }
        assertEquals(List.of(FIRST), fires.advance(), "the two hundredth call");
        assertEquals(1, fires.size(), "advancing reports, it does not remove");
    }

    @Test
    void aDuePositionIsReportedUntilItIsRemoved() {
        TemporaryFires fires = new TemporaryFires();
        fires.add(FIRST);
        advance(fires, TemporaryFires.LIFE_TICKS);
        for (int call = 0; call < 5; call++) {
            assertEquals(List.of(FIRST), fires.advance(), "still due, call " + call);
        }
        fires.remove(FIRST);
        assertEquals(List.of(), fires.advance());
    }

    @Test
    void eachPositionHasItsOwnCountdownAndTheOrderOfRegistrationIsKept() {
        TemporaryFires fires = new TemporaryFires();
        fires.add(FIRST);
        advance(fires, 50);
        fires.add(SECOND);
        advance(fires, 149);
        assertEquals(List.of(FIRST), fires.advance(), "the first is due at its 200th tick");
        advance(fires, 48);
        assertEquals(List.of(FIRST), fires.advance(), "the second has one tick left");
        assertEquals(List.of(FIRST, SECOND), fires.advance(), "both due, in the order they were added");
    }

    @Test
    void saveWritesPositionsAndTicksLeftInTheSameOrder() {
        TemporaryFires fires = new TemporaryFires();
        fires.add(FIRST);
        advance(fires, 10);
        fires.add(SECOND);

        CompoundTag tag = fires.save();
        assertArrayEquals(new long[] {FIRST.asLong(), SECOND.asLong()}, tag.getLongArray("Pos"));
        assertArrayEquals(new int[] {190, 200}, tag.getIntArray("Left"));
        assertEquals(2, tag.size(), "nothing else is written");
    }

    @Test
    void saveAndLoadRoundTrip() {
        TemporaryFires fires = new TemporaryFires();
        fires.add(FIRST);
        advance(fires, 10);
        fires.add(SECOND);

        TemporaryFires loaded = TemporaryFires.load(fires.save());
        assertEquals(2, loaded.size());
        assertFalse(loaded.add(FIRST), "the loaded list knows its positions");
        assertFalse(loaded.add(SECOND));
        advance(loaded, 189);
        assertEquals(List.of(FIRST), loaded.advance(), "190 ticks were left to the first");
        advance(loaded, 9);
        assertEquals(List.of(FIRST, SECOND), loaded.advance(), "200 ticks were left to the second");
        assertEquals(fires.save(), TemporaryFires.load(fires.save()).save(), "a second round trip changes nothing");
    }

    @Test
    void anEmptyListSavesAndLoadsEmpty() {
        CompoundTag tag = new TemporaryFires().save();
        assertEquals(0, tag.getLongArray("Pos").length);
        assertEquals(0, tag.getIntArray("Left").length);
        assertTrue(TemporaryFires.load(tag).isEmpty());
    }

    @Test
    void aMissingArrayLoadsEmpty() {
        assertTrue(TemporaryFires.load(new CompoundTag()).isEmpty());
        assertTrue(TemporaryFires.load(null).isEmpty());

        CompoundTag onlyPositions = new CompoundTag();
        onlyPositions.putLongArray("Pos", new long[] {FIRST.asLong()});
        assertTrue(TemporaryFires.load(onlyPositions).isEmpty());

        CompoundTag onlyTicks = new CompoundTag();
        onlyTicks.putIntArray("Left", new int[] {100});
        assertTrue(TemporaryFires.load(onlyTicks).isEmpty());
    }

    @Test
    void arraysOfUnequalLengthLoadEmpty() {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("Pos", new long[] {FIRST.asLong(), SECOND.asLong()});
        tag.putIntArray("Left", new int[] {100});
        assertTrue(TemporaryFires.load(tag).isEmpty());
    }

    @Test
    void arraysOfTheWrongKindLoadEmpty() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Pos", new int[] {1, 2});
        tag.putIntArray("Left", new int[] {100, 100});
        assertTrue(TemporaryFires.load(tag).isEmpty());
    }

    @Test
    void loadKeepsTheListWithinItsLimits() {
        long[] positions = new long[TemporaryFires.MAX_TEMPORARY_FIRES + 6];
        int[] ticks = new int[positions.length];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = new BlockPos(i, 64, 0).asLong();
            ticks[i] = 100;
        }
        CompoundTag tag = new CompoundTag();
        tag.putLongArray("Pos", positions);
        tag.putIntArray("Left", ticks);
        assertEquals(64, TemporaryFires.load(tag).size(), "entries past the cap are dropped");

        CompoundTag odd = new CompoundTag();
        odd.putLongArray("Pos", new long[] {FIRST.asLong(), FIRST.asLong(), SECOND.asLong()});
        odd.putIntArray("Left", new int[] {-7, 50, 100_000});
        TemporaryFires loaded = TemporaryFires.load(odd);
        assertEquals(2, loaded.size(), "a repeated position is listed once");
        assertEquals(List.of(FIRST), loaded.advance(), "negative ticks left count as none: due at once");
        assertArrayEquals(new int[] {0, 199}, loaded.save().getIntArray("Left"),
            "ticks left beyond the life time are cut to it, then one tick has passed");
    }
}
