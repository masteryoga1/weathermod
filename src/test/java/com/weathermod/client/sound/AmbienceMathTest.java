package com.weathermod.client.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.client.sound.AmbienceMath.Targets;
import com.weathermod.core.PrecipitationKind;
import org.junit.jupiter.api.Test;

/**
 * Checks the ambience arithmetic: the loop targets for each precipitation kind, the light-to-heavy rain mix, the
 * redistribution under a roof, the wind thresholds and gust pitch, the config volume scaling and the smoothstep
 * and approach primitives at their edges. Needs no Minecraft bootstrap: only floats and an enum are used.
 */
class AmbienceMathTest {
    private static final float EPSILON = 1.0E-5F;
    private static final boolean OPEN_SKY = false;
    private static final boolean UNDER_ROOF = true;
    private static final float CALM = 0.0F;
    private static final double FULL_VOLUME = 1.0D;

    private static Targets rain(float level) {
        return AmbienceMath.targets(level, PrecipitationKind.RAIN, OPEN_SKY, CALM, CALM, FULL_VOLUME);
    }

    private static Targets windOnly(float windSpeed, float gustSpeed) {
        return AmbienceMath.targets(0.0F, PrecipitationKind.NONE, OPEN_SKY, windSpeed, gustSpeed, FULL_VOLUME);
    }

    private static void assertNoPrecipitationLoops(Targets targets) {
        assertEquals(0.0F, targets.rainLight(), 0.0F, "light rain");
        assertEquals(0.0F, targets.rainHeavy(), 0.0F, "heavy rain");
        assertEquals(0.0F, targets.rainRoof(), 0.0F, "roof");
        assertEquals(0.0F, targets.snow(), 0.0F, "snow");
    }

    @Test
    void constantsMatchTheAgreedNumbers() {
        assertEquals(0.55F, AmbienceMath.HEAVY_START, 0.0F);
        assertEquals(1.0F, AmbienceMath.HEAVY_FULL, 0.0F);
        assertEquals(0.25F, AmbienceMath.ROOF_OUTSIDE_FACTOR, 0.0F);
        assertEquals(0.9F, AmbienceMath.ROOF_LOOP_FACTOR, 0.0F);
        assertEquals(0.8F, AmbienceMath.SLEET_RAIN_FACTOR, 0.0F);
        assertEquals(0.5F, AmbienceMath.SLEET_SNOW_FACTOR, 0.0F);
        assertEquals(0.6F, AmbienceMath.SNOW_FACTOR, 0.0F);
        assertEquals(3.0F, AmbienceMath.WIND_MIN, 0.0F);
        assertEquals(12.0F, AmbienceMath.WIND_RANGE, 0.0F);
        assertEquals(0.8F, AmbienceMath.WIND_MAX_VOLUME, 0.0F);
        assertEquals(0.8F, AmbienceMath.WIND_GUST_WEIGHT, 0.0F);
        assertEquals(0.04F, AmbienceMath.VOLUME_STEP, 0.0F);
        assertEquals(20, AmbienceMath.STOP_AFTER_TICKS);
        assertEquals(0.005F, AmbienceMath.SILENCE, 0.0F);
        assertEquals(0.95F, AmbienceMath.REST_PITCH, 0.0F);
    }

    @Test
    void silentTargetsAreAllZeroAtRestPitch() {
        Targets silent = Targets.SILENT;

        assertNoPrecipitationLoops(silent);
        assertEquals(0.0F, silent.wind(), 0.0F);
        assertEquals(AmbienceMath.REST_PITCH, silent.windPitch(), 0.0F);
        assertTrue(silent.isSilent());
        assertFalse(rain(0.5F).isSilent());
        assertTrue(new Targets(AmbienceMath.SILENCE, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F).isSilent(),
            "exactly the silence threshold still counts as silent");
    }

    @Test
    void rainBelowTheHeavyStartPlaysOnlyTheLightLoop() {
        Targets targets = rain(0.4F);

        assertEquals(0.4F, targets.rainLight(), EPSILON);
        assertEquals(0.0F, targets.rainHeavy(), 0.0F);
        assertEquals(0.0F, targets.rainRoof(), 0.0F);
        assertEquals(0.0F, targets.snow(), 0.0F);
        assertEquals(0.0F, targets.wind(), 0.0F);
    }

