package com.weathermod.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side settings: destruction, budgets, storm frequency, the director's spawn interval, the vanilla weather
 * bridge, snow and ice deposition, the built-in seasons, network sync rate, lightning, hail, tornadoes and small
 * vortices, what wind and vortices may push, the fate of ripped and thrown blocks and the reach of the tornado
 * siren and the weather deflector. Written to {@code config/weathermod-server.toml} (a copy under the world's
 * {@code serverconfig} folder overrides it) and synced to clients that join.
 */
public final class ServerConfig {
    /** How much storms may change the world. */
    public static final ModConfigSpec.EnumValue<DestructionMode> destructionMode;
    /** Blocks one tornado may remove per tick at full strength; a dimension removes at most twice this. */
    public static final ModConfigSpec.IntValue blockBudgetPerTick;
    /** Flying blocks one tornado may keep in the air; a dimension holds at most twice this many. */
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
    /** Whether vanilla rain and thunder follow the mod's precipitating storms. */
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
    /** Whether lightning starts fires: never, by the storm type's chance, or on every ground strike. */
    public static final ModConfigSpec.EnumValue<LightningFire> lightningFire;
    /** Whether vanilla's own random lightning and skeleton traps are dropped while the mod drives the thunder. */
    public static final ModConfigSpec.BooleanValue suppressVanillaLightning;
    /** Multiplier on how often storms strike lightning; 0 disables mod lightning. */
    public static final ModConfigSpec.DoubleValue lightningFrequency;
    /** Whether hail breaks crops and small plants. */
    public static final ModConfigSpec.BooleanValue hailBlockDamage;
    /** Whether strong hail also breaks glass that lies open to the sky. */
    public static final ModConfigSpec.BooleanValue hailBreaksGlass;
    /** Whether hail may kill mobs; when off it never takes a mob below one heart. */
    public static final ModConfigSpec.BooleanValue hailKillsMobs;
    /** Multiplier on how often storms produce tornadoes and small vortices; 0 disables them. */
    public static final ModConfigSpec.DoubleValue tornadoFrequency;
    /** Highest EF tier a natural tornado may grow to; commands ignore it. */
    public static final ModConfigSpec.IntValue maxTornadoTier;
    /** Whether fire whirls set blocks on fire. */
    public static final ModConfigSpec.BooleanValue vortexFire;
    /** Whether wind and vortices move players. */
    public static final ModConfigSpec.BooleanValue pushPlayers;
    /** Whether wind and vortices move mobs, vehicles and other entities. */
    public static final ModConfigSpec.BooleanValue pushMobs;
    /** Whether wind and vortices move dropped items and experience orbs. */
    public static final ModConfigSpec.BooleanValue pushItems;
    /** Fall damage after being thrown by a vortex: full, halved or none. */
    public static final ModConfigSpec.EnumValue<FallDamageMode> tornadoFallDamage;
    /** Chance that a ripped block that is not thrown drops as an item. */
    public static final ModConfigSpec.DoubleValue rippedBlockDropChance;
    /** Chance that a thrown block is placed where it lands instead of shattering. */
    public static final ModConfigSpec.DoubleValue flyingBlockLandChance;
    /** Blocks from a tornado's edge at which tornado sirens start. */
    public static final ModConfigSpec.IntValue sirenRange;
    /** Blocks a weather deflector protects. */
    public static final ModConfigSpec.IntValue deflectorRadius;

