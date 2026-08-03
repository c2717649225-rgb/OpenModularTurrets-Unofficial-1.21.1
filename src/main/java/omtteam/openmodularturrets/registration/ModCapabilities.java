package omtteam.openmodularturrets.registration;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class ModCapabilities {
    private ModCapabilities() {
    }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.TURRET_BASE.value(),
                (base, side) -> base.automationInventory());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.TURRET_BASE.value(),
                (base, side) -> base.energy());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.INVENTORY_EXPANDER.value(),
                (expander, side) -> expander.inventory());
    }
}
