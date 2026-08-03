package omtteam.openmodularturrets.config;

import java.util.EnumMap;
import java.util.Map;

import omtteam.openmodularturrets.data.BaseTier;
import omtteam.openmodularturrets.data.TurretDefinition;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-authoritative gameplay tuning. Values are read only through the getters so
 * config loading and reloads are never captured during static initialization.
 */
public final class ModServerConfig {
    private static final int MAX_ENERGY = 2_000_000_000;
    private static final int MAX_RANGE = 1_000;
    private static final int MAX_FIRE_INTERVAL = 1_200;
    public static final double MAX_TURRET_DAMAGE = 1_000_000.0D;
    private static final int MAX_SIMULTANEOUS = 64;

    public static final ModConfigSpec SPEC;

    private static final Map<BaseTier, BaseConfigValues> BASE_VALUES = new EnumMap<>(BaseTier.class);
    private static final Map<BaseTier, ModConfigSpec.IntValue> POWER_EXPANDER_CAPACITIES =
            new EnumMap<>(BaseTier.class);
    private static final Map<TurretDefinition, TurretConfigValues> TURRET_VALUES =
            new EnumMap<>(TurretDefinition.class);

    private static final ModConfigSpec.IntValue SOLAR_GENERATION;
    private static final ModConfigSpec.IntValue REDSTONE_REACTOR_GENERATION;
    private static final ModConfigSpec.BooleanValue REQUIRE_AMMO;
    private static final ModConfigSpec.BooleanValue ALLOW_BASE_CAMOUFLAGE;
    private static final ModConfigSpec.BooleanValue CONCEAL_WITHOUT_ADDON;
    private static final ModConfigSpec.IntValue TARGET_SEARCH_TICKS;
    private static final ModConfigSpec.BooleanValue WARNING_MESSAGE;
    private static final ModConfigSpec.BooleanValue WARNING_SOUND;
    private static final ModConfigSpec.IntValue WARNING_DISTANCE;
    private static final ModConfigSpec.DoubleValue TURRET_SOUND_VOLUME;
    private static final ModConfigSpec.BooleanValue TURRET_KILLS_DROP_LOOT;
    private static final ModConfigSpec.BooleanValue LOOT_ADDONS_OVERRIDE;
    private static final ModConfigSpec.BooleanValue GLOBAL_TARGET_PLAYERS;
    private static final ModConfigSpec.BooleanValue GLOBAL_TARGET_NEUTRALS;
    private static final ModConfigSpec.BooleanValue GLOBAL_TARGET_HOSTILES;
    private static final ModConfigSpec.BooleanValue DAMAGE_TRUSTED_PLAYERS;
    private static final ModConfigSpec.BooleanValue CAN_OP_ACCESS_OWNED_BLOCKS;
    private static final ModConfigSpec.BooleanValue OFFLINE_MODE_SUPPORT;
    private static final ModConfigSpec.BooleanValue BASE_BREAKABLE;
    private static final ModConfigSpec.BooleanValue ATTACHMENTS_BREAKABLE;
    private static final ModConfigSpec.BooleanValue ROCKETS_HOME;
    private static final ModConfigSpec.BooleanValue ROCKETS_HURT_DRAGON;
    private static final ModConfigSpec.BooleanValue ROCKETS_DESTROY_BLOCKS;
    private static final ModConfigSpec.BooleanValue GRENADES_DESTROY_BLOCKS;
    private static final ModConfigSpec.BooleanValue RAILGUN_DESTROYS_BLOCKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Turret base capacities and limits.").push("bases");
        int[] baseHardnessDefaults = {20, 30, 40, 50, 60};
        int[] baseResistanceDefaults = {5, 10, 15, 20, 25};
        for (BaseTier tier : BaseTier.values()) {
            builder.push("tier_" + tier.level());
            BASE_VALUES.put(tier, new BaseConfigValues(
                    builder.defineInRange("energy_capacity", tier.defaultEnergyCapacity(), 0, MAX_ENERGY),
                    builder.defineInRange("max_receive", tier.defaultMaxReceive(), 0, MAX_ENERGY),
                    builder.defineInRange("max_turrets", tier.defaultMaxTurrets(), 0, MAX_SIMULTANEOUS),
                    builder.defineInRange("hardness", baseHardnessDefaults[tier.ordinal()], 0.1D, 10_000.0D),
                    builder.defineInRange("blast_resistance", baseResistanceDefaults[tier.ordinal()], 0.0D, 10_000_000.0D)));
            builder.pop();
        }
        builder.pop();

