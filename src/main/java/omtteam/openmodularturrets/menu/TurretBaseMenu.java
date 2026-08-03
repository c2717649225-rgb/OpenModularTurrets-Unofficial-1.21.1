package omtteam.openmodularturrets.menu;

import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.data.BaseMode;
import omtteam.openmodularturrets.registration.ModMenus;
import omtteam.openmodularturrets.network.ClientTrustSnapshot;
import omtteam.openmodularturrets.network.TrustScope;
import omtteam.openmodularturrets.network.TrustSnapshotPayload;

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

public final class TurretBaseMenu extends AbstractContainerMenu {
    private static final int DATA_TIER = 0;
    private static final int DATA_MODE = 1;
    private static final int DATA_REDSTONE = 2;
    private static final int DATA_ACTIVE = 3;
    private static final int DATA_RANGE = 4;
    private static final int DATA_MAX_RANGE = 5;
    private static final int DATA_TARGET_FLAGS = 6;
    private static final int DATA_MULTI_TARGET = 7;
    private static final int DATA_ACCESS = 8;
    private static final int DATA_GLOBAL_TRUST = 9;
    private static final int DATA_ENERGY = 10;
    private static final int DATA_MAX_ENERGY = 12;
    private static final int DATA_KILLS = 14;
    private static final int DATA_PLAYER_KILLS = 18;
    private static final int DATA_SHOTS = 22;
    private static final int DATA_CAMOUFLAGED = 26;
    private static final int DATA_CAMOUFLAGE_LIGHT = 27;
    private static final int DATA_CAMOUFLAGE_OPACITY = 28;
    private static final int DATA_COUNT = 29;

    private final TurretBaseBlockEntity base;
    private final Player menuPlayer;
    private final int baseSlotCount;
    private final ContainerData data;
    private ClientTrustSnapshot.State trustState;

