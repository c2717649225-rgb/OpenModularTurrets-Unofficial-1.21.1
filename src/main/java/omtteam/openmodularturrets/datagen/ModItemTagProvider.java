package omtteam.openmodularturrets.datagen;

import java.util.concurrent.CompletableFuture;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.registration.ModItems;
import omtteam.openmodularturrets.registration.ModTags;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class ModItemTagProvider extends ItemTagsProvider {
    public ModItemTagProvider(PackOutput output,
            CompletableFuture<HolderLookup.Provider> lookupProvider,
            CompletableFuture<TagsProvider.TagLookup<Block>> blockTags,
            ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, blockTags, OpenModularTurrets.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(ModTags.Items.BULLETS).add(ModItems.AMMO_BULLET.value());
        tag(ModTags.Items.GRENADES).add(ModItems.AMMO_GRENADE.value());
        tag(ModTags.Items.ROCKETS).add(ModItems.AMMO_ROCKET.value());
        tag(ModTags.Items.SLUGS).add(ModItems.AMMO_FERRO_SLUG.value());
        tag(ModTags.Items.INCENDIARY_AMMO).add(ModItems.AMMO_BLAZING_CLAY.value());
        tag(ModTags.Items.POTATO_AMMO).add(Items.POTATO);
        tag(ModTags.Items.DISPOSABLE_AMMO)
                .add(Items.COBBLESTONE)
                .addTag(ItemTags.PLANKS);
        tag(ModTags.Items.AMMUNITION)
                .addTag(ModTags.Items.BULLETS)
                .addTag(ModTags.Items.GRENADES)
                .addTag(ModTags.Items.ROCKETS)
                .addTag(ModTags.Items.SLUGS)
                .addTag(ModTags.Items.INCENDIARY_AMMO)
                .addTag(ModTags.Items.POTATO_AMMO)
                .addTag(ModTags.Items.DISPOSABLE_AMMO);

        tag(ModTags.Items.ADDONS).add(
                ModItems.ADDON_CONCEALER.value(),
                ModItems.ADDON_DAMAGE_AMP.value(),
                ModItems.ADDON_POTENTIA.value(),
                ModItems.ADDON_RECYCLER.value(),
                ModItems.ADDON_REDSTONE_REACTOR.value(),
                ModItems.ADDON_SERIAL_PORT.value(),
                ModItems.ADDON_SOLAR_PANEL.value(),
                ModItems.ADDON_FAKE_DROPS.value());
        tag(ModTags.Items.UPGRADES).add(
                ModItems.UPGRADE_ACCURACY.value(),
                ModItems.UPGRADE_EFFICIENCY.value(),
                ModItems.UPGRADE_FIRE_RATE.value(),
                ModItems.UPGRADE_RANGE.value(),
                ModItems.UPGRADE_SCATTER_SHOT.value());
    }
}
