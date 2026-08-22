package omtteam.openmodularturrets.gametest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.registration.ModBlocks;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Fixed, isolated load fixture used by the project-side JFR pressure harness.
 * It intentionally uses ordinary registered blocks/entities so the measured
 * path is the same server path exercised by gameplay and the regular tests.
 */
@GameTestHolder("openmodularturrets")
public final class ArchitecturePressureGameTests {
    private static final int FIXTURE_COUNT = 100;
    private static final int WARMUP_TICKS = 200;
    private static final int SAMPLE_TICKS = 200;

    private ArchitecturePressureGameTests() {
    }

    @GameTest(template = "smoke", timeoutTicks = WARMUP_TICKS + SAMPLE_TICKS + 40)
    public static void architecturePressureFixture(GameTestHelper helper) {
        List<TurretBaseBlockEntity> bases = new ArrayList<>(FIXTURE_COUNT);
        for (int layer = 0; layer < 4; layer++) {
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 5; column++) {
                    int x = 1 + column * 3;
                    int y = 1 + layer * 3;
                    int z = 1 + row * 3;
                    var basePos = new net.minecraft.core.BlockPos(x, y, z);
                    var headPos = basePos.east();
                    helper.setBlock(basePos, ModBlocks.TURRET_BASE_TIER_FIVE.value());
                    helper.setBlock(headPos, ModBlocks.POTATO_CANNON_TURRET.value());
                    TurretBaseBlockEntity base = helper.getBlockEntity(basePos);
                    base.setActive(true);
                    base.setRange(TurretDefinition.POTATO.baseRange());
                    base.setTargetFlags(true, false, false);
                    base.energy().receiveEnergy(1_000_000, false);
                    base.inventory().setStackInSlot(0, new ItemStack(Items.POTATO, 64));
                    bases.add(base);
                }
            }
        }

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
            var maxHealth = target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(100_000.0D);
                target.setHealth(100_000.0F);
            }
        }

        OpenModularTurrets.LOGGER.info(
                "OMT_PRESSURE_READY bases={} targets={} warmupTicks={} sampleTicks={}",
                FIXTURE_COUNT, FIXTURE_COUNT, WARMUP_TICKS, SAMPLE_TICKS);
        helper.runAfterDelay(WARMUP_TICKS, () -> {
            OpenModularTurrets.LOGGER.info("OMT_PRESSURE_SAMPLE_START");
            collectSampleTick(helper, bases, new double[SAMPLE_TICKS], 0,
                    System.nanoTime());
        });
    }

    private static void collectSampleTick(GameTestHelper helper,
            List<TurretBaseBlockEntity> bases, double[] samples, int index,
            long previousNanos) {
        helper.runAfterDelay(1L, () -> {
            long now = System.nanoTime();
            samples[index] = (now - previousNanos) / 1_000_000.0D;
            if (index + 1 < samples.length) {
                collectSampleTick(helper, bases, samples, index + 1, now);
                return;
            }
            long totalShots = bases.stream()
                    .mapToLong(TurretBaseBlockEntity::shotsFired).sum();
            helper.assertTrue(totalShots > 0,
                    "Pressure fixture produced no turret activity");
            logPressureMetrics(samples);
            OpenModularTurrets.LOGGER.info(
                    "OMT_PRESSURE_SAMPLE_END shots={} bases={} targets={}",
                    totalShots, FIXTURE_COUNT, FIXTURE_COUNT);
            helper.succeed();
        });
    }

    private static void logPressureMetrics(double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double sum = 0.0D;
        for (double sample : samples) {
            sum += sample;
        }
        OpenModularTurrets.LOGGER.info(
                "OMT_PRESSURE_METRICS mean_ms={} p50_ms={} p95_ms={} p99_ms={} max_ms={} samples={}",
                format(sum / samples.length), format(percentile(sorted, 0.50D)),
                format(percentile(sorted, 0.95D)), format(percentile(sorted, 0.99D)),
                format(sorted[sorted.length - 1]), samples.length);
    }

    private static double percentile(double[] sorted, double percentile) {
        int index = Math.max(0,
                (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }
}
