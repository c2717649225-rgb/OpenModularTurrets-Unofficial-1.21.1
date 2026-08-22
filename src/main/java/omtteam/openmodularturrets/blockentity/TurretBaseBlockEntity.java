package omtteam.openmodularturrets.blockentity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.block.TurretBaseBlock;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.data.BaseMode;
import omtteam.openmodularturrets.data.BaseTier;
import omtteam.openmodularturrets.data.MemoryCardProfile;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.data.TurretUpgradeRules;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.data.TargetingRules;
import omtteam.openmodularturrets.data.OwnershipRules;
import omtteam.openmodularturrets.data.TurretCombatContext;
import omtteam.openmodularturrets.data.TurretTargetingWorldQueries;
import omtteam.openmodularturrets.data.TurretVolleyResourcesView;
import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.damage.TurretAttackContext;
import omtteam.openmodularturrets.network.ModNetwork;
import omtteam.openmodularturrets.registration.ModBlockEntities;
import omtteam.openmodularturrets.registration.ModBlocks;
import omtteam.openmodularturrets.registration.ModTags;
import omtteam.openmodularturrets.registration.ModItems;
import omtteam.openmodularturrets.registration.ModSounds;
import omtteam.openmodularturrets.block.PowerExpanderBlock;
import omtteam.openmodularturrets.security.SecuritySavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import omtteam.openmodularturrets.menu.TurretBaseMenu;

