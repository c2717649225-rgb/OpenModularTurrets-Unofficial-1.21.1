package omtteam.openmodularturrets.registration;

import java.util.List;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.block.InventoryExpanderBlock;
import omtteam.openmodularturrets.block.BaseAttachmentBlock;
import omtteam.openmodularturrets.block.TurretBaseBlock;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.block.PowerExpanderBlock;
import omtteam.openmodularturrets.block.ManualChargerBlock;
import omtteam.openmodularturrets.data.BaseTier;
import omtteam.openmodularturrets.data.TurretDefinition;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(OpenModularTurrets.MOD_ID);

    public static final DeferredBlock<TurretBaseBlock> TURRET_BASE_TIER_ONE =
            BLOCKS.registerBlock("turret_base_tier_one", p -> new TurretBaseBlock(BaseTier.ONE, p), properties());
    public static final DeferredBlock<TurretBaseBlock> TURRET_BASE_TIER_TWO =
            BLOCKS.registerBlock("turret_base_tier_two", p -> new TurretBaseBlock(BaseTier.TWO, p), properties());
    public static final DeferredBlock<TurretBaseBlock> TURRET_BASE_TIER_THREE =
            BLOCKS.registerBlock("turret_base_tier_three", p -> new TurretBaseBlock(BaseTier.THREE, p), properties());
    public static final DeferredBlock<TurretBaseBlock> TURRET_BASE_TIER_FOUR =
            BLOCKS.registerBlock("turret_base_tier_four", p -> new TurretBaseBlock(BaseTier.FOUR, p), properties());
    public static final DeferredBlock<TurretBaseBlock> TURRET_BASE_TIER_FIVE =
            BLOCKS.registerBlock("turret_base_tier_five", p -> new TurretBaseBlock(BaseTier.FIVE, p), properties());

    public static final DeferredBlock<InventoryExpanderBlock> EXPANDER_INV_TIER_ONE =
            BLOCKS.registerBlock("expander_inv_tier_one", p -> new InventoryExpanderBlock(1, p), attachmentProperties());
    public static final DeferredBlock<InventoryExpanderBlock> EXPANDER_INV_TIER_TWO =
            BLOCKS.registerBlock("expander_inv_tier_two", p -> new InventoryExpanderBlock(2, p), attachmentProperties());
    public static final DeferredBlock<InventoryExpanderBlock> EXPANDER_INV_TIER_THREE =
            BLOCKS.registerBlock("expander_inv_tier_three", p -> new InventoryExpanderBlock(3, p), attachmentProperties());
    public static final DeferredBlock<InventoryExpanderBlock> EXPANDER_INV_TIER_FOUR =
            BLOCKS.registerBlock("expander_inv_tier_four", p -> new InventoryExpanderBlock(4, p), attachmentProperties());
    public static final DeferredBlock<InventoryExpanderBlock> EXPANDER_INV_TIER_FIVE =
            BLOCKS.registerBlock("expander_inv_tier_five", p -> new InventoryExpanderBlock(5, p), attachmentProperties());

    public static final DeferredBlock<PowerExpanderBlock> EXPANDER_POWER_TIER_ONE =
            BLOCKS.registerBlock("expander_power_tier_one", p -> new PowerExpanderBlock(1, p), attachmentProperties());
    public static final DeferredBlock<PowerExpanderBlock> EXPANDER_POWER_TIER_TWO =
            BLOCKS.registerBlock("expander_power_tier_two", p -> new PowerExpanderBlock(2, p), attachmentProperties());
    public static final DeferredBlock<PowerExpanderBlock> EXPANDER_POWER_TIER_THREE =
            BLOCKS.registerBlock("expander_power_tier_three", p -> new PowerExpanderBlock(3, p), attachmentProperties());
    public static final DeferredBlock<PowerExpanderBlock> EXPANDER_POWER_TIER_FOUR =
            BLOCKS.registerBlock("expander_power_tier_four", p -> new PowerExpanderBlock(4, p), attachmentProperties());
    public static final DeferredBlock<PowerExpanderBlock> EXPANDER_POWER_TIER_FIVE =
            BLOCKS.registerBlock("expander_power_tier_five", p -> new PowerExpanderBlock(5, p), attachmentProperties());
    public static final DeferredBlock<BaseAttachmentBlock> BASE_ADDON_LOOT_DELETER =
            BLOCKS.registerBlock("base_addon_loot_deleter", BaseAttachmentBlock::new,
                    attachmentProperties());
    public static final DeferredBlock<ManualChargerBlock> LEVER_BLOCK =
            BLOCKS.registerBlock("lever_block", ManualChargerBlock::new,
                    properties().strength(2.0F, 15.0F).noOcclusion());

    public static final DeferredBlock<TurretHeadBlock> DISPOSABLE_ITEM_TURRET =
            BLOCKS.registerBlock("disposable_item_turret",
                    p -> new TurretHeadBlock(TurretDefinition.DISPOSABLE, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> POTATO_CANNON_TURRET =
            BLOCKS.registerBlock("potato_cannon_turret",
                    p -> new TurretHeadBlock(TurretDefinition.POTATO, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> MACHINE_GUN_TURRET =
            BLOCKS.registerBlock("machine_gun_turret",
                    p -> new TurretHeadBlock(TurretDefinition.MACHINE_GUN, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> INCENDIARY_TURRET =
            BLOCKS.registerBlock("incendiary_turret",
                    p -> new TurretHeadBlock(TurretDefinition.INCENDIARY, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> GRENADE_TURRET =
            BLOCKS.registerBlock("grenade_turret",
                    p -> new TurretHeadBlock(TurretDefinition.GRENADE, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> RELATIVISTIC_TURRET =
            BLOCKS.registerBlock("relativistic_turret",
                    p -> new TurretHeadBlock(TurretDefinition.RELATIVISTIC, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> ROCKET_TURRET =
            BLOCKS.registerBlock("rocket_turret",
                    p -> new TurretHeadBlock(TurretDefinition.ROCKET, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> TELEPORTER_TURRET =
            BLOCKS.registerBlock("teleporter_turret",
                    p -> new TurretHeadBlock(TurretDefinition.TELEPORTER, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> LASER_TURRET =
            BLOCKS.registerBlock("laser_turret",
                    p -> new TurretHeadBlock(TurretDefinition.LASER, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> RAIL_GUN_TURRET =
            BLOCKS.registerBlock("rail_gun_turret",
                    p -> new TurretHeadBlock(TurretDefinition.RAIL_GUN, p), turretProperties());
    public static final DeferredBlock<TurretHeadBlock> PLASMA_TURRET =
            BLOCKS.registerBlock("plasma_turret",
                    p -> new TurretHeadBlock(TurretDefinition.PLASMA, p), turretProperties());

    public static final List<DeferredBlock<? extends Block>> ALL = List.of(
            TURRET_BASE_TIER_ONE, TURRET_BASE_TIER_TWO, TURRET_BASE_TIER_THREE,
            TURRET_BASE_TIER_FOUR, TURRET_BASE_TIER_FIVE,
            EXPANDER_INV_TIER_ONE, EXPANDER_INV_TIER_TWO, EXPANDER_INV_TIER_THREE,
            EXPANDER_INV_TIER_FOUR, EXPANDER_INV_TIER_FIVE,
            EXPANDER_POWER_TIER_ONE, EXPANDER_POWER_TIER_TWO, EXPANDER_POWER_TIER_THREE,
            EXPANDER_POWER_TIER_FOUR, EXPANDER_POWER_TIER_FIVE,
            BASE_ADDON_LOOT_DELETER, LEVER_BLOCK,
            DISPOSABLE_ITEM_TURRET, POTATO_CANNON_TURRET, MACHINE_GUN_TURRET,
            INCENDIARY_TURRET, GRENADE_TURRET, RELATIVISTIC_TURRET, ROCKET_TURRET,
            TELEPORTER_TURRET, LASER_TURRET, RAIL_GUN_TURRET, PLASMA_TURRET);

    private ModBlocks() {
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.5F, 6.0F)
                .requiresCorrectToolForDrops();
    }

    private static BlockBehaviour.Properties attachmentProperties() {
        return properties().noOcclusion();
    }

    private static BlockBehaviour.Properties turretProperties() {
        return properties().noOcclusion();
    }
}
