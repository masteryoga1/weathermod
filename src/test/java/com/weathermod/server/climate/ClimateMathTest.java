package com.weathermod.server.climate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.PrecipitationKind;
import org.junit.jupiter.api.Test;

/** Checks the pure climate arithmetic the climate field is built from. Needs no Minecraft bootstrap. */
class ClimateMathTest {
    private static final float EPSILON = 1.0E-5F;

    @Test
    void dailyOffsetPeaksAtNoonAndBottomsAtMidnight() {
        assertEquals(0.08F, ClimateMath.dailyOffset(6000L), EPSILON, "noon is the warmest moment");
        assertEquals(-0.08F, ClimateMath.dailyOffset(18000L), EPSILON, "midnight is the coldest moment");
        assertEquals(0.0F, ClimateMath.dailyOffset(0L), EPSILON, "dawn sits between the two");
        assertEquals(0.0F, ClimateMath.dailyOffset(12000L), EPSILON, "dusk sits between the two");
    }

    @Test
    void dailyOffsetWrapsWholeDaysAndNegativeTimes() {
        assertEquals(ClimateMath.dailyOffset(6000L), ClimateMath.dailyOffset(6000L + 24000L * 37L), EPSILON);
        assertEquals(ClimateMath.dailyOffset(18000L), ClimateMath.dailyOffset(18000L - 24000L * 5L), EPSILON);
        assertEquals(ClimateMath.dailyOffset(3000L), ClimateMath.dailyOffset(-21000L), EPSILON);
    }

    @Test
    void dailyOffsetStaysInsideItsAmplitudeAndIsSymmetricAroundNoon() {
        for (long tick = 0; tick < 24000L; tick += 50L) {
            float offset = ClimateMath.dailyOffset(tick);
            assertTrue(offset >= -0.08F - EPSILON && offset <= 0.08F + EPSILON, "offset out of range at " + tick + ": " + offset);
            long mirrored = 12000L - tick;
            assertEquals(offset, ClimateMath.dailyOffset(mirrored), EPSILON, "offset is symmetric around noon at " + tick);
        }
        assertTrue(ClimateMath.dailyOffset(3000L) > 0.0F, "the morning is already warming");
        assertTrue(ClimateMath.dailyOffset(15000L) < 0.0F, "the evening is already cooling");
    }

    @Test
    void heightAdjustLeavesTheLowlandsAloneAndCoolsAbove() {
        assertEquals(0.8F, ClimateMath.heightAdjust(0.8F, 64), EPSILON);
        assertEquals(0.8F, ClimateMath.heightAdjust(0.8F, 80), EPSILON);
        assertEquals(0.8F, ClimateMath.heightAdjust(0.8F, -60), EPSILON);
        assertEquals(0.8F - 0.1F, ClimateMath.heightAdjust(0.8F, 160), EPSILON, "80 blocks higher costs 0.1");
        assertEquals(0.8F - 0.2F, ClimateMath.heightAdjust(0.8F, 240), EPSILON);
        assertEquals(-0.7F - 0.00125F, ClimateMath.heightAdjust(-0.7F, 81), EPSILON, "one block above the base costs one step");
    }

    @Test
    void anomaliesScaleNoiseAndClampOutOfRangeInputs() {
        assertEquals(0.25F, ClimateMath.temperatureAnomaly(1.0), EPSILON);
        assertEquals(-0.25F, ClimateMath.temperatureAnomaly(-1.0), EPSILON);
        assertEquals(0.0F, ClimateMath.temperatureAnomaly(0.0), EPSILON);
        assertEquals(0.125F, ClimateMath.temperatureAnomaly(0.5), EPSILON);
        assertEquals(0.25F, ClimateMath.temperatureAnomaly(3.0), EPSILON, "noise above 1 is clamped");
        assertEquals(-0.25F, ClimateMath.temperatureAnomaly(-7.5), EPSILON, "noise below -1 is clamped");

        assertEquals(0.2F, ClimateMath.humidityAnomaly(1.0), EPSILON);
        assertEquals(-0.2F, ClimateMath.humidityAnomaly(-1.0), EPSILON);
        assertEquals(-0.1F, ClimateMath.humidityAnomaly(-0.5), EPSILON);
        assertEquals(0.2F, ClimateMath.humidityAnomaly(2.0), EPSILON, "noise above 1 is clamped");
    }

    @Test
    void combineAddsEveryTerm() {
        SeasonInfo summer = new SeasonInfo("summer", 0.30F, -0.05F, 0.375F);
        ClimateSample sample = ClimateMath.combine(0.8F, 0.4F, 0.1F, 0.05F, summer, 0.08F);

        assertEquals(0.8F + 0.1F + 0.30F + 0.08F, sample.temperature(), EPSILON);
        assertEquals(0.4F + 0.05F - 0.05F, sample.humidity(), EPSILON);
    }

