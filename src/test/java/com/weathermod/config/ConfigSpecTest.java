package com.weathermod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.weathermod.client.particle.WeatherParticleEmitter;
import com.weathermod.client.render.FogEnhancer;
import com.weathermod.client.render.PrecipitationRenderer;
import com.weathermod.client.render.RainSplashes;
import com.weathermod.client.render.WeatherLevelDriver;
import com.weathermod.client.sound.WeatherAmbience;
import com.weathermod.server.WeatherManager;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

/**
 * Checks the config specs build with the documented defaults and ranges, that the pre-load defaults in
 * {@code WeatherManager} and in the client weather systems agree with them, and that every translation key the
 * configs, the commands and the mod's blocks, entities, death message and subtitles declare has an English string.
 * Needs no Minecraft bootstrap.
 */
class ConfigSpecTest {
    private static final String LANG_PATH = "/assets/weathermod/lang/en_us.json";
    private static final String COMMAND_PREFIX = "command.weathermod.";

    /** The client toggles for the client weather systems, in the order they follow {@code debugOverlay}. */
    private static final List<String> CLIENT_TOGGLE_KEYS = List.of(
        "rainLevelDriver", "precipitationRenderer", "hazeParticles", "windParticles",
        "hailParticles", "debrisParticles", "funnelGroundShadow", "delayedThunder");

    /** The server keys of lightning, hail, vortices and entity forces that are plain on and off switches. */
    private static final List<String> SERVER_HAZARD_TOGGLE_KEYS = List.of(
        "suppressVanillaLightning", "hailBlockDamage", "hailBreaksGlass", "hailKillsMobs", "vortexFire",
        "pushPlayers", "pushMobs", "pushItems");

    /** Every command translation key the server commands and the client commands use. */
    private static final List<String> COMMAND_KEYS = List.of(
        "climate.header", "climate.sample", "climate.season", "climate.season_none",
        "climate.kind.none", "climate.kind.rain", "climate.kind.sleet", "climate.kind.snow",
        "client.overlay.config_forced", "client.overlay.off", "client.overlay.on",
        "client.storms.climate", "client.storms.climate_no_season", "client.storms.climate_unknown",
        "client.storms.entry", "client.storms.header", "client.storms.none",
        "client.storms.kind.none", "client.storms.kind.rain", "client.storms.kind.sleet", "client.storms.kind.snow",
        "client.storms.wind", "client.storms.wind_gust", "client.storms.wind_unknown",
        "dimension_disabled",
        "director.pause.already", "director.paused", "director.resume.already", "director.resumed",
        "director.spawn_failed_no_type", "director.spawned",
        "director.status.frequency", "director.status.frequency_off", "director.status.header",
        "director.status.front_cold", "director.status.front_none", "director.status.front_warm",
        "director.status.last_spawn", "director.status.no_spawn", "director.status.paused",
        "director.status.running", "director.status.severe_cooldown", "director.status.severe_ready",
        "director.status.storms",
        "info.bridge_off", "info.bridge_on", "info.config_not_loaded", "info.deflectors", "info.destruction_mode",
        "info.director_paused", "info.director_running", "info.disabled", "info.enabled", "info.flying_blocks",
        "info.gusting", "info.header", "info.season", "info.season_none", "info.shields", "info.shields.overflow",
        "info.storms", "info.vortices", "info.wind", "info.wind_regime",
        "no", "yes",
        "storm.cleared", "storm.downburst", "storm.intensified", "storm.list.child", "storm.list.empty",
        "storm.list.entry", "storm.list.hail", "storm.list.header", "storm.list.natural", "storm.list.steered",
        "storm.not_found", "storm.released", "storm.removed", "storm.spawn_failed_full", "storm.spawned",
        "storm.steered", "storm.strike", "storm.strike_failed", "storm.tier_not_tornado", "storm.tier_set",
        "wind.regime.breeze", "wind.regime.calm", "wind.regime.gale", "wind.regime.windy", "wind.set");