    @Test
    void rainInTheMixBandSplitsBetweenLightAndHeavy() {
        Targets targets = rain(0.75F);
        float heavyMix = AmbienceMath.smoothstep(AmbienceMath.HEAVY_START, AmbienceMath.HEAVY_FULL, 0.75F);

        assertTrue(heavyMix > 0.4F && heavyMix < 0.45F, "mix at 0.75 is " + heavyMix);
        assertEquals(0.75F * (1.0F - heavyMix), targets.rainLight(), EPSILON);
        assertEquals(0.75F * heavyMix, targets.rainHeavy(), EPSILON);
        assertEquals(0.75F, targets.rainLight() + targets.rainHeavy(), EPSILON, "the split conserves the level");
        assertTrue(targets.rainLight() > targets.rainHeavy(), "light still dominates at 0.75");
    }

    @Test
    void fullRainPlaysOnlyTheHeavyLoop() {
        Targets targets = rain(1.0F);

        assertEquals(0.0F, targets.rainLight(), EPSILON);
        assertEquals(1.0F, targets.rainHeavy(), EPSILON);
        assertEquals(0.0F, targets.rainRoof(), 0.0F);
    }

    @Test
    void heavyMixStartsExactlyAtTheStartLevel() {
        Targets atStart = rain(AmbienceMath.HEAVY_START);
        Targets justAbove = rain(AmbienceMath.HEAVY_START + 0.05F);

        assertEquals(AmbienceMath.HEAVY_START, atStart.rainLight(), EPSILON);
        assertEquals(0.0F, atStart.rainHeavy(), 0.0F);
        assertTrue(justAbove.rainHeavy() > 0.0F);
        assertEquals(AmbienceMath.HEAVY_START + 0.05F, justAbove.rainLight() + justAbove.rainHeavy(), EPSILON);
    }

    @Test
    void heavyShareGrowsMonotonicallyWithTheRainLevel() {
        float previousHeavy = -1.0F;
        for (int step = 0; step <= 20; step++) {
            float level = step / 20.0F;
            Targets targets = rain(level);
            assertTrue(targets.rainHeavy() >= previousHeavy - EPSILON, "heavy fell at level " + level);
            assertEquals(level, targets.rainLight() + targets.rainHeavy(), EPSILON, "sum at level " + level);
            previousHeavy = targets.rainHeavy();
        }
    }

    @Test
    void snowPlaysOnlyTheSnowLoop() {
        Targets targets = AmbienceMath.targets(1.0F, PrecipitationKind.SNOW, OPEN_SKY, CALM, CALM, FULL_VOLUME);

        assertEquals(0.0F, targets.rainLight(), 0.0F);
        assertEquals(0.0F, targets.rainHeavy(), 0.0F);
        assertEquals(0.0F, targets.rainRoof(), 0.0F);
        assertEquals(AmbienceMath.SNOW_FACTOR, targets.snow(), EPSILON);

        Targets half = AmbienceMath.targets(0.5F, PrecipitationKind.SNOW, OPEN_SKY, CALM, CALM, FULL_VOLUME);
        assertEquals(0.5F * AmbienceMath.SNOW_FACTOR, half.snow(), EPSILON);
    }

    @Test
    void snowIgnoresTheRoof() {
        Targets open = AmbienceMath.targets(1.0F, PrecipitationKind.SNOW, OPEN_SKY, CALM, CALM, FULL_VOLUME);
        Targets roofed = AmbienceMath.targets(1.0F, PrecipitationKind.SNOW, UNDER_ROOF, CALM, CALM, FULL_VOLUME);

        assertEquals(open.snow(), roofed.snow(), 0.0F);
        assertEquals(0.0F, roofed.rainRoof(), 0.0F, "no rain, no roof loop");
    }

