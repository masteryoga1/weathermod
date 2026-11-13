package com.weathermod.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Checks that the files of the tornado siren and the weather deflector agree with each other and with the ids the
 * code registers: every blockstate names a model that exists, every model names textures that exist and are
 * 16 x 16 RGBA, the loot tables and recipes name the right items, and both blocks are in the two tags that make a
 * stone pickaxe the tool for them. Reads the files from the classpath, so it needs no Minecraft bootstrap.
 */
class BlockResourceTest {
    private static final String ASSETS = "/assets/weathermod/";
    private static final String DATA = "/data/weathermod/";
    private static final String NAMESPACE = "weathermod:";
    private static final String BLOCK_PREFIX = NAMESPACE + "block/";
    private static final String SIREN = "tornado_siren";
    private static final String DEFLECTOR = "weather_deflector";
    private static final List<String> BLOCKS = List.of(SIREN, DEFLECTOR);
    /** Every block model with the textures of its side and of its top and bottom. */
    private static final Map<String, List<String>> MODEL_TEXTURES = Map.of(
        "tornado_siren", List.of("tornado_siren_side", "tornado_siren_top"),
        "tornado_siren_active", List.of("tornado_siren_side_active", "tornado_siren_top"),
        "weather_deflector", List.of("weather_deflector_side", "weather_deflector_top"));
    private static final List<String> TEXTURES = List.of("tornado_siren_side", "tornado_siren_side_active",
        "tornado_siren_top", "weather_deflector_side", "weather_deflector_top");
    private static final List<String> MINEABLE_TAGS = List.of(
        "data/minecraft/tags/block/mineable/pickaxe.json", "data/minecraft/tags/block/needs_stone_tool.json");
    private static final int TEXTURE_SIZE = 16;
    private static final int OPAQUE = 255;
    /** Fewest clearly red texels of the lit lamp band: one of its two rows of twelve. */
    private static final int MIN_LAMP_TEXELS = 12;
    private static final int RGBA_COLOUR_TYPE = 6;
    private static final int PNG_HEADER_LENGTH = 33;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Test
    void sirenBlockstateCoversEveryStateAndLightsUpWhenActive() throws IOException {
        JsonObject variants = readJson(ASSETS + "blockstates/tornado_siren.json").getAsJsonObject("variants");
        assertEquals(Set.of("active=false,powered=false", "active=false,powered=true", "active=true,powered=false",
            "active=true,powered=true"), variants.keySet());
        for (String powered : List.of("false", "true")) {
            assertEquals(BLOCK_PREFIX + "tornado_siren",
                modelOf(variants, "active=false,powered=" + powered), "a silent siren is not lit");
            assertEquals(BLOCK_PREFIX + "tornado_siren_active",
                modelOf(variants, "active=true,powered=" + powered), "a sounding siren is lit");
        }
    }

    @Test
    void deflectorBlockstateHasOneVariant() throws IOException {
        JsonObject variants = readJson(ASSETS + "blockstates/weather_deflector.json").getAsJsonObject("variants");
        assertEquals(Set.of(""), variants.keySet());
        assertEquals(BLOCK_PREFIX + "weather_deflector", modelOf(variants, ""));
    }

    @Test
    void everyBlockstateModelExists() throws IOException {
        Set<String> used = new HashSet<>();
        for (String block : BLOCKS) {
            String path = ASSETS + "blockstates/" + block + ".json";
            JsonObject variants = readJson(path).getAsJsonObject("variants");
            assertNotNull(variants, path + " has no variants");
            for (String variant : variants.keySet()) {
                String model = modelOf(variants, variant);
                assertTrue(model.startsWith(BLOCK_PREFIX), path + " names a model outside the mod: " + model);
                String name = model.substring(BLOCK_PREFIX.length());
                assertTrue(resourceExists(ASSETS + "models/block/" + name + ".json"),
                    path + " names a model with no file: " + model);
                used.add(name);
            }
        }
        assertEquals(MODEL_TEXTURES.keySet(), used, "every block model is used by a blockstate");
    }

    @Test
    void blockModelsAreCubesWithExistingSixteenSquareTextures() throws IOException {
        for (Map.Entry<String, List<String>> entry : MODEL_TEXTURES.entrySet()) {
            String path = ASSETS + "models/block/" + entry.getKey() + ".json";
            JsonObject model = readJson(path);
            assertEquals("minecraft:block/cube_bottom_top", model.get("parent").getAsString(), path);
            JsonObject textures = model.getAsJsonObject("textures");
            assertEquals(Set.of("bottom", "side", "top"), textures.keySet(), path);
            assertEquals(BLOCK_PREFIX + entry.getValue().get(0), textures.get("side").getAsString(), path);
            assertEquals(BLOCK_PREFIX + entry.getValue().get(1), textures.get("top").getAsString(), path);
            assertEquals(textures.get("top").getAsString(), textures.get("bottom").getAsString(),
                path + " uses its top for the bottom");
            for (String key : textures.keySet()) {
                String texture = textures.get(key).getAsString();
                assertTrue(texture.startsWith(BLOCK_PREFIX), path + " names a texture outside the mod: " + texture);
                assertPng(ASSETS + "textures/block/" + texture.substring(BLOCK_PREFIX.length()) + ".png");
            }
        }
    }

