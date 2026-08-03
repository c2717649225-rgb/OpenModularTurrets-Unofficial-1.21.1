package omtteam.openmodularturrets.damage;

import omtteam.openmodularturrets.OpenModularTurrets;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

public final class ModDamageTypes {
    public static final ResourceKey<DamageType> TURRET_PROJECTILE = create("turret_projectile");
    public static final ResourceKey<DamageType> TURRET_EXPLOSION = create("turret_explosion");
    public static final ResourceKey<DamageType> TURRET_FIRE = create("turret_fire");
    public static final ResourceKey<DamageType> TURRET_ARMOR_PIERCING =
            create("turret_armor_piercing");

    private ModDamageTypes() {
    }

    private static ResourceKey<DamageType> create(String path) {
        return ResourceKey.create(Registries.DAMAGE_TYPE,
                ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, path));
    }
}