    /** The keys of the mod's blocks and their tooltips, entities, death message and sound subtitles. */
    private static final List<String> CONTENT_KEYS = List.of(
        "block.weathermod.tornado_siren", "block.weathermod.tornado_siren.tooltip",
        "block.weathermod.weather_deflector", "block.weathermod.weather_deflector.tooltip",
        "death.attack.weathermod.hail", "death.attack.weathermod.hail.player",
        "entity.weathermod.flying_block", "entity.weathermod.funnel",
        "subtitles.weathermod.hail", "subtitles.weathermod.siren", "subtitles.weathermod.thunder",
        "subtitles.weathermod.tornado");

    @Test
    void serverDefaultsMatchSpec() {
        assertEquals(DestructionMode.FULL, ServerConfig.destructionMode.getDefault());
        assertEquals(16, ServerConfig.blockBudgetPerTick.getDefault());
        assertEquals(48, ServerConfig.maxFlyingBlocksPerStorm.getDefault());
        assertEquals(1.0D, ServerConfig.stormFrequency.getDefault());
        assertEquals(240, ServerConfig.naturalSpawnIntervalSeconds.getDefault());
        assertEquals(0.3D, ServerConfig.stormDriftFactor.getDefault());
        assertEquals(8, ServerConfig.maxStormsPerDimension.getDefault());
        assertEquals(10, ServerConfig.syncIntervalTicks.getDefault());
        assertEquals(900, ServerConfig.severeWeatherCooldownSeconds.getDefault());
        assertEquals(true, ServerConfig.vanillaWeatherBridge.getDefault());
        assertEquals(true, ServerConfig.snowAccumulation.getDefault());
        assertEquals(true, ServerConfig.iceFormation.getDefault());
        assertEquals(4, ServerConfig.depositionBlocksPerTick.getDefault());
        assertEquals(28, ServerConfig.builtInYearLengthDays.getDefault());
        assertEquals(true, ServerConfig.endStormsOnSleep.getDefault());
    }

    @Test
    void lightningHailAndVortexDefaultsMatchSpec() {
        assertEquals(LightningFire.NORMAL, ServerConfig.lightningFire.getDefault());
        assertEquals(true, ServerConfig.suppressVanillaLightning.getDefault());
        assertEquals(1.0D, ServerConfig.lightningFrequency.getDefault());
        assertEquals(true, ServerConfig.hailBlockDamage.getDefault());
        assertEquals(false, ServerConfig.hailBreaksGlass.getDefault());
        assertEquals(false, ServerConfig.hailKillsMobs.getDefault());
        assertEquals(1.0D, ServerConfig.tornadoFrequency.getDefault());
        assertEquals(5, ServerConfig.maxTornadoTier.getDefault());
        assertEquals(true, ServerConfig.vortexFire.getDefault());
        assertEquals(true, ServerConfig.pushPlayers.getDefault());
        assertEquals(true, ServerConfig.pushMobs.getDefault());
        assertEquals(true, ServerConfig.pushItems.getDefault());
        assertEquals(FallDamageMode.REDUCED, ServerConfig.tornadoFallDamage.getDefault());
        assertEquals(0.1D, ServerConfig.rippedBlockDropChance.getDefault());
        assertEquals(0.5D, ServerConfig.flyingBlockLandChance.getDefault());
        assertEquals(256, ServerConfig.sirenRange.getDefault());
        assertEquals(48, ServerConfig.deflectorRadius.getDefault());
    }