    @Test
    void sleetMixesScaledRainAndSnow() {
        Targets targets = AmbienceMath.targets(1.0F, PrecipitationKind.SLEET, OPEN_SKY, CALM, CALM, FULL_VOLUME);

        assertEquals(0.0F, targets.rainLight(), EPSILON);
        assertEquals(AmbienceMath.SLEET_RAIN_FACTOR, targets.rainHeavy(), EPSILON);
        assertEquals(AmbienceMath.SNOW_FACTOR * AmbienceMath.SLEET_SNOW_FACTOR, targets.snow(), EPSILON);

        Targets light = AmbienceMath.targets(0.4F, PrecipitationKind.SLEET, OPEN_SKY, CALM, CALM, FULL_VOLUME);
        assertEquals(0.4F * AmbienceMath.SLEET_RAIN_FACTOR, light.rainLight(), EPSILON);
        assertEquals(0.0F, light.rainHeavy(), 0.0F);
        assertEquals(0.4F * AmbienceMath.SNOW_FACTOR * AmbienceMath.SLEET_SNOW_FACTOR, light.snow(), EPSILON);
    }

    @Test
    void noKindSilencesEveryPrecipitationLoopButNotTheWind() {
        Targets targets = AmbienceMath.targets(1.0F, PrecipitationKind.NONE, UNDER_ROOF, 15.0F, 15.0F, FULL_VOLUME);

        assertNoPrecipitationLoops(targets);
        assertEquals(AmbienceMath.WIND_MAX_VOLUME, targets.wind(), EPSILON);
    }

    @Test
    void zeroRainIsSilentForEveryKind() {
        for (PrecipitationKind kind : PrecipitationKind.values()) {
            assertNoPrecipitationLoops(AmbienceMath.targets(0.0F, kind, OPEN_SKY, CALM, CALM, FULL_VOLUME));
            assertNoPrecipitationLoops(AmbienceMath.targets(0.0F, kind, UNDER_ROOF, CALM, CALM, FULL_VOLUME));
        }
    }

    @Test
    void roofRedistributesLightRain() {
        Targets open = rain(0.4F);
        Targets roofed = AmbienceMath.targets(0.4F, PrecipitationKind.RAIN, UNDER_ROOF, CALM, CALM, FULL_VOLUME);

        assertEquals(open.rainLight() * AmbienceMath.ROOF_OUTSIDE_FACTOR, roofed.rainLight(), EPSILON);
        assertEquals(0.0F, roofed.rainHeavy(), 0.0F);
        assertEquals(0.4F * AmbienceMath.ROOF_LOOP_FACTOR, roofed.rainRoof(), EPSILON);
        assertTrue(roofed.rainRoof() > roofed.rainLight(), "the roof loop dominates under a roof");
    }

    @Test
    void roofRedistributesHeavyRainAndKeepsTheMix() {
        Targets open = rain(0.75F);
        Targets roofed = AmbienceMath.targets(0.75F, PrecipitationKind.RAIN, UNDER_ROOF, CALM, CALM, FULL_VOLUME);

        assertEquals(open.rainLight() * AmbienceMath.ROOF_OUTSIDE_FACTOR, roofed.rainLight(), EPSILON);
        assertEquals(open.rainHeavy() * AmbienceMath.ROOF_OUTSIDE_FACTOR, roofed.rainHeavy(), EPSILON);
        assertEquals(0.75F * AmbienceMath.ROOF_LOOP_FACTOR, roofed.rainRoof(), EPSILON);
        assertEquals(0.75F * AmbienceMath.ROOF_OUTSIDE_FACTOR, roofed.rainLight() + roofed.rainHeavy(), EPSILON);
    }

    @Test
    void roofLoopFollowsTheSleetRainFactor() {
        Targets roofed = AmbienceMath.targets(1.0F, PrecipitationKind.SLEET, UNDER_ROOF, CALM, CALM, FULL_VOLUME);

        assertEquals(AmbienceMath.SLEET_RAIN_FACTOR * AmbienceMath.ROOF_LOOP_FACTOR, roofed.rainRoof(), EPSILON);
        assertEquals(AmbienceMath.SLEET_RAIN_FACTOR * AmbienceMath.ROOF_OUTSIDE_FACTOR, roofed.rainHeavy(), EPSILON);
        assertEquals(AmbienceMath.SNOW_FACTOR * AmbienceMath.SLEET_SNOW_FACTOR, roofed.snow(), EPSILON);
    }