        builder.comment("Extra energy capacity granted by each power expander tier.").push("power_expanders");
        int[] powerExpanderDefaults = {2_500, 25_000, 75_000, 250_000, 5_000_000};
        for (BaseTier tier : BaseTier.values()) {
            POWER_EXPANDER_CAPACITIES.put(tier, builder.defineInRange(
                    "tier_" + tier.level() + "_capacity", powerExpanderDefaults[tier.ordinal()],
                    0, MAX_ENERGY));
        }
        builder.pop();

        builder.comment("Passive addon energy generation per game tick.").push("addons");
        SOLAR_GENERATION = builder.defineInRange("solar_panel_generation", 10, 0, MAX_ENERGY);
        REDSTONE_REACTOR_GENERATION = builder.defineInRange("redstone_reactor_generation", 1_600, 0, MAX_ENERGY);
        builder.pop();

        builder.comment("Active legacy gameplay switches. Defaults match OMT 1.12.2.")
                .push("general");
        REQUIRE_AMMO = builder.define("turrets_need_ammo", true);
        ALLOW_BASE_CAMOUFLAGE = builder.define("allow_base_camouflage", true);
        CONCEAL_WITHOUT_ADDON = builder.define("turrets_conceal_without_addon", false);
        TARGET_SEARCH_TICKS = builder.defineInRange("turret_target_search_ticks", 10, 1, 1_200);
        WARNING_MESSAGE = builder.define("turret_warning_message", true);
        WARNING_SOUND = builder.define("turret_alarm_sound", true);
        WARNING_DISTANCE = builder.defineInRange("turret_warning_distance", 5, 0, MAX_RANGE);
        TURRET_SOUND_VOLUME = builder.defineInRange("turret_sound_volume", 4.0D, 0.0D, 16.0D);
        TURRET_KILLS_DROP_LOOT = builder.define("turret_kills_drop_mob_loot", true);
        LOOT_ADDONS_OVERRIDE = builder.define("loot_addons_override_mob_loot", true);
        GLOBAL_TARGET_PLAYERS = builder.define("global_target_players", true);
        GLOBAL_TARGET_NEUTRALS = builder.define("global_target_neutral_mobs", true);
        GLOBAL_TARGET_HOSTILES = builder.define("global_target_hostile_mobs", true);
        DAMAGE_TRUSTED_PLAYERS = builder.define("damage_trusted_players", false);
        CAN_OP_ACCESS_OWNED_BLOCKS = builder.define("can_op_access_owned_blocks", false);
        OFFLINE_MODE_SUPPORT = builder.define("offline_mode_support", false);
        BASE_BREAKABLE = builder.define("base_breakable", false);
        ATTACHMENTS_BREAKABLE = builder.define("turret_attachments_breakable", false);
        ROCKETS_HOME = builder.define("rockets_home", false);
        ROCKETS_HURT_DRAGON = builder.define("rockets_hurt_ender_dragon", false);
        ROCKETS_DESTROY_BLOCKS = builder.define("rockets_destroy_blocks", false);
        GRENADES_DESTROY_BLOCKS = builder.define("grenades_destroy_blocks", false);
        RAILGUN_DESTROYS_BLOCKS = builder.define("railgun_destroys_blocks", false);
        builder.pop();