    @Test
    void combineWithNoSeasonAndNoAnomalyGivesTheBiomeValues() {
        ClimateSample sample = ClimateMath.combine(0.25F, 0.8F, 0.0F, 0.0F, SeasonInfo.NONE, 0.0F);
        assertEquals(0.25F, sample.temperature(), EPSILON);
        assertEquals(0.8F, sample.humidity(), EPSILON);
    }

    @Test
    void combineClampsTemperatureAndHumidity() {
        SeasonInfo summer = new SeasonInfo("summer", 0.30F, -0.05F, 0.375F);
        SeasonInfo winter = new SeasonInfo("winter", -0.35F, -0.10F, 0.875F);

        ClimateSample hot = ClimateMath.combine(2.0F, 0.0F, 0.25F, -0.2F, summer, 0.08F);
        assertEquals(ClimateMath.MAX_TEMPERATURE, hot.temperature(), EPSILON, "a desert summer noon tops out at 2.5");
        assertEquals(0.0F, hot.humidity(), EPSILON, "humidity never goes negative");

        ClimateSample cold = ClimateMath.combine(-0.7F, 0.9F, -0.25F, 0.2F, winter, -0.08F);
        assertEquals(ClimateMath.MIN_TEMPERATURE, cold.temperature(), EPSILON, "frozen peaks in winter bottom out at -1");
        assertEquals(0.0F, ClimateMath.combine(0.0F, 0.9F, 0.0F, 0.2F, SeasonInfo.NONE, 0.0F).humidity() - 1.0F, EPSILON,
            "humidity never exceeds 1");
    }

    @Test
    void ultraWarmIsHotAndDry() {
        ClimateSample nether = ClimateMath.ultraWarm(2.0F, 0.25F);
        assertEquals(2.0F + 0.125F, nether.temperature(), EPSILON, "half the anomaly applies");
        assertEquals(0.02F, nether.humidity(), EPSILON);

        ClimateSample coolBiome = ClimateMath.ultraWarm(0.5F, -0.25F);
        assertEquals(2.0F - 0.125F, coolBiome.temperature(), EPSILON, "a cool biome is lifted to the ultra warm floor first");

        ClimateSample hotterBiome = ClimateMath.ultraWarm(2.4F, 0.0F);
        assertEquals(2.4F, hotterBiome.temperature(), EPSILON, "a biome above the floor keeps its own temperature");

        assertSame(PrecipitationKind.RAIN, nether.precipitationKind(),
            "ultra warm temperatures resolve to rain, so only types that force another kind differ");
    }

    @Test
    void noSkyIsFixedWhateverTheBiome() {
        ClimateSample end = ClimateMath.noSky(0.5F);
        assertEquals(0.5F, end.temperature(), EPSILON);
        assertEquals(0.0F, end.humidity(), EPSILON);
        assertEquals(end, ClimateMath.noSky(2.0F));
        assertEquals(end, ClimateMath.noSky(-1.0F));
    }

    @Test
    void samplePrecipitationKindFollowsTheTemperatureThresholds() {
        assertSame(PrecipitationKind.RAIN, ClimateSample.TEMPERATE.precipitationKind());
        assertSame(PrecipitationKind.SNOW, new ClimateSample(0.0F, 0.5F).precipitationKind());
        assertSame(PrecipitationKind.SNOW, new ClimateSample(0.09F, 0.5F).precipitationKind());
        assertSame(PrecipitationKind.SLEET, new ClimateSample(0.10F, 0.5F).precipitationKind());
        assertSame(PrecipitationKind.SLEET, new ClimateSample(0.15F, 0.5F).precipitationKind());
        assertSame(PrecipitationKind.RAIN, new ClimateSample(0.20F, 0.5F).precipitationKind());
    }

    @Test
    void sampleWithTemperatureKeepsHumidity() {
        ClimateSample sample = new ClimateSample(0.8F, 0.4F);
        ClimateSample colder = sample.withTemperature(0.3F);
        assertEquals(0.3F, colder.temperature(), EPSILON);
        assertEquals(0.4F, colder.humidity(), EPSILON);
        assertSame(sample, sample.withTemperature(0.8F), "an unchanged temperature returns the same sample");
    }

    @Test
    void temperateSampleIsThePlains() {
        assertEquals(0.8F, ClimateSample.TEMPERATE.temperature(), EPSILON);
        assertEquals(0.4F, ClimateSample.TEMPERATE.humidity(), EPSILON);
    }
}