    @Test
    void preLoadDefaultsAgreeWithSpec() {
        assertEquals(ServerConfig.destructionMode.getDefault(), WeatherManager.DEFAULT_DESTRUCTION_MODE);
        assertEquals(ServerConfig.stormFrequency.getDefault(), WeatherManager.DEFAULT_STORM_FREQUENCY);
        assertEquals(ServerConfig.naturalSpawnIntervalSeconds.getDefault(),
            WeatherManager.DEFAULT_NATURAL_SPAWN_INTERVAL_SECONDS);
        assertEquals(ServerConfig.stormDriftFactor.getDefault(), WeatherManager.DEFAULT_STORM_DRIFT_FACTOR);
        assertEquals(ServerConfig.maxStormsPerDimension.getDefault(), WeatherManager.DEFAULT_MAX_STORMS_PER_DIMENSION);
        assertEquals(ServerConfig.syncIntervalTicks.getDefault(), WeatherManager.DEFAULT_SYNC_INTERVAL_TICKS);
        assertEquals(ServerConfig.vanillaWeatherBridge.getDefault(), WeatherManager.DEFAULT_VANILLA_WEATHER_BRIDGE);
        assertEquals(ServerConfig.snowAccumulation.getDefault(), WeatherManager.DEFAULT_SNOW_ACCUMULATION);
        assertEquals(ServerConfig.iceFormation.getDefault(), WeatherManager.DEFAULT_ICE_FORMATION);
        assertEquals(ServerConfig.depositionBlocksPerTick.getDefault(),
            WeatherManager.DEFAULT_DEPOSITION_BLOCKS_PER_TICK);
        assertEquals(ServerConfig.builtInYearLengthDays.getDefault(), WeatherManager.DEFAULT_BUILT_IN_YEAR_LENGTH_DAYS);
        assertEquals(ServerConfig.endStormsOnSleep.getDefault(), WeatherManager.DEFAULT_END_STORMS_ON_SLEEP);
        assertEquals(ServerConfig.blockBudgetPerTick.getDefault(), WeatherManager.DEFAULT_BLOCK_BUDGET_PER_TICK);
        assertEquals(ServerConfig.maxFlyingBlocksPerStorm.getDefault(),
            WeatherManager.DEFAULT_MAX_FLYING_BLOCKS_PER_STORM);
        assertEquals(ServerConfig.severeWeatherCooldownSeconds.getDefault(),
            WeatherManager.DEFAULT_SEVERE_WEATHER_COOLDOWN_SECONDS);
    }

    @Test
    void lightningHailAndVortexPreLoadDefaultsAgreeWithSpec() {
        assertEquals(ServerConfig.lightningFire.getDefault(), WeatherManager.DEFAULT_LIGHTNING_FIRE);
        assertEquals(ServerConfig.suppressVanillaLightning.getDefault(),
            WeatherManager.DEFAULT_SUPPRESS_VANILLA_LIGHTNING);
        assertEquals(ServerConfig.lightningFrequency.getDefault(), WeatherManager.DEFAULT_LIGHTNING_FREQUENCY);
        assertEquals(ServerConfig.hailBlockDamage.getDefault(), WeatherManager.DEFAULT_HAIL_BLOCK_DAMAGE);
        assertEquals(ServerConfig.hailBreaksGlass.getDefault(), WeatherManager.DEFAULT_HAIL_BREAKS_GLASS);
        assertEquals(ServerConfig.hailKillsMobs.getDefault(), WeatherManager.DEFAULT_HAIL_KILLS_MOBS);
        assertEquals(ServerConfig.tornadoFrequency.getDefault(), WeatherManager.DEFAULT_TORNADO_FREQUENCY);
        assertEquals(ServerConfig.maxTornadoTier.getDefault(), WeatherManager.DEFAULT_MAX_TORNADO_TIER);
        assertEquals(ServerConfig.vortexFire.getDefault(), WeatherManager.DEFAULT_VORTEX_FIRE);
        assertEquals(ServerConfig.pushPlayers.getDefault(), WeatherManager.DEFAULT_PUSH_PLAYERS);
        assertEquals(ServerConfig.pushMobs.getDefault(), WeatherManager.DEFAULT_PUSH_MOBS);
        assertEquals(ServerConfig.pushItems.getDefault(), WeatherManager.DEFAULT_PUSH_ITEMS);
        assertEquals(ServerConfig.tornadoFallDamage.getDefault(), WeatherManager.DEFAULT_TORNADO_FALL_DAMAGE);
        assertEquals(ServerConfig.rippedBlockDropChance.getDefault(),
            WeatherManager.DEFAULT_RIPPED_BLOCK_DROP_CHANCE);
        assertEquals(ServerConfig.flyingBlockLandChance.getDefault(),
            WeatherManager.DEFAULT_FLYING_BLOCK_LAND_CHANCE);
        assertEquals(ServerConfig.sirenRange.getDefault(), WeatherManager.DEFAULT_SIREN_RANGE);
        assertEquals(ServerConfig.deflectorRadius.getDefault(), WeatherManager.DEFAULT_DEFLECTOR_RADIUS);
    }

