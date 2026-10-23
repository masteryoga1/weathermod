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
 * Checks the config specs build with the documented defaults, that the pre-load defaults in {@code WeatherManager}
 * and in the client weather systems agree with them, and that every translation key they declare has an English
 * string. Needs no Minecraft bootstrap.
 */
class ConfigSpecTest {
    private static final String LANG_PATH = "/assets/weathermod/lang/en_us.json";
    private static final String COMMAND_PREFIX = "command.weathermod.";

    /** The client toggles for the client weather systems, in the order they follow {@code debugOverlay}. */
    private static final List<String> CLIENT_TOGGLE_KEYS = List.of(
        "rainLevelDriver", "precipitationRenderer", "hazeParticles", "windParticles");

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
        "director.status.last_spawn", "director.status.no_spawn", "director.status.paused",
        "director.status.running", "director.status.storms",
        "info.bridge_off", "info.bridge_on", "info.config_not_loaded", "info.destruction_mode",
        "info.director_paused", "info.director_running", "info.disabled", "info.enabled", "info.gusting",
        "info.header", "info.season", "info.season_none", "info.storms", "info.wind", "info.wind_regime",
        "no", "yes",
        "storm.cleared", "storm.intensified", "storm.list.empty", "storm.list.entry", "storm.list.header",
        "storm.list.natural", "storm.list.steered", "storm.not_found", "storm.released", "storm.removed",
        "storm.spawn_failed_full", "storm.spawned", "storm.steered",
        "wind.regime.breeze", "wind.regime.calm", "wind.regime.gale", "wind.regime.windy", "wind.set");

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
    void preLoadDefaultsAgreeWithSpec() {
        assertEquals(ServerConfig.destructionMode.getDefault(), WeatherManager.DEFAULT_DESTRUCTION_MODE);
        assertEquals(ServerConfig.stormFrequency.getDefault(), WeatherManager.DEFAULT_STORM_FREQUENCY);
        assertEquals(ServerConfig.naturalSpawnIntervalSeconds.getDefault(), WeatherManager.DEFAULT_NATURAL_SPAWN_INTERVAL_SECONDS);
        assertEquals(ServerConfig.stormDriftFactor.getDefault(), WeatherManager.DEFAULT_STORM_DRIFT_FACTOR);
        assertEquals(ServerConfig.maxStormsPerDimension.getDefault(), WeatherManager.DEFAULT_MAX_STORMS_PER_DIMENSION);
        assertEquals(ServerConfig.syncIntervalTicks.getDefault(), WeatherManager.DEFAULT_SYNC_INTERVAL_TICKS);
        assertEquals(ServerConfig.vanillaWeatherBridge.getDefault(), WeatherManager.DEFAULT_VANILLA_WEATHER_BRIDGE);
        assertEquals(ServerConfig.snowAccumulation.getDefault(), WeatherManager.DEFAULT_SNOW_ACCUMULATION);
        assertEquals(ServerConfig.iceFormation.getDefault(), WeatherManager.DEFAULT_ICE_FORMATION);
        assertEquals(ServerConfig.depositionBlocksPerTick.getDefault(), WeatherManager.DEFAULT_DEPOSITION_BLOCKS_PER_TICK);
        assertEquals(ServerConfig.builtInYearLengthDays.getDefault(), WeatherManager.DEFAULT_BUILT_IN_YEAR_LENGTH_DAYS);
        assertEquals(ServerConfig.endStormsOnSleep.getDefault(), WeatherManager.DEFAULT_END_STORMS_ON_SLEEP);
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
        assertEquals(List.of("minecraft:overworld", "minecraft:the_nether"), CommonConfig.enabledDimensions.getDefault());
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
    void destructionModeHelpers() {
        assertTrue(DestructionMode.FULL.ripsBlocks());
        assertTrue(DestructionMode.FULL.affectsEntities());
        assertFalse(DestructionMode.ENTITIES_ONLY.ripsBlocks());
        assertTrue(DestructionMode.ENTITIES_ONLY.affectsEntities());
        assertFalse(DestructionMode.VISUAL_ONLY.ripsBlocks());
        assertFalse(DestructionMode.VISUAL_ONLY.affectsEntities());
    }

    @Test
    void everyConfigTranslationKeyHasAnEnglishString() throws Exception {
        JsonObject lang = loadLang();
        List<String> missing = new ArrayList<>();

        checkKey(lang, "weathermod.configuration.title", missing);
        for (DestructionMode mode : DestructionMode.values()) {
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
