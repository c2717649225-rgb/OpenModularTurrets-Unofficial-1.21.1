package omtteam.openmodularturrets.turret.behavior;

import javax.annotation.Nullable;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Common, self-describing behavior contract for all turret types.
 * Encapsulates gameplay parameters, upgrade curves, and the firing execution strategy.
 */
public interface TurretBehavior {
    String id();

    int requiredBaseTier();

    int baseRange();

    int fireInterval();

    float damage();

    int energyCost();

    double baseAccuracyDeviation();

    int maxSimultaneous();

    double fireRateUpgrade();

    int rangeUpgrade();

    float damageAmpFraction();

    double accuracyUpgrade();

    double efficiencyUpgrade();

    double recyclerNegateChance();

    @Nullable
    TagKey<Item> ammoTag();

    VolleyStrategy volleyStrategy();
}
