package omtteam.openmodularturrets.gametest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.entity.TurretProjectileEntity;
import omtteam.openmodularturrets.registration.ModBlocks;
import omtteam.openmodularturrets.registration.ModItems;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Fixed, isolated load fixtures used by the project-side pressure harness.
 * They intentionally use ordinary registered blocks/entities so the measured
 * path is the same server path exercised by gameplay and the regular tests.
 */
@GameTestHolder("openmodularturrets")
public final class ArchitecturePressureGameTests {
    private static final int FIXTURE_COUNT = 100;
    private static final int WARMUP_TICKS = 200;
    private static final int SAMPLE_TICKS = 200;
    private static final Direction[] HEAD_SIDES = {
            Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH};

    private ArchitecturePressureGameTests() {
    }

    /** Baseline load: one potato turret head per tier-five base. */
    @GameTest(template = "smoke", timeoutTicks = WARMUP_TICKS + SAMPLE_TICKS + 40)
    public static void architecturePressureFixture(GameTestHelper helper) {
        List<TurretBaseBlockEntity> bases =
                new ArrayList<>(FIXTURE_COUNT);
        for (int layer = 0; layer < 4; layer++) {
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 5; column++) {
                    int x = 1 + column * 3;
                    int y = 1 + layer * 3;
                    int z = 1 + row * 3;
                    BlockPos basePos = new BlockPos(x, y, z);
                    helper.setBlock(basePos,
                            ModBlocks.TURRET_BASE_TIER_FIVE.value());
                    helper.setBlock(basePos.east(),
                            ModBlocks.POTATO_CANNON_TURRET.value());
                    TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
                    base.setActive(true);
                    base.setRange(TurretDefinition.POTATO.baseRange());
                    base.setTargetFlags(true, false, false);
                    base.energy().receiveEnergy(1_000_000, false);
                    base.inventory().setStackInSlot(0,
                            new ItemStack(Items.POTATO, 64));
                    bases.add(base);
                }
            }
        }

        spawnDurableTargets(helper);
        OpenModularTurrets.LOGGER.info(
                "OMT_PRESSURE_READY bases={} targets={} warmupTicks={} sampleTicks={}",
                FIXTURE_COUNT, FIXTURE_COUNT, WARMUP_TICKS, SAMPLE_TICKS);
        runSample(helper, bases, samples -> {
            logTickMetrics("OMT_PRESSURE_METRICS", samples);
            OpenModularTurrets.LOGGER.info(
                    "OMT_PRESSURE_SAMPLE_END shots={}",
                    totalShotsOf(bases));
            helper.succeed();
        });
    }

    /**
     * Saturated fully-equipped worst case behind the "100 full bases firing
     * simultaneously" performance goal: every tier-five base carries four
     * machine-gun heads (its maximum) plus a level-four scatter + fire-rate
     * upgrade pair, so the fleet attempts five projectiles per head every six
     * ticks.  Solar panel and redstone reactor addons exercise the addon mask
     * and fuel-cycle paths.  Damage-amp / fake-drops are deliberately omitted
     * because their damage scales with target health, which would make a
     * sustained fixture impossible.
     */
    @GameTest(template = "smoke", timeoutTicks = WARMUP_TICKS + SAMPLE_TICKS + 40)
    public static void architectureSaturatedPressureFixture(
            GameTestHelper helper) {
        List<TurretBaseBlockEntity> bases =
                new ArrayList<>(FIXTURE_COUNT);
        for (int layer = 0; layer < 4; layer++) {
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 5; column++) {
                    int x = 1 + column * 3;
                    int y = 1 + layer * 3;
                    int z = 1 + row * 3;
                    BlockPos basePos = new BlockPos(x, y, z);
                    helper.setBlock(basePos,
                            ModBlocks.TURRET_BASE_TIER_FIVE.value());
                    for (Direction side : HEAD_SIDES) {
                        helper.setBlock(basePos.relative(side),
                                ModBlocks.MACHINE_GUN_TURRET.value());
                    }
                    TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
                    base.setActive(true);
                    base.setRange(TurretDefinition.MACHINE_GUN.baseRange());
                    base.setTargetFlags(true, false, false);
                    while (base.energy().getEnergyStored() < 500_000) {
                        base.energy().receiveEnergy(50_000, false);
                    }
                    base.inventory().setStackInSlot(0,
                            new ItemStack(ModItems.AMMO_BULLET.value(), 99));
                    base.inventory().setStackInSlot(1,
                            new ItemStack(ModItems.AMMO_BULLET.value(), 99));
                    base.inventory().setStackInSlot(2,
                            new ItemStack(ModItems.AMMO_BULLET.value(), 99));
                    base.inventory().setStackInSlot(3,
                            new ItemStack(ModItems.AMMO_BULLET.value(), 99));
                    base.inventory().setStackInSlot(4,
                            new ItemStack(ModItems.AMMO_BULLET.value(), 99));
                    base.inventory().setStackInSlot(5,
                            new ItemStack(ModItems.AMMO_BULLET.value(), 99));
                    base.inventory().setStackInSlot(6,
                            new ItemStack(ModItems.AMMO_BULLET.value(), 99));
                    base.inventory().setStackInSlot(7,
                            new ItemStack(ModItems.AMMO_BULLET.value(), 99));
                    base.inventory().setStackInSlot(8,
                            new ItemStack(ModItems.AMMO_BULLET.value(), 99));
                    base.inventory().setStackInSlot(
                            TurretBaseBlockEntity.UPGRADE_SLOT_START,
                            new ItemStack(ModItems.UPGRADE_SCATTER_SHOT.value(), 4));
                    base.inventory().setStackInSlot(
                            TurretBaseBlockEntity.UPGRADE_SLOT_START + 1,
                            new ItemStack(ModItems.UPGRADE_FIRE_RATE.value(), 4));
                    base.inventory().setStackInSlot(
                            TurretBaseBlockEntity.ADDON_SLOT_START,
                            new ItemStack(ModItems.ADDON_SOLAR_PANEL.value()));
                    base.inventory().setStackInSlot(
                            TurretBaseBlockEntity.ADDON_SLOT_START + 1,
                            new ItemStack(ModItems.ADDON_REDSTONE_REACTOR.value()));
                    bases.add(base);
                }
            }
        }

        spawnDurableTargets(helper);
        OpenModularTurrets.LOGGER.info(
                "OMT_SATURATED_READY bases={} heads={} upgrades=scatter4+firerate4"
                        + " addons=solar+reactor warmupTicks={} sampleTicks={}",
                FIXTURE_COUNT, FIXTURE_COUNT * HEAD_SIDES.length,
                WARMUP_TICKS, SAMPLE_TICKS);
        runSample(helper, bases, samples -> {
            int liveProjectiles = bases.get(0).getLevel().getEntitiesOfClass(
                    TurretProjectileEntity.class,
                    new AABB(-160, -64, -160, 160, 224, 160)).size();
            logTickMetrics("OMT_SATURATED_METRICS", samples);
            OpenModularTurrets.LOGGER.info(
                    "OMT_SATURATED_SAMPLE_END shots={} liveProjectiles={}",
                    totalShotsOf(bases), liveProjectiles);
            helper.succeed();
        });
    }

    private static void spawnDurableTargets(GameTestHelper helper) {
        for (int i = 0; i < FIXTURE_COUNT; i++) {
            int layer = i / 25;
            int row = (i / 5) % 5;
            int column = i % 5;
            int x = 1 + column * 3;
            int y = 1 + layer * 3;
            int z = 1 + row * 3;
            Zombie target = helper.spawn(EntityType.ZOMBIE,
                    new Vec3(x + 3.5D, y + 1.0D, z + 0.5D));
            target.setNoAi(true);
            target.setNoGravity(true);
            var maxHealth = target.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(100_000.0D);
                target.setHealth(100_000.0F);
            }
        }
    }

    private static void runSample(GameTestHelper helper,
            List<TurretBaseBlockEntity> bases,
            Consumer<double[]> finisher) {
        helper.runAfterDelay(WARMUP_TICKS, () -> collectSampleTick(
                helper, bases, new double[SAMPLE_TICKS], 0, System.nanoTime(),
                finisher));
    }

    private static void collectSampleTick(GameTestHelper helper,
            List<TurretBaseBlockEntity> bases, double[] samples, int index,
            long previousNanos, Consumer<double[]> finisher) {
        helper.runAfterDelay(1L, () -> {
            long now = System.nanoTime();
            samples[index] = (now - previousNanos) / 1_000_000.0D;
            if (index + 1 < samples.length) {
                collectSampleTick(helper, bases, samples, index + 1, now,
                        finisher);
                return;
            }
            long totalShots = totalShotsOf(bases);
            if (totalShots <= 0) {
                helper.fail("Pressure fixture produced no turret activity");
                return;
            }
            finisher.accept(samples);
        });
    }

    private static void logTickMetrics(String tag, double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double sum = 0.0D;
        for (double sample : samples) {
            sum += sample;
        }
        OpenModularTurrets.LOGGER.info(
                "{} mean_ms={} p50_ms={} p95_ms={} p99_ms={} max_ms={} samples={}",
                tag, format(sum / samples.length),
                format(percentile(sorted, 0.50D)),
                format(percentile(sorted, 0.95D)),
                format(percentile(sorted, 0.99D)),
                format(sorted[sorted.length - 1]), samples.length);
    }

    private static double percentile(double[] sorted, double percentile) {
        int index = Math.max(0,
                (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static long totalShotsOf(List<TurretBaseBlockEntity> bases) {
        return bases.stream().mapToLong(TurretBaseBlockEntity::shotsFired).sum();
    }
}
