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

@GameTestHolder("openmodularturrets")
public final class OpenModularTurretsGameTests {
    private OpenModularTurretsGameTests() {
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
    public static void legacyConfigDefaults(GameTestHelper helper) {
        helper.assertTrue(ModServerConfig.SPEC.isLoaded(),
                "SERVER config was not loaded before gameplay tests");

        int[] baseCapacities = {500, 50_000, 150_000, 500_000, 10_000_000};
        int[] baseReceive = {50, 100, 1_000, 2_500, 50_000};
        int[] baseTurrets = {1, 1, 2, 3, 4};
        float[] baseHardness = {20.0F, 30.0F, 40.0F, 50.0F, 60.0F};
        float[] baseResistance = {5.0F, 10.0F, 15.0F, 20.0F, 25.0F};
        int[] expanderCapacities = {2_500, 25_000, 75_000, 250_000, 5_000_000};
        for (BaseTier tier : BaseTier.values()) {
            int index = tier.ordinal();
            helper.assertTrue(tier.energyCapacity() == baseCapacities[index]
                            && tier.maxReceive() == baseReceive[index]
                            && tier.maxTurrets() == baseTurrets[index]
                            && ModServerConfig.base(tier).hardness() == baseHardness[index]
                            && ModServerConfig.base(tier).blastResistance()
                                    == baseResistance[index],
                    "Base tier " + tier.level() + " config defaults drifted");
            helper.assertTrue(ModServerConfig.powerExpanderCapacity(tier)
                            == expanderCapacities[index],
                    "Power expander tier " + tier.level() + " config default drifted");
        }

        int[] ranges = {10, 15, 18, 12, 18, 20, 30, 20, 25, 30, 20};
        int[] intervals = {25, 35, 8, 25, 40, 25, 30, 100, 10, 100, 60};
        int[] energy = {2, 10, 100, 250, 3_000, 5_000, 5_000, 15_000,
                8_000, 25_000, 40_000};
        int[] simultaneous = {4, 4, 4, 4, 3, 4, 3, 1, 4, 2, 1};
        for (TurretDefinition definition : TurretDefinition.values()) {
            int index = definition.ordinal();
            var values = ModServerConfig.turret(definition);
            helper.assertTrue(values.baseRange() == ranges[index]
                            && values.fireInterval() == intervals[index]
                            && values.energyCost() == energy[index]
                            && values.maxSimultaneous() == simultaneous[index]
                            && values.accuracyUpgrade() == 0.2D
                            && values.efficiencyUpgrade() == 0.08D
                            && values.recyclerNegateChance() == 0.10D,
                    "Turret config defaults drifted for " + definition.id());
        }

        helper.assertTrue(TurretAddonRules.solarGeneration() == 10
                        && TurretAddonRules.reactorDustGeneration() == 1_600
                        && TurretAddonRules.reactorBlockGeneration() == 14_400,
                "Addon generation config defaults drifted");
        helper.assertTrue(ModServerConfig.requireAmmo()
                        && ModServerConfig.allowBaseCamouflage()
                        && !ModServerConfig.concealWithoutAddon()
                        && ModServerConfig.targetSearchTicks() == 10
                        && ModServerConfig.warningMessage()
                        && ModServerConfig.warningSound()
                        && ModServerConfig.warningDistance() == 5
                        && ModServerConfig.turretSoundVolume() == 4.0F
                        && ModServerConfig.turretKillsDropLoot()
                        && ModServerConfig.lootAddonsOverride()
                        && ModServerConfig.globalTargetPlayers()
                        && ModServerConfig.globalTargetNeutrals()
                        && ModServerConfig.globalTargetHostiles()
                        && !ModServerConfig.damageTrustedPlayers()
                        && !ModServerConfig.canOpAccessOwnedBlocks()
                        && !ModServerConfig.offlineModeSupport()
                        && !ModServerConfig.baseBreakable()
                        && !ModServerConfig.attachmentsBreakable()
                        && !ModServerConfig.rocketsHome()
                        && !ModServerConfig.rocketsHurtDragon()
                        && !ModServerConfig.rocketsDestroyBlocks()
                        && !ModServerConfig.grenadesDestroyBlocks()
                        && !ModServerConfig.railgunDestroysBlocks(),
                "Legacy behavior-switch config defaults drifted");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void legacyDurabilityRules(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        BlockPos absolute = helper.absolutePos(relative);
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockState base = ModBlocks.TURRET_BASE_TIER_ONE.value().defaultBlockState();
        BlockState head = ModBlocks.DISPOSABLE_ITEM_TURRET.value().defaultBlockState();
        BlockState attachment = ModBlocks.EXPANDER_POWER_TIER_ONE.value().defaultBlockState();
        BlockState inventoryAttachment =
                ModBlocks.EXPANDER_INV_TIER_ONE.value().defaultBlockState();
        BlockState charger = ModBlocks.LEVER_BLOCK.value().defaultBlockState();

        helper.assertTrue(base.getDestroyProgress(player, helper.getLevel(), absolute) == 0.0F,
                "Default legacy base became directly breakable");
        helper.assertTrue(head.getDestroyProgress(player, helper.getLevel(), absolute) == 0.0F,
                "Legacy turret head became directly breakable");
        helper.assertTrue(attachment.getDestroyProgress(player, helper.getLevel(), absolute)
                        == 0.0F,
                "Default legacy attachment became directly breakable");
        helper.assertTrue(inventoryAttachment.getDestroyProgress(
                        player, helper.getLevel(), absolute) == 0.0F,
                "Default legacy inventory expander became directly breakable");
        helper.assertTrue(charger.getDestroyProgress(player, helper.getLevel(), absolute) > 0.0F,
                "Legacy manual charger became unbreakable");
        helper.assertTrue(ModBlocks.TURRET_BASE_TIER_ONE.value().getExplosionResistance(
                        base, helper.getLevel(), absolute, null) == 6_000_000.0F
                        && ModBlocks.DISPOSABLE_ITEM_TURRET.value().getExplosionResistance(
                                head, helper.getLevel(), absolute, null) == 6_000_000.0F
                        && ModBlocks.EXPANDER_POWER_TIER_ONE.value().getExplosionResistance(
                                attachment, helper.getLevel(), absolute, null) == 3.0F,
                "Legacy explosion-resistance policy drifted");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void upgradeFormulaRules(GameTestHelper helper) {
        helper.assertTrue(TurretUpgradeRules.fireInterval(
                        TurretDefinition.MACHINE_GUN, 3) == 7,
                "Machine-gun fire-rate upgrade formula drifted");
        helper.assertTrue(TurretUpgradeRules.fireInterval(
                        TurretDefinition.LASER, 4) == 7,
                "Laser fire-rate coefficient drifted");
        helper.assertTrue(TurretUpgradeRules.fireInterval(
                        TurretDefinition.RAIL_GUN, 2) == 72,
                "Rail-gun fire-rate coefficient drifted");
        helper.assertTrue(TurretUpgradeRules.energyCost(
                        TurretDefinition.MACHINE_GUN, 3, 2) == 228,
                "Efficiency/scatter energy formula drifted");
        helper.assertTrue(TurretUpgradeRules.maximumRange(
                        TurretDefinition.ROCKET, 3) == 36,
                "Rocket range upgrade formula drifted");
        helper.assertTrue(TurretUpgradeRules.maximumRange(
                        TurretDefinition.PLASMA, 3) == 23,
                "Plasma range coefficient drifted");
        double deviation = TurretUpgradeRules.accuracyDeviation(
                TurretDefinition.MACHINE_GUN, 2, 3);
        helper.assertTrue(Math.abs(deviation - 23.543583D) < 0.0001D,
                "Accuracy/scatter deviation formula drifted: " + deviation);

        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        base.inventory().setStackInSlot(TurretBaseBlockEntity.UPGRADE_SLOT_START,
                new ItemStack(ModItems.UPGRADE_SCATTER_SHOT.value(), 2));
        base.inventory().setStackInSlot(TurretBaseBlockEntity.UPGRADE_SLOT_START + 1,
                new ItemStack(ModItems.UPGRADE_EFFICIENCY.value(), 3));
        base.inventory().setStackInSlot(0, new ItemStack(ModItems.AMMO_BULLET.value(), 2));
        base.energy().receiveEnergy(500, false);
        int energyBefore = base.energy().getEnergyStored();
        helper.assertTrue(base.consumeResourcesForVolley(TurretDefinition.MACHINE_GUN)
                        .isEmpty(),
                "Underfunded scatter volley consumed partial resources");
        helper.assertTrue(base.energy().getEnergyStored() == energyBefore
                        && base.inventory().getStackInSlot(0).getCount() == 2
                        && base.shotsFired() == 0,
                "Rejected scatter volley mutated server-owned resources");

        base.inventory().setStackInSlot(0, new ItemStack(ModItems.AMMO_BULLET.value(), 3));
        var volley = base.consumeResourcesForVolley(TurretDefinition.MACHINE_GUN);
        helper.assertTrue(volley.isPresent() && volley.get().projectileCount() == 3,
                "Scatter level two did not create a three-shot volley");
        helper.assertTrue(base.energy().getEnergyStored() == energyBefore - 228
                        && base.inventory().getStackInSlot(0).isEmpty()
                        && base.shotsFired() == 3,
                "Accepted scatter volley did not consume exact resources");
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
    public static void everyBaseTierHonorsTurretCapacity(GameTestHelper helper) {
        BlockPos relative = new BlockPos(5, 2, 5);
        Direction[] slots = Direction.values();
        List<Block> baseBlocks = List.of(
                ModBlocks.TURRET_BASE_TIER_ONE.value(),
                ModBlocks.TURRET_BASE_TIER_TWO.value(),
                ModBlocks.TURRET_BASE_TIER_THREE.value(),
                ModBlocks.TURRET_BASE_TIER_FOUR.value(),
                ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretDefinition definition = TurretDefinition.POTATO;
        Block turretBlock = ModBlocks.POTATO_CANNON_TURRET.value();

        for (int index = 0; index < BaseTier.values().length; index++) {
            helper.setBlock(relative, Blocks.AIR);
            for (Direction direction : slots) {
                helper.setBlock(relative.relative(direction), Blocks.AIR);
            }
            helper.setBlock(relative, baseBlocks.get(index));
            TurretBaseBlockEntity base = helper.getBlockEntity(relative);
            BaseTier tier = BaseTier.values()[index];
            helper.assertTrue(base.tier() == tier,
                    "Base block did not create the expected " + tier + " block entity");

            int capacity = tier.maxTurrets();
            for (int slot = 0; slot < capacity; slot++) {
                BlockPos candidate = relative.relative(slots[slot]);
                BlockPos absoluteCandidate = helper.absolutePos(candidate);
                helper.assertTrue(base.canSupportTurret(absoluteCandidate, definition),
                        tier + " rejected turret " + (slot + 1)
                                + " before reaching its capacity of " + capacity);
                helper.setBlock(candidate, turretBlock);
                helper.assertTrue(helper.getLevel().getBlockState(absoluteCandidate)
                                .is(turretBlock),
                        tier + " did not retain turret " + (slot + 1)
                                + " at an adjacent slot");
            }
            if (capacity < slots.length) {
                BlockPos overflow = relative.relative(slots[capacity]);
                helper.assertTrue(!base.canSupportTurret(
                                helper.absolutePos(overflow), definition),
                        tier + " accepted a turret beyond its capacity of " + capacity);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void capabilityAmmoBoundary(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        BlockPos absolute = helper.absolutePos(relative);
        IItemHandler handler = helper.getLevel().getCapability(
                Capabilities.ItemHandler.BLOCK, absolute, Direction.UP);
        helper.assertTrue(handler != null, "Turret base did not expose an item capability");
        helper.assertTrue(handler.getSlots() == TurretBaseBlockEntity.AMMO_SLOT_COUNT,
                "Automation can observe non-ammunition slots");
        ItemStack bullet = new ItemStack(ModItems.AMMO_BULLET.value());
        helper.assertTrue(handler.insertItem(0, bullet, false).isEmpty(),
                "Automation could not insert tagged ammunition");
        ItemStack upgrade = new ItemStack(ModItems.UPGRADE_RANGE.value());
        helper.assertTrue(handler.insertItem(0, upgrade, false).getCount() == 1,
                "Automation inserted an upgrade through an ammunition slot");
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        helper.assertTrue(base.inventory()
                        .getStackInSlot(TurretBaseBlockEntity.UPGRADE_SLOT_START).isEmpty(),
                "Capability mutated a hidden upgrade slot");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void networkAuthority(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_ONE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        var owner = helper.makeMockPlayer(GameType.CREATIVE);
        var stranger = helper.makeMockPlayer(GameType.CREATIVE);
        base.claim(owner.getUUID());
        helper.assertTrue(base.accessFor(owner) == AccessLevel.ADMIN,
                "Owner must have administrative access");
        helper.assertTrue(base.accessFor(stranger) == AccessLevel.NONE,
                "Untrusted player acquired authority");
        helper.assertTrue(!base.applyProfile(stranger, MemoryCardProfile.DEFAULT),
                "Untrusted profile mutation was accepted");
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
    public static void baseCommandAuthorityAndBounds(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 2, 2);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(relative.east(), ModBlocks.MACHINE_GUN_TURRET.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        FakePlayer owner = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "base_command_owner"));
        FakePlayer usePlayer = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "base_command_use"));
        FakePlayer viewPlayer = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "base_command_view"));
        FakePlayer stranger = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "base_command_none"));
        Vec3 baseCenter = base.getBlockPos().getCenter();
        owner.setPos(baseCenter);
        usePlayer.setPos(baseCenter);
        viewPlayer.setPos(baseCenter);
        stranger.setPos(baseCenter);
        base.claim(owner.getUUID());
        helper.assertTrue(base.setLocalTrust(owner, usePlayer.getUUID(), AccessLevel.USE)
                        && base.setLocalTrust(owner, viewPlayer.getUUID(), AccessLevel.VIEW),
                "Unable to create the base command permission fixture");

        TurretBaseMenu ownerMenu = new TurretBaseMenu(21, owner.getInventory(), base);
        owner.containerMenu = ownerMenu;
        BlockPos pos = base.getBlockPos();
        helper.assertTrue(!BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, 999, 0))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(22, pos, BaseCommand.SET_MODE.id(),
                                BaseMode.ALWAYS_ON.id()))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos.above(), BaseCommand.SET_MODE.id(),
                                BaseMode.ALWAYS_ON.id())),
                "Unknown or mismatched base command session was accepted");

        helper.assertTrue(!BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.SET_RANGE.id(), 0))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.SET_RANGE.id(),
                                base.maximumRange() + 1))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.SET_TARGET_FLAGS.id(), 8))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.SET_MULTI_TARGET.id(), 2))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.SET_MODE.id(), 4))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.ADJUST_RANGE.id(), 0))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos,
                                BaseCommand.TOGGLE_TARGET_FLAG.id(), 3))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.CYCLE_MODE.id(), 1))
                        && !BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos,
                                BaseCommand.TOGGLE_MULTI_TARGET.id(), 1)),
                "A base command accepted an out-of-bounds operand");

        TurretBaseMenu useMenu = new TurretBaseMenu(22, usePlayer.getInventory(), base);
        usePlayer.containerMenu = useMenu;
        helper.assertTrue(BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos, BaseCommand.SET_RANGE.id(), 1))
                        && BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos, BaseCommand.SET_TARGET_FLAGS.id(), 6))
                        && BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos, BaseCommand.SET_MULTI_TARGET.id(), 1))
                        && base.configuredRange() == 1
                        && !base.attackHostile() && base.attackNeutral()
                        && base.attackPlayers() && base.multiTargeting(),
                "USE access could not apply the three legacy setting commands");
        helper.assertTrue(BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos, BaseCommand.ADJUST_RANGE.id(), 1))
                        && BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos, BaseCommand.ADJUST_RANGE.id(), 1))
                        && BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos,
                                BaseCommand.TOGGLE_TARGET_FLAG.id(), 1))
                        && BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos,
                                BaseCommand.TOGGLE_TARGET_FLAG.id(), 2))
                        && BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos,
                                BaseCommand.TOGGLE_MULTI_TARGET.id(), 0))
                        && BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos,
                                BaseCommand.TOGGLE_MULTI_TARGET.id(), 0))
                        && base.configuredRange() == 3
                        && base.attackHostile() && !base.attackNeutral()
                        && base.attackPlayers() && base.multiTargeting(),
                "Sequential relative commands collapsed against stale client state");
        helper.assertTrue(!BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos, BaseCommand.SET_MODE.id(),
                                BaseMode.ALWAYS_ON.id()))
                        && !BaseCommandService.apply(usePlayer,
                        new BaseCommandPayload(22, pos, BaseCommand.DROP_TURRETS.id(), 0)),
                "USE access acquired an administrative base command");

        TurretBaseMenu viewMenu = new TurretBaseMenu(23, viewPlayer.getInventory(), base);
        viewPlayer.containerMenu = viewMenu;
        TurretBaseMenu strangerMenu = new TurretBaseMenu(24, stranger.getInventory(), base);
        stranger.containerMenu = strangerMenu;
        helper.assertTrue(!BaseCommandService.apply(viewPlayer,
                        new BaseCommandPayload(23, pos, BaseCommand.SET_RANGE.id(), 2))
                        && !BaseCommandService.apply(stranger,
                        new BaseCommandPayload(24, pos, BaseCommand.SET_RANGE.id(), 2)),
                "VIEW or NONE access mutated the base");

        owner.containerMenu = ownerMenu;
        helper.assertTrue(BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.SET_MODE.id(),
                                BaseMode.NONINVERTED.id()))
                        && BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.CYCLE_MODE.id(), 0))
                        && BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.CYCLE_MODE.id(), 0))
                        && base.mode() == BaseMode.ALWAYS_OFF,
                "ADMIN mode cycles collapsed against stale client state");
        owner.setPos(baseCenter.add(9.0D, 0.0D, 0.0D));
        helper.assertTrue(!BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.SET_MODE.id(),
                                BaseMode.ALWAYS_ON.id())),
                "A base command was accepted beyond the eight-block session radius");
        owner.setPos(baseCenter);

        helper.assertTrue(BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.DROP_TURRETS.id(), 0))
                        && helper.getLevel().getBlockState(pos.east()).isAir(),
                "ADMIN drop-turrets command did not remove the adjacent turret");
        helper.assertTrue(BaseCommandService.apply(owner,
                        new BaseCommandPayload(21, pos, BaseCommand.DROP_BASE.id(), 0))
                        && helper.getLevel().getBlockState(pos).isAir(),
                "ADMIN drop-base command did not remove the base");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void clientTrustSnapshotReducerContract(GameTestHelper helper) {
        int containerId = 17;
        BlockPos pos = new BlockPos(4, 5, 6);
        UUID owner = UUID.randomUUID();
        UUID trusted = UUID.randomUUID();
        ClientTrustSnapshot.Session session =
                new ClientTrustSnapshot.Session(containerId, pos, owner);
        ClientTrustSnapshot.State initial = ClientTrustSnapshot.begin(session);
        TrustSnapshotPayload.Entry first =
                new TrustSnapshotPayload.Entry(trusted, "first", AccessLevel.VIEW.id());
        TrustSnapshotPayload.Entry stale =
                new TrustSnapshotPayload.Entry(trusted, "stale", AccessLevel.USE.id());

        ClientTrustSnapshot.State accepted = ClientTrustSnapshot.reduce(initial, session,
                new TrustSnapshotPayload(containerId, pos, owner, TrustScope.LOCAL.id(), 2L,
                        List.of(first)));
        helper.assertTrue(accepted.snapshot(TrustScope.LOCAL).revision() == 2L
                        && accepted.snapshot(TrustScope.LOCAL).entries().equals(List.of(first)),
                "Reducer did not accept the current session's newer local snapshot");

        ClientTrustSnapshot.State afterStale = ClientTrustSnapshot.reduce(accepted, session,
                new TrustSnapshotPayload(containerId, pos, owner, TrustScope.LOCAL.id(), 1L,
                        List.of(stale)));
        ClientTrustSnapshot.State afterSameRevision = ClientTrustSnapshot.reduce(
                afterStale, session,
                new TrustSnapshotPayload(containerId, pos, owner, TrustScope.LOCAL.id(), 2L,
                        List.of(stale)));
        helper.assertTrue(afterStale.equals(accepted) && afterSameRevision.equals(accepted),
                "Reducer replaced a snapshot with an old or duplicate revision");

        ClientTrustSnapshot.State afterWrongSession = ClientTrustSnapshot.reduce(
                afterSameRevision, session,
                new TrustSnapshotPayload(containerId + 1, pos, owner,
                        TrustScope.GLOBAL.id(), 3L, List.of(stale)));
        helper.assertTrue(afterWrongSession.equals(accepted)
                        && afterWrongSession.snapshot(TrustScope.GLOBAL).entries().isEmpty(),
                "Reducer accepted a snapshot for another menu session");
        boolean rejectedUnknownScope = false;
        try {
            new TrustSnapshotPayload(
                    containerId, pos, owner, 99, 3L, List.of(stale));
        } catch (IllegalArgumentException expected) {
            rejectedUnknownScope = true;
        }
        helper.assertTrue(rejectedUnknownScope,
                "Trust snapshot payload accepted an unknown scope before reduction");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void menuWideContainerDataRoundTrip(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity original = helper.getBlockEntity(relative);
        var owner = helper.makeMockPlayer(GameType.CREATIVE);
        original.claim(owner.getUUID());

        int energy = 0x0098_967F;
        long kills = 0x7FFF_8000_FFFF_8001L;
        long playerKills = 0x0001_8000_0000_FFFFL;
        long shots = Long.MAX_VALUE;
        CompoundTag saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
        saved.putInt("energy", energy);
        saved.putLong("kills", kills);
        saved.putLong("player_kills", playerKills);
        saved.putLong("shots_fired", shots);
        BlockEntity loaded = BlockEntity.loadStatic(original.getBlockPos(),
                original.getBlockState(), saved, helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof TurretBaseBlockEntity,
                "Unable to construct a real menu data source from serialized base state");

        TurretBaseMenu menu = new TurretBaseMenu(
                18, owner.getInventory(), (TurretBaseBlockEntity) loaded);
        helper.assertTrue(menu.energy() == energy
                        && menu.maximumEnergy() == BaseTier.FIVE.energyCapacity(),
                "Signed 16-bit words did not reconstruct 32-bit energy values");
        helper.assertTrue(menu.kills() == kills
                        && menu.playerKills() == playerKills
                        && menu.shotsFired() == shots,
                "Signed 16-bit words did not reconstruct 64-bit statistic values");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void trustScopeMembershipAndSecurityRevision(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        var owner = helper.makeMockPlayer(GameType.CREATIVE);
        var target = helper.makeMockPlayer(GameType.SURVIVAL);
        base.claim(owner.getUUID());

        long localRevision = base.localTrustRevision();
        helper.assertTrue(base.setLocalTrust(
                        owner, target.getUUID(), "target", AccessLevel.NONE)
                        && base.hasLocalTrust(target.getUUID())
                        && base.localTrustRevision() == localRevision + 1L
                        && base.accessFor(target) == AccessLevel.NONE
                        && !base.mayTarget(target),
                "A local NONE entry was not retained as trusted membership");
        helper.assertTrue(base.removeLocalTrust(owner, target.getUUID())
                        && !base.hasLocalTrust(target.getUUID())
                        && base.localTrustRevision() == localRevision + 2L
                        && base.mayTarget(target),
                "Explicit local trust removal did not remove NONE membership");

        SecuritySavedData security = SecuritySavedData.get(helper.getLevel());
        UUID securityOwner = UUID.randomUUID();
        UUID securityTarget = UUID.randomUUID();
        long revision = security.revision(securityOwner);
        helper.assertTrue(security.setAccess(
                        securityOwner, securityTarget, "isolated", AccessLevel.VIEW)
                        && security.revision(securityOwner) == revision + 1L,
                "A global trust mutation did not increment its owner revision");
        helper.assertTrue(!security.setAccess(
                        securityOwner, securityTarget, "isolated", AccessLevel.VIEW)
                        && security.revision(securityOwner) == revision + 1L,
                "A no-op global trust mutation changed its revision");
        helper.assertTrue(security.removeAccess(securityOwner, securityTarget)
                        && security.revision(securityOwner) == revision + 2L
                        && !security.hasEntry(securityOwner, securityTarget),
                "Global REMOVE did not remove the entry and increment revision");
        helper.assertTrue(!security.removeAccess(securityOwner, securityTarget)
                        && security.revision(securityOwner) == revision + 2L,
                "A no-op global REMOVE changed its revision");

        helper.assertTrue(base.setLocalTrust(
                        owner, target.getUUID(), "target", AccessLevel.VIEW),
                "Unable to restore local trust for scope selection test");
        helper.assertTrue(security.setAccess(
                        owner.getUUID(), target.getUUID(), "target", AccessLevel.USE),
                "Unable to establish isolated global trust for scope selection test");
        helper.assertTrue(base.accessFor(target) == AccessLevel.VIEW
                        && base.setUseGlobalTrust(owner, true)
                        && base.accessFor(target) == AccessLevel.USE
                        && base.hasLocalTrust(target.getUUID()),
                "Switching to global trust did not select global access independently");
        helper.assertTrue(base.setUseGlobalTrust(owner, false)
                        && base.accessFor(target) == AccessLevel.VIEW,
                "Switching back to local trust did not restore local access");
        security.removeAccess(owner.getUUID(), target.getUUID());
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void memoryCardSneakUseClearsOnlyProfile(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        ItemStack card = new ItemStack(ModItems.MEMORY_CARD.value());
        card.set(ModDataComponents.MEMORY_CARD_PROFILE.value(), MemoryCardProfile.DEFAULT);
        Component customName = Component.literal("Keep me");
        card.set(DataComponents.CUSTOM_NAME, customName);
        player.setItemInHand(InteractionHand.MAIN_HAND, card);
        player.setShiftKeyDown(true);

        ModItems.MEMORY_CARD.value().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

        helper.assertTrue(card.get(ModDataComponents.MEMORY_CARD_PROFILE.value()) == null,
                "Sneak-use in air did not clear the memory-card profile");
        helper.assertTrue(customName.equals(card.get(DataComponents.CUSTOM_NAME)),
                "Clearing a memory card removed an unrelated Data Component");

        card.set(ModDataComponents.MEMORY_CARD_PROFILE.value(), MemoryCardProfile.DEFAULT);
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(relative);
        var hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
        ModItems.MEMORY_CARD.value().useOn(
                new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, card, hit));

        helper.assertTrue(card.get(ModDataComponents.MEMORY_CARD_PROFILE.value()) == null,
                "Sneak-use on a non-base block did not clear the memory-card profile");
        helper.assertTrue(customName.equals(card.get(DataComponents.CUSTOM_NAME)),
                "Non-base clearing removed an unrelated Data Component");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void survivalRecipeContract(GameTestHelper helper) {
        Set<ResourceLocation> recipes = helper.getLevel().getRecipeManager().getRecipeIds()
                .filter(id -> id.getNamespace().equals(OpenModularTurrets.MOD_ID))
                .collect(Collectors.toSet());
        helper.assertTrue(recipes.size() == 61,
                "Expected 61 dependency-free OMT recipes, found " + recipes.size());
        for (String excluded : Set.of("addon_potentia", "ammo_fake_disposable",
                "throwable_bullet", "throwable_grenade", "plasma_turret")) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    OpenModularTurrets.MOD_ID, excluded);
            helper.assertTrue(!recipes.contains(id),
                    "Creative-only item unexpectedly received a recipe: " + id);
        }
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void soundRegistryContract(GameTestHelper helper) {
        helper.assertTrue(ModSounds.ALL.size() == 18,
                "Expected exactly 18 legacy SoundEvent registrations");
        Set<ResourceLocation> ids = ModSounds.ALL.stream()
                .map(holder -> holder.getId())
                .collect(Collectors.toSet());
        helper.assertTrue(ids.size() == 18,
                "SoundEvent registry contains duplicate ids");
        helper.assertTrue(ModSounds.ALL.stream().allMatch(holder -> holder.isBound()),
                "At least one SoundEvent DeferredHolder is unbound");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 40)
    public static void launchSoundDispatchContract(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(4, 1, 5);
        BlockPos headPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_TWO.value());
        helper.setBlock(headPos, ModBlocks.MACHINE_GUN_TURRET.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        base.setActive(true);
        base.setRange(TurretDefinition.MACHINE_GUN.baseRange());
        base.setTargetFlags(true, false, false);
        base.energy().receiveEnergy(100, false);
        base.inventory().setStackInSlot(0, new ItemStack(ModItems.AMMO_BULLET.value(), 2));

        List<PlayLevelSoundEvent.AtPosition> launches = new ArrayList<>();
        Vec3 launchCenter = Vec3.atCenterOf(helper.absolutePos(headPos));
        Consumer<PlayLevelSoundEvent.AtPosition> recorder = event -> {
            if (event.getLevel() == helper.getLevel()
                    && event.getSound() != null
                    && event.getSound().value() == ModSounds.MACHINE_GUN.value()
                    && event.getPosition().distanceToSqr(launchCenter) < 0.25D) {
                launches.add(event);
                base.setActive(false);
            }
        };
        NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, recorder);

        var target = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 1.0D, 5.5D));
        target.setNoAi(true);
        target.setNoGravity(true);
        helper.runAfterDelay(15L, () -> {
            try {
                helper.assertTrue(launches.size() == 1,
                        "One machine-gun volley dispatched " + launches.size()
                                + " launch sounds");
                PlayLevelSoundEvent.AtPosition launch = launches.getFirst();
                helper.assertTrue(!launch.getLevel().isClientSide
                                && launch.getSource() == SoundSource.BLOCKS
                                && Math.abs(launch.getOriginalVolume()
                                        - ModServerConfig.turretSoundVolume()) < 0.0001F
                                && launch.getOriginalPitch() >= 0.5F
                                && launch.getOriginalPitch() < 1.5F,
                        "Launch sound did not preserve the legacy server/category/volume/pitch");
                helper.succeed();
            } finally {
                NeoForge.EVENT_BUS.unregister(recorder);
            }
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void bulletImpactSoundDispatchContract(GameTestHelper helper) {
        BlockPos relative = new BlockPos(4, 1, 5);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        base.setTargetFlags(true, false, false);
        var target = helper.spawn(EntityType.ZOMBIE, new Vec3(6.5D, 1.0D, 5.5D));
        target.setNoAi(true);
        target.setNoGravity(true);

        helper.runAfterDelay(2L, () -> {
            List<PlayLevelSoundEvent.AtPosition> impacts = new ArrayList<>();
            Vec3 fixtureCenter = Vec3.atCenterOf(base.getBlockPos()).add(0.0D, 1.0D, 0.0D);
            Consumer<PlayLevelSoundEvent.AtPosition> recorder = event -> {
                if (event.getLevel() == helper.getLevel()
                        && event.getSound() != null
                        && event.getSound().value() == ModSounds.BULLET_HIT.value()
                        && event.getPosition().distanceToSqr(fixtureCenter) < 16.0D) {
                    impacts.add(event);
                }
            };
            NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, recorder);

            TurretProjectileEntity projectile = TurretProjectileEntity.create(helper.getLevel(),
                    ProjectileKind.BULLET, base.getBlockPos(), target,
                    TurretDefinition.MACHINE_GUN, 0, base.attackContext(),
                    new ItemStack(ModItems.AMMO_BULLET.value()));
            Vec3 targetCenter = target.getBoundingBox().getCenter();
            projectile.setPos(targetCenter.x - 1.0D, targetCenter.y, targetCenter.z);
            projectile.shoot(1.0D, 0.0D, 0.0D, 1.0F, 0.0F);
            helper.getLevel().addFreshEntity(projectile);
            helper.runAfterDelay(5L, () -> {
                try {
                    helper.assertTrue(impacts.size() == 1,
                            "One direct bullet impact dispatched " + impacts.size()
                                    + " impact sounds");
                    PlayLevelSoundEvent.AtPosition impact = impacts.getFirst();
                    helper.assertTrue(!impact.getLevel().isClientSide
                                    && impact.getSource() == SoundSource.AMBIENT
                                    && Math.abs(impact.getOriginalVolume()
                                            - ModServerConfig.turretSoundVolume()) < 0.0001F
                                    && impact.getOriginalPitch() >= 0.5F
                                    && impact.getOriginalPitch() < 1.5F,
                            "Bullet impact sound did not preserve the legacy server/category/volume/pitch");
                    helper.succeed();
                } finally {
                    NeoForge.EVENT_BUS.unregister(recorder);
                }
            });
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void targetingBudget(GameTestHelper helper) {
        int scanningThisTick = 0;
        for (int i = 0; i < 100; i++) {
            if (TurretHeadBlockEntity.shouldScan(0L, new BlockPos(i, 0, i * 3))) {
                scanningThisTick++;
            }
        }
        helper.assertTrue(scanningThisTick <= 20,
                "Position staggering scheduled too many target searches: " + scanningThisTick);
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 40)
    public static void potatoTurretAcquiresVisibleHostileAndFires(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(4, 1, 5);
        BlockPos headPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_TWO.value());
        helper.setBlock(headPos, ModBlocks.POTATO_CANNON_TURRET.value());

        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        base.setActive(true);
        base.setRange(TurretDefinition.POTATO.baseRange());
        base.setTargetFlags(true, false, false);
        base.energy().receiveEnergy(100, false);
        base.inventory().setStackInSlot(0, new ItemStack(Items.POTATO, 2));

        var target = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 1.0D, 5.5D));
        target.setNoAi(true);
        target.setNoGravity(true);

        int energyBefore = base.energy().getEnergyStored();
        float healthBefore = target.getHealth();
        helper.runAfterDelay(12L, () -> {
            helper.assertTrue(base.shotsFired() > 0,
                    "A funded potato turret did not acquire and fire at a visible hostile");
            helper.assertTrue(base.energy().getEnergyStored()
                            == energyBefore - TurretDefinition.POTATO.energyCost(),
                    "Potato volley did not consume its authoritative energy cost");
            helper.assertTrue(base.inventory().getStackInSlot(0).getCount() == 1,
                    "Potato volley did not consume exactly one tagged ammunition item");
            helper.assertTrue(target.getHealth() < healthBefore,
                    "The fired potato projectile was blocked by its own turret head");
            helper.succeed();
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 40)
    public static void targetingSkipsBlockedPriorityCandidate(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(4, 1, 5);
        BlockPos headPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_TWO.value());
        helper.setBlock(headPos, ModBlocks.POTATO_CANNON_TURRET.value());
        // The nearer golem wins the distance score but is hidden by this block.
        helper.setBlock(new BlockPos(7, 2, 5), Blocks.STONE);

        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        TurretHeadBlockEntity head = helper.getBlockEntity(headPos);
        base.setActive(true);
        base.setRange(TurretDefinition.POTATO.baseRange());
        base.setTargetFlags(false, true, false);
        base.energy().receiveEnergy(100, false);
        base.inventory().setStackInSlot(0, new ItemStack(Items.POTATO, 2));
        head.setPriorityProfile(new TargetPriorityProfile(0, 0, -100, 0, 0));

        var blocked = helper.spawn(EntityType.IRON_GOLEM, new Vec3(8.5D, 1.0D, 5.5D));
        var visible = helper.spawn(EntityType.IRON_GOLEM, new Vec3(8.5D, 1.0D, 8.5D));
        blocked.setNoAi(true);
        blocked.setNoGravity(true);
        visible.setNoAi(true);
        visible.setNoGravity(true);

        helper.runAfterDelay(15L, () -> {
            helper.assertTrue(head.targets(visible) && !head.targets(blocked),
                    "Target search did not fall through to a visible lower-priority candidate");
            helper.succeed();
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 40)
    public static void scatterVolleyMeasuredSpread(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(4, 1, 5);
        BlockPos headPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(headPos, ModBlocks.MACHINE_GUN_TURRET.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        TurretHeadBlockEntity head = helper.getBlockEntity(headPos);
        base.setActive(true);
        base.setRange(TurretDefinition.MACHINE_GUN.baseRange());
        base.setTargetFlags(true, false, false);
        while (base.energy().getEnergyStored() < 5_000) {
            base.energy().receiveEnergy(1_000, false);
        }
        base.inventory().setStackInSlot(0, new ItemStack(ModItems.AMMO_BULLET.value(), 16));
        base.inventory().setStackInSlot(TurretBaseBlockEntity.UPGRADE_SLOT_START,
                new ItemStack(ModItems.UPGRADE_SCATTER_SHOT.value(), 2));

        var target = helper.spawn(EntityType.ZOMBIE, new Vec3(9.5D, 1.0D, 5.5D));
        target.setNoAi(true);
        target.setNoGravity(true);
        head.applyNetworkAim(0.0F, 0.0F, target.getId());

        float expectedInaccuracy = base.projectileInaccuracy(TurretDefinition.MACHINE_GUN);
        Vec3 muzzle = Vec3.atCenterOf(helper.absolutePos(headPos));
        helper.runAfterDelay(1L, () -> {
            helper.assertTrue(base.shotsFired() > 0,
                    "A funded machine-gun volley did not fire");
            Vec3 ideal = target.getEyePosition()
                    .subtract(Vec3.atCenterOf(helper.absolutePos(headPos))).normalize();
            var projectiles = helper.getLevel().getEntitiesOfClass(
                    TurretProjectileEntity.class,
                    new net.minecraft.world.phys.AABB(helper.absolutePos(basePos))
                            .inflate(8.0D),
                    projectile -> helper.absolutePos(basePos).equals(
                            projectile.sourceBasePos()));
            helper.assertTrue(projectiles.size() >= 3,
                    "Scatter volley did not spawn three projectiles: " + projectiles.size());
            double maxAngle = 0.0D;
            for (TurretProjectileEntity projectile : projectiles) {
                Vec3 motion = projectile.getDeltaMovement();
                if (motion.lengthSqr() < 1.0E-8D) {
                    continue;
                }
                Vec3 dir = motion.normalize();
                double angle = Math.acos(Math.clamp(dir.dot(ideal), -1.0D, 1.0D));
                maxAngle = Math.max(maxAngle, angle);
            }
            // Legacy spread: gaussian noise of ~0.0075 * inaccuracy per axis on a
            // normalized direction; three samples should show at least a small
            // fan.  A single degenerate trajectory implies the spread was lost.
            // Legacy spread: ~0.0075 * inaccuracy radians of gaussian noise per
            // axis on a normalized direction.  With two scatter upgrades the
            // inaccuracy is 1.8, so a three-projectile volley should stay within
            // roughly 0.005..0.05 radians of the ideal trajectory.  A value far
            // outside that band means the port lost or inflated the legacy fan.
            helper.assertTrue(maxAngle > 0.005D && maxAngle < 0.05D,
                    "Scatter volley spread off the legacy band: " + maxAngle
                            + " (inaccuracy=" + expectedInaccuracy + ")");
            helper.succeed();
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void addonMaskTracksInventory(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(3, 1, 3);
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        helper.assertTrue(base.addonRenderMask() == 0,
                "Addon render mask should start empty");
        base.inventory().setStackInSlot(TurretBaseBlockEntity.ADDON_SLOT_START,
                new ItemStack(ModItems.ADDON_DAMAGE_AMP.value()));
        helper.assertTrue(base.addonRenderMask() == 1,
                "Addon render mask did not pick up the damage amp");
        base.inventory().setStackInSlot(TurretBaseBlockEntity.ADDON_SLOT_START,
                new ItemStack(ModItems.ADDON_SOLAR_PANEL.value()));
        helper.assertTrue(base.addonRenderMask() == 2,
                "Addon render mask did not switch to the solar panel");
        base.inventory().setStackInSlot(TurretBaseBlockEntity.ADDON_SLOT_START,
                ItemStack.EMPTY);
        helper.assertTrue(base.addonRenderMask() == 0,
                "Addon render mask did not clear on removal");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void projectileStatePersistence(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        var target = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(target != null, "Unable to create projectile target");

        TurretProjectileEntity original = TurretProjectileEntity.create(helper.getLevel(),
                ProjectileKind.GRENADE, base.getBlockPos(), target, TurretDefinition.GRENADE,
                2, new TurretAttackContext(base.getBlockPos(), 3, true),
                new ItemStack(ModItems.AMMO_GRENADE.value()));
        CompoundTag saved = new CompoundTag();
        original.addAdditionalSaveData(saved);
        saved.putBoolean("grenade_hit", true);
        helper.assertTrue(saved.getInt("damage_amp_level") == 2
                        && saved.getInt("fake_drops_level") == 3
                        && saved.getBoolean("suppress_loot"),
                "Projectile did not persist its launch-time addon context");

        TurretProjectileEntity restored = new TurretProjectileEntity(
                omtteam.openmodularturrets.registration.ModEntities.GRENADE_PROJECTILE.value(),
                helper.getLevel());
        restored.readAdditionalSaveData(saved);
        helper.assertTrue(restored.projectileKind() == ProjectileKind.GRENADE,
                "Projectile kind did not survive save/load");
        CompoundTag roundTrip = new CompoundTag();
        restored.addAdditionalSaveData(roundTrip);
        helper.assertTrue(roundTrip.getInt("damage_amp_level") == 2
                        && roundTrip.getInt("fake_drops_level") == 3
                        && roundTrip.getBoolean("suppress_loot")
                        && roundTrip.getBoolean("grenade_hit"),
                "Projectile addon context did not survive save/load");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 60)
    public static void projectileLifetime(GameTestHelper helper) {
        helper.assertTrue(!ProjectileKind.BULLET.shouldExpire(40)
                        && ProjectileKind.BULLET.shouldExpire(41)
                        && !ProjectileKind.PLASMA.shouldExpire(30)
                        && ProjectileKind.PLASMA.shouldExpire(31)
                        && !ProjectileKind.GRENADE.fuseExpired(38)
                        && ProjectileKind.GRENADE.fuseExpired(39),
                "Legacy projectile lifetime or grenade fuse boundary drifted");
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        var target = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(target != null, "Unable to create projectile target");
        TurretProjectileEntity restored = TurretProjectileEntity.create(helper.getLevel(),
                ProjectileKind.GRENADE, base.getBlockPos(), target, TurretDefinition.GRENADE,
                0, base.attackContext(),
                new ItemStack(ModItems.AMMO_GRENADE.value()));
        restored.setPos(base.getBlockPos().getCenter().add(0.0D, 2.0D, 0.0D));
        helper.getLevel().addFreshEntity(restored);
        helper.runAfterDelay(ProjectileKind.GRENADE.maximumLifetime() + 2L, () -> {
            helper.assertTrue(restored.isRemoved(),
                    "Projectile exceeded its bounded maximum lifetime");
            helper.succeed();
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void projectileCollisionPolicy(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        var owner = helper.makeMockPlayer(GameType.SURVIVAL);
        base.claim(owner.getUUID());
        base.setTargetFlags(true, true, true);
        var hostile = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(hostile != null, "Unable to create projectile collision target");

        TurretProjectileEntity projectile = TurretProjectileEntity.create(helper.getLevel(),
                ProjectileKind.BULLET, base.getBlockPos(), hostile,
                TurretDefinition.MACHINE_GUN, 0, base.attackContext(),
                new ItemStack(ModItems.AMMO_BULLET.value()));
        TurretProjectileEntity otherProjectile = TurretProjectileEntity.create(
                helper.getLevel(), ProjectileKind.POTATO, base.getBlockPos(), hostile,
                TurretDefinition.POTATO, 0, base.attackContext(),
                new ItemStack(Items.POTATO));
        helper.assertTrue(!projectile.mayCollideWith(owner),
                "A projectile consumed itself on its protected owner");
        helper.assertTrue(!projectile.mayCollideWith(otherProjectile),
                "OMT projectiles can collide with each other");
        helper.assertTrue(projectile.mayCollideWith(hostile),
                "A legal hostile target was filtered from projectile collision");
        helper.assertTrue(projectile.ignoresBlockCollision(
                        ModBlocks.MACHINE_GUN_TURRET.value().defaultBlockState()),
                "A turret head no longer allows OMT projectiles to pass through");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void projectileDamageTypes(GameTestHelper helper) {
        var damageTypes = helper.getLevel().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE);
        helper.assertTrue(damageTypes.getHolder(ModDamageTypes.TURRET_PROJECTILE).isPresent(),
                "Projectile DamageType is missing");
        helper.assertTrue(damageTypes.getHolder(ModDamageTypes.TURRET_ARMOR_PIERCING).isPresent(),
                "Armor-piercing DamageType is missing");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void projectileCollisionIsServerAuthoritative(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        base.setTargetFlags(true, false, false);
        for (int x = -2; x <= 1; x++) {
            for (int y = 1; y <= 4; y++) {
                for (int z = -1; z <= 1; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        var target = helper.spawn(EntityType.ZOMBIE, new Vec3(0.5D, 2.0D, 0.5D));
        target.setNoAi(true);
        target.setNoGravity(true);
        helper.runAfterDelay(2L, () -> {
            float healthBefore = target.getHealth();
            TurretProjectileEntity projectile = TurretProjectileEntity.create(helper.getLevel(),
                    ProjectileKind.BULLET, base.getBlockPos(), target,
                    TurretDefinition.MACHINE_GUN, 0, base.attackContext(),
                    new ItemStack(ModItems.AMMO_BULLET.value()));
            Vec3 targetCenter = target.getBoundingBox().getCenter();
            projectile.setPos(targetCenter.x - 1.0D, targetCenter.y, targetCenter.z);
            projectile.shoot(1.0D, 0.0D, 0.0D, 1.0F, 0.0F);
            helper.getLevel().addFreshEntity(projectile);
            helper.runAfterDelay(5L, () -> {
                helper.assertTrue(target.getHealth() < healthBefore,
                        "Projectile collision did not apply server-owned damage"
                                + " health=" + target.getHealth()
                                + " target=" + target.position()
                                + " projectile=" + projectile.position()
                                + " removed=" + projectile.isRemoved()
                                + " mayDamage=" + base.mayDamage(target));
                helper.assertTrue(projectile.isRemoved(),
                        "Direct-hit projectile was not discarded");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void addonRuleVectors(GameTestHelper helper) {
        helper.assertTrue(TurretAddonRules.recyclerPreservesAmmo(TurretDefinition.MACHINE_GUN, 0.0D)
                        && TurretAddonRules.recyclerPreservesAmmo(TurretDefinition.MACHINE_GUN, 0.099999D)
                        && !TurretAddonRules.recyclerPreservesAmmo(TurretDefinition.MACHINE_GUN, 0.10D),
                "Recycler probability boundary drifted");
        helper.assertTrue(TurretAddonRules.fakeDropsLevel(0) == -1
                        && TurretAddonRules.fakeDropsLevel(1) == 0
                        && TurretAddonRules.fakeDropsLevel(4) == 3
                        && TurretAddonRules.fakeDropsLevel(64) == 3,
                "Fake Drops stack-to-level mapping drifted");
        helper.assertTrue(Math.abs(TurretAddonRules.amplifiedDamage(
                        TurretDefinition.MACHINE_GUN, 2.0F, 19.9F, 2) - 4.28F) < 0.0001F,
                "Damage Amp no longer uses floored current health and turret coefficient");
        helper.assertTrue(TurretAddonRules.amplifiedDamage(
                        TurretDefinition.RELATIVISTIC, 7.0F, 100.0F, 64) == 7.0F,
                "Zero-coefficient turret received amplified damage");
        helper.assertTrue(TurretAddonRules.selectReactorFuel(14_401, true, true)
                        == TurretAddonRules.ReactorFuel.BLOCK
                        && TurretAddonRules.selectReactorFuel(14_400, true, true)
                        == TurretAddonRules.ReactorFuel.DUST
                        && TurretAddonRules.selectReactorFuel(1_600, false, true)
                        == TurretAddonRules.ReactorFuel.NONE,
                "Reactor strict-capacity or block-priority boundary drifted");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void addonFakeDropsAttribution(GameTestHelper helper) {
        var level = helper.getLevel();
        var holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ModDamageTypes.TURRET_PROJECTILE);
        BlockPos basePos = helper.absolutePos(new BlockPos(0, 0, 0));
        ItemEntity direct = new ItemEntity(level, basePos.getX(), basePos.getY(),
                basePos.getZ(), new ItemStack(Items.ARROW));

        TurretDamageSource noAddon = TurretDamageSource.create(level, holder, direct,
                new TurretAttackContext(basePos, -1, false));
        helper.assertTrue(noAddon.getEntity() == null,
                "Fake Drops level -1 unexpectedly created a causing player");
        helper.assertTrue(noAddon.getDirectEntity() == direct,
                "Turret source lost its direct projectile entity");

        TurretDamageSource levelZero = TurretDamageSource.create(level, holder, direct,
                new TurretAttackContext(basePos, 0, false));
        helper.assertTrue(levelZero.getEntity() instanceof FakePlayer,
                "Fake Drops level 0 did not create player attribution");
        FakePlayer fakePlayer = (FakePlayer) levelZero.getEntity();
        var looting = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.LOOTING);
        helper.assertTrue(fakePlayer.getAttributeValue(Attributes.LUCK) == 0.0D
                        && fakePlayer.getMainHandItem().getEnchantmentLevel(looting) == 0,
                "Fake Drops level 0 did not reset Luck and Looting");

        TurretDamageSource levelThree = TurretDamageSource.create(level, holder, direct,
                new TurretAttackContext(basePos, 3, false));
        helper.assertTrue(levelThree.getEntity() == fakePlayer,
                "FakePlayerFactory did not reuse the fixed-profile level player");
        helper.assertTrue(fakePlayer.getGameProfile().getId().equals(
                        UUID.fromString("c5c97afa-fc98-44ab-944a-e67681a66b19")),
                "Fake Drops player profile UUID drifted from 1.12.2");
        helper.assertTrue(fakePlayer.getAttributeValue(Attributes.LUCK) == 3.0D
                        && fakePlayer.getMainHandItem().is(Items.DIAMOND_SWORD)
                        && fakePlayer.getMainHandItem().getEnchantmentLevel(looting) == 3,
                "Fake Drops level 3 did not prepare the legacy Luck/Looting context");

        var target = EntityType.ZOMBIE.create(level);
        helper.assertTrue(target != null, "Unable to create Fake Drops attribution target");
        target.setPos(basePos.getX() + 1.0D, basePos.getY(), basePos.getZ() + 1.0D);
        level.addFreshEntity(target);
        target.hurt(levelThree, 1_000.0F);
        helper.assertTrue(!target.isAlive() && target.getKillCredit() == fakePlayer,
                "A lethal turret hit was not attributed to the FakePlayer");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void addonSolarRules(GameTestHelper helper) {
        // GameTest structures are generated underground; lift this fixture above terrain so
        // canSeeSky exercises the real world query rather than a mocked predicate.
        BlockPos relative = new BlockPos(5, 200, 5);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_TWO.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        base.inventory().setStackInSlot(TurretBaseBlockEntity.ADDON_SLOT_START,
                new ItemStack(ModItems.ADDON_SOLAR_PANEL.value()));
        helper.getLevel().setWeatherParameters(6_000, 0, false, false);
        helper.setDayTime(6_000);
        int beforeSolar = base.energy().getEnergyStored();
        TurretBaseBlockEntity.serverTick(helper.getLevel(), helper.absolutePos(relative),
                base.getBlockState(), base);
        helper.assertTrue(base.energy().getEnergyStored()
                        == beforeSolar + TurretAddonRules.SOLAR_GENERATION,
                "Clear daytime Solar Panel did not generate exactly 10 FE");

        helper.setBlock(relative.above(3), Blocks.STONE);
        int beforeBlocked = base.energy().getEnergyStored();
        TurretBaseBlockEntity.serverTick(helper.getLevel(), helper.absolutePos(relative),
                base.getBlockState(), base);
        helper.assertTrue(base.energy().getEnergyStored() == beforeBlocked,
                "Sky-obstructed Solar Panel generated energy");
        helper.setBlock(relative.above(3), Blocks.AIR);
        helper.setBlock(relative, Blocks.AIR);
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void addonEnergyRules(GameTestHelper helper) {
        BlockPos relative = new BlockPos(5, 2, 5);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_TWO.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        base.inventory().setStackInSlot(TurretBaseBlockEntity.ADDON_SLOT_START,
                new ItemStack(ModItems.ADDON_REDSTONE_REACTOR.value()));
        base.inventory().setStackInSlot(0, new ItemStack(Blocks.REDSTONE_BLOCK));
        int beforeReactor = base.energy().getEnergyStored();
        int generated = base.runReactorCycle();
        helper.assertTrue(generated == TurretAddonRules.REACTOR_BLOCK_GENERATION
                        && base.energy().getEnergyStored()
                        == beforeReactor + TurretAddonRules.REACTOR_BLOCK_GENERATION
                        && base.inventory().getStackInSlot(0).isEmpty(),
                "Reactor failed block priority, fuel consumption or maxReceive bypass");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void addonRecyclerVolleyRules(GameTestHelper helper) {
        BlockPos relative = new BlockPos(0, 0, 0);
        helper.setBlock(relative, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(relative);
        base.inventory().setStackInSlot(TurretBaseBlockEntity.ADDON_SLOT_START,
                new ItemStack(ModItems.ADDON_RECYCLER.value()));
        base.inventory().setStackInSlot(TurretBaseBlockEntity.UPGRADE_SLOT_START,
                new ItemStack(ModItems.UPGRADE_SCATTER_SHOT.value(), 2));
        base.inventory().setStackInSlot(0, new ItemStack(ModItems.AMMO_BULLET.value(), 6));
        base.energy().receiveEnergy(1_000, false);
        int energyBefore = base.energy().getEnergyStored();

        helper.assertTrue(base.consumeResourcesForVolley(
                        TurretDefinition.MACHINE_GUN, 0.05D).isPresent(),
                "Successful recycler roll rejected a funded volley");
        helper.assertTrue(base.inventory().getStackInSlot(0).getCount() == 6
                        && base.energy().getEnergyStored() < energyBefore,
                "Recycler did not preserve the whole volley or incorrectly preserved energy");

        helper.assertTrue(base.consumeResourcesForVolley(
                        TurretDefinition.MACHINE_GUN, 0.10D).isPresent(),
                "Boundary recycler failure rejected a funded volley");
        helper.assertTrue(base.inventory().getStackInSlot(0).getCount() == 3,
                "Failed recycler roll did not atomically consume three scatter rounds");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void addonLootAttribution(GameTestHelper helper) {
        var holder = helper.getLevel().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ModDamageTypes.TURRET_PROJECTILE);
        BlockPos basePos = helper.absolutePos(new BlockPos(0, 0, 0));
        TurretDamageSource suppressing = TurretDamageSource.create(helper.getLevel(),
                holder, null,
                new TurretAttackContext(basePos, 3, true));
        var target = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(target != null, "Unable to create loot attribution target");
        var drops = new ArrayList<ItemEntity>();
        drops.add(new ItemEntity(helper.getLevel(), 0.0D, 2.0D, 0.0D,
                new ItemStack(Items.DIAMOND)));
        TurretCombatEvents.onLivingDrops(
                new LivingDropsEvent(target, suppressing, drops, true));
        helper.assertTrue(drops.isEmpty(),
                "Loot Deleter context did not suppress turret-attributed drops");

        drops.add(new ItemEntity(helper.getLevel(), 0.0D, 2.0D, 0.0D,
                new ItemStack(Items.DIAMOND)));
        TurretCombatEvents.onLivingDrops(new LivingDropsEvent(target,
                helper.getLevel().damageSources().generic(), drops, true));
        helper.assertTrue(drops.size() == 1,
                "Loot handler modified a non-turret damage control");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 60)
    public static void addonConcealmentState(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(5, 2, 5);
        BlockPos turretPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(turretPos, ModBlocks.MACHINE_GUN_TURRET.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        base.inventory().setStackInSlot(TurretBaseBlockEntity.ADDON_SLOT_START,
                new ItemStack(ModItems.ADDON_CONCEALER.value()));
        helper.runAfterDelay(TurretAddonRules.CONCEAL_DELAY + 2L, () -> {
            TurretHeadBlockEntity turret = helper.getBlockEntity(turretPos);
            helper.assertTrue(turret.concealed(),
                    "Concealer did not retract after 40 idle ticks");
            base.inventory().setStackInSlot(TurretBaseBlockEntity.ADDON_SLOT_START,
                    ItemStack.EMPTY);
            helper.runAfterDelay(2L, () -> {
                helper.assertTrue(!turret.concealed(),
                        "Turret did not expand after Concealer removal");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void targetPriorityRules(GameTestHelper helper) {
        for (TurretDefinition definition : TurretDefinition.values()) {
            TargetPriorityProfile profile = TargetPriorityProfile.defaults(definition);
            helper.assertTrue(profile != null,
                    "Missing target priority profile for " + definition.id());
        }
        TargetPriorityProfile nearFirst = new TargetPriorityProfile(0, 0, -10, 0, 0);
        double near = TargetingRules.score(nearFirst, 20.0F, 20.0F,
                2.0D, 20, 0, false);
        double far = TargetingRules.score(nearFirst, 20.0F, 20.0F,
                18.0D, 20, 0, false);
        helper.assertTrue(near > far, "Negative distance weight did not prefer near targets");

        TargetPriorityProfile playerFirst = new TargetPriorityProfile(0, 0, 0, 0, 10);
        helper.assertTrue(
                TargetingRules.score(playerFirst, 20.0F, 20.0F, 5.0D, 20, 0, true)
                        > TargetingRules.score(playerFirst, 20.0F, 20.0F,
                                5.0D, 20, 0, false),
                "Player weight did not distinguish player candidates");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void turretPriorityPersistence(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockState state = ModBlocks.RAIL_GUN_TURRET.value().defaultBlockState();
        TurretHeadBlockEntity original = new TurretHeadBlockEntity(pos, state);
        TargetPriorityProfile custom = new TargetPriorityProfile(31, -17, 9, 44, -6);
        original.setPriorityProfile(custom);

        CompoundTag saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(pos, state, saved,
                helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof TurretHeadBlockEntity restored
                        && restored.priorityProfile().equals(custom),
                "Turret priority profile did not survive block-entity persistence");

        CompoundTag legacyTag = saved.copy();
        legacyTag.putInt("data_version", 1);
        legacyTag.remove("priority_max_health");
        legacyTag.remove("priority_missing_health");
        legacyTag.remove("priority_distance");
        legacyTag.remove("priority_armor");
        legacyTag.remove("priority_player");
        BlockEntity migrated = BlockEntity.loadStatic(pos, state, legacyTag,
                helper.getLevel().registryAccess());
        helper.assertTrue(migrated instanceof TurretHeadBlockEntity legacy
                        && legacy.priorityProfile().equals(
                                TargetPriorityProfile.defaults(TurretDefinition.RAIL_GUN)),
                "Schema-one turret did not migrate to its definition defaults");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void targetingProfileMigration(GameTestHelper helper) {
        String schemaOne = """
                {
                  "schema_version": 1,
                  "range": 17,
                  "active": true,
                  "attack_hostile": true,
                  "attack_neutral": true,
                  "attack_players": false
                }
                """;
        MemoryCardProfile migrated = MemoryCardProfile.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(schemaOne))
                .resultOrPartial(message -> {
                    throw new IllegalStateException(message);
                })
                .orElseThrow();
        helper.assertTrue(migrated.schemaVersion() == MemoryCardProfile.CURRENT_SCHEMA
                        && !migrated.multiTargeting() && migrated.range() == 17
                        && migrated.mode() == BaseMode.ALWAYS_ON,
                "Schema 1 Memory Card did not migrate active=true to schema 3 mode");

        String schemaTwo = """
                {
                  "schema_version": 2,
                  "range": 19,
                  "active": false,
                  "attack_hostile": true,
                  "attack_neutral": false,
                  "attack_players": true,
                  "multi_targeting": true
                }
                """;
        MemoryCardProfile migratedTwo = MemoryCardProfile.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(schemaTwo))
                .getOrThrow();
        helper.assertTrue(migratedTwo.mode() == BaseMode.ALWAYS_OFF
                        && migratedTwo.multiTargeting(),
                "Schema 2 Memory Card did not migrate active=false to schema 3 mode");

        MemoryCardProfile schemaThree = new MemoryCardProfile(
                MemoryCardProfile.TRUST_SCHEMA - 1, 21, BaseMode.NONINVERTED.id(),
                true, false, true, true, java.util.List.of(), false);
        MemoryCardProfile roundTripped = MemoryCardProfile.CODEC
                .parse(JsonOps.INSTANCE,
                        MemoryCardProfile.CODEC.encodeStart(JsonOps.INSTANCE, schemaThree)
                                .getOrThrow())
                .getOrThrow();
        helper.assertTrue(roundTripped.equals(schemaThree),
                "Schema 3 Memory Card did not round-trip its base mode");
        helper.assertTrue(!roundTripped.carriesTrust(),
                "Schema 3 Memory Card unexpectedly gained a trusted-player list");

        MemoryCardProfile trustCard = new MemoryCardProfile(
                MemoryCardProfile.CURRENT_SCHEMA, 21, BaseMode.NONINVERTED.id(),
                true, false, true, true,
                java.util.List.of(new MemoryCardProfile.TrustEntry(
                        java.util.UUID.fromString(
                                "a5b5b6a5-0000-0000-0000-000000000001"),
                        "Alice", AccessLevel.ADMIN)), true);
        MemoryCardProfile trustRoundTripped = MemoryCardProfile.CODEC
                .parse(JsonOps.INSTANCE,
                        MemoryCardProfile.CODEC.encodeStart(JsonOps.INSTANCE, trustCard)
                                .getOrThrow())
                .getOrThrow();
        helper.assertTrue(trustRoundTripped.carriesTrust()
                        && trustRoundTripped.trustEntries().size() == 1
                        && trustRoundTripped.trustEntries().getFirst()
                                .access() == AccessLevel.ADMIN,
                "Schema 4 Memory Card did not round-trip the trusted-player list");
        helper.assertTrue(BaseMode.INVERTED.isActive(false)
                        && !BaseMode.INVERTED.isActive(true)
                        && !BaseMode.NONINVERTED.isActive(false)
                        && BaseMode.NONINVERTED.isActive(true)
                        && BaseMode.ALWAYS_ON.next() == BaseMode.ALWAYS_OFF
                        && BaseMode.NONINVERTED.next() == BaseMode.ALWAYS_ON,
                "Base mode truth table or legacy cycle order changed");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void memoryCardCopiesBaseSettings(GameTestHelper helper) {
        BlockPos sourcePos = new BlockPos(3, 2, 3);
        BlockPos targetPos = new BlockPos(7, 2, 3);
        helper.setBlock(sourcePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(targetPos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity source = helper.getBlockEntity(sourcePos);
        TurretBaseBlockEntity target = helper.getBlockEntity(targetPos);

        java.util.UUID trusted = java.util.UUID.fromString(
                "a5b5b6a5-0000-0000-0000-000000000002");
        var owner = helper.makeMockPlayer(GameType.SURVIVAL);
        source.claim(owner);
        source.setRange(24);
        source.setTargetFlags(false, true, false);
        source.setMode(BaseMode.NONINVERTED);
        source.setMultiTargeting(true);
        source.setLocalTrust(owner, trusted, AccessLevel.ADMIN);

        ItemStack card = new ItemStack(ModItems.MEMORY_CARD.value());
        owner.setItemInHand(InteractionHand.MAIN_HAND, card);
        owner.setShiftKeyDown(true);
        BlockPos absoluteSource = helper.absolutePos(sourcePos);
        card.useOn(new UseOnContext(owner, InteractionHand.MAIN_HAND,
                new BlockHitResult(absoluteSource.getCenter(), Direction.UP,
                        absoluteSource, false)));

        MemoryCardProfile profile = card.get(ModDataComponents.MEMORY_CARD_PROFILE.value());
        helper.assertTrue(profile != null && profile.carriesTrust()
                        && profile.range() == 24
                        && profile.mode() == BaseMode.NONINVERTED
                        && profile.multiTargeting()
                        && !profile.attackHostile() && profile.attackNeutral()
                        && !profile.attackPlayers(),
                "Memory card did not capture the source base settings");

        owner.setShiftKeyDown(false);
        BlockPos absoluteTarget = helper.absolutePos(targetPos);
        target.claim(owner);
        card.useOn(new UseOnContext(owner, InteractionHand.MAIN_HAND,
                new BlockHitResult(absoluteTarget.getCenter(), Direction.UP,
                        absoluteTarget, false)));
        helper.assertTrue(target.configuredRange() == 24
                        && target.mode() == BaseMode.NONINVERTED
                        && target.multiTargeting()
                        && !target.attackHostile() && target.attackNeutral()
                        && !target.attackPlayers(),
                "Memory card did not apply the stored settings to the target base");
        helper.assertTrue(target.localTrustSnapshot().containsKey(trusted),
                "Memory card did not restore the trusted-player list");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void multiTargetCoordination(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(5, 2, 5);
        BlockPos firstHeadPos = basePos.east();
        BlockPos secondHeadPos = basePos.west();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(firstHeadPos, ModBlocks.MACHINE_GUN_TURRET.value());
        helper.setBlock(secondHeadPos, ModBlocks.MACHINE_GUN_TURRET.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        TurretHeadBlockEntity first = helper.getBlockEntity(firstHeadPos);
        var zombie = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(zombie != null, "Unable to create multi-target fixture");
        zombie.setPos(helper.absolutePos(new BlockPos(5, 2, 8)).getCenter());
        helper.getLevel().addFreshEntity(zombie);
        first.applyNetworkAim(0.0F, 0.0F, zombie.getId());

        helper.assertTrue(!base.isTargetClaimedBySibling(
                        helper.absolutePos(secondHeadPos), zombie),
                "Focus-fire mode unexpectedly excluded a sibling target");
        base.setMultiTargeting(true);
        helper.assertTrue(base.isTargetClaimedBySibling(
                        helper.absolutePos(secondHeadPos), zombie),
                "Multi-target mode did not exclude a sibling target");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void targetProtectionPolicy(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(5, 2, 5);
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        var owner = helper.makeMockPlayer(GameType.SURVIVAL);
        var stranger = helper.makeMockPlayer(GameType.SURVIVAL);
        var teammate = helper.makeMockPlayer(GameType.SURVIVAL);
        var ownerTeam = helper.getLevel().getScoreboard().getPlayerTeam(
                "omt_owner_team_" + owner.getId());
        if (ownerTeam == null) {
            ownerTeam = helper.getLevel().getScoreboard().addPlayerTeam(
                    "omt_owner_team_" + owner.getId());
        }
        helper.getLevel().getScoreboard().addPlayerToTeam(
                owner.getScoreboardName(), ownerTeam);
        helper.getLevel().getScoreboard().addPlayerToTeam(
                teammate.getScoreboardName(), ownerTeam);
        base.claim(owner);
        helper.assertTrue(base.attackHostile() && !base.attackNeutral()
                        && !base.attackPlayers(),
                "A new base did not retain the legacy hostile-only target defaults");
        helper.assertTrue(MemoryCardProfile.DEFAULT.attackHostile()
                        && !MemoryCardProfile.DEFAULT.attackNeutral()
                        && !MemoryCardProfile.DEFAULT.attackPlayers(),
                "The default Memory Card profile disagrees with legacy base defaults");
        base.setTargetFlags(true, true, true);

        helper.assertTrue(!base.mayDamage(owner),
                "A base considered its owner damageable");
        helper.assertTrue(base.mayTarget(stranger),
                "An untrusted survival player was incorrectly protected by trust policy");
        helper.assertTrue(!base.mayDamage(teammate),
                "A scoreboard teammate of the base owner was considered damageable");
        var creative = helper.makeMockPlayer(GameType.CREATIVE);
        var spectator = helper.makeMockPlayer(GameType.SPECTATOR);
        helper.assertTrue(!base.mayDamage(creative),
                "Creative players must never be legal turret targets");
        helper.assertTrue(!base.mayDamage(spectator),
                "Spectator players must never be legal turret targets");

        Wolf wolf = EntityType.WOLF.create(helper.getLevel());
        helper.assertTrue(wolf != null, "Unable to create tameable protection fixture");
        wolf.tame(owner);
        helper.assertTrue(!base.mayDamage(wolf),
                "The owner's tameable pet was not protected");
        helper.assertTrue(base.setLocalTrust(owner, stranger.getUUID(), AccessLevel.VIEW),
                "Unable to create trusted-pet fixture");
        Wolf trustedWolf = EntityType.WOLF.create(helper.getLevel());
        helper.assertTrue(trustedWolf != null,
                "Unable to create trusted tameable fixture");
        trustedWolf.tame(stranger);
        helper.assertTrue(!base.mayDamage(trustedWolf),
                "A trusted player's tameable pet was not protected by default");
        helper.assertTrue(!TargetingRules.ownershipAllowsTarget(false, true, false)
                        && TargetingRules.ownershipAllowsTarget(false, true, true)
                        && !TargetingRules.ownershipAllowsTarget(true, false, true),
                "damage_trusted_players did not consistently preserve owner protection");

        Horse horse = EntityType.HORSE.create(helper.getLevel());
        helper.assertTrue(horse != null, "Unable to create tamed-horse fixture");
        horse.setTamed(true);
        helper.assertTrue(!base.mayDamage(horse),
                "A tamed horse was not protected by the legacy targeting rule");
        helper.assertTrue(EntityType.ARMOR_STAND.is(ModTags.EntityTypes.TARGET_BLACKLIST),
                "The legacy default ArmorStand target blacklist entry is missing");

        var zombie = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(zombie != null && base.mayDamage(zombie),
                "Hostile target flag did not allow a hostile mob");
        base.setTargetFlags(false, true, true);
        helper.assertTrue(!base.mayDamage(zombie),
                "Disabled hostile target flag still allowed a hostile mob");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void omlibOwnershipCompatibilityRules(GameTestHelper helper) {
        UUID ownerId = UUID.randomUUID();
        UUID rejoinedId = UUID.randomUUID();
        UUID trustedId = UUID.randomUUID();
        UUID renamedTrustedId = UUID.randomUUID();

        helper.assertTrue(!OwnershipRules.matches(ownerId, "LegacyOwner", rejoinedId,
                        "legacyowner", false)
                        && OwnershipRules.matches(ownerId, "LegacyOwner", rejoinedId,
                                "legacyowner", true)
                        && !OwnershipRules.matches(ownerId, "LegacyOwner", rejoinedId,
                                "DifferentPlayer", true),
                "offline_mode_support did not retain UUID-first and case-insensitive name fallback");
        helper.assertTrue(!OwnershipRules.opIsProtected(false, true)
                        && OwnershipRules.opIsProtected(true, true)
                        && !OwnershipRules.opIsProtected(true, false),
                "can_op_access_owned_blocks did not retain the legacy OP protection gate");

        SecuritySavedData security = SecuritySavedData.get(helper.getLevel());
        helper.assertTrue(security.setAccess(ownerId, trustedId, "OfflineTrusted",
                        AccessLevel.USE)
                        && security.accessFor(ownerId, renamedTrustedId, "offlinetrusted", true)
                                == AccessLevel.USE
                        && security.accessFor(ownerId, renamedTrustedId, "offlinetrusted", false)
                                == AccessLevel.NONE,
                "Global trust did not apply offline-mode name matching only when enabled");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void targetingMenuState(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(5, 2, 5);
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(basePos.east(), ModBlocks.MACHINE_GUN_TURRET.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        var owner = helper.makeMockPlayer(GameType.SURVIVAL);
        base.claim(owner.getUUID());
        base.setActive(false);
        base.setRange(13);
        base.setTargetFlags(false, true, true);
        base.setMultiTargeting(true);

        TurretBaseMenu menu = new TurretBaseMenu(1, owner.getInventory(), base);
        helper.assertTrue(!menu.active()
                        && menu.configuredRange() == 13
                        && menu.maximumRange() >= 13
                        && !menu.attackHostile()
                        && menu.attackNeutral()
                        && menu.attackPlayers()
                        && menu.multiTargeting()
                        && menu.accessLevel() == AccessLevel.ADMIN,
                "Turret menu data did not expose the authoritative server state");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void specialTurretRules(GameTestHelper helper) {
        float laserUnarmored = SpecialTurretRules.beamDamageMultiplier(
                TurretDefinition.LASER, 0);
        float laserArmored = SpecialTurretRules.beamDamageMultiplier(
                TurretDefinition.LASER, 20);
        float railUnarmored = SpecialTurretRules.beamDamageMultiplier(
                TurretDefinition.RAIL_GUN, 0);
        float railArmored = SpecialTurretRules.beamDamageMultiplier(
                TurretDefinition.RAIL_GUN, 20);
        helper.assertTrue(Math.abs(laserUnarmored - 1.6F) < 0.0001F
                        && Math.abs(laserArmored - 0.6F) < 0.0001F
                        && Math.abs(railUnarmored - 0.6F) < 0.0001F
                        && Math.abs(railArmored - 1.6F) < 0.0001F,
                "Laser or Rail Gun armor response differs from the legacy formula");
        helper.assertTrue(SpecialTurretRules.beamDamageType(TurretDefinition.LASER)
                        == ModDamageTypes.TURRET_PROJECTILE
                        && SpecialTurretRules.beamDamageType(TurretDefinition.RAIL_GUN)
                        == ModDamageTypes.TURRET_ARMOR_PIERCING
                        && SpecialTurretRules.beamColor(TurretDefinition.LASER)
                        != SpecialTurretRules.beamColor(TurretDefinition.RAIL_GUN),
                "Beam damage type or color mapping is not distinct");

        var slowed = EntityType.ZOMBIE.create(helper.getLevel());
        helper.assertTrue(slowed != null, "Unable to create relativistic fixture");
        helper.assertTrue(SpecialTurretRules.acceptsTarget(
                        TurretDefinition.RELATIVISTIC, slowed),
                "Relativistic turret rejected an unaffected target");
        slowed.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 200, 3));
        helper.assertTrue(!SpecialTurretRules.acceptsTarget(
                        TurretDefinition.RELATIVISTIC, slowed),
                "Relativistic turret accepted a target that is already slowed");
        helper.assertTrue(SpecialTurretRules.shotExecutions(
                        TurretDefinition.TELEPORTER, 4) == 1
                        && SpecialTurretRules.shotExecutions(
                                TurretDefinition.RELATIVISTIC, 4) == 1
                        && SpecialTurretRules.shotExecutions(
                                TurretDefinition.MACHINE_GUN, 4) == 4
                        && SpecialTurretRules.shotExecutions(
                                TurretDefinition.LASER, 4) == 4,
                "Scatter changed legacy special-shot execution cardinality");
        Vec3 homing = SpecialTurretRules.rocketHomingVelocity(
                Vec3.ZERO, new Vec3(3.0D, 4.0D, 0.0D));
        helper.assertTrue(Math.abs(homing.length() - 0.24D) < 0.000001D
                        && ProjectileKind.ROCKET.terrainExplosionStrength(true) == 2.3F
                        && ProjectileKind.GRENADE.terrainExplosionStrength(true) == 1.4F
                        && ProjectileKind.ROCKET.terrainExplosionStrength(false) == 0.1F
                        && ProjectileKind.GRENADE.terrainExplosionStrength(false) == 0.1F
                        && SpecialTurretRules.railgunCanDestroyBlock(199.99F, true)
                        && !SpecialTurretRules.railgunCanDestroyBlock(200.0F, true)
                        && !SpecialTurretRules.railgunCanDestroyBlock(1.0F, false),
                "Legacy homing or optional terrain-interaction vectors drifted");

        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 40)
    public static void teleporterForcesUnsafeLanding(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(4, 1, 5);
        BlockPos headPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FOUR.value());
        helper.setBlock(headPos, ModBlocks.TELEPORTER_TURRET.value());

        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        TurretHeadBlockEntity head = helper.getBlockEntity(headPos);
        helper.assertTrue(head != null, "Teleporter head block entity was not created");
        base.setActive(true);
        base.setRange(TurretDefinition.TELEPORTER.baseRange());
        base.setTargetFlags(true, false, false);
        // The base energy storage clamps each transfer to tier().maxReceive()
        // (2500 FE/t for tier four), so keep pushing until the volley cost is met.
        while (base.energy().getEnergyStored() < 40_000) {
            base.energy().receiveEnergy(10_000, false);
        }

        var target = helper.spawn(EntityType.ZOMBIE, new Vec3(10.5D, 1.0D, 5.5D));
        target.setNoAi(true);
        target.setNoGravity(true);
        head.applyNetworkAim(0.0F, 0.0F, target.getId());

        // Legacy 1.12 behaviour: the landing is forced unconditionally, so occupy
        // the cell directly above the head with a solid block - the target must
        // still be yanked there even though it is not a safe landing.
        BlockPos absoluteHead = helper.absolutePos(headPos);
        helper.getLevel().setBlock(absoluteHead.above(), Blocks.STONE.defaultBlockState(), 3);

        int energyBefore = base.energy().getEnergyStored();
        long shotsBefore = base.shotsFired();
        helper.runAfterDelay(20L, () -> {
            Vec3 forcedLanding = Vec3.atBottomCenterOf(absoluteHead.above());
            helper.assertTrue(target.position().distanceToSqr(forcedLanding) < 1.0E-6D,
                    "Teleporter did not force the unsafe landing at the head cell");
            helper.assertTrue(base.energy().getEnergyStored() <= energyBefore - 15_000,
                    "Forced teleporter shot did not consume the legacy energy cost");
            helper.assertTrue(base.shotsFired() == shotsBefore + 1,
                    "Forced teleporter shot was not counted as a volley");
            CompoundTag state = head.saveWithFullMetadata(
                    helper.getLevel().registryAccess());
            helper.assertTrue(state.getInt("cooldown") > 0,
                    "Forced teleporter shot did not start the firing cooldown");
            helper.succeed();
            target.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void visualStateContract(GameTestHelper helper) {
        for (TurretDefinition definition : TurretDefinition.values()) {
            String texture = TurretVisualRules.texturePath(definition);
            helper.assertTrue(texture.startsWith("textures/block/")
                            && texture.endsWith(".png"),
                    "Invalid visual texture route for " + definition.id());
        }
        helper.assertTrue(TurretVisualRules.texturePath(TurretDefinition.PLASMA)
                        .endsWith("grenade_turret.png"),
                "Plasma did not retain its legacy grenade-model texture");
        Set<TurretVisualRules.MountRotation> rotations =
                java.util.Arrays.stream(Direction.values())
                        .map(TurretVisualRules::mountRotation)
                        .collect(Collectors.toSet());
        helper.assertTrue(rotations.size() == Direction.values().length,
                "Six mount directions do not have six stable transforms");
        helper.assertTrue(TurretVisualRules.mountRotation(Direction.EAST)
                        .equals(new TurretVisualRules.MountRotation(1.56F, 1.565F))
                        && TurretVisualRules.mountRotation(Direction.UP)
                        .equals(new TurretVisualRules.MountRotation(3.145F, 0.0F)),
                "Mount transforms differ from the legacy support-piece rotations");
        BlockPos basePos = new BlockPos(5, 2, 5);
        BlockPos headPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(headPos, ModBlocks.POTATO_CANNON_TURRET.value());
        BlockPos absoluteHeadPos = helper.absolutePos(headPos);
        var headState = helper.getLevel().getBlockState(absoluteHeadPos);
        var headBounds = headState.getShape(helper.getLevel(), absoluteHeadPos).bounds();
        helper.assertTrue(!headState.canOcclude()
                        && Math.abs(headBounds.getXsize() - 0.6D) < 0.001D
                        && Math.abs(headBounds.getYsize() - 0.6D) < 0.001D
                        && Math.abs(headBounds.getZsize() - 0.6D) < 0.001D,
                "Turret head still occludes neighboring terrain or uses a full-cube shape");
        var expanderState = ModBlocks.EXPANDER_POWER_TIER_FIVE.value()
                .defaultBlockState().setValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        Direction.NORTH);
        var expanderBounds = expanderState.getShape(helper.getLevel(), absoluteHeadPos).bounds();
        helper.assertTrue(Math.abs(expanderBounds.getXsize() - 0.75D) < 0.001D
                        && Math.abs(expanderBounds.getYsize() - 0.75D) < 0.001D
                        && Math.abs(expanderBounds.getZsize() - 0.375D) < 0.001D,
                "Expander shape differs from the legacy 12x12x6 plate");
        var lootDeleterState = ModBlocks.BASE_ADDON_LOOT_DELETER.value().defaultBlockState()
                .setValue(omtteam.openmodularturrets.block.BaseAttachmentBlock.FACING,
                        Direction.NORTH);
        var lootDeleterBounds = lootDeleterState.getShape(helper.getLevel(), absoluteHeadPos).bounds();
        helper.assertTrue(Math.abs(lootDeleterBounds.getXsize() - 0.75D) < 0.001D
                        && Math.abs(lootDeleterBounds.getYsize() - 0.75D) < 0.001D
                        && Math.abs(lootDeleterBounds.getZsize() - 0.375D) < 0.001D,
                "Base addon loot deleter shape differs from the legacy attachment plate");
        helper.assertTrue(TurretVisualRules.addonMask(true, true, true) == 7
                        && TurretVisualRules.addonMask(false, false, false) == 0,
                "Addon render mask does not preserve its stable bit layout");
        helper.assertTrue(TurretVisualRules.beamSegments(1_000.0D)
                        == TurretVisualRules.MAX_BEAM_SEGMENTS
                        && TurretVisualRules.beamSegments(0.0D) == 1,
                "Beam particle sampling is not bounded");
        helper.assertTrue(TurretVisualRules.ROCKET_TRAIL_PARTICLES == 21
                        && TurretVisualRules.PLASMA_IMPACT_PARTICLES_PER_TYPE == 16
                        && TurretVisualRules.IDLE_DUST_PARTICLES == 6
                        && TurretVisualRules.TELEPORT_BURST_PARTICLES == 26
                        && TurretVisualRules.MAX_ACTIVE_BEAMS > 0
                        && TurretVisualRules.MAX_CLIENT_PROJECTILE_PARTICLES_PER_TICK
                                >= TurretVisualRules.ROCKET_TRAIL_PARTICLES,
                "Legacy particle count contract drifted");
        int mergedLight = TurretVisualRules.mergePackedLight(4 << 4, 13 << 20);
        helper.assertTrue(((mergedLight >> 4) & 0xF) == 4
                        && ((mergedLight >> 20) & 0xF) == 13,
                "Turret neighboring light samples were not merged component-wise");
        var chargerState = ModBlocks.LEVER_BLOCK.value().defaultBlockState();
        var chargerBounds = chargerState.getShape(helper.getLevel(), absoluteHeadPos).bounds();
        helper.assertTrue(!chargerState.canOcclude()
                        && chargerBounds.getXsize() < 1.0D
                        && chargerBounds.getYsize() < 1.0D
                        && chargerBounds.getZsize() < 1.0D,
                "Manual charger still renders or collides as a full cube");
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
        net.neoforged.neoforge.registries.DeferredBlock<?>[] inventoryExpanders = {
                ModBlocks.EXPANDER_INV_TIER_ONE, ModBlocks.EXPANDER_INV_TIER_TWO,
                ModBlocks.EXPANDER_INV_TIER_THREE, ModBlocks.EXPANDER_INV_TIER_FOUR,
                ModBlocks.EXPANDER_INV_TIER_FIVE
        };
        net.neoforged.neoforge.registries.DeferredBlock<?>[] powerExpanders = {
                ModBlocks.EXPANDER_POWER_TIER_ONE, ModBlocks.EXPANDER_POWER_TIER_TWO,
                ModBlocks.EXPANDER_POWER_TIER_THREE, ModBlocks.EXPANDER_POWER_TIER_FOUR,
                ModBlocks.EXPANDER_POWER_TIER_FIVE
        };
        for (net.neoforged.neoforge.registries.DeferredBlock<?> expander : inventoryExpanders) {
            BlockState state = expander.value().defaultBlockState().setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                    Direction.WEST);
            helper.assertTrue(state.canSurvive(helper.getLevel(), absoluteAttachment),
                    "Inventory expander was restricted by the tier-one base: "
                            + expander.getId());
        }
        for (net.neoforged.neoforge.registries.DeferredBlock<?> expander : powerExpanders) {
            BlockState state = expander.value().defaultBlockState().setValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
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
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        Direction.WEST);
        BlockPos absoluteExpanderPos = helper.absolutePos(expanderPos);
        helper.assertTrue(tierFiveState.canSurvive(helper.getLevel(), absoluteExpanderPos),
                "Legacy inventory expanders were incorrectly restricted by base tier");

        int[] expectedLimits = {4, 8, 16, 32, 64};
        net.neoforged.neoforge.registries.DeferredBlock<?>[] expanders = {
                ModBlocks.EXPANDER_INV_TIER_ONE,
                ModBlocks.EXPANDER_INV_TIER_TWO,
                ModBlocks.EXPANDER_INV_TIER_THREE,
                ModBlocks.EXPANDER_INV_TIER_FOUR,
                ModBlocks.EXPANDER_INV_TIER_FIVE
        };
        for (int tier = 0; tier < expanders.length; tier++) {
            helper.setBlock(expanderPos, expanders[tier].value().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
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

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void testTurretEnabledConfigToggle(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(5, 2, 5);
        BlockPos turretPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(turretPos, ModBlocks.LASER_TURRET.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        TurretHeadBlockEntity turret = helper.getBlockEntity(turretPos);

        helper.assertTrue(turret.definition().requiredBaseTier() == 5,
                "Laser turret definition tier should be 5");
        helper.assertTrue(ModServerConfig.turret(TurretDefinition.LASER).enabled(),
                "Laser turret should be enabled by default in server config");
        helper.succeed();
    }

    @GameTest(template = "smoke", timeoutTicks = 60)
    public static void combatKillAccountingContract(GameTestHelper helper) {
        BlockPos basePos = new BlockPos(4, 1, 5);
        BlockPos headPos = basePos.east();
        helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(headPos, ModBlocks.LASER_TURRET.value());

        TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
        base.setActive(true);
        base.setRange(TurretDefinition.LASER.baseRange());
        base.setTargetFlags(true, false, false);
        while (base.energy().getEnergyStored() < TurretDefinition.LASER.energyCost()) {
            base.energy().receiveEnergy(1_000, false);
        }

        var target = helper.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 1.0D, 5.5D));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setHealth(1.0F);

        helper.runAfterDelay(20L, () -> {
            helper.assertTrue(!target.isAlive(),
                    "A funded laser volley did not kill the weakened hostile");
            helper.assertTrue(base.kills() == 1,
                    "Synchronous volley kill was not recorded exactly once: " + base.kills());
            helper.assertTrue(base.playerKills() == 0,
                    "A hostile kill must not count as a player kill");
            helper.succeed();
        });
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void turretDefinitionGoldenDefaultsContract(GameTestHelper helper) {
        assertGoldenDefaults(helper, TurretDefinition.DISPOSABLE, true,
                1, 10, 25, 2.0F, 2, 50.0D, 4, 0.1D, 2, 0.05F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.POTATO, true,
                1, 15, 35, 3.0F, 10, 30.0D, 4, 0.1D, 2, 0.05F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.MACHINE_GUN, true,
                2, 18, 8, 2.0F, 100, 30.0D, 4, 0.1D, 2, 0.06F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.INCENDIARY, true,
                2, 12, 25, 2.0F, 250, 30.0D, 4, 0.1D, 2, 0.05F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.GRENADE, true,
                3, 18, 40, 8.0F, 3_000, 30.0D, 3, 0.1D, 2, 0.08F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.RELATIVISTIC, false,
                3, 20, 25, 0.0F, 5_000, 0.0D, 4, 0.1D, 2, 0.0F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.ROCKET, true,
                4, 30, 30, 10.0F, 5_000, 10.0D, 3, 0.1D, 2, 0.08F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.TELEPORTER, false,
                4, 20, 100, 0.0F, 15_000, 0.0D, 1, 0.1D, 2, 0.0F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.LASER, false,
                5, 25, 10, 4.0F, 8_000, 10.0D, 4, 0.125D, 2, 0.06F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.RAIL_GUN, true,
                5, 30, 100, 25.0F, 25_000, 3.0D, 2, 0.2D, 2, 0.10F, 0.2D, 0.08D, 0.10D);
        assertGoldenDefaults(helper, TurretDefinition.PLASMA, false,
                5, 20, 60, 20.0F, 40_000, 8.0D, 1, 0.2D, 1, 0.10F, 0.2D, 0.08D, 0.10D);

        for (TurretDefinition definition : TurretDefinition.values()) {
            helper.assertTrue(definition.volleyStrategy() != null,
                    definition.id() + " lost its volley strategy binding");
            helper.assertTrue(definition.shotKind() != null,
                    definition.id() + " lost its shot kind");
        }
        helper.succeed();
    }

    private static void assertGoldenDefaults(GameTestHelper helper, TurretDefinition definition,
            boolean hasAmmoTag, int requiredBaseTier, int baseRange, int fireInterval,
            float damage, int energyCost, double baseAccuracyDeviation, int maxSimultaneous,
            double fireRateUpgrade, int rangeUpgrade, float damageAmpFraction,
            double accuracyUpgrade, double efficiencyUpgrade, double recyclerNegateChance) {
        String id = definition.id();
        helper.assertTrue((definition.ammoTag() != null) == hasAmmoTag,
                id + " ammunition tag presence drifted");
        helper.assertTrue(definition.requiredBaseTier() == requiredBaseTier, id + " tier drifted");
        helper.assertTrue(definition.defaultBaseRange() == baseRange, id + " base range drifted");
        helper.assertTrue(definition.defaultFireInterval() == fireInterval, id + " fire interval drifted");
        helper.assertTrue(definition.defaultDamage() == damage, id + " damage drifted");
        helper.assertTrue(definition.defaultEnergyCost() == energyCost, id + " energy cost drifted");
        helper.assertTrue(definition.defaultBaseAccuracyDeviation() == baseAccuracyDeviation,
                id + " accuracy deviation drifted");
        helper.assertTrue(definition.defaultMaxSimultaneous() == maxSimultaneous,
                id + " simultaneous limit drifted");
        helper.assertTrue(definition.defaultFireRateUpgrade() == fireRateUpgrade,
                id + " fire rate upgrade drifted");
        helper.assertTrue(definition.defaultRangeUpgrade() == rangeUpgrade,
                id + " range upgrade drifted");
        helper.assertTrue(definition.defaultDamageAmpFraction() == damageAmpFraction,
                id + " damage amp fraction drifted");
        helper.assertTrue(definition.defaultAccuracyUpgrade() == accuracyUpgrade,
                id + " accuracy upgrade drifted");
        helper.assertTrue(definition.defaultEfficiencyUpgrade() == efficiencyUpgrade,
                id + " efficiency upgrade drifted");
        helper.assertTrue(definition.defaultRecyclerNegateChance() == recyclerNegateChance,
                id + " recycler chance drifted");
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void specialLaunchSoundMetadataContract(GameTestHelper helper) {
        for (TurretDefinition definition : TurretDefinition.values()) {
            boolean expectsFixedSound = definition == TurretDefinition.RELATIVISTIC
                    || definition == TurretDefinition.TELEPORTER;
            TurretDefinition.LaunchSound launchSound = definition.launchSound();
            helper.assertTrue((launchSound != null) == expectsFixedSound,
                    definition.id() + " fixed launch sound presence drifted");
            if (expectsFixedSound) {
                helper.assertTrue(launchSound.volume() == 0.6F && launchSound.pitch() == 1.0F,
                        definition.id() + " fixed launch sound parameters drifted");
            }
        }
        helper.succeed();
    }
}
