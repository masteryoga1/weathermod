package com.weathermod.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Checks the dimension's list of weather deflectors: adding, removing, duplicates and the cap, the search around a
 * point across the borders of the index's cells and below zero, and the long array the list is saved as. A
 * deflector counts from the middle of its block, so the one at block {@code (0, y, 0)} lies at {@code (0.5, 0.5)}.
 * Needs no Minecraft bootstrap.
 */
class DeflectorIndexTest {
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);
    /** Deflectors far from every query, there so the index holds more cells than a small query touches. */
    private static final List<BlockPos> BYSTANDERS = List.of(
        new BlockPos(2000, 64, 2000), new BlockPos(-2000, 64, 2000), new BlockPos(2000, 64, -2000),
        new BlockPos(-2000, 64, -2000), new BlockPos(2000, 64, 0), new BlockPos(-2000, 64, 0));

    @Test
    void constantsAreTheNumbersOfTheDesign() {
        assertEquals(4096, DeflectorIndex.MAX_DEFLECTORS);
        assertEquals(128, DeflectorIndex.CELL_BLOCKS);
    }

    @Test
    void addRemembersAPositionOnce() {
        DeflectorIndex index = new DeflectorIndex();
        assertEquals(0, index.size());
        assertFalse(index.contains(ORIGIN));

        assertTrue(index.add(ORIGIN));
        assertTrue(index.contains(ORIGIN));
        assertEquals(1, index.size());

        assertFalse(index.add(ORIGIN), "a listed position is not added again");
        assertFalse(index.add(new BlockPos(0, 64, 0)));
        assertEquals(1, index.size());
        assertEquals(List.of(ORIGIN), index.covering(0.5, 0.5, 1.0));

        assertTrue(index.add(new BlockPos(0, 65, 0)), "another height is another block");
        assertEquals(2, index.size());
    }

    @Test
    void removeForgetsAPosition() {
        DeflectorIndex index = new DeflectorIndex();
        BlockPos other = new BlockPos(3, 64, 0);
        index.add(ORIGIN);
        index.add(other);

        assertTrue(index.remove(ORIGIN));
        assertFalse(index.contains(ORIGIN));
        assertEquals(1, index.size());
        assertEquals(List.of(other), index.covering(0.5, 0.5, 10.0), "only the other one is left in the cell");

        assertFalse(index.remove(ORIGIN), "it was not there any more");
        assertTrue(index.remove(other));
        assertEquals(0, index.size());
        assertTrue(index.covering(0.5, 0.5, 10.0).isEmpty());

        assertTrue(index.add(ORIGIN), "a removed position can be added again");
        assertEquals(List.of(ORIGIN), index.covering(0.5, 0.5, 10.0));
    }

    @Test
    void addCopiesAMutablePosition() {
        DeflectorIndex index = new DeflectorIndex();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(1, 2, 3);
        assertTrue(index.add(cursor));
        cursor.set(9, 9, 9);

        assertTrue(index.contains(new BlockPos(1, 2, 3)));
        assertFalse(index.contains(new BlockPos(9, 9, 9)));
        assertEquals(List.of(new BlockPos(1, 2, 3)), List.copyOf(index.positions()));
    }

    @Test
    void theIndexStopsAtTheCap() {
        DeflectorIndex index = new DeflectorIndex();
        for (int i = 0; i < DeflectorIndex.MAX_DEFLECTORS; i++) {
            assertTrue(index.add(gridPosition(i)), "deflector " + i);
        }
        assertEquals(DeflectorIndex.MAX_DEFLECTORS, index.size());

        BlockPos extra = new BlockPos(-5, 64, -5);
        assertFalse(index.add(extra), "the index is full");
        assertFalse(index.contains(extra));
        assertEquals(DeflectorIndex.MAX_DEFLECTORS, index.size());
        assertTrue(index.covering(-4.5, -4.5, 1.0).isEmpty(), "a refused deflector covers nothing");

        assertTrue(index.remove(gridPosition(17)));
        assertTrue(index.add(extra), "there is room again");
        assertEquals(DeflectorIndex.MAX_DEFLECTORS, index.size());
    }

    @Test
    void positionsIsALiveUnmodifiableViewInTheOrderOfAdding() {
        DeflectorIndex index = new DeflectorIndex();
        BlockPos first = new BlockPos(500, 70, -3);
        BlockPos second = new BlockPos(-40, 12, 9);
        BlockPos third = new BlockPos(1, 1, 1);
        index.add(first);
        index.add(second);
        assertEquals(List.of(first, second), List.copyOf(index.positions()));

        index.add(third);
        index.remove(first);
        assertEquals(List.of(second, third), List.copyOf(index.positions()), "the view follows the index");

        assertThrows(UnsupportedOperationException.class, () -> index.positions().add(first));
        assertThrows(UnsupportedOperationException.class, () -> index.positions().remove(second));
        assertThrows(UnsupportedOperationException.class, () -> index.positions().clear());
        assertEquals(2, index.size());
    }

    @Test
    void coveringMeasuresHorizontallyFromTheMiddleOfTheBlockAndIncludesTheRadius() {
        DeflectorIndex index = new DeflectorIndex();
        index.add(ORIGIN);

        assertEquals(List.of(ORIGIN), index.covering(48.5, 0.5, 48.0), "exactly 48 blocks away");
        assertTrue(index.covering(48.75, 0.5, 48.0).isEmpty());
        // 30 and 40 are the legs of a triangle with a hypotenuse of exactly 50.
        assertEquals(List.of(ORIGIN), index.covering(-29.5, 40.5, 50.0));
        assertTrue(index.covering(-29.5, 40.5, 49.5).isEmpty());
        assertEquals(List.of(ORIGIN), index.covering(0.5, 0.5, 0.0), "a search without a radius finds the spot");

        DeflectorIndex deep = new DeflectorIndex();
        BlockPos bedrock = new BlockPos(0, -64, 0);
        deep.add(bedrock);
        assertEquals(List.of(bedrock), deep.covering(48.5, 0.5, 48.0), "height plays no part");
    }

    @Test
    void coveringReachesAcrossACellBorder() {
        // Blocks 127 and 128 lie in neighbouring cells of 128 blocks.
        DeflectorIndex index = new DeflectorIndex();
        BlockPos west = new BlockPos(127, 64, 10);
        BlockPos east = new BlockPos(128, 64, 10);
        BlockPos far = new BlockPos(300, 64, 10);
        index.add(west);
        index.add(east);
        index.add(far);
        BYSTANDERS.forEach(index::add);

        assertEquals(Set.of(west, east), Set.copyOf(index.covering(130.5, 10.5, 5.0)));
        assertEquals(Set.of(west, east), Set.copyOf(index.covering(125.5, 10.5, 3.0)));
        assertEquals(List.of(east), index.covering(130.5, 10.5, 2.5));
        assertEquals(List.of(west), index.covering(125.5, 10.5, 2.5));
        assertTrue(index.covering(100.5, 10.5, 5.0).isEmpty());

        // And across a border along z, with the query in a third cell.
        BlockPos north = new BlockPos(260, 64, -1);
        BlockPos south = new BlockPos(260, 64, 0);
        index.add(north);
        index.add(south);
        assertEquals(Set.of(north, south), Set.copyOf(index.covering(255.5, 0.0, 6.0)));
    }

    @Test
    void coveringWorksForNegativeCoordinates() {
        DeflectorIndex index = new DeflectorIndex();
        BlockPos justBelowZero = new BlockPos(-1, 64, -1);
        BlockPos cellEdge = new BlockPos(-128, 64, -128);
        BlockPos nextCell = new BlockPos(-129, 64, -129);
        index.add(ORIGIN);
        index.add(justBelowZero);
        index.add(cellEdge);
        index.add(nextCell);
        BYSTANDERS.forEach(index::add);

        // (-0.5, -0.5) is the middle of block -1; block 0 is the square root of two away.
        assertEquals(Set.of(justBelowZero, ORIGIN), Set.copyOf(index.covering(-0.5, -0.5, 1.5)));
        assertEquals(List.of(justBelowZero), index.covering(-0.5, -0.5, 1.0));
        // (-128, -128) is the corner between blocks -128 and -129, which lie in different cells.
        assertEquals(Set.of(cellEdge, nextCell), Set.copyOf(index.covering(-128.0, -128.0, 1.0)));
        assertEquals(List.of(cellEdge), index.covering(-120.5, -127.5, 7.0));
        assertEquals(List.of(nextCell), index.covering(-135.5, -128.5, 7.0));
        assertTrue(index.covering(-64.5, -64.5, 40.0).isEmpty());
    }

    @Test
    void coveringAgreesWithACheckOfEveryDeflector() {
        Random random = new Random(20240921L);
        DeflectorIndex index = new DeflectorIndex();
        List<BlockPos> all = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            BlockPos pos = new BlockPos(random.nextInt(2001) - 1000, random.nextInt(320) - 64,
                random.nextInt(2001) - 1000);
            if (index.add(pos)) {
                all.add(pos);
            }
        }
        // Small circles are answered from the cells, large ones by walking the list; both must agree with this.
        double[] radii = {0.0, 8.0, 48.0, 51.0, 130.0, 700.0, 5000.0, Double.POSITIVE_INFINITY};
        for (double radius : radii) {
            for (int query = 0; query < 40; query++) {
                double x = random.nextDouble() * 2400.0 - 1200.0;
                double z = random.nextDouble() * 2400.0 - 1200.0;
                Set<BlockPos> expected = new HashSet<>();
                for (BlockPos pos : all) {
                    double dx = pos.getX() + 0.5 - x;
                    double dz = pos.getZ() + 0.5 - z;
                    if (dx * dx + dz * dz <= radius * radius) {
                        expected.add(pos);
                    }
                }
                List<BlockPos> found = index.covering(x, z, radius);
                assertEquals(expected.size(), found.size(), "no deflector is listed twice");
                assertEquals(expected, Set.copyOf(found), "radius " + radius + " around " + x + ", " + z);
            }
        }
    }

    @Test
    void coveringWithoutAHitReturnsOneSharedEmptyList() {
        DeflectorIndex empty = new DeflectorIndex();
        List<BlockPos> none = empty.covering(0.0, 0.0, 10.0);
        assertTrue(none.isEmpty());
        assertSame(none, empty.covering(5.0, 5.0, 100.0));

        DeflectorIndex index = new DeflectorIndex();
        index.add(ORIGIN);
        BYSTANDERS.forEach(index::add);
        assertSame(none, index.covering(600.5, 600.5, 48.0), "a miss among the cells allocates nothing");
        // The nearest deflector is 848.5 blocks away; a circle this wide is answered from the list.
        assertSame(none, index.covering(600.5, 600.5, 800.0), "nor does a miss on the list");
        assertSame(none, index.covering(0.5, 0.5, -1.0), "a negative radius covers nothing");
        assertSame(none, index.covering(0.5, 0.5, Double.NaN));
        assertSame(none, index.covering(Double.NaN, 0.5, 48.0));
    }

    @Test
    void aListOfHitsBelongsToTheCaller() {
        DeflectorIndex index = new DeflectorIndex();
        index.add(ORIGIN);
        List<BlockPos> hits = index.covering(0.5, 0.5, 48.0);
        hits.clear();

        assertEquals(1, index.size());
        assertEquals(List.of(ORIGIN), index.covering(0.5, 0.5, 48.0));
    }

    @Test
    void theLongArrayRoundTripKeepsEveryPositionInOrder() {
        DeflectorIndex index = new DeflectorIndex();
        BlockPos first = new BlockPos(100, 70, -200);
        BlockPos second = new BlockPos(-30000000, -64, 29999999);
        BlockPos third = new BlockPos(0, 319, 0);
        index.add(first);
        index.add(second);
        index.add(third);

        long[] packed = index.toLongArray();
        assertArrayEquals(new long[] {first.asLong(), second.asLong(), third.asLong()}, packed);

        DeflectorIndex restored = DeflectorIndex.fromLongArray(packed);
        assertEquals(3, restored.size());
        assertEquals(List.of(first, second, third), List.copyOf(restored.positions()));
        assertArrayEquals(packed, restored.toLongArray());
        assertEquals(List.of(first), restored.covering(100.5, -199.5, 48.0), "the cells are rebuilt as well");
        assertEquals(List.of(second), restored.covering(-29999999.5, 29999999.5, 48.0));

        assertEquals(0, new DeflectorIndex().toLongArray().length);
        assertEquals(0, DeflectorIndex.fromLongArray(new long[0]).size());
    }

    @Test
    void fromLongArrayDropsDuplicatesAndEntriesPastTheCap() {
        long origin = ORIGIN.asLong();
        long other = new BlockPos(7, 64, 7).asLong();
        DeflectorIndex deduplicated = DeflectorIndex.fromLongArray(new long[] {origin, other, origin, other, origin});
        assertEquals(2, deduplicated.size());
        assertArrayEquals(new long[] {origin, other}, deduplicated.toLongArray());

        int tooMany = DeflectorIndex.MAX_DEFLECTORS + 4;
        long[] packed = new long[tooMany];
        for (int i = 0; i < tooMany; i++) {
            packed[i] = gridPosition(i).asLong();
        }
        DeflectorIndex capped = DeflectorIndex.fromLongArray(packed);
        assertEquals(DeflectorIndex.MAX_DEFLECTORS, capped.size());
        assertTrue(capped.contains(gridPosition(0)));
        assertTrue(capped.contains(gridPosition(DeflectorIndex.MAX_DEFLECTORS - 1)));
        assertFalse(capped.contains(gridPosition(DeflectorIndex.MAX_DEFLECTORS)));
        assertFalse(capped.contains(gridPosition(tooMany - 1)));
    }

    /** A different position for every index: a grid 64 blocks wide with four blocks between neighbours. */
    private static BlockPos gridPosition(int index) {
        return new BlockPos(index % 64 * 4, 64, index / 64 * 4);
    }
}
