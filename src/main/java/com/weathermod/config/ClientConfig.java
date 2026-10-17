package com.weathermod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-only settings: how dense, hazy and loud the weather is drawn, plus the debug overlay toggle.
 * Stored in {@code config/weathermod-client.toml}.
 */
public final class ClientConfig {
    private static final String TRANSLATION_PREFIX = "weathermod.configuration.";

    /** Multiplier on the number of weather particles spawned. */
    public static final ModConfigSpec.DoubleValue particleDensity;
    /** Multiplier on fog and haze thickness produced by storms. */
    public static final ModConfigSpec.DoubleValue hazeStrength;
    /** Volume of all weather sounds played by the mod. */
    public static final ModConfigSpec.DoubleValue soundVolume;
    /** Whether the weather debug overlay is drawn at start-up. */
    public static final ModConfigSpec.BooleanValue debugOverlay;

    /** The built specification handed to NeoForge. */
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        particleDensity = builder
                .comment("Multiplier on the number of rain, snow, dust and debris particles.",
                        "0.0 disables weather particles, 2.0 doubles them.")
                .translation(TRANSLATION_PREFIX + "particleDensity")
                .defineInRange("particleDensity", 1.0D, 0.0D, 2.0D);

        hazeStrength = builder
                .comment("Multiplier on fog and haze thickness caused by storms.",
                        "0.0 disables the fog enhancer, 2.0 doubles the effect.")
                .translation(TRANSLATION_PREFIX + "hazeStrength")
                .defineInRange("hazeStrength", 1.0D, 0.0D, 2.0D);

        soundVolume = builder
                .comment("Volume of wind, rain and storm sounds played by the mod, on top of the game's weather volume.")
                .translation(TRANSLATION_PREFIX + "soundVolume")
                .defineInRange("soundVolume", 1.0D, 0.0D, 1.0D);

        debugOverlay = builder
                .comment("Show the weather debug overlay (wind and storm list) in the top-left corner.",
                        "Can also be toggled in game with the overlay key or /weathermodc overlay.")
                .translation(TRANSLATION_PREFIX + "debugOverlay")
                .define("debugOverlay", false);

        SPEC = builder.build();
    }

    private ClientConfig() {
    }
}
