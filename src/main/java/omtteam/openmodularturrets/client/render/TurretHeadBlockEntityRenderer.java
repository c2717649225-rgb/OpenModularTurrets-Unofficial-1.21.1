package omtteam.openmodularturrets.client.render;

import java.util.EnumMap;
import java.util.Map;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.blockentity.TurretHeadBlockEntity;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretVisualRules;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * Unified renderer for all turret heads.
 */
public final class TurretHeadBlockEntityRenderer
        implements BlockEntityRenderer<TurretHeadBlockEntity> {
    private static final int DAMAGE_AMP_BIT = TurretVisualRules.ADDON_MASK_DAMAGE_AMP;
    private static final int SOLAR_PANEL_BIT = TurretVisualRules.ADDON_MASK_SOLAR_PANEL;
    private static final int REDSTONE_REACTOR_BIT = TurretVisualRules.ADDON_MASK_REDSTONE_REACTOR;
    private static final int ALL_ADDONS = TurretVisualRules.ADDON_MASK_ALL;
    private static final Map<TurretDefinition, ResourceLocation> TEXTURES =
            createTextures();
    private static final ResourceLocation DAMAGE_AMP_TEXTURE =
            addonTexture("addon_damage_amp");
    private static final ResourceLocation SOLAR_PANEL_TEXTURE =
            addonTexture("addon_solar_panel");
    private static final ResourceLocation REDSTONE_REACTOR_TEXTURE =
            addonTexture("addon_redstone_reactor");

    private final Map<TurretDefinition, TurretHeadModel> models =
            new EnumMap<>(TurretDefinition.class);
    private final TurretAddonOverlayModel damageAmp;
    private final TurretAddonOverlayModel solarPanel;
    private final TurretAddonOverlayModel redstoneReactor;

    public TurretHeadBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
        for (TurretDefinition definition : TurretDefinition.values()) {
            models.put(definition, new TurretHeadModel(
                    context.bakeLayer(TurretHeadModelLayers.layer(definition))));
        }
        damageAmp = new TurretAddonOverlayModel(
                context.bakeLayer(TurretAddonOverlayModel.DAMAGE_AMP_LAYER));
        solarPanel = new TurretAddonOverlayModel(
                context.bakeLayer(TurretAddonOverlayModel.SOLAR_PANEL_LAYER));
        redstoneReactor = new TurretAddonOverlayModel(
                context.bakeLayer(TurretAddonOverlayModel.REDSTONE_REACTOR_LAYER));
    }

    @Override
    public void render(TurretHeadBlockEntity turret, float partialTick,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            int packedOverlay) {
        if (turret.concealed()
                || !(turret.getBlockState().getBlock() instanceof TurretHeadBlock block)) {
            return;
        }

        TurretDefinition definition = block.definition();
        TurretHeadModel model = models.get(definition);
        Direction mount = findMount(turret);
        float yawRadians = (float) Math.toRadians(turret.aimYaw());
        float pitchRadians = (float) Math.toRadians(turret.aimPitch());
        int modelLight = surroundingLight(turret, packedLight);

        poseStack.pushPose();
        // Exact legacy TESR origin. Six-face fitting belongs only to the model's
        // support pieces; rotating the complete rig makes wall turrets lie down.
        poseStack.translate(0.5D, 1.5D, 0.5D);
        poseStack.scale(1.0F, -1.0F, -1.0F);

        model.setMount(mount);
        model.setAim(yawRadians, pitchRadians);
        float animation = ((turret.getLevel() == null ? 0L
                : turret.getLevel().getGameTime()) + partialTick) * 0.03F;
        // Presentation-layer selection keyed on the documented presentation
        // taxonomy; ShotKind.RELATIVISTIC/TELEPORT map one-to-one onto the two
        // special turret definitions.
        switch (definition.shotKind()) {
            case RELATIVISTIC -> model.setRelativisticAnimation(animation);
            case TELEPORT -> model.setTeleporterAnimation(animation);
            default -> { }
        }
        VertexConsumer consumer = bufferSource.getBuffer(
                RenderType.entityCutoutNoCull(TEXTURES.get(definition)));
        model.render(poseStack, consumer, modelLight, packedOverlay);
        renderAddons(turret, mount, definition, yawRadians, pitchRadians,
                poseStack, bufferSource, modelLight, packedOverlay);
        poseStack.popPose();
    }

    private void renderAddons(TurretHeadBlockEntity turret, Direction mount,
            TurretDefinition definition, float yawRadians, float pitchRadians,
            PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (turret.getLevel() == null
                || !(turret.getLevel().getBlockEntity(
                        turret.getBlockPos().relative(mount))
                        instanceof TurretBaseBlockEntity base)) {
            return;
        }
        int allowed = switch (definition) {
            case RELATIVISTIC -> SOLAR_PANEL_BIT;
            case TELEPORTER -> 0;
            default -> ALL_ADDONS;
        };
        int mask = base.addonRenderMask() & allowed;
        boolean directed = definition != TurretDefinition.RELATIVISTIC
                && definition != TurretDefinition.TELEPORTER;
        if ((mask & DAMAGE_AMP_BIT) != 0) {
            setAddonAim(damageAmp, directed, yawRadians, pitchRadians);
            renderAddon(damageAmp, DAMAGE_AMP_TEXTURE, poseStack, bufferSource,
                    packedLight, packedOverlay);
        }
        if ((mask & SOLAR_PANEL_BIT) != 0) {
            setAddonAim(solarPanel, directed, yawRadians, pitchRadians);
            renderAddon(solarPanel, SOLAR_PANEL_TEXTURE, poseStack, bufferSource,
                    packedLight, packedOverlay);
        }
        if ((mask & REDSTONE_REACTOR_BIT) != 0) {
            setAddonAim(redstoneReactor, directed, yawRadians, pitchRadians);
            renderAddon(redstoneReactor, REDSTONE_REACTOR_TEXTURE, poseStack,
                    bufferSource, packedLight, packedOverlay);
        }
    }

    private static void setAddonAim(TurretAddonOverlayModel model,
            boolean directed, float yawRadians, float pitchRadians) {
        if (directed) {
            model.setAim(yawRadians, pitchRadians);
        } else {
            model.resetAim();
        }
    }

    private static void renderAddon(TurretAddonOverlayModel model,
            ResourceLocation texture, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        VertexConsumer consumer =
                bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
        model.render(poseStack, consumer, packedLight, packedOverlay);
    }

    private static Direction findMount(TurretHeadBlockEntity turret) {
        return turret.baseDirectionForRender();
    }

    private static int surroundingLight(TurretHeadBlockEntity turret, int packedLight) {
        if (turret.getLevel() == null) {
            return packedLight;
        }
        int light = packedLight;
        for (Direction direction : Direction.values()) {
            light = TurretVisualRules.mergePackedLight(light,
                    LevelRenderer.getLightColor(turret.getLevel(),
                            turret.getBlockPos().relative(direction)));
        }
        return light;
    }


    private static Map<TurretDefinition, ResourceLocation> createTextures() {
        Map<TurretDefinition, ResourceLocation> textures =
                new EnumMap<>(TurretDefinition.class);
        textures.put(TurretDefinition.DISPOSABLE, texture("dispose_item_turret"));
        textures.put(TurretDefinition.POTATO, texture("potato_cannon_turret"));
        textures.put(TurretDefinition.MACHINE_GUN, texture("machine_gun_turret"));
        textures.put(TurretDefinition.INCENDIARY, texture("incendiary_turret"));
        textures.put(TurretDefinition.GRENADE, texture("grenade_turret"));
        textures.put(TurretDefinition.RELATIVISTIC, texture("relativistic_turret"));
        textures.put(TurretDefinition.ROCKET, texture("rocket_turret"));
        textures.put(TurretDefinition.TELEPORTER, texture("teleporter_turret"));
        textures.put(TurretDefinition.LASER, texture("laser_turret"));
        textures.put(TurretDefinition.RAIL_GUN, texture("rail_gun_turret"));
        // The 1.12 plasma renderer intentionally reused the grenade model and texture.
        textures.put(TurretDefinition.PLASMA, texture("grenade_turret"));
        return textures;
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID,
                "textures/block/" + name + ".png");
    }

    private static ResourceLocation addonTexture(String name) {
        return texture(name);
    }

}
