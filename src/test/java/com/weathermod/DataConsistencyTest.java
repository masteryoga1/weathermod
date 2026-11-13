package com.weathermod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.weathermod.core.ConvectiveParams;
import com.weathermod.core.ModTags;
import com.weathermod.core.PrecipitationMode;
import com.weathermod.core.Trigger;
import com.weathermod.core.TriggerContext;
import com.weathermod.core.VortexKind;
import com.weathermod.core.VortexParams;
import com.weathermod.core.WeatherDamageTypes;
import com.weathermod.core.WeatherFamily;
import com.weathermod.core.WeatherRegistries;
import com.weathermod.core.WeatherType;
import com.weathermod.core.WeatherTypes;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.junit.jupiter.api.Test;

/**
 * Checks that the data files shipped with the mod agree with each other, with the keys the code names them by and
 * with the numbers they were written from: the tag files (their shape, their references, the optional entries of
 * other mods and the tier at which a kind of block goes), the hail damage type, and the weather types of the
 * convective and the vortex family. The tier table of the tornadoes and the table of the convective types are
 * repeated here as constants. Reads the files from the classpath, or from the source tree when the resources do not
 * lie there as plain files, parses the types through {@link WeatherType#CODEC} and never touches a registry, so it
 * needs no Minecraft bootstrap.
 */
class DataConsistencyTest {
    private static final float EPSILON = 1.0e-6F;
    private static final String DATA_RESOURCE = "/data/" + WeatherMod.MOD_ID;
    private static final String LANG_RESOURCE = "/assets/" + WeatherMod.MOD_ID + "/lang/en_us.json";
    private static final String JSON_SUFFIX = ".json";
    private static final String TAG_MARK = "#";
    private static final String MOD_PREFIX = WeatherMod.MOD_ID + ":";
    private static final String VANILLA_NAMESPACE = "minecraft";
    private static final String TIER_TAG_PREFIX = TAG_MARK + MOD_PREFIX + "rippable/ef";
    /**
     * The funnel entity type is registered by the drawing work that ships after the server side. A tag entry naming
     * it before then would stop the whole tag from loading.
     */
    private static final Pattern FUNNEL_ID = Pattern.compile("weathermod:funnel(?![a-z0-9_./-])");

    /** The tag keys with a shipped file. The two family keys of the biome tags ship none. */
    private static final List<TagKey<?>> SHIPPED_TAGS = List.of(
        ModTags.Blocks.RIPPABLE_EF0, ModTags.Blocks.RIPPABLE_EF1, ModTags.Blocks.RIPPABLE_EF2,
        ModTags.Blocks.RIPPABLE_EF3, ModTags.Blocks.RIPPABLE_EF4, ModTags.Blocks.RIPPABLE_EF5,
        ModTags.Blocks.NEVER_RIP, ModTags.Blocks.RIPPABLE_WITH_BLOCK_ENTITY, ModTags.Blocks.HAIL_BREAKS,
        ModTags.Blocks.HAIL_BREAKS_GLASS, ModTags.Blocks.NATURAL_GROUND, ModTags.EntityTypes.RIP_SHIELD,
        ModTags.EntityTypes.WIND_IMMUNE, ModTags.Biomes.HAS_WEATHER_WATERSPOUT);

    /** Number of Enhanced Fujita tiers, EF0 to EF5. */
    private static final int TIERS = 6;

    /** Where a kind of block is listed, checked for every tier: at the named one and at no other. */
    private static final List<Probe> PROBES = List.of(
        new Probe("#minecraft:leaves", 0), new Probe("#minecraft:flowers", 0), new Probe("#minecraft:crops", 0),
        new Probe("minecraft:torch", 0), new Probe("#minecraft:wool_carpets", 0), new Probe("minecraft:snow", 0),
        new Probe("#c:glass_blocks", 1), new Probe("#c:glass_panes", 1), new Probe("#minecraft:wool", 1),
        new Probe("#minecraft:beds", 1), new Probe("#minecraft:all_signs", 1),
        new Probe("#minecraft:wooden_doors", 1), new Probe("#minecraft:wooden_trapdoors", 1),
        new Probe("#minecraft:wooden_fences", 1), new Probe("#minecraft:fence_gates", 1),
        new Probe("minecraft:lantern", 1), new Probe("minecraft:hay_block", 1),
        new Probe("#minecraft:planks", 2), new Probe("#minecraft:wooden_stairs", 2),
        new Probe("#minecraft:wooden_slabs", 2), new Probe("#c:bookshelves", 2), new Probe("#minecraft:sand", 2),
        new Probe("#c:gravels", 2),
        new Probe("#minecraft:logs", 3), new Probe("#minecraft:dirt", 3), new Probe("minecraft:clay", 3),
        new Probe("#minecraft:terracotta", 3), new Probe("#c:sandstone/blocks", 3),
        new Probe("minecraft:netherrack", 3), new Probe("minecraft:cobblestone", 3),
        new Probe("minecraft:mossy_cobblestone", 3), new Probe("#minecraft:fences", 3),
        new Probe("#c:stones", 4), new Probe("#c:cobblestones", 4), new Probe("#minecraft:stone_bricks", 4),
        new Probe("minecraft:bricks", 4), new Probe("#c:concretes", 4), new Probe("minecraft:cobbled_deepslate", 4),
        new Probe("#minecraft:walls", 4), new Probe("#minecraft:stairs", 4), new Probe("#minecraft:slabs", 4),
        new Probe("#minecraft:ice", 4),
        new Probe("#c:obsidians", 5), new Probe("#c:storage_blocks", 5), new Probe("#c:chains", 5),
        new Probe("minecraft:end_stone", 5), new Probe("minecraft:ancient_debris", 5),
        new Probe("minecraft:iron_bars", 5));

