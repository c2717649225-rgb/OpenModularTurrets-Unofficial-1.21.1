package omtteam.openmodularturrets.item;

import java.util.List;
import java.util.Locale;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.block.InventoryExpanderBlock;
import omtteam.openmodularturrets.block.ManualChargerBlock;
import omtteam.openmodularturrets.block.PowerExpanderBlock;
import omtteam.openmodularturrets.block.TurretBaseBlock;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.client.ClientTooltipUtil;
import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.data.TurretAddonRules;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretUpgradeRules;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
/**
 * Compact 1.21 replacement for the 1.12 ItemBlock/metadata addInformation
 * classes, located in item package for P0-3 physical client isolation compliance.
 */
public final class OmtTooltips {
    private OmtTooltips() {
    }

    public static void append(Item item, List<Component> lines) {
        // Legacy 1.12 ships no tooltip at all for crafting components and the
        // I/O bus (plain Items without addInformation), so neither does this
        // port - not even the shift hint.
        if (isCraftingComponent(id(item))) {
            return;
        }
        boolean shift = isShiftDown();
        // Turrets get their own two-stage card: a short grey description plus
        // the shift hint, expanding into the sectioned info/damage blocks.
        if (item instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof TurretHeadBlock) {
            appendTurret(blockItem, lines, shift);
            return;
        }
        if (!shift) {
            lines.add(text("tooltip.openmodularturrets.hold_shift")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            return;
        }
        if (item instanceof BlockItem blockItem) {
            appendBlock(blockItem, lines);
        } else {
            appendItem(item, lines);
        }
    }

    private static void appendTurret(BlockItem item, List<Component> lines,
            boolean shift) {
        TurretDefinition definition =
                ((TurretHeadBlock) item.getBlock()).definition();
        lines.add(text("tooltip.openmodularturrets.turret.desc")
                .withStyle(ChatFormatting.GRAY));
        if (!shift) {
            lines.add(text("tooltip.openmodularturrets.hold_shift")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            return;
        }
        lines.add(Component.empty());
        lines.add(text("tooltip.openmodularturrets.section.info")
                .withStyle(ChatFormatting.GOLD));
        kvGray(lines, "tooltip.openmodularturrets.label.tier",
                definition.requiredBaseTier());
        kvGray(lines, "tooltip.openmodularturrets.label.range", definition.baseRange());
        kvGray(lines, "tooltip.openmodularturrets.label.accuracy",
                text(accuracyKey(definition)));
        kvGray(lines, "tooltip.openmodularturrets.label.ammo",
                text(ammoTypeKey(definition)));
        kvGray(lines, "tooltip.openmodularturrets.label.tier_required",
                text(baseTierKey(definition.requiredBaseTier())));
        kvGray(lines, "tooltip.openmodularturrets.label.turret_limit",
                definition.maxSimultaneous());
        lines.add(Component.empty());
        lines.add(text("tooltip.openmodularturrets.section.damage")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        kvGray(lines, "tooltip.openmodularturrets.label.damage_stat",
                String.format(Locale.ROOT, "%.1f", definition.damage() / 2.0F)
                        + " " + text("tooltip.openmodularturrets.health").getString());
        kvGray(lines, "tooltip.openmodularturrets.label.aoe", aoeFor(definition));
        kvGray(lines, "tooltip.openmodularturrets.label.fire_rate",
                String.format(Locale.ROOT, "%.1f", 20.0D / definition.fireInterval()));
        kvGray(lines, "tooltip.openmodularturrets.label.energy_stat",
                definition.energyCost() + " RF");
        flavour(lines, turretFlavourKey(definition));
    }

    private static boolean isCraftingComponent(String id) {
        return id.startsWith("sensor_tier_") || id.startsWith("chamber_tier_")
                || id.startsWith("barrel_tier_") || "io_bus".equals(id);
    }

    private static boolean isShiftDown() {
        return FMLEnvironment.dist == Dist.CLIENT && ClientTooltipUtil.hasShiftDown();
    }

    private static void appendBlock(BlockItem item, List<Component> lines) {
        if (item.getBlock() instanceof TurretBaseBlock base) {
            ModServerConfig.BaseValues values = ModServerConfig.base(base.tier());
            int addons = Math.min(2, base.tier().addonSlots());
            int upgrades = Math.min(2, base.tier().upgradeSlots());
            lines.add(text("tooltip.openmodularturrets.base.desc").withStyle(ChatFormatting.GRAY));
            lines.add(Component.empty());
            lines.add(text("tooltip.openmodularturrets.section.energy")
                    .withStyle(ChatFormatting.AQUA));
            kv(lines, "tooltip.openmodularturrets.label.rf_max", values.energyCapacity() + " RF");
            kv(lines, "tooltip.openmodularturrets.label.rf_io", values.maxReceive() + " RF/t");
            lines.add(Component.empty());
            lines.add(text("tooltip.openmodularturrets.section.extras")
                    .withStyle(ChatFormatting.GREEN));
            lines.add(text("tooltip.openmodularturrets.extras.addons." + addons)
                    .withStyle(ChatFormatting.GRAY));
            if (upgrades > 0) {
                lines.add(text("tooltip.openmodularturrets.extras.upgrade." + upgrades)
                        .withStyle(ChatFormatting.GRAY));
            }
            kv(lines, "tooltip.openmodularturrets.label.turret_limit", values.maxTurrets());
            lines.add(Component.empty());
            if (base.tier().level() == 5) {
                flavour(lines, "tooltip.openmodularturrets.flavour.base.5a");
                lines.add(text("tooltip.openmodularturrets.flavour.base.5b")
                        .withStyle(ChatFormatting.DARK_GRAY));
            } else {
                flavour(lines, "tooltip.openmodularturrets.flavour.base."
                        + base.tier().level());
            }
            return;
        }
        if (item.getBlock() instanceof InventoryExpanderBlock expander) {
            lines.add(Component.empty());
            lines.add(text("tooltip.openmodularturrets.expander.inv.title")
                    .withStyle(ChatFormatting.GOLD));
            lines.add(Component.empty());
            lines.add(text("tooltip.openmodularturrets.inventory_expander.desc")
                    .withStyle(ChatFormatting.WHITE));
            lines.add(text("tooltip.openmodularturrets.expander.inv.stack")
                    .append(Component.literal(" " + (1 << (expander.tier() + 1)) + "."))
                    .withStyle(ChatFormatting.WHITE));
            flavour(lines, "tooltip.openmodularturrets.flavour.expander.inv."
                    + (expander.tier() + 1));
            return;
        }
        if (item.getBlock() instanceof PowerExpanderBlock expander) {
            lines.add(Component.empty());
            lines.add(text("tooltip.openmodularturrets.expander.power.title")
                    .withStyle(ChatFormatting.GOLD));
            lines.add(Component.empty());
            lines.add(text("tooltip.openmodularturrets.power_expander.value",
                    expander.extraCapacity()).withStyle(ChatFormatting.WHITE));
            flavour(lines, "tooltip.openmodularturrets.flavour.expander.power."
                    + (expander.tier() + 1));
            return;
        }
        if (item.getBlock() instanceof ManualChargerBlock) {
            lines.add(Component.empty());
            lines.add(text("tooltip.openmodularturrets.manual_charger.desc")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        String id = id(item);
        if ("base_addon_loot_deleter".equals(id)) {
            lines.add(Component.empty());
            lines.add(text("tooltip.openmodularturrets.loot_deleter.title")
                    .withStyle(ChatFormatting.GOLD));
            lines.add(Component.empty());
            lines.add(text("tooltip.openmodularturrets.loot_deleter.detail1"));
            lines.add(text("tooltip.openmodularturrets.loot_deleter.detail2"));
            flavour(lines, "tooltip.openmodularturrets.flavour.loot_deleter");
        }
    }

    private static void appendItem(Item item, List<Component> lines) {
        String id = id(item);
        if (id.startsWith("addon_")) {
            title(lines, "tooltip.openmodularturrets.addon.title", ChatFormatting.RED);
            appendAddonStats(id, lines);
            return;
        }
        if (id.startsWith("upgrade_")) {
            title(lines, "tooltip.openmodularturrets.upgrade.title");
            appendUpgradeStats(id, lines);
            return;
        }
        if (id.startsWith("ammo_")) {
            title(lines, "tooltip.openmodularturrets.ammo.title");
            lines.add(text("tooltip.openmodularturrets.ammo." + id + ".desc"));
            return;
        }
        // Throwable bullets/grenades and the crafting components had no
        // shift-expanded info in 1.12; they fall through with no description.
    }

    private static void appendAddonStats(String id, List<Component> lines) {
        String name = id.substring("addon_".length());
        lines.add(text("tooltip.openmodularturrets.addon." + name + ".desc")
                .withStyle(ChatFormatting.WHITE));
        switch (name) {
            case "solar_panel" -> stat(lines, "tooltip.openmodularturrets.addon.solar_panel.value",
                    TurretAddonRules.solarGeneration());
            case "redstone_reactor" -> stat(lines,
                    "tooltip.openmodularturrets.addon.redstone_reactor.value",
                    TurretAddonRules.reactorDustGeneration(),
                    TurretAddonRules.reactorBlockGeneration(),
                    TurretAddonRules.REACTOR_INTERVAL / 20);
            case "damage_amp" -> stat(lines, "tooltip.openmodularturrets.addon.damage_amp.value",
                    percentRange(minMaxFraction(TurretDefinition::damageAmpFraction)));
            case "recycler" -> stat(lines, "tooltip.openmodularturrets.addon.recycler.value",
                    percentRange(minMaxFraction(TurretDefinition::recyclerNegateChance)));
            case "concealer" -> stat(lines, "tooltip.openmodularturrets.addon.concealer.value",
                    TurretAddonRules.CONCEAL_DELAY / 20, TurretAddonRules.CONCEAL_DELAY);
            case "fake_drops" -> stat(lines, "tooltip.openmodularturrets.addon.fake_drops.value",
                    TurretAddonRules.fakeDropsLevel(Integer.MAX_VALUE));
            default -> { }
        }
        flavour(lines, "tooltip.openmodularturrets.addon." + name + ".flavour");
    }

    private static void appendUpgradeStats(String id, List<Component> lines) {
        String name = id.substring("upgrade_".length());
        lines.add(text("tooltip.openmodularturrets.upgrade.turretinfo")
                .withStyle(ChatFormatting.WHITE));
        lines.add(text("tooltip.openmodularturrets.upgrade.stacks")
                .withStyle(ChatFormatting.WHITE));
        switch (name) {
            case "accuracy" -> stat(lines, "tooltip.openmodularturrets.upgrade.accuracy.value",
                    spreadReductionPercent());
            case "efficiency" -> stat(lines, "tooltip.openmodularturrets.upgrade.efficiency.value",
                    percentRange(minMaxFraction(TurretDefinition::efficiencyUpgrade)));
            case "fire_rate" -> stat(lines, "tooltip.openmodularturrets.upgrade.fire_rate.value",
                    percentRange(minMaxFraction(TurretDefinition::fireRateUpgrade)));
            case "range" -> stat(lines, "tooltip.openmodularturrets.upgrade.range.value",
                    integerRange(minMaxInt(TurretDefinition::rangeUpgrade)));
            case "scatter_shot" -> stat(lines,
                    "tooltip.openmodularturrets.upgrade.scatter_shot.value", 1);
            default -> { }
        }
        flavour(lines, "tooltip.openmodularturrets.upgrade." + name + ".flavour");
    }

    private static double[] minMaxFraction(
            ToDoubleFunction<TurretDefinition> extractor) {
        double min = Double.MAX_VALUE;
        // Double.MIN_VALUE is the smallest positive value, not negative
        // infinity; seed with NEGATIVE_INFINITY so an all-zero field group
        // still reports a correct maximum.
        double max = Double.NEGATIVE_INFINITY;
        for (TurretDefinition definition : TurretDefinition.values()) {
            double value = extractor.applyAsDouble(definition);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new double[] {min, max};
    }

    private static int[] minMaxInt(
            ToIntFunction<TurretDefinition> extractor) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (TurretDefinition definition : TurretDefinition.values()) {
            int value = extractor.applyAsInt(definition);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new int[] {min, max};
    }

    private static String percentRange(double[] range) {
        String min = String.format(Locale.ROOT, "%.0f", range[0] * 100.0D);
        String max = String.format(Locale.ROOT, "%.0f", range[1] * 100.0D);
        return min.equals(max) ? min : min + "~" + max;
    }

    private static String integerRange(int[] range) {
        return range[0] == range[1] ? Integer.toString(range[0])
                : range[0] + "~" + range[1];
    }

    private static String spreadReductionPercent() {
        double[] upgrade = minMaxFraction(TurretDefinition::accuracyUpgrade);
        double divisor = Math.pow(1.0D + upgrade[0], 1.5D);
        return String.format(Locale.ROOT, "%.0f", (1.0D - 1.0D / divisor) * 100.0D);
    }

    private static String id(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key.getNamespace().equals(OpenModularTurrets.MOD_ID) ? key.getPath() : "";
    }

    private static void title(List<Component> lines, String key) {
        title(lines, key, ChatFormatting.BLUE);
    }

    private static void title(List<Component> lines, String key,
            ChatFormatting color) {
        lines.add(Component.empty());
        lines.add(text(key).withStyle(color));
        lines.add(Component.empty());
    }

    private static void flavour(List<Component> lines, String key) {
        lines.add(Component.empty());
        lines.add(text(key).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void stat(List<Component> lines, String key, Object... args) {
        lines.add(text(key, args).withStyle(ChatFormatting.GRAY));
    }

    private static void kv(List<Component> lines, String labelKey, Object value) {
        Component display = value instanceof Component component
                ? component.copy().withStyle(ChatFormatting.WHITE)
                : Component.literal(String.valueOf(value)).withStyle(ChatFormatting.WHITE);
        lines.add(text(labelKey).withStyle(ChatFormatting.GRAY).append(": ").append(display));
    }

    private static void kvGray(List<Component> lines, String labelKey, Object value) {
        Component display = value instanceof Component component
                ? component.copy().withStyle(ChatFormatting.WHITE)
                : Component.literal(String.valueOf(value)).withStyle(ChatFormatting.WHITE);
        lines.add(text(labelKey).withStyle(ChatFormatting.GRAY)
                .append(": ")
                .append(display));
    }

    private static String accuracyKey(TurretDefinition definition) {
        return switch (definition) {
            case DISPOSABLE -> "tooltip.openmodularturrets.accuracy.low";
            case GRENADE, INCENDIARY, MACHINE_GUN, POTATO, PLASMA ->
                    "tooltip.openmodularturrets.accuracy.medium";
            case LASER, RELATIVISTIC, TELEPORTER ->
                    "tooltip.openmodularturrets.accuracy.high";
            case RAIL_GUN, ROCKET -> "tooltip.openmodularturrets.accuracy.exact";
        };
    }

    private static String ammoTypeKey(TurretDefinition definition) {
        return switch (definition) {
            case DISPOSABLE -> "tooltip.openmodularturrets.ammo_type.0";
            case MACHINE_GUN -> "tooltip.openmodularturrets.ammo_type.1";
            case GRENADE -> "tooltip.openmodularturrets.ammo_type.2";
            case ROCKET -> "tooltip.openmodularturrets.ammo_type.3";
            case LASER, RELATIVISTIC, TELEPORTER -> "tooltip.openmodularturrets.ammo_type.4";
            case RAIL_GUN -> "tooltip.openmodularturrets.ammo_type.5";
            case POTATO -> "tooltip.openmodularturrets.ammo_type.6";
            case INCENDIARY -> "tooltip.openmodularturrets.ammo_type.7";
            case PLASMA -> "tooltip.openmodularturrets.ammo_type.air";
        };
    }

    private static String baseTierKey(int tier) {
        return "tooltip.openmodularturrets.base_tier." + Math.clamp(tier, 1, 5);
    }

    private static int aoeFor(TurretDefinition definition) {
        return switch (definition) {
            case GRENADE -> 3;
            case ROCKET -> 5;
            case PLASMA -> 3;
            default -> 0;
        };
    }

    private static String turretFlavourKey(TurretDefinition definition) {
        return switch (definition) {
            case DISPOSABLE -> "tooltip.openmodularturrets.flavour.turret.0";
            case MACHINE_GUN -> "tooltip.openmodularturrets.flavour.turret.1";
            case GRENADE -> "tooltip.openmodularturrets.flavour.turret.2a";
            case ROCKET -> "tooltip.openmodularturrets.flavour.turret.3";
            case LASER -> "tooltip.openmodularturrets.flavour.turret.4";
            case RAIL_GUN -> "tooltip.openmodularturrets.flavour.turret.5";
            case POTATO -> "tooltip.openmodularturrets.flavour.turret.6";
            case INCENDIARY -> "tooltip.openmodularturrets.flavour.turret.7";
            case RELATIVISTIC -> "tooltip.openmodularturrets.flavour.turret.8";
            case TELEPORTER -> "tooltip.openmodularturrets.flavour.turret.9a";
            case PLASMA -> "tooltip.openmodularturrets.flavour.turret.plasma";
        };
    }

    private static MutableComponent text(String key, Object... args) {
        return Component.translatable(key, args);
    }
}
