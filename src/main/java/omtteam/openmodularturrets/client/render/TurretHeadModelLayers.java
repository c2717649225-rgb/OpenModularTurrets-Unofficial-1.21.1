package omtteam.openmodularturrets.client.render;

import java.util.EnumMap;
import java.util.Map;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.data.TurretDefinition;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client-only model layer locations for the eleven turret head variants.
 */
public final class TurretHeadModelLayers {
    private static final Map<TurretDefinition, ModelLayerLocation> LAYERS =
            new EnumMap<>(TurretDefinition.class);

    static {
        for (TurretDefinition definition : TurretDefinition.values()) {
            LAYERS.put(definition, new ModelLayerLocation(
                    ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID,
                            "turret/" + definition.id()),
                    "main"));
        }
    }

    private TurretHeadModelLayers() {
    }

    public static ModelLayerLocation layer(TurretDefinition definition) {
        return LAYERS.get(definition);
    }

    /**
     * Called from the client mod-event subscriber's RegisterLayerDefinitions handler.
     */
    public static void registerLayerDefinitions(
            EntityRenderersEvent.RegisterLayerDefinitions event) {
        for (TurretDefinition definition : TurretDefinition.values()) {
            event.registerLayerDefinition(layer(definition),
                    () -> TurretHeadModel.createBodyLayer(definition));
        }
        TurretAddonOverlayModel.registerLayerDefinitions(event);
    }
}
