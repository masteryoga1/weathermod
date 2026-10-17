package com.weathermod.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

/**
 * Registers the three config files with the mod container. Called once from the mod constructor; the
 * client-side configuration screen is registered separately by the client module.
 */
public final class ConfigSetup {
    private ConfigSetup() {
    }

    /** Registers the server, client and common config specs so NeoForge loads and syncs them. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerConfig(ModConfig.Type.COMMON, CommonConfig.SPEC);
    }
}
