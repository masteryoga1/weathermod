package com.weathermod.server.impact;

import com.weathermod.WeatherMod;
import com.weathermod.core.ModTags;
import com.weathermod.server.family.ActiveVortex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

/**
 * World-space boxes in which no block is ripped: Create contraptions and glue near a vortex.
 *
 * <p>The boxes are the bounding boxes of the entities whose type is in {@code #weathermod:rip_shield}, grown by
 * {@link #PADDING_BLOCKS} and copied at the last scan. One scan of the level per second replaces a box query per
 * candidate block; a contraption that moves between two scans is covered by the padding and by the next scan.
 * When more shield entities are in reach than {@link #MAX_SHIELDS_PER_LEVEL}, the whole level counts as shielded
 * until a later scan finds fewer: the mod rather rips nothing than a machine.
 */
public final class ShieldVolumes {
    /** Boxes are grown by this much: anchors, tracks and supports touching the structure. */
    public static final double PADDING_BLOCKS = 1.0;
    /** Ticks between two scans of the level. */
    public static final int REFRESH_INTERVAL_TICKS = 20;
    /** Only shield entities this near the base of a vortex that acts on the world are kept. */
    public static final double KEEP_RANGE_BLOCKS = 160.0;
    /** More kept shield entities than this and the whole level counts as shielded until the next refresh. */
    public static final int MAX_SHIELDS_PER_LEVEL = 4096;

    private static final int BOX_STRIDE = 6;
    private static final double[] NO_BOUNDS = new double[0];

    /** The padded boxes, six numbers each: min x, y, z, then max x, y, z. */
    private double[] bounds = NO_BOUNDS;
    private int count;
    private boolean overflowing;
    /** The box around all boxes; meaningless while there is none. */
    private double allMinX;
    private double allMinY;
    private double allMinZ;
    private double allMaxX;
    private double allMaxY;
    private double allMaxZ;
    private boolean scanned;
    private int lastScanTick;

    /** Creates an empty set that shields nothing and scans at the first {@link #refreshIfDue}. */
    public ShieldVolumes() {
    }

    /**
     * Replaces the boxes; each is inflated by {@link #PADDING_BLOCKS} here.
     *
     * @param boxes    the bounding boxes of the shield entities found, without padding
     * @param overflow whether the scan hit the cap, which shields the whole level until the next replacement
     */
    public void replace(List<AABB> boxes, boolean overflow) {
        Objects.requireNonNull(boxes, "boxes");
        int size = boxes.size();
        double[] next = size == 0 ? NO_BOUNDS : new double[size * BOX_STRIDE];
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        int at = 0;
        for (AABB box : boxes) {
            Objects.requireNonNull(box, "box");
            double boxMinX = box.minX - PADDING_BLOCKS;
            double boxMinY = box.minY - PADDING_BLOCKS;
            double boxMinZ = box.minZ - PADDING_BLOCKS;
            double boxMaxX = box.maxX + PADDING_BLOCKS;
            double boxMaxY = box.maxY + PADDING_BLOCKS;
            double boxMaxZ = box.maxZ + PADDING_BLOCKS;
            next[at] = boxMinX;
            next[at + 1] = boxMinY;
            next[at + 2] = boxMinZ;
            next[at + 3] = boxMaxX;
            next[at + 4] = boxMaxY;
            next[at + 5] = boxMaxZ;
            at += BOX_STRIDE;
            minX = Math.min(minX, boxMinX);
            minY = Math.min(minY, boxMinY);
            minZ = Math.min(minZ, boxMinZ);
            maxX = Math.max(maxX, boxMaxX);
            maxY = Math.max(maxY, boxMaxY);
            maxZ = Math.max(maxZ, boxMaxZ);
        }
        this.bounds = next;
        this.count = size;
        this.overflowing = overflow;
        this.allMinX = minX;
        this.allMinY = minY;
        this.allMinZ = minZ;
        this.allMaxX = maxX;
        this.allMaxY = maxY;
        this.allMaxZ = maxZ;
    }

