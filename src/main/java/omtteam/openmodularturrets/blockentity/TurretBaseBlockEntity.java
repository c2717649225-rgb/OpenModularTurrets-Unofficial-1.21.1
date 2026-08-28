package omtteam.openmodularturrets.blockentity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;

import omtteam.openmodularturrets.block.TurretBaseBlock;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.blockentity.base.AddonContext;
import omtteam.openmodularturrets.blockentity.base.BaseAddonEngine;
import omtteam.openmodularturrets.blockentity.base.BaseCamouflageManager;
import omtteam.openmodularturrets.blockentity.base.BaseExpanderTopology;
import omtteam.openmodularturrets.blockentity.base.BaseMemoryCardAdapter;
import omtteam.openmodularturrets.blockentity.base.BaseTrustManager;
import omtteam.openmodularturrets.blockentity.base.BaseTrustManager.LocalTrustEntry;
import omtteam.openmodularturrets.blockentity.base.BaseWarningService;
import omtteam.openmodularturrets.blockentity.base.WarningContext;
import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.damage.TurretAttackContext;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.data.BaseMode;
import omtteam.openmodularturrets.data.BaseTier;
import omtteam.openmodularturrets.data.MemoryCardProfile;
import omtteam.openmodularturrets.data.OwnershipRules;
import omtteam.openmodularturrets.data.TargetingRules;
import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.data.TurretCombatContext;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretTargetingWorldQueries;
import omtteam.openmodularturrets.data.TurretUpgradeRules;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.data.TurretVolleyResourcesView;
import omtteam.openmodularturrets.menu.TurretBaseMenu;
import omtteam.openmodularturrets.network.ModNetwork;
import omtteam.openmodularturrets.registration.ModBlockEntities;
import omtteam.openmodularturrets.registration.ModBlocks;
import omtteam.openmodularturrets.registration.ModItems;
import omtteam.openmodularturrets.registration.ModTags;
import omtteam.openmodularturrets.security.SecuritySavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
import net.minecraft.tags.TagKey;
/**
 * Modernized, server-authoritative Turret Base BlockEntity.
 * Functions as a lean orchestrator delegating domain concerns to dedicated sub-components.
 */
