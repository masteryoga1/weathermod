package com.weathermod.server.director;

import com.weathermod.core.Headings;
import net.minecraft.util.RandomSource;

/**
 * Pure geometry for the storm director: where to put a new storm so the wind carries it over the player it was
 * planned for, and how far a storm's heading may be turned toward the people who would see it. Headings follow
 * {@link Headings}: 0 is north, 90 is east.
 */
public final class SpawnPlanner {
    /** Nearest spawn distance as a fraction of the type's interest radius. */
    public static final float MIN_DISTANCE_FRACTION = 0.55F;
    /** Width of the random band added to {@link #MIN_DISTANCE_FRACTION}, so the farthest spawn is at 0.80. */
    public static final float DISTANCE_SPREAD_FRACTION = 0.25F;
    /** Farthest spawn distance as a fraction of the interest radius. */
    public static final float MAX_DISTANCE_FRACTION = MIN_DISTANCE_FRACTION + DISTANCE_SPREAD_FRACTION;
    /** Largest sideways offset, perpendicular to the wind, as a fraction of the interest radius. */
    public static final float LATERAL_JITTER_FRACTION = 0.3F;

    private SpawnPlanner() {
    }

    /**
     * A planned spawn: the horizontal position and the heading the storm starts with.
     *
     * @param x              the storm centre along {@code X}
     * @param z              the storm centre along {@code Z}
     * @param headingDegrees the initial compass heading, in {@code [0, 360)}
     */
    public record Plan(double x, double z, float headingDegrees) {
    }

    /**
     * Plans a spawn upwind of an anchor: between {@link #MIN_DISTANCE_FRACTION} and {@link #MAX_DISTANCE_FRACTION}
     * of the interest radius in the direction the wind comes from, pushed sideways by up to
     * {@link #LATERAL_JITTER_FRACTION} of the interest radius, heading where the wind blows. The random source is
     * read twice: once for the distance, once for the sideways offset.
     */
    public static Plan upwind(double anchorX, double anchorZ, float windDirectionDegrees, float interestRadius,
                              RandomSource random) {
        float heading = Headings.wrapDegrees(windDirectionDegrees);
        float radius = Math.max(0.0F, interestRadius);
        double distance = radius * (MIN_DISTANCE_FRACTION + DISTANCE_SPREAD_FRACTION * random.nextFloat());
        double lateral = radius * LATERAL_JITTER_FRACTION * (random.nextFloat() * 2.0F - 1.0F);

        float from = Headings.wrapDegrees(heading + 180.0F);
        float side = Headings.wrapDegrees(heading + 90.0F);
        double x = anchorX + Headings.directionX(from) * distance + Headings.directionX(side) * lateral;
        double z = anchorZ + Headings.directionZ(from) * distance + Headings.directionZ(side) * lateral;
        return new Plan(x, z, heading);
    }

    /**
     * Turns a heading toward the bearing from one point to another by at most {@code maxBiasDegrees}. The two points
     * being the same leaves the heading alone. The result is wrapped into {@code [0, 360)}.
     */
    public static float biasHeading(float headingDegrees, double fromX, double fromZ, double toX, double toZ,
                                    float maxBiasDegrees) {
        float heading = Headings.wrapDegrees(headingDegrees);
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (dx == 0.0D && dz == 0.0D) {
            return heading;
        }
        float limit = Math.abs(maxBiasDegrees);
        float turn = signedDifference(heading, bearing(fromX, fromZ, toX, toZ));
        float clamped = Math.max(-limit, Math.min(limit, turn));
        return Headings.wrapDegrees(heading + clamped);
    }

    /**
     * The compass bearing from one point to another, in {@code [0, 360)}, following the {@link Headings}
     * convention. Two identical points give 0.
     */
    public static float bearing(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (dx == 0.0D && dz == 0.0D) {
            return 0.0F;
        }
        return Headings.wrapDegrees((float) Math.toDegrees(Math.atan2(dx, -dz)));
    }

    /**
     * The shortest signed turn from one heading to another, in {@code [-180, 180)}: positive turns clockwise
     * (toward larger headings), negative counter-clockwise.
     */
    public static float signedDifference(float fromDegrees, float toDegrees) {
        float difference = Headings.wrapDegrees(toDegrees - fromDegrees);
        return difference >= 180.0F ? difference - 360.0F : difference;
    }
}
