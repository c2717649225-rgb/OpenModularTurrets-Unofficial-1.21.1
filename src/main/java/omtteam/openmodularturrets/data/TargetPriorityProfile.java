package omtteam.openmodularturrets.data;

/**
 * Target weights use the stable 1.12 ordering. Positive distance prefers farther
 * targets and negative distance prefers nearer targets.
 */
public record TargetPriorityProfile(
        int maximumHealth,
        int missingHealth,
        int distance,
        int armor,
        int player) {
    public static TargetPriorityProfile defaults(TurretDefinition definition) {
        return switch (definition) {
            case DISPOSABLE -> new TargetPriorityProfile(5, 5, -5, 1, 10);
            case POTATO -> new TargetPriorityProfile(-5, -5, -5, 1, 10);
            case MACHINE_GUN -> new TargetPriorityProfile(1, 10, -5, -5, 10);
            case INCENDIARY -> new TargetPriorityProfile(10, -5, -2, 10, 10);
            case GRENADE, ROCKET, PLASMA ->
                    new TargetPriorityProfile(10, 10, 20, 1, 10);
            case RELATIVISTIC -> new TargetPriorityProfile(1, 1, 0, 10, 10);
            case TELEPORTER -> new TargetPriorityProfile(1, -10, 0, 10, 10);
            case LASER -> new TargetPriorityProfile(5, 10, 2, -10, 10);
            case RAIL_GUN -> new TargetPriorityProfile(10, 10, 20, 40, 10);
        };
    }
}
