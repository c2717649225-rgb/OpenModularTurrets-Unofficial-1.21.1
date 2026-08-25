package omtteam.openmodularturrets.gametest;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.blockentity.TurretHeadBlockEntity;
import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.data.BaseMode;
import omtteam.openmodularturrets.data.BaseTier;
import omtteam.openmodularturrets.data.MemoryCardProfile;
import omtteam.openmodularturrets.data.OwnershipRules;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.data.TurretUpgradeRules;
import omtteam.openmodularturrets.data.TargetPriorityProfile;
import omtteam.openmodularturrets.data.TargetingRules;
import omtteam.openmodularturrets.data.SpecialTurretRules;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.menu.TurretBaseMenu;
import omtteam.openmodularturrets.network.BaseCommand;
import omtteam.openmodularturrets.network.BaseCommandPayload;
import omtteam.openmodularturrets.network.BaseCommandService;
import omtteam.openmodularturrets.network.ClientTrustSnapshot;
import omtteam.openmodularturrets.network.TrustScope;
import omtteam.openmodularturrets.network.TrustSnapshotPayload;
import omtteam.openmodularturrets.registration.ModBlocks;
import omtteam.openmodularturrets.registration.ModDataComponents;
import omtteam.openmodularturrets.registration.ModItems;
import omtteam.openmodularturrets.registration.ModSounds;
import omtteam.openmodularturrets.registration.ModTags;
import omtteam.openmodularturrets.damage.ModDamageTypes;
import omtteam.openmodularturrets.damage.TurretAttackContext;
import omtteam.openmodularturrets.damage.TurretDamageSource;
import omtteam.openmodularturrets.event.TurretCombatEvents;
import omtteam.openmodularturrets.entity.ProjectileKind;
import omtteam.openmodularturrets.entity.TurretProjectileEntity;
import omtteam.openmodularturrets.security.SecuritySavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import java.util.Arrays;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.registries.DeferredBlock;
/**
 * Mechanical domain split of the former OpenModularTurretsGameTests
 monolith; namespace, annotations, bodies and expectations unchanged.
 */