    // The tier table of the tornadoes, one value per tier from EF0 to EF5.
    private static final WeatherType.Range TORNADO_INTENSITY = new WeatherType.Range(0.8F, 1.0F);
    private static final float[] DURATION_MIN_SECONDS = {60.0F, 120.0F, 180.0F, 240.0F, 300.0F, 420.0F};
    private static final float[] DURATION_MAX_SECONDS = {150.0F, 240.0F, 360.0F, 480.0F, 600.0F, 720.0F};
    private static final float[] CORE_RADIUS = {3.0F, 5.0F, 8.0F, 12.0F, 18.0F, 26.0F};
    private static final float[] TOP_RADIUS = {10.0F, 16.0F, 22.0F, 30.0F, 40.0F, 55.0F};
    /** The influence radius of the vortex, which is also the radius of the weather type. */
    private static final float[] INFLUENCE_RADIUS = {24.0F, 36.0F, 48.0F, 64.0F, 88.0F, 120.0F};
    private static final float[] INTEREST_RADIUS = {640.0F, 640.0F, 640.0F, 768.0F, 768.0F, 768.0F};
    private static final float[] WIND_SPEED = {8.0F, 13.0F, 18.0F, 23.0F, 28.0F, 33.0F};
    private static final float[] PULL = {0.034F, 0.057F, 0.079F, 0.102F, 0.125F, 0.148F};
    private static final float[] LIFT = {0.05F, 0.07F, 0.14F, 0.17F, 0.18F, 0.22F};
    private static final float[] RISE_CAP = {0.15F, 0.20F, 0.30F, 0.40F, 0.50F, 0.50F};
    /** 0 at EF5 stands for the funnel top. */
    private static final float[] EJECT_HEIGHT = {4.0F, 8.0F, 20.0F, 40.0F, 70.0F, 0.0F};
    private static final float[] RIP_SHARE = {0.125F, 0.25F, 0.375F, 0.625F, 0.875F, 1.0F};
    private static final float[] FLYING_BLOCK_SHARE = {0.125F, 0.25F, 0.5F, 0.75F, 1.0F, 1.0F};
    private static final float[] THROW_SPEED = {6.0F, 9.0F, 13.0F, 16.0F, 20.0F, 23.0F};
    private static final float TORNADO_MIN_SPEED = 0.8F;
    private static final float[] MAX_SPEED = {3.5F, 3.3F, 3.1F, 2.9F, 2.7F, 2.6F};

    // What the tier table promises about its own numbers.
    private static final float WIND_SPEED_BASE = 8.0F;
    private static final float WIND_SPEED_PER_TIER = 5.0F;
    private static final int DEFAULT_RIP_BUDGET = 16;
    private static final int[] RIPS_PER_TICK = {2, 4, 6, 10, 14, 16};
    private static final int DEFAULT_FLYING_BLOCK_CAP = 48;
    private static final int[] FLYING_BLOCKS = {6, 12, 24, 36, 48, 48};
    /** Downward acceleration of a living entity, in blocks per tick squared: lift below it carries nothing. */
    private static final float LIVING_GRAVITY = 0.08F;
    /** Lift is scaled by 0.5 + 0.5 * strength, and a vortex on the ground is never weaker than strength 0.3. */
    private static final float WEAKEST_GROUNDED_LIFT_SCALE = 0.65F;
    /** The lowest tier that carries living entities. */
    private static final int FIRST_CARRYING_TIER = 2;
    /** Walking speed of a player, in blocks per second: every tornado is slower. */
    private static final float WALKING_SPEED = 4.3F;
    /** The slowest a supercell moves, in blocks per second: its tornado must be able to keep up. */
    private static final float SUPERCELL_MIN_SPEED = 1.0F;
    private static final int COLD_FRONT_WEIGHT = 3;

    private static final List<String> SMALL_VORTICES =
        List.of("dust_devil", "fire_whirl", "gustnado", "landspout", "waterspout");
    private static final Set<String> SEVERE_TYPES = Set.of(
        "severe_thunderstorm", "hailstorm", "derecho", "supercell",
        "tornado_ef0", "tornado_ef1", "tornado_ef2", "tornado_ef3", "tornado_ef4", "tornado_ef5");

    /** The table of the convective types. */
    private static final List<ConvectiveRow> CONVECTIVE_TABLE = List.of(
        new ConvectiveRow("thunderstorm", 6, "", 1, 160.0F, 5.5F, 0.05F, 0.0F, 0.0F, 0.25F, 20.0F, 16.0F, 4.0F,
            1.0F, 0.0F, 0.0F, "landspout", 0.02F),
        new ConvectiveRow("severe_thunderstorm", 3, "", 3, 192.0F, 11.0F, 0.10F, 0.5F, 1.0F, 0.6F, 24.0F, 22.0F,
            8.0F, 1.2F, 0.0F, 0.0F, "gustnado", 0.1F),
        new ConvectiveRow("hailstorm", 2, "", 1, 144.0F, 4.0F, 0.02F, 1.0F, 2.0F, 0.2F, 20.0F, 16.0F, 4.0F,
            1.0F, 0.0F, 0.0F, "", 0.0F),
        new ConvectiveRow("squall_line", 6, "cold", 1, 224.0F, 6.0F, 0.05F, 0.0F, 0.0F, 1.0F, 28.0F, 24.0F, 14.0F,
            1.6F, 0.0F, 0.0F, "gustnado", 0.2F),
        new ConvectiveRow("derecho", 3, "cold", 1, 256.0F, 8.0F, 0.05F, 0.3F, 1.0F, 2.0F, 32.0F, 28.0F, 22.0F,
            2.0F, 0.0F, 0.0F, "gustnado", 0.4F),
        new ConvectiveRow("supercell", 2, "", 3, 224.0F, 15.0F, 0.10F, 0.8F, 2.0F, 0.5F, 28.0F, 24.0F, 6.0F,
            0.8F, 1.0F, 0.6F, "", 0.0F));

