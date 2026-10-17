package com.weathermod.core;

import java.util.List;
import net.minecraft.resources.ResourceKey;

/**
 * Registry keys of the weather types shipped with the mod. The entries themselves live in
 * {@code data/weathermod/weathermod/weather_type/} and may be replaced by datapacks.
 */
public final class WeatherTypes {
    /** Test storm spawned by the debug commands. Never spawns naturally. */
    public static final ResourceKey<WeatherType> PLACEHOLDER = WeatherRegistries.key("placeholder");
    public static final ResourceKey<WeatherType> OVERCAST = WeatherRegistries.key("overcast");
    public static final ResourceKey<WeatherType> DRIZZLE = WeatherRegistries.key("drizzle");
    public static final ResourceKey<WeatherType> RAIN = WeatherRegistries.key("rain");
    public static final ResourceKey<WeatherType> DOWNPOUR = WeatherRegistries.key("downpour");
    public static final ResourceKey<WeatherType> SNOW = WeatherRegistries.key("snow");
    public static final ResourceKey<WeatherType> SLEET = WeatherRegistries.key("sleet");
    public static final ResourceKey<WeatherType> FOG = WeatherRegistries.key("fog");
    public static final ResourceKey<WeatherType> THUNDERSTORM = WeatherRegistries.key("thunderstorm");
    public static final ResourceKey<WeatherType> HEAT_HAZE = WeatherRegistries.key("heat_haze");

    /** Every key above, in the order they are listed. */
    public static final List<ResourceKey<WeatherType>> ALL = List.of(
        PLACEHOLDER, OVERCAST, DRIZZLE, RAIN, DOWNPOUR, SNOW, SLEET, FOG, THUNDERSTORM, HEAT_HAZE);

    private WeatherTypes() {
    }
}