    @Test
    void accessorsReturnThePreLoadDefaultsWhileNoServerConfigIsLoaded() {
        assertFalse(ServerConfig.SPEC.isLoaded(), "a unit test never loads the server config");
        assertEquals(WeatherManager.DEFAULT_BLOCK_BUDGET_PER_TICK, WeatherManager.blockBudgetPerTick());
        assertEquals(WeatherManager.DEFAULT_MAX_FLYING_BLOCKS_PER_STORM, WeatherManager.maxFlyingBlocksPerStorm());
        assertEquals(WeatherManager.DEFAULT_SEVERE_WEATHER_COOLDOWN_SECONDS,
            WeatherManager.severeWeatherCooldownSeconds());
        assertEquals(WeatherManager.DEFAULT_LIGHTNING_FIRE, WeatherManager.lightningFire());
        assertEquals(WeatherManager.DEFAULT_SUPPRESS_VANILLA_LIGHTNING, WeatherManager.suppressVanillaLightning());
        assertEquals(WeatherManager.DEFAULT_LIGHTNING_FREQUENCY, WeatherManager.lightningFrequency());
        assertEquals(WeatherManager.DEFAULT_HAIL_BLOCK_DAMAGE, WeatherManager.hailBlockDamage());
        assertEquals(WeatherManager.DEFAULT_HAIL_BREAKS_GLASS, WeatherManager.hailBreaksGlass());
        assertEquals(WeatherManager.DEFAULT_HAIL_KILLS_MOBS, WeatherManager.hailKillsMobs());
        assertEquals(WeatherManager.DEFAULT_TORNADO_FREQUENCY, WeatherManager.tornadoFrequency());
        assertEquals(WeatherManager.DEFAULT_MAX_TORNADO_TIER, WeatherManager.maxTornadoTier());
        assertEquals(WeatherManager.DEFAULT_VORTEX_FIRE, WeatherManager.vortexFire());
        assertEquals(WeatherManager.DEFAULT_PUSH_PLAYERS, WeatherManager.pushPlayers());
        assertEquals(WeatherManager.DEFAULT_PUSH_MOBS, WeatherManager.pushMobs());
        assertEquals(WeatherManager.DEFAULT_PUSH_ITEMS, WeatherManager.pushItems());
        assertEquals(WeatherManager.DEFAULT_TORNADO_FALL_DAMAGE, WeatherManager.tornadoFallDamage());
        assertEquals(WeatherManager.DEFAULT_RIPPED_BLOCK_DROP_CHANCE, WeatherManager.rippedBlockDropChance());
        assertEquals(WeatherManager.DEFAULT_FLYING_BLOCK_LAND_CHANCE, WeatherManager.flyingBlockLandChance());
        assertEquals(WeatherManager.DEFAULT_SIREN_RANGE, WeatherManager.sirenRange());
        assertEquals(WeatherManager.DEFAULT_DEFLECTOR_RADIUS, WeatherManager.deflectorRadius());
    }

