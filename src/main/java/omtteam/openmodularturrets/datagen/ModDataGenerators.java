package omtteam.openmodularturrets.datagen;

import java.util.List;
import java.util.Set;

import omtteam.openmodularturrets.OpenModularTurrets;

import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

@EventBusSubscriber(modid = OpenModularTurrets.MOD_ID)
public final class ModDataGenerators {
    private ModDataGenerators() {
    }

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();
        var lookupProvider = event.getLookupProvider();

        generator.addProvider(event.includeClient(), new ModBlockStateProvider(output, existingFileHelper));
        generator.addProvider(event.includeClient(), new ModItemModelProvider(output, existingFileHelper));
        generator.addProvider(event.includeClient(), new ModLanguageProvider(output, "en_us"));
        generator.addProvider(event.includeClient(), new ModLanguageProvider(output, "zh_cn"));

        ModBlockTagProvider blockTags = new ModBlockTagProvider(
                output, lookupProvider, existingFileHelper);
        generator.addProvider(
                event.includeServer(),
                blockTags);
        generator.addProvider(
                event.includeServer(),
                new ModEntityTypeTagProvider(output, lookupProvider, existingFileHelper));
        generator.addProvider(
                event.includeServer(),
                new ModDamageTypeTagProvider(output, lookupProvider, existingFileHelper));
        generator.addProvider(
                event.includeServer(),
                new ModItemTagProvider(output, lookupProvider,
                        blockTags.contentsGetter(), existingFileHelper));
        generator.addProvider(
                event.includeServer(),
                new LootTableProvider(
                        output,
                        Set.of(),
                        List.of(new LootTableProvider.SubProviderEntry(
                                ModBlockLootSubProvider::new,
                                LootContextParamSets.BLOCK)),
                        lookupProvider));
        generator.addProvider(
                event.includeServer(),
                new ModRecipeProvider(output, lookupProvider));
    }
}
