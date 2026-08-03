package omtteam.openmodularturrets.datagen;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import omtteam.openmodularturrets.registration.ModItems;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.common.Tags;

/**
 * Vanilla-only recipes ported from the 1.12 recipe JSONs.
 *
 * <p>The old metadata-backed outputs are intentionally mapped to the standalone
 * 1.21 item IDs. Mekanism and Ender IO alternatives are outside this provider.
 */
public final class ModRecipeProvider extends RecipeProvider {
    private static final Ingredient COBBLESTONE = Ingredient.of(Tags.Items.COBBLESTONES_NORMAL);
    private static final Ingredient PLANKS = Ingredient.of(ItemTags.PLANKS);
    private static final Ingredient WOODEN_CHESTS = Ingredient.of(Tags.Items.CHESTS_WOODEN);
    private static final Ingredient IRON = Ingredient.of(Tags.Items.INGOTS_IRON);
    private static final Ingredient GOLD = Ingredient.of(Tags.Items.INGOTS_GOLD);
    private static final Ingredient DIAMOND = Ingredient.of(Tags.Items.GEMS_DIAMOND);
    private static final Ingredient QUARTZ = Ingredient.of(Tags.Items.GEMS_QUARTZ);
    private static final Ingredient LAPIS = Ingredient.of(Tags.Items.GEMS_LAPIS);
    private static final Ingredient REDSTONE = Ingredient.of(Tags.Items.DUSTS_REDSTONE);
    private static final Ingredient REDSTONE_BLOCK = Ingredient.of(Tags.Items.STORAGE_BLOCKS_REDSTONE);
    private static final Ingredient OBSIDIAN = Ingredient.of(Tags.Items.OBSIDIANS_NORMAL);
    private static final Ingredient GLASS_PANE = Ingredient.of(Tags.Items.GLASS_PANES_COLORLESS);

    public ModRecipeProvider(
            PackOutput output,
            CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        buildIntermediateRecipes(output);
        buildBaseRecipes(output);
        buildExpanderRecipes(output);
        buildTurretRecipes(output);
        buildAddonAndUpgradeRecipes(output);
        buildAmmoAndUtilityRecipes(output);
    }