    @Test
    void clientDefaultsMatchSpec() {
        assertEquals(1.0D, ClientConfig.particleDensity.getDefault());
        assertEquals(1.0D, ClientConfig.hazeStrength.getDefault());
        assertEquals(1.0D, ClientConfig.soundVolume.getDefault());
        assertEquals(false, ClientConfig.debugOverlay.getDefault());
        assertEquals(true, ClientConfig.rainLevelDriver.getDefault());
        assertEquals(true, ClientConfig.precipitationRenderer.getDefault());
        assertEquals(true, ClientConfig.hazeParticles.getDefault());
        assertEquals(true, ClientConfig.windParticles.getDefault());
        assertEquals(true, ClientConfig.hailParticles.getDefault());
        assertEquals(true, ClientConfig.debrisParticles.getDefault());
        assertEquals(true, ClientConfig.funnelGroundShadow.getDefault());
        assertEquals(true, ClientConfig.delayedThunder.getDefault());
    }

    @Test
    void clientTogglesAreBooleansWithTranslatedNamesAndTooltips() throws Exception {
        JsonObject lang = loadLang();
        List<String> missing = new ArrayList<>();
        for (String name : CLIENT_TOGGLE_KEYS) {
            ModConfigSpec.ValueSpec spec = ClientConfig.SPEC.getSpec().get(name);
            assertNotNull(spec, "no ValueSpec for " + name);
            assertEquals(true, spec.getDefault(), name + " defaults to on");
            assertTrue(spec.test(true), name + " accepts true");
            assertTrue(spec.test(false), name + " accepts false");
            assertFalse(spec.test("yes"), name + " rejects a string");
            String key = ConfigSetup.TRANSLATION_PREFIX + name;
            assertEquals(key, spec.getTranslationKey());
            checkKey(lang, key, missing);
            checkKey(lang, key + ".tooltip", missing);
        }
        assertTrue(missing.isEmpty(), "Missing client toggle translation keys in " + LANG_PATH + ": " + missing);
    }

    @Test
    void clientPreLoadDefaultsAgreeWithSpec() {
        assertEquals(ClientConfig.rainLevelDriver.getDefault(), WeatherLevelDriver.DEFAULT_RAIN_LEVEL_DRIVER);
        assertEquals(ClientConfig.precipitationRenderer.getDefault(),
            PrecipitationRenderer.DEFAULT_PRECIPITATION_RENDERER);
        assertEquals(ClientConfig.hazeParticles.getDefault(), WeatherParticleEmitter.DEFAULT_HAZE_PARTICLES);
        assertEquals(ClientConfig.windParticles.getDefault(), WeatherParticleEmitter.DEFAULT_WIND_PARTICLES);
        assertEquals(ClientConfig.particleDensity.getDefault(), WeatherParticleEmitter.DEFAULT_PARTICLE_DENSITY);
        assertEquals(ClientConfig.particleDensity.getDefault(), RainSplashes.DEFAULT_PARTICLE_DENSITY);
        assertEquals(ClientConfig.hazeStrength.getDefault(), WeatherParticleEmitter.DEFAULT_HAZE_STRENGTH);
        assertEquals(ClientConfig.hazeStrength.getDefault(), FogEnhancer.DEFAULT_HAZE_STRENGTH);
        assertEquals(ClientConfig.soundVolume.getDefault(), WeatherAmbience.DEFAULT_SOUND_VOLUME);
    }

    @Test
    void commonDefaultsMatchSpec() {
        assertEquals(List.of("minecraft:overworld", "minecraft:the_nether"),
            CommonConfig.enabledDimensions.getDefault());
        assertEquals(false, CommonConfig.fantasyPackEnabled.getDefault());
    }

    @Test
    void enabledDimensionsValidatorAcceptsResourceLocationsOnly() {
        ModConfigSpec.ValueSpec spec = CommonConfig.SPEC.getSpec().get("enabledDimensions");
        assertNotNull(spec);
        assertTrue(spec.test(List.of("minecraft:overworld", "mymod:sky_islands")));
        assertTrue(spec.test(List.of()), "an empty list disables weather everywhere and must be allowed");
        assertFalse(spec.test(List.of("Not A Location!")));
        assertFalse(spec.test(List.of("minecraft:overworld", 42)));
        assertFalse(spec.test("minecraft:overworld"));
    }

