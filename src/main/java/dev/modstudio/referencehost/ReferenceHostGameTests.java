package dev.modstudio.referencehost;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * Minimal infrastructure probe for this repository's reference host.
 *
 * <p>This test proves that DataGen structures, world mutation, the real
 * GameTestServer, and the toolkit's exact-symbol reporter work together. It is
 * not a gameplay-quality claim and must not replace feature-specific tests.</p>
 */
@GameTestHolder("tutorialmod")
public final class ReferenceHostGameTests {
    private ReferenceHostGameTests() {
    }

    @GameTest(template = "smoke", timeoutTicks = 20)
    public static void vanillaBlockRoundTripsThroughWorld(
            GameTestHelper helper) {
        helper.setBlock(0, 0, 0, Blocks.STONE);
        helper.assertBlockPresent(Blocks.STONE, 0, 0, 0);
        helper.succeed();
    }
}
