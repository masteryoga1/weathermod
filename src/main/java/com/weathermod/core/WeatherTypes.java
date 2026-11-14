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
    /** A thunderstorm with hail, strong downbursts and gustnadoes; severe weather. */
    public static final ResourceKey<WeatherType> SEVERE_THUNDERSTORM = WeatherRegistries.key("severe_thunderstorm");
    /** The convective type with the strongest hail; severe weather. */
    public static final ResourceKey<WeatherType> HAILSTORM = WeatherRegistries.key("hailstorm");
    /** A fast line of storms with straight wind; forms only along a cold front. */
    public static final ResourceKey<WeatherType> SQUALL_LINE = WeatherRegistries.key("squall_line");
    /** The strongest straight-wind storm; forms only along a cold front; severe weather. */
    public static final ResourceKey<WeatherType> DERECHO = WeatherRegistries.key("derecho");
    /** The slow rotating storm that produces tornadoes; severe weather. */
    public static final ResourceKey<WeatherType> SUPERCELL = WeatherRegistries.key("supercell");
    /** The tier every natural tornado touches down as. */
    public static final ResourceKey<WeatherType> TORNADO_EF0 = WeatherRegistries.key("tornado_ef0");
    public static final ResourceKey<WeatherType> TORNADO_EF1 = WeatherRegistries.key("tornado_ef1");
    public static final ResourceKey<WeatherType> TORNADO_EF2 = WeatherRegistries.key("tornado_ef2");
    public static final ResourceKey<WeatherType> TORNADO_EF3 = WeatherRegistries.key("tornado_ef3");
    public static final ResourceKey<WeatherType> TORNADO_EF4 = WeatherRegistries.key("tornado_ef4");
    public static final ResourceKey<WeatherType> TORNADO_EF5 = WeatherRegistries.key("tornado_ef5");
    /** The small vortex an ordinary thunderstorm spawns. */
    public static final ResourceKey<WeatherType> LANDSPOUT = WeatherRegistries.key("landspout");
    /** The small vortex of the oceans. */
    public static final ResourceKey<WeatherType> WATERSPOUT = WeatherRegistries.key("waterspout");
    /** The small vortex on the gust front of severe thunderstorms, squall lines and derechos. */
    public static final ResourceKey<WeatherType> GUSTNADO = WeatherRegistries.key("gustnado");
    /** The small vortex of hot, dry, calm days. */
    public static final ResourceKey<WeatherType> DUST_DEVIL = WeatherRegistries.key("dust_devil");
    /** The burning vortex of the Nether. */
    public static final ResourceKey<WeatherType> FIRE_WHIRL = WeatherRegistries.key("fire_whirl");

    /** Every key above, in the order they are listed. */
    public static final List<ResourceKey<WeatherType>> ALL = List.of(
        PLACEHOLDER, OVERCAST, DRIZZLE, RAIN, DOWNPOUR, SNOW, SLEET, FOG, THUNDERSTORM, HEAT_HAZE,
        SEVERE_THUNDERSTORM, HAILSTORM, SQUALL_LINE, DERECHO, SUPERCELL,
        TORNADO_EF0, TORNADO_EF1, TORNADO_EF2, TORNADO_EF3, TORNADO_EF4, TORNADO_EF5,
        LANDSPOUT, WATERSPOUT, GUSTNADO, DUST_DEVIL, FIRE_WHIRL);

    /** The six tornado keys in tier order. */
    public static final List<ResourceKey<WeatherType>> TORNADO_TIERS = List.of(
        TORNADO_EF0, TORNADO_EF1, TORNADO_EF2, TORNADO_EF3, TORNADO_EF4, TORNADO_EF5);

    private WeatherTypes() {
    }

    /** The tornado type of a tier, 0..5; other values are clamped. */
    public static ResourceKey<WeatherType> tornado(int tier) {
        return TORNADO_TIERS.get(Math.max(0, Math.min(TORNADO_TIERS.size() - 1, tier)));
    }
}
