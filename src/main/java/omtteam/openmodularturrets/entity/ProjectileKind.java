package omtteam.openmodularturrets.entity;

import java.util.Locale;

import omtteam.openmodularturrets.registration.ModEntities;
import omtteam.openmodularturrets.registration.ModItems;
import omtteam.openmodularturrets.data.TurretDefinition;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.util.function.Supplier;
public enum ProjectileKind {
    DISPOSABLE("disposable", 0.03D, 40, ModItems.AMMO_FAKE_DISPOSABLE),
    POTATO("potato", 0.03D, 40, () -> Items.POTATO),
    BULLET("bullet", 0.0D, 40, ModItems.AMMO_BULLET),
    BLAZING_CLAY("blazing_clay", 0.0D, 40, ModItems.AMMO_BLAZING_CLAY),
    GRENADE("grenade", 0.03D, 40, ModItems.AMMO_GRENADE),
    ROCKET("rocket", 0.0D, 40, ModItems.AMMO_ROCKET),
    PLASMA("plasma", 0.001D, 30, () -> Items.EMERALD);

    /** Age at which an unfused grenade airbursts; the direct-entity bounce
     *  writes a lower fuse age from TurretProjectileEntity so the burst still
     *  happens mid-flight after impact. */
    public static final int GRENADE_FUSE_AGE_TICKS = 39;

    private final String id;
    private final double gravity;
    private final int maximumLifetime;
    private final Supplier<? extends Item> displayItem;

    ProjectileKind(String id, double gravity, int maximumLifetime,
            Supplier<? extends Item> displayItem) {
        this.id = id;
        this.gravity = gravity;
        this.maximumLifetime = maximumLifetime;
        this.displayItem = displayItem;
    }

    public String id() {
        return id;
    }

    public double gravity() {
        return gravity;
    }

    public int maximumLifetime() {
        return maximumLifetime;
    }

    public boolean shouldExpire(int age) {
        return age > maximumLifetime;
    }

    public boolean fuseExpired(int age) {
        return this == GRENADE && age >= GRENADE_FUSE_AGE_TICKS;
    }

    public Item displayItem() {
        return displayItem.get();
    }

    public EntityType<TurretProjectileEntity> entityType() {
        return switch (this) {
            case DISPOSABLE -> ModEntities.DISPOSABLE_ITEM_PROJECTILE.value();
            case POTATO -> ModEntities.POTATO_PROJECTILE.value();
            case BULLET -> ModEntities.BULLET_PROJECTILE.value();
            case BLAZING_CLAY -> ModEntities.BLAZING_CLAY_PROJECTILE.value();
            case GRENADE -> ModEntities.GRENADE_PROJECTILE.value();
            case ROCKET -> ModEntities.ROCKET_PROJECTILE.value();
            case PLASMA -> ModEntities.PLASMA_PROJECTILE.value();
        };
    }

    public TurretDefinition turretDefinition() {
        return switch (this) {
            case DISPOSABLE -> TurretDefinition.DISPOSABLE;
            case POTATO -> TurretDefinition.POTATO;
            case BULLET -> TurretDefinition.MACHINE_GUN;
            case BLAZING_CLAY -> TurretDefinition.INCENDIARY;
            case GRENADE -> TurretDefinition.GRENADE;
            case ROCKET -> TurretDefinition.ROCKET;
            case PLASMA -> TurretDefinition.PLASMA;
        };
    }

    public float terrainExplosionStrength(boolean enabled) {
        if (!enabled) {
            return this == ROCKET || this == GRENADE ? 0.1F : 0.0F;
        }
        return switch (this) {
            case ROCKET -> 2.3F;
            case GRENADE -> 1.4F;
            default -> 0.0F;
        };
    }

    public static ProjectileKind byId(String id) {
        for (ProjectileKind value : values()) {
            if (value.id.equals(id.toLowerCase(Locale.ROOT))) {
                return value;
            }
        }
        return DISPOSABLE;
    }

    public static ProjectileKind forType(EntityType<?> type) {
        for (ProjectileKind value : values()) {
            if (value.entityType() == type) {
                return value;
            }
        }
        return DISPOSABLE;
    }
}
