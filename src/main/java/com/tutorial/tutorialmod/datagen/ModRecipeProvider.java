package com.tutorial.tutorialmod.datagen;

import java.util.concurrent.CompletableFuture;

import com.tutorial.tutorialmod.TutorialMod;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class ModRecipeProvider extends RecipeProvider {
    public ModRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput) {
        ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, TutorialMod.EXAMPLE_BLOCK.get())
                .define('#', Blocks.STONE)
                .pattern("##")
                .pattern("##")
                .unlockedBy("has_stone", has(Blocks.STONE))
                .save(recipeOutput);

        ShapelessRecipeBuilder.shapeless(RecipeCategory.FOOD, TutorialMod.EXAMPLE_ITEM.get())
                .requires(Items.APPLE)
                .requires(Items.REDSTONE)
                .unlockedBy("has_redstone", has(Items.REDSTONE))
                .save(recipeOutput);
    }
}