    @Test
    void windIsSilentUpToTheMinimumSpeed() {
        assertEquals(0.0F, windOnly(0.0F, 0.0F).wind(), 0.0F);
        assertEquals(0.0F, windOnly(AmbienceMath.WIND_MIN, AmbienceMath.WIND_MIN).wind(), 0.0F);
        assertEquals(0.0F, windOnly(-5.0F, -5.0F).wind(), 0.0F, "a negative speed counts as calm");
        assertTrue(windOnly(AmbienceMath.WIND_MIN + 0.5F, AmbienceMath.WIND_MIN + 0.5F).wind() > 0.0F);
    }

    @Test
    void windReachesFullVolumeAtTheTopOfItsRangeAndClampsAbove() {
        float full = AmbienceMath.WIND_MIN + AmbienceMath.WIND_RANGE;

        assertEquals(AmbienceMath.WIND_MAX_VOLUME, windOnly(full, full).wind(), EPSILON);
        assertEquals(AmbienceMath.WIND_MAX_VOLUME, windOnly(40.0F, 40.0F).wind(), EPSILON);
        assertEquals(AmbienceMath.WIND_MAX_VOLUME / 2.0F, windOnly(9.0F, 9.0F).wind(), EPSILON,
            "halfway through the range gives half the volume");
    }

    @Test
    void gustsCountWithTheirWeight() {
        assertEquals(0.6F, windOnly(0.0F, 15.0F).wind(), EPSILON, "15 * 0.8 = 12 -> (12 - 3) / 12 * 0.8");
        assertEquals(windOnly(9.0F, 9.0F).wind(), windOnly(9.0F, 10.0F).wind(), EPSILON,
            "a weighted gust below the sustained wind changes nothing");
        assertTrue(windOnly(9.0F, 15.0F).wind() > windOnly(9.0F, 9.0F).wind(), "a strong gust raises the volume");
        assertEquals(AmbienceMath.windVolume(6.0F, 15.0F), windOnly(6.0F, 15.0F).wind(), 0.0F);
    }

    @Test
    void gustPitchRisesWithTheGustExcess() {
        assertEquals(AmbienceMath.REST_PITCH, windOnly(10.0F, 10.0F).windPitch(), 0.0F, "no gust");
        assertEquals(AmbienceMath.REST_PITCH, windOnly(10.0F, 5.0F).windPitch(), 0.0F, "a gust below the wind");
        assertEquals(1.0F, windOnly(10.0F, 13.0F).windPitch(), EPSILON, "half the gust range");
        assertEquals(AmbienceMath.REST_PITCH + AmbienceMath.GUST_PITCH_RANGE, windOnly(10.0F, 16.0F).windPitch(),
            EPSILON, "the full gust range");
        assertEquals(AmbienceMath.REST_PITCH + AmbienceMath.GUST_PITCH_RANGE, windOnly(10.0F, 30.0F).windPitch(),
            EPSILON, "clamped above the range");
        assertEquals(AmbienceMath.REST_PITCH, windOnly(0.0F, 0.0F).windPitch(), 0.0F, "calm air");
    }

    @Test
    void pitchIsNotScaledByTheConfigVolume() {
        Targets loud = AmbienceMath.targets(0.0F, PrecipitationKind.NONE, OPEN_SKY, 10.0F, 16.0F, 1.0D);
        Targets quiet = AmbienceMath.targets(0.0F, PrecipitationKind.NONE, OPEN_SKY, 10.0F, 16.0F, 0.25D);

        assertEquals(loud.windPitch(), quiet.windPitch(), 0.0F);
        assertEquals(loud.wind() * 0.25F, quiet.wind(), EPSILON);
    }

