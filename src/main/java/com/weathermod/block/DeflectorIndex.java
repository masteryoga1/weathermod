package com.weathermod.block;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Every weather deflector of one dimension, in loaded and unloaded chunks alike. Owned and saved by
 * {@code DimensionWeatherData} (key {@code "Deflectors"}); maintained by the deflector block and its block entity.
 * Positions are bucketed in cells of {@link #CELL_BLOCKS} so a query looks at the cells its circle touches, not at
 * every deflector. A deflector counts from the middle of its block, horizontally; its height plays no part.
 */
public final class DeflectorIndex {
    /** The most deflectors one dimension remembers; further ones are placed but do nothing. */
    public static final int MAX_DEFLECTORS = 4096;
    /** Edge length of one bucket of the index, in blocks. */
    public static final int CELL_BLOCKS = 128;

    private static final List<BlockPos> NONE = List.of();
    private static final double BLOCK_CENTRE = 0.5;

    private final Set<BlockPos> positions = new LinkedHashSet<>();
    private final Collection<BlockPos> positionsView = Collections.unmodifiableCollection(positions);
    private final Long2ObjectOpenHashMap<List<BlockPos>> cells = new Long2ObjectOpenHashMap<>();

    /** Creates an empty index. */
    public DeflectorIndex() {
    }

    /**
     * Remembers a deflector. The position is copied, so a mutable one may be reused by the caller.
     *
     * @return false when the position is listed already or the index holds {@link #MAX_DEFLECTORS}
     */
    public boolean add(BlockPos pos) {
        if (positions.size() >= MAX_DEFLECTORS) {
            return false;
        }
        BlockPos key = pos.immutable();
        if (!positions.add(key)) {
            return false;
        }
        long cell = cellKey(cellOfBlock(key.getX()), cellOfBlock(key.getZ()));
        List<BlockPos> bucket = cells.get(cell);
        if (bucket == null) {
            bucket = new ArrayList<>(1);
            cells.put(cell, bucket);
        }
        bucket.add(key);
        return true;
    }

    /**
     * Forgets a deflector.
     *
     * @return false when the position was not listed
     */
    public boolean remove(BlockPos pos) {
        if (!positions.remove(pos)) {
            return false;
        }
        long cell = cellKey(cellOfBlock(pos.getX()), cellOfBlock(pos.getZ()));
        List<BlockPos> bucket = cells.get(cell);
        if (bucket != null) {
            bucket.remove(pos);
            if (bucket.isEmpty()) {
                cells.remove(cell);
            }
        }
        return true;
    }

    /** Whether a deflector is listed at the position. */
    public boolean contains(BlockPos pos) {
        return positions.contains(pos);
    }

    /** How many deflectors are listed. */
    public int size() {
        return positions.size();
    }

    /** Every listed position, in the order they were added. The view cannot be modified. */
    public Collection<BlockPos> positions() {
        return positionsView;
    }

    /**
     * The deflectors within {@code radius} blocks, horizontally, of a point; a deflector exactly {@code radius}
     * away is included. Returns a shared empty list when there is none, so the common miss allocates nothing; a
     * list with hits is the caller's own.
     *
     * @param x      the point along {@code X}
     * @param z      the point along {@code Z}
     * @param radius the distance to search, in blocks
     */
    public List<BlockPos> covering(double x, double z, double radius) {
        if (positions.isEmpty() || !(radius >= 0.0)) {
            return NONE;
        }
        double radiusSqr = radius * radius;
        // A deflector counts from the middle of its block, so one block more is searched on the low side.
        long minCellX = cellOfCoordinate(x - radius - 1.0);
        long maxCellX = cellOfCoordinate(x + radius);
        long minCellZ = cellOfCoordinate(z - radius - 1.0);
        long maxCellZ = cellOfCoordinate(z + radius);
        long spanX = maxCellX - minCellX + 1L;
        long spanZ = maxCellZ - minCellZ + 1L;
        int occupied = cells.size();
        List<BlockPos> hits = null;
        if (spanX > occupied || spanZ > occupied || spanX * spanZ > occupied) {
            // The circle spans more cells than hold a deflector: walking the deflectors is the shorter way.
            for (BlockPos pos : positions) {
                hits = collect(pos, x, z, radiusSqr, hits);
            }
        } else {
            for (long cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (long cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    List<BlockPos> bucket = cells.get(cellKey((int) cellX, (int) cellZ));
                    if (bucket == null) {
                        continue;
                    }
                    for (int i = 0; i < bucket.size(); i++) {
                        hits = collect(bucket.get(i), x, z, radiusSqr, hits);
                    }
                }
            }
        }
        return hits == null ? NONE : hits;
    }

    /** The positions packed with {@code BlockPos.asLong}, in the order they were added. */
    public long[] toLongArray() {
        long[] packed = new long[positions.size()];
        int i = 0;
        for (BlockPos pos : positions) {
            packed[i++] = pos.asLong();
        }
        return packed;
    }

    /**
     * Rebuilds an index from {@link #toLongArray()}. Duplicates are dropped, and so is every entry past
     * {@link #MAX_DEFLECTORS}.
     */
    public static DeflectorIndex fromLongArray(long[] positions) {
        DeflectorIndex index = new DeflectorIndex();
        for (long packed : positions) {
            index.add(BlockPos.of(packed));
        }
        return index;
    }

    private static List<BlockPos> collect(BlockPos pos, double x, double z, double radiusSqr, List<BlockPos> hits) {
        double dx = pos.getX() + BLOCK_CENTRE - x;
        double dz = pos.getZ() + BLOCK_CENTRE - z;
        if (!(dx * dx + dz * dz <= radiusSqr)) {
            return hits;
        }
        List<BlockPos> result = hits == null ? new ArrayList<>(2) : hits;
        result.add(pos);
        return result;
    }

    private static int cellOfBlock(int block) {
        return Math.floorDiv(block, CELL_BLOCKS);
    }

    /** The cell of a coordinate; the cast pins a coordinate beyond the int range to the outermost cell. */
    private static int cellOfCoordinate(double coordinate) {
        return (int) Math.floor(coordinate / CELL_BLOCKS);
    }

    private static long cellKey(int cellX, int cellZ) {
        return (long) cellX << 32 | cellZ & 0xFFFFFFFFL;
    }
}