    /**
     * One entry of a tag file.
     *
     * @param text     the entry as a plain string spells it: an id, or {@code #} and the id of a tag
     * @param required false only for an entry in object form with {@code "required": false}
     */
    private record TagEntry(String text, boolean required) {
        boolean isTag() {
            return text.startsWith(TAG_MARK);
        }

        String id() {
            return isTag() ? text.substring(TAG_MARK.length()) : text;
        }

        String namespace() {
            return id().substring(0, id().indexOf(':'));
        }

        String path() {
            return id().substring(id().indexOf(':') + 1);
        }
    }

    /** A tag entry and the only tier whose own file lists it. */
    private record Probe(String entry, int tier) {
    }

    /** One row of the table of the convective types. An empty small vortex means the type spawns none. */
    private record ConvectiveRow(String name, int weight, String front, int frontWeight, float radius,
                                 float lightningPerMinute, float lightningFireChance, float hail, float hailDamage,
                                 float downburstsPerMinute, float downburstRadius, float downburstSpeed,
                                 float outflowSpeed, float driftMultiplier, float minSpeed, float tornadoChance,
                                 String smallVortex, float smallVorticesPerMinute) {
    }

    @Test
    void everyTagFileParsesAndBelongsToATagKey() throws IOException {
        List<String> expected = SHIPPED_TAGS.stream().map(DataConsistencyTest::tagName).sorted().toList();
        assertEquals(expected, jsonNames(tagDirectory()),
            "the tag files and the shipped keys of ModTags must name the same tags");
        for (TagKey<?> key : SHIPPED_TAGS) {
            assertEquals(WeatherMod.MOD_ID, key.location().getNamespace(), key + " is not a tag of the mod");
            List<String> texts = texts(key);
            assertFalse(texts.isEmpty(), tagName(key) + " lists nothing");
            assertEquals(texts.size(), new HashSet<>(texts).size(), tagName(key) + " lists an entry twice: " + texts);
        }
    }

    @Test
    void everyReferenceToATagOfTheModNamesATagFile() throws IOException {
        List<String> files = jsonNames(tagDirectory());
        int references = 0;
        for (TagKey<?> key : SHIPPED_TAGS) {
            String registry = key.registry().location().getPath();
            for (TagEntry entry : entries(key)) {
                if (entry.isTag() && entry.namespace().equals(WeatherMod.MOD_ID)) {
                    String target = registry + "/" + entry.path();
                    assertTrue(files.contains(target),
                        tagName(key) + " refers to " + entry.text() + ", but there is no tags/" + target + ".json");
                    assertFalse(target.equals(tagName(key)), tagName(key) + " contains itself");
                    references++;
                }
            }
        }
        assertTrue(references > 0, "the tier tags refer to each other");
    }

    @Test
    void entriesOfOtherModsAreOptional() throws IOException {
        int optional = 0;
        for (TagKey<?> key : SHIPPED_TAGS) {
            for (TagEntry entry : entries(key)) {
                String namespace = entry.namespace();
                if (!namespace.equals(VANILLA_NAMESPACE) && !namespace.equals(WeatherMod.MOD_ID)) {
                    assertFalse(entry.required(), tagName(key) + " lists " + entry.text()
                        + " as a required entry: without that mod the whole tag would fail to load");
                    optional++;
                }
            }
        }
        assertTrue(optional > 0, "the tags name entries of the common namespace and of other mods");
    }

    @Test
    void everyTierTagStartsWithTheTierBelow() throws IOException {
        for (int tier = 0; tier < TIERS; tier++) {
            TagKey<?> key = ModTags.Blocks.rippable(tier);
            assertEquals(MOD_PREFIX + "rippable/ef" + tier, key.location().toString());
            List<String> texts = texts(key);
            List<String> tierReferences = texts.stream().filter(text -> text.startsWith(TIER_TAG_PREFIX)).toList();
            if (tier == 0) {
                assertEquals(List.of(), tierReferences, "the lowest tier contains no other tier");
            } else {
                String below = TIER_TAG_PREFIX + (tier - 1);
                assertEquals(below, texts.get(0), "EF" + tier + " must start with the tier below");
                assertEquals(List.of(below), tierReferences, "EF" + tier + " contains exactly the tier below");
            }
        }
    }

    @Test
    void tierLookupsAreClampedToTheSixTiers() {
        assertEquals(TIERS, WeatherTypes.TORNADO_TIERS.size());
        for (int tier = 0; tier < TIERS; tier++) {
            assertSame(WeatherTypes.TORNADO_TIERS.get(tier), WeatherTypes.tornado(tier));
            assertEquals("tornado_ef" + tier, WeatherTypes.tornado(tier).location().getPath());
        }
        assertSame(WeatherTypes.TORNADO_EF0, WeatherTypes.tornado(-1));
        assertSame(WeatherTypes.TORNADO_EF5, WeatherTypes.tornado(TIERS));
        assertSame(ModTags.Blocks.RIPPABLE_EF0, ModTags.Blocks.rippable(-1));
        assertSame(ModTags.Blocks.RIPPABLE_EF5, ModTags.Blocks.rippable(TIERS));
    }

