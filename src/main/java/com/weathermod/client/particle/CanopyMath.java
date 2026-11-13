package com.weathermod.client.particle;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Pure rules for where the wind can tear a leaf off a tree: a column sheds only when its highest block is a leaf
 * block open to the sky, either off the top face of the canopy or off its underside, and only where the leaf has a
 * free block to fly into. Sampling whole columns through the heightmap, instead of single blocks in a volume, is
 * what lets a few tries per tick find the canopy. The level is reached through two small lookups, so the rules are
 * unit-tested against canopies written out in a few lines.
 */
public final class CanopyMath {
    /** Height above the top face of a canopy a leaf appears at, so its collision box starts clear of the block. */
    public static final double LEAF_LIFT = 0.05D;
    /**
     * How far below the bottom of the lowest leaf block an underside leaf appears; far enough that neither its
     * collision box nor its drawn quad reaches back into the leaf layer.
     */
    public static final double LEAF_DROP = 0.3D;
    /** A leaf appears at least this far inside its column, so its collision box never straddles two columns. */
    public static final double LEAF_EDGE_MARGIN = 0.15D;
    /** Thickest canopy, in leaf blocks, the search walks down through to reach the underside. */
    public static final int LEAF_SCAN_DEPTH = 8;

    /** What the canopy rules need to know about one block. */
    public enum BlockKind {
        /** Nothing a leaf could hit. */
        AIR,
        /** A leaf block. */
        LEAVES,
        /** Anything else: ground, logs, snow, water. */
        OTHER
    }

    /** Answers the height of a column: the y of the first block above its highest motion-blocking block. */
    @FunctionalInterface
    public interface HeightLookup {
        /** The heightmap value of a column, as {@code Level.getHeight} reports it. */
        int heightAt(int blockX, int blockZ);
    }

    /** Answers what kind of block sits at a position. */
    @FunctionalInterface
    public interface BlockLookup {
        /** The kind of the block at a position. */
        BlockKind kindAt(int blockX, int blockY, int blockZ);
    }

    /** Receives each leaf {@link #shedLeaves} tears off. */
    @FunctionalInterface
    public interface LeafSink {
        /**
         * Called once per leaf.
         *
         * @param x       x the leaf appears at
         * @param y       height the leaf appears at
         * @param z       z the leaf appears at
         * @param fromTop true when it leaves the top face of the canopy, false when it drops from the underside
         */
        void accept(double x, double y, double z, boolean fromTop);
    }

    /**
     * A place a leaf can come loose.
     *
     * @param y       the height the leaf appears at
     * @param fromTop true when it leaves the top face of the canopy, false when it drops from the underside
     */
    public record LeafSite(double y, boolean fromTop) {
    }

    private CanopyMath() {
    }

    /**
     * Moves a coordinate toward the middle of its block: the position inside the block is squeezed from
     * {@code [0, 1)} into {@code [LEAF_EDGE_MARGIN, 1 - LEAF_EDGE_MARGIN)}, the block itself stays the same.
     */
    public static double insetInBlock(double coordinate) {
        int block = Mth.floor(coordinate);
        return block + LEAF_EDGE_MARGIN + (coordinate - block) * (1.0D - 2.0D * LEAF_EDGE_MARGIN);
    }

