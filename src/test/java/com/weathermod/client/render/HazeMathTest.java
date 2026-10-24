package com.weathermod.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Checks the fog enhancer's arithmetic: the effective haze clamps and takes the config and shader pack factors,
 * the far plane shortens monotonically with haze and never grows, the near plane is capped at a fraction of the
 * new far plane, and the colour blend moves every channel toward a grey while staying inside 0..1. Needs no
 * Minecraft bootstrap: only {@code Mth} arithmetic is used.
 */
class HazeMathTest {
    private static final float EPSILON = 1.0E-6F;
    private static final float VANILLA_FAR = 192.0F;
    private static final float VANILLA_TERRAIN_NEAR = VANILLA_FAR - 19.2F;

    @Test
    void constantsMatchTheAgreedNumbers() {
        assertEquals(0.01F, HazeMath.MIN_HAZE, 0.0F);
        assertEquals(24.0F, HazeMath.MIN_VISIBILITY, 0.0F);
        assertEquals(0.2F, HazeMath.NEAR_FRACTION, 0.0F);
        assertEquals(0.7F, HazeMath.COLOR_MIX, 0.0F);
        assertEquals(1.15F, HazeMath.GREY_LIFT, 0.0F);
        assertEquals(0.03F, HazeMath.GREY_BIAS, 0.0F);
        assertEquals(0.75F, HazeMath.SHADER_PACK_FACTOR, 0.0F);
    }

    @Test
    void effectiveHazeIsTheProductWithoutAPack() {
        assertEquals(0.0F, HazeMath.effectiveHaze(0.0F, 1.0D, false), 0.0F);
        assertEquals(0.5F, HazeMath.effectiveHaze(0.5F, 1.0D, false), EPSILON);
        assertEquals(0.25F, HazeMath.effectiveHaze(0.5F, 0.5D, false), EPSILON);
        assertEquals(1.0F, HazeMath.effectiveHaze(0.5F, 2.0D, false), EPSILON);
        assertEquals(0.0F, HazeMath.effectiveHaze(1.0F, 0.0D, false), 0.0F, "strength 0 disables the enhancer");
    }

    @Test
    void effectiveHazeIsScaledByTheShaderPackFactor() {
        assertEquals(0.75F, HazeMath.effectiveHaze(1.0F, 1.0D, true), EPSILON);
        assertEquals(0.6F, HazeMath.effectiveHaze(0.8F, 1.0D, true), EPSILON);
        assertEquals(0.3F, HazeMath.effectiveHaze(0.8F, 0.5D, true), EPSILON);
        assertEquals(HazeMath.effectiveHaze(0.4F, 1.0D, false) * HazeMath.SHADER_PACK_FACTOR,
                HazeMath.effectiveHaze(0.4F, 1.0D, true), EPSILON);
    }

    @Test
    void effectiveHazeIsClampedToTheUnitInterval() {
        assertEquals(1.0F, HazeMath.effectiveHaze(1.0F, 2.0D, false), 0.0F);
        assertEquals(1.0F, HazeMath.effectiveHaze(0.8F, 2.0D, true), 0.0F, "0.8 * 2 * 0.75 = 1.2 clamps to 1");
        assertEquals(1.0F, HazeMath.effectiveHaze(1.5F, 1.0D, false), 0.0F);
        assertEquals(0.0F, HazeMath.effectiveHaze(-0.5F, 1.0D, false), 0.0F);
        assertEquals(0.0F, HazeMath.effectiveHaze(0.5F, -1.0D, false), 0.0F);
    }

    @Test
    void effectiveHazeTreatsNotANumberAsNoHaze() {
        assertEquals(0.0F, HazeMath.effectiveHaze(Float.NaN, 1.0D, false), 0.0F);
        assertEquals(0.0F, HazeMath.effectiveHaze(0.5F, Double.NaN, false), 0.0F);
        assertEquals(0.0F, HazeMath.effectiveHaze(Float.NaN, Double.NaN, true), 0.0F);
    }

    @Test
    void farPlaneIsUnchangedWithoutHaze() {
        assertEquals(VANILLA_FAR, HazeMath.farPlane(VANILLA_FAR, 0.0F), 0.0F);
        assertEquals(64.0F, HazeMath.farPlane(64.0F, 0.0F), 0.0F);
        assertEquals(VANILLA_FAR, HazeMath.farPlane(VANILLA_FAR, Float.NaN), 0.0F);
        assertEquals(VANILLA_FAR, HazeMath.farPlane(VANILLA_FAR, -1.0F), 0.0F);
    }

    @Test
    void farPlaneReachesMinimumVisibilityAtFullHaze() {
        assertEquals(HazeMath.MIN_VISIBILITY, HazeMath.farPlane(VANILLA_FAR, 1.0F), EPSILON);
        assertEquals(HazeMath.MIN_VISIBILITY, HazeMath.farPlane(1000.0F, 1.0F), EPSILON);
        assertEquals(HazeMath.MIN_VISIBILITY, HazeMath.farPlane(VANILLA_FAR, 1.5F), EPSILON,
                "haze above 1 clamps and does not pull the fog closer than the minimum visibility");
    }

