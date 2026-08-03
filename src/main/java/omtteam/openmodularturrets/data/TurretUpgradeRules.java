package omtteam.openmodularturrets.data;

/**
 * Pure, server-safe upgrade calculations ported from the 1.12.2 defaults.
 */
public final class TurretUpgradeRules {
    /** Legacy 1.12 cap: the same upgrade stacks up to four levels. */
    public static final int MAX_STACK_LEVELS = 4;

    private TurretUpgradeRules() {
    }

    public static int fireInterval(TurretDefinition definition, int level) {
        double divisor = 1.0D + definition.fireRateUpgrade() * nonNegative(level);
        return Math.max(1, (int) Math.ceil(definition.fireInterval() / divisor));
    }

    public static int projectileCount(int scatterLevel) {
        return 1 + nonNegative(scatterLevel);
    }

    public static int energyCost(TurretDefinition definition, int efficiencyLevel,
            int scatterLevel) {
        double efficiency = Math.max(0.0D,
                1.0D - definition.efficiencyUpgrade() * nonNegative(efficiencyLevel));
        double cost = definition.energyCost() * efficiency * projectileCount(scatterLevel);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, Math.round(cost)));
    }

    public static int maximumRange(TurretDefinition definition, int rangeLevel) {
        long range = (long) definition.baseRange()
                + (long) definition.rangeUpgrade() * nonNegative(rangeLevel);
        return (int) Math.min(Integer.MAX_VALUE, range);
    }

    public static double accuracyDeviation(TurretDefinition definition, int accuracyLevel,
            int scatterLevel) {
        double accuracyDivisor = Math.pow(
                1.0D + definition.accuracyUpgrade() * nonNegative(accuracyLevel), 1.5D);
        double scatterPenalty = 1.0D + nonNegative(scatterLevel) / 10.0D;
        return definition.baseAccuracyDeviation() / accuracyDivisor * scatterPenalty;
    }

    public static float projectileInaccuracy(TurretDefinition definition, int accuracyLevel,
            int scatterLevel) {
        return (float) (accuracyDeviation(definition, accuracyLevel, scatterLevel) / 20.0D);
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }
}
