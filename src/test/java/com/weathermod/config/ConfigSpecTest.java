package com.weathermod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

/**
 * Checks the config specs build with the documented defaults and that every translation key they
 * declare has an English string. Needs no Minecraft bootstrap.
 */
class ConfigSpecTest {
    private static final String LANG_PATH = "/assets/weathermod/lang/en_us.json";

    @Test
    void serverDefaultsMatchSpec() {
        assertEquals(DestructionMode.FULL, ServerConfig.destructionMode.getDefault());
        assertEquals(16, ServerConfig.blockBudgetPerTick.getDefault());
        assertEquals(48, ServerConfig.maxFlyingBlocksPerStorm.getDefault());
        assertEquals(1.0D, ServerConfig.stormFrequency.getDefault());
        assertEquals(8, ServerConfig.maxStormsPerDimension.getDefault());
        assertEquals(10, ServerConfig.syncIntervalTicks.getDefault());
        assertEquals(900, ServerConfig.severeWeatherCooldownSeconds.getDefault());
    }

    @Test
    void clientDefaultsMatchSpec() {
        assertEquals(1.0D, ClientConfig.particleDensity.getDefault());
        assertEquals(1.0D, ClientConfig.hazeStrength.getDefault());
        assertEquals(1.0D, ClientConfig.soundVolume.getDefault());
        assertEquals(false, ClientConfig.debugOverlay.getDefault());
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
