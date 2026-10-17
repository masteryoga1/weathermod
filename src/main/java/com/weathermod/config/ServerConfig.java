package com.weathermod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side settings: destruction, budgets, storm frequency, the director's spawn interval, the vanilla weather
 * bridge, snow and ice deposition, the built-in seasons and network sync rate. Written to
 * {@code config/weathermod-server.toml} (a copy under the world's {@code serverconfig} folder overrides it) and
 * synced to clients that join.
 */
public final class ServerConfig {
    /** How much storms may change the world. */
    public static final ModConfigSpec.EnumValue<DestructionMode> destructionMode;
    /** Blocks the ripper may break per tick across one storm. */
    public static final ModConfigSpec.IntValue blockBudgetPerTick;
    /** Upper bound on flying block entities a single storm may keep alive. */
    public static final ModConfigSpec.IntValue maxFlyingBlocksPerStorm;
    /** Multiplier on natural storm spawn rate; 0 disables natural storms. */
    public static final ModConfigSpec.DoubleValue stormFrequency;
    /** Seconds between the director's natural spawn attempts at a storm frequency of one. */
    public static final ModConfigSpec.IntValue naturalSpawnIntervalSeconds;
    /** Fraction of the wind speed at which storms travel. */
    public static final ModConfigSpec.DoubleValue stormDriftFactor;
    /** Hard cap on simultaneous storms in one dimension. */
    public static final ModConfigSpec.IntValue maxStormsPerDimension;
    /** Ticks between storm delta packets sent to interested players. */
    public static final ModConfigSpec.IntValue syncIntervalTicks;
    /** Seconds that must pass after severe weather before the next severe storm may form. */
    public static final ModConfigSpec.IntValue severeWeatherCooldownSeconds;
    /** Whether vanilla rain and thunder follow the mod's precipitation storms. */
    public static final ModConfigSpec.BooleanValue vanillaWeatherBridge;
    /** Whether snow storms lay snow layers on the ground. */
    public static final ModConfigSpec.BooleanValue snowAccumulation;
    /** Whether snow storms freeze still water into ice. */
    public static final ModConfigSpec.BooleanValue iceFormation;
    /** Deposition attempts per precipitation storm per tick; 0 disables deposition. */
    public static final ModConfigSpec.IntValue depositionBlocksPerTick;
    /** Length of the built-in seasonal year in days; 0 disables the built-in seasons. */
    public static final ModConfigSpec.IntValue builtInYearLengthDays;
    /** Whether sleeping through the night ends the precipitation storms over the sleepers. */
    public static final ModConfigSpec.BooleanValue endStormsOnSleep;

    /** The built specification handed to NeoForge. */
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        destructionMode = builder
                .comment("How much storms may change the world.",
                        "FULL rips blocks and moves entities, ENTITIES_ONLY moves entities but never rips or throws blocks,",
                        "VISUAL_ONLY touches nothing. Snow and ice deposition follow snowAccumulation and iceFormation",
                        "and are disabled only by VISUAL_ONLY.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "destructionMode")
                .defineEnum("destructionMode", DestructionMode.FULL);

        blockBudgetPerTick = builder
                .comment("Maximum number of blocks one storm may rip out of the ground per tick.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "blockBudgetPerTick")
                .defineInRange("blockBudgetPerTick", 16, 1, 256);

        maxFlyingBlocksPerStorm = builder
                .comment("Maximum number of flying block entities a single storm may have in the air at once.",
                        "0 means ripped blocks are destroyed instead of thrown.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "maxFlyingBlocksPerStorm")
                .defineInRange("maxFlyingBlocksPerStorm", 48, 0, 512);

        stormFrequency = builder
                .comment("Multiplier applied to how often storms form naturally.",
                        "1.0 is the designed rate, 0.0 disables natural storms entirely.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "stormFrequency")
                .defineInRange("stormFrequency", 1.0D, 0.0D, 10.0D);

        naturalSpawnIntervalSeconds = builder
                .comment("Real seconds between the storm director's natural spawn attempts at a storm frequency of 1.0.",
                        "Each wait is divided by stormFrequency and varied by up to half either way.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "naturalSpawnIntervalSeconds")
                .defineInRange("naturalSpawnIntervalSeconds", 240, 10, 86400);

        stormDriftFactor = builder
                .comment("Fraction of the wind speed at which storms travel across the world.",
                        "1.0 carries a 128-block rain storm past a player in under a minute; 0.3 gives a few minutes of rain.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "stormDriftFactor")
                .defineInRange("stormDriftFactor", 0.3D, 0.05D, 1.0D);

        maxStormsPerDimension = builder
                .comment("Maximum number of storms that may exist at the same time in one dimension.",
                        "Both natural spawns and commands respect this limit.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "maxStormsPerDimension")
                .defineInRange("maxStormsPerDimension", 8, 1, 64);

        syncIntervalTicks = builder
                .comment("Ticks between storm update packets sent to players within a storm's interest radius.",
                        "Lower values are smoother but cost more bandwidth.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "syncIntervalTicks")
                .defineInRange("syncIntervalTicks", 10, 1, 100);

        severeWeatherCooldownSeconds = builder
                .comment("Real seconds that must pass after a severe storm ends before another one may form in the same dimension.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "severeWeatherCooldownSeconds")
                .defineInRange("severeWeatherCooldownSeconds", 900, 0, 86400);

        vanillaWeatherBridge = builder
                .comment("Keep the vanilla rain and thunder flags in step with the mod's storms.",
                        "Vanilla rains while a precipitation storm covers a player and is dry otherwise,",
                        "so crops, cauldrons and fishing respond. Off leaves the vanilla weather cycle alone.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "vanillaWeatherBridge")
                .define("vanillaWeatherBridge", true);

        snowAccumulation = builder
                .comment("Let snow storms lay snow layers on the ground under them,",
                        "up to the snowAccumulationHeight game rule.",
                        "Ignored while destructionMode is VISUAL_ONLY.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "snowAccumulation")
                .define("snowAccumulation", true);

        iceFormation = builder
                .comment("Let snow storms freeze still water under them into ice.",
                        "Ignored while destructionMode is VISUAL_ONLY.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "iceFormation")
                .define("iceFormation", true);

        depositionBlocksPerTick = builder
                .comment("Positions each precipitation storm tries to lay snow or ice on per tick.",
                        "0 disables deposition, higher values build snow faster at more server cost.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "depositionBlocksPerTick")
                .defineInRange("depositionBlocksPerTick", 4, 0, 64);

        builtInYearLengthDays = builder
                .comment("Length of the built-in seasonal year in game days; the four seasons each take a quarter of it.",
                        "0 disables the built-in seasons.",
                        "Ignored when Serene Seasons is installed, which then drives the seasons.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "builtInYearLengthDays")
                .defineInRange("builtInYearLengthDays", 28, 0, 3650);

        endStormsOnSleep = builder
                .comment("End the precipitation storms covering players who sleep through the night, as vanilla ends its rain.",
                        "The storms ramp down instead of vanishing at once.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "endStormsOnSleep")
                .define("endStormsOnSleep", true);

        SPEC = builder.build();
    }

    private ServerConfig() {
    }
}
