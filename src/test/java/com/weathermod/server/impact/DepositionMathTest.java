package com.weathermod.server.impact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.PrecipitationKind;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/** Checks the pure deposition rules: temperature thresholds, the layer cap, the acceptance roll and the disc pick. */
class DepositionMathTest {
    private static final float EPSILON = 1.0E-6F;

    @Test
    void snowThresholdIsTheSharedOne() {
        assertTrue(DepositionMath.coldEnoughToSnow(-0.5F));
        assertTrue(DepositionMath.coldEnoughToSnow(0.0F));
        assertTrue(DepositionMath.coldEnoughToSnow(0.099F));
        assertFalse(DepositionMath.coldEnoughToSnow(PrecipitationKind.SNOW_BELOW), "the bound itself is not snow");
        assertFalse(DepositionMath.coldEnoughToSnow(0.15F));
        assertFalse(DepositionMath.coldEnoughToSnow(0.8F));
    }

    @Test
    void snowThresholdAgreesWithThePrecipitationKind() {
        for (float temperature = -1.0F; temperature <= 2.5F; temperature += 0.01F) {
            assertEquals(PrecipitationKind.fromTemperature(temperature) == PrecipitationKind.SNOW,
                DepositionMath.coldEnoughToSnow(temperature), "at temperature " + temperature);
        }
    }

    @Test
    void freezeThresholdSitsAboveTheSnowThreshold() {
        assertEquals(0.15F, DepositionMath.FREEZE_BELOW, EPSILON);
        assertTrue(DepositionMath.FREEZE_BELOW > PrecipitationKind.SNOW_BELOW);

        assertTrue(DepositionMath.coldEnoughToFreeze(-0.7F));
        assertTrue(DepositionMath.coldEnoughToFreeze(0.1F), "sleet temperatures still freeze still water");
        assertTrue(DepositionMath.coldEnoughToFreeze(0.149F));
        assertFalse(DepositionMath.coldEnoughToFreeze(0.15F), "the bound itself does not freeze");
        assertFalse(DepositionMath.coldEnoughToFreeze(0.25F));
    }

    @Test
    void snowLayersTargetFollowsTheGameRuleUpToEightLayers() {
        assertEquals(0, DepositionMath.snowLayersTarget(0));
        assertEquals(0, DepositionMath.snowLayersTarget(-3));
        assertEquals(1, DepositionMath.snowLayersTarget(1), "the vanilla default is a single layer");
        assertEquals(5, DepositionMath.snowLayersTarget(5));
        assertEquals(8, DepositionMath.snowLayersTarget(8));
        assertEquals(8, DepositionMath.snowLayersTarget(9));
        assertEquals(8, DepositionMath.snowLayersTarget(Integer.MAX_VALUE));
        assertEquals(8, DepositionMath.MAX_SNOW_LAYERS);
    }

    @Test
    void acceptanceMatchesTheCoverage() {
        RandomSource random = RandomSource.create(1234L);
        int rolls = 20000;

        int none = 0;
        int half = 0;
        int full = 0;
        for (int i = 0; i < rolls; i++) {
            if (DepositionMath.accept(0.0F, random)) {
                none++;
            }
            if (DepositionMath.accept(0.5F, random)) {
                half++;
            }
            if (DepositionMath.accept(1.0F, random)) {
                full++;
            }
        }

        assertEquals(0, none, "zero coverage never deposits");
        assertEquals(rolls, full, "full coverage always deposits");
        assertEquals(0.5D, half / (double) rolls, 0.02D, "half coverage deposits about half the time");
    }

    @Test
    void acceptanceIsDeterministicPerSeed() {
        RandomSource first = RandomSource.create(99L);
        RandomSource second = RandomSource.create(99L);
        for (int i = 0; i < 200; i++) {
            float coverage = (i % 10) / 10.0F;
            assertEquals(DepositionMath.accept(coverage, first), DepositionMath.accept(coverage, second));
        }
    }

    @Test
    void discPointsStayInsideTheDisc() {
        RandomSource random = RandomSource.create(7L);
        double cx = 1234.5D;
        double cz = -987.25D;
        float radius = 96.0F;

        for (int i = 0; i < 5000; i++) {
            double[] point = DepositionMath.randomPointInDisc(cx, cz, radius, random);
            assertEquals(2, point.length);
            double distance = Math.hypot(point[0] - cx, point[1] - cz);
            assertTrue(distance <= radius + 1.0E-9D, "point " + i + " lies outside the disc at " + distance);
        }
    }

    @Test
    void discPointsAreUniformOverTheArea() {
        RandomSource random = RandomSource.create(2024L);
        float radius = 100.0F;
        int samples = 40000;

        int innerHalf = 0;
        int positiveX = 0;
        int positiveZ = 0;
        double sumX = 0.0D;
        double sumZ = 0.0D;
        for (int i = 0; i < samples; i++) {
            double[] point = DepositionMath.randomPointInDisc(0.0D, 0.0D, radius, random);
            if (Math.hypot(point[0], point[1]) <= radius / 2.0D) {
                innerHalf++;
            }
            if (point[0] > 0.0D) {
                positiveX++;
            }
            if (point[1] > 0.0D) {
                positiveZ++;
            }
            sumX += point[0];
            sumZ += point[1];
        }

        assertEquals(0.25D, innerHalf / (double) samples, 0.015D,
            "a disc of half the radius holds a quarter of the area, so a quarter of uniform points");
        assertEquals(0.5D, positiveX / (double) samples, 0.015D, "no bias along x");
        assertEquals(0.5D, positiveZ / (double) samples, 0.015D, "no bias along z");
        assertEquals(0.0D, sumX / samples, 1.5D, "the points centre on the disc centre along x");
        assertEquals(0.0D, sumZ / samples, 1.5D, "the points centre on the disc centre along z");
    }

    @Test
    void nonPositiveRadiusGivesTheCentreWithoutRolling() {
        RandomSource random = RandomSource.create(5L);
        RandomSource untouched = RandomSource.create(5L);

        assertArrayEquals(new double[] {10.0D, -20.0D}, DepositionMath.randomPointInDisc(10.0D, -20.0D, 0.0F, random));
        assertArrayEquals(new double[] {10.0D, -20.0D}, DepositionMath.randomPointInDisc(10.0D, -20.0D, -5.0F, random));
        assertArrayEquals(new double[] {10.0D, -20.0D},
            DepositionMath.randomPointInDisc(10.0D, -20.0D, Float.NaN, random));
        assertEquals(untouched.nextDouble(), random.nextDouble(), "the random source was not consumed");
    }
}
