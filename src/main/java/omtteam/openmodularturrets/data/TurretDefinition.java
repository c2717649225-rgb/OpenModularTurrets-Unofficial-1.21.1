package omtteam.openmodularturrets.data;

import java.util.List;
import java.util.Objects;
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
 *
 * <p>Constants are declared through the named {@link Builder} so every value
 * is labeled at its use site; the golden-defaults GameTest pins all values
 * against transcription drift.</p>
 */
public enum TurretDefinition implements TurretBehavior {
    DISPOSABLE(def("disposable_item_turret")
            .tier(1).baseRange(10).fireInterval(25).damage(2.0F).energyCost(2)
            .accuracyDeviation(50.0D).maxSimultaneous(4)
            .fireRateUpgrade(0.1D).rangeUpgrade(2).damageAmpFraction(0.05F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .ammoTag(ModTags.Items.DISPOSABLE_AMMO).shotKind(ShotKind.PROJECTILE)
            .volleyStrategy(new ProjectileVolleyStrategy(ProjectileKind.DISPOSABLE, 1.6F))),
    POTATO(def("potato_cannon_turret")
            .tier(1).baseRange(15).fireInterval(35).damage(3.0F).energyCost(10)
            .accuracyDeviation(30.0D).maxSimultaneous(4)
            .fireRateUpgrade(0.1D).rangeUpgrade(2).damageAmpFraction(0.05F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .ammoTag(ModTags.Items.POTATO_AMMO).shotKind(ShotKind.PROJECTILE)
            .volleyStrategy(new ProjectileVolleyStrategy(ProjectileKind.POTATO, 1.6F))),
    MACHINE_GUN(def("machine_gun_turret")
            .tier(2).baseRange(18).fireInterval(8).damage(2.0F).energyCost(100)
            .accuracyDeviation(30.0D).maxSimultaneous(4)
            .fireRateUpgrade(0.1D).rangeUpgrade(2).damageAmpFraction(0.06F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .ammoTag(ModTags.Items.BULLETS).shotKind(ShotKind.PROJECTILE)
            .volleyStrategy(new ProjectileVolleyStrategy(ProjectileKind.BULLET, 3.0F))),
    INCENDIARY(def("incendiary_turret")
            .tier(2).baseRange(12).fireInterval(25).damage(2.0F).energyCost(250)
            .accuracyDeviation(30.0D).maxSimultaneous(4)
            .fireRateUpgrade(0.1D).rangeUpgrade(2).damageAmpFraction(0.05F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .ammoTag(ModTags.Items.INCENDIARY_AMMO).shotKind(ShotKind.INCENDIARY)
            .volleyStrategy(new ProjectileVolleyStrategy(ProjectileKind.BLAZING_CLAY, 1.6F))),
    GRENADE(def("grenade_turret")
            .tier(3).baseRange(18).fireInterval(40).damage(8.0F).energyCost(3_000)
            .accuracyDeviation(30.0D).maxSimultaneous(3)
            .fireRateUpgrade(0.1D).rangeUpgrade(2).damageAmpFraction(0.08F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .ammoTag(ModTags.Items.GRENADES).shotKind(ShotKind.EXPLOSIVE)
            .volleyStrategy(new ProjectileVolleyStrategy(ProjectileKind.GRENADE, 1.6F))),
    RELATIVISTIC(def("relativistic_turret")
            .tier(3).baseRange(20).fireInterval(25).damage(0.0F).energyCost(5_000)
            .accuracyDeviation(0.0D).maxSimultaneous(4)
            .fireRateUpgrade(0.1D).rangeUpgrade(2).damageAmpFraction(0.0F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .shotKind(ShotKind.RELATIVISTIC).fixedLaunchSound(0.6F, 1.0F)
            .volleyStrategy(new StatusEffectVolleyStrategy(List.of(
                    new StatusEffectVolleyStrategy.EffectSpec(MobEffects.MOVEMENT_SLOWDOWN, 200, 3),
                    new StatusEffectVolleyStrategy.EffectSpec(MobEffects.WEAKNESS, 200, 3))))),
    ROCKET(def("rocket_turret")
            .tier(4).baseRange(30).fireInterval(30).damage(10.0F).energyCost(5_000)
            .accuracyDeviation(10.0D).maxSimultaneous(3)
            .fireRateUpgrade(0.1D).rangeUpgrade(2).damageAmpFraction(0.08F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .ammoTag(ModTags.Items.ROCKETS).shotKind(ShotKind.EXPLOSIVE)
            .volleyStrategy(new ProjectileVolleyStrategy(ProjectileKind.ROCKET, 0.24F))),
    TELEPORTER(def("teleporter_turret")
            .tier(4).baseRange(20).fireInterval(100).damage(0.0F).energyCost(15_000)
            .accuracyDeviation(0.0D).maxSimultaneous(1)
            .fireRateUpgrade(0.1D).rangeUpgrade(2).damageAmpFraction(0.0F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .shotKind(ShotKind.TELEPORT).fixedLaunchSound(0.6F, 1.0F)
            .volleyStrategy(TeleportVolleyStrategy.INSTANCE)),
    LASER(def("laser_turret")
            .tier(5).baseRange(25).fireInterval(10).damage(4.0F).energyCost(8_000)
            .accuracyDeviation(10.0D).maxSimultaneous(4)
            .fireRateUpgrade(0.125D).rangeUpgrade(2).damageAmpFraction(0.06F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .shotKind(ShotKind.BEAM)
            .volleyStrategy(new BeamVolleyStrategy(ModDamageTypes.TURRET_PROJECTILE, false, false))),
    RAIL_GUN(def("rail_gun_turret")
            .tier(5).baseRange(30).fireInterval(100).damage(25.0F).energyCost(25_000)
            .accuracyDeviation(3.0D).maxSimultaneous(2)
            .fireRateUpgrade(0.2D).rangeUpgrade(2).damageAmpFraction(0.10F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .ammoTag(ModTags.Items.SLUGS).shotKind(ShotKind.BEAM)
            .volleyStrategy(new BeamVolleyStrategy(ModDamageTypes.TURRET_ARMOR_PIERCING, true, true))),
    PLASMA(def("plasma_turret")
            .tier(5).baseRange(20).fireInterval(60).damage(20.0F).energyCost(40_000)
            .accuracyDeviation(8.0D).maxSimultaneous(1)
            .fireRateUpgrade(0.2D).rangeUpgrade(1).damageAmpFraction(0.10F)
            .accuracyUpgrade(0.2D).efficiencyUpgrade(0.08D).recyclerChance(0.10D)
            .shotKind(ShotKind.PLASMA)
            .volleyStrategy(new ProjectileVolleyStrategy(ProjectileKind.PLASMA, 3.0F)));

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
    @Nullable
    private final TagKey<Item> ammoTag;
    private final ShotKind shotKind;
    private final VolleyStrategy volleyStrategy;
    @Nullable
    private final LaunchSound launchSound;

    TurretDefinition(Builder builder) {
        this.id = builder.id;
        this.requiredBaseTier = builder.requiredBaseTier;
        this.baseRange = builder.baseRange;
        this.fireInterval = builder.fireInterval;
        this.damage = builder.damage;
        this.energyCost = builder.energyCost;
        this.baseAccuracyDeviation = builder.baseAccuracyDeviation;
        this.maxSimultaneous = builder.maxSimultaneous;
        this.fireRateUpgrade = builder.fireRateUpgrade;
        this.rangeUpgrade = builder.rangeUpgrade;
        this.damageAmpFraction = builder.damageAmpFraction;
        this.accuracyUpgrade = builder.accuracyUpgrade;
        this.efficiencyUpgrade = builder.efficiencyUpgrade;
        this.recyclerNegateChance = builder.recyclerNegateChance;
        this.ammoTag = builder.ammoTag;
        this.shotKind = builder.shotKind;
        this.volleyStrategy = builder.volleyStrategy;
        this.launchSound = builder.launchSound;
    }

    private static Builder def(String id) {
        return new Builder(id);
    }

    /**
     * Presentation-layer taxonomy consumed by rendering and sound selection.
     * Behavioral execution lives exclusively in the bound {@link VolleyStrategy};
     * do not branch gameplay logic on these constants.
     */
    public enum ShotKind {
        PROJECTILE,
        INCENDIARY,
        EXPLOSIVE,
        RELATIVISTIC,
        TELEPORT,
        BEAM,
        PLASMA
    }

    /**
     * Fixed launch-sound parameters for turret types whose legacy sound must
     * not vary randomly; {@code null} means "use config volume and a random pitch".
     */
    public record LaunchSound(float volume, float pitch) {
    }

    private static final class Builder {
        private final String id;
        private int requiredBaseTier = 1;
        private int baseRange;
        private int fireInterval;
        private float damage;
        private int energyCost;
        private double baseAccuracyDeviation;
        private int maxSimultaneous;
        private double fireRateUpgrade;
        private int rangeUpgrade;
        private float damageAmpFraction;
        private double accuracyUpgrade;
        private double efficiencyUpgrade;
        private double recyclerNegateChance;
        @Nullable
        private TagKey<Item> ammoTag;
        private ShotKind shotKind;
        private VolleyStrategy volleyStrategy;
        @Nullable
        private LaunchSound launchSound;

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        private Builder tier(int requiredBaseTier) {
            this.requiredBaseTier = requiredBaseTier;
            return this;
        }

        private Builder baseRange(int baseRange) {
            this.baseRange = baseRange;
            return this;
        }

        private Builder fireInterval(int fireInterval) {
            this.fireInterval = fireInterval;
            return this;
        }

        private Builder damage(float damage) {
            this.damage = damage;
            return this;
        }

        private Builder energyCost(int energyCost) {
            this.energyCost = energyCost;
            return this;
        }

        private Builder accuracyDeviation(double baseAccuracyDeviation) {
            this.baseAccuracyDeviation = baseAccuracyDeviation;
            return this;
        }

        private Builder maxSimultaneous(int maxSimultaneous) {
            this.maxSimultaneous = maxSimultaneous;
            return this;
        }

        private Builder fireRateUpgrade(double fireRateUpgrade) {
            this.fireRateUpgrade = fireRateUpgrade;
            return this;
        }

        private Builder rangeUpgrade(int rangeUpgrade) {
            this.rangeUpgrade = rangeUpgrade;
            return this;
        }

        private Builder damageAmpFraction(float damageAmpFraction) {
            this.damageAmpFraction = damageAmpFraction;
            return this;
        }

        private Builder accuracyUpgrade(double accuracyUpgrade) {
            this.accuracyUpgrade = accuracyUpgrade;
            return this;
        }

        private Builder efficiencyUpgrade(double efficiencyUpgrade) {
            this.efficiencyUpgrade = efficiencyUpgrade;
            return this;
        }

        private Builder recyclerChance(double recyclerNegateChance) {
            this.recyclerNegateChance = recyclerNegateChance;
            return this;
        }

        private Builder ammoTag(TagKey<Item> ammoTag) {
            this.ammoTag = ammoTag;
            return this;
        }

        private Builder shotKind(ShotKind shotKind) {
            this.shotKind = Objects.requireNonNull(shotKind, "shotKind");
            return this;
        }

        private Builder volleyStrategy(VolleyStrategy volleyStrategy) {
            this.volleyStrategy = Objects.requireNonNull(volleyStrategy, "volleyStrategy");
            return this;
        }

        private Builder fixedLaunchSound(float volume, float pitch) {
            this.launchSound = new LaunchSound(volume, pitch);
            return this;
        }
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
    @Nullable
    public LaunchSound launchSound() { return launchSound; }
}