    @Test
    void configVolumeScalesEveryLoop() {
        Targets full = AmbienceMath.targets(0.75F, PrecipitationKind.SLEET, UNDER_ROOF, 9.0F, 12.0F, 1.0D);
        Targets half = AmbienceMath.targets(0.75F, PrecipitationKind.SLEET, UNDER_ROOF, 9.0F, 12.0F, 0.5D);

        assertEquals(full.rainLight() * 0.5F, half.rainLight(), EPSILON);
        assertEquals(full.rainHeavy() * 0.5F, half.rainHeavy(), EPSILON);
        assertEquals(full.rainRoof() * 0.5F, half.rainRoof(), EPSILON);
        assertEquals(full.snow() * 0.5F, half.snow(), EPSILON);
        assertEquals(full.wind() * 0.5F, half.wind(), EPSILON);
        assertTrue(full.rainLight() > 0.0F && full.rainRoof() > 0.0F && full.snow() > 0.0F && full.wind() > 0.0F,
            "the scenario exercises every loop");
    }

    @Test
    void zeroConfigVolumeSilencesEverything() {
        Targets targets = AmbienceMath.targets(1.0F, PrecipitationKind.SLEET, UNDER_ROOF, 15.0F, 20.0F, 0.0D);

        assertTrue(targets.isSilent());
        assertNoPrecipitationLoops(targets);
        assertEquals(0.0F, targets.wind(), 0.0F);
    }

    @Test
    void configVolumeIsClampedToTheUnitRange() {
        Targets reference = rain(1.0F);

        assertEquals(reference.rainHeavy(),
            AmbienceMath.targets(1.0F, PrecipitationKind.RAIN, OPEN_SKY, CALM, CALM, 3.0D).rainHeavy(), 0.0F);
        assertTrue(AmbienceMath.targets(1.0F, PrecipitationKind.RAIN, OPEN_SKY, CALM, CALM, -1.0D).isSilent());
        assertTrue(AmbienceMath.targets(1.0F, PrecipitationKind.RAIN, OPEN_SKY, CALM, CALM, Double.NaN).isSilent());
    }

    @Test
    void rainLevelIsClampedAndNanCountsAsDry() {
        assertEquals(1.0F, rain(1.7F).rainHeavy(), EPSILON);
        assertEquals(0.0F, rain(1.7F).rainLight(), EPSILON);
        assertNoPrecipitationLoops(rain(-0.3F));
        assertNoPrecipitationLoops(rain(Float.NaN));
    }

    @Test
    void volumesStayInsideTheUnitRange() {
        for (PrecipitationKind kind : PrecipitationKind.values()) {
            for (int step = 0; step <= 10; step++) {
                float level = step / 10.0F;
                Targets targets = AmbienceMath.targets(level, kind, UNDER_ROOF, 20.0F, 40.0F, 1.0D);
                for (float volume : new float[] {targets.rainLight(), targets.rainHeavy(), targets.rainRoof(),
                    targets.snow(), targets.wind()}) {
                    assertTrue(volume >= 0.0F && volume <= 1.0F, kind + " at " + level + " gave " + volume);
                }
            }
        }
    }

    @Test
    void rejectsANullKind() {
        assertThrows(NullPointerException.class,
            () -> AmbienceMath.targets(0.5F, null, OPEN_SKY, CALM, CALM, FULL_VOLUME));
    }

    @Test
    void smoothstepIsZeroBelowAndOneAboveItsEdges() {
        assertEquals(0.0F, AmbienceMath.smoothstep(0.2F, 0.8F, -1.0F), 0.0F);
        assertEquals(0.0F, AmbienceMath.smoothstep(0.2F, 0.8F, 0.2F), 0.0F);
        assertEquals(1.0F, AmbienceMath.smoothstep(0.2F, 0.8F, 0.8F), 0.0F);
        assertEquals(1.0F, AmbienceMath.smoothstep(0.2F, 0.8F, 5.0F), 0.0F);
    }

    @Test
    void smoothstepIsHalfAtTheMidpointAndSymmetric() {
        assertEquals(0.5F, AmbienceMath.smoothstep(0.0F, 1.0F, 0.5F), EPSILON);
        assertEquals(0.5F, AmbienceMath.smoothstep(0.2F, 0.8F, 0.5F), EPSILON);
        assertEquals(1.0F - AmbienceMath.smoothstep(0.0F, 1.0F, 0.25F), AmbienceMath.smoothstep(0.0F, 1.0F, 0.75F),
            EPSILON);
        assertEquals(0.15625F, AmbienceMath.smoothstep(0.0F, 1.0F, 0.25F), EPSILON, "t*t*(3-2t) at 0.25");
    }

