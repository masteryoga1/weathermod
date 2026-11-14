package com.weathermod.server.family;

import com.weathermod.core.Headings;
import com.weathermod.core.VortexField;
import com.weathermod.core.VortexParams;
import net.minecraft.util.Mth;

/**
 * The pure rules of the vortex family: how far a funnel has come down, where a tornado sits under its parent and
 * how it is led there, how the tracked ground and the dig reference follow the terrain, and how tall a funnel is.
 * Everything {@link VortexSystem} decides without looking at the world is decided here.
 */
public final class VortexMath {
    /** Below this intensity the funnel has not reached the ground: no forces, no ripping. */
    public static final float GROUNDED_INTENSITY = 0.3F;
    /** A natural tornado hangs under its parent at least this long before it may touch down (20 s). */
    public static final int TOUCHDOWN_DELAY_MIN_TICKS = 400;
    /** A natural tornado hangs under its parent at most this long before it may touch down (30 s). */
    public static final int TOUCHDOWN_DELAY_MAX_TICKS = 600;
    /** A grounded natural tornado below its target tier grows one tier every this many ticks (20 s). */
    public static final int TIER_STEP_TICKS = 400;
    /** A funnel that reaches the cloud base is at least this tall, so mountains do not flatten it. */
    public static final int MIN_FUNNEL_HEIGHT_BLOCKS = 40;
    /** A tornado sits this fraction of the parent's radius from its centre. */
    public static final float TORNADO_OFFSET_FRACTION = 0.35F;
    /** A tornado sits at this angle, clockwise, from its parent's heading: the rear right flank. */
    public static final float TORNADO_OFFSET_ANGLE_DEGREES = 135.0F;
    /** Within this fraction of the parent's radius of its anchor a child simply moves with the parent. */
    public static final float LEASH_FRACTION = 0.25F;
    /** A leashed child turns by at most this much per tick. */
    public static final float LEASH_TURN_DEGREES_PER_TICK = 3.0F;
    /** The tracked ground height follows the terrain by at most this much per tick. */
    public static final double GROUND_FOLLOW_BLOCKS_PER_TICK = 0.5;
    /**
     * The dig reference is sampled this many base radii, plus the margin, ahead of the storm centre: beyond
     * everything the ripper can reach (a rip disc of 1.25 base radii around a base that wanders 0.5 base radii).
     */
    public static final float DIG_PROBE_RADIUS_FACTOR = 2.0F;
    /** Blocks added to the dig probe distance, see {@link #DIG_PROBE_RADIUS_FACTOR}. */
    public static final double DIG_PROBE_MARGIN_BLOCKS = 2.0;
    /** The dig reference may fall by at most this many blocks per block the storm travels. */
    public static final double DIG_SLOPE_BLOCKS_PER_BLOCK = 1.0;
    /** A waterspout over land for this long ends. */
    public static final int OFF_SURFACE_TICKS = 100;
    /** Fire whirls try to light one block this often. */
    public static final int FIRE_ATTEMPT_INTERVAL_TICKS = 10;

    private VortexMath() {
    }

    /**
     * The spot under a parent storm where its tornado belongs: {@link #TORNADO_OFFSET_FRACTION} of the parent's
     * radius from its centre, {@link #TORNADO_OFFSET_ANGLE_DEGREES} clockwise from its heading. Writes
     * {@code out[0] = x} and {@code out[1] = z}.
     */
    public static void tornadoAnchor(double parentX, double parentZ, float parentHeadingDegrees, float parentRadius,
                                     double[] out) {
        float bearing = parentHeadingDegrees + TORNADO_OFFSET_ANGLE_DEGREES;
        double distance = (double) TORNADO_OFFSET_FRACTION * parentRadius;
        out[0] = parentX + Headings.directionX(bearing) * distance;
        out[1] = parentZ + Headings.directionZ(bearing) * distance;
    }

    /**
     * {@code current} moved toward {@code target} by at most {@code maxStep}; the target itself once it is within
     * reach. A negative step counts as zero. A target that is not a number leaves {@code current} alone, and a
     * {@code current} that is not a number jumps to the target.
     */
    public static double approach(double current, double target, double maxStep) {
        if (Double.isNaN(target)) {
            return current;
        }
        if (Double.isNaN(current)) {
            return target;
        }
        double step = Math.max(0.0, maxStep);
        double difference = target - current;
        if (Math.abs(difference) <= step) {
            return target;
        }
        return current + Math.signum(difference) * step;
    }

