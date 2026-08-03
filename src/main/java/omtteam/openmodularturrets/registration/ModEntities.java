package omtteam.openmodularturrets.registration;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.entity.TurretProjectileEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, OpenModularTurrets.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<TurretProjectileEntity>>
            DISPOSABLE_ITEM_PROJECTILE = projectile("disposable_item_projectile");
    public static final DeferredHolder<EntityType<?>, EntityType<TurretProjectileEntity>>
            POTATO_PROJECTILE = projectile("potato_projectile");
    public static final DeferredHolder<EntityType<?>, EntityType<TurretProjectileEntity>>
            BULLET_PROJECTILE = projectile("bullet_projectile");
    public static final DeferredHolder<EntityType<?>, EntityType<TurretProjectileEntity>>
            BLAZING_CLAY_PROJECTILE = projectile("blazing_clay_projectile");
    public static final DeferredHolder<EntityType<?>, EntityType<TurretProjectileEntity>>
            GRENADE_PROJECTILE = projectile("grenade_projectile");
    public static final DeferredHolder<EntityType<?>, EntityType<TurretProjectileEntity>>
            ROCKET_PROJECTILE = projectile("rocket_projectile");
    public static final DeferredHolder<EntityType<?>, EntityType<TurretProjectileEntity>>
            PLASMA_PROJECTILE = projectile("plasma_projectile");

    private ModEntities() {
    }

    private static DeferredHolder<EntityType<?>, EntityType<TurretProjectileEntity>> projectile(
            String id) {
        return ENTITY_TYPES.register(id, () -> EntityType.Builder
                .<TurretProjectileEntity>of(TurretProjectileEntity::new, MobCategory.MISC)
                .sized(0.25F, 0.25F)
                .clientTrackingRange(8)
                .updateInterval(1)
                .build(id));
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
    }
}
