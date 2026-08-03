package omtteam.openmodularturrets.datagen;

import java.util.concurrent.CompletableFuture;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.registration.ModBlocks;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class ModBlockTagProvider extends BlockTagsProvider {
    public ModBlockTagProvider(
            PackOutput output,
            CompletableFuture<HolderLookup.Provider> lookupProvider,
            ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, OpenModularTurrets.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        Block[] blocks = ModBlocks.ALL.stream()
                .map(holder -> holder.get())
                .toArray(Block[]::new);
        tag(BlockTags.MINEABLE_WITH_PICKAXE).add(blocks);
        tag(BlockTags.NEEDS_IRON_TOOL).add(blocks);
    }
}
