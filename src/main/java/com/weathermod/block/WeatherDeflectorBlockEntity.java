package com.weathermod.block;

import com.weathermod.server.DimensionWeatherData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block entity of the weather deflector. It does not tick and saves nothing; it exists to report its block to
 * the dimension's {@link DeflectorIndex} when its chunk loads. That repairs a world whose index was lost and
 * adopts deflectors a structure or a command placed without the block's placement hook.
 *
 * <p>Removal is left alone on purpose: a block entity is also removed when its chunk unloads, and an unloaded
 * deflector must stay listed. The block takes its position out of the index when it really is gone.
 */
public final class WeatherDeflectorBlockEntity extends BlockEntity {
    /** Creates the block entity of the deflector at a position. */
    public WeatherDeflectorBlockEntity(BlockPos pos, BlockState state) {
        super(WeatherBlocks.WEATHER_DEFLECTOR_ENTITY.get(), pos, state);
    }

    /** On the server, lists the block's position in the dimension's index when it is missing there. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            DimensionWeatherData data = DimensionWeatherData.get(serverLevel);
            DeflectorIndex index = data.deflectors();
            if (!index.contains(worldPosition) && index.add(worldPosition)) {
                data.setDirty();
            }
        }
    }
}
