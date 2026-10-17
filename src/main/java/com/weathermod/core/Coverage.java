package com.weathermod.core;

import net.minecraft.util.Mth;

/**
 * How strongly a storm affects a point at some distance from its centre: full intensity inside the inner
 * {@link #FULL_FRACTION} of the radius, a smooth fall to nothing at the radius, nothing beyond it. Server systems and
 * client visuals share this so the vanilla rain switch, deposition and rendering agree on where a storm ends.
 */
public final class Coverage {
    /** Inside {@code radius * FULL_FRACTION} the coverage is the storm's full intensity. */
    public static final float FULL_FRACTION = 0.6F;

    private Coverage() {
    }

    /**
     * The distance falloff alone, from 1 inside {@code FULL_FRACTION * radius} through a smoothstep down to 0 at
     * {@code radius}, and 0 beyond. A radius that is not positive, or a distance that is not a number, gives 0.
     */
    public static float falloff(double distance, float radius) {
        if (!(radius > 0.0F) || Double.isNaN(distance)) {
            return 0.0F;
        }
        double full = radius * (double) FULL_FRACTION;
        if (distance <= full) {
            return 1.0F;
        }
        if (distance >= radius) {
            return 0.0F;
        }
        double t = (distance - full) / (radius - full);
        return (float) (1.0 - Mth.smoothstep(t));
    }

    /** The intensity scaled by {@link #falloff(double, float)} and clamped to 0..1. */
    public static float coverage(float intensity, double distance, float radius) {
        if (Float.isNaN(intensity)) {
            return 0.0F;
        }
        return Mth.clamp(intensity * falloff(distance, radius), 0.0F, 1.0F);
    }
}