    /** The built specification handed to NeoForge. */
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        destructionMode = builder
                .comment("How much storms may change the world.",
                        "FULL: storms rip blocks, and lightning may start fires, powers lightning rods and cleans",
                        "struck copper as vanilla lightning does.",
                        "ENTITIES_ONLY: storms push and hurt but change no block",
                        "(bolts light nothing and power no rod).",
                        "VISUAL_ONLY: storms are only shown.",
                        "In every mode a bolt is still heard by sculk sensors, and in FULL a bolt that cleans a copper",
                        "block may also clean copper next to it, as in vanilla.",
                        "Snow and ice deposition are the exception: they follow snowAccumulation and iceFormation",
                        "and are disabled only by VISUAL_ONLY.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "destructionMode")
                .defineEnum("destructionMode", DestructionMode.FULL);

        blockBudgetPerTick = builder
                .comment("Blocks one tornado may remove per tick at full strength (an EF5; weaker tiers take a share).",
                        "All storms of a dimension together remove at most twice this per tick.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "blockBudgetPerTick")
                .defineInRange("blockBudgetPerTick", 16, 1, 256);

        maxFlyingBlocksPerStorm = builder
                .comment("Flying blocks one tornado may keep in the air (an EF4 or EF5; weaker tiers take a share).",
                        "A dimension holds at most twice this many at once. 0 turns flying blocks off.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "maxFlyingBlocksPerStorm")
                .defineInRange("maxFlyingBlocksPerStorm", 48, 0, 512);

        stormFrequency = builder
                .comment("Multiplier applied to how often storms form naturally.",
                        "1.0 is the designed rate, 0.0 disables natural storms entirely.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "stormFrequency")
                .defineInRange("stormFrequency", 1.0D, 0.0D, 10.0D);

        naturalSpawnIntervalSeconds = builder
                .comment("Real seconds between the storm director's natural spawn attempts",
                        "at a storm frequency of 1.0.",
                        "Each wait is divided by stormFrequency and varied by up to half either way.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "naturalSpawnIntervalSeconds")
                .defineInRange("naturalSpawnIntervalSeconds", 240, 10, 86400);

        stormDriftFactor = builder
                .comment("Fraction of the wind speed at which storms travel across the world.",
                        "1.0 carries a 128-block rain storm past a player in under a minute;",
                        "0.3 gives a few minutes of rain.")
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
                .comment("Real seconds that must pass after a severe storm ends",
                        "before another one may form in the same dimension.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "severeWeatherCooldownSeconds")
                .defineInRange("severeWeatherCooldownSeconds", 900, 0, 86400);

        vanillaWeatherBridge = builder
                .comment("Keep the vanilla rain and thunder flags in step with the mod's storms.",
                        "Vanilla rains while a precipitation storm or a thunderstorm covers a player",
                        "and is dry otherwise, and a thunderstorm switches vanilla thunder on as well,",
                        "so crops, cauldrons, fishing, beds and Channeling respond.",
                        "Off leaves the vanilla weather cycle alone.")
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
                .comment("Length of the built-in seasonal year in game days;",
                        "the four seasons each take a quarter of it.",
                        "0 disables the built-in seasons.",
                        "Ignored when Serene Seasons is installed, which then drives the seasons.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "builtInYearLengthDays")
                .defineInRange("builtInYearLengthDays", 28, 0, 3650);

        endStormsOnSleep = builder
                .comment("End the precipitation storms covering players who sleep through the night,",
                        "as vanilla ends its rain.",
                        "The storms ramp down instead of vanishing at once.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "endStormsOnSleep")
                .define("endStormsOnSleep", true);

        lightningFire = builder
                .comment("Whether lightning starts fires.",
                        "OFF: never.",
                        "NORMAL: by the storm type's chance and only while the doFireTick game rule is on.",
                        "ALWAYS: on every ground strike.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "lightningFire")
                .defineEnum("lightningFire", LightningFire.NORMAL);

        suppressVanillaLightning = builder
                .comment("Drop vanilla's own random lightning and skeleton traps",
                        "while the mod drives the thunder flag,",
                        "so every bolt under a storm follows the mod's damage and fire rules",
                        "(the mod's own strikes can still spring a skeleton trap).",
                        "This also drops lightning other mods or datapacks spawn from block ticks during a storm.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "suppressVanillaLightning")
                .define("suppressVanillaLightning", true);

        lightningFrequency = builder
                .comment("Multiplier on how often storms strike lightning.",
                        "0.0 disables mod lightning.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "lightningFrequency")
                .defineInRange("lightningFrequency", 1.0D, 0.0D, 10.0D);

        hailBlockDamage = builder
                .comment("Hail breaks crops and small plants.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "hailBlockDamage")
                .define("hailBlockDamage", true);

        hailBreaksGlass = builder
                .comment("Strong hail also breaks glass that lies open to the sky (skylights, greenhouse roofs).")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "hailBreaksGlass")
                .define("hailBreaksGlass", false);

        hailKillsMobs = builder
                .comment("Hail may kill mobs.",
                        "When off it never takes a mob below one heart (players can always die).")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "hailKillsMobs")
                .define("hailKillsMobs", false);

        tornadoFrequency = builder
                .comment("Multiplier on how often storms produce tornadoes and small vortices.",
                        "0.0 disables them.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "tornadoFrequency")
                .defineInRange("tornadoFrequency", 1.0D, 0.0D, 10.0D);

        maxTornadoTier = builder
                .comment("Highest EF tier a natural tornado may grow to.",
                        "Commands ignore it.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "maxTornadoTier")
                .defineInRange("maxTornadoTier", 5, 0, 5);

        vortexFire = builder
                .comment("Fire whirls set blocks on fire.",
                        "Needs the doFireTick game rule;",
                        "fire on netherrack and soul sand goes out again after ten seconds.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "vortexFire")
                .define("vortexFire", true);

        pushPlayers = builder
                .comment("Wind and vortices move players.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "pushPlayers")
                .define("pushPlayers", true);

        pushMobs = builder
                .comment("Wind and vortices move mobs, vehicles and other entities.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "pushMobs")
                .define("pushMobs", true);

        pushItems = builder
                .comment("Wind and vortices move dropped items and experience orbs.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "pushItems")
                .define("pushItems", true);

        tornadoFallDamage = builder
                .comment("Fall damage after being thrown by a vortex.",
                        "FULL: as any fall. REDUCED: halved. NONE: none.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "tornadoFallDamage")
                .defineEnum("tornadoFallDamage", FallDamageMode.REDUCED);

        rippedBlockDropChance = builder
                .comment("Chance that a ripped block that is not thrown drops as an item.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "rippedBlockDropChance")
                .defineInRange("rippedBlockDropChance", 0.1D, 0.0D, 1.0D);

        flyingBlockLandChance = builder
                .comment("Chance that a thrown block is placed where it lands instead of shattering.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "flyingBlockLandChance")
                .defineInRange("flyingBlockLandChance", 0.5D, 0.0D, 1.0D);

        sirenRange = builder
                .comment("Blocks from a tornado's edge at which tornado sirens start.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "sirenRange")
                .defineInRange("sirenRange", 256, 16, 1024);

        deflectorRadius = builder
                .comment("Blocks a weather deflector protects.")
                .translation(ConfigSetup.TRANSLATION_PREFIX + "deflectorRadius")
                .defineInRange("deflectorRadius", 48, 8, 96);

        SPEC = builder.build();
    }

    private ServerConfig() {
    }
}
