package com.weathermod.server.climate;

import net.minecraft.util.Mth;

/**
 * Pure arithmetic of the climate field: the daily temperature swing, the lapse rate above the lowlands, the scaling
 * of noise into anomalies and the per-dimension rules that turn biome values into a {@link ClimateSample}. Nothing
 * here touches a level, so all of it is unit tested.
 */
public final class ClimateMath {
    /** Game ticks in one day. */
    public static final long TICKS_PER_DAY = 24000L;
    /** Tick of the day the temperature peaks at. */
    public static final long NOON_TICK = 6000L;
    /** Size of the daily swing: the offset is this at noon and its negative at midnight. */
    public static final float DAILY_AMPLITUDE = 0.08F;
    /** Height up to which the temperature is the biome's own; above it the lapse rate applies. */
    public static final int LAPSE_BASE_Y = 80;
    /** Temperature lost per block above {@link #LAPSE_BASE_Y}. */
    public static final float LAPSE_PER_BLOCK = 0.00125F;
    /** Largest temperature anomaly the noise field adds or removes. */
    public static final float TEMPERATURE_ANOMALY_AMPLITUDE = 0.25F;
    /** Largest humidity anomaly the noise field adds or removes. */
    public static final float HUMIDITY_ANOMALY_AMPLITUDE = 0.20F;
    /** Coldest temperature a combined sample may report. */
    public static final float MIN_TEMPERATURE = -1.0F;
    /** Hottest temperature a combined sample may report. */
    public static final float MAX_TEMPERATURE = 2.5F;
    /** Floor of the base temperature in ultra warm dimensions such as the Nether. */
    public static final float ULTRA_WARM_MIN_TEMPERATURE = 2.0F;
    /** How much of the temperature anomaly ultra warm dimensions keep. */
    public static final float ULTRA_WARM_ANOMALY_FACTOR = 0.5F;
    /** Fixed humidity of ultra warm dimensions. */
    public static final float ULTRA_WARM_HUMIDITY = 0.02F;
    /** Fixed temperature of dimensions without sky light such as the End. */
    public static final float NO_SKY_TEMPERATURE = 0.5F;
    /** Fixed humidity of dimensions without sky light. */
    public static final float NO_SKY_HUMIDITY = 0.0F;

    private ClimateMath() {
    }

    /**
     * The temperature offset of the time of day: a cosine that is {@code +DAILY_AMPLITUDE} at tick 6000 (noon),
     * {@code -DAILY_AMPLITUDE} at tick 18000 (midnight) and zero at dawn and dusk. Any day time works, including
     * negative values and values beyond one day.
     */
    public static float dailyOffset(long dayTime) {
        double phase = (Math.floorMod(dayTime, TICKS_PER_DAY) - NOON_TICK) / (double) TICKS_PER_DAY;
        return (float) (DAILY_AMPLITUDE * Math.cos(2.0 * Math.PI * phase));
    }

    /** Cools a temperature with height: unchanged up to {@link #LAPSE_BASE_Y}, then {@link #LAPSE_PER_BLOCK} colder per block. */
    public static float heightAdjust(float temperature, int y) {
        if (y <= LAPSE_BASE_Y) {
            return temperature;
        }
        return temperature - (y - LAPSE_BASE_Y) * LAPSE_PER_BLOCK;
    }

    /** Scales a noise value in {@code [-1, 1]} to a temperature anomaly in {@code [-0.25, 0.25]}; values outside are clamped first. */
    public static float temperatureAnomaly(double noise) {
        return (float) (Mth.clamp(noise, -1.0, 1.0) * TEMPERATURE_ANOMALY_AMPLITUDE);
    }

    /** Scales a noise value in {@code [-1, 1]} to a humidity anomaly in {@code [-0.20, 0.20]}; values outside are clamped first. */
    public static float humidityAnomaly(double noise) {
        return (float) (Mth.clamp(noise, -1.0, 1.0) * HUMIDITY_ANOMALY_AMPLITUDE);
    }

    /**
     * The climate of a dimension with a sky: biome values plus anomalies, the season's offsets and the daily swing.
     * The temperature is clamped to {@code [MIN_TEMPERATURE, MAX_TEMPERATURE]} and the humidity to {@code [0, 1]}.
     */
    public static ClimateSample combine(float baseTemperature, float baseHumidity, float temperatureAnomaly,
                                        float humidityAnomaly, SeasonInfo season, float dailyOffset) {
        float temperature = Mth.clamp(
            baseTemperature + temperatureAnomaly + season.temperatureOffset() + dailyOffset,
            MIN_TEMPERATURE, MAX_TEMPERATURE);
        float humidity = Mth.clamp(baseHumidity + humidityAnomaly + season.humidityOffset(), 0.0F, 1.0F);
        return new ClimateSample(temperature, humidity);
    }

    /**
     * The climate of an ultra warm dimension such as the Nether: never below {@link #ULTRA_WARM_MIN_TEMPERATURE},
     * half the temperature anomaly on top, no seasons, no daily swing and a fixed, nearly dry humidity.
     */
    public static ClimateSample ultraWarm(float baseTemperature, float temperatureAnomaly) {
        float temperature = Math.max(ULTRA_WARM_MIN_TEMPERATURE, baseTemperature)
            + temperatureAnomaly * ULTRA_WARM_ANOMALY_FACTOR;
        return new ClimateSample(temperature, ULTRA_WARM_HUMIDITY);
    }

    /**
     * The climate of a dimension without sky light such as the End: a fixed cool, dry sample. The biome temperature
     * is accepted for symmetry with the other rules but does not matter.
     */
    public static ClimateSample noSky(float baseTemperature) {
        return new ClimateSample(NO_SKY_TEMPERATURE, NO_SKY_HUMIDITY);
    }
}
