package omtteam.openmodularturrets.client;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.network.ModNetwork;
import omtteam.openmodularturrets.registration.ModMenus;
import omtteam.openmodularturrets.registration.ModEntities;
import omtteam.openmodularturrets.registration.ModBlockEntities;
import omtteam.openmodularturrets.client.render.TurretHeadBlockEntityRenderer;
import omtteam.openmodularturrets.client.render.TurretHeadItemRenderer;
import omtteam.openmodularturrets.client.render.TurretBaseBlockEntityRenderer;
import omtteam.openmodularturrets.client.render.TurretHeadModelLayers;
import omtteam.openmodularturrets.client.render.ManualChargerModel;
import omtteam.openmodularturrets.client.render.ManualChargerItemRenderer;
import omtteam.openmodularturrets.client.render.ManualChargerBlockEntityRenderer;
import omtteam.openmodularturrets.client.render.BeamRenderCache;
import omtteam.openmodularturrets.registration.ModItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@EventBusSubscriber(modid = OpenModularTurrets.MOD_ID, value = Dist.CLIENT)
public final class ModClientEvents {
    static {
        ModNetwork.installBeamEffectHandler(payload -> BeamRenderCache.add(
                payload.start(), payload.end(), payload.color(), payload.alpha(),
                payload.durationTicks()));
    }

    private ModClientEvents() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.TURRET_BASE_MENU.value(), TurretBaseScreen::new);
        event.register(ModMenus.INVENTORY_EXPANDER_MENU.value(), InventoryExpanderScreen::new);
    }

    @SubscribeEvent
    public static void registerClientItemExtensions(RegisterClientExtensionsEvent event) {
        TurretHeadItemRenderer renderer = new TurretHeadItemRenderer(
                Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
        IClientItemExtensions extensions = new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return renderer;
            }
        };
        event.registerItem(extensions,
                ModItems.DISPOSABLE_ITEM_TURRET.value(),
                ModItems.POTATO_CANNON_TURRET.value(),
                ModItems.MACHINE_GUN_TURRET.value(),
                ModItems.INCENDIARY_TURRET.value(),
                ModItems.GRENADE_TURRET.value(),
                ModItems.RELATIVISTIC_TURRET.value(),
                ModItems.ROCKET_TURRET.value(),
                ModItems.TELEPORTER_TURRET.value(),
                ModItems.LASER_TURRET.value(),
                ModItems.RAIL_GUN_TURRET.value(),
                ModItems.PLASMA_TURRET.value());

        ManualChargerItemRenderer chargerRenderer = new ManualChargerItemRenderer(
                Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
        event.registerItem(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return chargerRenderer;
            }
        }, ModItems.LEVER_BLOCK.value());
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.TURRET_HEAD.value(),
                TurretHeadBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.TURRET_BASE.value(),
                TurretBaseBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MANUAL_CHARGER.value(),
                ManualChargerBlockEntityRenderer::new);
        event.registerEntityRenderer(ModEntities.DISPOSABLE_ITEM_PROJECTILE.value(),
                ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.POTATO_PROJECTILE.value(),
                ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.BULLET_PROJECTILE.value(),
                ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.BLAZING_CLAY_PROJECTILE.value(),
                ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.GRENADE_PROJECTILE.value(),
                ThrownItemRenderer::new);
        event.registerEntityRenderer(ModEntities.ROCKET_PROJECTILE.value(),
                RocketProjectileRenderer::new);
        event.registerEntityRenderer(ModEntities.PLASMA_PROJECTILE.value(),
                ThrownItemRenderer::new);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(
            EntityRenderersEvent.RegisterLayerDefinitions event) {
        TurretHeadModelLayers.registerLayerDefinitions(event);
        event.registerLayerDefinition(ManualChargerModel.LAYER,
                ManualChargerModel::createLayer);
    }
}
