package com.weathermod.server.director;

import com.weathermod.core.Headings;
import com.weathermod.core.Trigger;

/**
 * The front trigger of this milestone: a front is where the temperature anomaly changes quickly along the wind.
 * Pure. The director samples the climate field's anomaly upwind and downwind of a planned position and asks
 * {@link #frontAt}; later milestones replace the caller's use of this class with front systems of their own.
 */
public final class FrontMath {
    /** The anomaly is sampled this far upwind and downwind of the planned position. */
    public static final double SAMPLE_OFFSET_BLOCKS = 256.0;
    /** Smallest anomaly difference between the two samples that counts as a front. */
    public static final float FRONT_THRESHOLD = 0.08F;

    private FrontMath() {
    }

    /**
     * {@link Trigger#FRONT_COLD} when {@code upwindAnomaly - downwindAnomaly <= -FRONT_THRESHOLD} (colder air is
     * arriving), {@link Trigger#FRONT_WARM} when it is {@code >= FRONT_THRESHOLD}, otherwise {@code ""}. A
     * difference that is not a number gives {@code ""}.
     */
    public static String frontAt(float upwindAnomaly, float downwindAnomaly) {
        float difference = upwindAnomaly - downwindAnomaly;
        if (difference <= -FRONT_THRESHOLD) {
            return Trigger.FRONT_COLD;
        }
        if (difference >= FRONT_THRESHOLD) {
            return Trigger.FRONT_WARM;
        }
        return "";
    }

    /**
     * The upwind sample position along {@code X}:
     * {@code x - Headings.directionX(windDirectionDegrees) * SAMPLE_OFFSET_BLOCKS}.
     *
     * @param windDirectionDegrees the heading the wind blows toward
     */
    public static double upwindX(double x, float windDirectionDegrees) {
        return x - Headings.directionX(windDirectionDegrees) * SAMPLE_OFFSET_BLOCKS;
    }

    /** The upwind sample position along {@code Z}, {@link #SAMPLE_OFFSET_BLOCKS} against the wind. */
    public static double upwindZ(double z, float windDirectionDegrees) {
        return z - Headings.directionZ(windDirectionDegrees) * SAMPLE_OFFSET_BLOCKS;
    }

    /** The downwind sample position along {@code X}, {@link #SAMPLE_OFFSET_BLOCKS} with the wind. */
    public static double downwindX(double x, float windDirectionDegrees) {
        return x + Headings.directionX(windDirectionDegrees) * SAMPLE_OFFSET_BLOCKS;
    }

    /** The downwind sample position along {@code Z}, {@link #SAMPLE_OFFSET_BLOCKS} with the wind. */
    public static double downwindZ(double z, float windDirectionDegrees) {
        return z + Headings.directionZ(windDirectionDegrees) * SAMPLE_OFFSET_BLOCKS;
    }
}
