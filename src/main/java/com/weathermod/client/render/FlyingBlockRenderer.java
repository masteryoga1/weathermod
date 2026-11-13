package com.weathermod.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.weathermod.entity.FlyingBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws a flying block as the block it carries, the way vanilla draws a falling block: the block's model through
 * the model renderer in the moving-block render types, lit from where the entity is. Only blocks with a model are
 * drawn; a block that needs a block entity renderer or has no shape stays invisible.
 */
public final class FlyingBlockRenderer extends EntityRenderer<FlyingBlockEntity> {
    private static final float SHADOW_RADIUS_BLOCKS = 0.5F;

    private final BlockRenderDispatcher dispatcher;
    private final RandomSource random = RandomSource.create();

    /** Creates the renderer with the block render dispatcher of the given context. */
    public FlyingBlockRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = SHADOW_RADIUS_BLOCKS;
        this.dispatcher = context.getBlockRenderDispatcher();
    }

    /**
     * Draws the carried block with its lower north-west corner half a block from the entity's position, so that
     * the entity stands in the middle of the block's base. Nothing is drawn while the block equals the block at
     * the entity's position: the two would flicker in the same place.
     */
    @Override
    public void render(FlyingBlockEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        BlockState state = entity.blockState();
        if (state.getRenderShape() != RenderShape.MODEL) {
            return;
        }
        Level level = entity.level();
        if (state == level.getBlockState(entity.blockPosition())) {
            return;
        }
        BlockPos lightPos = BlockPos.containing(entity.getX(), entity.getBoundingBox().maxY, entity.getZ());
        long seed = entity.getId();
        BakedModel model = dispatcher.getBlockModel(state);
        poseStack.pushPose();
        poseStack.translate(-0.5, 0.0, -0.5);
        random.setSeed(seed);
        for (RenderType renderType : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            dispatcher.getModelRenderer().tesselateBlock(level, model, state, lightPos, poseStack,
                buffer.getBuffer(RenderTypeHelper.getMovingBlockRenderType(renderType)), false, random, seed,
                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, renderType);
        }
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    /** The block atlas: the carried block's model is drawn from it. */
    @Override
    public ResourceLocation getTextureLocation(FlyingBlockEntity entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