        builder.comment("Per-turret server-authoritative combat tuning.").push("turrets");
        for (TurretDefinition turret : TurretDefinition.values()) {
            builder.push(turret.id());
            TURRET_VALUES.put(turret, new TurretConfigValues(
                    builder.define("enabled", true),
                    builder.defineInRange("base_range", turret.defaultBaseRange(), 0, MAX_RANGE),
                    builder.defineInRange("fire_interval", turret.defaultFireInterval(), 1, MAX_FIRE_INTERVAL),
                    builder.defineInRange("damage", turret.defaultDamage(), 0.0D,
                            MAX_TURRET_DAMAGE),
                    builder.defineInRange("energy_cost", turret.defaultEnergyCost(), 0, MAX_ENERGY),
                    builder.defineInRange("base_accuracy_deviation", turret.defaultBaseAccuracyDeviation(), 0.0D, 360.0D),
                    builder.defineInRange("max_simultaneous", turret.defaultMaxSimultaneous(), 1, MAX_SIMULTANEOUS),
                    builder.defineInRange("fire_rate_upgrade", turret.defaultFireRateUpgrade(), 0.0D, 1.0D),
                    builder.defineInRange("range_upgrade", turret.defaultRangeUpgrade(), 0, MAX_RANGE),
                    builder.defineInRange("damage_amp_fraction", turret.defaultDamageAmpFraction(), 0.0D, 1.0D),
                    builder.defineInRange("accuracy_upgrade", turret.defaultAccuracyUpgrade(), 0.0D, 1.0D),
                    builder.defineInRange("efficiency_upgrade", turret.defaultEfficiencyUpgrade(), 0.0D, 1.0D),
                    builder.defineInRange("recycler_negate_chance", turret.defaultRecyclerNegateChance(), 0.0D, 1.0D)));
            builder.pop();
        }
        builder.pop();

