package omtteam.openmodularturrets.registration;

import java.util.List;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.item.MemoryCardItem;
import omtteam.openmodularturrets.item.OmtTooltipBlockItem;
import omtteam.openmodularturrets.item.OmtTooltipItem;

import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(OpenModularTurrets.MOD_ID);

    public static final DeferredItem<OmtTooltipBlockItem> TURRET_BASE_TIER_ONE = tooltipBlockItem("turret_base_tier_one", ModBlocks.TURRET_BASE_TIER_ONE);
    public static final DeferredItem<OmtTooltipBlockItem> TURRET_BASE_TIER_TWO = tooltipBlockItem("turret_base_tier_two", ModBlocks.TURRET_BASE_TIER_TWO);
    public static final DeferredItem<OmtTooltipBlockItem> TURRET_BASE_TIER_THREE = tooltipBlockItem("turret_base_tier_three", ModBlocks.TURRET_BASE_TIER_THREE);
    public static final DeferredItem<OmtTooltipBlockItem> TURRET_BASE_TIER_FOUR = tooltipBlockItem("turret_base_tier_four", ModBlocks.TURRET_BASE_TIER_FOUR);
    public static final DeferredItem<OmtTooltipBlockItem> TURRET_BASE_TIER_FIVE = tooltipBlockItem("turret_base_tier_five", ModBlocks.TURRET_BASE_TIER_FIVE);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_INV_TIER_ONE = tooltipBlockItem("expander_inv_tier_one", ModBlocks.EXPANDER_INV_TIER_ONE);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_INV_TIER_TWO = tooltipBlockItem("expander_inv_tier_two", ModBlocks.EXPANDER_INV_TIER_TWO);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_INV_TIER_THREE = tooltipBlockItem("expander_inv_tier_three", ModBlocks.EXPANDER_INV_TIER_THREE);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_INV_TIER_FOUR = tooltipBlockItem("expander_inv_tier_four", ModBlocks.EXPANDER_INV_TIER_FOUR);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_INV_TIER_FIVE = tooltipBlockItem("expander_inv_tier_five", ModBlocks.EXPANDER_INV_TIER_FIVE);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_POWER_TIER_ONE = tooltipBlockItem("expander_power_tier_one", ModBlocks.EXPANDER_POWER_TIER_ONE);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_POWER_TIER_TWO = tooltipBlockItem("expander_power_tier_two", ModBlocks.EXPANDER_POWER_TIER_TWO);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_POWER_TIER_THREE = tooltipBlockItem("expander_power_tier_three", ModBlocks.EXPANDER_POWER_TIER_THREE);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_POWER_TIER_FOUR = tooltipBlockItem("expander_power_tier_four", ModBlocks.EXPANDER_POWER_TIER_FOUR);
    public static final DeferredItem<OmtTooltipBlockItem> EXPANDER_POWER_TIER_FIVE = tooltipBlockItem("expander_power_tier_five", ModBlocks.EXPANDER_POWER_TIER_FIVE);
    public static final DeferredItem<OmtTooltipBlockItem> BASE_ADDON_LOOT_DELETER = tooltipBlockItem("base_addon_loot_deleter", ModBlocks.BASE_ADDON_LOOT_DELETER);
    public static final DeferredItem<OmtTooltipBlockItem> LEVER_BLOCK = tooltipBlockItem("lever_block", ModBlocks.LEVER_BLOCK);
    public static final DeferredItem<OmtTooltipBlockItem> DISPOSABLE_ITEM_TURRET = tooltipBlockItem("disposable_item_turret", ModBlocks.DISPOSABLE_ITEM_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> POTATO_CANNON_TURRET = tooltipBlockItem("potato_cannon_turret", ModBlocks.POTATO_CANNON_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> MACHINE_GUN_TURRET = tooltipBlockItem("machine_gun_turret", ModBlocks.MACHINE_GUN_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> INCENDIARY_TURRET = tooltipBlockItem("incendiary_turret", ModBlocks.INCENDIARY_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> GRENADE_TURRET = tooltipBlockItem("grenade_turret", ModBlocks.GRENADE_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> RELATIVISTIC_TURRET = tooltipBlockItem("relativistic_turret", ModBlocks.RELATIVISTIC_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> ROCKET_TURRET = tooltipBlockItem("rocket_turret", ModBlocks.ROCKET_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> TELEPORTER_TURRET = tooltipBlockItem("teleporter_turret", ModBlocks.TELEPORTER_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> LASER_TURRET = tooltipBlockItem("laser_turret", ModBlocks.LASER_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> RAIL_GUN_TURRET = tooltipBlockItem("rail_gun_turret", ModBlocks.RAIL_GUN_TURRET);
    public static final DeferredItem<OmtTooltipBlockItem> PLASMA_TURRET = tooltipBlockItem("plasma_turret", ModBlocks.PLASMA_TURRET);

    public static final DeferredItem<OmtTooltipItem> SENSOR_TIER_ONE = tooltipItem("sensor_tier_one");
    public static final DeferredItem<OmtTooltipItem> SENSOR_TIER_TWO = tooltipItem("sensor_tier_two");
    public static final DeferredItem<OmtTooltipItem> SENSOR_TIER_THREE = tooltipItem("sensor_tier_three");
    public static final DeferredItem<OmtTooltipItem> SENSOR_TIER_FOUR = tooltipItem("sensor_tier_four");
    public static final DeferredItem<OmtTooltipItem> SENSOR_TIER_FIVE = tooltipItem("sensor_tier_five");
    public static final DeferredItem<OmtTooltipItem> CHAMBER_TIER_ONE = tooltipItem("chamber_tier_one");
    public static final DeferredItem<OmtTooltipItem> CHAMBER_TIER_TWO = tooltipItem("chamber_tier_two");
    public static final DeferredItem<OmtTooltipItem> CHAMBER_TIER_THREE = tooltipItem("chamber_tier_three");
    public static final DeferredItem<OmtTooltipItem> CHAMBER_TIER_FOUR = tooltipItem("chamber_tier_four");
    public static final DeferredItem<OmtTooltipItem> CHAMBER_TIER_FIVE = tooltipItem("chamber_tier_five");
    public static final DeferredItem<OmtTooltipItem> BARREL_TIER_ONE = tooltipItem("barrel_tier_one");
    public static final DeferredItem<OmtTooltipItem> BARREL_TIER_TWO = tooltipItem("barrel_tier_two");
    public static final DeferredItem<OmtTooltipItem> BARREL_TIER_THREE = tooltipItem("barrel_tier_three");
    public static final DeferredItem<OmtTooltipItem> BARREL_TIER_FOUR = tooltipItem("barrel_tier_four");
    public static final DeferredItem<OmtTooltipItem> BARREL_TIER_FIVE = tooltipItem("barrel_tier_five");
    public static final DeferredItem<OmtTooltipItem> IO_BUS = tooltipItem("io_bus");

    public static final DeferredItem<OmtTooltipItem> ADDON_CONCEALER = tooltipItem("addon_concealer");
    public static final DeferredItem<OmtTooltipItem> ADDON_DAMAGE_AMP = tooltipItem("addon_damage_amp");
    public static final DeferredItem<OmtTooltipItem> ADDON_POTENTIA = tooltipItem("addon_potentia");
    public static final DeferredItem<OmtTooltipItem> ADDON_RECYCLER = tooltipItem("addon_recycler");
    public static final DeferredItem<OmtTooltipItem> ADDON_REDSTONE_REACTOR = tooltipItem("addon_redstone_reactor");
    public static final DeferredItem<OmtTooltipItem> ADDON_SERIAL_PORT = tooltipItem("addon_serial_port");
    public static final DeferredItem<OmtTooltipItem> ADDON_SOLAR_PANEL = tooltipItem("addon_solar_panel");
    public static final DeferredItem<OmtTooltipItem> ADDON_FAKE_DROPS = tooltipItem("addon_fake_drops");

    public static final DeferredItem<OmtTooltipItem> UPGRADE_ACCURACY = tooltipItem("upgrade_accuracy");
    public static final DeferredItem<OmtTooltipItem> UPGRADE_EFFICIENCY = tooltipItem("upgrade_efficiency");
    public static final DeferredItem<OmtTooltipItem> UPGRADE_FIRE_RATE = tooltipItem("upgrade_fire_rate");
    public static final DeferredItem<OmtTooltipItem> UPGRADE_RANGE = tooltipItem("upgrade_range");
    public static final DeferredItem<OmtTooltipItem> UPGRADE_SCATTER_SHOT = tooltipItem("upgrade_scatter_shot");

    public static final DeferredItem<OmtTooltipItem> AMMO_BLAZING_CLAY = tooltipItem("ammo_blazing_clay");
    public static final DeferredItem<OmtTooltipItem> AMMO_BULLET = tooltipItem("ammo_bullet");
    public static final DeferredItem<OmtTooltipItem> AMMO_FERRO_SLUG = tooltipItem("ammo_ferro_slug");
    public static final DeferredItem<OmtTooltipItem> AMMO_GRENADE = tooltipItem("ammo_grenade");
    public static final DeferredItem<OmtTooltipItem> AMMO_ROCKET = tooltipItem("ammo_rocket");
    public static final DeferredItem<OmtTooltipItem> AMMO_FAKE_DISPOSABLE = tooltipItem("ammo_fake_disposable");
    public static final DeferredItem<OmtTooltipItem> THROWABLE_BULLET = tooltipItem("throwable_bullet");
    public static final DeferredItem<OmtTooltipItem> THROWABLE_GRENADE = tooltipItem("throwable_grenade");
    public static final DeferredItem<MemoryCardItem> MEMORY_CARD =
            ITEMS.registerItem("memory_card", MemoryCardItem::new);

    public static final List<DeferredItem<? extends Item>> REGULAR_ITEMS = List.of(
            SENSOR_TIER_ONE, SENSOR_TIER_TWO, SENSOR_TIER_THREE, SENSOR_TIER_FOUR, SENSOR_TIER_FIVE,
            CHAMBER_TIER_ONE, CHAMBER_TIER_TWO, CHAMBER_TIER_THREE, CHAMBER_TIER_FOUR, CHAMBER_TIER_FIVE,
            BARREL_TIER_ONE, BARREL_TIER_TWO, BARREL_TIER_THREE, BARREL_TIER_FOUR, BARREL_TIER_FIVE,
            IO_BUS, ADDON_CONCEALER, ADDON_DAMAGE_AMP, ADDON_POTENTIA, ADDON_RECYCLER,
            ADDON_REDSTONE_REACTOR, ADDON_SERIAL_PORT, ADDON_SOLAR_PANEL, ADDON_FAKE_DROPS,
            UPGRADE_ACCURACY, UPGRADE_EFFICIENCY, UPGRADE_FIRE_RATE, UPGRADE_RANGE,
            UPGRADE_SCATTER_SHOT, AMMO_BLAZING_CLAY, AMMO_BULLET, AMMO_FERRO_SLUG,
            AMMO_GRENADE, AMMO_ROCKET, AMMO_FAKE_DISPOSABLE, THROWABLE_BULLET,
            THROWABLE_GRENADE, MEMORY_CARD);

    private ModItems() {
    }

    private static DeferredItem<OmtTooltipItem> tooltipItem(String id) {
        return ITEMS.registerItem(id, OmtTooltipItem::new);
    }

    private static DeferredItem<OmtTooltipBlockItem> tooltipBlockItem(String id,
            Supplier<? extends Block> block) {
        return ITEMS.registerItem(id, properties -> new OmtTooltipBlockItem(block.get(), properties));
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

}
