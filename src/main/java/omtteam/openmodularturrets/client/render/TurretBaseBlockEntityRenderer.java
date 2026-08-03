package omtteam.openmodularturrets.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Renders the authoritative copied block model while the base's static model is hidden.
 */
public final class TurretBaseBlockEntityRenderer
        implements BlockEntityRenderer<TurretBaseBlockEntity> {
    private final BlockRenderDispatcher blockRenderer;
    private final RandomSource random = RandomSource.create();

    public TurretBaseBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        blockRenderer = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(TurretBaseBlockEntity base, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BlockState camouflage = base.camouflageState().orElse(null);
        if (camouflage == null || base.getLevel() == null) {
            return;
        }

        BakedModel model = blockRenderer.getBlockModel(camouflage);
        ModelData modelData = model.getModelData(base.getLevel(), base.getBlockPos(),
                camouflage, ModelData.EMPTY);
        long seed = camouflage.getSeed(base.getBlockPos());
        random.setSeed(seed);
        for (RenderType renderType : model.getRenderTypes(
                camouflage, random, modelData)) {
            random.setSeed(seed);
            blockRenderer.renderBatched(
                    camouflage,
                    base.getBlockPos(),
                    base.getLevel(),
                    poseStack,
                    bufferSource.getBuffer(
                            RenderTypeHelper.getEntityRenderType(renderType, false)),
                    false,
                    random,
                    modelData,
                    renderType);
        }
    }
}