    @Test
    void farPlaneIsHalfWayAtHalfHaze() {
        float expected = (VANILLA_FAR + HazeMath.MIN_VISIBILITY) * 0.5F;
        assertEquals(expected, HazeMath.farPlane(VANILLA_FAR, 0.5F), 1.0E-4F);
    }

    @Test
    void farPlaneIsMonotonicInHazeAndNeverAboveTheInput() {
        float[] fars = {VANILLA_FAR, 512.0F, 96.0F, 24.0F, 10.0F, 0.0F};
        for (float far : fars) {
            float previous = HazeMath.farPlane(far, 0.0F);
            assertEquals(far, previous, 0.0F, "haze 0 keeps far " + far);
            for (float haze = 0.01F; haze <= 1.0F; haze += 0.01F) {
                float current = HazeMath.farPlane(far, haze);
                assertTrue(current <= far + EPSILON, "far " + far + " grew at haze " + haze + ": " + current);
                assertTrue(current <= previous + EPSILON,
                        "far " + far + " is not monotonic at haze " + haze + ": " + current + " > " + previous);
                previous = current;
            }
        }
    }

    @Test
    void farPlaneBelowMinimumVisibilityStaysPut() {
        assertEquals(10.0F, HazeMath.farPlane(10.0F, 0.5F), 0.0F);
        assertEquals(10.0F, HazeMath.farPlane(10.0F, 1.0F), 0.0F);
        assertEquals(HazeMath.MIN_VISIBILITY, HazeMath.farPlane(HazeMath.MIN_VISIBILITY, 0.7F), 0.0F);
    }

    @Test
    void nearPlaneIsCappedAtAFractionOfTheNewFarPlane() {
        float newFar = HazeMath.farPlane(VANILLA_FAR, 1.0F);
        assertEquals(HazeMath.MIN_VISIBILITY * HazeMath.NEAR_FRACTION,
                HazeMath.nearPlane(VANILLA_TERRAIN_NEAR, newFar), EPSILON);
        assertEquals(4.8F, HazeMath.nearPlane(VANILLA_TERRAIN_NEAR, 24.0F), EPSILON);
        assertEquals(20.0F, HazeMath.nearPlane(150.0F, 100.0F), EPSILON);
    }

    @Test
    void nearPlaneKeepsANearerInput() {
        assertEquals(0.0F, HazeMath.nearPlane(0.0F, 24.0F), 0.0F, "the sky fog starts at the camera and stays there");
        assertEquals(3.0F, HazeMath.nearPlane(3.0F, 24.0F), 0.0F);
        assertEquals(-8.0F, HazeMath.nearPlane(-8.0F, 24.0F), 0.0F);
    }

    @Test
    void nearPlaneNeverPassesTheFarPlane() {
        for (float haze = 0.0F; haze <= 1.0F; haze += 0.05F) {
            float newFar = HazeMath.farPlane(VANILLA_FAR, haze);
            float newNear = HazeMath.nearPlane(VANILLA_TERRAIN_NEAR, newFar);
            assertTrue(newNear < newFar, "near " + newNear + " must stay in front of far " + newFar + " at haze " + haze);
        }
    }

    @Test
    void greyTargetIsTheLiftedAverage() {
        assertEquals(0.5F * HazeMath.GREY_LIFT + HazeMath.GREY_BIAS, HazeMath.greyTarget(0.5F, 0.5F, 0.5F), EPSILON);
        assertEquals(0.605F, HazeMath.greyTarget(0.2F, 0.5F, 0.8F), EPSILON, "only the average matters");
        assertEquals(HazeMath.GREY_BIAS, HazeMath.greyTarget(0.0F, 0.0F, 0.0F), EPSILON, "black hazes toward a dark grey");
        assertEquals(1.0F, HazeMath.greyTarget(1.0F, 1.0F, 1.0F), 0.0F, "white clamps to white");
        assertEquals(1.0F, HazeMath.greyTarget(0.9F, 0.9F, 0.9F), 0.0F);
    }

    @Test
    void blendColorIsUnchangedWithoutHaze() {
        assertArrayEquals(new float[] {0.6F, 0.7F, 0.9F}, HazeMath.blendColor(0.6F, 0.7F, 0.9F, 0.0F), 0.0F);
        assertArrayEquals(new float[] {0.6F, 0.7F, 0.9F}, HazeMath.blendColor(0.6F, 0.7F, 0.9F, Float.NaN), 0.0F);
        assertArrayEquals(new float[] {0.6F, 0.7F, 0.9F}, HazeMath.blendColor(0.6F, 0.7F, 0.9F, -0.5F), 0.0F);
    }

