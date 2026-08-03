package omtteam.openmodularturrets.data;

public final class TargetingRules {
    private static final double MAX_HEALTH_NORMALIZER = 1024.0D;
    private static final double MAX_ARMOR_NORMALIZER = 30.0D;

    private TargetingRules() {
    }

    /**
     * Shared 1.12.2 ownership/trust rule for players and owned tameable entities.
     */
    public static boolean ownershipAllowsTarget(boolean owner, boolean trusted,
            boolean damageTrustedPlayers) {
        return !owner && (damageTrustedPlayers || !trusted);
    }

    public static double score(TargetPriorityProfile priorities, float health,
            float maximumHealth, double distance, int maximumRange, int armor,
            boolean player) {
        double safeMaximumHealth = Math.max(1.0D, maximumHealth);
        double maximumHealthMetric =
                Math.clamp(safeMaximumHealth / MAX_HEALTH_NORMALIZER, 0.0D, 1.0D);
        double missingHealthMetric =
                Math.clamp((safeMaximumHealth - health) / safeMaximumHealth, 0.0D, 1.0D);
        double distanceMetric = maximumRange <= 0 ? 0.0D
                : Math.clamp(distance / maximumRange, 0.0D, 1.0D);
        double armorMetric =
                Math.clamp((armor + 1.0D) / MAX_ARMOR_NORMALIZER, 0.0D, 1.0D);
        return maximumHealthMetric * priorities.maximumHealth()
                + missingHealthMetric * priorities.missingHealth()
                + distanceMetric * priorities.distance()
                + armorMetric * priorities.armor()
                + (player ? priorities.player() : 0.0D);
    }
}
