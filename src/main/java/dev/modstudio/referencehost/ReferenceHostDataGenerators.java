package dev.modstudio.referencehost;

import java.nio.file.Path;
import java.util.List;

import omtteam.openmodularturrets.OpenModularTurrets;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.structures.SnbtToNbt;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * DataGen input for the toolkit's isolated GameTest reference host.
 *
 * <p>This provider is deliberately independent from the tutorial registrations
 * so the workspace's {@code minimal} profile can remove example content while
 * retaining the tooling self-check.</p>
 */
@EventBusSubscriber(modid = OpenModularTurrets.MOD_ID)
public final class ReferenceHostDataGenerators {
    private ReferenceHostDataGenerators() {
    }

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        generator.addProvider(
                event.includeServer(),
                new SnbtToNbt(
                        generator.getPackOutput(),
                        List.of(
                                Path.of(
                                        "..",
                                        "src",
                                        "main",
                                        "snbt")
                                        .normalize())));
    }
}
