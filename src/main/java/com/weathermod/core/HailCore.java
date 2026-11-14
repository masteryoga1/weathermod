package com.weathermod.core;

/**
 * Where hail falls under a storm. Hail does not fall from the whole storm but from its forward flank: a disc of
 * {@link #CORE_RADIUS_FRACTION} of the storm radius whose centre lies {@link #CORE_OFFSET_FRACTION} of the radius
 * ahead of the storm centre, along the heading. The server's hail damage and the client's hail particles both ask
 * this class, so stones are drawn exactly where they hurt.
 */
public final class HailCore {
    /** The hail core is a disc of this fraction of the storm radius. */
    public static final float CORE_RADIUS_FRACTION = 0.4F;
    /** Its centre sits this fraction of the storm radius ahead of the storm centre, along the heading. */
    public static final float CORE_OFFSET_FRACTION = 0.3F;
    /** Inside this fraction of the core radius hail has full strength; it falls linearly to zero at the edge. */
    public static final float FULL_FRACTION = 0.7F;

    private HailCore() {
    }

    /** The x of the core's centre for a storm at {@code stormX} that moves along {@code headingDegrees}. */
    public static double centreX(double stormX, float headingDegrees, float radius) {
        return stormX + Headings.directionX(headingDegrees) * offset(radius);
    }

    /** The z of the core's centre for a storm at {@code stormZ} that moves along {@code headingDegrees}. */
    public static double centreZ(double stormZ, float headingDegrees, float radius) {
        return stormZ + Headings.directionZ(headingDegrees) * offset(radius);
    }

    /** The radius of the hail core of a storm with the given radius, in blocks; never negative. */
    public static float coreRadius(float radius) {
        return radius > 0.0F ? radius * CORE_RADIUS_FRACTION : 0.0F;
    }

    /**
     * The storm's hail strength scaled by the falloff at {@code (x, z)}: the full strength within
     * {@link #FULL_FRACTION} of the core radius from the core's centre, falling linearly to zero at the core's
     * edge, and zero outside the core. A storm without hail or without a radius gives zero everywhere.
     *
     * @param hail the storm's hail strength, 0..1; values outside are clamped
     */
    public static float strengthAt(double stormX, double stormZ, float headingDegrees, float radius, float hail,
                                   double x, double z) {
        if (!(hail > 0.0F) || !(radius > 0.0F)) {
            return 0.0F;
        }
        double core = coreRadius(radius);
        double dx = x - centreX(stormX, headingDegrees, radius);
        double dz = z - centreZ(stormZ, headingDegrees, radius);
        double distanceSqr = dx * dx + dz * dz;
        if (!(distanceSqr < core * core)) {
            return 0.0F;
        }
        float strength = Math.min(1.0F, hail);
        double full = core * FULL_FRACTION;
        double distance = Math.sqrt(distanceSqr);
        if (distance <= full) {
            return strength;
        }
        return (float) (strength * (core - distance) / (core - full));
    }

    private static double offset(float radius) {
        return radius > 0.0F ? (double) radius * CORE_OFFSET_FRACTION : 0.0;
    }
}
