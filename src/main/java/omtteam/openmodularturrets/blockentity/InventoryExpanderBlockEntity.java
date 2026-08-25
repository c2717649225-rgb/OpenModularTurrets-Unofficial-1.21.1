package omtteam.openmodularturrets.blockentity;

import omtteam.openmodularturrets.registration.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.MenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.items.ItemStackHandler;
import omtteam.openmodularturrets.menu.InventoryExpanderMenu;
import omtteam.openmodularturrets.block.InventoryExpanderBlock;
import omtteam.openmodularturrets.registration.ModTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
public final class InventoryExpanderBlockEntity extends BlockEntity implements MenuProvider {
    private final ItemStackHandler inventory = new ItemStackHandler(9) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.is(ModTags.Items.AMMUNITION)
                    || stack.is(Items.REDSTONE)
                    || stack.is(Blocks.REDSTONE_BLOCK.asItem());
        }

        @Override
        public int getSlotLimit(int slot) {
            int tier = getBlockState().getBlock() instanceof InventoryExpanderBlock block
                    ? block.tier() : 1;
            return 1 << (tier + 1);
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    public InventoryExpanderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INVENTORY_EXPANDER.value(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public Optional<TurretBaseBlockEntity> linkedBase() {
        if (level != null) {
            for (Direction direction : Direction.values()) {
                if (level.getBlockEntity(worldPosition.relative(direction))
                        instanceof TurretBaseBlockEntity base) {
                    return Optional.of(base);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.openmodularturrets.inventory_expander");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return linkedBase().filter(base -> base.accessFor(player)
                        .allows(omtteam.openmodularturrets.data.AccessLevel.VIEW))
                .map(base -> (AbstractContainerMenu) new InventoryExpanderMenu(
                        containerId, inventory, this))
                .orElse(null);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("data_version", 1);
        tag.put("inventory", inventory.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("inventory", Tag.TAG_COMPOUND)) {
            inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        }
    }
}
