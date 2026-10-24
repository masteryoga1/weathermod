package com.weathermod.client.render;

import net.minecraft.util.Mth;

/**
 * The arithmetic behind the fog enhancer: how much haze the fog should show after the config and the shader pack
 * have had their say, how far the fog planes move in for that haze, and how the fog colour drifts toward a lifted
 * grey. Pure functions on floats so the fog path allocates nothing and the rules can be unit-tested.
 */
public final class HazeMath {
    /** Below this effective haze the enhancer leaves the fog alone, so other mods' fog is untouched. */
    public static final float MIN_HAZE = 0.01F;
    /** Fog distance in blocks at full haze: the far plane is pulled in toward this, never below it. */
    public static final float MIN_VISIBILITY = 24.0F;
    /** The near plane is capped at this fraction of the new far plane so the haze starts close to the camera. */
    public static final float NEAR_FRACTION = 0.2F;
    /** How far toward the grey the fog colour moves at full haze. */
    public static final float COLOR_MIX = 0.7F;
    /** The grey the colour drifts toward is the colour's average brightness lifted by this factor. */
    public static final float GREY_LIFT = 1.15F;
    /** Added to the lifted average so even a black fog colour hazes toward a visible grey. */
    public static final float GREY_BIAS = 0.03F;
    /** Haze is scaled by this under a shader pack, whose own atmosphere already dims the distance. */
    public static final float SHADER_PACK_FACTOR = 0.75F;

    private HazeMath() {
    }

    /**
     * The haze the fog should show: the driver's haze scaled by the {@code hazeStrength} config and, under a shader
     * pack, by {@link #SHADER_PACK_FACTOR}, clamped to {@code [0, 1]}. A haze or strength that is not a number
     * counts as no haze.
     */
    public static float effectiveHaze(float haze, double hazeStrengthConfig, boolean shaderPackInUse) {
        if (Float.isNaN(haze) || Double.isNaN(hazeStrengthConfig)) {
            return 0.0F;
        }
        double scaled = haze * hazeStrengthConfig;
        if (shaderPackInUse) {
            scaled *= SHADER_PACK_FACTOR;
        }
        return clampUnit((float) scaled);
    }

    /**
     * The far fog plane for a haze: the input far plane blended toward {@link #MIN_VISIBILITY} by the haze and never
     * further out than the input, so a short render distance or another mod's closer fog stays as it is.
     */
    public static float farPlane(float far, float haze) {
        float h = clampUnit(haze);
        return Math.min(far, Mth.lerp(h, far, MIN_VISIBILITY));
    }

    /**
     * The near fog plane for a new far plane: the input near plane, pulled in to {@link #NEAR_FRACTION} of the new
     * far plane when it sits further out than that.
     */
    public static float nearPlane(float near, float newFar) {
        return Math.min(near, newFar * NEAR_FRACTION);
    }

    /**
     * The grey a fog colour drifts toward: its average brightness lifted by {@link #GREY_LIFT}, raised by
     * {@link #GREY_BIAS} and clamped to {@code [0, 1]}.
     */
    public static float greyTarget(float r, float g, float b) {
        float average = (r + g + b) / 3.0F;
        return Mth.clamp(average * GREY_LIFT + GREY_BIAS, 0.0F, 1.0F);
    }

    /**
     * One colour channel moved toward a grey by {@code haze * COLOR_MIX}; haze 0 returns the channel unchanged and
     * haze 1 moves it {@link #COLOR_MIX} of the way.
     */
    public static float blendChannel(float channel, float grey, float haze) {
        return Mth.lerp(clampUnit(haze) * COLOR_MIX, channel, grey);
    }

    /**
     * The fog colour for a haze as {@code {r, g, b}}: each channel moved toward {@link #greyTarget} by
     * {@code haze * COLOR_MIX}. Allocates the result; the fog path uses {@link #greyTarget} and
     * {@link #blendChannel} directly.
     */
    public static float[] blendColor(float r, float g, float b, float haze) {
        float grey = greyTarget(r, g, b);
        return new float[] {
            blendChannel(r, grey, haze),
            blendChannel(g, grey, haze),
            blendChannel(b, grey, haze)
        };
    }

    private static float clampUnit(float value) {
        if (!(value > 0.0F)) {
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }
}
