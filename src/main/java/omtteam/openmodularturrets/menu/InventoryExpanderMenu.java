package omtteam.openmodularturrets.menu;

import omtteam.openmodularturrets.blockentity.InventoryExpanderBlockEntity;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.registration.ModMenus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;

public final class InventoryExpanderMenu extends AbstractContainerMenu {
    private static final int CONTAINER_SLOTS = 9;
    private static final int DATA_ACCESS = 0;
    private static final int DATA_COUNT = 1;
    private final InventoryExpanderBlockEntity expander;
    private final Player menuPlayer;
    private final ContainerData data;

    public InventoryExpanderMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory,
                requireExpander(playerInventory.player, buffer.readBlockPos()),
                new SimpleContainerData(DATA_COUNT));
    }

    public InventoryExpanderMenu(int containerId, Inventory playerInventory,
            InventoryExpanderBlockEntity expander) {
        this(containerId, playerInventory, expander,
                serverData(expander, playerInventory.player));
    }

    private InventoryExpanderMenu(int containerId, Inventory playerInventory,
            InventoryExpanderBlockEntity expander, ContainerData data) {
        super(ModMenus.INVENTORY_EXPANDER_MENU.value(), containerId);
        this.expander = expander;
        this.menuPlayer = playerInventory.player;
        this.data = data;
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int slot = column + row * 3;
                addSlot(new AuthorizedSlot(expander.inventory(), slot,
                        62 + column * 18, 17 + row * 18));
            }
        }
        addPlayerInventory(playerInventory);
    }

    public AccessLevel accessLevel() {
        return AccessLevel.byId(data.get(DATA_ACCESS));
    }

    @Override
    public boolean stillValid(Player player) {
        return !expander.isRemoved()
                && expander.getLevel() != null
                && player.level() == expander.getLevel()
                && expander.getLevel().getBlockEntity(expander.getBlockPos()) == expander
                && player.distanceToSqr(expander.getBlockPos().getCenter()) <= 64.0D
                && (player.level().isClientSide
                        ? accessLevel().allows(AccessLevel.VIEW)
                        : currentAccess(player).allows(AccessLevel.VIEW));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || !mayModify(player)) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (index < CONTAINER_SLOTS) {
            if (!moveItemStackTo(stack, CONTAINER_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, CONTAINER_SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        slot.onTake(player, stack);
        return copy;
    }

    private boolean mayModify(Player player) {
        return player.level().isClientSide
                ? accessLevel().allows(AccessLevel.USE)
                : currentAccess(player).allows(AccessLevel.USE);
    }

    private AccessLevel currentAccess(Player player) {
        return expander.linkedBase()
                .map(base -> base.accessFor(player))
                .orElse(AccessLevel.NONE);
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 142));
        }
    }

    private static InventoryExpanderBlockEntity requireExpander(Player player, BlockPos pos) {
        if (player.level().getBlockEntity(pos) instanceof InventoryExpanderBlockEntity expander) {
            return expander;
        }
        throw new IllegalStateException("Missing inventory expander for menu at " + pos);
    }

    private static ContainerData serverData(InventoryExpanderBlockEntity expander, Player player) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                if (index != DATA_ACCESS) {
                    return 0;
                }
                return expander.linkedBase()
                        .map(base -> base.accessFor(player).id())
                        .orElse(AccessLevel.NONE.id());
            }

            @Override
            public void set(int index, int value) {
                // Server values are read-only; vanilla menu sync writes client data.
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    private final class AuthorizedSlot extends SlotItemHandler {
        private AuthorizedSlot(net.neoforged.neoforge.items.IItemHandler itemHandler,
                int index, int xPosition, int yPosition) {
            super(itemHandler, index, xPosition, yPosition);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return mayModify(menuPlayer) && super.mayPlace(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return mayModify(player) && super.mayPickup(player);
        }
    }
}