        SPEC = builder.build();
    }

    private ModServerConfig() {
    }

    public static BaseValues base(BaseTier tier) {
        if (!SPEC.isLoaded()) {
            return new BaseValues(tier.defaultEnergyCapacity(), tier.defaultMaxReceive(),
                    tier.defaultMaxTurrets(), 10.0F + tier.level() * 10.0F,
                    tier.level() * 5.0F);
        }
        BaseConfigValues values = BASE_VALUES.get(tier);
        return new BaseValues(values.energyCapacity().get(), values.maxReceive().get(),
                values.maxTurrets().get(), values.hardness().get().floatValue(),
                values.blastResistance().get().floatValue());
    }

    public static int powerExpanderCapacity(BaseTier tier) {
        if (!SPEC.isLoaded()) {
            return switch (tier) {
                case ONE -> 2_500;
                case TWO -> 25_000;
                case THREE -> 75_000;
                case FOUR -> 250_000;
                case FIVE -> 5_000_000;
            };
        }
        return POWER_EXPANDER_CAPACITIES.get(tier).get();
    }

    public static int solarGeneration() {
        return SPEC.isLoaded() ? SOLAR_GENERATION.get() : 10;
    }

    public static int redstoneReactorGeneration() {
        return SPEC.isLoaded() ? REDSTONE_REACTOR_GENERATION.get() : 1_600;
    }

    public static TurretValues turret(TurretDefinition turret) {
        if (!SPEC.isLoaded()) {
            return new TurretValues(true, turret.defaultBaseRange(), turret.defaultFireInterval(),
                    turret.defaultDamage(), turret.defaultEnergyCost(),
                    turret.defaultBaseAccuracyDeviation(), turret.defaultMaxSimultaneous(),
                    turret.defaultFireRateUpgrade(), turret.defaultRangeUpgrade(),
                    turret.defaultDamageAmpFraction(), turret.defaultAccuracyUpgrade(),
                    turret.defaultEfficiencyUpgrade(), turret.defaultRecyclerNegateChance());
        }
        TurretConfigValues values = TURRET_VALUES.get(turret);
        return new TurretValues(
                values.enabled().get(),
                values.baseRange().get(),
                values.fireInterval().get(),
                values.damage().get().floatValue(),
                values.energyCost().get(),
                values.baseAccuracyDeviation().get(),
                values.maxSimultaneous().get(),
                values.fireRateUpgrade().get(),
                values.rangeUpgrade().get(),
                values.damageAmpFraction().get().floatValue(),
                values.accuracyUpgrade().get(),
                values.efficiencyUpgrade().get(),
                values.recyclerNegateChance().get());
    }

    public static boolean requireAmmo() {
        return !SPEC.isLoaded() || REQUIRE_AMMO.get();
    }

    public static boolean allowBaseCamouflage() {
        return !SPEC.isLoaded() || ALLOW_BASE_CAMOUFLAGE.get();
    }

    public static boolean concealWithoutAddon() {
        return SPEC.isLoaded() && CONCEAL_WITHOUT_ADDON.get();
    }

    public static int targetSearchTicks() {
        return SPEC.isLoaded() ? TARGET_SEARCH_TICKS.get() : 10;
    }

    public static boolean warningMessage() {
        return !SPEC.isLoaded() || WARNING_MESSAGE.get();
    }

    public static boolean warningSound() {
        return !SPEC.isLoaded() || WARNING_SOUND.get();
    }

    public static int warningDistance() {
        return SPEC.isLoaded() ? WARNING_DISTANCE.get() : 5;
    }

    public static float turretSoundVolume() {
        return SPEC.isLoaded() ? TURRET_SOUND_VOLUME.get().floatValue() : 4.0F;
    }

    public static boolean turretKillsDropLoot() {
        return !SPEC.isLoaded() || TURRET_KILLS_DROP_LOOT.get();
    }

    public static boolean lootAddonsOverride() {
        return !SPEC.isLoaded() || LOOT_ADDONS_OVERRIDE.get();
    }

    public static boolean globalTargetPlayers() {
        return !SPEC.isLoaded() || GLOBAL_TARGET_PLAYERS.get();
    }

    public static boolean globalTargetNeutrals() {
        return !SPEC.isLoaded() || GLOBAL_TARGET_NEUTRALS.get();
    }

    public static boolean globalTargetHostiles() {
        return !SPEC.isLoaded() || GLOBAL_TARGET_HOSTILES.get();
    }

    public static boolean damageTrustedPlayers() {
        return SPEC.isLoaded() && DAMAGE_TRUSTED_PLAYERS.get();
    }

    /** Legacy OMLib switch: operators may open, but not modify, owned bases. */
    public static boolean canOpAccessOwnedBlocks() {
        return SPEC.isLoaded() && CAN_OP_ACCESS_OWNED_BLOCKS.get();
    }

    /** Legacy OMLib switch: ownership and trust may fall back to player names. */
    public static boolean offlineModeSupport() {
        return SPEC.isLoaded() && OFFLINE_MODE_SUPPORT.get();
    }

    public static boolean baseBreakable() {
        return SPEC.isLoaded() && BASE_BREAKABLE.get();
    }

    public static boolean attachmentsBreakable() {
        return SPEC.isLoaded() && ATTACHMENTS_BREAKABLE.get();
    }

    public static boolean rocketsHome() {
        return SPEC.isLoaded() && ROCKETS_HOME.get();
    }

    public static boolean rocketsHurtDragon() {
        return SPEC.isLoaded() && ROCKETS_HURT_DRAGON.get();
    }

    public static boolean rocketsDestroyBlocks() {
        return SPEC.isLoaded() && ROCKETS_DESTROY_BLOCKS.get();
    }

    public static boolean grenadesDestroyBlocks() {
        return SPEC.isLoaded() && GRENADES_DESTROY_BLOCKS.get();
    }

    public static boolean railgunDestroysBlocks() {
        return SPEC.isLoaded() && RAILGUN_DESTROYS_BLOCKS.get();
    }

    public record BaseValues(int energyCapacity, int maxReceive, int maxTurrets,
            float hardness, float blastResistance) {
    }

    public record TurretValues(
            boolean enabled,
            int baseRange,
            int fireInterval,
            float damage,
            int energyCost,
            double baseAccuracyDeviation,
            int maxSimultaneous,
            double fireRateUpgrade,
            int rangeUpgrade,
            float damageAmpFraction,
            double accuracyUpgrade,
            double efficiencyUpgrade,
            double recyclerNegateChance) {
    }

    private record BaseConfigValues(
            ModConfigSpec.IntValue energyCapacity,
            ModConfigSpec.IntValue maxReceive,
            ModConfigSpec.IntValue maxTurrets,
            ModConfigSpec.DoubleValue hardness,
            ModConfigSpec.DoubleValue blastResistance) {
    }

    private record TurretConfigValues(
            ModConfigSpec.BooleanValue enabled,
            ModConfigSpec.IntValue baseRange,
            ModConfigSpec.IntValue fireInterval,
            ModConfigSpec.DoubleValue damage,
            ModConfigSpec.IntValue energyCost,
            ModConfigSpec.DoubleValue baseAccuracyDeviation,
            ModConfigSpec.IntValue maxSimultaneous,
            ModConfigSpec.DoubleValue fireRateUpgrade,
            ModConfigSpec.IntValue rangeUpgrade,
            ModConfigSpec.DoubleValue damageAmpFraction,
            ModConfigSpec.DoubleValue accuracyUpgrade,
            ModConfigSpec.DoubleValue efficiencyUpgrade,
            ModConfigSpec.DoubleValue recyclerNegateChance) {
    }
}