    /**
     * Whether a point lies in one of the padded boxes, their faces included. Always true while overflowing.
     */
    public boolean contains(double x, double y, double z) {
        if (overflowing) {
            return true;
        }
        if (count == 0 || x < allMinX || x > allMaxX || z < allMinZ || z > allMaxZ || y < allMinY || y > allMaxY) {
            return false;
        }
        double[] all = bounds;
        int end = count * BOX_STRIDE;
        for (int at = 0; at < end; at += BOX_STRIDE) {
            if (x >= all[at] && x <= all[at + 3] && z >= all[at + 2] && z <= all[at + 5]
                && y >= all[at + 1] && y <= all[at + 4]) {
                return true;
            }
        }
        return false;
    }

    /** True when nothing is shielded: there is no box and the set is not overflowing. */
    public boolean isEmpty() {
        return count == 0 && !overflowing;
    }

    /** The number of boxes held, whether or not the set is overflowing. */
    public int size() {
        return count;
    }

    /** Whether the last scan hit the cap, so that every position counts as shielded. */
    public boolean isOverflowing() {
        return overflowing;
    }

    /**
     * Whether a point is within {@link #KEEP_RANGE_BLOCKS} (horizontally, the limit included) of one of the given
     * bases; bases are x, z pairs. No bases, no range.
     */
    public static boolean inKeepRange(double x, double z, double[] bases) {
        if (bases == null) {
            return false;
        }
        double limit = KEEP_RANGE_BLOCKS * KEEP_RANGE_BLOCKS;
        for (int i = 0; i + 1 < bases.length; i += 2) {
            double dx = x - bases[i];
            double dz = z - bases[i + 1];
            if (dx * dx + dz * dz <= limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rescans when {@link #REFRESH_INTERVAL_TICKS} have passed since the last scan, and at the first call. One
     * whole-level scan, no per-block queries: every entity whose type is in {@code #weathermod:rip_shield} and
     * that is in keep range of the base of a vortex that acts on the world gives a box. With no such vortex the
     * boxes are cleared without a scan. When the scan finds more than {@link #MAX_SHIELDS_PER_LEVEL} entities the
     * set overflows; one warning is logged when that begins.
     *
     * @param level    the level to scan
     * @param tick     the level's weather tick
     * @param vortices the vortices published for the level
     */
    public void refreshIfDue(ServerLevel level, int tick, Collection<ActiveVortex> vortices) {
        if (scanned && tick >= lastScanTick && (long) tick - lastScanTick < REFRESH_INTERVAL_TICKS) {
            return;
        }
        scanned = true;
        lastScanTick = tick;

        double[] bases = actingBases(vortices);
        if (bases.length == 0) {
            replace(List.of(), false);
            return;
        }
        List<Entity> found = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(Entity.class),
            entity -> entity.getType().is(ModTags.EntityTypes.RIP_SHIELD)
                && inKeepRange(entity.getX(), entity.getZ(), bases),
            found, MAX_SHIELDS_PER_LEVEL + 1);
        boolean overflow = found.size() > MAX_SHIELDS_PER_LEVEL;
        boolean began = overflow && !overflowing;
        List<AABB> boxes = new ArrayList<>(found.size());
        for (Entity entity : found) {
            boxes.add(entity.getBoundingBox());
        }
        replace(boxes, overflow);
        if (began) {
            WeatherMod.LOGGER.warn("More than {} contraption or glue entities are within {} blocks of a vortex in {}:"
                    + " no block is ripped in this dimension until there are fewer",
                MAX_SHIELDS_PER_LEVEL, (int) KEEP_RANGE_BLOCKS, level.dimension().location());
        }
    }

    private static double[] actingBases(Collection<ActiveVortex> vortices) {
        int acting = 0;
        for (ActiveVortex vortex : vortices) {
            if (vortex.actsOnWorld()) {
                acting++;
            }
        }
        double[] bases = new double[acting * 2];
        int at = 0;
        for (ActiveVortex vortex : vortices) {
            if (vortex.actsOnWorld()) {
                bases[at] = vortex.baseX();
                bases[at + 1] = vortex.baseZ();
                at += 2;
            }
        }
        return bases;
    }
}
