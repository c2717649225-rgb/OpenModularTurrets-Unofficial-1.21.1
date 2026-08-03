package omtteam.openmodularturrets.client;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.client.render.BeamRenderCache;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

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
    public static void renderBeams(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            BeamRenderCache.render(event.getPoseStack());
        }
    }
}
