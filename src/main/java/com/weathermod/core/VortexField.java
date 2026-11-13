package com.weathermod.core;

import net.minecraft.util.Mth;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * The force field of a vortex: a Rankine profile (a solid-body core in which the speed grows with the distance
 * from the axis, and a free vortex outside it in which the speed falls with that distance) around the axis a
 * {@link FunnelSpine} describes. It is the one force model of the mod: server entities, flying blocks, the local
 * player on the client and the debris particles all call {@link #acceleration}, so everything a vortex moves
 * moves the same way. Pure arithmetic; safe to call from the server and the client thread at once.
 */
public final class VortexField {
    /**
     * The vortex keeps this share of its size at zero strength: radii scale by
     * {@code MIN_SCALE + (1 - MIN_SCALE) * strength}.
     */
    public static final float MIN_SCALE = 0.5F;
    /** Tangential acceleration is at most pull times this. */
    public static final float TANGENTIAL_RATIO = 1.5F;
    /** Share of the missing tangential speed added per tick. */
    public static final float TANGENTIAL_RESPONSE = 0.15F;
    /** Lift acts within this many core radii of the axis. */
    public static final float LIFT_RADIUS_FACTOR = 1.5F;
    /**
     * Lift keeps this share at zero strength: it scales by
     * {@code LIFT_BASE_SHARE + (1 - LIFT_BASE_SHARE) * strength}.
     */
    public static final float LIFT_BASE_SHARE = 0.5F;
    /** Forces are full inside this fraction of the influence radius and fade linearly to zero at it. */
    public static final float EDGE_FADE_START = 0.7F;
    /** The field reaches this far above the funnel top. */
    public static final double REACH_ABOVE_BLOCKS = 8.0;
    /** The field reaches this far below the ground point. */
    public static final double REACH_BELOW_BLOCKS = 4.0;
    /** Share of the force that reaches an entity with a roof over it, by EF tier. */
    public static final float[] SHELTER_FACTOR = {0.0F, 0.0F, 0.2F, 0.4F, 0.7F, 1.0F};

    /** Closer to the axis than this a body has no direction to be pulled or turned in. */
    private static final double AXIS_EPSILON_BLOCKS = 1.0E-4;
    /** Lift never falls below this share of its value at the core radius, so the calm centre still carries. */
    private static final float MIN_LIFT_PROFILE = 0.5F;
    private static final float TICKS_PER_SECOND = 20.0F;

    private VortexField() {
    }

    /**
     * How large a vortex of the given strength is against its full size:
     * {@code MIN_SCALE + (1 - MIN_SCALE) * strength}, the strength clamped to 0..1.
     */
    public static float scale(float strength) {
        return MIN_SCALE + (1.0F - MIN_SCALE) * clampStrength(strength);
    }

    /** The radius beyond which a vortex of the given strength does nothing: the type's value times the scale. */
    public static float influenceRadius(VortexParams params, float strength) {
        return params.influenceRadius() * scale(strength);
    }

    /**
     * The Rankine profile: {@code r / coreRadius} inside the core, {@code coreRadius / r} outside, 0 at
     * {@code r = 0}. It peaks at 1 on the core radius. A core radius of zero or less gives 0 everywhere.
     */
    public static float rankine(double r, double coreRadius) {
        if (!(r > 0.0) || !(coreRadius > 0.0)) {
            return 0.0F;
        }
        return (float) (r < coreRadius ? r / coreRadius : coreRadius / r);
    }

    /**
     * How much of the force is left at a distance from the axis: 1 up to {@link #EDGE_FADE_START} of the influence
     * radius, falling linearly to 0 at the influence radius, 0 beyond it.
     */
    public static float edgeFade(double r, double influenceRadius) {
        if (!(influenceRadius > 0.0) || !(r < influenceRadius)) {
            return 0.0F;
        }
        double start = influenceRadius * EDGE_FADE_START;
        if (r <= start) {
            return 1.0F;
        }
        return (float) ((influenceRadius - r) / (influenceRadius - start));
    }

    /** 1 under open sky, {@link #SHELTER_FACTOR} of the tier (clamped to 0..5) otherwise. */
    public static float exposure(int tier, boolean underOpenSky) {
        return underOpenSky ? 1.0F : SHELTER_FACTOR[Mth.clamp(tier, 0, SHELTER_FACTOR.length - 1)];
    }

    /** {@code LIFT_BASE_SHARE + (1 - LIFT_BASE_SHARE) * strength}, the strength clamped to 0..1. */
    public static float liftScale(float strength) {
        return LIFT_BASE_SHARE + (1.0F - LIFT_BASE_SHARE) * clampStrength(strength);
    }

    /**
     * The acceleration, in blocks per tick squared, the vortex gives a body at {@code (x, y, z)} moving at
     * {@code (vx, vy, vz)} blocks per tick. {@code centreX} and {@code centreZ} are the storm centre the spine's
     * offsets are relative to. Returns false and writes zero when the spine is not grounded, its strength is zero,
     * or the body is outside the field.
     *
     * <p>The field reaches from {@link #REACH_BELOW_BLOCKS} below the ground point to {@link #REACH_ABOVE_BLOCKS}
     * above the top and out to the influence radius around the axis at the body's height. Inside it three parts
     * add up, each scaled by the exposure and the edge fade:
     * <ul>
     * <li>a pull toward the axis of {@code pull * rankine * strength};</li>
     * <li>a push along the tangent, counter-clockwise seen from above (a body east of the axis is turned north),
     * that closes {@link #TANGENTIAL_RESPONSE} of the gap to the target speed
     * {@code windSpeed / 20 * rankine * strength} per tick, never more than {@code pull * TANGENTIAL_RATIO} and
     * never against the body's motion;</li>
     * <li>lift of {@code lift * max(rankine, 0.5) * liftScale(strength)} within {@link #LIFT_RADIUS_FACTOR} core
     * radii of the axis, while the body rises slower than the rise cap and is lower above the ground point than
     * the eject height (the type's value times the scale, or the whole funnel when the type gives 0).</li>
     * </ul>
     *
     * @param exposure share of the force that reaches the body, see {@link #exposure(int, boolean)}
     * @param out      receives the acceleration; always written
     */
    public static boolean acceleration(VortexParams params, FunnelSpine spine, double centreX, double centreZ,
                                       double x, double y, double z, double vx, double vy, double vz,
                                       float exposure, Vector3d out) {
        out.set(0.0, 0.0, 0.0);
        float strength = spine.strength();
        if (!spine.grounded() || !(strength > 0.0F)) {
            return false;
        }
        double groundY = spine.groundY();
        double topY = spine.topY();
        if (y < groundY - REACH_BELOW_BLOCKS || y > topY + REACH_ABOVE_BLOCKS) {
            return false;
        }

        Vector3f axis = new Vector3f();
        spine.offsetAt(y, axis);
        double dx = x - (centreX + axis.x);
        double dz = z - (centreZ + axis.z);
        double r = Math.sqrt(dx * dx + dz * dz);
        float coreRadius = spine.radiusAt(y);
        double liftRadius = (double) coreRadius * LIFT_RADIUS_FACTOR;
        double reach = Math.max(influenceRadius(params, strength), liftRadius);
        if (r > reach) {
            return false;
        }

        float profile = rankine(r, coreRadius);
        float fade = exposure * edgeFade(r, reach);
        float k = strength * fade;
        if (!(k > 0.0F)) {
            return false;
        }

        double ax = 0.0;
        double ay = 0.0;
        double az = 0.0;
        if (r >= AXIS_EPSILON_BLOCKS) {
            double outwardX = dx / r;
            double outwardZ = dz / r;
            double inward = (double) params.pull() * profile * k;
            ax -= outwardX * inward;
            az -= outwardZ * inward;

            double tangentX = outwardZ;
            double tangentZ = -outwardX;
            double targetSpeed = (double) params.windSpeed() / TICKS_PER_SECOND * profile * strength;
            double currentSpeed = vx * tangentX + vz * tangentZ;
            double tangential = Mth.clamp((targetSpeed - currentSpeed) * TANGENTIAL_RESPONSE, 0.0,
                (double) params.pull() * TANGENTIAL_RATIO) * fade;
            ax += tangentX * tangential;
            az += tangentZ * tangential;
        }

        double ejectHeight = params.ejectHeight() > 0.0F
            ? (double) params.ejectHeight() * scale(strength)
            : topY - groundY;
        if (r <= liftRadius && vy < params.riseCap() && y - groundY < ejectHeight) {
            ay = (double) params.lift() * Math.max(profile, MIN_LIFT_PROFILE) * liftScale(strength) * fade;
        }

        out.set(ax, ay, az);
        return true;
    }

    private static float clampStrength(float strength) {
        return Float.isNaN(strength) ? 0.0F : Mth.clamp(strength, 0.0F, 1.0F);
    }
}
