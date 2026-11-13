package com.weathermod.server;

import com.weathermod.config.CommonConfig;
import com.weathermod.config.DestructionMode;
import com.weathermod.config.FallDamageMode;
import com.weathermod.config.LightningFire;
import com.weathermod.config.ServerConfig;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.network.StormRemovedPayload;
import com.weathermod.network.WeatherNetwork;
import com.weathermod.server.climate.ClimateField;
import com.weathermod.server.family.VortexState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Entry point for everything outside the simulation that wants to query or change a dimension's weather.
 * Wraps config lookups with safe defaults for before the server config loads (the title screen, item tooltips,
 * unit tests) and pairs storm changes with the network messages they require.
 */
public final class WeatherManager {
    /** Sync interval used before the server config has loaded. */
    public static final int DEFAULT_SYNC_INTERVAL_TICKS = 10;
    /** Storm cap used before the server config has loaded. */
    public static final int DEFAULT_MAX_STORMS_PER_DIMENSION = 8;
    /** Natural spawn frequency multiplier used before the server config has loaded. */
    public static final double DEFAULT_STORM_FREQUENCY = 1.0D;
    /** Seconds between natural spawn attempts at frequency one, used before the server config has loaded. */
    public static final int DEFAULT_NATURAL_SPAWN_INTERVAL_SECONDS = 240;
    /** Fraction of the wind speed at which storms travel before the config loads. */
    public static final double DEFAULT_STORM_DRIFT_FACTOR = 0.3D;
    /** Whether the vanilla rain bridge is on before the server config has loaded. */
    public static final boolean DEFAULT_VANILLA_WEATHER_BRIDGE = true;
    /** Whether snow storms lay snow before the server config has loaded. */
    public static final boolean DEFAULT_SNOW_ACCUMULATION = true;
    /** Whether snow storms freeze water before the server config has loaded. */
    public static final boolean DEFAULT_ICE_FORMATION = true;
    /** Deposition attempts per precipitation storm per tick before the server config has loaded. */
    public static final int DEFAULT_DEPOSITION_BLOCKS_PER_TICK = 4;
    /** Length of the built-in seasonal year in days before the server config has loaded. */
    public static final int DEFAULT_BUILT_IN_YEAR_LENGTH_DAYS = 28;
    /** Whether sleeping ends the rain before the server config has loaded. */
    public static final boolean DEFAULT_END_STORMS_ON_SLEEP = true;
    /** Destruction mode assumed before the server config has loaded. */
    public static final DestructionMode DEFAULT_DESTRUCTION_MODE = DestructionMode.FULL;
    /** Blocks one tornado may remove per tick at full strength before the server config has loaded. */
    public static final int DEFAULT_BLOCK_BUDGET_PER_TICK = 16;
    /** Flying blocks one tornado may keep in the air before the server config has loaded. */
    public static final int DEFAULT_MAX_FLYING_BLOCKS_PER_STORM = 48;
    /** Seconds between two severe storms before the server config has loaded. */
    public static final int DEFAULT_SEVERE_WEATHER_COOLDOWN_SECONDS = 900;
    /** Whether lightning starts fires before the server config has loaded. */
    public static final LightningFire DEFAULT_LIGHTNING_FIRE = LightningFire.NORMAL;
    /** Whether vanilla's own lightning is dropped under a mod thunderstorm before the server config has loaded. */
    public static final boolean DEFAULT_SUPPRESS_VANILLA_LIGHTNING = true;
    /** Multiplier on the lightning rate of storms before the server config has loaded. */
    public static final double DEFAULT_LIGHTNING_FREQUENCY = 1.0D;
    /** Whether hail breaks crops and small plants before the server config has loaded. */
    public static final boolean DEFAULT_HAIL_BLOCK_DAMAGE = true;
    /** Whether strong hail breaks glass open to the sky before the server config has loaded. */
    public static final boolean DEFAULT_HAIL_BREAKS_GLASS = false;
    /** Whether hail may kill mobs before the server config has loaded. */
    public static final boolean DEFAULT_HAIL_KILLS_MOBS = false;
    /** Multiplier on how often storms produce tornadoes and small vortices before the server config has loaded. */
    public static final double DEFAULT_TORNADO_FREQUENCY = 1.0D;
    /** Highest EF tier a natural tornado may grow to before the server config has loaded. */
    public static final int DEFAULT_MAX_TORNADO_TIER = 5;
    /** Whether fire whirls set blocks on fire before the server config has loaded. */
    public static final boolean DEFAULT_VORTEX_FIRE = true;
    /** Whether wind and vortices move players before the server config has loaded. */
    public static final boolean DEFAULT_PUSH_PLAYERS = true;
    /** Whether wind and vortices move mobs, vehicles and other entities before the server config has loaded. */
    public static final boolean DEFAULT_PUSH_MOBS = true;
    /** Whether wind and vortices move dropped items and experience orbs before the server config has loaded. */
    public static final boolean DEFAULT_PUSH_ITEMS = true;
    /** Fall damage after being thrown by a vortex before the server config has loaded. */
    public static final FallDamageMode DEFAULT_TORNADO_FALL_DAMAGE = FallDamageMode.REDUCED;
    /** Chance that a ripped block that is not thrown drops as an item before the server config has loaded. */
    public static final double DEFAULT_RIPPED_BLOCK_DROP_CHANCE = 0.1D;
    /** Chance that a thrown block is placed where it lands before the server config has loaded. */
    public static final double DEFAULT_FLYING_BLOCK_LAND_CHANCE = 0.5D;
    /** Blocks from a tornado's edge at which tornado sirens start before the server config has loaded. */
    public static final int DEFAULT_SIREN_RANGE = 256;
    /** Blocks a weather deflector protects before the server config has loaded. */
    public static final int DEFAULT_DEFLECTOR_RADIUS = 48;

