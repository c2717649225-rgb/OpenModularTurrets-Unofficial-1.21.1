package omtteam.openmodularturrets.client.render;

import java.util.ArrayList;
import java.util.List;

import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretVisualRules;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.core.Direction;

/**
 * Exact 1.12.2 ModelRenderer geometry, expressed as independent modern
 * ModelParts.  The old models deliberately did not use a parent/child gun
 * hierarchy: every part owns its own pivot and rotation, so this class keeps
 * that layout rather than approximating it with a shared receiver.
 */
public final class TurretHeadModel {
    private static final int TEXTURE_SIZE = 64;
    private static final float HALF_PI = 1.570796F;

    private final ModelPart root;
    private final ModelPart base;
    private final ModelPart pole;
    private final ModelPart boxUnder;
    private final ModelPart crystal;
    private final ModelPart pillar;
    private final List<ModelPart> aimParts = new ArrayList<>();
    private final List<ModelPart> mountParts = new ArrayList<>();
    private final List<ModelPart> spinners = new ArrayList<>();

    public TurretHeadModel(ModelPart root) {
        this.root = root;
        base = root.getChild("base");
        pole = child(root, "pole");
        boxUnder = child(root, "box_under");
        crystal = child(root, "crystal");
        pillar = child(root, "pillar_large");

        mountParts.add(base);
        if (pole != null) {
            mountParts.add(pole);
        }
        if (crystal != null) {
            // The legacy relativistic renderer gave every structural component
            // the base-fit rotation before its crystal animation overwrote it.
            for (String name : new String[] {"spike_1", "spike_2", "spike_3",
                    "spike_4", "base_2", "crystal"}) {
                mountParts.add(root.getChild(name));
            }
        }
        for (String name : new String[] {"spinner_1", "spinner_2", "spinner_3",
                "spinner_4"}) {
            ModelPart spinner = child(root, name);
            if (spinner != null) {
                spinners.add(spinner);
            }
        }
        for (String name : new String[] {"cross_bar", "cannon", "barrel",
                "chamber", "shape_1", "gun_stock", "barrel_1", "barrel_2",
                "tank", "launcher", "missile_1", "missile_2", "bar_under",
                "bar_middle", "bar_top", "counter_weight", "barrel_top",
                "barrel_bot", "barrel_right", "barrel_left", "body_bot",
                "body_top", "guard_binder", "right_guard", "left_guard"}) {
            ModelPart part = child(root, name);
            if (part != null) {
                aimParts.add(part);
            }
        }
    }

    /** Applies the old directed-turret target rotation (pitch first, then yaw). */
    public void setAim(float yawRadians, float pitchRadians) {
        if (boxUnder != null) {
            boxUnder.yRot = yawRadians;
        }
        ModelPart left = child(root, "box_left");
        ModelPart right = child(root, "box_right");
        if (left != null) {
            left.xRot = yawRadians;
        }
        if (right != null) {
            right.xRot = yawRadians;
        }
        for (ModelPart part : aimParts) {
            part.xRot = pitchRadians;
            part.yRot = yawRadians;
        }
    }

    /**
     * Applies only the per-part base-fit rotations from ModelAbstractTurret.
     * The renderer still owns the world-space six-direction pose; this method
     * must not rotate the entire assembled model.
     */
    public void setMount(Direction baseDirection) {
        TurretVisualRules.MountRotation rotation =
                TurretVisualRules.mountRotation(baseDirection);
        for (ModelPart part : mountParts) {
            part.xRot = rotation.xRadians();
            part.yRot = rotation.yRadians();
        }
        if (boxUnder != null) {
            boxUnder.xRot = rotation.xRadians();
        }
    }

    /** Reproduces the 1.12 relativistic crystal's per-tick rotation. */
    public void setRelativisticAnimation(float rotationAnimation) {
        if (crystal != null) {
            crystal.xRot = rotationAnimation;
            crystal.yRot = rotationAnimation;
            crystal.zRot = rotationAnimation;
        }
    }

    /** Reproduces the 1.12 teleporter spinner/pillar animation. */
    public void setTeleporterAnimation(float rotationAnimation) {
        for (ModelPart spinner : spinners) {
            spinner.yRot = rotationAnimation;
        }
        if (pillar != null) {
            pillar.yRot = -rotationAnimation;
        }
    }

    public void render(PoseStack poseStack, VertexConsumer consumer, int packedLight,
            int packedOverlay) {
        root.render(poseStack, consumer, packedLight, packedOverlay);
    }

