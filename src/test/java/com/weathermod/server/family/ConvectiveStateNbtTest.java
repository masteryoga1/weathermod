package com.weathermod.server.family;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pins how the convective family state is saved: the keys, the round trip, the defaults an empty tag gives and
 * what becomes of values a damaged tag holds. Needs no Minecraft bootstrap.
 */
class ConvectiveStateNbtTest {
    private static ConvectiveState sample() {
        ConvectiveState state = new ConvectiveState();
        state.strikeTicks = 137;
        state.hailBurstTicks = 912;
        state.hailGapTicks = 0;
        state.tornadoRollFailed = true;
        state.tornadoesSpawned = 1;
        return state;
    }

    @Test
    void aFreshStateHasNothingScheduled() {
        ConvectiveState state = new ConvectiveState();
        assertEquals(-1, ConvectiveState.NOT_SCHEDULED);
        assertEquals(-1, state.strikeTicks);
        assertEquals(0, state.hailBurstTicks);
        assertEquals(-1, state.hailGapTicks);
        assertFalse(state.tornadoRollFailed);
        assertEquals(0, state.tornadoesSpawned);
    }

    @Test
    void anEmptyTagGivesTheDefaults() {
        ConvectiveState state = ConvectiveState.load(new CompoundTag());
        assertEquals(-1, state.strikeTicks);
        assertEquals(0, state.hailBurstTicks);
        assertEquals(-1, state.hailGapTicks);
        assertFalse(state.tornadoRollFailed);
        assertEquals(0, state.tornadoesSpawned);
        assertEquals(new ConvectiveState(), state);
    }

    @Test
    void aStateSurvivesSavingAndLoading() {
        ConvectiveState state = sample();
        ConvectiveState loaded = ConvectiveState.load(state.save());
        assertEquals(137, loaded.strikeTicks);
        assertEquals(912, loaded.hailBurstTicks);
        assertEquals(0, loaded.hailGapTicks);
        assertTrue(loaded.tornadoRollFailed);
        assertEquals(1, loaded.tornadoesSpawned);
        assertEquals(state, loaded);
        assertEquals(state.hashCode(), loaded.hashCode());
        assertEquals(state.save(), loaded.save());
    }

    @Test
    void aFreshStateSurvivesSavingAndLoading() {
        ConvectiveState fresh = new ConvectiveState();
        assertEquals(fresh, ConvectiveState.load(fresh.save()));
    }

    @Test
    void theTagUsesTheAgreedKeys() {
        CompoundTag tag = sample().save();
        assertEquals(5, tag.size());
        assertTrue(tag.contains("StrikeTicks", Tag.TAG_INT));
        assertTrue(tag.contains("HailBurstTicks", Tag.TAG_INT));
        assertTrue(tag.contains("HailGapTicks", Tag.TAG_INT));
        assertTrue(tag.contains("TornadoRollFailed", Tag.TAG_BYTE));
        assertTrue(tag.contains("TornadoesSpawned", Tag.TAG_INT));
        assertEquals(137, tag.getInt("StrikeTicks"));
        assertEquals(912, tag.getInt("HailBurstTicks"));
        assertEquals(0, tag.getInt("HailGapTicks"));
        assertTrue(tag.getBoolean("TornadoRollFailed"));
        assertEquals(1, tag.getInt("TornadoesSpawned"));
    }

    @Test
    void eachKeyIsReadOnItsOwn() {
        CompoundTag strike = new CompoundTag();
        strike.putInt("StrikeTicks", 40);
        assertEquals(40, ConvectiveState.load(strike).strikeTicks);
        assertEquals(-1, ConvectiveState.load(strike).hailGapTicks);

        CompoundTag gap = new CompoundTag();
        gap.putInt("HailGapTicks", 250);
        assertEquals(250, ConvectiveState.load(gap).hailGapTicks);
        assertEquals(-1, ConvectiveState.load(gap).strikeTicks);

        CompoundTag tornado = new CompoundTag();
        tornado.putInt("TornadoesSpawned", 2);
        assertEquals(2, ConvectiveState.load(tornado).tornadoesSpawned);
        assertFalse(ConvectiveState.load(tornado).tornadoRollFailed);
    }

    @Test
    void damagedValuesAreBroughtBackIntoRange() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("StrikeTicks", -50);
        tag.putInt("HailBurstTicks", -3);
        tag.putInt("HailGapTicks", -7);
        tag.putInt("TornadoesSpawned", -1);
        ConvectiveState state = ConvectiveState.load(tag);
        assertEquals(-1, state.strikeTicks);
        assertEquals(0, state.hailBurstTicks);
        assertEquals(-1, state.hailGapTicks);
        assertEquals(0, state.tornadoesSpawned);
    }

    @Test
    void aValueOfTheWrongKindCountsAsMissing() {
        CompoundTag tag = new CompoundTag();
        tag.putString("StrikeTicks", "soon");
        tag.putString("HailGapTicks", "later");
        ConvectiveState state = ConvectiveState.load(tag);
        assertEquals(-1, state.strikeTicks);
        assertEquals(-1, state.hailGapTicks);
    }

    @Test
    void everyFieldCountsForEquality() {
        ConvectiveState base = sample();
        assertEquals(base, sample());
        assertEquals(base.hashCode(), sample().hashCode());
        assertNotEquals(base, new Object());

        ConvectiveState other = sample();
        other.strikeTicks++;
        assertNotEquals(base, other);

        other = sample();
        other.hailBurstTicks++;
        assertNotEquals(base, other);

        other = sample();
        other.hailGapTicks = 5;
        assertNotEquals(base, other);

        other = sample();
        other.tornadoRollFailed = false;
        assertNotEquals(base, other);

        other = sample();
        other.tornadoesSpawned++;
        assertNotEquals(base, other);
    }

    @Test
    void theTextNamesEveryField() {
        assertEquals("ConvectiveState[strikeTicks=137, hailBurstTicks=912, hailGapTicks=0, tornadoRollFailed=true, "
            + "tornadoesSpawned=1]", sample().toString());
    }
}
