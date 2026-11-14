package com.weathermod.gametest;

import com.weathermod.WeatherMod;
import com.weathermod.block.WeatherBlocks;
import com.weathermod.core.ModTags;
import com.weathermod.core.WeatherDamageTypes;
import com.weathermod.entity.WeatherEntities;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.StormInstance;
import com.weathermod.server.family.ConvectiveState;
import com.weathermod.server.family.VortexState;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Saved weather data and the mod's datapack content in the running server. */
final class PersistenceTests {
    private static final int SETTLE_TICKS = 40;
    private static final int FAR_BLOCKS = 4096;

    private PersistenceTests() {
    }

    /**
     * A hailstorm and a tornado run for two seconds so that their family systems have filled their state, next to
     * a listed deflector and a listed temporary fire. The dimension's data is then saved and loaded again: both
     * storms come back tag for tag with equal family state, the deflector and the fire are still listed, and
     * saving the loaded copy gives the same tag. Finally the level's data storage is saved, and the file it
     * writes holds exactly that tag.
     */
    static void stormStateSurvivesSaveAndLoad(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        ServerLevel level = bed.level;
        Vec3 centre = bed.centre();
        // The convective storm sits beside the scene so that its lightning and hail stay off the tornado's ground.
        StormInstance hailstorm = bed.spawnStorm("hailstorm", centre.x + 400.0, centre.z, 0.9F);
        StormInstance tornado = bed.spawnStorm("tornado_ef2", centre.x, centre.z, 1.0F);
        tornado.steer(90.0F, 0.0F);
        // Both lie in chunks that are not loaded, so nothing prunes them, and far outside any storm's reach.
        BlockPos farDeflector = bed.mid(FAR_BLOCKS, 0, 0);
        BlockPos farFire = bed.mid(0, 0, FAR_BLOCKS);
        bed.expect(bed.data.deflectors().add(farDeflector), "The deflector index refused a position");
        bed.expect(bed.data.temporaryFires().add(farFire), "The temporary fire list refused a position");
        CompoundTag[] written = {null};

        bed.after(SETTLE_TICKS, () -> {
            HolderLookup.Provider registries = level.registryAccess();
            ConvectiveState convective = hailstorm.familyState(ConvectiveState.class, ConvectiveState::load);
            VortexState vortex = tornado.familyState(VortexState.class, VortexState::load);
            bed.expect(vortex.seeded && !Double.isNaN(vortex.groundY),
                "The tornado's family state was never filled: " + vortex);
            bed.expect(convective.strikeTicks != ConvectiveState.NOT_SCHEDULED
                    || convective.hailGapTicks != ConvectiveState.NOT_SCHEDULED,
                "The hailstorm's family state was never filled: " + convective);

            CompoundTag saved = bed.data.save(new CompoundTag(), registries);
            DimensionWeatherData loaded = DimensionWeatherData.load(saved.copy(), registries);
            bed.expect(loaded.storms().size() == 2, "Loaded " + loaded.storms().size() + " storms, saved 2");
            for (StormInstance storm : List.of(hailstorm, tornado)) {
                Optional<StormInstance> copy = loaded.find(storm.id());
                bed.expect(copy.isPresent(), "Storm " + storm.id() + " is missing after a save and load");
                bed.expect(copy.get().save().equals(storm.save()),
                    "Storm " + storm.id() + " changed in a save and load: " + storm.save() + " became "
                        + copy.get().save());
            }
            VortexState vortexCopy = loaded.find(tornado.id()).orElseThrow()
                .familyState(VortexState.class, VortexState::load);
            bed.expect(vortexCopy.equals(vortex), "The tornado's state " + vortex + " became " + vortexCopy);
            ConvectiveState convectiveCopy = loaded.find(hailstorm.id()).orElseThrow()
                .familyState(ConvectiveState.class, ConvectiveState::load);
            bed.expect(convectiveCopy.equals(convective),
                "The hailstorm's state " + convective + " became " + convectiveCopy);
            bed.expect(loaded.deflectors().contains(farDeflector), "The deflector position was lost");
            bed.expect(loaded.temporaryFires().size() == 1, "The temporary fire was lost");
            bed.expect(loaded.tickCounter() == bed.data.tickCounter() && loaded.nextStormId() == bed.data.nextStormId(),
                "The tick counter or the next storm id changed in a save and load");
            bed.expect(loaded.director().equals(bed.data.director()), "The director state changed");
            CompoundTag again = loaded.save(new CompoundTag(), registries);
            bed.expect(again.equals(saved), "Saving the loaded data gives another tag: " + saved + " became " + again);

            written[0] = saved;
            level.getDataStorage().save();
        });
        bed.succeedWhen(() -> {
            bed.expect(written[0] != null, "Nothing saved yet");
            CompoundTag onDisk;
            try {
                onDisk = level.getDataStorage().readTagFromDisk(DimensionWeatherData.DATA_NAME, null, 0);
            } catch (IOException failure) {
                throw new GameTestAssertException("The saved weather data cannot be read: " + failure);
            }
            bed.expect(onDisk.getCompound("data").equals(written[0]),
                "The file of the weather data does not hold what was saved");
        });
    }

