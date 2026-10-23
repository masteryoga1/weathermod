package com.weathermod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-only settings: how dense, hazy and loud the weather is drawn, which of the client weather systems run
 * (rain level driver, precipitation renderer, haze and wind particles) and the debug overlay toggle.
 * Stored in {@code config/weathermod-client.toml}.
 */
public final class ClientConfig {
    /** Multiplier on the number of weather particles spawned. */
    public static final ModConfigSpec.DoubleValue particleDensity;
    /** Multiplier on fog and haze thickness produced by storms. */
    public static final ModConfigSpec.DoubleValue hazeStrength;
    /** Volume of all weather sounds played by the mod. */
    public static final ModConfigSpec.DoubleValue soundVolume;
    /** Whether the weather debug overlay is drawn at start-up. */
    public static final ModConfigSpec.BooleanValue debugOverlay;
    /** Whether the client writes the vanilla rain and thunder levels from nearby storms each tick. */
    public static final ModConfigSpec.BooleanValue rainLevelDriver;
    /** Whether the mod's renderer draws rain, snow and sleet inside the vanilla rain hook. */
    public static final ModConfigSpec.BooleanValue precipitationRenderer;
    /** Whether drifting haze particles are spawned under fog, overcast and storms. */
    public static final ModConfigSpec.BooleanValue hazeParticles;
    /** Whether dust and leaf particles are spawned when the wind blows hard. */
    public static final ModConfigSpec.BooleanValue windParticles;

    /** The built specification handed to NeoForge. */
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        particleDensity = builder
                .comment("Multiplier on the number of rain, snow, dust and debris particles.",
                        "0.0 disables weather particles, 2.0 doubles them.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "particleDensity")
                .defineInRange("particleDensity", 1.0D, 0.0D, 2.0D);

        hazeStrength = builder
                .comment("Multiplier on fog and haze thickness caused by storms.",
                        "0.0 disables the fog enhancer, 2.0 doubles the effect.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "hazeStrength")
                .defineInRange("hazeStrength", 1.0D, 0.0D, 2.0D);

        soundVolume = builder
                .comment("Volume of wind, rain and storm sounds played by the mod, on top of the game's weather volume.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "soundVolume")
                .defineInRange("soundVolume", 1.0D, 0.0D, 1.0D);

        debugOverlay = builder
                .comment("Show the weather debug overlay (wind and storm list) in the top-left corner.",
                        "Can also be toggled in game with the overlay key or /weathermodc overlay.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "debugOverlay")
                .define("debugOverlay", false);

        rainLevelDriver = builder
                .comment("Write the vanilla rain and thunder levels from nearby storms each tick,",
                        "which is what shader packs read for darkening, wet surfaces and puddles.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "rainLevelDriver")
                .define("rainLevelDriver", true);

        precipitationRenderer = builder
                .comment("Draw rain, snow and sleet with the mod's renderer inside the vanilla rain hook.",
                        "Off: vanilla draws its own rain at the driven rain level.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "precipitationRenderer")
                .define("precipitationRenderer", true);

        hazeParticles = builder
                .comment("Spawn drifting haze particles under fog, overcast and storms.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "hazeParticles")
                .define("hazeParticles", true);

        windParticles = builder
                .comment("Spawn dust and leaves blown by the wind.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "windParticles")
                .define("windParticles", true);

        SPEC = builder.build();
    }

    private ClientConfig() {
    }
}