    @Test
    void rangesAreEnforced() {
        ModConfigSpec.ValueSpec budget = ServerConfig.SPEC.getSpec().get("blockBudgetPerTick");
        assertTrue(budget.test(1));
        assertTrue(budget.test(256));
        assertFalse(budget.test(0));
        assertFalse(budget.test(257));

        ModConfigSpec.ValueSpec volume = ClientConfig.SPEC.getSpec().get("soundVolume");
        assertTrue(volume.test(0.5D));
        assertFalse(volume.test(1.5D));
    }

    @Test
    void simulationAndImpactRangesAreEnforced() {
        ModConfigSpec.ValueSpec interval = ServerConfig.SPEC.getSpec().get("naturalSpawnIntervalSeconds");
        assertTrue(interval.test(10));
        assertTrue(interval.test(86400));
        assertFalse(interval.test(9));
        assertFalse(interval.test(86401));

        ModConfigSpec.ValueSpec deposition = ServerConfig.SPEC.getSpec().get("depositionBlocksPerTick");
        assertTrue(deposition.test(0), "zero attempts switches deposition off and must be allowed");
        assertTrue(deposition.test(64));
        assertFalse(deposition.test(-1));
        assertFalse(deposition.test(65));

        ModConfigSpec.ValueSpec yearLength = ServerConfig.SPEC.getSpec().get("builtInYearLengthDays");
        assertTrue(yearLength.test(0), "zero days disables the built-in seasons and must be allowed");
        assertTrue(yearLength.test(3650));
        assertFalse(yearLength.test(-1));
        assertFalse(yearLength.test(3651));

        ModConfigSpec.ValueSpec bridge = ServerConfig.SPEC.getSpec().get("vanillaWeatherBridge");
        assertTrue(bridge.test(true));
        assertTrue(bridge.test(false));
        assertFalse(bridge.test("yes"));
    }

    @Test
    void lightningHailAndVortexRangesAreEnforced() {
        for (String name : List.of("lightningFrequency", "tornadoFrequency")) {
            ModConfigSpec.ValueSpec frequency = ServerConfig.SPEC.getSpec().get(name);
            assertNotNull(frequency, "no ValueSpec for " + name);
            assertTrue(frequency.test(0.0D), "a " + name + " of zero switches the hazard off and must be allowed");
            assertTrue(frequency.test(10.0D), name);
            assertFalse(frequency.test(-0.1D), name);
            assertFalse(frequency.test(10.1D), name);
        }

        ModConfigSpec.ValueSpec tier = ServerConfig.SPEC.getSpec().get("maxTornadoTier");
        assertTrue(tier.test(0), "EF0 is the lowest tier a natural tornado may be capped at");
        assertTrue(tier.test(5));
        assertFalse(tier.test(-1));
        assertFalse(tier.test(6));

        for (String name : List.of("rippedBlockDropChance", "flyingBlockLandChance")) {
            ModConfigSpec.ValueSpec chance = ServerConfig.SPEC.getSpec().get(name);
            assertNotNull(chance, "no ValueSpec for " + name);
            assertTrue(chance.test(0.0D), name);
            assertTrue(chance.test(1.0D), name);
            assertFalse(chance.test(-0.01D), name);
            assertFalse(chance.test(1.01D), name);
        }

        ModConfigSpec.ValueSpec sirenRange = ServerConfig.SPEC.getSpec().get("sirenRange");
        assertTrue(sirenRange.test(16));
        assertTrue(sirenRange.test(1024));
        assertFalse(sirenRange.test(15));
        assertFalse(sirenRange.test(1025));

        ModConfigSpec.ValueSpec deflectorRadius = ServerConfig.SPEC.getSpec().get("deflectorRadius");
        assertTrue(deflectorRadius.test(8));
        assertTrue(deflectorRadius.test(96));
        assertFalse(deflectorRadius.test(7));
        assertFalse(deflectorRadius.test(97));
    }

