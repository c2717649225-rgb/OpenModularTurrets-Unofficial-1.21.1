package com.tutorial.tutorialmod.datagen;

import java.util.Set;

import com.tutorial.tutorialmod.TutorialMod;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;

public final class ModBlockLootSubProvider extends BlockLootSubProvider {
    public ModBlockLootSubProvider(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        dropSelf(TutorialMod.EXAMPLE_BLOCK.get());
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return TutorialMod.BLOCKS.getEntries().stream()
                .map(holder -> (Block) holder.value())
                .toList();
    }
}