    public static LayerDefinition createBodyLayer(TurretDefinition definition) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        switch (definition) {
            case DISPOSABLE -> disposable(root);
            case POTATO -> potato(root);
            case MACHINE_GUN -> machineGun(root);
            case INCENDIARY -> incendiary(root);
            case GRENADE, PLASMA -> grenade(root);
            case RELATIVISTIC -> relativistic(root);
            case ROCKET -> rocket(root);
            case TELEPORTER -> teleporter(root);
            case LASER -> laser(root);
            case RAIL_GUN -> railGun(root);
        }
        return LayerDefinition.create(mesh, TEXTURE_SIZE, TEXTURE_SIZE);
    }

    private static void disposable(PartDefinition root) {
        standardBase(root, 37, 28, 19, false);
        standardSides(root, 19);
        box(root, "cannon", 20, 0, -2, -3, -12, 4, 4, 14, 0, 16, 0, 0, 0, 0);
    }

    private static void potato(PartDefinition root) {
        standardBase(root, 37, 28, 15, false);
        standardSides(root, 15);
        box(root, "barrel", 36, 0, -1.001F, -2.001F, -10.99F, 2.002F, 3.002F, 11.98F, 0, 15, 0, 0, 0, 0);
        box(root, "chamber", 0, 4, -2.001F, -3.001F, 1.001F, 4.002F, 4.002F, 3.998F, 0, 15, 0, 0, 0, 0);
    }

    private static void machineGun(PartDefinition root) {
        standardBase(root, 37, 28, 15, false);
        standardSides(root, 15);
        box(root, "gun_stock", 36, 0, -2, -5, -2, 4, 8, 8, 0, 15, 0, 0, 0, 0);
        box(root, "shape_1", 32, 21, -1, -2, -15, 2, 2, 14, 0, 17, 0, 0, 0, 0);
    }

    private static void incendiary(PartDefinition root) {
        standardBase(root, 37, 28, 15, true);
        standardSides(root, 15);
        box(root, "barrel_1", 0, 0, 1, -1, -10, 2, 2, 11, 0, 15, 0, 0, 0, 0);
        box(root, "barrel_2", 0, 0, -3, -1, -10, 2, 2, 11, 0, 15, 0, 0, 0, 0);
        box(root, "tank", 29, 0, -3, -3, -6, 6, 4, 10, 0, 16, 0, 0, 0, 0);
    }

    private static void grenade(PartDefinition root) {
        standardBase(root, 37, 28, 15, false);
        standardSides(root, 15);
        box(root, "barrel", 32, 0, -2, -4, -8, 4, 4, 12, 0, 16, 0, 0, 0, 0);
        box(root, "chamber", 35, 20, -3, -5, 3, 6, 6, 6, 0, 16, 0, 0, 0, 0);
        box(root, "shape_1", 0, 5, -1, -6, -7, 2, 2, 4, 0, 16, 0, 0, 0, 0);
    }

    private static void rocket(PartDefinition root) {
        standardBase(root, 37, 28, 15, false);
        standardSides(root, 15);
        box(root, "launcher", 36, 0, -2, -5, -2, 4, 8, 8, 0, 15, 0, 0, 0, 0);
        box(root, "missile_1", 0, 6, -1, -5, -4, 2, 2, 2, 0, 16, 0, 0, 0, 0);
        box(root, "missile_2", 0, 6, -1, -1, -4, 2, 2, 2, 0, 16, 0, 0, 0, 0);
    }

    private static void laser(PartDefinition root) {
        standardBase(root, 37, 28, 15, false);
        standardSides(root, 15);
        box(root, "chamber", 20, 0, -2, -7, -3, 4, 7, 4, 0, 16, 0.1F, 0, 0, 0);
        box(root, "bar_under", 37, 0, -1, -2, -12, 2, 1, 10, 0, 16, 0, 0, 0, 0);
        box(root, "bar_middle", 39, 26, -1, -4, -8, 2, 1, 7, 0, 16, 0, 0, 0, 0);
        box(root, "bar_top", 37, 16, -1, -6, -6, 2, 1, 7, 0, 16, 0, 0, 0, 0);
        box(root, "counter_weight", 0, 4, -2, -6, 1, 4, 4, 4, 0, 17, 0, 0, 0, 0);
    }

    private static void relativistic(PartDefinition root) {
        box(root, "base", 0, 37, -6, 7, -6, 12, 1, 12, 0, 16, 0, 0, 0, 0);
        box(root, "spike_1", 24, 0, -6, 0, -6, 1, 8, 1, 0, 15, 0, 0, 0, 0);
        box(root, "spike_2", 24, 0, -6, 0, 5, 1, 8, 1, 0, 15, 0, 0, 0, 0);
        box(root, "spike_3", 24, 0, 5, 0, -6, 1, 8, 1, 0, 15, 0, 0, 0, 0);
        box(root, "spike_4", 24, 0, 5, 0, 5, 1, 8, 1, 0, 15, 0, 0, 0, 0);
        box(root, "base_2", 0, 0, -2, 6, -2, 4, 2, 4, 0, 15, 0, 0, 0, 0);
        box(root, "crystal", 0, 25, -2, -2, -2, 4, 4, 4, 0, 15, 0,
                0.7853982F, 0.7853982F, 0.7853982F);
    }

    private static void teleporter(PartDefinition root) {
        box(root, "base", 0, 37, -6, 7, -6, 12, 1, 12, 0, 16, 0, 0, 0, 0);
        box(root, "base_stand", 0, 51, -6, -1, -6, 12, 1, 12, 0, 13, 0, 0, 0, 0);
        box(root, "pillar_large", 0, 0, -2, 0, -2, 4, 10, 4, 0, 13, 0, 0, 0, 0);
        box(root, "spinner_1", 0, 14, -5, 0, -2, 1, 8, 4, 0, 14, 0, 0, 0, 0);
        box(root, "spinner_2", 0, 26, -2, 0, 4, 4, 8, 1, 0, 14, 0, 0, 0, 0);
        box(root, "spinner_3", 0, 26, -2, 0, -5, 4, 8, 1, 0, 14, 0, 0, 0, 0);
        box(root, "spinner_4", 0, 14, 4, 0, -2, 1, 8, 4, 0, 14, 0, 0, 0, 0);
    }

    private static void railGun(PartDefinition root) {
        box(root, "base", 0, 0, -6, 7, -6, 12, 1, 12, 0, 16, 0, 0, 0, 0);
        box(root, "barrel_top", 25, 27, -1.001F, 2.001F, -16.0F, 2.002F, 1.0F, 17.0F, 0, 15, 0, 0, 0, 0);
        box(root, "barrel_bot", 25, 27, -1, -1, -16, 2, 1, 17, 0, 15, 0, 0, 0, 0);
        box(root, "barrel_right", 25, 45, -2, -1, -16, 1, 2, 17, 0, 15, 0, 0, 0, 0);
        box(root, "barrel_left", 25, 45, 1, -1, -16, 1, 2, 17, 0, 15, 0, 0, 0, 0);
        box(root, "body_bot", 0, 29, -3, 2, 0, 6, 2, 6, 0, 15, 0, 0, 0, 0);
        box(root, "body_top", 0, 37, -3, -3, 1, 6, 4, 6, 0, 15, 0, 0, 0, 0);
        box(root, "binder", 0, 21, -1, 1, 3, 2, 1, 1, 0, 15, 0, 0, 0, 0);
        box(root, "right_guard", 0, 47, -6.1F, -5, -3, 1, 8, 8, 0, 15, 0, 0, 0, 0);
        box(root, "left_guard", 0, 47, 5.1F, -5, -3, 1, 8, 8, 0, 15, 0, 0, 0, 0);
        box(root, "guard_binder", 0, 25, -6, -0.9F, 0, 12, 1, 1, 0, 15, 0, 0, 0, 0);
    }

    private static void standardBase(PartDefinition root, int baseV, int poleV,
            int underV, boolean incendiaryPole) {
        box(root, "base", 0, baseV, -6, 7, -6, 12, 1, 12, 0, 16, 0, 0, 0, 0);
        if (incendiaryPole) {
            box(root, "pole", 0, poleV, -2, 0, -2, 4, 4, 4, 0, 19, 0, 0, 0, 0);
        } else {
            box(root, "pole", 0, poleV, -2, 4, -2, 4, 4, 4, 0, 16, 0, 0, 0, 0);
        }
        box(root, "box_under", 0, underV, -4, 3, -4, 8, 1, 8, 0, 16, 0, 0, 0, 0);
    }

    private static void standardSides(PartDefinition root, int sideV) {
        box(root, "box_left", 0, sideV, -4, 4, -4, 8, 1, 8, 0, 16, 0, 0, 0, HALF_PI);
        box(root, "box_right", 0, sideV, -4, -5, -4, 8, 1, 8, 0, 16, 0, 0, 0, HALF_PI);
        box(root, "cross_bar", 0, 0, -4, -2, 0, 8, 1, 1, 0, 16, 0, 0, 0, 0);
    }

    private static void box(PartDefinition root, String name, int u, int v,
            float x, float y, float z, float width, float height, float depth,
            float pivotX, float pivotY, float pivotZ, float xRot, float yRot,
            float zRot) {
        root.addOrReplaceChild(name, CubeListBuilder.create().mirror().texOffs(u, v)
                .addBox(x, y, z, width, height, depth),
                PartPose.offsetAndRotation(pivotX, pivotY, pivotZ, xRot, yRot, zRot));
    }

    private static ModelPart child(ModelPart root, String name) {
        return root.hasChild(name) ? root.getChild(name) : null;
    }

}