    @Test
    void smoothstepIsMonotonic() {
        float previous = 0.0F;
        for (int step = 0; step <= 100; step++) {
            float value = AmbienceMath.smoothstep(0.3F, 0.7F, step / 100.0F);
            assertTrue(value >= previous, "fell at step " + step);
            previous = value;
        }
    }

    @Test
    void smoothstepWithEqualEdgesIsAStep() {
        assertEquals(0.0F, AmbienceMath.smoothstep(0.5F, 0.5F, 0.49F), 0.0F);
        assertEquals(1.0F, AmbienceMath.smoothstep(0.5F, 0.5F, 0.5F), 0.0F);
        assertEquals(1.0F, AmbienceMath.smoothstep(0.5F, 0.5F, 0.51F), 0.0F);
    }

    @Test
    void smoothstepTreatsNanAsZero() {
        assertEquals(0.0F, AmbienceMath.smoothstep(0.0F, 1.0F, Float.NaN), 0.0F);
        assertEquals(0.0F, AmbienceMath.smoothstep(0.5F, 0.5F, Float.NaN), 0.0F);
    }

    @Test
    void approachMovesByAtMostOneStepInEitherDirection() {
        assertEquals(0.04F, AmbienceMath.approach(0.0F, 1.0F, 0.04F), EPSILON);
        assertEquals(0.96F, AmbienceMath.approach(1.0F, 0.0F, 0.04F), EPSILON);
    }

    @Test
    void approachLandsExactlyOnTheTargetWithinOneStep() {
        assertEquals(0.5F, AmbienceMath.approach(0.48F, 0.5F, 0.04F), 0.0F);
        assertEquals(0.5F, AmbienceMath.approach(0.52F, 0.5F, 0.04F), 0.0F);
        assertEquals(0.5F, AmbienceMath.approach(0.46F, 0.5F, 0.04F), 0.0F, "exactly one step away");
        assertEquals(0.5F, AmbienceMath.approach(0.5F, 0.5F, 0.04F), 0.0F, "already there");
    }

    @Test
    void approachReadsANegativeStepAsItsMagnitude() {
        assertEquals(0.04F, AmbienceMath.approach(0.0F, 1.0F, -0.04F), EPSILON);
        assertEquals(0.96F, AmbienceMath.approach(1.0F, 0.0F, -0.04F), EPSILON);
    }

    @Test
    void approachWithZeroStepOnlyMovesOntoAnEqualTarget() {
        assertEquals(0.3F, AmbienceMath.approach(0.3F, 1.0F, 0.0F), 0.0F);
        assertEquals(0.3F, AmbienceMath.approach(0.3F, 0.3F, 0.0F), 0.0F);
    }

    @Test
    void approachIgnoresANanTarget() {
        assertEquals(0.3F, AmbienceMath.approach(0.3F, Float.NaN, 0.04F), 0.0F);
    }

    @Test
    void repeatedApproachEndsExactlyOnTheTargetWithoutOvershoot() {
        float volume = 0.0F;
        float target = 0.37F;
        int ticks = 0;
        while (volume != target && ticks < 100) {
            float next = AmbienceMath.approach(volume, target, AmbienceMath.VOLUME_STEP);
            assertTrue(next <= target + EPSILON, "overshoot at tick " + ticks);
            assertTrue(next >= volume, "moved backwards at tick " + ticks);
            volume = next;
            ticks++;
        }
        assertEquals(target, volume, 0.0F);
        assertEquals(10, ticks, "0.37 takes nine full steps of 0.04 and one partial step");
    }

    @Test
    void fullFadeTakesTwentyFiveTicks() {
        float volume = 1.0F;
        int ticks = 0;
        while (volume > 0.0F) {
            volume = AmbienceMath.approach(volume, 0.0F, AmbienceMath.VOLUME_STEP);
            ticks++;
        }
        assertEquals(25, ticks);
        assertEquals(0.0F, volume, 0.0F);
    }
}