    private void buildIntermediateRecipes(RecipeOutput output) {
        shaped(output, RecipeCategory.MISC, ModItems.SENSOR_TIER_ONE, 1, Items.REDSTONE,
                new String[]{" A ", "ABA", " A "}, Map.of('A', REDSTONE, 'B', PLANKS));
        shaped(output, RecipeCategory.MISC, ModItems.SENSOR_TIER_TWO, 1, Items.IRON_INGOT,
                new String[]{" A ", "ABA", " C "},
                Map.of('A', IRON, 'B', item(ModItems.SENSOR_TIER_ONE), 'C', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.SENSOR_TIER_THREE, 1, Items.GOLD_INGOT,
                new String[]{" C ", "ABA", " C "},
                Map.of('A', GOLD, 'B', item(ModItems.SENSOR_TIER_TWO), 'C', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.SENSOR_TIER_FOUR, 1, Items.DIAMOND,
                new String[]{"ADA", "CBC", "EDE"},
                Map.of('A', GOLD, 'B', item(ModItems.SENSOR_TIER_THREE), 'C', item(ModItems.IO_BUS),
                        'D', DIAMOND, 'E', QUARTZ));
        shaped(output, RecipeCategory.MISC, ModItems.SENSOR_TIER_FIVE, 1, Items.OBSIDIAN,
                new String[]{"ADA", "CBC", "EDE"},
                Map.of('A', GOLD, 'B', item(ModItems.SENSOR_TIER_FOUR), 'C', item(ModItems.IO_BUS),
                        'D', item(Items.GLOWSTONE_DUST), 'E', OBSIDIAN));

        shaped(output, RecipeCategory.MISC, ModItems.CHAMBER_TIER_ONE, 1, Items.COBBLESTONE,
                new String[]{"AAA", " BC", "AAA"},
                Map.of('A', COBBLESTONE, 'B', PLANKS, 'C', REDSTONE));
        shaped(output, RecipeCategory.MISC, ModItems.CHAMBER_TIER_TWO, 1, Items.IRON_INGOT,
                new String[]{"AAA", " BC", "AAA"},
                Map.of('A', IRON, 'B', item(ModItems.CHAMBER_TIER_ONE), 'C', REDSTONE));
        shaped(output, RecipeCategory.MISC, ModItems.CHAMBER_TIER_THREE, 1, Items.GOLD_INGOT,
                new String[]{"AAA", " BC", "AAA"},
                Map.of('A', GOLD, 'B', item(ModItems.CHAMBER_TIER_TWO), 'C', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.CHAMBER_TIER_FOUR, 1, Items.DIAMOND,
                new String[]{"DAD", " BC", "DAD"},
                Map.of('A', DIAMOND, 'B', item(ModItems.CHAMBER_TIER_THREE),
                        'C', item(ModItems.IO_BUS), 'D', QUARTZ));
        shaped(output, RecipeCategory.MISC, ModItems.CHAMBER_TIER_FIVE, 1, Items.OBSIDIAN,
                new String[]{"ADA", " BC", "ADA"},
                Map.of('A', OBSIDIAN, 'B', item(ModItems.CHAMBER_TIER_FOUR),
                        'C', item(ModItems.IO_BUS), 'D', QUARTZ));

        shaped(output, RecipeCategory.MISC, ModItems.BARREL_TIER_ONE, 1, Items.COBBLESTONE,
                new String[]{"AAA", " B ", "AAA"}, Map.of('A', COBBLESTONE, 'B', PLANKS));
        shaped(output, RecipeCategory.MISC, ModItems.BARREL_TIER_TWO, 1, Items.IRON_INGOT,
                new String[]{"AAA", " B ", "AAA"},
                Map.of('A', IRON, 'B', item(ModItems.BARREL_TIER_ONE)));
        shaped(output, RecipeCategory.MISC, ModItems.BARREL_TIER_THREE, 1, Items.GOLD_INGOT,
                new String[]{"AAA", " B ", "AAA"},
                Map.of('A', GOLD, 'B', item(ModItems.BARREL_TIER_TWO)));
        shaped(output, RecipeCategory.MISC, ModItems.BARREL_TIER_FOUR, 1, Items.DIAMOND,
                new String[]{"CAC", " B ", "CAC"},
                Map.of('A', DIAMOND, 'B', item(ModItems.BARREL_TIER_THREE), 'C', QUARTZ));
        shaped(output, RecipeCategory.MISC, ModItems.BARREL_TIER_FIVE, 1, Items.OBSIDIAN,
                new String[]{"AAA", "CBC", "AAA"},
                Map.of('A', OBSIDIAN, 'B', item(ModItems.BARREL_TIER_FOUR),
                        'C', item(Items.GLOWSTONE_DUST)));

        shaped(output, RecipeCategory.REDSTONE, ModItems.IO_BUS, 1, Items.REDSTONE,
                new String[]{" A ", "BBB", " C "}, Map.of('A', GOLD, 'B', REDSTONE, 'C', IRON));
    }

    private void buildBaseRecipes(RecipeOutput output) {
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.TURRET_BASE_TIER_ONE, 1, Items.COBBLESTONE,
                new String[]{"ABA", "BCB", "ABA"},
                Map.of('A', COBBLESTONE, 'B', PLANKS, 'C', item(ModItems.SENSOR_TIER_ONE)));
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.TURRET_BASE_TIER_TWO, 1, Items.IRON_INGOT,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', IRON, 'B', item(ModItems.TURRET_BASE_TIER_ONE),
                        'C', item(ModItems.SENSOR_TIER_TWO), 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.TURRET_BASE_TIER_THREE, 1, Items.GOLD_INGOT,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', GOLD, 'B', item(ModItems.TURRET_BASE_TIER_TWO),
                        'C', item(ModItems.SENSOR_TIER_THREE), 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.TURRET_BASE_TIER_FOUR, 1, Items.DIAMOND,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', DIAMOND, 'B', item(ModItems.TURRET_BASE_TIER_THREE),
                        'C', item(ModItems.SENSOR_TIER_FOUR), 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.TURRET_BASE_TIER_FIVE, 1, Items.OBSIDIAN,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', OBSIDIAN, 'B', item(ModItems.TURRET_BASE_TIER_FOUR),
                        'C', item(ModItems.SENSOR_TIER_FIVE), 'D', item(ModItems.IO_BUS)));
    }

