package omtteam.openmodularturrets.client.render;

import java.util.EnumMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.data.TurretDefinition;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.core.Direction;
/**
 * Renders turret-head block items with the same model used by the placed head.
 * The old 1.12 port used a TESR for these items; a NeoForge item extension is
 * the equivalent 1.21.1 hook.
 */
public final class TurretHeadItemRenderer extends BlockEntityWithoutLevelRenderer {
    private static final Map<TurretDefinition, ResourceLocation> TEXTURES =
            createTextures();

    private final EntityModelSet modelSet;
    private final Map<TurretDefinition, TurretHeadModel> models =
            new EnumMap<>(TurretDefinition.class);

    public TurretHeadItemRenderer(BlockEntityRenderDispatcher dispatcher,
            EntityModelSet modelSet) {
        super(dispatcher, modelSet);
        this.modelSet = modelSet;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        super.onResourceManagerReload(resourceManager);
        models.clear();
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            int packedOverlay) {
        if (!(stack.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof TurretHeadBlock block)) {
            return;
        }

        TurretDefinition definition = block.definition();
        TurretHeadModel model = models.computeIfAbsent(definition,
                key -> new TurretHeadModel(modelSet.bakeLayer(
                        TurretHeadModelLayers.layer(key))));
        if (model == null) {
            return;
        }

        poseStack.pushPose();
        // Preserve the legacy TESR item pose; the exact model is authored in the
        // same 16-pixel coordinate system as the old ModelRenderer classes.
        poseStack.translate(0.5D, 1.5D, 0.5D);
        poseStack.scale(0.7F, -0.7F, -0.7F);
        poseStack.translate(0.0D, 0.4D, 0.5D);
        Vector3f axis = new Vector3f(2.5F, -4.5F, -1.0F).normalize();
        poseStack.mulPose(new Quaternionf().rotationAxis(
                (float) Math.toRadians(45.0D), axis));
        model.setMount(Direction.DOWN);
        model.setAim(0.0F, 0.0F);
        VertexConsumer consumer = bufferSource.getBuffer(
                RenderType.entityCutoutNoCull(TEXTURES.get(definition)));
        model.render(poseStack, consumer, packedLight, packedOverlay);
        poseStack.popPose();
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
        textures.put(TurretDefinition.PLASMA, texture("grenade_turret"));
        return textures;
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID,
                "textures/block/" + name + ".png");
    }
}