    /**
     * How far the funnel has come down, 0..1: {@code min(clamp(intensity / GROUNDED_INTENSITY, 0, 1),
     * touchdownDelayTicks <= 0 ? 1 : clamp(ageTicks / (float) touchdownDelayTicks, 0, 1))}. The funnel follows the
     * storm's intensity and, for a natural tornado, is held aloft until the storm is old enough.
     */
    public static float descent(float intensity, int ageTicks, int touchdownDelayTicks) {
        float byIntensity = Mth.clamp(intensity / GROUNDED_INTENSITY, 0.0F, 1.0F);
        float byAge = touchdownDelayTicks <= 0
            ? 1.0F
            : Mth.clamp(ageTicks / (float) touchdownDelayTicks, 0.0F, 1.0F);
        return Math.min(byIntensity, byAge);
    }

    /** Whether a funnel with this descent stands on the ground: {@code descent >= 1}. */
    public static boolean grounded(float descent) {
        return descent >= 1.0F;
    }

    /**
     * {@code fromDegrees} turned toward {@code toDegrees} the short way by at most {@code maxStepDegrees}; wrapped
     * into {@code [0, 360)}. Exactly opposite headings turn anticlockwise.
     */
    public static float turnToward(float fromDegrees, float toDegrees, float maxStepDegrees) {
        float maxStep = Math.abs(maxStepDegrees);
        float difference = Mth.wrapDegrees(toDegrees - fromDegrees);
        if (Math.abs(difference) <= maxStep) {
            return Headings.wrapDegrees(toDegrees);
        }
        return Headings.wrapDegrees(fromDegrees + Math.signum(difference) * maxStep);
    }

    /**
     * Motion of a child on its parent's leash; writes {@code out[0]} = heading in degrees, {@code out[1]} = speed
     * in blocks per second. Within {@link #LEASH_FRACTION} {@code * parentRadius} of the anchor the target is the
     * parent's heading and speed; farther out it is the bearing to the anchor at {@code maxSpeed}. The heading is
     * {@code turnToward(currentHeadingDegrees, target heading, LEASH_TURN_DEGREES_PER_TICK)}.
     */
    public static void childMotion(double x, double z, float currentHeadingDegrees, double anchorX, double anchorZ,
                                   float parentHeadingDegrees, float parentSpeed, float parentRadius,
                                   float maxSpeed, float[] out) {
        double dx = anchorX - x;
        double dz = anchorZ - z;
        double leash = (double) LEASH_FRACTION * parentRadius;
        float targetHeading;
        float speed;
        if (dx * dx + dz * dz <= leash * leash) {
            targetHeading = parentHeadingDegrees;
            speed = parentSpeed;
        } else {
            targetHeading = (float) Math.toDegrees(Math.atan2(dx, -dz));
            speed = maxSpeed;
        }
        out[0] = turnToward(currentHeadingDegrees, targetHeading, LEASH_TURN_DEGREES_PER_TICK);
        out[1] = speed;
    }

    /** True when the child is farther than {@code parentRadius} from its anchor: the parent has left it behind. */
    public static boolean lostParent(double x, double z, double anchorX, double anchorZ, float parentRadius) {
        double dx = anchorX - x;
        double dz = anchorZ - z;
        return dx * dx + dz * dz > (double) parentRadius * parentRadius;
    }

    /**
     * The terrain height the dig limit is measured from after one more sample: {@code previous} when
     * {@code sample} is NaN; {@code sample} when {@code previous} is NaN; else {@code max(sample, previous -
     * travelledBlocks * DIG_SLOPE_BLOCKS_PER_BLOCK)}. The reference rises at once and falls only as fast as the
     * storm travels, so a parked vortex keeps the reference it arrived with.
     */
    public static double digReference(double previous, double sample, double travelledBlocks) {
        if (Double.isNaN(sample)) {
            return previous;
        }
        if (Double.isNaN(previous)) {
            return sample;
        }
        return Math.max(sample, previous - Math.max(0.0, travelledBlocks) * DIG_SLOPE_BLOCKS_PER_BLOCK);
    }

    /**
     * Top of the funnel: {@code max(cloudBaseY, groundY + MIN_FUNNEL_HEIGHT_BLOCKS)} when the type reaches the
     * cloud base, otherwise {@code groundY + lerp(height range, heightFraction) * VortexField.scale(strength)}.
     *
     * @param heightFraction where in the type's height range this funnel lies, 0..1; clamped
     */
    public static double topY(VortexParams params, double groundY, double cloudBaseY, float heightFraction,
                              float strength) {
        if (params.reachesCloudBase()) {
            return Math.max(cloudBaseY, groundY + MIN_FUNNEL_HEIGHT_BLOCKS);
        }
        float fraction = Float.isNaN(heightFraction) ? 0.0F : Mth.clamp(heightFraction, 0.0F, 1.0F);
        return groundY + (double) params.height().lerp(fraction) * VortexField.scale(strength);
    }
}
