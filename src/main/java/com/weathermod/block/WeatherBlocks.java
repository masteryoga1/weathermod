package com.weathermod.block;

import com.weathermod.WeatherMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's blocks with their items and block entity types: the tornado siren, which lights up and wails while a
 * tornado is near, and the weather deflector, which keeps tornadoes out of a circle and stops storm damage inside
 * it. Common code so both sides agree on the ids. Also puts the two items into the vanilla creative tabs.
 */
@EventBusSubscriber(modid = WeatherMod.MOD_ID)
public final class WeatherBlocks {
    /** Hardness of both blocks: an iron tool breaks them as fast as a lightning rod. */
    public static final float DESTROY_TIME = 3.0F;
    /** Blast resistance of both blocks, that of iron blocks and lightning rods. */
    public static final float EXPLOSION_RESISTANCE = 6.0F;

    /** Every block of the mod, attached to the mod bus from the mod constructor. */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(WeatherMod.MOD_ID);
    /** Every item of the mod, attached to the mod bus from the mod constructor. */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WeatherMod.MOD_ID);
    /** Every block entity type of the mod, attached to the mod bus from the mod constructor. */
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, WeatherMod.MOD_ID);

    /** {@code weathermod:tornado_siren}: lit while a tornado is in range or while powered by redstone. */
    public static final DeferredBlock<TornadoSirenBlock> TORNADO_SIREN = BLOCKS.registerBlock("tornado_siren",
        TornadoSirenBlock::new, BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .sound(SoundType.METAL)
            .strength(DESTROY_TIME, EXPLOSION_RESISTANCE)
            .requiresCorrectToolForDrops()
            .lightLevel(state -> state.getValue(TornadoSirenBlock.ACTIVE) ? TornadoSirenBlock.ACTIVE_LIGHT_LEVEL : 0));
    /** {@code weathermod:weather_deflector}: remembered by the dimension's deflector index while it stands. */
    public static final DeferredBlock<WeatherDeflectorBlock> WEATHER_DEFLECTOR = BLOCKS.registerBlock(
        "weather_deflector", WeatherDeflectorBlock::new, BlockBehaviour.Properties.of()
            .mapColor(MapColor.WARPED_NYLIUM)
            .sound(SoundType.METAL)
            .strength(DESTROY_TIME, EXPLOSION_RESISTANCE)
            .requiresCorrectToolForDrops());

    /** The item of the tornado siren. */
    public static final DeferredItem<BlockItem> TORNADO_SIREN_ITEM =
        ITEMS.registerSimpleBlockItem(TORNADO_SIREN, new Item.Properties());
    /** The item of the weather deflector. */
    public static final DeferredItem<BlockItem> WEATHER_DEFLECTOR_ITEM =
        ITEMS.registerSimpleBlockItem(WEATHER_DEFLECTOR, new Item.Properties());

    /** The block entity type of the tornado siren, which holds its hold counter and runs its checks. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TornadoSirenBlockEntity>>
        TORNADO_SIREN_ENTITY = BLOCK_ENTITY_TYPES.register("tornado_siren", () -> BlockEntityType.Builder
            .of(TornadoSirenBlockEntity::new, TORNADO_SIREN.get()).build(null));
    /** The block entity type of the weather deflector, which reports the block to the index when it loads. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WeatherDeflectorBlockEntity>>
        WEATHER_DEFLECTOR_ENTITY = BLOCK_ENTITY_TYPES.register("weather_deflector", () -> BlockEntityType.Builder
            .of(WeatherDeflectorBlockEntity::new, WEATHER_DEFLECTOR.get()).build(null));

    private WeatherBlocks() {
    }

    /** Attaches the block, item and block entity type registers to the mod bus; called once by the mod constructor. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
    }

    /** Adds the siren to the redstone tab and the deflector to the functional blocks tab. */
    @SubscribeEvent
    public static void onBuildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(TORNADO_SIREN_ITEM);
        } else if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(WEATHER_DEFLECTOR_ITEM);
        }
    }
}
