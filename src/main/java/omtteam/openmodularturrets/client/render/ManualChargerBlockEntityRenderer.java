package omtteam.openmodularturrets.client.render;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.block.ManualChargerBlock;
import omtteam.openmodularturrets.blockentity.ManualChargerBlockEntity;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import com.mojang.math.Axis;

public final class ManualChargerBlockEntityRenderer
        implements BlockEntityRenderer<ManualChargerBlockEntity> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            OpenModularTurrets.MOD_ID, "textures/block/lever_block.png");
    private final ManualChargerModel model;

    public ManualChargerBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        model = new ManualChargerModel(context.bakeLayer(ManualChargerModel.LAYER));
    }

    @Override
    public void render(ManualChargerBlockEntity charger, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource,
            int packedLight, int packedOverlay) {
        Direction facing = charger.getBlockState().getValue(ManualChargerBlock.FACING);
        poseStack.pushPose();
        poseStack.translate(0.5D, 1.5D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(yRotation(facing)));
        poseStack.scale(1.0F, -1.0F, -1.0F);
        model.setRotation(charger.animationRadians(partialTick));
        model.render(poseStack,
                bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)),
                packedLight, packedOverlay);
        poseStack.popPose();
    }

    private static float yRotation(Direction facing) {
        return switch (facing) {
            case SOUTH -> 0.0F;
            case WEST -> -90.0F;
            case NORTH -> -180.0F;
            case EAST -> -270.0F;
            default -> 0.0F;
        };
    }
}
