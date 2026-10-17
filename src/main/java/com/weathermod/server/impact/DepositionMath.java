package com.weathermod.server.impact;

import com.weathermod.core.PrecipitationKind;
import net.minecraft.util.RandomSource;

/**
 * The level-free part of snow and ice deposition: the temperature thresholds, the snow layer cap, the coverage
 * acceptance roll and the uniform pick of a point under a storm. Everything here is pure and unit tested.
 */
public final class DepositionMath {
    /**
     * Water freezes at surface temperatures strictly below this: {@link PrecipitationKind#SNOW_BELOW} plus a small
     * margin, which lands on the 0.15 vanilla uses for its own snow and ice.
     */
    public static final float FREEZE_BELOW = PrecipitationKind.SNOW_BELOW + 0.05F;
    /** The most layers a snow block holds, the same as {@code SnowLayerBlock.MAX_HEIGHT}. */
    public static final int MAX_SNOW_LAYERS = 8;

    private DepositionMath() {
    }

    /** True when snow settles at a surface temperature: strictly below {@link PrecipitationKind#SNOW_BELOW}. */
    public static boolean coldEnoughToSnow(float temperatureAtSurface) {
        return temperatureAtSurface < PrecipitationKind.SNOW_BELOW;
    }

    /** True when still water freezes at a surface temperature: strictly below {@link #FREEZE_BELOW}. */
    public static boolean coldEnoughToFreeze(float temperatureAtSurface) {
        return temperatureAtSurface < FREEZE_BELOW;
    }

    /**
     * How many layers snow may pile up to under the {@code snowAccumulationHeight} game rule: the rule capped at
     * {@link #MAX_SNOW_LAYERS}, or 0 when the rule is 0 or negative, which disables snow layers altogether.
     */
    public static int snowLayersTarget(int accumulationRule) {
        return accumulationRule <= 0 ? 0 : Math.min(accumulationRule, MAX_SNOW_LAYERS);
    }

    /** Rolls once: an attempt under a coverage succeeds with probability equal to that coverage. */
    public static boolean accept(float coverage, RandomSource random) {
        return random.nextFloat() < coverage;
    }

    /**
     * A point uniformly distributed over the area of the disc with the given centre and radius, as {@code {x, z}}.
     * A radius that is not positive gives the centre without touching the random source.
     */
    public static double[] randomPointInDisc(double cx, double cz, float radius, RandomSource random) {
        if (!(radius > 0.0F)) {
            return new double[] {cx, cz};
        }
        double distance = radius * Math.sqrt(random.nextDouble());
        double angle = random.nextDouble() * (Math.PI * 2.0D);
        return new double[] {cx + Math.cos(angle) * distance, cz + Math.sin(angle) * distance};
    }
}
