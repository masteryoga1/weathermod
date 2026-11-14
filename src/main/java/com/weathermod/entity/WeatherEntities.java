package com.weathermod.entity;

import com.weathermod.WeatherMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's entity types. Common code so both sides agree on the ids; the client registers the renderers that
 * draw them.
 */
public final class WeatherEntities {
    /** Every entity type of the mod, attached to the mod bus from the mod constructor. */
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, WeatherMod.MOD_ID);

    private static final String FLYING_BLOCK_NAME = "flying_block";
    private static final float FLYING_BLOCK_SIZE_BLOCKS = 0.98F;
    private static final int FLYING_BLOCK_TRACKING_RANGE_CHUNKS = 10;
    private static final int FLYING_BLOCK_UPDATE_INTERVAL_TICKS = 2;

    /**
     * {@code weathermod:flying_block}, a block torn loose by a vortex: miscellaneous category, 0.98 by 0.98 blocks
     * like a vanilla falling block, tracked for 10 chunks, its position sent every 2 ticks and its velocity
     * never, never saved, not summonable, fire immune.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<FlyingBlockEntity>> FLYING_BLOCK =
        ENTITY_TYPES.register(FLYING_BLOCK_NAME, () -> EntityType.Builder
            .<FlyingBlockEntity>of(FlyingBlockEntity::new, MobCategory.MISC)
            .sized(FLYING_BLOCK_SIZE_BLOCKS, FLYING_BLOCK_SIZE_BLOCKS)
            .clientTrackingRange(FLYING_BLOCK_TRACKING_RANGE_CHUNKS)
            .updateInterval(FLYING_BLOCK_UPDATE_INTERVAL_TICKS)
            .setShouldReceiveVelocityUpdates(false)
            .noSave()
            .noSummon()
            .fireImmune()
            .build(WeatherMod.MOD_ID + ":" + FLYING_BLOCK_NAME));

    private WeatherEntities() {
    }

    /** Attaches the entity type register to the mod bus; called once from the mod constructor. */
    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }
}
