package omtteam.openmodularturrets.blockentity.base;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

import omtteam.openmodularturrets.block.PowerExpanderBlock;
import omtteam.openmodularturrets.blockentity.InventoryExpanderBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * High-cohesion sub-component managing 6-direction attachment expander topologies and caches.
 * Ensures zero-state mutations and level==null safety during deserialization.
 */
public final class BaseExpanderTopology {
    private final List<BlockPos> cachedAmmoExpanderPositions = new ArrayList<>(6);
    private final List<IItemHandler> cachedAmmoInventories = new ArrayList<>(7);
    @Nullable
    private Level cachedAmmoLevel;
    private boolean ammoTopologyCached;

    @Nullable
    private Level cachedCapacityLevel;
    private long cachedCapacityGameTime = Long.MIN_VALUE;
    private int cachedMaxEnergyCapacity;

    public BaseExpanderTopology() {
    }

    public void invalidateCaches() {
        cachedAmmoExpanderPositions.clear();
        cachedAmmoInventories.clear();
        cachedAmmoLevel = null;
        ammoTopologyCached = false;
        cachedCapacityLevel = null;
        cachedCapacityGameTime = Long.MIN_VALUE;
        cachedMaxEnergyCapacity = 0;
    }

    public int calculateMaxEnergyCapacity(long baseTierCapacity, @Nullable Level level, BlockPos basePos) {
        if (level == null) {
            return (int) Math.min(Integer.MAX_VALUE, baseTierCapacity);
        }
        long gameTime = level.getGameTime();
        if (cachedCapacityLevel == level && cachedCapacityGameTime == gameTime) {
            return cachedMaxEnergyCapacity;
        }
        long capacity = baseTierCapacity;
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(basePos.relative(direction)).getBlock()
                    instanceof PowerExpanderBlock expander) {
                capacity += expander.extraCapacity();
            }
        }
        int result = (int) Math.min(Integer.MAX_VALUE, capacity);
        cachedCapacityLevel = level;
        cachedCapacityGameTime = gameTime;
        cachedMaxEnergyCapacity = result;
        return result;
    }

    /**
     * Aggregates the base's own automation inventory with every adjacent
     * inventory expander.  Contract: the returned read-only view wraps an
     * internal buffer that is rebuilt on each call — consume it within the
     * current tick and never retain it across calls.
     */
    public List<IItemHandler> aggregateAmmoInventories(@Nullable Level level, BlockPos basePos,
                                                       IItemHandler automationInventory) {
        if (cachedAmmoLevel != level) {
            invalidateCaches();
            cachedAmmoLevel = level;
        }
        if (!ammoTopologyCached) {
            cachedAmmoExpanderPositions.clear();
            if (level != null) {
                for (Direction direction : Direction.values()) {
                    BlockPos relative = basePos.relative(direction);
                    if (level.getBlockEntity(relative) instanceof InventoryExpanderBlockEntity) {
                        cachedAmmoExpanderPositions.add(relative.immutable());
                    }
                }
            }
            ammoTopologyCached = true;
        }
        cachedAmmoInventories.clear();
        cachedAmmoInventories.add(automationInventory);
        if (level != null) {
            for (BlockPos expanderPos : cachedAmmoExpanderPositions) {
                if (level.getBlockEntity(expanderPos) instanceof InventoryExpanderBlockEntity expander) {
                    cachedAmmoInventories.add(expander.inventory());
                }
            }
        }
        return Collections.unmodifiableList(cachedAmmoInventories);
    }
}
