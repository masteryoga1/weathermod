package com.weathermod.server.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Checks the level-free part of the climate field: the anomaly noise is deterministic per seed, bounded, varies
 * across the map and drifts with time. Needs no Minecraft bootstrap.
 */
class ClimateFieldTest {
    private static final double EPSILON = 1.0E-9D;

    private static ClimateField field(long seed) {
        return new ClimateField(seed, new BuiltInSeasonProvider(28));
    }

    @Test
    void sameSeedGivesTheSameNoise() {
        ClimateField first = field(42L);
        ClimateField second = field(42L);

        for (int i = 0; i < 50; i++) {
            double x = i * 517.0D - 12000.0D;
            double z = i * -311.0D + 4000.0D;
            long time = i * 7331L;
            assertEquals(first.temperatureNoise(x, z, time), second.temperatureNoise(x, z, time), EPSILON);
            assertEquals(first.humidityNoise(x, z, time), second.humidityNoise(x, z, time), EPSILON);
        }
        assertEquals(42L, first.seed());
    }

    @Test
    void differentSeedsGiveDifferentNoise() {
        ClimateField first = field(1L);
        ClimateField second = field(2L);

        int differences = 0;
        for (int i = 0; i < 50; i++) {
            double x = i * 900.0D;
            double z = i * 1300.0D;
            if (Math.abs(first.temperatureNoise(x, z, 0L) - second.temperatureNoise(x, z, 0L)) > 1.0E-6D) {
                differences++;
            }
        }
        assertTrue(differences > 40, "different seeds should give a different pattern, got " + differences + " differing points");
    }

    @Test
    void temperatureAndHumidityNoiseAreIndependent() {
        ClimateField field = field(7L);

        int differences = 0;
        for (int i = 0; i < 50; i++) {
            double x = i * 700.0D + 100.0D;
            double z = i * 1100.0D - 2000.0D;
            if (Math.abs(field.temperatureNoise(x, z, 0L) - field.humidityNoise(x, z, 0L)) > 1.0E-6D) {
                differences++;
            }
        }
        assertTrue(differences > 40, "temperature and humidity noise should not be the same field");
    }

    @Test
    void noiseStaysNearTheUnitRangeAndAnomaliesInsideTheirs() {
        ClimateField field = field(2024L);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (int ix = -20; ix <= 20; ix++) {
            for (int iz = -20; iz <= 20; iz++) {
                double x = ix * 613.0D;
                double z = iz * 487.0D;
                double noise = field.temperatureNoise(x, z, 12345L);
                min = Math.min(min, noise);
                max = Math.max(max, noise);
                assertTrue(Math.abs(noise) <= 1.1D, "noise far outside the unit range at " + x + ", " + z + ": " + noise);

                float temperatureAnomaly = field.temperatureAnomaly(x, z, 12345L);
                float humidityAnomaly = field.humidityAnomaly(x, z, 12345L);
                assertTrue(temperatureAnomaly >= -0.25F && temperatureAnomaly <= 0.25F);
                assertTrue(humidityAnomaly >= -0.2F && humidityAnomaly <= 0.2F);
            }
        }
        assertTrue(max > 0.2D, "the field has warm spots, max was " + max);
        assertTrue(min < -0.2D, "the field has cold spots, min was " + min);
    }

    @Test
    void noiseIsSmoothOverNeighbouringBlocksAndDriftsWithTime() {
        ClimateField field = field(99L);

        double here = field.temperatureNoise(1000.0D, -500.0D, 0L);
        double nextBlock = field.temperatureNoise(1001.0D, -500.0D, 0L);
        assertEquals(here, nextBlock, 0.01D, "one block over the anomaly is nearly the same");

        double nextTick = field.temperatureNoise(1000.0D, -500.0D, 1L);
        assertEquals(here, nextTick, 0.001D, "one tick later the anomaly is nearly the same");

        int changed = 0;
        for (int i = 0; i < 20; i++) {
            double x = i * 1500.0D;
            double now = field.temperatureNoise(x, 0.0D, 0L);
            double fourDaysLater = field.temperatureNoise(x, 0.0D, Math.round(ClimateField.DRIFT_TICKS * 2.0D));
            if (Math.abs(now - fourDaysLater) > 0.01D) {
                changed++;
            }
        }
        assertTrue(changed > 10, "the pattern should drift over days, " + changed + " of 20 points changed");
    }

    @Test
    void constantsMatchTheDesign() {
        assertEquals(1.0D / 2048.0D, ClimateField.ANOMALY_SCALE, EPSILON);
        assertEquals(96000.0D, ClimateField.DRIFT_TICKS, EPSILON);
        assertEquals(4L * 24000L, Math.round(ClimateField.DRIFT_TICKS), "the pattern drifts one noise unit in four days");
    }

    @Test
    void fieldKeepsItsSeasonProvider() {
        BuiltInSeasonProvider seasons = new BuiltInSeasonProvider(28);
        ClimateField field = new ClimateField(5L, seasons);
        assertSame(seasons, field.seasons());
        assertNotEquals(field(5L).seed(), field(6L).seed());
    }
}
