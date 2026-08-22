package omtteam.openmodularturrets.client;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.client.render.BeamRenderCache;
import omtteam.openmodularturrets.entity.TurretProjectileEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(
        modid = OpenModularTurrets.MOD_ID,
        value = Dist.CLIENT)
public final class ClientGameEvents {
    private ClientGameEvents() {
    }

    @SubscribeEvent
    public static void afterClientTick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level != null && !Minecraft.getInstance().isPaused()) {
            ClientProjectileEffects.tickClientLevel(level);
        }
    }

    @SubscribeEvent
    public static void projectileJoined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ClientLevel level
                && event.getEntity() instanceof TurretProjectileEntity projectile) {
            ClientProjectileEffects.track(level, projectile);
        }
    }

    @SubscribeEvent
    public static void projectileLeft(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof TurretProjectileEntity projectile) {
            ClientProjectileEffects.untrack(projectile);
        }
    }

    @SubscribeEvent
    public static void clientLevelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            ClientProjectileEffects.clear();
            BeamRenderCache.clear();
        }
    }

    @SubscribeEvent
    public static void clientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientProjectileEffects.clear();
        BeamRenderCache.clear();
    }

    @SubscribeEvent
    public static void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void ignored, ResourceManager resourceManager,
                    ProfilerFiller profiler) {
                ClientProjectileEffects.clear();
                BeamRenderCache.clear();
            }
        });
    }

    @SubscribeEvent
    public static void renderBeams(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            BeamRenderCache.render(event.getPoseStack());
        }
    }
}
