package com.weathermod.server.family;

import com.weathermod.server.FamilyState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * What the convective family remembers about one storm: when it strikes next, where its hail schedule stands and
 * what became of its tornado rolls. Persisted inside the storm under {@code "Family"}; every key is optional on
 * load, so an empty tag gives the state of a storm that was just spawned. Downbursts and small vortices are
 * memoryless and keep nothing here.
 */
public final class ConvectiveState implements FamilyState {
    /** The value of a countdown that was not started yet. */
    public static final int NOT_SCHEDULED = -1;

    private static final String KEY_STRIKE_TICKS = "StrikeTicks";
    private static final String KEY_HAIL_BURST_TICKS = "HailBurstTicks";
    private static final String KEY_HAIL_GAP_TICKS = "HailGapTicks";
    private static final String KEY_TORNADO_ROLL_FAILED = "TornadoRollFailed";
    private static final String KEY_TORNADOES_SPAWNED = "TornadoesSpawned";

    /** Ticks until the next lightning strike; {@link #NOT_SCHEDULED} while none is scheduled. */
    public int strikeTicks = NOT_SCHEDULED;
    /** Remaining ticks of the running hail burst; zero while no burst runs. */
    public int hailBurstTicks;
    /** Ticks until the next hail burst; {@link #NOT_SCHEDULED} before the storm's first gap was drawn. */
    public int hailGapTicks = NOT_SCHEDULED;
    /** A tornado roll was made and failed; this storm rolls no more. */
    public boolean tornadoRollFailed;
    /** How many tornadoes this storm has produced. */
    public int tornadoesSpawned;

    /** A fresh state: no strike and no hail scheduled, no tornado rolled or spawned. */
    public ConvectiveState() {
    }

    /** Writes the state to a new compound tag; all five keys are always written. */
    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_STRIKE_TICKS, strikeTicks);
        tag.putInt(KEY_HAIL_BURST_TICKS, hailBurstTicks);
        tag.putInt(KEY_HAIL_GAP_TICKS, hailGapTicks);
        tag.putBoolean(KEY_TORNADO_ROLL_FAILED, tornadoRollFailed);
        tag.putInt(KEY_TORNADOES_SPAWNED, tornadoesSpawned);
        return tag;
    }

    /**
     * Reads a state written by {@link #save()}. Missing keys fall back to the fresh-state defaults, so an empty tag
     * gives a fresh state; a countdown below {@link #NOT_SCHEDULED} counts as not scheduled and negative counters
     * are clamped to zero, so a damaged tag still loads.
     */
    public static ConvectiveState load(CompoundTag tag) {
        ConvectiveState state = new ConvectiveState();
        if (tag.contains(KEY_STRIKE_TICKS, Tag.TAG_ANY_NUMERIC)) {
            state.strikeTicks = Math.max(NOT_SCHEDULED, tag.getInt(KEY_STRIKE_TICKS));
        }
        state.hailBurstTicks = Math.max(0, tag.getInt(KEY_HAIL_BURST_TICKS));
        if (tag.contains(KEY_HAIL_GAP_TICKS, Tag.TAG_ANY_NUMERIC)) {
            state.hailGapTicks = Math.max(NOT_SCHEDULED, tag.getInt(KEY_HAIL_GAP_TICKS));
        }
        state.tornadoRollFailed = tag.getBoolean(KEY_TORNADO_ROLL_FAILED);
        state.tornadoesSpawned = Math.max(0, tag.getInt(KEY_TORNADOES_SPAWNED));
        return state;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConvectiveState that)) {
            return false;
        }
        return strikeTicks == that.strikeTicks
            && hailBurstTicks == that.hailBurstTicks
            && hailGapTicks == that.hailGapTicks
            && tornadoRollFailed == that.tornadoRollFailed
            && tornadoesSpawned == that.tornadoesSpawned;
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(strikeTicks);
        result = 31 * result + Integer.hashCode(hailBurstTicks);
        result = 31 * result + Integer.hashCode(hailGapTicks);
        result = 31 * result + Boolean.hashCode(tornadoRollFailed);
        result = 31 * result + Integer.hashCode(tornadoesSpawned);
        return result;
    }

    @Override
    public String toString() {
        return "ConvectiveState[strikeTicks=" + strikeTicks + ", hailBurstTicks=" + hailBurstTicks
            + ", hailGapTicks=" + hailGapTicks + ", tornadoRollFailed=" + tornadoRollFailed
            + ", tornadoesSpawned=" + tornadoesSpawned + "]";
    }
}