    @Test
    void lightningHailAndVortexTogglesAreBooleans() {
        for (String name : SERVER_HAZARD_TOGGLE_KEYS) {
            ModConfigSpec.ValueSpec spec = ServerConfig.SPEC.getSpec().get(name);
            assertNotNull(spec, "no ValueSpec for " + name);
            assertTrue(spec.test(true), name + " accepts true");
            assertTrue(spec.test(false), name + " accepts false");
            assertFalse(spec.test("yes"), name + " rejects a string");
            assertEquals(ConfigSetup.TRANSLATION_PREFIX + name, spec.getTranslationKey());
        }
    }

    @Test
    void destructionModeHelpers() {
        assertTrue(DestructionMode.FULL.ripsBlocks());
        assertTrue(DestructionMode.FULL.affectsEntities());
        assertFalse(DestructionMode.ENTITIES_ONLY.ripsBlocks());
        assertTrue(DestructionMode.ENTITIES_ONLY.affectsEntities());
        assertFalse(DestructionMode.VISUAL_ONLY.ripsBlocks());
        assertFalse(DestructionMode.VISUAL_ONLY.affectsEntities());
    }

    @Test
    void lightningFireHelpers() throws Exception {
        assertEquals(List.of(LightningFire.OFF, LightningFire.NORMAL, LightningFire.ALWAYS),
            List.of(LightningFire.values()));
        assertEquals("off", LightningFire.OFF.getSerializedName());
        assertEquals("normal", LightningFire.NORMAL.getSerializedName());
        assertEquals("always", LightningFire.ALWAYS.getSerializedName());
        assertEquals("weathermod.configuration.lightningFire.off", LightningFire.OFF.getTranslationKey());
        assertEquals("weathermod.configuration.lightningFire.normal", LightningFire.NORMAL.getTranslationKey());
        assertEquals("weathermod.configuration.lightningFire.always", LightningFire.ALWAYS.getTranslationKey());

        JsonObject lang = loadLang();
        assertEquals("Off", lang.get(LightningFire.OFF.getTranslationKey()).getAsString());
        assertEquals("Normal", lang.get(LightningFire.NORMAL.getTranslationKey()).getAsString());
        assertEquals("Always", lang.get(LightningFire.ALWAYS.getTranslationKey()).getAsString());

        ModConfigSpec.ValueSpec spec = ServerConfig.SPEC.getSpec().get("lightningFire");
        assertNotNull(spec);
        assertEquals(ConfigSetup.TRANSLATION_PREFIX + "lightningFire", spec.getTranslationKey());
        for (LightningFire fire : LightningFire.values()) {
            assertTrue(spec.test(fire), fire + " is an allowed value");
            assertTrue(spec.test(fire.name()), fire + " is allowed by name, as the config file spells it");
        }
        assertFalse(spec.test(FallDamageMode.NONE), "a value of another enum is rejected");
        assertFalse(spec.test("SOMETIMES"));
    }

    @Test
    void fallDamageModeHelpers() throws Exception {
        assertEquals(List.of(FallDamageMode.FULL, FallDamageMode.REDUCED, FallDamageMode.NONE),
            List.of(FallDamageMode.values()));
        assertEquals("full", FallDamageMode.FULL.getSerializedName());
        assertEquals("reduced", FallDamageMode.REDUCED.getSerializedName());
        assertEquals("none", FallDamageMode.NONE.getSerializedName());
        assertEquals("weathermod.configuration.tornadoFallDamage.full", FallDamageMode.FULL.getTranslationKey());
        assertEquals("weathermod.configuration.tornadoFallDamage.reduced",
            FallDamageMode.REDUCED.getTranslationKey());
        assertEquals("weathermod.configuration.tornadoFallDamage.none", FallDamageMode.NONE.getTranslationKey());

        JsonObject lang = loadLang();
        assertEquals("Full", lang.get(FallDamageMode.FULL.getTranslationKey()).getAsString());
        assertEquals("Reduced", lang.get(FallDamageMode.REDUCED.getTranslationKey()).getAsString());
        assertEquals("None", lang.get(FallDamageMode.NONE.getTranslationKey()).getAsString());

        ModConfigSpec.ValueSpec spec = ServerConfig.SPEC.getSpec().get("tornadoFallDamage");
        assertNotNull(spec);
        assertEquals(ConfigSetup.TRANSLATION_PREFIX + "tornadoFallDamage", spec.getTranslationKey());
        for (FallDamageMode mode : FallDamageMode.values()) {
            assertTrue(spec.test(mode), mode + " is an allowed value");
            assertTrue(spec.test(mode.name()), mode + " is allowed by name, as the config file spells it");
        }
        assertFalse(spec.test(LightningFire.OFF), "a value of another enum is rejected");
        assertFalse(spec.test("HALF"));
    }