    /**
     * The datapack content the mod ships is what the running server uses: the hail damage type, both recipes,
     * both loot tables, the tool tags of the two blocks, the tier tags with their nesting, the tags that protect
     * blocks and entities, and the biome tag of the waterspout.
     */
    static void datapackContentIsLoaded(GameTestHelper helper) {
        TestBed bed = TestBed.begin(helper);
        ServerLevel level = bed.level;
        bed.expect(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).containsKey(WeatherDamageTypes.HAIL),
            "The hail damage type is not registered");

        for (Block block : List.of(WeatherBlocks.TORNADO_SIREN.get(), WeatherBlocks.WEATHER_DEFLECTOR.get())) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            BlockState state = block.defaultBlockState();
            Optional<RecipeHolder<?>> recipe = level.getServer().getRecipeManager().byKey(id);
            bed.expect(recipe.isPresent(), "There is no recipe " + id);
            ItemStack crafted = recipe.get().value().getResultItem(level.registryAccess());
            bed.expect(crafted.is(block.asItem()), "The recipe " + id + " makes " + crafted);
            List<ItemStack> drops =
                Block.getDrops(state, level, bed.mid(0, 0, 0), null, null, new ItemStack(Items.IRON_PICKAXE));
            bed.expect(drops.size() == 1 && drops.get(0).is(block.asItem()), id + " drops " + drops);
            bed.expect(state.is(BlockTags.MINEABLE_WITH_PICKAXE), id + " is not mineable with a pickaxe");
            bed.expect(state.is(BlockTags.NEEDS_STONE_TOOL), id + " does not need a stone tool");
            bed.expect(state.is(ModTags.Blocks.NEVER_RIP), id + " is not tagged never_rip");
        }

        expectTier(bed, Blocks.OAK_LEAVES, 0);
        expectTier(bed, Blocks.GLASS, 1);
        expectTier(bed, Blocks.WHITE_WOOL, 1);
        expectTier(bed, Blocks.OAK_PLANKS, 2);
        expectTier(bed, Blocks.OAK_LOG, 3);
        expectTier(bed, Blocks.COBBLESTONE, 3);
        expectTier(bed, Blocks.STONE_BRICKS, 4);
        expectTier(bed, Blocks.OBSIDIAN, 5);
        expectTier(bed, Blocks.IRON_BLOCK, 5);
        bed.expect(Blocks.BEDROCK.defaultBlockState().is(ModTags.Blocks.NEVER_RIP), "Bedrock is not never_rip");
        bed.expect(Blocks.LODESTONE.defaultBlockState().is(ModTags.Blocks.NEVER_RIP), "Lodestone is not never_rip");
        bed.expect(Blocks.GRASS_BLOCK.defaultBlockState().is(ModTags.Blocks.NATURAL_GROUND),
            "Grass is not natural ground");
        bed.expect(Blocks.WHEAT.defaultBlockState().is(ModTags.Blocks.HAIL_BREAKS), "Hail does not break wheat");
        bed.expect(Blocks.GLASS.defaultBlockState().is(ModTags.Blocks.HAIL_BREAKS_GLASS),
            "Glass is not in hail_breaks_glass");
        bed.expect(Blocks.WHITE_BED.defaultBlockState().is(ModTags.Blocks.RIPPABLE_WITH_BLOCK_ENTITY),
            "Beds are not rippable with their block entity");

        bed.expect(EntityType.END_CRYSTAL.is(ModTags.EntityTypes.WIND_IMMUNE), "End crystals are not wind immune");
        bed.expect(WeatherEntities.FLYING_BLOCK.get().is(ModTags.EntityTypes.WIND_IMMUNE),
            "Flying blocks are not wind immune");
        bed.expect(!EntityType.COW.is(ModTags.EntityTypes.WIND_IMMUNE), "Cows are wind immune");
        bed.expect(level.registryAccess().registryOrThrow(Registries.BIOME)
                .getTag(ModTags.Biomes.HAS_WEATHER_WATERSPOUT).filter(tag -> tag.size() > 0).isPresent(),
            "The biome tag " + WeatherMod.MOD_ID + ":has_weather/waterspout is missing or empty");
        bed.succeed();
    }

    /** A block is in the tag of its tier and of every higher tier, and in none below. */
    private static void expectTier(TestBed bed, Block block, int tier) {
        BlockState state = block.defaultBlockState();
        for (int other = 0; other <= 5; other++) {
            boolean tagged = state.is(ModTags.Blocks.rippable(other));
            bed.expect(tagged == (other >= tier), block.getName().getString() + " in the EF" + other
                + " tag: " + tagged + ", its tier is " + tier);
        }
    }
}
