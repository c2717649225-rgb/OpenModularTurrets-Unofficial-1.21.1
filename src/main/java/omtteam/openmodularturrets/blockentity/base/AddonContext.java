package omtteam.openmodularturrets.blockentity.base;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Narrow action context interface for addon energy cycles and operations.
 * Implemented directly by the host TurretBaseBlockEntity for zero heap allocations.
 */
public interface AddonContext {
    boolean hasAddon(Item addon);

    boolean containsItem(Item item);

    boolean extractItem(Item item);

    int generateEnergy(int amount);

    int maxEnergyCapacity();

    int storedEnergy();

    boolean canSeeSky(BlockPos pos);

    BlockPos worldPosition();
}