    @Test
    void everyConfigTranslationKeyHasAnEnglishString() throws Exception {
        JsonObject lang = loadLang();
        List<String> missing = new ArrayList<>();

        checkKey(lang, "weathermod.configuration.title", missing);
        for (DestructionMode mode : DestructionMode.values()) {
            checkKey(lang, mode.getTranslationKey(), missing);
        }
        for (LightningFire fire : LightningFire.values()) {
            checkKey(lang, fire.getTranslationKey(), missing);
        }
        for (FallDamageMode mode : FallDamageMode.values()) {
            checkKey(lang, mode.getTranslationKey(), missing);
        }
        collectMissing(lang, ServerConfig.SPEC, missing);
        collectMissing(lang, ClientConfig.SPEC, missing);
        collectMissing(lang, CommonConfig.SPEC, missing);

        assertTrue(missing.isEmpty(), "Missing translation keys in " + LANG_PATH + ": " + missing);
    }

    @Test
    void everyCommandTranslationKeyHasAnEnglishString() throws Exception {
        JsonObject lang = loadLang();
        List<String> missing = new ArrayList<>();
        for (String key : COMMAND_KEYS) {
            checkKey(lang, COMMAND_PREFIX + key, missing);
        }
        assertTrue(missing.isEmpty(), "Missing command translation keys in " + LANG_PATH + ": " + missing);
    }

    @Test
    void everyBlockEntityDeathAndSubtitleKeyHasAnEnglishString() throws Exception {
        JsonObject lang = loadLang();
        List<String> missing = new ArrayList<>();
        for (String key : CONTENT_KEYS) {
            checkKey(lang, key, missing);
        }
        assertTrue(missing.isEmpty(), "Missing translation keys in " + LANG_PATH + ": " + missing);
    }

    @Test
    void langKeysAreSorted() throws Exception {
        JsonObject lang = loadLang();
        List<String> keys = new ArrayList<>(lang.keySet());
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(null);
        assertEquals(sorted, keys, "keys in " + LANG_PATH + " must be sorted");
    }

    private static void collectMissing(JsonObject lang, ModConfigSpec spec, List<String> missing) {
        for (UnmodifiableConfig.Entry entry : spec.getValues().entrySet()) {
            String path = entry.getKey();
            ModConfigSpec.ValueSpec valueSpec = spec.getSpec().get(path);
            assertNotNull(valueSpec, "no ValueSpec for " + path);
            String key = valueSpec.getTranslationKey();
            assertNotNull(key, "config value " + path + " has no translation key");
            assertTrue(key.startsWith("weathermod.configuration."), "unexpected translation key " + key);
            checkKey(lang, key, missing);
            checkKey(lang, key + ".tooltip", missing);
        }
    }

    private static void checkKey(JsonObject lang, String key, List<String> missing) {
        if (!lang.has(key) || lang.get(key).getAsString().isBlank()) {
            missing.add(key);
        }
    }

    private static JsonObject loadLang() throws Exception {
        try (InputStream stream = ConfigSpecTest.class.getResourceAsStream(LANG_PATH)) {
            assertNotNull(stream, "lang file not on the test classpath: " + LANG_PATH);
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}
