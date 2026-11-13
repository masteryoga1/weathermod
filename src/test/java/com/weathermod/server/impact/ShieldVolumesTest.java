package com.weathermod.server.impact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/**
 * Pins the shield volumes as far as they can be tested without a level: the padding of the boxes, the overflow
 * that shields everything, the empty set, the box count and the keep range around the bases of vortices. The scan
 * itself needs a level with entities and is part of the smoke test.
 */
class ShieldVolumesTest {
    private static final AABB UNIT_BOX = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

    @Test
    void theConstantsAreTheAgreedOnes() {
        assertEquals(1.0, ShieldVolumes.PADDING_BLOCKS);
        assertEquals(20, ShieldVolumes.REFRESH_INTERVAL_TICKS);
        assertEquals(160.0, ShieldVolumes.KEEP_RANGE_BLOCKS);
        assertEquals(4096, ShieldVolumes.MAX_SHIELDS_PER_LEVEL);
    }

    @Test
    void aNewSetShieldsNothing() {
        ShieldVolumes shields = new ShieldVolumes();
        assertTrue(shields.isEmpty());
        assertEquals(0, shields.size());
        assertFalse(shields.isOverflowing());
        assertFalse(shields.contains(0.0, 0.0, 0.0));
        assertFalse(shields.contains(1234.5, 64.0, -987.5));
    }

    @Test
    void aBoxIsGrownByThePaddingOnEverySide() {
        ShieldVolumes shields = new ShieldVolumes();
        shields.replace(List.of(UNIT_BOX), false);

        assertFalse(shields.isEmpty());
        assertEquals(1, shields.size());
        assertTrue(shields.contains(0.5, 0.5, 0.5), "inside the box itself");
        assertTrue(shields.contains(1.9, 0.5, 0.5), "in the padding east of it");
        assertTrue(shields.contains(-0.9, 0.5, 0.5), "in the padding west of it");
        assertTrue(shields.contains(0.5, 1.9, 0.5), "in the padding above it");
        assertTrue(shields.contains(0.5, -0.9, 0.5), "in the padding below it");
        assertTrue(shields.contains(0.5, 0.5, 1.9), "in the padding south of it");
        assertTrue(shields.contains(0.5, 0.5, -0.9), "in the padding north of it");
        assertTrue(shields.contains(2.0, 2.0, 2.0), "the padded faces belong to the box");
        assertTrue(shields.contains(-1.0, -1.0, -1.0), "the padded faces belong to the box");

        assertFalse(shields.contains(2.1, 0.5, 0.5), "beyond the padding east of it");
        assertFalse(shields.contains(-1.1, 0.5, 0.5), "beyond the padding west of it");
        assertFalse(shields.contains(0.5, 2.1, 0.5), "beyond the padding above it");
        assertFalse(shields.contains(0.5, -1.1, 0.5), "beyond the padding below it");
        assertFalse(shields.contains(0.5, 0.5, 2.1), "beyond the padding south of it");
        assertFalse(shields.contains(0.5, 0.5, -1.1), "beyond the padding north of it");
    }

    @Test
    void theBlockNextToAContraptionIsShieldedAndTheOneAfterItIsNot() {
        ShieldVolumes shields = new ShieldVolumes();
        shields.replace(List.of(new AABB(10.0, 64.0, 10.0, 13.0, 67.0, 13.0)), false);

        assertTrue(shields.contains(13.5, 64.5, 11.5), "the centre of the block touching the east face");
        assertFalse(shields.contains(14.5, 64.5, 11.5), "the centre of the block after that");
        assertTrue(shields.contains(11.5, 63.5, 11.5), "the centre of the block it stands on");
        assertFalse(shields.contains(11.5, 62.5, 11.5), "the centre of the block under that");
    }

