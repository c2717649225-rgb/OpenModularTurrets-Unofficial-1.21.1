package omtteam.openmodularturrets.data;

import java.util.List;
import javax.annotation.Nullable;

import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.damage.ModDamageTypes;
import omtteam.openmodularturrets.entity.ProjectileKind;
import omtteam.openmodularturrets.registration.ModTags;
import omtteam.openmodularturrets.turret.behavior.BeamVolleyStrategy;
import omtteam.openmodularturrets.turret.behavior.ProjectileVolleyStrategy;
import omtteam.openmodularturrets.turret.behavior.StatusEffectVolleyStrategy;
import omtteam.openmodularturrets.turret.behavior.TeleportVolleyStrategy;
import omtteam.openmodularturrets.turret.behavior.TurretBehavior;
import omtteam.openmodularturrets.turret.behavior.VolleyStrategy;

import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;

/**
 * Stable gameplay values ported from the 1.12.2 default configuration.
 * Fire interval is measured in game ticks.
 * Implements the extensible TurretBehavior strategy interface.
 */
public enum TurretDefinition implements TurretBehavior {
    DISPOSABLE("disposable_item_turret", 1, 10, 25, 2.0F, 2, 50.0D, 4, 0.1D, 2, 0.05F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.DISPOSABLE_AMMO, ShotKind.PROJECTILE,
            new ProjectileVolleyStrategy(ProjectileKind.DISPOSABLE, 1.6F)),
    POTATO("potato_cannon_turret", 1, 15, 35, 3.0F, 10, 30.0D, 4, 0.1D, 2, 0.05F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.POTATO_AMMO, ShotKind.PROJECTILE,
            new ProjectileVolleyStrategy(ProjectileKind.POTATO, 1.6F)),
    MACHINE_GUN("machine_gun_turret", 2, 18, 8, 2.0F, 100, 30.0D, 4, 0.1D, 2, 0.06F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.BULLETS, ShotKind.PROJECTILE,
            new ProjectileVolleyStrategy(ProjectileKind.BULLET, 3.0F)),
    INCENDIARY("incendiary_turret", 2, 12, 25, 2.0F, 250, 30.0D, 4, 0.1D, 2, 0.05F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.INCENDIARY_AMMO, ShotKind.INCENDIARY,
            new ProjectileVolleyStrategy(ProjectileKind.BLAZING_CLAY, 1.6F)),
    GRENADE("grenade_turret", 3, 18, 40, 8.0F, 3_000, 30.0D, 3, 0.1D, 2, 0.08F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.GRENADES, ShotKind.EXPLOSIVE,
            new ProjectileVolleyStrategy(ProjectileKind.GRENADE, 1.6F)),
    RELATIVISTIC("relativistic_turret", 3, 20, 25, 0.0F, 5_000, 0.0D, 4, 0.1D, 2, 0.0F, 0.2D, 0.08D, 0.10D,
            null, ShotKind.RELATIVISTIC,
            new StatusEffectVolleyStrategy(List.of(
                    new StatusEffectVolleyStrategy.EffectSpec(MobEffects.MOVEMENT_SLOWDOWN, 200, 3),
                    new StatusEffectVolleyStrategy.EffectSpec(MobEffects.WEAKNESS, 200, 3)))),
    ROCKET("rocket_turret", 4, 30, 30, 10.0F, 5_000, 10.0D, 3, 0.1D, 2, 0.08F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.ROCKETS, ShotKind.EXPLOSIVE,
            new ProjectileVolleyStrategy(ProjectileKind.ROCKET, 0.24F)),
    TELEPORTER("teleporter_turret", 4, 20, 100, 0.0F, 15_000, 0.0D, 1, 0.1D, 2, 0.0F, 0.2D, 0.08D, 0.10D,
            null, ShotKind.TELEPORT,
            TeleportVolleyStrategy.INSTANCE),
    LASER("laser_turret", 5, 25, 10, 4.0F, 8_000, 10.0D, 4, 0.125D, 2, 0.06F, 0.2D, 0.08D, 0.10D,
            null, ShotKind.BEAM,
            new BeamVolleyStrategy(ModDamageTypes.TURRET_PROJECTILE, false, false)),
    RAIL_GUN("rail_gun_turret", 5, 30, 100, 25.0F, 25_000, 3.0D, 2, 0.2D, 2, 0.10F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.SLUGS, ShotKind.BEAM,
            new BeamVolleyStrategy(ModDamageTypes.TURRET_ARMOR_PIERCING, true, true)),
    PLASMA("plasma_turret", 5, 20, 60, 20.0F, 40_000, 8.0D, 1, 0.2D, 1, 0.10F, 0.2D, 0.08D, 0.10D,
            null, ShotKind.PLASMA,
            new ProjectileVolleyStrategy(ProjectileKind.PLASMA, 3.0F));

