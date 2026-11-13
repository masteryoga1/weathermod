package com.weathermod.server.impact;

import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;

/**
 * Fires the mod lit on a block where fire never burns out by itself (netherrack, magma, soul sand): each is
 * removed after {@link #LIFE_TICKS}. Owned and saved by {@code DimensionWeatherData}, ticked by the simulator.
 *
 * <p>Removing the mod's own fire needs no event, because the fire was placed through
 * {@link ProtectedActions#place}; a fire a player has meanwhile put out or replaced is not a fire block any more
 * and is left alone. Positions are kept in the order they were added.
 */
public final class TemporaryFires {
    /** Fires remembered at once; a fire lit while the list is full is not lit at all by the caller. */
    public static final int MAX_TEMPORARY_FIRES = 64;
    /** Ticks a remembered fire burns before it is removed. */
    public static final int LIFE_TICKS = 200;

    private static final String KEY_POS = "Pos";
    private static final String KEY_LEFT = "Left";

    /** Ticks left by packed position, in the order of registration. */
    private final Long2IntLinkedOpenHashMap left = new Long2IntLinkedOpenHashMap();

    /** Creates an empty list. */
    public TemporaryFires() {
    }

    /** Registers a position with {@link #LIFE_TICKS} left; false when full or already listed. */
    public boolean add(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        long key = pos.asLong();
        if (left.size() >= MAX_TEMPORARY_FIRES || left.containsKey(key)) {
            return false;
        }
        left.put(key, LIFE_TICKS);
        return true;
    }

    /** Forgets a position; does nothing when it is not listed. */
    public void remove(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        left.remove(pos.asLong());
    }

    /** The number of positions listed. */
    public int size() {
        return left.size();
    }

    /** Whether no position is listed. */
    public boolean isEmpty() {
        return left.isEmpty();
    }

    /**
     * Counts every entry down by one tick (not below zero) and returns the positions that have reached zero, in
     * the order they were added. A position stays listed, and is returned again by every later call, until it is
     * removed.
     */
    public List<BlockPos> advance() {
        if (left.isEmpty()) {
            return List.of();
        }
        List<BlockPos> due = new ArrayList<>();
        for (long key : left.keySet().toLongArray()) {
            int ticks = left.get(key);
            if (ticks > 0) {
                ticks--;
                left.put(key, ticks);
            }
            if (ticks == 0) {
                due.add(BlockPos.of(key));
            }
        }
        return due;
    }

    /**
     * Advances the list by one tick and puts out what is due: every due position whose chunk and neighbouring
     * columns are loaded is dropped from the list, and its block removed with {@code level.removeBlock(pos, false)}
     * when it is still a fire. Due positions that are not loaded stay listed and are tried again every tick.
     *
     * @param level the level the fires burn in
     */
    public void tick(ServerLevel level) {
        List<BlockPos> due = advance();
        for (BlockPos pos : due) {
            if (!ProtectedActions.surroundingsLoaded(level, pos)) {
                continue;
            }
            left.remove(pos.asLong());
            if (level.getBlockState(pos).getBlock() instanceof BaseFireBlock) {
                level.removeBlock(pos, false);
            }
        }
    }

    /**
     * Writes the list: long array {@code Pos} (packed positions, {@link BlockPos#asLong()}) and int array
     * {@code Left} (ticks left), in the same order.
     */
    public CompoundTag save() {
        long[] positions = new long[left.size()];
        int[] ticks = new int[positions.length];
        int at = 0;
        for (long key : left.keySet().toLongArray()) {
            positions[at] = key;
            ticks[at] = left.get(key);
            at++;
        }
        CompoundTag tag = new CompoundTag();
        tag.putLongArray(KEY_POS, positions);
        tag.putIntArray(KEY_LEFT, ticks);
        return tag;
    }

    /**
     * Reads a list written by {@link #save()}. Tolerant: a missing array, arrays of unequal length or a tag that
     * is null give an empty list; repeated positions and entries past {@link #MAX_TEMPORARY_FIRES} are dropped;
     * ticks left are kept within 0 to {@link #LIFE_TICKS}.
     */
    public static TemporaryFires load(CompoundTag tag) {
        TemporaryFires fires = new TemporaryFires();
        if (tag == null || !tag.contains(KEY_POS, Tag.TAG_LONG_ARRAY) || !tag.contains(KEY_LEFT, Tag.TAG_INT_ARRAY)) {
            return fires;
        }
        long[] positions = tag.getLongArray(KEY_POS);
        int[] ticks = tag.getIntArray(KEY_LEFT);
        if (positions.length != ticks.length) {
            return fires;
        }
        for (int i = 0; i < positions.length && fires.left.size() < MAX_TEMPORARY_FIRES; i++) {
            if (!fires.left.containsKey(positions[i])) {
                fires.left.put(positions[i], Math.max(0, Math.min(LIFE_TICKS, ticks[i])));
            }
        }
        return fires;
    }
}
