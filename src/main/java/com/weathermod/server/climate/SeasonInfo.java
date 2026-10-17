package com.weathermod.server.climate;

import java.util.Objects;

/**
 * The season a {@link SeasonProvider} reports for one level and biome: a display name, the offsets it adds to the
 * biome's temperature and humidity, and how far the seasonal cycle has run.
 *
 * @param name              the lower-case season name, such as {@code "summer"} or {@code "mid_winter"}
 * @param temperatureOffset the offset added to the biome temperature, on the vanilla biome scale
 * @param humidityOffset    the offset added to the biome humidity
 * @param progress          position in the seasonal cycle, 0 inclusive to 1 exclusive
 */
public record SeasonInfo(String name, float temperatureOffset, float humidityOffset, float progress) {
    /** No season: reported for dimensions without a sky and by providers that are switched off. */
    public static final SeasonInfo NONE = new SeasonInfo("none", 0.0F, 0.0F, 0.0F);

    public SeasonInfo {
        Objects.requireNonNull(name, "name");
    }

    /** True when this is {@link #NONE}. */
    public boolean isNone() {
        return NONE.equals(this);
    }
}