    private final String id;
    private final int requiredBaseTier;
    private final int baseRange;
    private final int fireInterval;
    private final float damage;
    private final int energyCost;
    private final double baseAccuracyDeviation;
    private final int maxSimultaneous;
    private final double fireRateUpgrade;
    private final int rangeUpgrade;
    private final float damageAmpFraction;
    private final double accuracyUpgrade;
    private final double efficiencyUpgrade;
    private final double recyclerNegateChance;
    private final TagKey<Item> ammoTag;
    private final ShotKind shotKind;
    private final VolleyStrategy volleyStrategy;

    TurretDefinition(String id, int requiredBaseTier, int baseRange, int fireInterval,
            float damage, int energyCost, double baseAccuracyDeviation, int maxSimultaneous,
            double fireRateUpgrade, int rangeUpgrade, float damageAmpFraction,
            double accuracyUpgrade, double efficiencyUpgrade, double recyclerNegateChance,
            @Nullable TagKey<Item> ammoTag,
            ShotKind shotKind,
            VolleyStrategy volleyStrategy) {
        this.id = id;
        this.requiredBaseTier = requiredBaseTier;
        this.baseRange = baseRange;
        this.fireInterval = fireInterval;
        this.damage = damage;
        this.energyCost = energyCost;
        this.baseAccuracyDeviation = baseAccuracyDeviation;
        this.maxSimultaneous = maxSimultaneous;
        this.fireRateUpgrade = fireRateUpgrade;
        this.rangeUpgrade = rangeUpgrade;
        this.damageAmpFraction = damageAmpFraction;
        this.accuracyUpgrade = accuracyUpgrade;
        this.efficiencyUpgrade = efficiencyUpgrade;
        this.recyclerNegateChance = recyclerNegateChance;
        this.ammoTag = ammoTag;
        this.shotKind = shotKind;
        this.volleyStrategy = volleyStrategy;
    }

    @Override
    public String id() { return id; }
    @Override
    public int requiredBaseTier() { return requiredBaseTier; }
    @Override
    public int baseRange() { return ModServerConfig.turret(this).baseRange(); }
    @Override
    public int fireInterval() { return ModServerConfig.turret(this).fireInterval(); }
    @Override
    public float damage() { return ModServerConfig.turret(this).damage(); }
    @Override
    public int energyCost() { return ModServerConfig.turret(this).energyCost(); }
    @Override
    public double baseAccuracyDeviation() { return ModServerConfig.turret(this).baseAccuracyDeviation(); }
    @Override
    public int maxSimultaneous() { return ModServerConfig.turret(this).maxSimultaneous(); }
    @Override
    public double fireRateUpgrade() { return ModServerConfig.turret(this).fireRateUpgrade(); }
    @Override
    public int rangeUpgrade() { return ModServerConfig.turret(this).rangeUpgrade(); }
    @Override
    public float damageAmpFraction() { return ModServerConfig.turret(this).damageAmpFraction(); }
    @Override
    public double accuracyUpgrade() { return ModServerConfig.turret(this).accuracyUpgrade(); }
    @Override
    public double efficiencyUpgrade() { return ModServerConfig.turret(this).efficiencyUpgrade(); }
    @Override
    public double recyclerNegateChance() { return ModServerConfig.turret(this).recyclerNegateChance(); }
    @Override
    @Nullable public TagKey<Item> ammoTag() { return ammoTag; }
    @Override
    public VolleyStrategy volleyStrategy() { return volleyStrategy; }

    public int defaultBaseRange() { return baseRange; }
    public int defaultFireInterval() { return fireInterval; }
    public float defaultDamage() { return damage; }
    public int defaultEnergyCost() { return energyCost; }
    public double defaultBaseAccuracyDeviation() { return baseAccuracyDeviation; }
    public int defaultMaxSimultaneous() { return maxSimultaneous; }
    public double defaultFireRateUpgrade() { return fireRateUpgrade; }
    public int defaultRangeUpgrade() { return rangeUpgrade; }
    public float defaultDamageAmpFraction() { return damageAmpFraction; }
    public double defaultAccuracyUpgrade() { return accuracyUpgrade; }
    public double defaultEfficiencyUpgrade() { return efficiencyUpgrade; }
    public double defaultRecyclerNegateChance() { return recyclerNegateChance; }
    public ShotKind shotKind() { return shotKind; }

    public enum ShotKind {
        PROJECTILE,
        INCENDIARY,
        EXPLOSIVE,
        RELATIVISTIC,
        TELEPORT,
        BEAM,
        PLASMA
    }
}
