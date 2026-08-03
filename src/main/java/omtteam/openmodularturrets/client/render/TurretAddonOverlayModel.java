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
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * The three visible inventory addons from the 1.12 turret renderer.
 */
public final class TurretAddonOverlayModel {
    public static final ModelLayerLocation DAMAGE_AMP_LAYER = layer("damage_amp");
    public static final ModelLayerLocation SOLAR_PANEL_LAYER = layer("solar_panel");
    public static final ModelLayerLocation REDSTONE_REACTOR_LAYER =
            layer("redstone_reactor");

    private final ModelPart root;
    private final ModelPart[] aimedParts;
    private final ModelPart[] resetParts;
    private final float[] resetXPitches;

    public TurretAddonOverlayModel(ModelPart root) {
        this.root = root;
        aimedParts = root.getAllParts().skip(1).toArray(ModelPart[]::new);
        resetParts = aimedParts;
        resetXPitches = new float[aimedParts.length];
        for (int index = 0; index < aimedParts.length; index++) {
            resetXPitches[index] = aimedParts[index].xRot;
        }
    }

    public void setAim(float yawRadians, float pitchRadians) {
        for (ModelPart part : aimedParts) {
            part.yRot = yawRadians;
            part.xRot = pitchRadians;
        }
    }

    public void resetAim() {
        for (int index = 0; index < resetParts.length; index++) {
            resetParts[index].yRot = 0.0F;
            resetParts[index].xRot = resetXPitches[index];
        }
    }

    public void render(PoseStack poseStack, VertexConsumer consumer, int packedLight,
            int packedOverlay) {
        root.render(poseStack, consumer, packedLight, packedOverlay);
    }

    public static void registerLayerDefinitions(
            EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(DAMAGE_AMP_LAYER,
                TurretAddonOverlayModel::createDamageAmpLayer);
        event.registerLayerDefinition(SOLAR_PANEL_LAYER,
                TurretAddonOverlayModel::createSolarPanelLayer);
        event.registerLayerDefinition(REDSTONE_REACTOR_LAYER,
                TurretAddonOverlayModel::createRedstoneReactorLayer);
    }

    private static LayerDefinition createDamageAmpLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("bottom_bar", CubeListBuilder.create().mirror()
                .texOffs(4, 15).addBox(-1.5F, 1.0F, -13.0F, 3.0F, 1.0F, 11.0F),
                PartPose.offset(0.0F, 15.0F, 0.0F));
        addDamageAmpRod(root, "rod_1", 1.001F, -13.0F);
        addDamageAmpRod(root, "rod_2", 1.001F, -11.0F);
        addDamageAmpRod(root, "rod_3", -2.001F, -9.0F);
        addDamageAmpRod(root, "rod_4", -2.001F, -13.0F);
        addDamageAmpRod(root, "rod_5", -2.001F, -11.0F);
        addDamageAmpRod(root, "rod_6", 1.001F, -9.0F);
        return LayerDefinition.create(mesh, 64, 64);
    }

    private static LayerDefinition createSolarPanelLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("shape_1", CubeListBuilder.create().mirror()
                .texOffs(0, 0).addBox(-1.0F, -8.0F, -1.0F, 2.0F, 6.0F, 1.0F),
                PartPose.offsetAndRotation(0.0F, 15.0F, 0.0F, 0.2321598F, 0.0F, 0.0F));
        root.addOrReplaceChild("shape_2", CubeListBuilder.create().mirror()
                .texOffs(26, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 1.0F, 8.0F),
                PartPose.offset(0.0F, 15.0F, 0.0F));
        root.addOrReplaceChild("shape_3", CubeListBuilder.create().mirror()
                .texOffs(0, 0).addBox(-1.0F, -8.0F, 0.0F, 2.0F, 6.0F, 1.0F),
                PartPose.offsetAndRotation(0.0F, 15.0F, 0.0F, -0.2565324F, 0.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 64);
    }

    private static LayerDefinition createRedstoneReactorLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("shape_2", CubeListBuilder.create().mirror()
                .texOffs(5, 15).addBox(-1.0F, 0.0F, 0.0F, 2.0F, 1.0F, 11.0F),
                PartPose.offset(0.0F, 15.0F, 0.0F));
        root.addOrReplaceChild("shape_5", CubeListBuilder.create().mirror()
                .texOffs(5, 15).addBox(-1.0F, -3.0F, 0.0F, 2.0F, 1.0F, 11.0F),
                PartPose.offset(0.0F, 15.0F, 0.0F));
        root.addOrReplaceChild("shape_1", CubeListBuilder.create().mirror()
                .texOffs(0, 0).addBox(-3.0F, -4.0F, 9.0F, 6.0F, 6.0F, 6.0F),
                PartPose.offset(0.0F, 15.0F, 0.0F));
        root.addOrReplaceChild("shape_3", CubeListBuilder.create().mirror()
                .texOffs(29, 0).addBox(-1.0F, -6.0F, 11.0F, 2.0F, 3.0F, 1.0F),
                PartPose.offset(0.0F, 15.0F, 0.0F));
        root.addOrReplaceChild("shape_4", CubeListBuilder.create().mirror()
                .texOffs(29, 0).addBox(-1.0F, -6.0F, 13.0F, 2.0F, 3.0F, 1.0F),
                PartPose.offset(0.0F, 15.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 64);
    }

    private static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(
                ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID,
                        "turret_addon/" + name),
                "main");
    }

    private static void addDamageAmpRod(PartDefinition root, String name, float x, float z) {
        root.addOrReplaceChild(name, CubeListBuilder.create().mirror()
                .texOffs(0, 0).addBox(x, -5.0F, z, 1.0F, 6.0F, 1.0F),
                PartPose.offset(0.0F, 15.0F, 0.0F));
    }
}
