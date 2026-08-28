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
public final class SecurityTrustGameTests {
    private SecurityTrustGameTests() {
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
    public static void memoryCardCopiesBaseSettings(GameTestHelper helper) {
        BlockPos sourcePos = new BlockPos(3, 2, 3);
        BlockPos targetPos = new BlockPos(7, 2, 3);
        helper.setBlock(sourcePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        helper.setBlock(targetPos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
        TurretBaseBlockEntity source = helper.getBlockEntity(sourcePos);
        TurretBaseBlockEntity target = helper.getBlockEntity(targetPos);

        UUID trusted = UUID.fromString(
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
    public static void memoryCardTrustApplicationRequiresAdmin(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, ModBlocks.TURRET_BASE_TIER_ONE.value());
        TurretBaseBlockEntity base = helper.getBlockEntity(pos);
        var owner = helper.makeMockPlayer(GameType.CREATIVE);
        var attacker = helper.makeMockPlayer(GameType.CREATIVE);
        var marker = helper.makeMockPlayer(GameType.CREATIVE);
        base.claim(owner.getUUID());
        var attackerId = attacker.getUUID();

        // The attacker only holds USE on this base; a bystander marks the
        // existing trust content so a wipe is detectable.
        base.setLocalTrust(owner, attackerId, "Attacker", AccessLevel.USE);
        base.setLocalTrust(owner, marker.getUUID(), "Marker", AccessLevel.VIEW);
        base.setRange(3);

        var trustCard = new MemoryCardProfile(
                MemoryCardProfile.CURRENT_SCHEMA, 12, BaseMode.ALWAYS_ON.id(),
                true, false, false, false,
                List.of(new MemoryCardProfile.TrustEntry(
                        attackerId, "Attacker", AccessLevel.ADMIN)),
                true);

        helper.assertTrue(!base.applyProfile(attacker, trustCard),
                "A USE-level player applied a trust-carrying memory card");
        helper.assertTrue(base.localTrustSnapshot().get(attackerId).access()
                        == AccessLevel.USE,
                "Denied card application elevated the attacker");
        helper.assertTrue(base.localTrustSnapshot().containsKey(marker.getUUID()),
                "Denied card application wiped existing trust entries");
        helper.assertTrue(base.configuredRange() == 3,
                "Denied card application still mutated base settings");

        helper.assertTrue(base.applyProfile(owner, trustCard),
                "The owner could not apply a trust-carrying memory card");
        helper.assertTrue(base.localTrustSnapshot().get(attackerId).access()
                        == AccessLevel.ADMIN,
                "Admin-applied card did not write its trust entry");
        helper.assertTrue(!base.localTrustSnapshot().containsKey(marker.getUUID()),
                "Admin-applied card did not replace the whole local trust list");

        var plainCard = new MemoryCardProfile(
                MemoryCardProfile.CURRENT_SCHEMA, 14, BaseMode.ALWAYS_ON.id(),
                true, false, false, false, List.of(), false);
        helper.assertTrue(base.applyProfile(attacker, plainCard),
                "Non-trust profiles must stay available to USE-level players");
        helper.assertTrue(base.configuredRange() == 14,
                "Non-trust card settings were not applied");
        helper.succeed();
    }
}
