package omtteam.openmodularturrets.blockentity.base;

import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.registration.ModItems;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * High-cohesion sub-component managing addon energy generation, fuel cycles, and visual masks.
 */
public final class BaseAddonEngine {
    public BaseAddonEngine() {
    }

    public int runReactorCycle(AddonContext ctx) {
        if (!ctx.hasAddon(ModItems.ADDON_REDSTONE_REACTOR.value())) {
            return 0;
        }
        int freeCapacity = ctx.maxEnergyCapacity() - ctx.storedEnergy();
        TurretAddonRules.ReactorFuel fuel = TurretAddonRules.selectReactorFuel(
                freeCapacity,
                ctx.containsItem(Blocks.REDSTONE_BLOCK.asItem()),
                ctx.containsItem(Items.REDSTONE));

        Item fuelItem = switch (fuel) {
            case BLOCK -> Blocks.REDSTONE_BLOCK.asItem();
            case DUST -> Items.REDSTONE;
            case NONE -> null;
        };
        if (fuelItem != null && ctx.extractItem(fuelItem)) {
            return ctx.generateEnergy(fuel.generation());
        }
        return 0;
    }

    public int computeRenderMask(AddonContext ctx) {        boolean damageAmp = ctx.hasAddon(ModItems.ADDON_DAMAGE_AMP.value());
        boolean solar = ctx.hasAddon(ModItems.ADDON_SOLAR_PANEL.value());
        boolean reactor = ctx.hasAddon(ModItems.ADDON_REDSTONE_REACTOR.value());
        return TurretVisualRules.addonMask(damageAmp, solar, reactor);
    }
}