    @Test
    void aPointBetweenTwoBoxesIsNotShielded() {
        ShieldVolumes shields = new ShieldVolumes();
        shields.replace(List.of(UNIT_BOX, new AABB(100.0, 0.0, 100.0, 101.0, 1.0, 101.0)), false);

        assertEquals(2, shields.size());
        assertTrue(shields.contains(0.5, 0.5, 0.5));
        assertTrue(shields.contains(100.5, 0.5, 100.5));
        assertFalse(shields.contains(50.0, 0.5, 50.0), "inside the box around both, inside neither");
        assertFalse(shields.contains(100.5, 0.5, 0.5), "east of the first, north of the second");
    }

    @Test
    void anOverflowShieldsEverything() {
        ShieldVolumes shields = new ShieldVolumes();
        shields.replace(List.of(), true);

        assertTrue(shields.isOverflowing());
        assertFalse(shields.isEmpty(), "a set that shields everything is not empty");
        assertEquals(0, shields.size());
        assertTrue(shields.contains(0.0, 0.0, 0.0));
        assertTrue(shields.contains(-30000.0, 320.0, 30000.0));
    }

    @Test
    void anOverflowKeepsCountingItsBoxes() {
        List<AABB> boxes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            boxes.add(new AABB(i * 10.0, 0.0, 0.0, i * 10.0 + 1.0, 1.0, 1.0));
        }
        ShieldVolumes shields = new ShieldVolumes();
        shields.replace(boxes, true);

        assertEquals(5, shields.size());
        assertTrue(shields.contains(5.0, 50.0, 5.0), "outside every box, shielded all the same");
    }

    @Test
    void replacingEndsAnOverflowAndDropsTheOldBoxes() {
        ShieldVolumes shields = new ShieldVolumes();
        shields.replace(List.of(UNIT_BOX), true);
        shields.replace(List.of(new AABB(20.0, 0.0, 20.0, 21.0, 1.0, 21.0)), false);

        assertFalse(shields.isOverflowing());
        assertEquals(1, shields.size());
        assertFalse(shields.contains(0.5, 0.5, 0.5), "the old box is gone");
        assertTrue(shields.contains(20.5, 0.5, 20.5));

        shields.replace(List.of(), false);
        assertTrue(shields.isEmpty());
        assertEquals(0, shields.size());
        assertFalse(shields.contains(20.5, 0.5, 20.5));
    }

    @Test
    void theKeepRangeEndsAt160Blocks() {
        double[] bases = {100.0, -50.0};
        assertTrue(ShieldVolumes.inKeepRange(100.0, -50.0, bases), "on the base");
        assertTrue(ShieldVolumes.inKeepRange(260.0, -50.0, bases), "160 blocks east");
        assertFalse(ShieldVolumes.inKeepRange(261.0, -50.0, bases), "161 blocks east");
        assertTrue(ShieldVolumes.inKeepRange(100.0, -210.0, bases), "160 blocks north");
        assertFalse(ShieldVolumes.inKeepRange(100.0, -211.0, bases), "161 blocks north");
    }

    @Test
    void theKeepRangeIsACircle() {
        double[] bases = {0.0, 0.0};
        assertTrue(ShieldVolumes.inKeepRange(96.0, 128.0, bases), "96 and 128 make exactly 160");
        assertFalse(ShieldVolumes.inKeepRange(97.0, 128.0, bases));
        assertFalse(ShieldVolumes.inKeepRange(120.0, 120.0, bases), "inside the square, outside the circle");
    }

    @Test
    void anyBaseInRangeKeepsAShield() {
        double[] bases = {0.0, 0.0, 1000.0, 1000.0};
        assertTrue(ShieldVolumes.inKeepRange(1100.0, 1000.0, bases), "near the second base");
        assertTrue(ShieldVolumes.inKeepRange(-100.0, 0.0, bases), "near the first base");
        assertFalse(ShieldVolumes.inKeepRange(500.0, 500.0, bases), "between the two, near neither");
    }

    @Test
    void withoutBasesNothingIsInRange() {
        assertFalse(ShieldVolumes.inKeepRange(0.0, 0.0, new double[0]));
        assertFalse(ShieldVolumes.inKeepRange(0.0, 0.0, null));
    }
}