    @Test
    void blocksGoAtTheTierTheyWereAssigned() throws IOException {
        List<List<String>> tierTexts = tierTexts();
        for (Probe probe : PROBES) {
            List<Integer> tiers = new ArrayList<>();
            for (int tier = 0; tier < TIERS; tier++) {
                if (tierTexts.get(tier).contains(probe.entry())) {
                    tiers.add(tier);
                }
            }
            assertEquals(List.of(probe.tier()), tiers,
                probe.entry() + " must be listed at EF" + probe.tier() + " and at no other tier");
        }
    }

    @Test
    void noEntryIsListedAtTwoTiers() throws IOException {
        List<List<String>> tierTexts = tierTexts();
        Set<String> seen = new HashSet<>();
        for (int tier = 0; tier < TIERS; tier++) {
            for (String text : tierTexts.get(tier)) {
                if (!text.startsWith(TIER_TAG_PREFIX)) {
                    assertTrue(seen.add(text), text + " is listed at EF" + tier + " although a lower tier has it");
                }
            }
        }
    }

    @Test
    void neverRipIsBuiltOnWitherImmuneAndKeepsTheUniqueBlocks() throws IOException {
        List<String> neverRip = texts(ModTags.Blocks.NEVER_RIP);

        assertTrue(neverRip.contains("#minecraft:wither_immune"), "never_rip is built on wither_immune");
        assertFalse(neverRip.contains("#minecraft:dragon_immune"),
            "dragon_immune holds obsidian, which the highest tier must be able to take");
        assertTrue(neverRip.contains("minecraft:dragon_egg"), "the dragon egg is unique and must stay");
        assertTrue(neverRip.contains("minecraft:heavy_core"), "the heavy core is hard-won and must stay");
        for (List<String> tier : tierTexts()) {
            for (String text : tier) {
                assertFalse(neverRip.contains(text), text + " is both rippable and never ripped");
            }
        }
    }

    @Test
    void naturalGroundLeavesOutWhatPlayersBuildWith() throws IOException {
        List<String> ground = texts(ModTags.Blocks.NATURAL_GROUND);

        assertFalse(ground.contains("#minecraft:terracotta"), "players build with terracotta");
        assertFalse(ground.contains("#c:sandstone/blocks"), "players build with sandstone");
        assertTrue(ground.contains("#minecraft:dirt"), "dirt is terrain");
        assertTrue(ground.contains("#minecraft:sand"), "sand is terrain");
        assertTrue(ground.contains("#minecraft:base_stone_overworld"), "stone is terrain");
    }

    @Test
    void onlyBedsBannersAndSignsAreRippedWithABlockEntity() throws IOException {
        List<String> withBlockEntity = texts(ModTags.Blocks.RIPPABLE_WITH_BLOCK_ENTITY);

        assertEquals(Set.of("#minecraft:beds", "#minecraft:banners", "#minecraft:all_signs"),
            new HashSet<>(withBlockEntity));
        List<List<String>> tierTexts = tierTexts();
        for (String text : withBlockEntity) {
            assertTrue(tierTexts.stream().anyMatch(tier -> tier.contains(text)), text + " is rippable at no tier");
        }
    }

    @Test
    void whatShieldsBlocksIsNeverPushed() throws IOException {
        String shield = TAG_MARK + ModTags.EntityTypes.RIP_SHIELD.location();

        assertEquals("#weathermod:rip_shield", shield);
        assertTrue(texts(ModTags.EntityTypes.WIND_IMMUNE).contains(shield), "wind_immune must contain rip_shield");
    }

