package com.weathermod.compat.iris;

import com.weathermod.WeatherMod;
import net.neoforged.fml.ModList;

/**
 * Detects Iris and asks it whether a shader pack is active; safe to call when Iris is absent. Nothing in this class
 * names an Iris type, so it loads whether or not that mod is installed; {@link IrisApiAccess} is only touched once
 * {@link #isLoaded()} is true.
 */
public final class IrisCompat {
    /** The Iris mod id, as declared in its mods.toml. */
    public static final String MOD_ID = "iris";

    /** Whether Iris is in the mod list; null until the mod list has answered once. */
    private static volatile Boolean loaded;

    /** Set once the Iris API has thrown; from then on shader packs are reported as inactive without asking again. */
    private static volatile boolean apiFailed;

    private IrisCompat() {
    }

    /**
     * True when Iris is in the mod list, cached after the first call that finds a mod list. False before the mod
     * list exists, as in unit tests; that answer is not cached so a later call in the game still finds Iris.
     */
    public static boolean isLoaded() {
        Boolean cached = loaded;
        if (cached != null) {
            return cached;
        }
        ModList mods = ModList.get();
        if (mods == null) {
            return false;
        }
        boolean present = mods.isLoaded(MOD_ID);
        loaded = present;
        return present;
    }

    /**
     * True when Iris is loaded and reports a shader pack in use. False when Iris is absent. Should the Iris API
     * throw, the failure is logged once at WARN and every later call returns false without asking Iris again.
     */
    public static boolean isShaderPackInUse() {
        if (apiFailed || !isLoaded()) {
            return false;
        }
        try {
            return IrisApiAccess.shaderPackInUse();
        } catch (Throwable t) {
            apiFailed = true;
            WeatherMod.LOGGER.warn("Iris is loaded but its API could not be queried; treating shader packs as inactive", t);
            return false;
        }
    }
}
