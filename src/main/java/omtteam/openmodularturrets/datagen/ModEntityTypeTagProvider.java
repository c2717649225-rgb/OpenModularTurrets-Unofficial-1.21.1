package omtteam.openmodularturrets.datagen;

import java.util.concurrent.CompletableFuture;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.registration.ModTags;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class ModEntityTypeTagProvider extends TagsProvider<EntityType<?>> {
    public ModEntityTypeTagProvider(PackOutput output,
            CompletableFuture<HolderLookup.Provider> lookupProvider,
            ExistingFileHelper existingFileHelper) {
        super(output, Registries.ENTITY_TYPE, lookupProvider,
                OpenModularTurrets.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(ModTags.EntityTypes.TARGET_BLACKLIST).add(
                BuiltInRegistries.ENTITY_TYPE.getResourceKey(EntityType.ARMOR_STAND)
                        .orElseThrow());
    }
}
