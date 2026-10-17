package com.weathermod.server.climate;

import com.weathermod.core.PrecipitationKind;

/**
 * The climate at one point: a temperature on the vanilla biome scale (frozen peaks about -0.7, taiga 0.25, plains
 * 0.8, desert 2.0) and a humidity from 0 to 1. Produced by {@link ClimateField}, read by the storm director and
 * the family systems.
 *
 * @param temperature the temperature on the vanilla biome scale
 * @param humidity    the humidity, 0 bone dry to 1 saturated
 */
public record ClimateSample(float temperature, float humidity) {
    /** A mild, moderately humid climate: the plains with no anomaly. The fallback when nothing better is known. */
    public static final ClimateSample TEMPERATE = new ClimateSample(0.8F, 0.4F);

    /** What falls at this temperature when a type leaves the choice to the climate, see {@link PrecipitationKind#fromTemperature}. */
    public PrecipitationKind precipitationKind() {
        return PrecipitationKind.fromTemperature(temperature);
    }

    /** A copy with another temperature and the same humidity. */
    public ClimateSample withTemperature(float newTemperature) {
        return newTemperature == temperature ? this : new ClimateSample(newTemperature, humidity);
    }
}