    /**
     * Tears up to {@code wanted} leaves off the canopy around the camera. It tests at most
     * {@code wanted * EmitterMath.LEAF_TRIES_PER_LEAF} random columns in the ring between
     * {@link EmitterMath#LEAF_MIN_DISTANCE} and {@link EmitterMath#LEAF_DISTANCE} and stops as soon as enough of
     * them answered, so a forest costs little more than one try per leaf and open country a fixed handful of
     * heightmap reads. {@link EmitterMath#LEAF_TOP_SHARE} of the tries look at the top face of the canopy first,
     * the rest at its underside, and a leaf only appears within {@link EmitterMath#LEAF_BELOW_CAMERA} below and
     * {@link EmitterMath#LEAF_ABOVE_CAMERA} above the camera, which keeps it inside the 32-block distance the
     * vanilla particle path accepts.
     *
     * @param random    the source of the random columns
     * @param heights   the heightmap of the level
     * @param blocks    the blocks of the level
     * @param camX      camera x
     * @param camY      camera y
     * @param camZ      camera z
     * @param downwindX unit x component of the direction the wind blows toward
     * @param downwindZ unit z component of the direction the wind blows toward
     * @param wanted    the number of leaves this tick asks for; nothing happens when it is not positive
     * @param sink      receives each leaf
     * @return the number of leaves handed to the sink, never more than {@code wanted}
     */
    public static int shedLeaves(RandomSource random, HeightLookup heights, BlockLookup blocks, double camX,
                                 double camY, double camZ, double downwindX, double downwindZ, int wanted,
                                 LeafSink sink) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(sink, "sink");
        double lowestY = camY - EmitterMath.LEAF_BELOW_CAMERA;
        double highestY = camY + EmitterMath.LEAF_ABOVE_CAMERA;
        int shed = 0;
        for (int tries = wanted * EmitterMath.LEAF_TRIES_PER_LEAF; tries > 0 && shed < wanted; tries--) {
            double[] offset = EmitterMath.ringOffset(random, EmitterMath.LEAF_MIN_DISTANCE,
                EmitterMath.LEAF_DISTANCE);
            double x = insetInBlock(camX + offset[0]);
            double z = insetInBlock(camZ + offset[1]);
            boolean preferTop = random.nextFloat() < EmitterMath.LEAF_TOP_SHARE;
            Optional<LeafSite> site = leafSite(heights, blocks, x, z, downwindX, downwindZ, preferTop, lowestY,
                highestY);
            if (site.isPresent()) {
                sink.accept(x, site.get().y(), z, site.get().fromTop());
                shed++;
            }
        }
        return shed;
    }

    /**
     * Finds where the column under a point can shed a leaf. The column's highest motion-blocking block must be a
     * leaf block, which rules out trees under a roof and columns that are not loaded. The top face sheds when the
     * block above it and the block one step downwind at that level are air; the underside sheds when the canopy is
     * at most {@link #LEAF_SCAN_DEPTH} leaf blocks thick there, with air below it and one step downwind at that
     * level. The preferred face is tried first and the other one when it cannot shed. Either way the leaf must
     * appear between the two given heights.
     *
     * @param heights    the heightmap of the level
     * @param blocks     the blocks of the level
     * @param x          x of the point the leaf would appear at
     * @param z          z of the point the leaf would appear at
     * @param downwindX  unit x component of the direction the wind blows toward
     * @param downwindZ  unit z component of the direction the wind blows toward
     * @param preferTop  whether to try the top face before the underside
     * @param lowestY    lowest height a leaf may appear at
     * @param highestY   highest height a leaf may appear at
     * @return the site, or empty when this column sheds nothing
     */
    public static Optional<LeafSite> leafSite(HeightLookup heights, BlockLookup blocks, double x, double z,
                                              double downwindX, double downwindZ, boolean preferTop,
                                              double lowestY, double highestY) {
        Objects.requireNonNull(heights, "heights");
        Objects.requireNonNull(blocks, "blocks");
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        int top = heights.heightAt(blockX, blockZ) - 1;
        if (blocks.kindAt(blockX, top, blockZ) != BlockKind.LEAVES) {
            return Optional.empty();
        }
        int aheadX = Mth.floor(x + downwindX);
        int aheadZ = Mth.floor(z + downwindZ);
        boolean fromTop = preferTop;
        double y = faceY(blocks, blockX, top, blockZ, aheadX, aheadZ, fromTop, lowestY, highestY);
        if (Double.isNaN(y)) {
            fromTop = !preferTop;
            y = faceY(blocks, blockX, top, blockZ, aheadX, aheadZ, fromTop, lowestY, highestY);
        }
        return Double.isNaN(y) ? Optional.empty() : Optional.of(new LeafSite(y, fromTop));
    }

    /** The height a leaf appears at off one face of a canopy column, or NaN when that face cannot shed. */
    private static double faceY(BlockLookup blocks, int blockX, int top, int blockZ, int aheadX, int aheadZ,
                                boolean fromTop, double lowestY, double highestY) {
        int freeY;
        double y;
        if (fromTop) {
            freeY = top + 1;
            y = freeY + LEAF_LIFT;
        } else {
            int lowest = lowestLeaf(blocks, blockX, top, blockZ);
            if (lowest > top) {
                return Double.NaN;
            }
            freeY = lowest - 1;
            y = lowest - LEAF_DROP;
        }
        if (y < lowestY || y > highestY) {
            return Double.NaN;
        }
        if (blocks.kindAt(blockX, freeY, blockZ) != BlockKind.AIR) {
            return Double.NaN;
        }
        boolean sameColumn = aheadX == blockX && aheadZ == blockZ;
        if (!sameColumn && blocks.kindAt(aheadX, freeY, aheadZ) != BlockKind.AIR) {
            return Double.NaN;
        }
        return y;
    }

    /**
     * The y of the lowest leaf block of the unbroken run of leaves hanging from the top of a column, or a value
     * above {@code top} when the run is more than {@link #LEAF_SCAN_DEPTH} blocks thick.
     */
    private static int lowestLeaf(BlockLookup blocks, int blockX, int top, int blockZ) {
        int lowest = top;
        for (int thickness = 1; blocks.kindAt(blockX, lowest - 1, blockZ) == BlockKind.LEAVES; thickness++) {
            if (thickness >= LEAF_SCAN_DEPTH) {
                return top + 1;
            }
            lowest--;
        }
        return lowest;
    }
}