    @Test
    void blendColorMovesSeventyPercentTowardTheGreyAtFullHaze() {
        float grey = HazeMath.greyTarget(0.6F, 0.7F, 0.9F);
        float[] blended = HazeMath.blendColor(0.6F, 0.7F, 0.9F, 1.0F);

        assertEquals(3, blended.length);
        assertEquals(0.6F + (grey - 0.6F) * HazeMath.COLOR_MIX, blended[0], EPSILON);
        assertEquals(0.7F + (grey - 0.7F) * HazeMath.COLOR_MIX, blended[1], EPSILON);
        assertEquals(0.9F + (grey - 0.9F) * HazeMath.COLOR_MIX, blended[2], EPSILON);
        assertArrayEquals(blended, HazeMath.blendColor(0.6F, 0.7F, 0.9F, 2.0F), 0.0F, "haze above 1 clamps to 1");
    }

    @Test
    void blendColorMatchesTheChannelHelper() {
        float grey = HazeMath.greyTarget(0.3F, 0.5F, 0.2F);
        float[] blended = HazeMath.blendColor(0.3F, 0.5F, 0.2F, 0.4F);

        assertEquals(HazeMath.blendChannel(0.3F, grey, 0.4F), blended[0], 0.0F);
        assertEquals(HazeMath.blendChannel(0.5F, grey, 0.4F), blended[1], 0.0F);
        assertEquals(HazeMath.blendChannel(0.2F, grey, 0.4F), blended[2], 0.0F);
    }

    @Test
    void blendColorMovesEveryChannelTowardTheGreyAsHazeRises() {
        float[][] colours = {
            {0.6F, 0.7F, 0.9F},
            {0.05F, 0.05F, 0.1F},
            {0.9F, 0.3F, 0.1F},
            {0.0F, 0.0F, 0.0F},
            {1.0F, 1.0F, 1.0F},
            {0.5F, 0.5F, 0.5F}
        };
        for (float[] colour : colours) {
            float grey = HazeMath.greyTarget(colour[0], colour[1], colour[2]);
            float[] previous = HazeMath.blendColor(colour[0], colour[1], colour[2], 0.0F);
            for (float haze = 0.05F; haze <= 1.0F; haze += 0.05F) {
                float[] current = HazeMath.blendColor(colour[0], colour[1], colour[2], haze);
                for (int channel = 0; channel < 3; channel++) {
                    float before = Math.abs(previous[channel] - grey);
                    float after = Math.abs(current[channel] - grey);
                    assertTrue(after <= before + EPSILON, "channel " + channel + " of " + describe(colour)
                            + " moved away from grey " + grey + " at haze " + haze);
                }
                previous = current;
            }
        }
    }

    @Test
    void blendColorStaysInsideTheUnitInterval() {
        float[][] colours = {
            {0.0F, 0.0F, 0.0F},
            {1.0F, 1.0F, 1.0F},
            {1.0F, 0.0F, 0.0F},
            {0.0F, 1.0F, 0.0F},
            {0.0F, 0.0F, 1.0F},
            {0.95F, 0.98F, 1.0F},
            {0.02F, 0.01F, 0.05F}
        };
        for (float[] colour : colours) {
            for (float haze = 0.0F; haze <= 1.0F; haze += 0.1F) {
                float[] blended = HazeMath.blendColor(colour[0], colour[1], colour[2], haze);
                for (int channel = 0; channel < 3; channel++) {
                    assertTrue(blended[channel] >= 0.0F && blended[channel] <= 1.0F,
                            "channel " + channel + " of " + describe(colour) + " left 0..1 at haze " + haze + ": "
                                    + blended[channel]);
                }
            }
        }
    }

    @Test
    void blendColorDesaturates() {
        float[] blended = HazeMath.blendColor(0.9F, 0.3F, 0.1F, 1.0F);
        float inputSpread = 0.9F - 0.1F;
        float outputSpread = max(blended) - min(blended);

        assertTrue(outputSpread < inputSpread, "the channel spread must shrink: " + outputSpread);
        assertEquals(inputSpread * (1.0F - HazeMath.COLOR_MIX), outputSpread, EPSILON);
    }

    @Test
    void blendColorLiftsADarkStormSky() {
        float[] blended = HazeMath.blendColor(0.1F, 0.1F, 0.12F, 1.0F);
        assertTrue(blended[0] > 0.1F && blended[1] > 0.1F && blended[2] > 0.12F,
                "a dark fog colour gets lighter under haze");
    }

    private static float max(float[] values) {
        float result = values[0];
        for (float value : values) {
            result = Math.max(result, value);
        }
        return result;
    }

    private static float min(float[] values) {
        float result = values[0];
        for (float value : values) {
            result = Math.min(result, value);
        }
        return result;
    }

    private static String describe(float[] colour) {
        return "(" + colour[0] + ", " + colour[1] + ", " + colour[2] + ")";
    }
}
