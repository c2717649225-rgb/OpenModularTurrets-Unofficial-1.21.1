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
public final class AddonUpgradeGameTests {
    private AddonUpgradeGameTests() {
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
}