    @Test
    void blockTexturesAreSixteenSquareAndOpaque() throws IOException {
        // The models are plain cubes in the solid layer: a texel that is not opaque would not show as a hole.
        for (String name : TEXTURES) {
            String path = ASSETS + "textures/block/" + name + ".png";
            assertPng(path);
            BufferedImage image = readImage(path);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    assertEquals(OPAQUE, image.getRGB(x, y) >>> 24, path + " is not opaque at " + x + "," + y);
                }
            }
        }
    }

    @Test
    void theActiveSirenSideIsTheSilentOneWithARedLamp() throws IOException {
        BufferedImage silent = readImage(ASSETS + "textures/block/tornado_siren_side.png");
        BufferedImage active = readImage(ASSETS + "textures/block/tornado_siren_side_active.png");
        int changed = 0;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                if (silent.getRGB(x, y) != active.getRGB(x, y)) {
                    changed++;
                }
            }
        }
        assertTrue(changed >= MIN_LAMP_TEXELS, "only " + changed + " texels tell the active siren from the silent");
        assertTrue(changed < TEXTURE_SIZE * TEXTURE_SIZE / 2, "the active side must stay the same siren");
        assertEquals(0, countRed(silent), "the silent siren shows no lit lamp");
        int red = countRed(active);
        assertTrue(red >= MIN_LAMP_TEXELS, "the active siren shows only " + red + " red texels");
    }

    @Test
    void itemModelsInheritTheBlockModels() throws IOException {
        for (String block : BLOCKS) {
            String path = ASSETS + "models/item/" + block + ".json";
            assertEquals(BLOCK_PREFIX + block, readJson(path).get("parent").getAsString(), path);
            assertTrue(resourceExists(ASSETS + "models/block/" + block + ".json"), path + " has no parent file");
        }
    }

    @Test
    void lootTablesDropTheBlockItselfUnlessAnExplosionTakesIt() throws IOException {
        for (String block : BLOCKS) {
            String path = DATA + "loot_table/blocks/" + block + ".json";
            JsonObject table = readJson(path);
            assertEquals("minecraft:block", table.get("type").getAsString(), path);
            assertEquals(NAMESPACE + "blocks/" + block, table.get("random_sequence").getAsString(), path);
            JsonArray pools = table.getAsJsonArray("pools");
            assertEquals(1, pools.size(), path + " pools");
            JsonObject pool = pools.get(0).getAsJsonObject();
            assertEquals(1.0, pool.get("rolls").getAsDouble(), path + " rolls");
            JsonArray entries = pool.getAsJsonArray("entries");
            assertEquals(1, entries.size(), path + " entries");
            JsonObject entry = entries.get(0).getAsJsonObject();
            assertEquals("minecraft:item", entry.get("type").getAsString(), path);
            assertEquals(NAMESPACE + block, entry.get("name").getAsString(), path);
            JsonArray conditions = pool.getAsJsonArray("conditions");
            assertEquals(1, conditions.size(), path + " conditions");
            assertEquals("minecraft:survives_explosion",
                conditions.get(0).getAsJsonObject().get("condition").getAsString(), path);
        }
    }

    @Test
    void sirenRecipeIsIronAroundANoteBlockOverRedstone() throws IOException {
        assertShapedRecipe(SIREN, "redstone", List.of("III", "INI", "IRI"), Map.of(
            "I", "minecraft:iron_ingot", "N", "minecraft:note_block", "R", "minecraft:redstone"));
    }

    @Test
    void deflectorRecipeIsCopperAroundRodPearlAndIron() throws IOException {
        assertShapedRecipe(DEFLECTOR, "misc", List.of("CLC", "CEC", "CIC"), Map.of(
            "C", "minecraft:copper_block", "L", "minecraft:lightning_rod", "E", "minecraft:ender_pearl",
            "I", "minecraft:iron_block"));
    }

    @Test
    void bothBlocksAreMineableWithAStonePickaxe() throws IOException {
        for (String tagPath : MINEABLE_TAGS) {
            // Vanilla's file of the same path may be on the classpath too; the mod's is the one naming mod blocks.
            List<JsonObject> ours = new ArrayList<>();
            for (JsonObject tag : readEvery(tagPath)) {
                if (tagEntries(tag).stream().anyMatch(id -> id.startsWith(NAMESPACE))) {
                    ours.add(tag);
                }
            }
            assertEquals(1, ours.size(), tagPath + " must exist once with the mod's blocks");
            JsonObject tag = ours.get(0);
            assertEquals(List.of(NAMESPACE + SIREN, NAMESPACE + DEFLECTOR), tagEntries(tag), tagPath);
            assertFalse(tag.has("replace") && tag.get("replace").getAsBoolean(),
                tagPath + " must add to vanilla's tag, not replace it");
        }
    }

    private static void assertShapedRecipe(String block, String category, List<String> pattern,
                                           Map<String, String> key) throws IOException {
        String path = DATA + "recipe/" + block + ".json";
        JsonObject recipe = readJson(path);
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString(), path);
        assertEquals(category, recipe.get("category").getAsString(), path);
        assertEquals(pattern, stringsOf(recipe.getAsJsonArray("pattern")), path);

        JsonObject keys = recipe.getAsJsonObject("key");
        assertEquals(key.keySet(), keys.keySet(), path + " key symbols");
        for (Map.Entry<String, String> entry : key.entrySet()) {
            JsonObject ingredient = keys.getAsJsonObject(entry.getKey());
            assertEquals(Set.of("item"), ingredient.keySet(), path + " ingredient " + entry.getKey());
            assertEquals(entry.getValue(), ingredient.get("item").getAsString(), path + " " + entry.getKey());
        }
        // A shaped recipe is rejected when a symbol of the pattern has no key or a key is never used.
        Set<String> symbols = new HashSet<>();
        for (String row : pattern) {
            assertEquals(3, row.length(), path + " row " + row);
            for (int i = 0; i < row.length(); i++) {
                symbols.add(row.substring(i, i + 1));
            }
        }
        assertEquals(keys.keySet(), symbols, path + " pattern symbols");

        JsonObject result = recipe.getAsJsonObject("result");
        assertEquals(NAMESPACE + block, result.get("id").getAsString(), path);
        assertEquals(1, result.get("count").getAsInt(), path);
    }

    private static String modelOf(JsonObject variants, String variant) {
        return variants.getAsJsonObject(variant).get("model").getAsString();
    }

    private static List<String> stringsOf(JsonArray array) {
        List<String> strings = new ArrayList<>();
        for (JsonElement element : array) {
            strings.add(element.getAsString());
        }
        return strings;
    }

    /** The ids a tag file lists; an entry may be a plain id or an object with an {@code id}. */
    private static List<String> tagEntries(JsonObject tag) {
        List<String> ids = new ArrayList<>();
        for (JsonElement element : tag.getAsJsonArray("values")) {
            ids.add(element.isJsonObject() ? element.getAsJsonObject().get("id").getAsString()
                : element.getAsString());
        }
        return ids;
    }

    /** How many texels are the red of a lit lamp: strong red, little green and blue. */
    private static int countRed(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if ((argb >> 16 & 0xFF) >= 200 && (argb >> 8 & 0xFF) <= 120 && (argb & 0xFF) <= 100) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Asserts that the file is an 8-bit RGBA PNG of 16 x 16, from its header alone. */
    private static void assertPng(String path) throws IOException {
        byte[] head;
        try (InputStream stream = open(path)) {
            head = stream.readNBytes(PNG_HEADER_LENGTH);
        }
        assertEquals(PNG_HEADER_LENGTH, head.length, path + " is shorter than a PNG header");
        assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(head, PNG_SIGNATURE.length), path + " is not a PNG");
        assertEquals("IHDR", new String(head, 12, 4, StandardCharsets.US_ASCII), path + " first chunk must be IHDR");
        assertEquals(TEXTURE_SIZE, readInt(head, 16), path + " width");
        assertEquals(TEXTURE_SIZE, readInt(head, 20), path + " height");
        assertEquals(8, head[24] & 0xFF, path + " bit depth");
        assertEquals(RGBA_COLOUR_TYPE, head[25] & 0xFF, path + " must be RGBA");
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24
            | (bytes[offset + 1] & 0xFF) << 16
            | (bytes[offset + 2] & 0xFF) << 8
            | (bytes[offset + 3] & 0xFF);
    }

    private static BufferedImage readImage(String path) throws IOException {
        try (InputStream stream = open(path)) {
            BufferedImage image = ImageIO.read(stream);
            assertNotNull(image, path + " could not be decoded");
            return image;
        }
    }

    private static JsonObject readJson(String path) throws IOException {
        try (Reader reader = new InputStreamReader(open(path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** Every file of one path on the classpath, which for a vanilla tag may be more than the mod's own. */
    private static List<JsonObject> readEvery(String path) throws IOException {
        List<JsonObject> found = new ArrayList<>();
        Enumeration<URL> urls = BlockResourceTest.class.getClassLoader().getResources(path);
        while (urls.hasMoreElements()) {
            try (Reader reader = new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8)) {
                found.add(JsonParser.parseReader(reader).getAsJsonObject());
            }
        }
        return found;
    }

    private static boolean resourceExists(String path) throws IOException {
        try (InputStream stream = BlockResourceTest.class.getResourceAsStream(path)) {
            return stream != null;
        }
    }

    private static InputStream open(String path) {
        InputStream stream = BlockResourceTest.class.getResourceAsStream(path);
        assertNotNull(stream, "missing classpath resource " + path);
        return stream;
    }
}
