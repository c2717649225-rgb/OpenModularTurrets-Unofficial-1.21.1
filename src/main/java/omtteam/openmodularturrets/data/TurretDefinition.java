package omtteam.openmodularturrets.data;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.registration.ModTags;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Stable gameplay values ported from the 1.12.2 default configuration.
 * Fire interval is measured in game ticks.
 */
public enum TurretDefinition {
    DISPOSABLE("disposable_item_turret", 1, 10, 25, 2.0F, 2, 50.0D, 4, 0.1D, 2, 0.05F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.DISPOSABLE_AMMO, ShotKind.PROJECTILE),
    POTATO("potato_cannon_turret", 1, 15, 35, 3.0F, 10, 30.0D, 4, 0.1D, 2, 0.05F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.POTATO_AMMO, ShotKind.PROJECTILE),
    MACHINE_GUN("machine_gun_turret", 2, 18, 8, 2.0F, 100, 30.0D, 4, 0.1D, 2, 0.06F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.BULLETS, ShotKind.PROJECTILE),
    INCENDIARY("incendiary_turret", 2, 12, 25, 2.0F, 250, 30.0D, 4, 0.1D, 2, 0.05F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.INCENDIARY_AMMO, ShotKind.INCENDIARY),
    GRENADE("grenade_turret", 3, 18, 40, 8.0F, 3_000, 30.0D, 3, 0.1D, 2, 0.08F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.GRENADES, ShotKind.EXPLOSIVE),
    RELATIVISTIC("relativistic_turret", 3, 20, 25, 0.0F, 5_000, 0.0D, 4, 0.1D, 2, 0.0F, 0.2D, 0.08D, 0.10D,
            null, ShotKind.RELATIVISTIC),
    ROCKET("rocket_turret", 4, 30, 30, 10.0F, 5_000, 10.0D, 3, 0.1D, 2, 0.08F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.ROCKETS, ShotKind.EXPLOSIVE),
    TELEPORTER("teleporter_turret", 4, 20, 100, 0.0F, 15_000, 0.0D, 1, 0.1D, 2, 0.0F, 0.2D, 0.08D, 0.10D,
            null, ShotKind.TELEPORT),
    LASER("laser_turret", 5, 25, 10, 4.0F, 8_000, 10.0D, 4, 0.125D, 2, 0.06F, 0.2D, 0.08D, 0.10D,
            null, ShotKind.BEAM),
    RAIL_GUN("rail_gun_turret", 5, 30, 100, 25.0F, 25_000, 3.0D, 2, 0.2D, 2, 0.10F, 0.2D, 0.08D, 0.10D,
            ModTags.Items.SLUGS, ShotKind.BEAM),
    PLASMA("plasma_turret", 5, 20, 60, 20.0F, 40_000, 8.0D, 1, 0.2D, 1, 0.10F, 0.2D, 0.08D, 0.10D,
            null, ShotKind.PLASMA);

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

    TurretDefinition(String id, int requiredBaseTier, int baseRange, int fireInterval,
            float damage, int energyCost, double baseAccuracyDeviation, int maxSimultaneous,
            double fireRateUpgrade, int rangeUpgrade, float damageAmpFraction,
            double accuracyUpgrade, double efficiencyUpgrade, double recyclerNegateChance,
            @Nullable TagKey<Item> ammoTag,
            ShotKind shotKind) {
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
    }

    public String id() { return id; }
    public int requiredBaseTier() { return requiredBaseTier; }
    public int baseRange() { return ModServerConfig.turret(this).baseRange(); }
    public int fireInterval() { return ModServerConfig.turret(this).fireInterval(); }
    public float damage() { return ModServerConfig.turret(this).damage(); }
    public int energyCost() { return ModServerConfig.turret(this).energyCost(); }
    public double baseAccuracyDeviation() { return ModServerConfig.turret(this).baseAccuracyDeviation(); }
    public int maxSimultaneous() { return ModServerConfig.turret(this).maxSimultaneous(); }
    public double fireRateUpgrade() { return ModServerConfig.turret(this).fireRateUpgrade(); }
    public int rangeUpgrade() { return ModServerConfig.turret(this).rangeUpgrade(); }
    public float damageAmpFraction() { return ModServerConfig.turret(this).damageAmpFraction(); }
    public double accuracyUpgrade() { return ModServerConfig.turret(this).accuracyUpgrade(); }
    public double efficiencyUpgrade() { return ModServerConfig.turret(this).efficiencyUpgrade(); }
    public double recyclerNegateChance() { return ModServerConfig.turret(this).recyclerNegateChance(); }
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
    @Nullable public TagKey<Item> ammoTag() { return ammoTag; }
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
