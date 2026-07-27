package com.tutorial.tutorialmod.datagen;

import com.tutorial.tutorialmod.TutorialMod;

import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, TutorialMod.MODID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        Block exampleBlock = TutorialMod.EXAMPLE_BLOCK.get();
        ModelFile model = models().cubeAll(TutorialMod.EXAMPLE_BLOCK.getId().getPath(), mcLoc("block/stone"));
        simpleBlockWithItem(exampleBlock, model);
    }
}