    @Test
    void noDataFileMentionsTheFunnelEntity() throws IOException {
        Path directory = dataDirectory();
        List<Path> files = regularFiles(directory);
        assertFalse(files.isEmpty(), "no data file found in " + directory.toAbsolutePath());
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            assertFalse(FUNNEL_ID.matcher(text).find(), relativeName(directory, file) + " names weathermod:funnel");
        }
    }

    @Test
    void hailDamageTypeHasTheRequiredKeys() throws IOException {
        JsonObject hail = hailDamageType();

        assertEquals("weathermod.hail", hail.get("message_id").getAsString());
        assertEquals("never", hail.get("scaling").getAsString());
        assertEquals(0.1F, hail.get("exhaustion").getAsFloat(), EPSILON);
    }

    @Test
    void hailDeathMessagesAreTranslated() throws IOException {
        String key = "death.attack." + hailDamageType().get("message_id").getAsString();
        JsonObject lang = lang();

        assertTrue(lang.has(key), "en_us.json lacks " + key);
        assertTrue(lang.has(key + ".player"), "en_us.json lacks " + key + ".player");
    }

    @Test
    void weatherTypeKeysMatchTheFiles() throws IOException {
        List<String> keys = new ArrayList<>();
        for (ResourceKey<WeatherType> key : WeatherTypes.ALL) {
            assertTrue(key.isFor(WeatherRegistries.WEATHER_TYPE), key + " is not a weather type key");
            assertEquals(WeatherMod.MOD_ID, key.location().getNamespace(), key + " is not a type of the mod");
            keys.add(key.location().getPath());
        }
        assertEquals(keys.size(), new HashSet<>(keys).size(), "WeatherTypes.ALL lists a key twice: " + keys);
        Collections.sort(keys);

        assertEquals(typeNames(), keys, "WeatherTypes.ALL must hold one key per shipped weather type file");
    }

    @Test
    void tornadoFilesEqualTheTierTable() throws IOException {
        for (int tier = 0; tier < TIERS; tier++) {
            String name = "tornado_ef" + tier;
            WeatherType type = type(name);
            assertSame(WeatherFamily.VORTEX, type.family(), name + " family");
            assertEquals(TORNADO_INTENSITY, type.intensity(), name + " intensity");
            assertEquals(new WeatherType.Range(DURATION_MIN_SECONDS[tier], DURATION_MAX_SECONDS[tier]),
                type.durationSeconds(), name + " duration_seconds");
            assertEquals(INFLUENCE_RADIUS[tier], type.radius(), EPSILON, name + " radius");
            assertEquals(INTEREST_RADIUS[tier], type.interestRadius(), EPSILON, name + " interest_radius");
            assertEquals(0, type.weight(), name + " weight: only supercells and commands make tornadoes");
            assertFalse(type.spawnsNaturally(), name + " must not spawn naturally");
            assertTrue(type.enabled(), name + " enabled");
            assertSame(PrecipitationMode.NONE, type.precipitation(), name + " precipitation");
            assertEquals(0.0F, type.visuals().rainLevel(), EPSILON, name + " rain_level");
            assertEquals(0.0F, type.visuals().thunderLevel(), EPSILON, name + " thunder_level");
            assertEquals(Optional.empty(), type.convective(), name + " convective");
            assertEquals(Trigger.ANY, type.trigger(), name + " trigger");

            VortexParams vortex = vortex(name);
            assertSame(VortexKind.TORNADO, vortex.kind(), name + " kind");
            assertEquals(tier, vortex.tier(), name + " tier");
            assertEquals(CORE_RADIUS[tier], vortex.coreRadius(), EPSILON, name + " core_radius");
            assertEquals(TOP_RADIUS[tier], vortex.topRadius(), EPSILON, name + " top_radius");
            assertEquals(INFLUENCE_RADIUS[tier], vortex.influenceRadius(), EPSILON, name + " influence_radius");
            assertTrue(vortex.reachesCloudBase(), name + " reaches the cloud base, so it sets no height");
            assertEquals(WIND_SPEED[tier], vortex.windSpeed(), EPSILON, name + " wind_speed");
            assertEquals(PULL[tier], vortex.pull(), EPSILON, name + " pull");
            assertEquals(LIFT[tier], vortex.lift(), EPSILON, name + " lift");
            assertEquals(RISE_CAP[tier], vortex.riseCap(), EPSILON, name + " rise_cap");
            assertEquals(EJECT_HEIGHT[tier], vortex.ejectHeight(), EPSILON, name + " eject_height");
            assertEquals(RIP_SHARE[tier], vortex.ripShare(), EPSILON, name + " rip_share");
            assertEquals(FLYING_BLOCK_SHARE[tier], vortex.flyingBlockShare(), EPSILON, name + " flying_block_share");
            assertEquals(THROW_SPEED[tier], vortex.throwSpeed(), EPSILON, name + " throw_speed");
            assertEquals(TORNADO_MIN_SPEED, vortex.minSpeed(), EPSILON, name + " min_speed");
            assertEquals(MAX_SPEED[tier], vortex.maxSpeed(), EPSILON, name + " max_speed");
            assertEquals(TORNADO_MIN_SPEED, type.minSpeed(), EPSILON, name + " slowest ground speed");
            assertEquals(MAX_SPEED[tier], type.maxSpeed(), EPSILON, name + " fastest ground speed");
        }
    }

    @Test
    void tornadoTiersKeepWhatTheirNumbersPromise() throws IOException {
        VortexParams below = null;
        for (int tier = 0; tier < TIERS; tier++) {
            String name = "tornado_ef" + tier;
            VortexParams vortex = vortex(name);
            assertEquals(WIND_SPEED_BASE + WIND_SPEED_PER_TIER * tier, vortex.windSpeed(), EPSILON,
                name + " wind_speed is 8 + 5 * tier");
            assertEquals(RIPS_PER_TICK[tier], Math.round(DEFAULT_RIP_BUDGET * vortex.ripShare()),
                name + " rips per tick at the default budget");
            assertEquals(FLYING_BLOCKS[tier], Math.round(DEFAULT_FLYING_BLOCK_CAP * vortex.flyingBlockShare()),
                name + " flying blocks at the default cap");
            if (tier < FIRST_CARRYING_TIER) {
                assertTrue(vortex.lift() < LIVING_GRAVITY, name + " must never carry a living entity");
            } else {
                assertTrue(vortex.lift() * WEAKEST_GROUNDED_LIFT_SCALE > LIVING_GRAVITY,
                    name + " must carry living entities at every strength it has on the ground");
            }
            assertTrue(vortex.maxSpeed() < WALKING_SPEED, name + " must be slower than a walking player");
            assertTrue(vortex.minSpeed() <= SUPERCELL_MIN_SPEED, name + " must be able to match its supercell");
            if (below != null) {
                assertTrue(vortex.coreRadius() > below.coreRadius(), name + " core_radius grows with the tier");
                assertTrue(vortex.topRadius() > below.topRadius(), name + " top_radius grows with the tier");
                assertTrue(vortex.influenceRadius() > below.influenceRadius(),
                    name + " influence_radius grows with the tier");
                assertTrue(vortex.pull() > below.pull(), name + " pull grows with the tier");
                assertTrue(vortex.lift() > below.lift(), name + " lift grows with the tier");
                assertTrue(vortex.maxSpeed() < below.maxSpeed(), name + " is slower than the tier below");
            }
            below = vortex;
        }
    }

    @Test
    void everyVortexTypeIsAsWideAsItsInfluence() throws IOException {
        List<String> small = new ArrayList<>();
        int tornadoes = 0;
        for (String name : typeNames()) {
            WeatherType type = type(name);
            if (type.vortex().isEmpty()) {
                continue;
            }
            VortexParams vortex = type.vortex().get();
            assertEquals(vortex.influenceRadius(), type.radius(), name + " radius must equal vortex.influence_radius");
            if (vortex.kind().isRated()) {
                assertEquals("tornado_ef" + vortex.tier(), name, "a tornado file is named after its tier");
                tornadoes++;
            } else {
                assertEquals(vortex.kind().getSerializedName(), name, "a small vortex file is named after its kind");
                small.add(name);
            }
        }
        assertEquals(TIERS, tornadoes, "one tornado file per tier");
        assertEquals(SMALL_VORTICES, small, "the small vortex types");
    }

    @Test
    void smallVorticesNeverCarryAndRipOnlyTheLowestTier() throws IOException {
        for (String name : SMALL_VORTICES) {
            VortexParams vortex = vortex(name);
            assertEquals(0, vortex.tier(), name + " tier");
            assertTrue(vortex.lift() < LIVING_GRAVITY, name + " must never lift a player off the ground");
            assertFalse(type(name).severe(), name + " is not severe weather");
        }
    }

    @Test
    void onlyThreeSmallVorticesFormOnTheirOwn() throws IOException {
        assertEquals(0, type("landspout").weight(), "landspouts are spawned by their parent storm");
        assertEquals(0, type("gustnado").weight(), "gustnadoes are spawned by their parent storm");
        assertEquals(2, type("waterspout").weight(), "waterspout weight");
        assertEquals(3, type("dust_devil").weight(), "dust_devil weight");
        assertEquals(4, type("fire_whirl").weight(), "fire_whirl weight");
    }

    @Test
    void smallVorticesFormWhereTheyBelong() throws IOException {
        ResourceLocation overworld = ResourceLocation.withDefaultNamespace("overworld");
        ResourceLocation nether = ResourceLocation.withDefaultNamespace("the_nether");

        WeatherType waterspout = type("waterspout");
        assertEquals(Optional.of(ModTags.Biomes.HAS_WEATHER_WATERSPOUT), waterspout.biomes(), "waterspout biomes");
        assertEquals(List.of("#minecraft:is_ocean"), texts(ModTags.Biomes.HAS_WEATHER_WATERSPOUT),
            "waterspouts form over the oceans");
        assertEquals(List.of(overworld), waterspout.dimensions(), "waterspout dimensions");

        WeatherType dustDevil = type("dust_devil");
        assertEquals(new Trigger(0.0F, 6.0F, Trigger.TimeOfDay.DAY, "", 1), dustDevil.trigger(), "dust_devil trigger");
        assertTrue(dustDevil.trigger().accepts(new TriggerContext(6.0F, true, "")), "a calm day");
        assertFalse(dustDevil.trigger().accepts(new TriggerContext(6.5F, true, "")), "too windy");
        assertFalse(dustDevil.trigger().accepts(new TriggerContext(3.0F, false, "")), "night");
        assertTrue(dustDevil.allowsClimate(2.0F, 0.0F), "a desert is hot and dry enough");
        assertFalse(dustDevil.allowsClimate(0.8F, 0.4F), "plains are neither hot nor dry enough");
        assertEquals(List.of(overworld), dustDevil.dimensions(), "dust_devil dimensions");

        WeatherType fireWhirl = type("fire_whirl");
        assertEquals(List.of(nether), fireWhirl.dimensions(), "the fire whirl is the Nether's weather");
        assertFalse(fireWhirl.allowsDimension(overworld), "a fire whirl reaches the overworld only by command");
        assertTrue(fireWhirl.allowsClimate(2.0F, 0.02F), "the climate the Nether reports");
        assertTrue(vortex("fire_whirl").kind().setsFire(), "the fire whirl is the vortex that sets fire");
    }

    @Test
    void everySmallVortexNamesAShippedVortexType() throws IOException {
        List<String> names = typeNames();
        Set<String> spawned = new HashSet<>();
        for (String name : names) {
            Optional<ConvectiveParams> convective = type(name).convective();
            if (convective.isEmpty()) {
                continue;
            }
            Optional<ResourceLocation> smallVortex = convective.get().smallVortex();
            assertEquals(smallVortex.isPresent(), convective.get().smallVorticesPerMinute() > 0.0F,
                name + " must set small_vortex and small_vortices_per_minute together");
            if (smallVortex.isEmpty()) {
                continue;
            }
            ResourceLocation id = smallVortex.get();
            assertEquals(WeatherMod.MOD_ID, id.getNamespace(), name + " small_vortex");
            assertTrue(names.contains(id.getPath()), name + " names the small vortex " + id + ", which has no file");
            assertFalse(vortex(id.getPath()).kind().isRated(),
                name + " names " + id + ": tornadoes come from tornado_chance, not from small_vortex");
            spawned.add(id.getPath());
        }
        assertEquals(Set.of("gustnado", "landspout"), spawned, "the vortices without a natural weight have a parent");
    }

    @Test
    void convectiveFilesEqualTheTableOfTheConvectiveTypes() throws IOException {
        List<String> convectiveTypes = new ArrayList<>();
        for (String name : typeNames()) {
            if (type(name).family() == WeatherFamily.CONVECTIVE) {
                convectiveTypes.add(name);
            }
        }
        assertEquals(CONVECTIVE_TABLE.stream().map(ConvectiveRow::name).sorted().toList(), convectiveTypes,
            "the table must have one row per convective type");

        for (ConvectiveRow row : CONVECTIVE_TABLE) {
            String name = row.name();
            WeatherType type = type(name);
            ConvectiveParams convective = convective(name);
            assertEquals(row.weight(), type.weight(), name + " weight");
            assertEquals(row.front(), type.trigger().front(), name + " trigger.front");
            assertEquals(row.frontWeight(), type.trigger().frontWeight(), name + " trigger.front_weight");
            assertEquals(row.radius(), type.radius(), EPSILON, name + " radius");
            assertEquals(row.lightningPerMinute(), convective.lightningPerMinute(), EPSILON,
                name + " lightning_per_minute");
            assertEquals(row.lightningFireChance(), convective.lightningFireChance(), EPSILON,
                name + " lightning_fire_chance");
            assertEquals(row.hail(), convective.hail(), EPSILON, name + " hail");
            assertEquals(row.hailDamage(), convective.hailDamage(), EPSILON, name + " hail_damage");
            assertEquals(row.downburstsPerMinute(), convective.downburstsPerMinute(), EPSILON,
                name + " downbursts_per_minute");
            assertEquals(row.downburstRadius(), convective.downburstRadius(), EPSILON, name + " downburst_radius");
            assertEquals(row.downburstSpeed(), convective.downburstSpeed(), EPSILON, name + " downburst_speed");
            assertEquals(row.outflowSpeed(), convective.outflowSpeed(), EPSILON, name + " outflow_speed");
            assertEquals(row.driftMultiplier(), convective.driftMultiplier(), EPSILON, name + " drift_multiplier");
            assertEquals(row.driftMultiplier(), type.driftMultiplier(), EPSILON, name + " drift multiplier");
            assertEquals(row.minSpeed(), convective.minSpeed(), EPSILON, name + " min_speed");
            assertEquals(row.minSpeed(), type.minSpeed(), EPSILON, name + " slowest ground speed");
            assertEquals(row.tornadoChance(), convective.tornadoChance(), EPSILON, name + " tornado_chance");
            assertEquals(typeId(row.smallVortex()), convective.smallVortex(), name + " small_vortex");
            assertEquals(row.smallVorticesPerMinute(), convective.smallVorticesPerMinute(), EPSILON,
                name + " small_vortices_per_minute");
        }
    }

    @Test
    void coldFrontsMakeTheStormsTheyShould() throws IOException {
        TriggerContext cold = new TriggerContext(10.0F, true, Trigger.FRONT_COLD);
        TriggerContext warm = new TriggerContext(10.0F, true, Trigger.FRONT_WARM);
        TriggerContext none = new TriggerContext(10.0F, true, "");

        for (String name : List.of("squall_line", "derecho")) {
            Trigger trigger = type(name).trigger();
            assertEquals(Trigger.FRONT_COLD, trigger.front(), name + " forms only along a cold front");
            assertTrue(trigger.accepts(cold), name + " along a cold front");
            assertFalse(trigger.accepts(none), name + " without a front");
            assertFalse(trigger.accepts(warm), name + " along a warm front");
        }
        for (String name : List.of("severe_thunderstorm", "supercell")) {
            WeatherType type = type(name);
            Trigger trigger = type.trigger();
            assertEquals("", trigger.front(), name + " forms anywhere its climate allows");
            assertEquals(COLD_FRONT_WEIGHT, trigger.frontWeight(), name + " trigger.front_weight");
            assertTrue(trigger.accepts(none), name + " without a front");
            assertEquals(COLD_FRONT_WEIGHT * type.weight(), trigger.weight(type.weight(), cold),
                name + " is three times as likely along a cold front");
            assertEquals(type.weight(), trigger.weight(type.weight(), none), name + " without a front");
        }
    }

    @Test
    void everyTornadoCanMatchItsSupercell() throws IOException {
        float parentMinSpeed = convective("supercell").minSpeed();

        assertEquals(SUPERCELL_MIN_SPEED, parentMinSpeed, EPSILON, "supercell convective.min_speed");
        assertEquals(TIERS - 1, convective("supercell").maxTornadoTier(), "a supercell may make every tier");
        for (int tier = 0; tier < TIERS; tier++) {
            String name = "tornado_ef" + tier;
            assertTrue(vortex(name).minSpeed() <= parentMinSpeed,
                name + " must not be forced to move faster than the slowest supercell");
        }
    }

    @Test
    void severeTypesAreTheDamagingStormsAndEveryTornado() throws IOException {
        Set<String> severe = new HashSet<>();
        for (String name : typeNames()) {
            if (type(name).severe()) {
                severe.add(name);
            }
        }

        assertEquals(SEVERE_TYPES, severe, "the types that count for the severe weather cooldown");
    }

    /** Path of a tag's file below {@code tags/}, without the suffix: the registry's path, then the tag's. */
    private static String tagName(TagKey<?> key) {
        return key.registry().location().getPath() + "/" + key.location().getPath();
    }

    /** The entries of a shipped tag in file order, each checked for its shape. */
    private static List<TagEntry> entries(TagKey<?> key) throws IOException {
        Path file = tagDirectory().resolve(tagName(key) + JSON_SUFFIX);
        assertTrue(Files.isRegularFile(file), "no tag file " + file.toAbsolutePath());
        JsonElement values = readObject(file).get("values");
        assertTrue(values != null && values.isJsonArray(), file + " must have a values array");
        List<TagEntry> entries = new ArrayList<>();
        for (JsonElement element : values.getAsJsonArray()) {
            TagEntry entry = entry(file, element);
            assertTrue(entry.id().indexOf(':') > 0 && ResourceLocation.tryParse(entry.id()) != null,
                file + " holds an entry that is not a namespaced id: " + entry.text());
            entries.add(entry);
        }
        return entries;
    }

    private static TagEntry entry(Path file, JsonElement element) {
        if (isString(element)) {
            return new TagEntry(element.getAsString(), true);
        }
        assertTrue(element.isJsonObject(), file + " holds an entry that is neither a string nor an object: " + element);
        JsonObject object = element.getAsJsonObject();
        JsonElement id = object.get("id");
        JsonElement required = object.get("required");
        assertTrue(isString(id), file + " holds an object entry without an id: " + element);
        assertTrue(required != null && required.isJsonPrimitive() && required.getAsJsonPrimitive().isBoolean(),
            file + " holds an object entry without a required flag: " + element);
        return new TagEntry(id.getAsString(), required.getAsBoolean());
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    /** The entries of a shipped tag as plain strings, in file order. */
    private static List<String> texts(TagKey<?> key) throws IOException {
        return entries(key).stream().map(TagEntry::text).toList();
    }

    /** The entries of the six tier tags, index 0 holding EF0. */
    private static List<List<String>> tierTexts() throws IOException {
        List<List<String>> tiers = new ArrayList<>();
        for (int tier = 0; tier < TIERS; tier++) {
            tiers.add(texts(ModTags.Blocks.rippable(tier)));
        }
        return tiers;
    }

    /** The names of the shipped weather types, sorted. */
    private static List<String> typeNames() throws IOException {
        return jsonNames(typeDirectory());
    }

    private static WeatherType type(String name) throws IOException {
        Path file = typeDirectory().resolve(name + JSON_SUFFIX);
        assertTrue(Files.isRegularFile(file), "no weather type file " + file.toAbsolutePath());
        return WeatherType.CODEC.parse(JsonOps.INSTANCE, readJson(file))
            .getOrThrow(message -> new AssertionError(file + " does not parse: " + message));
    }

    private static VortexParams vortex(String name) throws IOException {
        return type(name).vortex().orElseThrow(() -> new AssertionError(name + " has no vortex block"));
    }

    private static ConvectiveParams convective(String name) throws IOException {
        return type(name).convective().orElseThrow(() -> new AssertionError(name + " has no convective block"));
    }

    /** The id of a shipped weather type, or empty for an empty name. */
    private static Optional<ResourceLocation> typeId(String name) {
        return name.isEmpty()
            ? Optional.empty()
            : Optional.of(ResourceLocation.fromNamespaceAndPath(WeatherMod.MOD_ID, name));
    }

    private static JsonObject hailDamageType() throws IOException {
        ResourceLocation id = WeatherDamageTypes.HAIL.location();
        assertEquals(WeatherMod.MOD_ID, id.getNamespace());
        JsonObject hail = readObject(dataDirectory().resolve("damage_type").resolve(id.getPath() + JSON_SUFFIX));
        for (String key : List.of("message_id", "scaling", "exhaustion")) {
            assertTrue(hail.has(key), "damage_type/" + id.getPath() + ".json lacks the required key " + key);
        }
        return hail;
    }

    private static JsonObject lang() throws IOException {
        try (InputStream stream = DataConsistencyTest.class.getResourceAsStream(LANG_RESOURCE)) {
            if (stream != null) {
                String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return JsonParser.parseString(json).getAsJsonObject();
            }
        }
        return readObject(Path.of("src", "main", "resources", "assets", WeatherMod.MOD_ID, "lang", "en_us.json"));
    }

    private static Path tagDirectory() {
        return dataDirectory().resolve("tags");
    }

    private static Path typeDirectory() {
        return dataDirectory().resolve(WeatherMod.MOD_ID).resolve("weather_type");
    }

    /**
     * The directory {@code data/weathermod}: the copy on the test classpath when the resources lie there as plain
     * files, otherwise the one in the source tree.
     */
    private static Path dataDirectory() {
        URL url = DataConsistencyTest.class.getResource(DATA_RESOURCE);
        if (url != null && "file".equals(url.getProtocol())) {
            try {
                Path directory = Path.of(url.toURI());
                if (Files.isDirectory(directory)) {
                    return directory;
                }
            } catch (URISyntaxException exception) {
                throw new AssertionError("Cannot locate " + url, exception);
            }
        }
        Path source = Path.of("src", "main", "resources", "data", WeatherMod.MOD_ID);
        assertTrue(Files.isDirectory(source),
            "data/" + WeatherMod.MOD_ID + " is neither a directory on the classpath nor at " + source.toAbsolutePath());
        return source;
    }

    /** The JSON files below a directory as sorted paths relative to it, with "/" between the parts and no suffix. */
    private static List<String> jsonNames(Path directory) throws IOException {
        assertTrue(Files.isDirectory(directory), "missing directory " + directory.toAbsolutePath());
        List<String> names = new ArrayList<>();
        for (Path file : regularFiles(directory)) {
            String relative = relativeName(directory, file);
            assertTrue(relative.endsWith(JSON_SUFFIX), relative + " in " + directory + " is not a JSON file");
            names.add(relative.substring(0, relative.length() - JSON_SUFFIX.length()));
        }
        Collections.sort(names);
        return names;
    }

    private static List<Path> regularFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static String relativeName(Path directory, Path file) {
        return directory.relativize(file).toString().replace(directory.getFileSystem().getSeparator(), "/");
    }

    private static JsonObject readObject(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "missing file " + file.toAbsolutePath());
        JsonElement root = readJson(file);
        assertTrue(root.isJsonObject(), file + " must hold a JSON object");
        return root.getAsJsonObject();
    }

    private static JsonElement readJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (JsonParseException exception) {
            throw new AssertionError(file + " is not valid JSON", exception);
        }
    }
}
