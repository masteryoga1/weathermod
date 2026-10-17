package com.weathermod.server.climate;

import com.weathermod.WeatherMod;
import com.weathermod.compat.sereneseasons.SereneSeasonsCompat;

/**
 * Chooses the season provider for a world: Serene Seasons when that mod is installed, otherwise the built-in
 * day-count seasons with the configured year length.
 */
public final class SeasonProviders {
    private SeasonProviders() {
    }

    /**
     * Creates the provider to use. With Serene Seasons loaded its provider wins; should creating it fail, the
     * failure is logged and the built-in provider is used so the climate field always has a source of seasons.
     *
     * @param builtInYearLengthDays the year length for the built-in provider, zero or less disabling it
     */
    public static SeasonProvider create(int builtInYearLengthDays) {
        if (SereneSeasonsCompat.isLoaded()) {
            try {
                SeasonProvider provider = SereneSeasonsCompat.createProvider();
                if (provider != null) {
                    WeatherMod.LOGGER.debug("Seasons follow Serene Seasons");
                    return provider;
                }
                WeatherMod.LOGGER.warn("Serene Seasons is loaded but gave no season provider; using the built-in seasons");
            } catch (RuntimeException | LinkageError e) {
                WeatherMod.LOGGER.warn("Serene Seasons is loaded but its season provider could not be created; using the built-in seasons", e);
            }
        }
        return new BuiltInSeasonProvider(builtInYearLengthDays);
    }
}