public final class TurretBaseBlockEntity extends BlockEntity
        implements MenuProvider, TurretTargetingWorldQueries, TurretCombatContext,
        AddonContext, WarningContext {

    public static final int AMMO_SLOT_COUNT = 9;
    public static final int ADDON_SLOT_START = 9;
    public static final int UPGRADE_SLOT_START = 11;
    public static final int INVENTORY_SIZE = 13;

    private static final int DATA_VERSION = 5;
    private static final int WARNING_SCAN_INTERVAL = 20;
    /** Reclaims energy stored above a shrunken capacity after expander loss. */
    private static final int ENERGY_CLAMP_INTERVAL = 10;
    /** Owner team-name cache refresh cadence, staggered by position. */
    private static final int OWNER_TEAM_REFRESH_INTERVAL = 20;

    // Sub-components
    private final BaseTrustManager trustManager = new BaseTrustManager();
    private final BaseCamouflageManager camouflageManager = new BaseCamouflageManager();
    private final BaseExpanderTopology expanderTopology = new BaseExpanderTopology();
    private final BaseAddonEngine addonEngine = new BaseAddonEngine();
    private final BaseWarningService warningService = new BaseWarningService();

    // Physical storage & capabilities
    private final BaseEnergyStorage energy = new BaseEnergyStorage();
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
            if (slot >= ADDON_SLOT_START && slot < UPGRADE_SLOT_START && level instanceof ServerLevel) {
                ModNetwork.sendBlockEntityUpdateToTracking(TurretBaseBlockEntity.this);
            }
        }
    };
    private final IItemHandler automationInventory = new RangedWrapper(inventory, 0, AMMO_SLOT_COUNT);

    // Range cache
    @Nullable
    private Level cachedRangeLevel;
    private long cachedRangeGameTime = Long.MIN_VALUE;
    private int cachedRangeUpgradeLevel = Integer.MIN_VALUE;
    private int cachedMaximumRange;

    // Base state
    @Nullable
    private UUID owner;
    private String ownerName = "";
    private String ownerTeamName = "";
    private BaseMode mode = BaseMode.INVERTED;
    private boolean redstonePowered;
    private boolean useGlobalTrust;
    private boolean attackHostile = true;
    private boolean attackNeutral;
    private boolean attackPlayers;
    private boolean multiTargeting;
    private int configuredRange = 10;
    private long shotsFired;
    private long kills;
    private long playerKills;
    private int syncedAddonRenderMask;

    public TurretBaseBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TURRET_BASE.value(), pos, state);
    }

    @Override
    public void setRemoved() {
        invalidateNeighborCaches();
        super.setRemoved();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, TurretBaseBlockEntity base) {
        base.refreshRedstoneSignal();
        // Legacy 1.12 solar rules: daylight, clear weather, open sky two blocks
        // above the base, and an air gap three blocks above it.
        if (base.hasAddon(ModItems.ADDON_SOLAR_PANEL.value())
                && level.isDay() && !level.isRaining()
                && level.canSeeSky(pos.above(2))
                && level.getBlockState(pos.above(3)).isAir()) {
            base.energy.generateEnergy(TurretAddonRules.solarGeneration());
        }
        // Position-staggered like the warning scan so large fleets do not run
        // every reactor cycle on the same global tick.
        if (Math.floorMod(level.getGameTime() + pos.asLong(),
                TurretAddonRules.REACTOR_INTERVAL) == 0) {
            base.runReactorCycle();
        }
        if (level.getGameTime() % ENERGY_CLAMP_INTERVAL == 0
                && base.energy.getEnergyStored() > base.energy.getMaxEnergyStored()) {
            base.energy.setStoredForLoad(base.energy.getMaxEnergyStored());
            base.markForSave();
        }
        if (Math.floorMod(level.getGameTime() + pos.asLong(), WARNING_SCAN_INTERVAL) == 0) {
            base.warnNearbyPlayers();
        }
        if (Math.floorMod(level.getGameTime() + pos.asLong(), OWNER_TEAM_REFRESH_INTERVAL) == 0) {
            base.refreshOwnerTeamName();
        }
    }

    public BaseTier tier() {
        return getBlockState().getBlock() instanceof TurretBaseBlock block ? block.tier() : BaseTier.ONE;
    }

    public void claim(UUID playerId) {
        doClaim(playerId, "", "");
    }

    public void claim(UUID playerId, String name) {
        doClaim(playerId, name, "");
    }

    public void claim(Player player) {
        doClaim(player.getUUID(), sanitizeName(player.getGameProfile().getName()),
                player.getTeam() == null ? "" : player.getTeam().getName());
    }

    private void doClaim(UUID playerId, String name, String teamName) {
        if (owner != null) {
            return;
        }
        owner = playerId;
        ownerName = sanitizeName(name);
        ownerTeamName = teamName;
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
        boolean opCheck = OwnershipRules.opIsProtected(ModServerConfig.canOpAccessOwnedBlocks(),
                player instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(4));
        return trustManager.accessFor(player, owner, ownerName, useGlobalTrust, level, opCheck);
    }

    public boolean setLocalTrust(Player actor, UUID target, AccessLevel access) {
        return setLocalTrust(actor, target, target.toString(), access);
    }

    public boolean setLocalTrust(Player actor, UUID target, String name, AccessLevel access) {
        if (accessFor(actor) != AccessLevel.ADMIN) {
            return false;
        }
        boolean changed = trustManager.setLocalTrust(target, name, access, owner);
        if (changed) {
            markForSave();
        }
        return changed;
    }

    public boolean removeLocalTrust(Player actor, UUID target) {
        if (accessFor(actor) != AccessLevel.ADMIN) {
            return false;
        }
        boolean changed = trustManager.removeLocalTrust(target);
        if (changed) {
            markForSave();
        }
        return changed;
    }

    public Map<UUID, LocalTrustEntry> localTrustSnapshot() {
        return trustManager.snapshot();
    }

    public boolean hasLocalTrust(UUID playerId) {
        return trustManager.contains(playerId);
    }

    public long localTrustRevision() {
        return trustManager.revision();
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
        if (!(level instanceof ServerLevel serverLevel) || accessFor(actor) != AccessLevel.ADMIN) {
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
        return camouflageManager.camouflageState();
    }

    public int camouflageLightValue() {
        return camouflageManager.lightValue();
    }

    public int camouflageLightOpacity() {
        return camouflageManager.lightOpacity();
    }

    public boolean setCamouflage(Player actor, BlockState state) {
        if (level == null || !isOwner(actor) || !camouflageManager.setCamouflage(state, level, worldPosition)) {
            return false;
        }
        syncCamouflageState();
        return true;
    }

    public boolean clearCamouflage(Player actor) {
        if (!isOwner(actor) || !camouflageManager.clearCamouflage()) {
            return false;
        }
        syncCamouflageState();
        return true;
    }

    public boolean setCamouflageLightValue(Player actor, int value) {
        if (!isOwner(actor) || tier().level() < 4 || !camouflageManager.setLightValue(value)) {
            return false;
        }
        syncCamouflageState();
        return true;
    }

    public boolean setCamouflageLightOpacity(Player actor, int value) {
        if (!isOwner(actor) || tier().level() < 4 || !camouflageManager.setLightOpacity(value)) {
            return false;
        }
        markForSaveAndSync();
        refreshLighting();
        return true;
    }

    public boolean isValidCamouflage(BlockState state) {
        return level != null && camouflageManager.isValidCamouflage(state, level, worldPosition);
    }

    private void syncCamouflageState() {
        if (level == null) {
            return;
        }
        BlockState current = getBlockState();
        if (current.getBlock() instanceof TurretBaseBlock) {
            BlockState next = current
                    .setValue(TurretBaseBlock.CAMOUFLAGED, camouflageManager.camouflageState().isPresent())
                    .setValue(TurretBaseBlock.LIGHT_LEVEL, camouflageManager.lightValue());
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

    @Override
    public boolean isTargetClaimedBySibling(BlockPos requestingHead, LivingEntity entity) {
        if (!multiTargeting || level == null) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = worldPosition.relative(direction);
            if (!adjacent.equals(requestingHead)
                    && level.getBlockEntity(adjacent) instanceof TurretHeadBlockEntity siblingHead) {
                if (siblingHead.targets(entity)) {
                    return true;
                }
            }
        }
        return false;
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
        return owner != null
                && player.getTeam() != null
                && !ownerTeamName.isEmpty()
                && ownerTeamName.equals(player.getTeam().getName());
    }

    /**
     * Slow-cadence maintenance for the cached owner team name.  Kept out of
     * the targeting predicates so {@link #mayDamage} stays side-effect free.
     */
    private void refreshOwnerTeamName() {
        if (!(level instanceof ServerLevel serverLevel) || owner == null) {
            return;
        }
        Player ownerPlayer = serverLevel.getPlayerByUUID(owner);
        if (ownerPlayer == null) {
            return;
        }
        String onlineTeam = ownerPlayer.getTeam() == null
                ? "" : ownerPlayer.getTeam().getName();
        if (!onlineTeam.equals(ownerTeamName)) {
            ownerTeamName = onlineTeam;
            markForSave();
        }
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
        // Hot path: probe by UUID first, then fall back to offline-mode name
        // matching over the live map; snapshot() copies are reserved for exports.
        if (trustManager.contains(player.getUUID())) {
            return true;
        }
        return ModServerConfig.offlineModeSupport()
                && trustManager.matchesByName(player.getUUID(),
                        player.getGameProfile().getName());
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
        return trustManager.contains(playerId);
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

    public int addonLevel(Item item) {
        return countStacks(item, ADDON_SLOT_START, addonSlotCount());
    }

    public int upgradeLevel(Item item) {
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

    public Optional<VolleyResources> consumeResourcesForVolley(TurretDefinition definition, double recyclerRoll) {
        int shotCount = projectileCount();
        int actualEnergyCost = effectiveEnergyCost(definition);
        TagKey<Item> ammo = definition.ammoTag();
        if (energy.getEnergyStored() < actualEnergyCost
                || (ModServerConfig.requireAmmo() && ammo != null && countAvailableAmmo(ammo) < shotCount)) {
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

    private ItemStack findRepresentativeAmmo(TagKey<Item> ammo) {
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

    private int countAvailableAmmo(TagKey<Item> ammo) {
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

    private ItemStack extractAmmo(TagKey<Item> ammo, int requested) {
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

    public List<IItemHandler> ammoInventories() {
        return expanderTopology.aggregateAmmoInventories(level, worldPosition, automationInventory);
    }

    public void invalidateNeighborCaches() {
        invalidateRangeCache();
        expanderTopology.invalidateCaches();
    }

    private void invalidateRangeCache() {
        cachedRangeLevel = null;
        cachedRangeGameTime = Long.MIN_VALUE;
        cachedRangeUpgradeLevel = Integer.MIN_VALUE;
        cachedMaximumRange = 0;
    }

    public int runReactorCycle() {
        return addonEngine.runReactorCycle(this);
    }

    @Override
    public boolean containsItem(Item item) {
        for (IItemHandler handler : ammoInventories()) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                if (handler.getStackInSlot(slot).is(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean extractItem(Item item) {
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

    @Override
    public int generateEnergy(int amount) {
        return energy.generateEnergy(amount);
    }

    @Override
    public int maxEnergyCapacity() {
        return energy.getMaxEnergyStored();
    }

    @Override
    public int storedEnergy() {
        return energy.getEnergyStored();
    }

    public MemoryCardProfile createProfile() {
        return BaseMemoryCardAdapter.exportProfile(configuredRange, mode, attackHostile,
                attackNeutral, attackPlayers, multiTargeting, localTrustSnapshot());
    }

    public boolean applyProfile(Player actor, MemoryCardProfile profile) {
        if (!accessFor(actor).allows(AccessLevel.USE)
                || profile.schemaVersion() < 1
                || profile.schemaVersion() > MemoryCardProfile.CURRENT_SCHEMA) {
            return false;
        }
        // Trust entries are privilege-bearing and a carrying card replaces the
        // whole local list, so applying one needs admin on THIS base.  Without
        // this gate a USE-level player could escalate via a card saved on any
        // other base where they held admin (and wipe this base's trust).
        if (profile.carriesTrust() && accessFor(actor) != AccessLevel.ADMIN) {
            return false;
        }
        configuredRange = Math.max(0, profile.range());
        mode = profile.mode();
        attackHostile = profile.attackHostile();
        attackNeutral = profile.attackNeutral();
        attackPlayers = profile.attackPlayers();
        multiTargeting = profile.multiTargeting();
        if (profile.carriesTrust()) {
            BaseMemoryCardAdapter.applyTrust(trustManager, profile.trustEntries(), owner);
        }
        markForSave();
        return true;
    }

    public int configuredRange() {
        return configuredRange;
    }

    public void setRange(int range) {
        int bounded = Math.max(0, range);
        if (configuredRange != bounded) {
            configuredRange = bounded;
            markForSave();
        }
    }

    public int range() {
        return Math.min(configuredRange, maximumRange());
    }

    @Override
    public BlockPos worldPosition() {
        return worldPosition;
    }

    @Override
    public Level level() {
        return level;
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

    public void updateRangeAfterTurretPlacement(BlockPos turretPos, TurretDefinition definition) {
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
                    && level.getBlockState(turretPos).getBlock() instanceof TurretHeadBlock turret) {
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

    public void setAttackHostile(boolean attack) {
        if (attackHostile != attack) {
            attackHostile = attack;
            markForSave();
        }
    }

    public void setAttackNeutral(boolean attack) {
        if (attackNeutral != attack) {
            attackNeutral = attack;
            markForSave();
        }
    }

    public void setAttackPlayers(boolean attack) {
        if (attackPlayers != attack) {
            attackPlayers = attack;
            markForSave();
        }
    }

    public void setTargetFlags(boolean attackHostile, boolean attackNeutral, boolean attackPlayers) {
        boolean changed = this.attackHostile != attackHostile
                || this.attackNeutral != attackNeutral
                || this.attackPlayers != attackPlayers;
        this.attackHostile = attackHostile;
        this.attackNeutral = attackNeutral;
        this.attackPlayers = attackPlayers;
        if (changed) {
            markForSave();
        }
    }

    public void setMultiTargeting(boolean multiTargeting) {
        if (this.multiTargeting != multiTargeting) {
            this.multiTargeting = multiTargeting;
            markForSave();
        }
    }

    public void warnNearbyPlayers() {
        warningService.warnNearbyPlayers(this);
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
        return addonEngine.computeRenderMask(this);
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

    @Override
    public boolean hasAddon(Item item) {
        return addonLevel(item) > 0;
    }

    private static boolean isReactorFuel(ItemStack stack) {
        return stack.is(Items.REDSTONE) || stack.is(Blocks.REDSTONE_BLOCK.asItem());
    }

    private int countStacks(Item item, int start, int length) {
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
        tag.putInt("energy", energy.getEnergyStored());
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.putInt("mode_id", mode.id());
        tag.putBoolean("redstone_powered", redstonePowered);
        tag.putBoolean("active", active());
        tag.putBoolean("use_global_trust", useGlobalTrust);
        tag.putBoolean("attack_hostile", attackHostile);
        tag.putBoolean("attack_neutral", attackNeutral);
        tag.putBoolean("attack_players", attackPlayers);
        tag.putBoolean("multi_targeting", multiTargeting);
        tag.putInt("range", configuredRange);
        tag.putLong("shots_fired", shotsFired);
        tag.putLong("kills", kills);
        tag.putLong("player_kills", playerKills);
        tag.putInt("addon_render_mask", addonRenderMask());

        camouflageManager.saveNbt(tag);
        trustManager.saveNbt(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = sanitizeName(tag.getString("owner_name"));
        ownerTeamName = tag.getString("owner_team");
        energy.setStoredForLoad(tag.getInt("energy"));
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
        attackHostile = !tag.contains("attack_hostile") || tag.getBoolean("attack_hostile");
        attackNeutral = tag.contains("attack_neutral") && tag.getBoolean("attack_neutral");
        attackPlayers = tag.getBoolean("attack_players");
        multiTargeting = tag.getBoolean("multi_targeting");
        configuredRange = Math.max(0, tag.getInt("range"));
        shotsFired = Math.max(0L, tag.getLong("shots_fired"));
        kills = Math.max(0L, tag.getLong("kills"));
        playerKills = Math.max(0L, tag.getLong("player_kills"));

        camouflageManager.loadNbt(tag, registries);
        trustManager.loadNbt(tag, owner);

        if (tag.contains("addon_render_mask", Tag.TAG_INT)) {
            syncedAddonRenderMask = Math.clamp(tag.getInt("addon_render_mask"), 0, TurretVisualRules.ADDON_MASK_ALL);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("energy", energy.getEnergyStored());
        tag.putInt("mode_id", mode.id());
        tag.putBoolean("redstone_powered", redstonePowered);
        tag.putBoolean("active", active());
        tag.putBoolean("use_global_trust", useGlobalTrust);
        tag.putString("owner_name", ownerName);
        // The owner UUID is mirrored to tracking clients on purpose: the menu
        // trust view and the Jade integration read it client-side.  It is
        // world-local gameplay data, not a secret; revisit only if a privacy
        // requirement explicitly lands.
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
        tag.putInt("range", configuredRange);
        tag.putInt("addon_render_mask", addonRenderMask());
        camouflageManager.saveNbt(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        energy.setStoredForLoad(tag.getInt("energy"));
        mode = BaseMode.byIdOrDefault(tag.getInt("mode_id"));
        redstonePowered = tag.getBoolean("redstone_powered");
        useGlobalTrust = tag.getBoolean("use_global_trust");
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = sanitizeName(tag.getString("owner_name"));
        configuredRange = Math.max(0, tag.getInt("range"));
        camouflageManager.loadNbt(tag, registries);
        if (tag.contains("addon_render_mask", Tag.TAG_INT)) {
            syncedAddonRenderMask = Math.clamp(tag.getInt("addon_render_mask"), 0, TurretVisualRules.ADDON_MASK_ALL);
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static String sanitizeName(@Nullable String name) {
        return name == null ? "" : name.trim();
    }

    public final class BaseEnergyStorage implements IEnergyStorage {
        private int stored;

        /**
         * Persistence and capacity-clamp write path.  Mirrors the historical
         * load semantics: clamp to non-negative, but allow values above the
         * current capacity until the periodic clamp cycle reclaims them.
         */
        public void setStoredForLoad(int value) {
            stored = Math.max(0, value);
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = Math.max(0, Math.min(maxReceive, getMaxEnergyStored() - stored));
            if (!simulate && received > 0) {
                stored += received;
                markForSave();
            }
            return received;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int extracted = Math.max(0, Math.min(maxExtract, stored));
            if (!simulate && extracted > 0) {
                stored -= extracted;
                markForSave();
            }
            return extracted;
        }

        @Override public int getEnergyStored() { return stored; }

        @Override
        public int getMaxEnergyStored() {
            return expanderTopology.calculateMaxEnergyCapacity(tier().energyCapacity(), level, worldPosition);
        }

        @Override public boolean canExtract() { return true; }
        @Override public boolean canReceive() { return true; }

        public int generateEnergy(int requested) {
            int generated = Math.max(0, Math.min(requested, getMaxEnergyStored() - stored));
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