    private void buildExpanderRecipes(RecipeOutput output) {
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.EXPANDER_INV_TIER_ONE, 1, Items.COBBLESTONE,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', COBBLESTONE, 'B', PLANKS, 'C', WOODEN_CHESTS, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.EXPANDER_INV_TIER_TWO, 1, Items.IRON_INGOT,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', IRON, 'B', item(ModItems.EXPANDER_INV_TIER_ONE),
                        'C', WOODEN_CHESTS, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.EXPANDER_INV_TIER_THREE, 1, Items.GOLD_INGOT,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', GOLD, 'B', item(ModItems.EXPANDER_INV_TIER_TWO),
                        'C', WOODEN_CHESTS, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.EXPANDER_INV_TIER_FOUR, 1, Items.DIAMOND,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', DIAMOND, 'B', item(ModItems.EXPANDER_INV_TIER_THREE),
                        'C', WOODEN_CHESTS, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.EXPANDER_INV_TIER_FIVE, 1, Items.OBSIDIAN,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', OBSIDIAN, 'B', item(ModItems.EXPANDER_INV_TIER_FOUR),
                        'C', WOODEN_CHESTS, 'D', item(ModItems.IO_BUS)));

        shaped(output, RecipeCategory.REDSTONE, ModItems.EXPANDER_POWER_TIER_ONE, 1, Items.COBBLESTONE,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', COBBLESTONE, 'B', PLANKS, 'C', REDSTONE, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.REDSTONE, ModItems.EXPANDER_POWER_TIER_TWO, 1, Items.IRON_INGOT,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', IRON, 'B', item(ModItems.EXPANDER_POWER_TIER_ONE),
                        'C', REDSTONE_BLOCK, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.REDSTONE, ModItems.EXPANDER_POWER_TIER_THREE, 1, Items.GOLD_INGOT,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', GOLD, 'B', item(ModItems.EXPANDER_POWER_TIER_TWO),
                        'C', REDSTONE_BLOCK, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.REDSTONE, ModItems.EXPANDER_POWER_TIER_FOUR, 1, Items.DIAMOND,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', DIAMOND, 'B', item(ModItems.EXPANDER_POWER_TIER_THREE),
                        'C', REDSTONE_BLOCK, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.REDSTONE, ModItems.EXPANDER_POWER_TIER_FIVE, 1, Items.OBSIDIAN,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', OBSIDIAN, 'B', item(ModItems.EXPANDER_POWER_TIER_FOUR),
                        'C', REDSTONE_BLOCK, 'D', item(ModItems.IO_BUS)));
    }

    private void buildTurretRecipes(RecipeOutput output) {
        shaped(output, RecipeCategory.COMBAT, ModItems.DISPOSABLE_ITEM_TURRET, 1, Items.COBBLESTONE,
                new String[]{" A ", "CBC", "CDC"},
                Map.of('A', item(ModItems.BARREL_TIER_ONE), 'B', item(ModItems.CHAMBER_TIER_ONE),
                        'C', COBBLESTONE, 'D', REDSTONE));
        shaped(output, RecipeCategory.COMBAT, ModItems.POTATO_CANNON_TURRET, 1, Items.COBBLESTONE,
                new String[]{"CAC", "CAC", "DBD"},
                Map.of('A', item(ModItems.BARREL_TIER_ONE), 'B', item(ModItems.CHAMBER_TIER_ONE),
                        'C', COBBLESTONE, 'D', REDSTONE));
        shaped(output, RecipeCategory.COMBAT, ModItems.MACHINE_GUN_TURRET, 1, Items.IRON_INGOT,
                new String[]{" A ", "CAC", "DBD"},
                Map.of('A', item(ModItems.BARREL_TIER_TWO), 'B', item(ModItems.CHAMBER_TIER_TWO),
                        'C', IRON, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.COMBAT, ModItems.INCENDIARY_TURRET, 1, Items.IRON_INGOT,
                new String[]{"A A", "BCB", "DCD"},
                Map.of('A', item(ModItems.BARREL_TIER_TWO), 'B', item(ModItems.CHAMBER_TIER_TWO),
                        'C', IRON, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.COMBAT, ModItems.GRENADE_TURRET, 1, Items.GOLD_INGOT,
                new String[]{" A ", "CBC", "CDC"},
                Map.of('A', item(ModItems.BARREL_TIER_THREE), 'B', item(ModItems.CHAMBER_TIER_THREE),
                        'C', GOLD, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.COMBAT, ModItems.RELATIVISTIC_TURRET, 1, Items.ENDER_PEARL,
                new String[]{"CAC", "ABA", "CDC"},
                Map.of('A', item(Items.ENDER_PEARL), 'B', item(ModItems.SENSOR_TIER_THREE),
                        'C', GOLD, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.COMBAT, ModItems.ROCKET_TURRET, 1, Items.DIAMOND,
                new String[]{"CAC", "ABA", "EDE"},
                Map.of('A', item(ModItems.BARREL_TIER_FOUR), 'B', item(ModItems.CHAMBER_TIER_FOUR),
                        'C', QUARTZ, 'D', item(ModItems.IO_BUS), 'E', DIAMOND));
        shaped(output, RecipeCategory.COMBAT, ModItems.TELEPORTER_TURRET, 1, Items.ENDER_EYE,
                new String[]{"CEC", "ABA", "CDC"},
                Map.of('A', DIAMOND, 'B', item(ModItems.SENSOR_TIER_FOUR),
                        'C', item(Items.ENDER_EYE), 'D', item(ModItems.IO_BUS), 'E', QUARTZ));
        shaped(output, RecipeCategory.COMBAT, ModItems.LASER_TURRET, 1, Items.OBSIDIAN,
                new String[]{" A ", "CBC", "DCD"},
                Map.of('A', item(ModItems.BARREL_TIER_FIVE), 'B', item(ModItems.CHAMBER_TIER_FIVE),
                        'C', OBSIDIAN, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.COMBAT, ModItems.RAIL_GUN_TURRET, 1, Items.OBSIDIAN,
                new String[]{"CAC", "CAC", "DBD"},
                Map.of('A', item(ModItems.BARREL_TIER_FIVE), 'B', item(ModItems.CHAMBER_TIER_FIVE),
                        'C', OBSIDIAN, 'D', item(ModItems.IO_BUS)));
    }

    private void buildAddonAndUpgradeRecipes(RecipeOutput output) {
        shaped(output, RecipeCategory.MISC, ModItems.ADDON_CONCEALER, 1, Items.IRON_INGOT,
                new String[]{"ABA", "BCD", "ABA"},
                Map.of('A', IRON, 'B', QUARTZ, 'C', WOODEN_CHESTS, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.ADDON_DAMAGE_AMP, 1, Items.ENDER_EYE,
                new String[]{"AAA", "BCB", "AAA"},
                Map.of('A', IRON, 'B', item(Items.ENDER_EYE), 'C', REDSTONE_BLOCK));
        shaped(output, RecipeCategory.MISC, ModItems.ADDON_RECYCLER, 1, Items.ENDER_CHEST,
                new String[]{"ABA", "BCD", "ABA"},
                Map.of('A', GOLD, 'B', item(Items.MAGMA_CREAM),
                        'C', item(Items.ENDER_CHEST), 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.ADDON_REDSTONE_REACTOR, 1, Items.REDSTONE_BLOCK,
                new String[]{"CAC", "ABD", "CAC"},
                Map.of('A', REDSTONE_BLOCK, 'B', item(Items.ENDER_EYE),
                        'C', QUARTZ, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.ADDON_SERIAL_PORT, 1, Items.QUARTZ,
                new String[]{" A ", "BAB", " A "},
                Map.of('A', QUARTZ, 'B', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.ADDON_SOLAR_PANEL, 1, Items.LAPIS_LAZULI,
                new String[]{"AAA", "CBC", "DED"},
                Map.of('A', GLASS_PANE, 'B', LAPIS, 'C', REDSTONE,
                        'D', IRON, 'E', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.ADDON_FAKE_DROPS, 1, Items.LAPIS_LAZULI,
                new String[]{"CAC", "ABD", "CAC"},
                Map.of('A', LAPIS, 'B', item(Items.ENDER_EYE),
                        'C', QUARTZ, 'D', item(ModItems.IO_BUS)));

        shaped(output, RecipeCategory.MISC, ModItems.UPGRADE_ACCURACY, 1, Items.QUARTZ,
                new String[]{" A ", "ABA", " C "},
                Map.of('A', QUARTZ, 'B', GOLD, 'C', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.UPGRADE_EFFICIENCY, 1, Items.ENDER_EYE,
                new String[]{" A ", "ABA", " C "},
                Map.of('A', QUARTZ, 'B', item(Items.ENDER_EYE), 'C', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.UPGRADE_FIRE_RATE, 1, Items.BLAZE_POWDER,
                new String[]{" A ", "ABA", " C "},
                Map.of('A', QUARTZ, 'B', item(Items.BLAZE_POWDER), 'C', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.UPGRADE_RANGE, 1, Items.DIAMOND,
                new String[]{" A ", "ABA", " C "},
                Map.of('A', QUARTZ, 'B', DIAMOND, 'C', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.MISC, ModItems.UPGRADE_SCATTER_SHOT, 1, Items.FLINT,
                new String[]{" A ", "ABA", " C "},
                Map.of('A', QUARTZ, 'B', item(Items.FLINT), 'C', item(ModItems.IO_BUS)));
    }

    private void buildAmmoAndUtilityRecipes(RecipeOutput output) {
        shaped(output, RecipeCategory.COMBAT, ModItems.AMMO_BLAZING_CLAY, 32, Items.BLAZE_POWDER,
                new String[]{"BCB", "CAC", "BCB"},
                Map.of('A', item(Items.BLAZE_POWDER), 'B', item(Items.CLAY_BALL), 'C', REDSTONE));
        shaped(output, RecipeCategory.COMBAT, ModItems.AMMO_BULLET, 64, Items.GUNPOWDER,
                new String[]{" A ", "BC ", " A "},
                Map.of('A', IRON, 'B', item(Items.GUNPOWDER), 'C', REDSTONE));
        shaped(output, RecipeCategory.COMBAT, ModItems.AMMO_FERRO_SLUG, 16, Items.FLINT,
                new String[]{" C ", "CBC", " A "},
                Map.of('A', IRON, 'B', item(Items.FLINT), 'C', REDSTONE));
        shaped(output, RecipeCategory.COMBAT, ModItems.AMMO_GRENADE, 32, Items.GUNPOWDER,
                new String[]{" C ", "ABA", " A "},
                Map.of('A', IRON, 'B', item(Items.GUNPOWDER), 'C', REDSTONE));
        shaped(output, RecipeCategory.COMBAT, ModItems.AMMO_ROCKET, 32, Items.TNT,
                new String[]{" A ", "ABA", "ACA"},
                Map.of('A', IRON, 'B', item(Items.TNT), 'C', REDSTONE));

        shaped(output, RecipeCategory.BUILDING_BLOCKS, ModItems.BASE_ADDON_LOOT_DELETER, 1, Items.OBSIDIAN,
                new String[]{"ABA", "DCD", "ADA"},
                Map.of('A', OBSIDIAN, 'B', IRON, 'C', REDSTONE, 'D', item(ModItems.IO_BUS)));
        shaped(output, RecipeCategory.REDSTONE, ModItems.LEVER_BLOCK, 1, Items.COBBLESTONE,
                new String[]{"AAA", "A  ", "A  "}, Map.of('A', COBBLESTONE));
        shaped(output, RecipeCategory.TOOLS, ModItems.MEMORY_CARD, 1, Items.PAPER,
                new String[]{"BAB", "CEC", "FDF"},
                Map.of('A', GOLD, 'B', REDSTONE, 'C', IRON, 'D', item(ModItems.IO_BUS),
                        'E', item(Items.PAPER), 'F', item(Items.BLUE_DYE)));
    }

    private void shaped(
            RecipeOutput output,
            RecipeCategory category,
            ItemLike result,
            int count,
            ItemLike unlock,
            String[] pattern,
            Map<Character, Ingredient> definitions) {
        ShapedRecipeBuilder builder = ShapedRecipeBuilder.shaped(category, result, count);
        for (String row : pattern) {
            builder.pattern(row);
        }
        definitions.forEach(builder::define);
        builder.unlockedBy("has_ingredient", has(unlock)).save(output);
    }

    private static Ingredient item(ItemLike item) {
        return Ingredient.of(item);
    }
}