@GameTestHolder("openmodularturrets")
public final class BaseStateGameTests {
    private BaseStateGameTests() {
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void statePersistence(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_ONE.value());
        helper.setBlock(relative.east(), ModBlocks.DISPOSABLE_ITEM_TURRET.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        var owner = helper.makeMockPlayer(GameType.CREATIVE);
        base.claim(owner.getUUID());
        for (int i = 0; i < 8; i++) {
            base.energy().receiveEnergy(50, false);
        }
        base.setRange(999);
        base.setTargetFlags(true, true, false);

        CompoundTag saved = base.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(base.getBlockPos(), base.getBlockState(),
                saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof TurretBaseBlockEntity,
                "Saved base must load as a turret base");
        TurretBaseBlockEntity copy = (TurretBaseBlockEntity) loaded;
        helper.assertTrue(copy.energy().getEnergyStored() == 400,
                "Energy did not survive save/load");
        helper.assertTrue(base.range() == TurretDefinition.DISPOSABLE.baseRange(),
                "Effective range did not use the attached turret's dynamic maximum");
        helper.assertTrue(copy.configuredRange() == 999,
                "Configured range did not survive save/load");
        helper.assertTrue(copy.owner().filter(owner.getUUID()::equals).isPresent(),
                "Owner UUID did not survive save/load");

        ItemStack card = new ItemStack(ModItems.MEMORY_CARD.value());
        MemoryCardProfile profile = base.createProfile();
        card.set(ModDataComponents.MEMORY_CARD_PROFILE.value(), profile);
        helper.assertTrue(profile.equals(card.copy()
                        .get(ModDataComponents.MEMORY_CARD_PROFILE.value())),
                "Memory-card Data Component did not survive stack copy");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void baseCamouflagePersistenceAndValidation(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        var owner = helper.makeMockPlayer(GameType.CREATIVE);
        var stranger = helper.makeMockPlayer(GameType.CREATIVE);
        base.claim(owner.getUUID());

        helper.assertTrue(!base.setCamouflage(stranger, Blocks.STONE.defaultBlockState()),
                "A non-owner changed the base camouflage");
        helper.assertTrue(!base.setCamouflage(owner, Blocks.AIR.defaultBlockState()),
                "Air was accepted as base camouflage");
        helper.assertTrue(!base.setCamouflage(owner, Blocks.CHEST.defaultBlockState()),
                "A block entity block was accepted as base camouflage");
        helper.assertTrue(!base.setCamouflage(owner, Blocks.STONE_SLAB.defaultBlockState()),
                "A partial block was accepted as base camouflage");
        helper.assertTrue(base.setCamouflage(owner, Blocks.STONE.defaultBlockState()),
                "Owner could not apply stone camouflage");
        helper.assertTrue(base.camouflageState().orElseThrow().is(Blocks.STONE),
                "Applied camouflage state was not retained");
        helper.assertTrue(base.getBlockState().getValue(
                        omtteam.openmodularturrets.block.TurretBaseBlock.CAMOUFLAGED),
                "Base block state did not mirror camouflage presence");

        CompoundTag saved = base.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(base.getBlockPos(), base.getBlockState(),
                saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof TurretBaseBlockEntity,
                "Camouflaged base did not load as a turret base");
        TurretBaseBlockEntity copy = (TurretBaseBlockEntity) loaded;
        helper.assertTrue(copy.camouflageState().orElseThrow().is(Blocks.STONE),
                "Camouflage block state did not survive save/load");

        CompoundTag legacy = saved.copy();
        legacy.remove("camouflage_state");
        legacy.remove("camouflage_light_value");
        legacy.remove("camouflage_light_opacity");
        BlockEntity legacyLoaded = BlockEntity.loadStatic(base.getBlockPos(),
                base.getBlockState(), legacy, helper.getLevel().registryAccess());
        TurretBaseBlockEntity legacyCopy = (TurretBaseBlockEntity) legacyLoaded;
        helper.assertTrue(legacyCopy.camouflageState().isEmpty()
                        && legacyCopy.camouflageLightValue() == 0
                        && legacyCopy.camouflageLightOpacity() == 15,
                "Legacy base data did not use camouflage defaults");

        helper.assertTrue(base.clearCamouflage(owner),
                "Owner could not clear base camouflage");
        helper.assertTrue(!base.getBlockState().getValue(
                        omtteam.openmodularturrets.block.TurretBaseBlock.CAMOUFLAGED),
                "Cleared camouflage remained in the base block state");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void baseCamouflageLightContract(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        var owner = helper.makeMockPlayer(GameType.CREATIVE);
        base.claim(owner.getUUID());
        helper.assertTrue(base.setCamouflage(owner, Blocks.STONE.defaultBlockState()),
                "Owner could not establish light contract fixture");
        helper.assertTrue(base.setCamouflageLightValue(owner, 15)
                        && base.setCamouflageLightOpacity(owner, 0),
                "Tier five owner could not set camouflage light values");
        helper.assertTrue(base.camouflageLightValue() == 15
                        && base.camouflageLightOpacity() == 0
                        && base.getBlockState().getValue(
                                omtteam.openmodularturrets.block.TurretBaseBlock.LIGHT_LEVEL) == 15,
                "Camouflage light values were not mirrored or persisted in memory");
        helper.assertTrue(!base.setCamouflageLightValue(owner, -1)
                        && !base.setCamouflageLightValue(owner, 16)
                        && !base.setCamouflageLightOpacity(owner, -1)
                        && !base.setCamouflageLightOpacity(owner, 16),
                "Out-of-range camouflage light values were accepted");

        BlockPos tierThreePos = relative.east(2);
        helper.setBlock(tierThreePos, ModBlocks.TURRET_BASE_TIER_THREE.value());
        TurretBaseBlockEntity tierThree = helper.getBlockEntity(tierThreePos);
        tierThree.claim(owner.getUUID());
        helper.assertTrue(!tierThree.setCamouflageLightValue(owner, 1)
                        && !tierThree.setCamouflageLightOpacity(owner, 1)
                        && tierThree.camouflageLightValue() == 0
                        && tierThree.camouflageLightOpacity() == 15,
                "Tier three base exposed camouflage light controls");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void tieredInventoryRules(GameTestHelper helper) {
        helper.assertTrue(BaseTier.ONE.addonSlots() == 0
                        && BaseTier.ONE.upgradeSlots() == 0,
                "Tier one unexpectedly enables addon or upgrade slots");
        for (BaseTier tier : new BaseTier[] {
                BaseTier.TWO, BaseTier.THREE, BaseTier.FOUR
        }) {
            helper.assertTrue(tier.addonSlots() == 2 && tier.upgradeSlots() == 1,
                    "Tier " + tier.level() + " has the wrong slot policy");
        }
        helper.assertTrue(BaseTier.FIVE.addonSlots() == 2
                        && BaseTier.FIVE.upgradeSlots() == 2,
                "Tier five must expose two addon and two upgrade slots");

        BlockPos tierOnePos = new BlockPos(0, 0, 0);
        helper.setBlock(tierOnePos, ModBlocks.TURRET_BASE_TIER_ONE.value());
        TurretBaseBlockEntity tierOne = helper.getBlockEntity(tierOnePos);
        ItemStack addon = new ItemStack(ModItems.ADDON_SOLAR_PANEL.value());
        helper.assertTrue(tierOne.inventory()
                        .insertItem(TurretBaseBlockEntity.ADDON_SLOT_START, addon, false)
                        .getCount() == 1,
                "Tier one accepted an addon into a disabled slot");
        tierOne.inventory().setStackInSlot(TurretBaseBlockEntity.ADDON_SLOT_START,
                addon.copy());
        helper.assertTrue(!tierOne.inventory()
                        .extractItem(TurretBaseBlockEntity.ADDON_SLOT_START, 1, false)
                        .isEmpty(),
                "A legacy item in a disabled slot could not be recovered");

        BlockPos tierFivePos = new BlockPos(2, 0, 0);
        helper.setBlock(tierFivePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity tierFive = helper.getBlockEntity(tierFivePos);
        tierFive.inventory().setStackInSlot(TurretBaseBlockEntity.UPGRADE_SLOT_START,
                new ItemStack(ModItems.UPGRADE_FIRE_RATE.value(), 3));
        tierFive.inventory().setStackInSlot(TurretBaseBlockEntity.UPGRADE_SLOT_START + 1,
                new ItemStack(ModItems.UPGRADE_FIRE_RATE.value(), 5));
        helper.assertTrue(tierFive.upgradeLevel(ModItems.UPGRADE_FIRE_RATE.value()) == 8,
                "Upgrade level counted occupied slots instead of stack counts");

        var owner = helper.makeMockPlayer(GameType.CREATIVE);
        var viewer = helper.makeMockPlayer(GameType.CREATIVE);
        tierFive.claim(owner.getUUID());
        helper.assertTrue(tierFive.setLocalTrust(owner, viewer.getUUID(), AccessLevel.VIEW),
                "Unable to establish VIEW-only test permission");
        TurretBaseMenu viewerMenu = new TurretBaseMenu(1, viewer.getInventory(), tierFive);
        TurretBaseMenu ownerMenu = new TurretBaseMenu(2, owner.getInventory(), tierFive);
        helper.assertTrue(!viewerMenu.getSlot(0)
                        .mayPlace(new ItemStack(ModItems.AMMO_BULLET.value())),
                "VIEW-only player can mutate base inventory");
        helper.assertTrue(ownerMenu.getSlot(0)
                        .mayPlace(new ItemStack(ModItems.AMMO_BULLET.value())),
                "Base owner cannot mutate base inventory");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void turretAttachmentLimits(GameTestHelper helper) {
        BlockPos relative = new BlockPos(5, 2, 5);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);

        BlockPos north = relative.north();
        BlockPos south = relative.south();
        BlockPos east = relative.east();
        BlockPos west = relative.west();
        helper.setBlock(north, ModBlocks.RAIL_GUN_TURRET.value());
        helper.setBlock(south, ModBlocks.RAIL_GUN_TURRET.value());
        helper.assertTrue(!base.canSupportTurret(
                        helper.absolutePos(east), TurretDefinition.RAIL_GUN),
                "Third rail gun bypassed its per-kind limit");

        helper.setBlock(east, ModBlocks.PLASMA_TURRET.value());
        helper.assertTrue(!base.canSupportTurret(
                        helper.absolutePos(west), TurretDefinition.PLASMA),
                "Second plasma turret bypassed its per-kind limit");
        helper.setBlock(west, ModBlocks.LASER_TURRET.value());
        helper.assertTrue(!base.canSupportTurret(
                        helper.absolutePos(relative.above()), TurretDefinition.DISPOSABLE),
                "Fifth turret bypassed the tier-five total limit");
        helper.assertTrue(base.canSupportTurret(
                        helper.absolutePos(north), TurretDefinition.RAIL_GUN),
                "Existing turret was counted twice during survival validation");

        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FOUR.value());
        TurretBaseBlockEntity tierFour = helper.getBlockEntity(relative);
        helper.assertTrue(!tierFour.canSupportTurret(
                        helper.absolutePos(relative.above()), TurretDefinition.LASER),
                "Tier-four base accepted a tier-five turret");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void baseModePersistenceAndMigration(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 2, 2);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);

        for (BaseMode mode : BaseMode.values()) {
            boolean unpowered = mode == BaseMode.ALWAYS_ON || mode == BaseMode.INVERTED;
            boolean powered = mode == BaseMode.ALWAYS_ON || mode == BaseMode.NONINVERTED;
            helper.assertTrue(mode.isActive(false) == unpowered
                            && mode.isActive(true) == powered,
                    "Base mode truth table changed for " + mode);
        }
        helper.assertTrue(BaseMode.DEFAULT == BaseMode.INVERTED
                        && BaseMode.ALWAYS_ON.next() == BaseMode.ALWAYS_OFF
                        && BaseMode.ALWAYS_OFF.next() == BaseMode.INVERTED
                        && BaseMode.INVERTED.next() == BaseMode.NONINVERTED
                        && BaseMode.NONINVERTED.next() == BaseMode.ALWAYS_ON,
                "Default base mode or legacy mode cycle order changed");

        base.setActive(true);
        helper.assertTrue(base.mode() == BaseMode.ALWAYS_ON && base.active(),
                "setActive(true) did not select the legacy always-on mode");
        base.setActive(false);
        helper.assertTrue(base.mode() == BaseMode.ALWAYS_OFF && !base.active(),
                "setActive(false) did not select the legacy always-off mode");

        base.setMode(BaseMode.INVERTED);
        helper.setBlock(relative.west(), Blocks.REDSTONE_BLOCK);
        base.refreshRedstoneSignal();
        helper.assertTrue(base.redstonePowered() && !base.active(),
                "Powered inverted mode did not disable the base");
        helper.setBlock(relative.west(), Blocks.AIR);
        base.refreshRedstoneSignal();
        helper.assertTrue(!base.redstonePowered() && base.active(),
                "Unpowered inverted mode did not enable the base");

        CompoundTag current = base.saveWithFullMetadata(helper.getLevel().registryAccess());
        current.putInt("mode_id", BaseMode.NONINVERTED.id());
        current.putBoolean("redstone_powered", true);
        BlockEntity loaded = BlockEntity.loadStatic(base.getBlockPos(), base.getBlockState(),
                current, helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof TurretBaseBlockEntity copy
                        && copy.mode() == BaseMode.NONINVERTED
                        && copy.redstonePowered() && copy.active(),
                "Current base mode or redstone state did not survive save/load");

        CompoundTag legacyMode = current.copy();
        legacyMode.remove("mode_id");
        legacyMode.putInt("mode", BaseMode.ALWAYS_OFF.id());
        loaded = BlockEntity.loadStatic(base.getBlockPos(), base.getBlockState(), legacyMode,
                helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof TurretBaseBlockEntity copy
                        && copy.mode() == BaseMode.ALWAYS_OFF,
                "Legacy integer mode did not migrate");

        CompoundTag legacyActive = current.copy();
        legacyActive.remove("mode_id");
        legacyActive.remove("mode");
        legacyActive.putBoolean("active", true);
        loaded = BlockEntity.loadStatic(base.getBlockPos(), base.getBlockState(), legacyActive,
                helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof TurretBaseBlockEntity copy
                        && copy.mode() == BaseMode.ALWAYS_ON,
                "Legacy active flag did not migrate to always-on mode");

        CompoundTag noMode = current.copy();
        noMode.remove("mode_id");
        noMode.remove("mode");
        noMode.remove("active");
        loaded = BlockEntity.loadStatic(base.getBlockPos(), base.getBlockState(), noMode,
                helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof TurretBaseBlockEntity copy
                        && copy.mode() == BaseMode.DEFAULT,
                "Missing legacy mode fields did not use the inverted default");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void legacyBasePlacementRules(GameTestHelper helper) {
        BlockPos first = new BlockPos(2, 2, 2);
        BlockPos adjacent = first.east();
        helper.setBlock(first, ModBlocks.TURRET_BASE_TIER_ONE.value());
        BlockPos absoluteAdjacent = helper.absolutePos(adjacent);
        helper.assertTrue(!ModBlocks.TURRET_BASE_TIER_FIVE.value().defaultBlockState()
                        .canSurvive(helper.getLevel(), absoluteAdjacent),
                "Adjacent turret bases bypassed the legacy no-touch placement rule");

        BlockPos axialDistanceTwo = first.east(2);
        helper.assertTrue(!ModBlocks.TURRET_BASE_TIER_FIVE.value().defaultBlockState()
                        .canSurvive(helper.getLevel(), helper.absolutePos(axialDistanceTwo)),
                "Axial distance-two bases bypassed the legacy spacing rule");

        BlockPos planarDiagonal = first.east().south();
        helper.assertTrue(!ModBlocks.TURRET_BASE_TIER_FIVE.value().defaultBlockState()
                        .canSurvive(helper.getLevel(), helper.absolutePos(planarDiagonal)),
                "Planar-diagonal bases bypassed the legacy spacing rule");

        BlockPos separated = first.east(3);
        helper.assertTrue(ModBlocks.TURRET_BASE_TIER_FIVE.value().defaultBlockState()
                        .canSurvive(helper.getLevel(), helper.absolutePos(separated)),
                "Bases beyond the legacy spacing radius were incorrectly rejected");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void ownerSneakRemovesExpanders(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(2, 2, 2);
        BlockPos powerPos = basePos.east();
        BlockPos inventoryPos = basePos.south();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_ONE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        var owner = helper.makeMockPlayer(GameType.SURVIVAL);
        base.claim(owner);
        owner.setShiftKeyDown(true);

        BlockState power = ModBlocks.EXPANDER_POWER_TIER_FIVE.value().defaultBlockState()
                .setValue(omtteam.openmodularturrets.block.BaseAttachmentBlock.FACING,
                        Direction.WEST);
        helper.setBlock(powerPos, power);
        BlockPos absolutePower = helper.absolutePos(powerPos);
        power.useWithoutItem(helper.getLevel(), owner,
                new BlockHitResult(absolutePower.getCenter(), Direction.EAST,
                        absolutePower, false));
        helper.assertTrue(helper.getBlockState(powerPos).isAir(),
                "Owner sneak-empty interaction did not remove the power expander");

        BlockState inventory = ModBlocks.EXPANDER_INV_TIER_FIVE.value().defaultBlockState()
                .setValue(omtteam.openmodularturrets.block.InventoryExpanderBlock.FACING,
                        Direction.NORTH);
        helper.setBlock(inventoryPos, inventory);
        BlockPos absoluteInventory = helper.absolutePos(inventoryPos);
        inventory.useWithoutItem(helper.getLevel(), owner,
                new BlockHitResult(absoluteInventory.getCenter(), Direction.SOUTH,
                        absoluteInventory, false));
        helper.assertTrue(helper.getBlockState(inventoryPos).isAir(),
                "Owner sneak-empty interaction did not remove the inventory expander");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void orphanedAttachmentsDropWhenBaseRemoved(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(2, 2, 2);
        BlockPos turretPos = basePos.east();
        BlockPos powerPos = basePos.south();
        BlockPos chargerPos = basePos.north();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_ONE.value());
        helper.setBlock(turretPos, ModBlocks.DISPOSABLE_ITEM_TURRET.value());
        helper.setBlock(powerPos,
                ModBlocks.EXPANDER_POWER_TIER_ONE.value().defaultBlockState()
                        .setValue(omtteam.openmodularturrets.block.BaseAttachmentBlock.FACING,
                                Direction.NORTH));
        helper.setBlock(chargerPos,
                ModBlocks.LEVER_BLOCK.value().defaultBlockState()
                        .setValue(omtteam.openmodularturrets.block.ManualChargerBlock.FACING,
                                Direction.SOUTH));

        BlockPos absoluteBase = helper.absolutePos(basePos);
        helper.getLevel().destroyBlock(absoluteBase, true);
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(turretPos)).isAir()
                        && helper.getLevel().getBlockState(helper.absolutePos(powerPos)).isAir()
                        && helper.getLevel().getBlockState(helper.absolutePos(chargerPos)).isAir(),
                "Orphaned turret, power expander or hand crank remained after base removal");

        List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(absoluteBase).inflate(2.0D));
        helper.assertTrue(drops.stream().anyMatch(entity -> entity.getItem()
                        .is(ModItems.DISPOSABLE_ITEM_TURRET.value())),
                "Orphaned turret did not drop as an item");
        helper.assertTrue(drops.stream().anyMatch(entity -> entity.getItem()
                        .is(ModItems.EXPANDER_POWER_TIER_ONE.value())),
                "Orphaned power expander did not drop as an item");
        helper.assertTrue(drops.stream().anyMatch(entity -> entity.getItem()
                        .is(ModItems.LEVER_BLOCK.value())),
                "Orphaned hand crank did not drop as an item");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void attachmentTierPlacementMatrix(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(5, 2, 5);
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_ONE.value());
        BlockPos attachmentPos = basePos.east();
        BlockPos absoluteAttachment = helper.absolutePos(attachmentPos);
        DeferredBlock<?>[] inventoryExpanders = {
                ModBlocks.EXPANDER_INV_TIER_ONE, ModBlocks.EXPANDER_INV_TIER_TWO,
                ModBlocks.EXPANDER_INV_TIER_THREE, ModBlocks.EXPANDER_INV_TIER_FOUR,
                ModBlocks.EXPANDER_INV_TIER_FIVE
        };
        DeferredBlock<?>[] powerExpanders = {
                ModBlocks.EXPANDER_POWER_TIER_ONE, ModBlocks.EXPANDER_POWER_TIER_TWO,
                ModBlocks.EXPANDER_POWER_TIER_THREE, ModBlocks.EXPANDER_POWER_TIER_FOUR,
                ModBlocks.EXPANDER_POWER_TIER_FIVE
        };
        for (DeferredBlock<?> expander : inventoryExpanders) {
            BlockState state = expander.value().defaultBlockState().setValue(
                    BlockStateProperties.FACING,
                    Direction.WEST);
            helper.assertTrue(state.canSurvive(helper.getLevel(), absoluteAttachment),
                    "Inventory expander was restricted by the tier-one base: "
                            + expander.getId());
        }
        for (DeferredBlock<?> expander : powerExpanders) {
            BlockState state = expander.value().defaultBlockState().setValue(
                    BlockStateProperties.FACING,
                    Direction.WEST);
            helper.assertTrue(state.canSurvive(helper.getLevel(), absoluteAttachment),
                    "Power expander was restricted by the tier-one base: "
                            + expander.getId());
        }
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void turretPlacementRangePromotion(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(5, 2, 5);
        BlockPos firstTurretPos = basePos.east();
        BlockPos strongerTurretPos = basePos.west();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_THREE.value());
        helper.setBlock(firstTurretPos, ModBlocks.DISPOSABLE_ITEM_TURRET.value());
        helper.setBlock(strongerTurretPos, ModBlocks.POTATO_CANNON_TURRET.value());

        BlockState strongerState = helper.getLevel().getBlockState(
                helper.absolutePos(strongerTurretPos));
        ModBlocks.POTATO_CANNON_TURRET.value().setPlacedBy(
                helper.getLevel(), helper.absolutePos(strongerTurretPos), strongerState,
                null, ItemStack.EMPTY);
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        helper.assertTrue(base.maximumRange() == TurretDefinition.POTATO.baseRange()
                        && base.configuredRange() == TurretDefinition.POTATO.baseRange(),
                "Placing a higher-range turret did not promote the legacy base range");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void inventoryExpanderLegacyRules(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(5, 2, 5);
        BlockPos expanderPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_ONE.value());
        var tierFiveState = ModBlocks.EXPANDER_INV_TIER_FIVE.value()
                .defaultBlockState().setValue(
                        BlockStateProperties.FACING,
                        Direction.WEST);
        BlockPos absoluteExpanderPos = helper.absolutePos(expanderPos);
        helper.assertTrue(tierFiveState.canSurvive(helper.getLevel(), absoluteExpanderPos),
                "Legacy inventory expanders were incorrectly restricted by base tier");

        int[] expectedLimits = {4, 8, 16, 32, 64};
        DeferredBlock<?>[] expanders = {
                ModBlocks.EXPANDER_INV_TIER_ONE,
                ModBlocks.EXPANDER_INV_TIER_TWO,
                ModBlocks.EXPANDER_INV_TIER_THREE,
                ModBlocks.EXPANDER_INV_TIER_FOUR,
                ModBlocks.EXPANDER_INV_TIER_FIVE
        };
        for (int tier = 0; tier < expanders.length; tier++) {
            helper.setBlock(expanderPos, expanders[tier].value().defaultBlockState()
                    .setValue(BlockStateProperties.FACING,
                            Direction.WEST));
            helper.assertTrue(helper.getBlockEntity(expanderPos)
                            instanceof omtteam.openmodularturrets.blockentity.InventoryExpanderBlockEntity expander
                            && expander.inventory().getSlotLimit(0) == expectedLimits[tier],
                    "Inventory expander tier " + (tier + 1) + " has the wrong slot capacity");
        }
        var base = (TurretBaseBlockEntity) helper.getBlockEntity(basePos);
        var expander = (omtteam.openmodularturrets.blockentity.InventoryExpanderBlockEntity)
                helper.getBlockEntity(expanderPos);
        expander.inventory().setStackInSlot(0, new ItemStack(Items.POTATO, 2));
        base.energy().receiveEnergy(10_000, false);
        helper.assertTrue(base.consumeResourcesForVolley(TurretDefinition.POTATO, 1.0D)
                        .isPresent()
                        && expander.inventory().getStackInSlot(0).getCount() == 1,
                "Turret base did not consume ammunition from its inventory expander");
        helper.succeed();
    }
}