    private WeatherManager() {
    }

    /** Whether the weather simulation runs in this level, per {@code CommonConfig.enabledDimensions}. */
    public static boolean isEnabled(ServerLevel level) {
        return CommonConfig.isDimensionEnabled(level.dimension());
    }

    /** The level's weather data, created on first access. */
    public static DimensionWeatherData get(ServerLevel level) {
        return DimensionWeatherData.get(level);
    }

    /** The level's climate field, built on first access, see {@link DimensionWeatherData#climate(ServerLevel)}. */
    public static ClimateField climate(ServerLevel level) {
        return get(level).climate(level);
    }

    /** Ticks between storm delta packets, from the server config or the default before it loads. */
    public static int syncIntervalTicks() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.syncIntervalTicks.get() : DEFAULT_SYNC_INTERVAL_TICKS;
    }

    /** Maximum simultaneous storms in one dimension, from the server config or the default before it loads. */
    public static int maxStormsPerDimension() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.maxStormsPerDimension.get()
            : DEFAULT_MAX_STORMS_PER_DIMENSION;
    }

    /**
     * Multiplier on the natural spawn rate; zero disables natural storms. From the config or the default before it
     * loads.
     */
    public static double stormFrequency() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.stormFrequency.get() : DEFAULT_STORM_FREQUENCY;
    }

    /** Seconds between natural spawn attempts at frequency one, from the config or the default before it loads. */
    public static int naturalSpawnIntervalSeconds() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.naturalSpawnIntervalSeconds.get()
            : DEFAULT_NATURAL_SPAWN_INTERVAL_SECONDS;
    }

    /** Fraction of the wind speed at which storms travel, from the config or the default before it loads. */
    public static float stormDriftFactor() {
        return (float) (ServerConfig.SPEC.isLoaded()
            ? ServerConfig.stormDriftFactor.get()
            : DEFAULT_STORM_DRIFT_FACTOR);
    }

    /** Whether vanilla rain and thunder follow the storms, from the config or the default before it loads. */
    public static boolean vanillaWeatherBridge() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.vanillaWeatherBridge.get()
            : DEFAULT_VANILLA_WEATHER_BRIDGE;
    }

    /** Whether snow storms lay snow layers, from the config or the default before it loads. */
    public static boolean snowAccumulation() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.snowAccumulation.get() : DEFAULT_SNOW_ACCUMULATION;
    }

    /** Whether snow storms freeze still water, from the config or the default before it loads. */
    public static boolean iceFormation() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.iceFormation.get() : DEFAULT_ICE_FORMATION;
    }

    /** Deposition attempts per precipitation storm per tick, from the config or the default before it loads. */
    public static int depositionBlocksPerTick() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.depositionBlocksPerTick.get()
            : DEFAULT_DEPOSITION_BLOCKS_PER_TICK;
    }

    /** Days in the built-in seasonal year, zero disabling it, from the config or the default before it loads. */
    public static int builtInYearLengthDays() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.builtInYearLengthDays.get()
            : DEFAULT_BUILT_IN_YEAR_LENGTH_DAYS;
    }

    /**
     * Whether sleeping through the night ends the rain over the sleepers, from the config or the default before it
     * loads.
     */
    public static boolean endStormsOnSleep() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.endStormsOnSleep.get() : DEFAULT_END_STORMS_ON_SLEEP;
    }

    /** How much storms may change the world, from the config or the default before it loads. */
    public static DestructionMode destructionMode() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.destructionMode.get() : DEFAULT_DESTRUCTION_MODE;
    }

    /**
     * Whether precipitation storms may lay snow or freeze water: the destruction mode is not
     * {@link DestructionMode#VISUAL_ONLY} and at least one of the two deposition toggles is on.
     */
    public static boolean depositionEnabled() {
        return destructionMode() != DestructionMode.VISUAL_ONLY && (snowAccumulation() || iceFormation());
    }

    /**
     * Blocks one tornado may remove per tick at full strength; all storms of a dimension together remove at most
     * {@code StormRuntime.LEVEL_BUDGET_STORMS} times this. From the config or the default before it loads.
     */
    public static int blockBudgetPerTick() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.blockBudgetPerTick.get()
            : DEFAULT_BLOCK_BUDGET_PER_TICK;
    }

    /**
     * Flying blocks one tornado may keep in the air, zero turning flying blocks off; a dimension holds at most
     * {@code StormRuntime.LEVEL_BUDGET_STORMS} times this. From the config or the default before it loads.
     */
    public static int maxFlyingBlocksPerStorm() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.maxFlyingBlocksPerStorm.get()
            : DEFAULT_MAX_FLYING_BLOCKS_PER_STORM;
    }

    /**
     * Seconds that must pass after a severe storm ends before another may form naturally, from the config or the
     * default before it loads.
     */
    public static int severeWeatherCooldownSeconds() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.severeWeatherCooldownSeconds.get()
            : DEFAULT_SEVERE_WEATHER_COOLDOWN_SECONDS;
    }

    /** Whether lightning starts fires, from the config or the default before it loads. */
    public static LightningFire lightningFire() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.lightningFire.get() : DEFAULT_LIGHTNING_FIRE;
    }

    /**
     * Whether vanilla's own random lightning and skeleton traps are dropped while the mod drives the thunder flag,
     * from the config or the default before it loads.
     */
    public static boolean suppressVanillaLightning() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.suppressVanillaLightning.get()
            : DEFAULT_SUPPRESS_VANILLA_LIGHTNING;
    }

    /**
     * Multiplier on how often storms strike lightning; zero disables mod lightning. From the config or the default
     * before it loads.
     */
    public static double lightningFrequency() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.lightningFrequency.get() : DEFAULT_LIGHTNING_FREQUENCY;
    }

    /** Whether hail breaks crops and small plants, from the config or the default before it loads. */
    public static boolean hailBlockDamage() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.hailBlockDamage.get() : DEFAULT_HAIL_BLOCK_DAMAGE;
    }

    /**
     * Whether strong hail also breaks glass that lies open to the sky, from the config or the default before it
     * loads.
     */
    public static boolean hailBreaksGlass() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.hailBreaksGlass.get() : DEFAULT_HAIL_BREAKS_GLASS;
    }

    /**
     * Whether hail may kill mobs; when off it never takes a mob below one heart. From the config or the default
     * before it loads.
     */
    public static boolean hailKillsMobs() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.hailKillsMobs.get() : DEFAULT_HAIL_KILLS_MOBS;
    }

    /**
     * Multiplier on how often storms produce tornadoes and small vortices; zero disables them. From the config or
     * the default before it loads.
     */
    public static double tornadoFrequency() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.tornadoFrequency.get() : DEFAULT_TORNADO_FREQUENCY;
    }

    /**
     * Highest EF tier a natural tornado may grow to, 0 to 5; commands ignore it. From the config or the default
     * before it loads.
     */
    public static int maxTornadoTier() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.maxTornadoTier.get() : DEFAULT_MAX_TORNADO_TIER;
    }

    /** Whether fire whirls set blocks on fire, from the config or the default before it loads. */
    public static boolean vortexFire() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.vortexFire.get() : DEFAULT_VORTEX_FIRE;
    }

    /** Whether wind and vortices move players, from the config or the default before it loads. */
    public static boolean pushPlayers() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.pushPlayers.get() : DEFAULT_PUSH_PLAYERS;
    }

    /**
     * Whether wind and vortices move mobs, vehicles and other entities, from the config or the default before it
     * loads.
     */
    public static boolean pushMobs() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.pushMobs.get() : DEFAULT_PUSH_MOBS;
    }

    /**
     * Whether wind and vortices move dropped items and experience orbs, from the config or the default before it
     * loads.
     */
    public static boolean pushItems() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.pushItems.get() : DEFAULT_PUSH_ITEMS;
    }

    /** Fall damage after being thrown by a vortex, from the config or the default before it loads. */
    public static FallDamageMode tornadoFallDamage() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.tornadoFallDamage.get() : DEFAULT_TORNADO_FALL_DAMAGE;
    }

    /**
     * Chance, 0 to 1, that a ripped block that is not thrown drops as an item, from the config or the default
     * before it loads.
     */
    public static double rippedBlockDropChance() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.rippedBlockDropChance.get()
            : DEFAULT_RIPPED_BLOCK_DROP_CHANCE;
    }

    /**
     * Chance, 0 to 1, that a thrown block is placed where it lands instead of shattering, from the config or the
     * default before it loads.
     */
    public static double flyingBlockLandChance() {
        return ServerConfig.SPEC.isLoaded()
            ? ServerConfig.flyingBlockLandChance.get()
            : DEFAULT_FLYING_BLOCK_LAND_CHANCE;
    }

    /**
     * Blocks from a tornado's edge at which tornado sirens start, from the config or the default before it loads.
     */
    public static int sirenRange() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.sirenRange.get() : DEFAULT_SIREN_RANGE;
    }

    /** Blocks a weather deflector protects, from the config or the default before it loads. */
    public static int deflectorRadius() {
        return ServerConfig.SPEC.isLoaded() ? ServerConfig.deflectorRadius.get() : DEFAULT_DEFLECTOR_RADIUS;
    }

    /**
     * Spawns a storm, pins its peak intensity when one is given, and immediately sends it to every player within
     * its interest radius.
     *
     * @param peakIntensity the peak intensity to pin, or {@code null} to keep the one rolled from the type
     * @return the new storm, or {@code null} when the dimension is full
     */
    @Nullable
    public static StormInstance spawn(ServerLevel level, Holder<WeatherType> type, double x, double z,
                                      @Nullable Float peakIntensity) {
        StormInstance storm = get(level).spawn(type, x, z, level.getRandom());
        if (storm != null) {
            if (peakIntensity != null) {
                storm.setPeakIntensity(peakIntensity);
            }
            storm.startAtPeak();
            sync(level, storm);
        }
        return storm;
    }

    /**
     * Spawns a storm on behalf of the director: it is marked natural, so it counts toward the natural storm cap,
     * and sent at once to every player within its interest radius.
     *
     * @return the new storm, or {@code null} when the dimension is full
     */
    @Nullable
    public static StormInstance spawnNatural(ServerLevel level, Holder<WeatherType> type, double x, double z) {
        StormInstance storm = get(level).spawn(type, x, z, level.getRandom());
        if (storm != null) {
            storm.markNatural();
            sync(level, storm);
        }
        return storm;
    }

    /** Resolves a storm's type in the level's registry; empty when the type is unknown, as after a datapack change. */
    public static Optional<WeatherType> type(ServerLevel level, StormInstance storm) {
        return WeatherRegistries.optionalRegistry(level.registryAccess())
            .map(registry -> registry.get(storm.type()));
    }

    /**
     * Changes a tornado's tier: the storm takes the given weather type with that type's radius and interest
     * radius, the target tier of its vortex state becomes the new type's tier, so natural growth does not undo the
     * change, and the storm is sent to nearby players at once. Lifetime, age, intensity and the rest of the family
     * state are kept. A type without a vortex block leaves the family state alone.
     *
     * @throws IllegalArgumentException when the holder has no registry key
     */
    public static void retype(ServerLevel level, StormInstance storm, Holder<WeatherType> type) {
        ResourceKey<WeatherType> key = type.unwrapKey()
            .orElseThrow(() -> new IllegalArgumentException("Weather type holder is not registered: " + type));
        WeatherType weatherType = type.value();
        storm.retype(key, weatherType.radius(), weatherType.interestRadius());
        weatherType.vortex().ifPresent(params ->
            storm.familyState(VortexState.class, VortexState::load).targetTier = params.tier());
        sync(level, storm);
    }

    /**
     * Sends a changed storm to every player within its interest radius right away, together with its funnel spine
     * when it has one, clears its dirty flag and marks the dimension's data for saving. Every storm change made
     * outside the simulator's own tick goes through here.
     */
    public static void sync(ServerLevel level, StormInstance storm) {
        WeatherNetwork.sendStormToNearby(level, storm);
        storm.clearDirty();
        get(level).setDirty();
    }

    /** Removes a storm and tells every player in the dimension to drop it. Returns whether it existed. */
    public static boolean remove(ServerLevel level, int id) {
        if (!get(level).remove(id)) {
            return false;
        }
        WeatherNetwork.sendToDimension(level, new StormRemovedPayload(id));
        return true;
    }

    /** Removes every storm in the dimension, notifying players of each. Returns how many were removed. */
    public static int clear(ServerLevel level) {
        DimensionWeatherData data = get(level);
        List<Integer> ids = new ArrayList<>(data.storms().size());
        for (StormInstance storm : data.storms()) {
            ids.add(storm.id());
        }
        data.clear();
        for (int id : ids) {
            WeatherNetwork.sendToDimension(level, new StormRemovedPayload(id));
        }
        return ids.size();
    }
}