    public TurretBaseMenu(int containerId, Inventory playerInventory,
            RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory,
                requireBase(playerInventory.player, buffer.readBlockPos()),
                new SimpleContainerData(DATA_COUNT));
    }

    public TurretBaseMenu(int containerId, Inventory playerInventory,
            TurretBaseBlockEntity base) {
        this(containerId, playerInventory, base, serverData(base, playerInventory.player));
    }

    private TurretBaseMenu(int containerId, Inventory playerInventory,
            TurretBaseBlockEntity base, ContainerData data) {
        super(ModMenus.TURRET_BASE_MENU.value(), containerId);
        this.base = base;
        this.menuPlayer = playerInventory.player;
        this.data = data;
        checkContainerDataCount(data, DATA_COUNT);
        addDataSlots(data);

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int slot = column + row * 3;
                addSlot(new AuthorizedSlot(base.inventory(), slot,
                        8 + column * 18, 17 + row * 18));
            }
        }
        for (int slot = 0; slot < base.addonSlotCount(); slot++) {
            addSlot(new AuthorizedSlot(base.inventory(),
                    TurretBaseBlockEntity.ADDON_SLOT_START + slot,
                    72 + slot * 20, 18));
        }
        for (int slot = 0; slot < base.upgradeSlotCount(); slot++) {
            addSlot(new AuthorizedSlot(base.inventory(),
                    TurretBaseBlockEntity.UPGRADE_SLOT_START + slot,
                    72 + slot * 20, 52));
        }
        baseSlotCount = slots.size();
        addPlayerInventory(playerInventory);
    }

    public TurretBaseBlockEntity base() {
        return base;
    }

    public int baseSlotCount() {
        return baseSlotCount;
    }

    public int tier() {
        return data.get(DATA_TIER);
    }

    public BaseMode mode() {
        return BaseMode.byIdOrDefault(data.get(DATA_MODE));
    }

    public boolean redstonePowered() {
        return data.get(DATA_REDSTONE) != 0;
    }

    public boolean active() {
        return data.get(DATA_ACTIVE) != 0;
    }

    public int configuredRange() {
        return data.get(DATA_RANGE);
    }

    public int maximumRange() {
        return data.get(DATA_MAX_RANGE);
    }

    public boolean attackHostile() {
        return (targetFlags() & 1) != 0;
    }

    public boolean attackNeutral() {
        return (targetFlags() & 2) != 0;
    }

    public boolean attackPlayers() {
        return (targetFlags() & 4) != 0;
    }

    public int targetFlags() {
        return data.get(DATA_TARGET_FLAGS);
    }

    public boolean multiTargeting() {
        return data.get(DATA_MULTI_TARGET) != 0;
    }

    public AccessLevel accessLevel() {
        return AccessLevel.byId(data.get(DATA_ACCESS));
    }

    public boolean useGlobalTrust() {
        return data.get(DATA_GLOBAL_TRUST) != 0;
    }

    public void acceptTrustSnapshot(TrustSnapshotPayload payload) {
        if (payload.containerId() != containerId
                || !payload.pos().equals(base.getBlockPos())) {
            return;
        }
        ClientTrustSnapshot.Session session = new ClientTrustSnapshot.Session(
                containerId, base.getBlockPos(), payload.owner());
        if (trustState == null || !trustState.session().equals(session)) {
            trustState = ClientTrustSnapshot.begin(session);
        }
        trustState = ClientTrustSnapshot.reduce(trustState, session, payload);
    }

    public ClientTrustSnapshot.Snapshot trustSnapshot(TrustScope scope) {
        return trustState == null
                ? ClientTrustSnapshot.begin(new ClientTrustSnapshot.Session(
                        containerId, base.getBlockPos(), new java.util.UUID(0L, 0L)))
                        .snapshot(scope)
                : trustState.snapshot(scope);
    }

    public int energy() {
        return readInt(data, DATA_ENERGY);
    }

    public int maximumEnergy() {
        return readInt(data, DATA_MAX_ENERGY);
    }

    public long kills() {
        return readLong(data, DATA_KILLS);
    }

    public long playerKills() {
        return readLong(data, DATA_PLAYER_KILLS);
    }

    public long shotsFired() {
        return readLong(data, DATA_SHOTS);
    }

    public boolean camouflaged() {
        return data.get(DATA_CAMOUFLAGED) != 0;
    }

    public int camouflageLightValue() {
        return data.get(DATA_CAMOUFLAGE_LIGHT);
    }

    public int camouflageLightOpacity() {
        return data.get(DATA_CAMOUFLAGE_OPACITY);
    }

    @Override
    public boolean stillValid(Player player) {
        return !base.isRemoved()
                && base.getLevel() != null
                && player.level() == base.getLevel()
                && base.getLevel().getBlockEntity(base.getBlockPos()) == base
                && player.distanceToSqr(base.getBlockPos().getCenter()) <= 64.0D
                // Global trust lives in server SavedData and is intentionally not
                // mirrored into the client BlockEntity. Use the synchronized access
                // level client-side so a trusted remote player does not have their
                // screen closed by a false local NONE result.
                && (player.level().isClientSide
                        ? accessLevel().allows(AccessLevel.VIEW)
                        : base.accessFor(player).allows(AccessLevel.VIEW));
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
        if (index < baseSlotCount) {
            if (!moveItemStackTo(stack, baseSlotCount, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, baseSlotCount, false)) {
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
                || base.accessFor(player).allows(AccessLevel.USE);
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

    private static TurretBaseBlockEntity requireBase(Player player, BlockPos pos) {
        if (player.level().getBlockEntity(pos) instanceof TurretBaseBlockEntity base) {
            return base;
        }
        throw new IllegalStateException("Missing turret base for menu at " + pos);
    }

    private static ContainerData serverData(TurretBaseBlockEntity base, Player player) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_TIER -> base.tier().level();
                    case DATA_MODE -> base.mode().id();
                    case DATA_REDSTONE -> base.redstonePowered() ? 1 : 0;
                    case DATA_ACTIVE -> base.active() ? 1 : 0;
                    case DATA_RANGE -> base.range();
                    case DATA_MAX_RANGE -> base.maximumRange();
                    case DATA_TARGET_FLAGS -> (base.attackHostile() ? 1 : 0)
                            | (base.attackNeutral() ? 2 : 0)
                            | (base.attackPlayers() ? 4 : 0);
                    case DATA_MULTI_TARGET -> base.multiTargeting() ? 1 : 0;
                    case DATA_ACCESS -> base.accessFor(player).id();
                    case DATA_GLOBAL_TRUST -> base.useGlobalTrust() ? 1 : 0;
                    case DATA_ENERGY, DATA_ENERGY + 1 ->
                            word(base.energy().getEnergyStored(), index - DATA_ENERGY);
                    case DATA_MAX_ENERGY, DATA_MAX_ENERGY + 1 ->
                            word(base.energy().getMaxEnergyStored(), index - DATA_MAX_ENERGY);
                    case DATA_KILLS, DATA_KILLS + 1, DATA_KILLS + 2, DATA_KILLS + 3 ->
                            word(base.kills(), index - DATA_KILLS);
                    case DATA_PLAYER_KILLS, DATA_PLAYER_KILLS + 1,
                            DATA_PLAYER_KILLS + 2, DATA_PLAYER_KILLS + 3 ->
                            word(base.playerKills(), index - DATA_PLAYER_KILLS);
                    case DATA_SHOTS, DATA_SHOTS + 1, DATA_SHOTS + 2, DATA_SHOTS + 3 ->
                            word(base.shotsFired(), index - DATA_SHOTS);
                    case DATA_CAMOUFLAGED -> base.camouflageState().isPresent() ? 1 : 0;
                    case DATA_CAMOUFLAGE_LIGHT -> base.camouflageLightValue();
                    case DATA_CAMOUFLAGE_OPACITY -> base.camouflageLightOpacity();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                // Server values are read-only; vanilla menu sync writes the client data object.
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    private static int word(int value, int wordIndex) {
        return (short) (value >>> (wordIndex * 16));
    }

    private static int word(long value, int wordIndex) {
        return (short) (value >>> (wordIndex * 16));
    }

    private static int readInt(ContainerData data, int start) {
        return (data.get(start) & 0xFFFF)
                | ((data.get(start + 1) & 0xFFFF) << 16);
    }

    private static long readLong(ContainerData data, int start) {
        long value = 0L;
        for (int word = 0; word < 4; word++) {
            value |= (long) (data.get(start + word) & 0xFFFF) << (word * 16);
        }
        return value;
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

        @Override
        public int getMaxStackSize(ItemStack stack) {
            // Mirrors the base inventory limits so menu drag/drop cannot bypass
            // the legacy 1.12 stack caps (upgrades to 4, addons to 1).
            int slot = getSlotIndex();
            if (slot >= TurretBaseBlockEntity.UPGRADE_SLOT_START
                    && slot < TurretBaseBlockEntity.INVENTORY_SIZE) {
                return 4;
            }
            if (slot >= TurretBaseBlockEntity.ADDON_SLOT_START
                    && slot < TurretBaseBlockEntity.UPGRADE_SLOT_START) {
                return 1;
            }
            return super.getMaxStackSize(stack);
        }
    }
}
