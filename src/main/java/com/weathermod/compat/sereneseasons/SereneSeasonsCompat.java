package com.weathermod.compat.sereneseasons;

import com.weathermod.server.climate.SeasonProvider;
import net.neoforged.fml.ModList;

/**
 * Entry point of the optional Serene Seasons integration. Nothing in this class names a Serene Seasons type, so it
 * loads whether or not that mod is installed; {@link #createProvider()} is only called once {@link #isLoaded()} is true.
 */
public final class SereneSeasonsCompat {
    /** The Serene Seasons mod id, as declared in its mods.toml. */
    public static final String MOD_ID = "sereneseasons";

    private SereneSeasonsCompat() {
    }

    /** True when Serene Seasons is in the mod list. False before the mod list exists, as in unit tests. */
    public static boolean isLoaded() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(MOD_ID);
    }

    /**
     * Creates the season provider that reads Serene Seasons. Only call this when {@link #isLoaded()} is true: the
     * provider class links against the Serene Seasons API and cannot be loaded without it.
     */
    public static SeasonProvider createProvider() {
        return new SereneSeasonsSeasonProvider();
    }
}
