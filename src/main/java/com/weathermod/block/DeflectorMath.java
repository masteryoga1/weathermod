package com.weathermod.block;

import com.weathermod.core.Headings;
import java.util.Collection;
import net.minecraft.core.BlockPos;

/**
 * The level-free geometry of the weather deflector: pushing a storm centre out of the circles deflectors keep
 * clear, and turning a storm that heads into one onto the tangent so it slides around. A deflector's circle is
 * centred on the middle of its block and measured horizontally; its height plays no part. Everything here is pure
 * and unit tested.
 */
public final class DeflectorMath {
    /** A pushed-out point ends this far outside the circle. */
    public static final double EDGE_MARGIN_BLOCKS = 1.0;
    /** A point is pushed at most this many times in one call, so overlapping circles cannot trap the call. */
    public static final int MAX_PUSH_ITERATIONS = 4;
    /** A storm this near outside a circle and heading into it is turned onto the tangent. */
    public static final double SLIDE_BAND_BLOCKS = 2.0;

    /** Nearer to a centre than this a point has no bearing from it. */
    private static final double CENTRE_EPSILON_BLOCKS = 1.0E-6;
    /** A heading that misses the centre by less than this, as a sine, counts as heading exactly at it. */
    private static final double STRAIGHT_IN_EPSILON = 1.0E-9;
    private static final double BLOCK_CENTRE = 0.5;

    private DeflectorMath() {
    }

    /**
     * Moves a point out of every circle of radius {@code deflectorRadius + margin} around a deflector. Each step
     * takes the nearest deflector whose circle holds the point and moves the point straight away from it, to
     * {@link #EDGE_MARGIN_BLOCKS} outside that circle; a point exactly on a centre moves toward {@code +X}. The
     * step is repeated up to {@link #MAX_PUSH_ITERATIONS} times, because leaving one circle may enter another. A
     * point on a circle or outside it is not inside and is left alone.
     *
     * @param x               the point along {@code X}
     * @param z               the point along {@code Z}
     * @param margin          blocks added to the deflector radius, the reach of what must stay outside
     * @param deflectors      the deflectors to stay out of
     * @param deflectorRadius the radius a deflector protects, in blocks
     * @param out             receives the resulting point: {@code out[0] = x}, {@code out[1] = z}
     * @return whether the point moved
     */
    public static boolean pushOut(double x, double z, double margin, Collection<BlockPos> deflectors,
                                  double deflectorRadius, double[] out) {
        double radius = deflectorRadius + margin;
        double pointX = x;
        double pointZ = z;
        boolean moved = false;
        if (radius > 0.0 && !deflectors.isEmpty()) {
            double radiusSqr = radius * radius;
            double target = radius + EDGE_MARGIN_BLOCKS;
            for (int iteration = 0; iteration < MAX_PUSH_ITERATIONS; iteration++) {
                BlockPos nearest = null;
                double nearestSqr = radiusSqr;
                for (BlockPos deflector : deflectors) {
                    double dx = pointX - centreX(deflector);
                    double dz = pointZ - centreZ(deflector);
                    double distanceSqr = dx * dx + dz * dz;
                    if (distanceSqr < nearestSqr) {
                        nearest = deflector;
                        nearestSqr = distanceSqr;
                    }
                }
                if (nearest == null) {
                    break;
                }
                double centreX = centreX(nearest);
                double centreZ = centreZ(nearest);
                double distance = Math.sqrt(nearestSqr);
                if (distance < CENTRE_EPSILON_BLOCKS) {
                    pointX = centreX + target;
                    pointZ = centreZ;
                } else {
                    pointX = centreX + (pointX - centreX) / distance * target;
                    pointZ = centreZ + (pointZ - centreZ) / distance * target;
                }
                moved = true;
            }
        }
        out[0] = pointX;
        out[1] = pointZ;
        return moved;
    }

    /**
     * The heading a storm should take to pass a deflector instead of pressing against it. The deflector in
     * question is the nearest one that the storm is heading into (its heading has a positive component toward the
     * deflector) and whose circle of radius {@code deflectorRadius + margin} the storm is inside of or at most
     * {@link #EDGE_MARGIN_BLOCKS} plus {@link #SLIDE_BAND_BLOCKS} away from. The result is the one of the two
     * tangent directions of that circle that lies nearer to the storm's heading, wrapped into {@code [0, 360)}; a
     * storm heading exactly at the centre turns to its right. Without such a deflector the heading is returned
     * exactly as given.
     *
     * @param x               the storm centre along {@code X}
     * @param z               the storm centre along {@code Z}
     * @param headingDegrees  the storm's compass heading, see {@link Headings}
     * @param margin          blocks added to the deflector radius, the reach of what must stay outside
     * @param deflectors      the deflectors to pass
     * @param deflectorRadius the radius a deflector protects, in blocks
     */
    public static float slideHeading(double x, double z, float headingDegrees, double margin,
                                     Collection<BlockPos> deflectors, double deflectorRadius) {
        if (deflectors.isEmpty()) {
            return headingDegrees;
        }
        double reach = deflectorRadius + margin + EDGE_MARGIN_BLOCKS + SLIDE_BAND_BLOCKS;
        if (!(reach > 0.0)) {
            return headingDegrees;
        }
        double headingX = Headings.directionX(headingDegrees);
        double headingZ = Headings.directionZ(headingDegrees);
        double nearestSqr = reach * reach;
        double towardX = 0.0;
        double towardZ = 0.0;
        boolean found = false;
        for (BlockPos deflector : deflectors) {
            double dx = centreX(deflector) - x;
            double dz = centreZ(deflector) - z;
            double distanceSqr = dx * dx + dz * dz;
            if (distanceSqr > nearestSqr || distanceSqr < CENTRE_EPSILON_BLOCKS * CENTRE_EPSILON_BLOCKS) {
                continue;
            }
            if (!(headingX * dx + headingZ * dz > 0.0)) {
                continue;
            }
            if (!found || distanceSqr < nearestSqr) {
                found = true;
                nearestSqr = distanceSqr;
                towardX = dx;
                towardZ = dz;
            }
        }
        if (!found) {
            return headingDegrees;
        }
        // The direction toward the centre turned a quarter clockwise, seen from above: right of the heading for a
        // storm that heads straight in. The other tangent is its opposite. The tangent is as long as the storm is
        // far from the centre, so the comparison below is one of sines and rounding cannot decide a dead heat.
        double tangentX = -towardZ;
        double tangentZ = towardX;
        if (headingX * tangentX + headingZ * tangentZ < -STRAIGHT_IN_EPSILON * Math.sqrt(nearestSqr)) {
            tangentX = -tangentX;
            tangentZ = -tangentZ;
        }
        // Adding zero turns a negative zero, which the wrap lets through, into a plain one.
        return Headings.wrapDegrees((float) Math.toDegrees(Math.atan2(tangentX, -tangentZ))) + 0.0F;
    }

    private static double centreX(BlockPos deflector) {
        return deflector.getX() + BLOCK_CENTRE;
    }

    private static double centreZ(BlockPos deflector) {
        return deflector.getZ() + BLOCK_CENTRE;
    }
}
