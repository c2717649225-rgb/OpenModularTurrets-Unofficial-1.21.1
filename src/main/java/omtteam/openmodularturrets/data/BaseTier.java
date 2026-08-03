package omtteam.openmodularturrets.data;

import omtteam.openmodularturrets.config.ModServerConfig;

public enum BaseTier {
    ONE(1, 500, 50, 1, 0, 0),
    TWO(2, 50_000, 100, 1, 2, 1),
    THREE(3, 150_000, 1_000, 2, 2, 1),
    FOUR(4, 500_000, 2_500, 3, 2, 1),
    FIVE(5, 10_000_000, 50_000, 4, 2, 2);

    private final int level;
    private final int energyCapacity;
    private final int maxReceive;
    private final int maxTurrets;
    private final int addonSlots;
    private final int upgradeSlots;

    BaseTier(int level, int energyCapacity, int maxReceive, int maxTurrets,
            int addonSlots, int upgradeSlots) {
        this.level = level;
        this.energyCapacity = energyCapacity;
        this.maxReceive = maxReceive;
        this.maxTurrets = maxTurrets;
        this.addonSlots = addonSlots;
        this.upgradeSlots = upgradeSlots;
    }

    public int level() { return level; }
    public int energyCapacity() { return ModServerConfig.base(this).energyCapacity(); }
    public int maxReceive() { return ModServerConfig.base(this).maxReceive(); }
    public int maxTurrets() { return ModServerConfig.base(this).maxTurrets(); }
    public int defaultEnergyCapacity() { return energyCapacity; }
    public int defaultMaxReceive() { return maxReceive; }
    public int defaultMaxTurrets() { return maxTurrets; }
    public int addonSlots() { return addonSlots; }
    public int upgradeSlots() { return upgradeSlots; }
}
