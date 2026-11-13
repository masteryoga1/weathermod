package com.weathermod.server.impact;

import java.util.OptionalInt;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Finds the ground a vortex stands on without ever loading a chunk. Under an open sky the heightmap answers;
 * under a ceiling (the Nether) the heightmap reports the roof, so the column is scanned around a hint instead.
 * Water and lava count as ground, so a waterspout stands on the sea and a fire whirl on a lava lake.
 */
public final class GroundFinder {
    /** A vortex samples its ground once in this many ticks. */
    public static final int SAMPLE_INTERVAL_TICKS = 5;
    /** Under a ceiling the scan starts this far above the hint. */
    public static final int CEILING_SCAN_UP_BLOCKS = 8;
    /** Under a ceiling the scan goes this far below the hint. */
    public static final int CEILING_SCAN_DOWN_BLOCKS = 48;

    private GroundFinder() {
    }

    /**
     * The y of the first free block above the ground at a column; empty when the chunk is not loaded or, under a
     * ceiling, no floor lies within the scan. Never loads a chunk.
     *
     * <p>Without a ceiling this is one above the top block that stops motion or holds a fluid, leaves not
     * counted. With a ceiling the column is scanned with {@link #scan} from {@link #CEILING_SCAN_UP_BLOCKS} above
     * the hint to {@link #CEILING_SCAN_DOWN_BLOCKS} below it.
     *
     * @param hintY where the ground is expected: the last known ground height, a player's height or the sea level;
     *              only used under a ceiling
     */
    public static OptionalInt groundY(ServerLevel level, int x, int z, int hintY) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return OptionalInt.empty();
        }
        if (!level.dimensionType().hasCeiling()) {
            return OptionalInt.of(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, hintY, z);
        return scan(y -> isGround(chunk.getBlockState(pos.setY(y))), hintY, level.getMinBuildHeight(),
            CEILING_SCAN_UP_BLOCKS, CEILING_SCAN_DOWN_BLOCKS);
    }

    /**
     * The pure scan used under a ceiling: from {@code startY + maxUp} downward, the y above the first solid block
     * that has a free block above it; empty when none within {@code maxUp + maxDown} steps or below {@code minY}.
     * The lowest block examined is the higher of {@code startY - maxDown} and {@code minY}; negative distances
     * count as zero.
     *
     * @param solidAt whether the block at a height counts as ground
     */
    public static OptionalInt scan(IntPredicate solidAt, int startY, int minY, int maxUp, int maxDown) {
        long top = (long) startY + Math.max(0, maxUp);
        long bottom = Math.max(minY, (long) startY - Math.max(0, maxDown));
        if (top >= Integer.MAX_VALUE || top < bottom) {
            return OptionalInt.empty();
        }
        boolean freeAbove = !solidAt.test((int) top + 1);
        // Counted in long so that a scan down to the lowest int ends instead of wrapping around.
        for (long y = top; y >= bottom; y--) {
            boolean solid = solidAt.test((int) y);
            if (solid && freeAbove) {
                return OptionalInt.of((int) y + 1);
            }
            freeAbove = !solid;
        }
        return OptionalInt.empty();
    }

    /** What counts as ground in the scan: the block stops motion or holds a fluid. */
    public static boolean isGround(BlockState state) {
        return state.blocksMotion() || !state.getFluidState().isEmpty();
    }

    /**
     * Whether the block below a ground y holds water. False when the chunk is not loaded; never loads a chunk.
     *
     * @param groundY the y of the first free block above the ground, as {@link #groundY} returns it
     */
    public static boolean isWater(ServerLevel level, int x, int groundY, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) {
            return false;
        }
        return chunk.getFluidState(x, groundY - 1, z).is(FluidTags.WATER);
    }
}
