package com.weathermod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side settings: destruction, budgets, storm frequency and network sync rate. Stored per world in
 * {@code serverconfig/weathermod-server.toml} and synced to clients that join.
 */
public final class ServerConfig {
    private static final String TRANSLATION_PREFIX = "weathermod.configuration.";

    /** How much storms may change the world. */
    public static final ModConfigSpec.EnumValue<DestructionMode> destructionMode;
    /** Blocks the ripper may break per tick across one storm. */
    public static final ModConfigSpec.IntValue blockBudgetPerTick;
    /** Upper bound on flying block entities a single storm may keep alive. */
    public static final ModConfigSpec.IntValue maxFlyingBlocksPerStorm;
    /** Multiplier on natural storm spawn rate; 0 disables natural storms. */
    public static final ModConfigSpec.DoubleValue stormFrequency;
    /** Hard cap on simultaneous storms in one dimension. */
    public static final ModConfigSpec.IntValue maxStormsPerDimension;
    /** Ticks between storm delta packets sent to interested players. */
    public static final ModConfigSpec.IntValue syncIntervalTicks;
    /** Seconds that must pass after severe weather before the next severe storm may form. */
    public static final ModConfigSpec.IntValue severeWeatherCooldownSeconds;

    /** The built specification handed to NeoForge. */
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        destructionMode = builder
                .comment("How much storms may change the world.",
                        "FULL rips blocks and moves entities, ENTITIES_ONLY leaves terrain alone, VISUAL_ONLY touches nothing.")
                .translation(TRANSLATION_PREFIX + "destructionMode")
                .defineEnum("destructionMode", DestructionMode.FULL);

        blockBudgetPerTick = builder
                .comment("Maximum number of blocks one storm may rip out of the ground per tick.")
                .translation(TRANSLATION_PREFIX + "blockBudgetPerTick")
                .defineInRange("blockBudgetPerTick", 16, 1, 256);

        maxFlyingBlocksPerStorm = builder
                .comment("Maximum number of flying block entities a single storm may have in the air at once.",
                        "0 means ripped blocks are destroyed instead of thrown.")
                .translation(TRANSLATION_PREFIX + "maxFlyingBlocksPerStorm")
                .defineInRange("maxFlyingBlocksPerStorm", 48, 0, 512);

        stormFrequency = builder
                .comment("Multiplier applied to how often storms form naturally.",
                        "1.0 is the designed rate, 0.0 disables natural storms entirely.")
                .translation(TRANSLATION_PREFIX + "stormFrequency")
                .defineInRange("stormFrequency", 1.0D, 0.0D, 10.0D);

        maxStormsPerDimension = builder
                .comment("Maximum number of storms that may exist at the same time in one dimension.",
                        "Both natural spawns and commands respect this limit.")
                .translation(TRANSLATION_PREFIX + "maxStormsPerDimension")
                .defineInRange("maxStormsPerDimension", 8, 1, 64);

        syncIntervalTicks = builder
                .comment("Ticks between storm update packets sent to players within a storm's interest radius.",
                        "Lower values are smoother but cost more bandwidth.")
                .translation(TRANSLATION_PREFIX + "syncIntervalTicks")
                .defineInRange("syncIntervalTicks", 10, 1, 100);

        severeWeatherCooldownSeconds = builder
                .comment("Real seconds that must pass after a severe storm ends before another one may form in the same dimension.")
                .translation(TRANSLATION_PREFIX + "severeWeatherCooldownSeconds")
                .defineInRange("severeWeatherCooldownSeconds", 900, 0, 86400);

        SPEC = builder.build();
    }

    private ServerConfig() {
    }
}
