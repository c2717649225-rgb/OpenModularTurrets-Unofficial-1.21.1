package omtteam.openmodularturrets.data;

import omtteam.openmodularturrets.config.ModServerConfig;

public final class TurretAddonRules {
    public static final int SOLAR_GENERATION = 10;
    public static final int REACTOR_DUST_GENERATION = 1_600;
    public static final int REACTOR_BLOCK_GENERATION = REACTOR_DUST_GENERATION * 9;
    public static final int REACTOR_INTERVAL = 20;
    public static final int CONCEAL_DELAY = 40;

    private TurretAddonRules() {
    }

    public static int solarGeneration() {
        return ModServerConfig.solarGeneration();
    }

    public static int reactorDustGeneration() {
        return ModServerConfig.redstoneReactorGeneration();
    }

    public static int reactorBlockGeneration() {
        return (int) Math.min(Integer.MAX_VALUE, (long) reactorDustGeneration() * 9L);
    }

    public static boolean recyclerPreservesAmmo(TurretDefinition definition, double roll) {
        return roll >= 0.0D && roll < definition.recyclerNegateChance();
    }

    public static float amplifiedDamage(TurretDefinition definition, float baseDamage,
            float currentHealth, int ampLevel) {
        if (ampLevel <= 0 || definition.damageAmpFraction() <= 0.0F) {
            return baseDamage;
        }
        return baseDamage + (float) Math.floor(Math.max(0.0F, currentHealth))
                * definition.damageAmpFraction() * ampLevel;
    }

    public static int fakeDropsLevel(int addonCount) {
        return addonCount <= 0 ? -1 : Math.min(3, addonCount - 1);
    }

    public static ReactorFuel selectReactorFuel(int freeCapacity, boolean hasBlock,
            boolean hasDust) {
        if (hasBlock && freeCapacity > reactorBlockGeneration()) {
            return ReactorFuel.BLOCK;
        }
        if (hasDust && freeCapacity > reactorDustGeneration()) {
            return ReactorFuel.DUST;
        }
        return ReactorFuel.NONE;
    }

    public enum ReactorFuel {
        NONE,
        DUST,
        BLOCK;

        public int generation() {
            return switch (this) {
                case NONE -> 0;
                case DUST -> reactorDustGeneration();
                case BLOCK -> reactorBlockGeneration();
            };
        }
    }
}
