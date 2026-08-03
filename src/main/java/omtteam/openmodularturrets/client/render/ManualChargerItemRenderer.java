package omtteam.openmodularturrets.client.render;

import omtteam.openmodularturrets.OpenModularTurrets;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class ManualChargerItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            OpenModularTurrets.MOD_ID, "textures/block/lever_block.png");
    private final EntityModelSet modelSet;
    private ManualChargerModel model;

    public ManualChargerItemRenderer(BlockEntityRenderDispatcher dispatcher,
            EntityModelSet modelSet) {
        super(dispatcher, modelSet);
        this.modelSet = modelSet;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        super.onResourceManagerReload(resourceManager);
        model = null;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource bufferSource,
            int packedLight, int packedOverlay) {
        if (model == null) {
            // RegisterClientExtensionsEvent may run before model-layer definitions.
            // Bake only when the item is actually rendered, after layer registration.
            model = new ManualChargerModel(
                    modelSet.bakeLayer(ManualChargerModel.LAYER));
        }
        poseStack.pushPose();
        poseStack.translate(0.5D, 1.5D, 0.5D);
        poseStack.scale(0.7F, -0.7F, -0.7F);
        poseStack.translate(0.0D, 0.4D, 0.5D);
        Vector3f axis = new Vector3f(2.5F, -4.5F, -1.0F).normalize();
        poseStack.mulPose(new Quaternionf().rotationAxis(
                (float) Math.toRadians(45.0D), axis));
        model.setRotation(0.0F);
        model.render(poseStack,
                bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)),
                packedLight, packedOverlay);
        poseStack.popPose();
    }
}