public final class TurretBaseBlockEntity extends BlockEntity
        implements net.minecraft.world.MenuProvider,
        TurretTargetingWorldQueries, TurretCombatContext {
    public static final int AMMO_SLOT_COUNT = 9;
    public static final int ADDON_SLOT_START = 9;
    public static final int UPGRADE_SLOT_START = 11;
    public static final int INVENTORY_SIZE = 13;

    private static final int DATA_VERSION = 5;
    private static final int MAX_LOCAL_TRUST = 128;
    private static final int TARGET_SCAN_INTERVAL = 10;
    private static final int WARNING_SCAN_INTERVAL = 20;
    private static final long WARNING_COOLDOWN = 12_000L;
    private static final int MAX_WARNING_COOLDOWNS = 128;

    private final ItemStackHandler inventory = new ItemStackHandler(INVENTORY_SIZE) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (!isSlotEnabled(slot)) {
                return false;
            }
            if (slot < AMMO_SLOT_COUNT) {
                return stack.is(ModTags.Items.AMMUNITION) || isReactorFuel(stack);
            }
            if (slot < UPGRADE_SLOT_START) {
                return stack.is(ModTags.Items.ADDONS);
            }
            return stack.is(ModTags.Items.UPGRADES);
        }

        @Override
        public int getSlotLimit(int slot) {
            // Legacy 1.12 limits: upgrades stack to 4 levels, addons to 1 per
            // slot (the 1.12 damage-amp/solar exception is not ported because
            // this base exposes only two addon slots anyway).
            if (slot >= UPGRADE_SLOT_START && slot < INVENTORY_SIZE) {
                return 4;
            }
            if (slot >= ADDON_SLOT_START && slot < UPGRADE_SLOT_START) {
                return 1;
            }
            return super.getSlotLimit(slot);
        }

        @Override
        protected void onContentsChanged(int slot) {
            if (slot >= UPGRADE_SLOT_START && slot < INVENTORY_SIZE) {
                invalidateRangeCache();
            }
            markForSave();
            // Addon slots change the rendered turret-head overlay, but
            // persistence alone does not push the BlockEntity update packet,
            // so the client's addonRenderMask needs an explicit tracking sync.
            if (slot >= ADDON_SLOT_START && slot < UPGRADE_SLOT_START
                    && level instanceof ServerLevel) {
                ModNetwork.sendBlockEntityUpdateToTracking(TurretBaseBlockEntity.this);
            }
        }
    };
    private final IItemHandler automationInventory =
            new RangedWrapper(inventory, 0, AMMO_SLOT_COUNT);
    private final BaseEnergyStorage energy = new BaseEnergyStorage();
    private final Map<UUID, LocalTrustEntry> localTrust = new HashMap<>();
    private final Map<UUID, Long> warningCooldowns = new HashMap<>();
    private final List<BlockPos> cachedAmmoExpanderPositions = new java.util.ArrayList<>();
    private final List<IItemHandler> cachedAmmoInventories = new java.util.ArrayList<>(
            Direction.values().length + 1);
    /**
     * Range is queried from every attached head every tick. Keep this derived
     * maximum stable for the current level tick so candidate scans do not
     * repeatedly walk the same neighboring blocks and upgrade slots.
     * The level/time key preserves live config reload behavior; topology and
     * inventory changes invalidate it immediately as well.
     */
    @Nullable
    private net.minecraft.world.level.Level cachedRangeLevel;
    private long cachedRangeGameTime = Long.MIN_VALUE;
    private int cachedRangeUpgradeLevel = Integer.MIN_VALUE;
    private int cachedMaximumRange;
    @Nullable
    private net.minecraft.world.level.Level cachedAmmoLevel;
    private boolean ammoTopologyCached;
    @Nullable
    private net.minecraft.world.level.Level cachedCapacityLevel;
    private long cachedCapacityGameTime = Long.MIN_VALUE;
    private int cachedMaxEnergyCapacity;

    @Nullable
    private UUID owner;
    private String ownerName = "";
    private String ownerTeamName = "";
    private BaseMode mode = BaseMode.INVERTED;
    private boolean redstonePowered;
    private boolean useGlobalTrust;
    private long localTrustRevision;
    // Legacy 1.12 defaults: new bases attack hostile mobs only
    // (TargetingSettings(false, true, false) in player/hostile/neutral order).
    private boolean attackHostile = true;
    private boolean attackNeutral;
    private boolean attackPlayers;
    private boolean multiTargeting;
    private int configuredRange = 10;
    private long shotsFired;
    private long kills;
    private long playerKills;
    private int syncedAddonRenderMask;
    @Nullable
    private BlockState camouflageState;
    private int camouflageLightValue;
    private int camouflageLightOpacity = 15;

    public TurretBaseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TURRET_BASE.value(), pos, state);
    }

    /**
     * Release derived attachment views when the block entity leaves its
     * level.  NeoForge marks the entity removed and invalidates capabilities,
     * but it does not clear custom cache fields; clearing them here prevents a
     * cached item-handler view from retaining removed neighbours for longer
     * than the block entity itself.
     */
    @Override
    public void setRemoved() {
        invalidateNeighborCaches();
        super.setRemoved();
    }

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos,
            BlockState state, TurretBaseBlockEntity base) {
        base.refreshRedstoneSignal();
        if (base.hasAddon(ModItems.ADDON_SOLAR_PANEL.value())
                && level.isDay() && !level.isRaining()
                && level.canSeeSky(pos.above(2))
                && level.getBlockState(pos.above(3)).isAir()) {
            base.energy.generateEnergy(TurretAddonRules.solarGeneration());
        }
        if (level.getGameTime() % TurretAddonRules.REACTOR_INTERVAL == 0) {
            base.runReactorCycle();
        }
        if (level.getGameTime() % TARGET_SCAN_INTERVAL == 0 && base.energy.stored > base.energy.getMaxEnergyStored()) {
            base.energy.stored = base.energy.getMaxEnergyStored();
            base.markForSave();
        }
        if (Math.floorMod(level.getGameTime() + pos.asLong(), WARNING_SCAN_INTERVAL) == 0) {
            base.warnNearbyPlayers();
        }
    }

    public BaseTier tier() {
        return getBlockState().getBlock() instanceof TurretBaseBlock block ? block.tier() : BaseTier.ONE;
    }

    public void claim(UUID playerId) {
        claim(playerId, "");
    }

    public void claim(UUID playerId, String name) {
        if (owner == null) {
            owner = playerId;
            ownerName = sanitizeName(name);
            markForSave();
        }
    }

    public void claim(Player player) {
        if (owner != null) {
            return;
        }
        owner = player.getUUID();
        ownerName = sanitizeName(player.getGameProfile().getName());
        ownerTeamName = player.getTeam() == null ? "" : player.getTeam().getName();
        markForSave();
    }

    public Optional<UUID> owner() {
        return Optional.ofNullable(owner);
    }

    public boolean isOwner(Player player) {
        return owner != null && OwnershipRules.matches(owner, ownerName, player.getUUID(),
                player.getGameProfile().getName(), ModServerConfig.offlineModeSupport());
    }

    public String ownerName() {
        return ownerName;
    }

    public AccessLevel accessFor(Player player) {
        if (isOwner(player)) {
            return AccessLevel.ADMIN;
        }
        if (useGlobalTrust) {
            if (level instanceof ServerLevel serverLevel && owner != null) {
                AccessLevel access = SecuritySavedData.get(serverLevel).accessFor(owner,
                        player.getUUID(), player.getGameProfile().getName(),
                        ModServerConfig.offlineModeSupport());
                return access != AccessLevel.NONE ? access : opAccess(player);
            }
            return opAccess(player);
        }
        LocalTrustEntry direct = localTrust.get(player.getUUID());
        if (direct != null) {
            return direct.access();
        }
        if (ModServerConfig.offlineModeSupport()) {
            AccessLevel matched = localTrust.values().stream()
                    .filter(entry -> OwnershipRules.matches(entry.player(), entry.name(),
                            player.getUUID(), player.getGameProfile().getName(), true))
                    .map(LocalTrustEntry::access)
                    .max(java.util.Comparator.comparingInt(AccessLevel::id))
                    .orElse(AccessLevel.NONE);
            if (matched != AccessLevel.NONE) {
                return matched;
            }
        }
        return opAccess(player);
    }

    public boolean setLocalTrust(Player actor, UUID target, AccessLevel access) {
        return setLocalTrust(actor, target, target.toString(), access);
    }

    public boolean setLocalTrust(Player actor, UUID target, String name, AccessLevel access) {
        if (accessFor(actor) != AccessLevel.ADMIN
                || target.equals(owner)
                || (!localTrust.containsKey(target) && localTrust.size() >= MAX_LOCAL_TRUST)) {
            return false;
        }
        LocalTrustEntry next = new LocalTrustEntry(target, sanitizeName(name), access);
        if (next.equals(localTrust.get(target))) {
            return false;
        }
        localTrust.put(target, next);
        localTrustRevision++;
        markForSave();
        return true;
    }

    public boolean removeLocalTrust(Player actor, UUID target) {
        if (accessFor(actor) != AccessLevel.ADMIN || localTrust.remove(target) == null) {
            return false;
        }
        localTrustRevision++;
        markForSave();
        return true;
    }

    public Map<UUID, LocalTrustEntry> localTrustSnapshot() {
        return Map.copyOf(localTrust);
    }

    public boolean hasLocalTrust(UUID playerId) {
        return localTrust.containsKey(playerId);
    }

    public long localTrustRevision() {
        return localTrustRevision;
    }

    public boolean useGlobalTrust() {
        return useGlobalTrust;
    }

    public boolean setUseGlobalTrust(Player actor, boolean useGlobal) {
        if (accessFor(actor) != AccessLevel.ADMIN || useGlobalTrust == useGlobal) {
            return false;
        }
        useGlobalTrust = useGlobal;
        markForSave();
        return true;
    }

    public int dropAdjacentTurrets(Player actor) {
        if (!(level instanceof ServerLevel serverLevel)
                || accessFor(actor) != AccessLevel.ADMIN) {
            return 0;
        }
        int dropped = 0;
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = worldPosition.relative(direction);
            if (serverLevel.getBlockState(adjacent).getBlock() instanceof TurretHeadBlock
                    && serverLevel.destroyBlock(adjacent, true, actor)) {
                dropped++;
            }
        }
        return dropped;
    }

    public boolean dropBase(Player actor) {
        return level instanceof ServerLevel serverLevel
                && accessFor(actor) == AccessLevel.ADMIN
                && serverLevel.getBlockEntity(worldPosition) == this
                && serverLevel.destroyBlock(worldPosition, true, actor);
    }

    public Optional<BlockState> camouflageState() {
        return Optional.ofNullable(camouflageState);
    }

    public int camouflageLightValue() {
        return camouflageLightValue;
    }

    public int camouflageLightOpacity() {
        return camouflageLightOpacity;
    }

    public boolean setCamouflage(Player actor, BlockState state) {
        if (!ModServerConfig.allowBaseCamouflage()
                || !isOwner(actor) || !isValidCamouflage(state)) {
            return false;
        }
        if (state.equals(camouflageState)) {
            return false;
        }
        camouflageState = state;
        syncCamouflageState();
        return true;
    }

    public boolean clearCamouflage(Player actor) {
        if (!isOwner(actor) || camouflageState == null) {
            return false;
        }
        camouflageState = null;
        syncCamouflageState();
        return true;
    }

    public boolean setCamouflageLightValue(Player actor, int value) {
        if (!ModServerConfig.allowBaseCamouflage()
                || !isOwner(actor) || tier().level() < 4 || value < 0 || value > 15
                || camouflageLightValue == value) {
            return false;
        }
        camouflageLightValue = value;
        syncCamouflageState();
        return true;
    }

    public boolean setCamouflageLightOpacity(Player actor, int value) {
        if (!ModServerConfig.allowBaseCamouflage()
                || !isOwner(actor) || tier().level() < 4 || value < 0 || value > 15
                || camouflageLightOpacity == value) {
            return false;
        }
        camouflageLightOpacity = value;
        markForSaveAndSync();
        refreshLighting();
        return true;
    }

    private boolean isValidCamouflage(BlockState state) {
        return level != null
                && !state.isAir()
                && state.getRenderShape() == RenderShape.MODEL
                && !state.hasBlockEntity()
                && !(state.getBlock() instanceof TurretBaseBlock)
                && Block.isShapeFullBlock(state.getCollisionShape(level, worldPosition));
    }

    private void syncCamouflageState() {
        if (level == null) {
            return;
        }
        BlockState current = getBlockState();
        if (current.getBlock() instanceof TurretBaseBlock) {
            BlockState next = current
                    .setValue(TurretBaseBlock.CAMOUFLAGED, camouflageState != null)
                    .setValue(TurretBaseBlock.LIGHT_LEVEL, camouflageLightValue);
            if (next != current) {
                level.setBlock(worldPosition, next, 3);
            }
        }
        if (level instanceof ServerLevel) {
            ModNetwork.sendBlockEntityUpdateToTracking(this);
        }
        markForSave();
        refreshLighting();
    }

    private void refreshLighting() {
        if (level != null) {
            level.getChunkSource().getLightEngine().checkBlock(worldPosition);
        }
    }

    public boolean mayTarget(Player player) {
        if (owner == null) {
            return true;
        }
        if (OwnershipRules.opIsProtected(ModServerConfig.canOpAccessOwnedBlocks(),
                player instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(4))) {
            return false;
        }
        return TargetingRules.ownershipAllowsTarget(isOwner(player), isTrusted(player),
                ModServerConfig.damageTrustedPlayers());
    }

    public boolean mayDamage(LivingEntity entity) {
        if (entity instanceof Player player) {
            return ModServerConfig.globalTargetPlayers()
                    && attackPlayers && !player.isCreative() && !player.isSpectator()
                    && mayTarget(player)
                    && !isOwnerTeamMember(player)
                    && (!(level instanceof ServerLevel serverLevel)
                            || serverLevel.getServer().isPvpAllowed());
        }
        if (entity instanceof TamableAnimal tamable && tamable.getOwnerUUID() != null) {
            UUID tameOwner = tamable.getOwnerUUID();
            if (!TargetingRules.ownershipAllowsTarget(tameOwner.equals(owner),
                    isTrusted(tameOwner), ModServerConfig.damageTrustedPlayers())) {
                return false;
            }
        }
        if (entity instanceof AbstractHorse horse && horse.isTamed()) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel && owner != null) {
            Player ownerPlayer = serverLevel.getPlayerByUUID(owner);
            if (ownerPlayer != null && entity.isAlliedTo(ownerPlayer)) {
                return false;
            }
        }
        if (entity instanceof Enemy) {
            return ModServerConfig.globalTargetHostiles() && attackHostile;
        }
        return ModServerConfig.globalTargetNeutrals()
                && entity instanceof Mob && attackNeutral;
    }

    private boolean isOwnerTeamMember(Player player) {
        if (owner == null || player.getTeam() == null) {
            return false;
        }
        String currentOwnerTeam = ownerTeamName;
        if (level instanceof ServerLevel serverLevel) {
            Player ownerPlayer = serverLevel.getPlayerByUUID(owner);
            String onlineTeam = ownerPlayer == null || ownerPlayer.getTeam() == null
                    ? "" : ownerPlayer.getTeam().getName();
            if (ownerPlayer != null && !onlineTeam.equals(ownerTeamName)) {
                ownerTeamName = onlineTeam;
                currentOwnerTeam = onlineTeam;
                markForSave();
            }
        }
        return !currentOwnerTeam.isEmpty()
                && currentOwnerTeam.equals(player.getTeam().getName());
    }

    private boolean isTrusted(Player player) {
        if (isOwner(player)) {
            return true;
        }
        if (useGlobalTrust) {
            return level instanceof ServerLevel serverLevel
                    && owner != null
                    && SecuritySavedData.get(serverLevel).hasEntry(owner, player.getUUID(),
                            player.getGameProfile().getName(), ModServerConfig.offlineModeSupport());
        }
        return localTrust.values().stream().anyMatch(entry -> OwnershipRules.matches(
                entry.player(), entry.name(), player.getUUID(), player.getGameProfile().getName(),
                ModServerConfig.offlineModeSupport()));
    }

    private boolean isTrusted(UUID playerId) {
        if (owner != null && owner.equals(playerId)) {
            return true;
        }
        if (useGlobalTrust) {
            return level instanceof ServerLevel serverLevel
                    && owner != null
                    && SecuritySavedData.get(serverLevel).hasEntry(owner, playerId);
        }
        return localTrust.containsKey(playerId);
    }

    private static AccessLevel opAccess(Player player) {
        return OwnershipRules.opIsProtected(ModServerConfig.canOpAccessOwnedBlocks(),
                player instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(4))
                ? AccessLevel.VIEW : AccessLevel.NONE;
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public IItemHandler automationInventory() {
        return automationInventory;
    }

    public IEnergyStorage energy() {
        return energy;
    }

    public int addonSlotCount() {
        return tier().addonSlots();
    }

    public int upgradeSlotCount() {
        return tier().upgradeSlots();
    }

    public int enabledMenuSlotCount() {
        return AMMO_SLOT_COUNT + addonSlotCount() + upgradeSlotCount();
    }

    public boolean isSlotEnabled(int slot) {
        if (slot >= 0 && slot < AMMO_SLOT_COUNT) {
            return true;
        }
        if (slot >= ADDON_SLOT_START && slot < UPGRADE_SLOT_START) {
            return slot - ADDON_SLOT_START < addonSlotCount();
        }
        return slot >= UPGRADE_SLOT_START && slot < INVENTORY_SIZE
                && slot - UPGRADE_SLOT_START < upgradeSlotCount();
    }

    public int addonLevel(net.minecraft.world.item.Item item) {
        return countStacks(item, ADDON_SLOT_START, addonSlotCount());
    }

    public int upgradeLevel(net.minecraft.world.item.Item item) {
        return countStacks(item, UPGRADE_SLOT_START, upgradeSlotCount());
    }

    public int projectileCount() {
        return TurretUpgradeRules.projectileCount(
                upgradeLevel(ModItems.UPGRADE_SCATTER_SHOT.value()));
    }

    public int effectiveEnergyCost(TurretDefinition definition) {
        return TurretUpgradeRules.energyCost(definition,
                upgradeLevel(ModItems.UPGRADE_EFFICIENCY.value()),
                upgradeLevel(ModItems.UPGRADE_SCATTER_SHOT.value()));
    }

    public Optional<VolleyResources> consumeResourcesForVolley(TurretDefinition definition) {
        double recyclerRoll = level == null ? 1.0D : level.random.nextDouble();
        return consumeResourcesForVolley(definition, recyclerRoll);
    }

    public Optional<VolleyResources> consumeResourcesForVolley(TurretDefinition definition,
            double recyclerRoll) {
        int shotCount = projectileCount();
        int actualEnergyCost = effectiveEnergyCost(definition);
        net.minecraft.tags.TagKey<net.minecraft.world.item.Item> ammo = definition.ammoTag();
        if (energy.stored < actualEnergyCost
                || (ModServerConfig.requireAmmo() && ammo != null
                        && countAvailableAmmo(ammo) < shotCount)) {
            return Optional.empty();
        }

        energy.extractEnergy(actualEnergyCost, false);
        ItemStack consumedAmmo = ItemStack.EMPTY;
        boolean preserveAmmo = ammo != null
                && hasAddon(ModItems.ADDON_RECYCLER.value())
                && TurretAddonRules.recyclerPreservesAmmo(definition, recyclerRoll);
        if (ModServerConfig.requireAmmo() && ammo != null && !preserveAmmo) {
            consumedAmmo = extractAmmo(ammo, shotCount);
        } else if (ammo != null) {
            consumedAmmo = findRepresentativeAmmo(ammo);
        }
        shotsFired += shotCount;
        markForSave();
        return Optional.of(new VolleyResources(consumedAmmo, shotCount));
    }

    private ItemStack findRepresentativeAmmo(
            net.minecraft.tags.TagKey<net.minecraft.world.item.Item> ammo) {
        for (IItemHandler handler : ammoInventories()) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.is(ammo)) {
                    return stack.copyWithCount(1);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private int countAvailableAmmo(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> ammo) {
        long count = 0;
        for (IItemHandler handler : ammoInventories()) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.is(ammo)) {
                    count += stack.getCount();
                    if (count >= Integer.MAX_VALUE) {
                        return Integer.MAX_VALUE;
                    }
                }
            }
        }
        return (int) count;
    }

    private ItemStack extractAmmo(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> ammo,
            int requested) {
        ItemStack representative = ItemStack.EMPTY;
        int remaining = requested;
        for (IItemHandler handler : ammoInventories()) {
            for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
                if (!handler.getStackInSlot(slot).is(ammo)) {
                    continue;
                }
                ItemStack extracted = handler.extractItem(slot, remaining, false);
                if (representative.isEmpty() && !extracted.isEmpty()) {
                    representative = extracted.copyWithCount(1);
                }
                remaining -= extracted.getCount();
            }
        }
        return representative;
    }

    private java.util.List<IItemHandler> ammoInventories() {
        if (cachedAmmoLevel != level) {
            invalidateNeighborCaches();
            cachedAmmoLevel = level;
        }
        if (!ammoTopologyCached) {
            cachedAmmoExpanderPositions.clear();
            if (level != null) {
                for (Direction direction : Direction.values()) {
                    if (level.getBlockEntity(worldPosition.relative(direction))
                            instanceof InventoryExpanderBlockEntity) {
                        cachedAmmoExpanderPositions.add(
                                worldPosition.relative(direction).immutable());
                    }
                }
            }
            ammoTopologyCached = true;
        }
        cachedAmmoInventories.clear();
        cachedAmmoInventories.add(automationInventory);
        if (level != null) {
            for (BlockPos expanderPos : cachedAmmoExpanderPositions) {
                if (level.getBlockEntity(expanderPos)
                        instanceof InventoryExpanderBlockEntity expander) {
                    cachedAmmoInventories.add(expander.inventory());
                }
            }
        }
        return cachedAmmoInventories;
    }

    /**
     * Neighbor attachments are part of the cached automation view.  Block
     * updates invalidate it so removed expanders can never be retained by a
     * long-lived base.
     */
    public void invalidateNeighborCaches() {
        invalidateRangeCache();
        cachedAmmoExpanderPositions.clear();
        cachedAmmoInventories.clear();
        cachedAmmoLevel = null;
        ammoTopologyCached = false;
        cachedCapacityLevel = null;
        cachedCapacityGameTime = Long.MIN_VALUE;
    }

    private void invalidateRangeCache() {
        cachedRangeLevel = null;
        cachedRangeGameTime = Long.MIN_VALUE;
        cachedRangeUpgradeLevel = Integer.MIN_VALUE;
        cachedMaximumRange = 0;
    }

    public int runReactorCycle() {
        if (!hasAddon(ModItems.ADDON_REDSTONE_REACTOR.value())) {
            return 0;
        }
        int freeCapacity = energy.getMaxEnergyStored() - energy.getEnergyStored();
        TurretAddonRules.ReactorFuel fuel = TurretAddonRules.selectReactorFuel(
                freeCapacity, containsItem(Blocks.REDSTONE_BLOCK.asItem()),
                containsItem(Items.REDSTONE));
        net.minecraft.world.item.Item fuelItem = switch (fuel) {
            case BLOCK -> Blocks.REDSTONE_BLOCK.asItem();
            case DUST -> Items.REDSTONE;
            case NONE -> null;
        };
        if (fuelItem != null && extractItem(fuelItem)) {
            return energy.generateEnergy(fuel.generation());
        }
        return 0;
    }

    private boolean containsItem(net.minecraft.world.item.Item item) {
        for (IItemHandler handler : ammoInventories()) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (handler.getStackInSlot(slot).is(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean extractItem(net.minecraft.world.item.Item item) {
        for (IItemHandler handler : ammoInventories()) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (handler.getStackInSlot(slot).is(item)
                        && !handler.extractItem(slot, 1, false).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public MemoryCardProfile createProfile() {
        List<MemoryCardProfile.TrustEntry> trust = localTrustSnapshot().values().stream()
                .map(entry -> new MemoryCardProfile.TrustEntry(
                        entry.player(), entry.name(), entry.access()))
                .toList();
        return new MemoryCardProfile(MemoryCardProfile.CURRENT_SCHEMA, configuredRange, mode.id(),
                attackHostile, attackNeutral, attackPlayers, multiTargeting, trust, true);
    }

    public boolean applyProfile(Player actor, MemoryCardProfile profile) {
        if (!accessFor(actor).allows(AccessLevel.USE)
                || profile.schemaVersion() < 1
                || profile.schemaVersion() > MemoryCardProfile.CURRENT_SCHEMA) {
            return false;
        }
        configuredRange = Math.max(0, profile.range());
        mode = profile.mode();
        attackHostile = profile.attackHostile();
        attackNeutral = profile.attackNeutral();
        attackPlayers = profile.attackPlayers();
        multiTargeting = profile.multiTargeting();
        if (profile.carriesTrust()) {
            applyStoredTrust(profile.trustEntries());
        }
        markForSave();
        return true;
    }

    /**
     * Restores the trusted-player list captured by a schema 4 memory card.  The
     * legacy 1.12 base wrote and read {@code trustedPlayers} alongside its
     * targeting settings, so a card written from a base must reproduce that
     * list exactly - including an explicit empty list.
     */
    private void applyStoredTrust(List<MemoryCardProfile.TrustEntry> entries) {
        localTrust.clear();
        int count = 0;
        for (MemoryCardProfile.TrustEntry entry : entries) {
            // Bound the restored list like setLocalTrust does; a crafted or
            // corrupted card must not bypass MAX_LOCAL_TRUST (audit F-F2).
            if (count >= MAX_LOCAL_TRUST) {
                break;
            }
            localTrust.put(entry.player(),
                    new LocalTrustEntry(entry.player(), entry.name(), entry.access()));
            count++;
        }
    }

    public int range() {
        int maximum = maximumRange();
        return maximum <= 0 ? 0 : Math.clamp(configuredRange, 1, maximum);
    }

    public int configuredRange() {
        return configuredRange;
    }

    public int maximumRange() {
        if (level == null) {
            return maximumRangeExcluding(null);
        }
        long gameTime = level.getGameTime();
        int rangeLevel = upgradeLevel(ModItems.UPGRADE_RANGE.value());
        if (cachedRangeLevel == level && cachedRangeGameTime == gameTime
                && cachedRangeUpgradeLevel == rangeLevel) {
            return cachedMaximumRange;
        }
        int maximum = maximumRangeExcluding(null, rangeLevel);
        cachedRangeLevel = level;
        cachedRangeGameTime = gameTime;
        cachedRangeUpgradeLevel = rangeLevel;
        cachedMaximumRange = maximum;
        return maximum;
    }

    /**
     * Restores the legacy placement-time range promotion.  The old block hook
     * compared the newly placed head's native range with the previous maximum
     * and selected the new maximum when it was stronger.
     */
    public void updateRangeAfterTurretPlacement(BlockPos turretPos,
            TurretDefinition definition) {
        invalidateRangeCache();
        if (definition.baseRange() > maximumRangeExcluding(turretPos)) {
            setRange(maximumRange());
        }
    }

    private int maximumRangeExcluding(@Nullable BlockPos excluded) {
        if (level == null) {
            return 0;
        }
        int rangeLevel = upgradeLevel(ModItems.UPGRADE_RANGE.value());
        return maximumRangeExcluding(excluded, rangeLevel);
    }

    private int maximumRangeExcluding(@Nullable BlockPos excluded, int rangeLevel) {
        int maximum = 0;
        for (Direction direction : Direction.values()) {
            BlockPos turretPos = worldPosition.relative(direction);
            if (!turretPos.equals(excluded)
                    && level.getBlockState(turretPos).getBlock()
                            instanceof TurretHeadBlock turret) {
                maximum = Math.max(maximum,
                        TurretUpgradeRules.maximumRange(turret.definition(), rangeLevel));
            }
        }
        return maximum;
    }
    public BaseMode mode() { return mode; }
    public boolean redstonePowered() { return redstonePowered; }
    public boolean active() { return mode.isActive(redstonePowered); }
    public boolean attackHostile() { return attackHostile; }
    public boolean attackNeutral() { return attackNeutral; }
    public boolean attackPlayers() { return attackPlayers; }
    public boolean multiTargeting() { return multiTargeting; }
    public long shotsFired() { return shotsFired; }
    public long kills() { return kills; }
    public long playerKills() { return playerKills; }

    public void recordKill() {
        kills++;
        markForSave();
    }

    public void recordKill(LivingEntity target) {
        kills++;
        if (target instanceof Player) {
            playerKills++;
        }
        markForSave();
    }

    public void setActive(boolean active) {
        setMode(active ? BaseMode.ALWAYS_ON : BaseMode.ALWAYS_OFF);
    }

    public void setMode(BaseMode nextMode) {
        if (mode != nextMode) {
            mode = nextMode;
            markForSave();
        }
    }

    public void cycleMode() {
        setMode(mode.next());
    }

    public void refreshRedstoneSignal() {
        if (level == null || level.isClientSide) {
            return;
        }
        boolean powered = level.hasNeighborSignal(worldPosition);
        if (redstonePowered != powered) {
            redstonePowered = powered;
            markForSave();
        }
    }

    public void setRange(int range) {
        configuredRange = Math.max(0, range);
        markForSave();
    }

    public void setTargetFlags(boolean hostile, boolean neutral, boolean players) {
        attackHostile = hostile;
        attackNeutral = neutral;
        attackPlayers = players;
        markForSave();
    }

    public void setMultiTargeting(boolean multiTargeting) {
        this.multiTargeting = multiTargeting;
        markForSave();
    }

    public boolean isTargetClaimedBySibling(BlockPos requestingHead, LivingEntity entity) {
        if (!multiTargeting || level == null) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = worldPosition.relative(direction);
            if (!adjacent.equals(requestingHead)
                    && level.getBlockEntity(adjacent) instanceof TurretHeadBlockEntity head
                    && head.targets(entity)) {
                return true;
            }
        }
        return false;
    }

    private void warnNearbyPlayers() {
        if (!(level instanceof ServerLevel serverLevel) || !active() || !attackPlayers
                || (!ModServerConfig.warningMessage() && !ModServerConfig.warningSound())) {
            return;
        }
        long now = serverLevel.getGameTime();
        warningCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        int warningRange = range() + ModServerConfig.warningDistance();
        AABB area = new AABB(worldPosition).inflate(warningRange);
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class, area,
                candidate -> mayDamage(candidate))) {
            if (warningCooldowns.containsKey(player.getUUID())) {
                continue;
            }
            if (warningCooldowns.size() >= MAX_WARNING_COOLDOWNS) {
                UUID oldest = warningCooldowns.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(null);
                if (oldest != null) {
                    warningCooldowns.remove(oldest);
                }
            }
            if (ModServerConfig.warningSound()) {
                player.playNotifySound(ModSounds.WARNING.value(), SoundSource.BLOCKS,
                        ModServerConfig.turretSoundVolume(), 1.0F);
            }
            if (ModServerConfig.warningMessage()) {
                player.sendSystemMessage(Component.translatable(
                        "message.openmodularturrets.target_warning")
                        .withStyle(ChatFormatting.RED));
            }
            warningCooldowns.put(player.getUUID(), now + WARNING_COOLDOWN);
        }
    }

    public int damageAmpLevel() {
        return addonLevel(ModItems.ADDON_DAMAGE_AMP.value());
    }

    @Override
    public int accuracyUpgradeLevel() {
        return upgradeLevel(ModItems.UPGRADE_ACCURACY.value());
    }

    @Override
    public int scatterShotUpgradeLevel() {
        return upgradeLevel(ModItems.UPGRADE_SCATTER_SHOT.value());
    }

    public int fakeDropsLevel() {
        return TurretAddonRules.fakeDropsLevel(
                addonLevel(ModItems.ADDON_FAKE_DROPS.value()));
    }

    public boolean hasConcealer() {
        return hasAddon(ModItems.ADDON_CONCEALER.value());
    }

    public int addonRenderMask() {
        if (level != null && level.isClientSide) {
            return syncedAddonRenderMask;
        }
        return TurretVisualRules.addonMask(
                hasAddon(ModItems.ADDON_DAMAGE_AMP.value()),
                hasAddon(ModItems.ADDON_SOLAR_PANEL.value()),
                hasAddon(ModItems.ADDON_REDSTONE_REACTOR.value()));
    }

    public TurretAttackContext attackContext() {
        return new TurretAttackContext(worldPosition, fakeDropsLevel(), hasLootDeleter());
    }

    private boolean hasLootDeleter() {
        if (level == null) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(worldPosition.relative(direction))
                    .is(ModBlocks.BASE_ADDON_LOOT_DELETER.value())) {
                return true;
            }
        }
        return false;
    }

    public int adjustedFireInterval(TurretDefinition definition) {
        return TurretUpgradeRules.fireInterval(definition,
                upgradeLevel(ModItems.UPGRADE_FIRE_RATE.value()));
    }

    public float projectileInaccuracy(TurretDefinition definition) {
        return TurretUpgradeRules.projectileInaccuracy(definition,
                upgradeLevel(ModItems.UPGRADE_ACCURACY.value()),
                upgradeLevel(ModItems.UPGRADE_SCATTER_SHOT.value()));
    }

    private boolean hasAddon(net.minecraft.world.item.Item item) {
        return addonLevel(item) > 0;
    }

    private static boolean isReactorFuel(ItemStack stack) {
        return stack.is(Items.REDSTONE) || stack.is(Blocks.REDSTONE_BLOCK.asItem());
    }

    private int countStacks(net.minecraft.world.item.Item item, int start, int length) {
        int count = 0;
        for (int slot = start; slot < start + length; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public boolean canSupportTurret(BlockPos candidatePos, TurretDefinition definition) {
        if (worldPosition.distManhattan(candidatePos) != 1
                || tier().level() < definition.requiredBaseTier()) {
            return false;
        }
        int total = 0;
        int sameKind = 0;
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = worldPosition.relative(direction);
            TurretDefinition adjacentDefinition = null;
            if (adjacent.equals(candidatePos)) {
                adjacentDefinition = definition;
            } else if (level != null
                    && level.getBlockState(adjacent).getBlock() instanceof TurretHeadBlock head) {
                adjacentDefinition = head.definition();
            }
            if (adjacentDefinition != null) {
                total++;
                if (adjacentDefinition == definition) {
                    sameKind++;
                }
            }
        }
        return total <= tier().maxTurrets()
                && sameKind <= definition.maxSimultaneous();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.openmodularturrets.turret_base");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return accessFor(player).allows(AccessLevel.VIEW)
                ? new TurretBaseMenu(containerId, inventory, this) : null;
    }

    private void markForSave() {
        setChanged();
    }

    /**
     * Marks persistent state dirty and immediately publishes state consumed by
     * the client renderer or light engine.  Energy and other server-authoritative
     * values deliberately use {@link #markForSave()} only; menus and Jade have
     * their own server data paths and do not need a full BlockEntity packet per
     * tick.
     */
    private void markForSaveAndSync() {
        markForSave();
        if (level instanceof ServerLevel) {
            ModNetwork.sendBlockEntityUpdateToTracking(this);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("data_version", DATA_VERSION);
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
        tag.putString("owner_name", ownerName);
        tag.putString("owner_team", ownerTeamName);
        tag.putInt("energy", energy.stored);
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.putInt("mode_id", mode.id());
        tag.putBoolean("redstone_powered", redstonePowered);
        tag.putBoolean("active", active());
        tag.putBoolean("use_global_trust", useGlobalTrust);
        tag.putLong("local_trust_revision", localTrustRevision);
        tag.putBoolean("attack_hostile", attackHostile);
        tag.putBoolean("attack_neutral", attackNeutral);
        tag.putBoolean("attack_players", attackPlayers);
        tag.putBoolean("multi_targeting", multiTargeting);
        tag.putInt("range", configuredRange);
        tag.putLong("shots_fired", shotsFired);
        tag.putLong("kills", kills);
        tag.putLong("player_kills", playerKills);
        tag.putInt("addon_render_mask", addonRenderMask());
        if (camouflageState != null) {
            tag.put("camouflage_state", NbtUtils.writeBlockState(camouflageState));
        }
        tag.putInt("camouflage_light_value", camouflageLightValue);
        tag.putInt("camouflage_light_opacity", camouflageLightOpacity);

        ListTag trust = new ListTag();
        localTrust.forEach((id, trustEntry) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", id);
            entry.putString("name", trustEntry.name());
            entry.putInt("access", trustEntry.access().id());
            trust.add(entry);
        });
        tag.put("local_trust", trust);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = sanitizeName(tag.getString("owner_name"));
        ownerTeamName = tag.getString("owner_team");
        // Do not clamp against the load-time capacity: during loadAdditional the
        // level is still null (vanilla promotePendingBlockEntity attaches it
        // afterwards), so power-expander capacity is not counted yet and a clamp
        // here would silently truncate legitimately stored energy on every world
        // load (audit F-C5). The runtime tick converges stored energy to the real
        // capacity, which also covers config-driven capacity reductions.
        energy.stored = Math.max(0, tag.getInt("energy"));
        if (tag.contains("inventory", Tag.TAG_COMPOUND)) {
            inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        }
        if (tag.contains("mode_id", Tag.TAG_INT)) {
            mode = BaseMode.byIdOrDefault(tag.getInt("mode_id"));
        } else if (tag.contains("mode", Tag.TAG_INT)) {
            mode = BaseMode.byIdOrDefault(tag.getInt("mode"));
        } else if (tag.contains("active")) {
            mode = tag.getBoolean("active") ? BaseMode.ALWAYS_ON : BaseMode.ALWAYS_OFF;
        } else {
            mode = BaseMode.DEFAULT;
        }
        redstonePowered = tag.getBoolean("redstone_powered");
        useGlobalTrust = tag.getBoolean("use_global_trust");
        localTrustRevision = Math.max(0L, tag.getLong("local_trust_revision"));
        attackHostile = !tag.contains("attack_hostile") || tag.getBoolean("attack_hostile");
        attackNeutral = tag.contains("attack_neutral") && tag.getBoolean("attack_neutral");
        attackPlayers = tag.getBoolean("attack_players");
        multiTargeting = tag.getBoolean("multi_targeting");
        configuredRange = Math.max(0, tag.getInt("range"));
        shotsFired = Math.max(0L, tag.getLong("shots_fired"));
        kills = Math.max(0L, tag.getLong("kills"));
        playerKills = Math.max(0L, tag.getLong("player_kills"));
        camouflageState = null;
        if (tag.contains("camouflage_state", Tag.TAG_COMPOUND)) {
            BlockState loaded = NbtUtils.readBlockState(
                    registries.lookupOrThrow(Registries.BLOCK),
                    tag.getCompound("camouflage_state"));
            if (!loaded.isAir()
                    && loaded.getRenderShape() == RenderShape.MODEL
                    && !loaded.hasBlockEntity()
                    && !(loaded.getBlock() instanceof TurretBaseBlock)) {
                camouflageState = loaded;
            }
        }
        camouflageLightValue = Math.clamp(
                tag.getInt("camouflage_light_value"), 0, 15);
        camouflageLightOpacity = tag.contains("camouflage_light_opacity", Tag.TAG_INT)
                ? Math.clamp(tag.getInt("camouflage_light_opacity"), 0, 15)
                : 15;
        localTrust.clear();
        ListTag trust = tag.getList("local_trust", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(trust.size(), MAX_LOCAL_TRUST); i++) {
            CompoundTag entry = trust.getCompound(i);
            if (entry.hasUUID("player")) {
                UUID playerId = entry.getUUID("player");
                if (!playerId.equals(owner)) {
                    localTrust.put(playerId, new LocalTrustEntry(playerId,
                            sanitizeName(entry.getString("name")),
                            AccessLevel.byId(entry.getInt("access"))));
                }
            }
        }
        // 1.21.1 handles BlockEntity update packets through loadWithComponents
        // -> loadAdditional (handleUpdateTag is no longer invoked client side),
        // so the synced addon overlay mask must be restored here - otherwise
        // the client mask is frozen at its initial value forever.
        syncedAddonRenderMask = Math.clamp(
                tag.getInt("addon_render_mask"), 0, 7);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("energy", energy.stored);
        tag.putInt("mode_id", mode.id());
        tag.putBoolean("redstone_powered", redstonePowered);
        tag.putBoolean("active", active());
        tag.putBoolean("use_global_trust", useGlobalTrust);
        tag.putString("owner_name", ownerName);
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
        tag.putInt("range", configuredRange);
        tag.putInt("addon_render_mask", addonRenderMask());
        if (camouflageState != null) {
            tag.put("camouflage_state", NbtUtils.writeBlockState(camouflageState));
        }
        tag.putInt("camouflage_light_value", camouflageLightValue);
        tag.putInt("camouflage_light_opacity", camouflageLightOpacity);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        energy.stored = Math.max(0, tag.getInt("energy"));
        mode = BaseMode.byIdOrDefault(tag.getInt("mode_id"));
        redstonePowered = tag.getBoolean("redstone_powered");
        useGlobalTrust = tag.getBoolean("use_global_trust");
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = sanitizeName(tag.getString("owner_name"));
        configuredRange = Math.max(0, tag.getInt("range"));
        camouflageState = null;
        if (tag.contains("camouflage_state", Tag.TAG_COMPOUND)) {
            BlockState loaded = NbtUtils.readBlockState(
                    registries.lookupOrThrow(Registries.BLOCK),
                    tag.getCompound("camouflage_state"));
            if (!loaded.isAir()
                    && loaded.getRenderShape() == RenderShape.MODEL
                    && !loaded.hasBlockEntity()
                    && !(loaded.getBlock() instanceof TurretBaseBlock)) {
                camouflageState = loaded;
            }
        }
        camouflageLightValue = Math.clamp(
                tag.getInt("camouflage_light_value"), 0, 15);
        camouflageLightOpacity = tag.contains("camouflage_light_opacity", Tag.TAG_INT)
                ? Math.clamp(tag.getInt("camouflage_light_opacity"), 0, 15)
                : 15;
        refreshLighting();
        syncedAddonRenderMask = Math.clamp(tag.getInt("addon_render_mask"), 0, 7);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(name.length(), 40));
        name.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(40)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    public record LocalTrustEntry(UUID player, String name, AccessLevel access) {
        public LocalTrustEntry {
            name = sanitizeName(name);
        }
    }

    private final class BaseEnergyStorage implements IEnergyStorage {
        private int stored;

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = Math.min(tier().maxReceive(),
                    Math.min(maxReceive, getMaxEnergyStored() - stored));
            if (!simulate && received > 0) {
                stored += received;
                markForSave();
            }
            return received;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int extracted = Math.min(maxExtract, stored);
            if (!simulate && extracted > 0) {
                stored -= extracted;
                markForSave();
            }
            return extracted;
        }

        @Override public int getEnergyStored() { return stored; }
        @Override
        public int getMaxEnergyStored() {
            long gameTime = level == null ? Long.MIN_VALUE : level.getGameTime();
            if (level != null && cachedCapacityLevel == level
                    && cachedCapacityGameTime == gameTime) {
                return cachedMaxEnergyCapacity;
            }
            long capacity = tier().energyCapacity();
            if (level != null) {
                for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                    if (level.getBlockState(worldPosition.relative(direction)).getBlock()
                            instanceof PowerExpanderBlock expander) {
                        capacity += expander.extraCapacity();
                    }
                }
            }
            int result = (int) Math.min(Integer.MAX_VALUE, capacity);
            if (level != null) {
                cachedCapacityLevel = level;
                cachedCapacityGameTime = gameTime;
                cachedMaxEnergyCapacity = result;
            }
            return result;
        }
        @Override public boolean canExtract() { return true; }
        @Override public boolean canReceive() { return true; }

        private int generateEnergy(int requested) {
            int generated = Math.max(0,
                    Math.min(requested, getMaxEnergyStored() - stored));
            if (generated > 0) {
                stored += generated;
                markForSave();
            }
            return generated;
        }
    }

    public record VolleyResources(ItemStack ammo, int projectileCount)
            implements TurretVolleyResourcesView {
    }
}
