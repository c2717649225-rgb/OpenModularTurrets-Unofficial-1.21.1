package com.tutorial.tutorialmod.datagen;

import com.tutorial.tutorialmod.TutorialMod;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, TutorialMod.MODID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        withExistingParent(TutorialMod.EXAMPLE_ITEM.getId().getPath(), mcLoc("item/generated"))
                .texture("layer0", mcLoc("item/redstone"));
    }
}
