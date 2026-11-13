package com.weathermod.block;

import com.mojang.serialization.MapCodec;
import com.weathermod.server.WeatherManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

/**
 * The tornado siren: a block that lights up, and from the client sound code on wails, while a rated tornado is
 * within the configured range of it or while it is powered by redstone. The block only keeps its two state
 * properties; its block entity looks for tornadoes once a second and holds the alarm for a while after the last
 * one, see {@link TornadoSirenBlockEntity#serverTick}.
 */
public final class TornadoSirenBlock extends BaseEntityBlock {
    /** The block's codec, as every block entity block must declare one. */
    public static final MapCodec<TornadoSirenBlock> CODEC = simpleCodec(TornadoSirenBlock::new);
    /** Whether a redstone signal reaches the block. Written by the block when a neighbour changes. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /**
     * Whether the siren sounds: powered, or a tornado was in range within the hold time. Written by the block
     * entity; the only thing the client reads, for the lit model, the light and the loop sound.
     */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    /** Light the block gives off while active. */
    public static final int ACTIVE_LIGHT_LEVEL = 7;
    /** Translation key of the item tooltip; its argument is the configured range in blocks. */
    public static final String TOOLTIP_KEY = "block.weathermod.tornado_siren.tooltip";

    /** Creates the block with both properties false. */
    public TornadoSirenBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false).setValue(ACTIVE, false));
    }

    @Override
    protected MapCodec<TornadoSirenBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED, ACTIVE);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TornadoSirenBlockEntity(pos, state);
    }

    /**
     * On the server the ticker that looks for tornadoes; on the client one that calls
     * {@link TornadoSirenBlockEntity#clientTick}, the hook the client sound code assigns.
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> blockEntityType) {
        if (level.isClientSide) {
            return createTickerHelper(blockEntityType, WeatherBlocks.TORNADO_SIREN_ENTITY.get(),
                TornadoSirenBlockEntity::callClientHook);
        }
        return createTickerHelper(blockEntityType, WeatherBlocks.TORNADO_SIREN_ENTITY.get(),
            TornadoSirenBlockEntity::serverTick);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !state.is(oldState.getBlock())) {
            updatePowered(state, level, pos);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide) {
            updatePowered(state, level, pos);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents,
                                TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        tooltipComponents.add(
            Component.translatable(TOOLTIP_KEY, WeatherManager.sirenRange()).withStyle(ChatFormatting.GRAY));
    }

    /** Copies the redstone signal at the block into {@link #POWERED} when the two differ. */
    private static void updatePowered(BlockState state, Level level, BlockPos pos) {
        boolean powered = level.hasNeighborSignal(pos);
        if (powered != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_ALL);
        }
    }
}
