package com.weathermod.block;

import com.mojang.serialization.MapCodec;
import com.weathermod.WeatherMod;
import com.weathermod.server.DimensionWeatherData;
import com.weathermod.server.WeatherManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The weather deflector: within the configured radius of it the lifting core of a vortex cannot enter and storm
 * damage does not happen. The block does none of that itself; it keeps the dimension's {@link DeflectorIndex} up
 * to date, which the vortex and impact code ask. The index, not the loaded block, is what counts, because a
 * deflector has to work while its chunk is unloaded and from the first tick after a restart.
 */
public final class WeatherDeflectorBlock extends BaseEntityBlock {
    /** The block's codec, as every block entity block must declare one. */
    public static final MapCodec<WeatherDeflectorBlock> CODEC = simpleCodec(WeatherDeflectorBlock::new);
    /** Translation key of the item tooltip; its argument is the configured radius in blocks. */
    public static final String TOOLTIP_KEY = "block.weathermod.weather_deflector.tooltip";

    /** Creates the block. */
    public WeatherDeflectorBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<WeatherDeflectorBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WeatherDeflectorBlockEntity(pos, state);
    }

    /**
     * Lists a newly placed deflector in the dimension's index. A deflector placed while the index is full stands
     * but does nothing, which one warning in the log says.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !state.is(oldState.getBlock())) {
            DimensionWeatherData data = DimensionWeatherData.get(serverLevel);
            DeflectorIndex index = data.deflectors();
            if (index.add(pos)) {
                data.setDirty();
            } else if (!index.contains(pos)) {
                WeatherMod.LOGGER.warn("The weather deflector at {} in {} does nothing: the dimension has {} already",
                    pos.toShortString(), serverLevel.dimension().location(), DeflectorIndex.MAX_DEFLECTORS);
            }
        }
    }

    /** Takes a deflector that really is gone out of the dimension's index, then lets the block entity go. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel && !state.is(newState.getBlock())) {
            DimensionWeatherData data = DimensionWeatherData.get(serverLevel);
            if (data.deflectors().remove(pos)) {
                data.setDirty();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents,
                                TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        tooltipComponents.add(
            Component.translatable(TOOLTIP_KEY, WeatherManager.deflectorRadius()).withStyle(ChatFormatting.GRAY));
    }
}
