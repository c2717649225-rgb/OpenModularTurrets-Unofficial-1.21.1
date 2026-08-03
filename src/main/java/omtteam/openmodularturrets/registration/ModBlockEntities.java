package omtteam.openmodularturrets.registration;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.blockentity.InventoryExpanderBlockEntity;
import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.blockentity.TurretHeadBlockEntity;
import omtteam.openmodularturrets.blockentity.ManualChargerBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, OpenModularTurrets.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurretBaseBlockEntity>>
            TURRET_BASE = BLOCK_ENTITIES.register("turret_base",
                    () -> BlockEntityType.Builder.of(TurretBaseBlockEntity::new,
                            ModBlocks.TURRET_BASE_TIER_ONE.value(),
                            ModBlocks.TURRET_BASE_TIER_TWO.value(),
                            ModBlocks.TURRET_BASE_TIER_THREE.value(),
                            ModBlocks.TURRET_BASE_TIER_FOUR.value(),
                            ModBlocks.TURRET_BASE_TIER_FIVE.value()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TurretHeadBlockEntity>>
            TURRET_HEAD = BLOCK_ENTITIES.register("turret_head",
                    () -> BlockEntityType.Builder.of(TurretHeadBlockEntity::new,
                            ModBlocks.DISPOSABLE_ITEM_TURRET.value(),
                            ModBlocks.POTATO_CANNON_TURRET.value(),
                            ModBlocks.MACHINE_GUN_TURRET.value(),
                            ModBlocks.INCENDIARY_TURRET.value(),
                            ModBlocks.GRENADE_TURRET.value(),
                            ModBlocks.RELATIVISTIC_TURRET.value(),
                            ModBlocks.ROCKET_TURRET.value(),
                            ModBlocks.TELEPORTER_TURRET.value(),
                            ModBlocks.LASER_TURRET.value(),
                            ModBlocks.RAIL_GUN_TURRET.value(),
                            ModBlocks.PLASMA_TURRET.value()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InventoryExpanderBlockEntity>>
            INVENTORY_EXPANDER = BLOCK_ENTITIES.register("inventory_expander",
                    () -> BlockEntityType.Builder.of(InventoryExpanderBlockEntity::new,
                            ModBlocks.EXPANDER_INV_TIER_ONE.value(),
                            ModBlocks.EXPANDER_INV_TIER_TWO.value(),
                            ModBlocks.EXPANDER_INV_TIER_THREE.value(),
                            ModBlocks.EXPANDER_INV_TIER_FOUR.value(),
                            ModBlocks.EXPANDER_INV_TIER_FIVE.value()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ManualChargerBlockEntity>>
            MANUAL_CHARGER = BLOCK_ENTITIES.register("manual_charger",
                    () -> BlockEntityType.Builder.of(ManualChargerBlockEntity::new,
                            ModBlocks.LEVER_BLOCK.value()).build(null));

    private ModBlockEntities() {
    }
}
