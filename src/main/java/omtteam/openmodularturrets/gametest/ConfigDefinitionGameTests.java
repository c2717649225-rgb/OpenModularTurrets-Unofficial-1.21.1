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
public final class ConfigDefinitionGameTests {
    private ConfigDefinitionGameTests() {
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
                Arrays.stream(Direction.values())
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
                        BlockStateProperties.FACING,
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
}
