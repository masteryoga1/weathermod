package com.weathermod.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Checks that the generated client assets agree with each other and with the code that loads them: every particle
 * definition lists sprites that exist, every sound event in sounds.json has its OGG file, and the textures have the
 * sizes the renderer and the particles expect. Reads the files from the classpath and decodes the PNG headers
 * itself, so it needs no Minecraft bootstrap.
 */
class ResourceConsistencyTest {
    private static final String ASSETS = "/assets/weathermod/";
    private static final String NAMESPACE = "weathermod:";
    private static final List<String> SOUND_KEYS = List.of(
        "ambient.rain_light", "ambient.rain_heavy", "ambient.rain_roof", "ambient.snow", "ambient.wind");
    private static final List<String> HAZE_SPRITES = List.of("haze_0", "haze_1", "haze_2", "haze_3");
    private static final List<String> DUST_SPRITES = List.of("wind_dust_0", "wind_dust_1");
    private static final List<String> LEAF_SPRITES = List.of("wind_leaf_0", "wind_leaf_1");
    private static final Map<String, List<String>> PARTICLE_SPRITES = Map.of(
        "haze", HAZE_SPRITES, "wind_dust", DUST_SPRITES, "wind_leaf", LEAF_SPRITES);
    private static final int ENVIRONMENT_SIZE = 64;
    private static final int HAZE_SIZE = 32;
    private static final int SMALL_SIZE = 8;
    private static final int RGBA_COLOR_TYPE = 6;
    private static final int PNG_HEADER_LENGTH = 33;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] OGG_SIGNATURE = {'O', 'g', 'g', 'S'};

    /** The fields of a PNG IHDR chunk this test reads. */
    private record PngHeader(int width, int height, int bitDepth, int colorType) {
    }

    @Test
    void particleDefinitionsListExistingSprites() throws IOException {
        for (Map.Entry<String, List<String>> entry : PARTICLE_SPRITES.entrySet()) {
            String path = ASSETS + "particles/" + entry.getKey() + ".json";
            JsonArray textures = readJson(path).getAsJsonArray("textures");
            assertNotNull(textures, path + " has no textures array");
            List<String> names = new ArrayList<>();
            for (JsonElement element : textures) {
                String id = element.getAsString();
                assertTrue(id.startsWith(NAMESPACE), path + " lists a sprite outside the mod namespace: " + id);
                String name = id.substring(NAMESPACE.length());
                assertTrue(resourceExists(ASSETS + "textures/particle/" + name + ".png"),
                    path + " lists a sprite with no texture: " + id);
                names.add(name);
            }
            assertEquals(entry.getValue(), names, path + " must list the generated sprites in order");
        }
    }

    @Test
    void hazeSpritesAre32Square() throws IOException {
        for (String name : HAZE_SPRITES) {
            assertPng(ASSETS + "textures/particle/" + name + ".png", HAZE_SIZE);
        }
    }

    @Test
    void windSpritesAre8Square() throws IOException {
        for (String name : DUST_SPRITES) {
            assertPng(ASSETS + "textures/particle/" + name + ".png", SMALL_SIZE);
        }
        for (String name : LEAF_SPRITES) {
            assertPng(ASSETS + "textures/particle/" + name + ".png", SMALL_SIZE);
        }
    }

    @Test
    void environmentTexturesExistAndAre64Square() throws IOException {
        assertPng(ASSETS + "textures/environment/rain.png", ENVIRONMENT_SIZE);
        assertPng(ASSETS + "textures/environment/snow.png", ENVIRONMENT_SIZE);
    }

    @Test
    void soundsJsonDeclaresTheFiveAmbienceLoops() throws IOException {
        JsonObject sounds = readJson(ASSETS + "sounds.json");
        for (String key : SOUND_KEYS) {
            assertTrue(sounds.has(key), "sounds.json is missing " + key);
        }
        assertEquals(SOUND_KEYS.size(), sounds.size(), "sounds.json declares exactly the five ambience loops");
    }

    @Test
    void everySoundEntryHasItsFile() throws IOException {
        JsonObject sounds = readJson(ASSETS + "sounds.json");
        for (Map.Entry<String, JsonElement> entry : sounds.entrySet()) {
            String key = entry.getKey();
            JsonObject event = entry.getValue().getAsJsonObject();
            assertEquals("weather", event.get("category").getAsString(), key + " must use the weather category");
            JsonArray files = event.getAsJsonArray("sounds");
            assertNotNull(files, key + " has no sounds array");
            assertFalse(files.isEmpty(), key + " lists no sound files");
            for (JsonElement element : files) {
                JsonObject sound = element.getAsJsonObject();
                String name = sound.get("name").getAsString();
                assertTrue(name.startsWith(NAMESPACE), key + " names a sound outside the mod namespace: " + name);
                assertFalse(sound.get("stream").getAsBoolean(), key + " must be loaded, not streamed");
                String path = ASSETS + "sounds/" + name.substring(NAMESPACE.length()) + ".ogg";
                byte[] head = readHead(path, OGG_SIGNATURE.length);
                assertArrayEquals(OGG_SIGNATURE, head, path + " is not an Ogg file");
            }
        }
    }

    private static void assertPng(String path, int size) throws IOException {
        PngHeader header = readPngHeader(path);
        assertEquals(size, header.width(), path + " width");
        assertEquals(size, header.height(), path + " height");
        assertEquals(8, header.bitDepth(), path + " bit depth");
        assertEquals(RGBA_COLOR_TYPE, header.colorType(), path + " must be RGBA");
    }

    private static PngHeader readPngHeader(String path) throws IOException {
        byte[] head = readHead(path, PNG_HEADER_LENGTH);
        assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(head, PNG_SIGNATURE.length), path + " is not a PNG");
        assertEquals(13, readInt(head, 8), path + " IHDR chunk length");
        assertEquals("IHDR", new String(head, 12, 4, StandardCharsets.US_ASCII), path + " first chunk must be IHDR");
        return new PngHeader(readInt(head, 16), readInt(head, 20), head[24] & 0xFF, head[25] & 0xFF);
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24
            | (bytes[offset + 1] & 0xFF) << 16
            | (bytes[offset + 2] & 0xFF) << 8
            | (bytes[offset + 3] & 0xFF);
    }

    private static byte[] readHead(String path, int count) throws IOException {
        try (InputStream stream = open(path)) {
            byte[] head = stream.readNBytes(count);
            assertEquals(count, head.length, path + " is shorter than " + count + " bytes");
            return head;
        }
    }

    private static JsonObject readJson(String path) throws IOException {
        try (Reader reader = new InputStreamReader(open(path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static boolean resourceExists(String path) throws IOException {
        try (InputStream stream = ResourceConsistencyTest.class.getResourceAsStream(path)) {
            return stream != null;
        }
    }

    private static InputStream open(String path) {
        InputStream stream = ResourceConsistencyTest.class.getResourceAsStream(path);
        assertNotNull(stream, "missing classpath resource " + path);
        return stream;
    }
}
