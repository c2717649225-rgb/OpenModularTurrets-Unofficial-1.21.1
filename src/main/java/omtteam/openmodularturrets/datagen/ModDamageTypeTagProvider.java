package omtteam.openmodularturrets.datagen;

import java.util.concurrent.CompletableFuture;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.damage.ModDamageTypes;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class ModDamageTypeTagProvider extends TagsProvider<DamageType> {
    private static final TagKey<DamageType> BYPASSES_ARMOR = vanilla("bypasses_armor");
    private static final TagKey<DamageType> IS_EXPLOSION = vanilla("is_explosion");
    private static final TagKey<DamageType> IS_FIRE = vanilla("is_fire");
    private static final TagKey<DamageType> IS_PROJECTILE = vanilla("is_projectile");

    public ModDamageTypeTagProvider(PackOutput output,
            CompletableFuture<HolderLookup.Provider> lookupProvider,
            ExistingFileHelper existingFileHelper) {
        super(output, Registries.DAMAGE_TYPE, lookupProvider,
                OpenModularTurrets.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(BYPASSES_ARMOR).add(ModDamageTypes.TURRET_ARMOR_PIERCING);
        tag(IS_EXPLOSION).add(ModDamageTypes.TURRET_EXPLOSION);
        tag(IS_FIRE).add(ModDamageTypes.TURRET_FIRE);
        tag(IS_PROJECTILE).add(ModDamageTypes.TURRET_PROJECTILE,
                ModDamageTypes.TURRET_FIRE, ModDamageTypes.TURRET_ARMOR_PIERCING);
    }

    private static TagKey<DamageType> vanilla(String path) {
        return TagKey.create(Registries.DAMAGE_TYPE,
                ResourceLocation.withDefaultNamespace(path));
    }
}
