package omtteam.openmodularturrets.client.render;

import omtteam.openmodularturrets.OpenModularTurrets;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

public final class ManualChargerModel {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID,
                    "manual_charger"), "main");

    private final ModelPart root;
    private final ModelPart handle;
    private final ModelPart post;

    public ManualChargerModel(ModelPart root) {
        this.root = root;
        handle = root.getChild("handle");
        post = root.getChild("post");
    }

    public void setRotation(float radians) {
        handle.zRot = radians;
        post.zRot = radians;
    }

    public void render(PoseStack poseStack, VertexConsumer consumer,
            int packedLight, int packedOverlay) {
        root.render(poseStack, consumer, packedLight, packedOverlay);
    }

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("handle", CubeListBuilder.create().mirror()
                        .texOffs(0, 0).addBox(-1.0F, -1.0F, -9.0F,
                                2.0F, 2.0F, 8.0F),
                PartPose.offset(0.0F, 16.0F, 0.0F));
        root.addOrReplaceChild("post", CubeListBuilder.create().mirror()
                        .texOffs(22, 0).addBox(-1.0F, 0.0F, -3.0F,
                                2.0F, 6.0F, 2.0F),
                PartPose.offset(0.0F, 16.0F, 0.0F));
        return LayerDefinition.create(mesh, 32, 32);
    }
}
