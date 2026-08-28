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
public final class CombatTargetingGameTests {
    private CombatTargetingGameTests() {
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
                    new AABB(helper.absolutePos(basePos))
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
                true, false, true, true, List.of(), false);
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
                List.of(new MemoryCardProfile.TrustEntry(
                        UUID.fromString(
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
        slowed.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SLOWDOWN, 200, 3));
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
            target.remove(RemovalReason.DISCARDED);
        });
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
        // Neutral-only targeting: the saturated pressure fixture's stray
        // machine-gun bullets are ineligible against a villager, so this
        // contract cannot be pre-empted by cross-plot friendly fire.
        base.setTargetFlags(false, true, false);
        while (base.energy().getEnergyStored() < TurretDefinition.LASER.energyCost()) {
            base.energy().receiveEnergy(1_000, false);
        }

        var target = helper.spawn(EntityType.VILLAGER, new Vec3(8.5D, 1.0D, 5.5D));
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
}
