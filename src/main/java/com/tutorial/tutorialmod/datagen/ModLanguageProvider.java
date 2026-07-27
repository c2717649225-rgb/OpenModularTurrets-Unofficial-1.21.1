package com.tutorial.tutorialmod.datagen;

import com.tutorial.tutorialmod.TutorialMod;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public final class ModLanguageProvider extends LanguageProvider {
    public ModLanguageProvider(PackOutput output) {
        super(output, TutorialMod.MODID, "en_us");
    }

    @Override
    protected void addTranslations() {
        add("itemGroup." + TutorialMod.MODID, "Example Mod Tab");
        addBlock(TutorialMod.EXAMPLE_BLOCK, "Example Block");
        addItem(TutorialMod.EXAMPLE_ITEM, "Example Item");
        add(TutorialMod.MODID + ".configuration.title", "Example Mod Configs");
        add(TutorialMod.MODID + ".configuration.section." + TutorialMod.MODID + ".common.toml", "Example Mod Configs");
        add(TutorialMod.MODID + ".configuration.section." + TutorialMod.MODID + ".common.toml.title", "Example Mod Configs");
        add(TutorialMod.MODID + ".configuration.items", "Item List");
        add(TutorialMod.MODID + ".configuration.logDirtBlock", "Log Dirt Block");
        add(TutorialMod.MODID + ".configuration.magicNumberIntroduction", "Magic Number Text");
        add(TutorialMod.MODID + ".configuration.magicNumber", "Magic Number");
    }
}
