package omtteam.openmodularturrets.block;

import com.mojang.serialization.MapCodec;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.data.BaseTier;
import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.item.MemoryCardItem;
import omtteam.openmodularturrets.registration.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.network.chat.Component;
public final class TurretBaseBlock extends BaseEntityBlock {
    public static final BooleanProperty CAMOUFLAGED =
            BooleanProperty.create("camouflaged");
    public static final IntegerProperty LIGHT_LEVEL =
            IntegerProperty.create("light_level", 0, 15);

    private final BaseTier tier;

    public TurretBaseBlock(BaseTier tier, Properties properties) {
        super(properties.lightLevel(state -> state.getValue(LIGHT_LEVEL)));
        this.tier = tier;
        registerDefaultState(stateDefinition.any()
                .setValue(CAMOUFLAGED, false)
                .setValue(LIGHT_LEVEL, 0));
    }

    public BaseTier tier() {
        return tier;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return state.getValue(CAMOUFLAGED) ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        // Legacy checked every direct neighbor and every block touching those
        // positions, rejecting bases at Manhattan distance one or two.
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    int distance = Math.abs(x) + Math.abs(y) + Math.abs(z);
                    if (distance >= 1 && distance <= 2
                            && level.getBlockState(pos.offset(x, y, z)).getBlock()
                                    instanceof TurretBaseBlock) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    protected void createBlockStateDefinition(
            StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CAMOUFLAGED, LIGHT_LEVEL);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TurretBaseBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModBlockEntities.TURRET_BASE.value(),
                        TurretBaseBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player player
                && level.getBlockEntity(pos) instanceof TurretBaseBlockEntity base) {
            base.claim(player);
            base.refreshRedstoneSignal();
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
            Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide
                && level.getBlockEntity(pos) instanceof TurretBaseBlockEntity base) {
            base.invalidateNeighborCaches();
            base.refreshRedstoneSignal();
        }
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state,
            Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (!player.isShiftKeyDown() && stack.getItem() instanceof BlockItem blockItem) {
            if (!level.isClientSide
                    && level.getBlockEntity(pos) instanceof TurretBaseBlockEntity base) {
                BlockState camouflage = blockItem.getBlock().getStateForPlacement(
                        new BlockPlaceContext(new UseOnContext(player, hand, hit)));
                if (camouflage == null || !base.setCamouflage(player, camouflage)) {
                    player.displayClientMessage(
                            Component.translatable(
                                    "message.openmodularturrets.camouflage_rejected"),
                            true);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        // A memory card handles the base interaction itself (save on sneak,
        // load on plain use).  Returning PASS lets the server fall through to
        // the item's useOn after the block pass, matching the legacy 1.12
        // block-side card handling instead of opening the GUI.
        if (player.getMainHandItem().getItem() instanceof MemoryCardItem
                || player.getOffhandItem().getItem() instanceof MemoryCardItem) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide
                    && level.getBlockEntity(pos) instanceof TurretBaseBlockEntity base) {
                if (!base.clearCamouflage(player)) {
                    player.displayClientMessage(
                            Component.translatable(
                                    "message.openmodularturrets.camouflage_clear_rejected"),
                            true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide
                && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof TurretBaseBlockEntity base
                && base.accessFor(player).allows(AccessLevel.VIEW)) {
            serverPlayer.openMenu(base, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof TurretBaseBlockEntity base) {
            return base.camouflageLightOpacity();
        }
        return super.getLightBlock(state, level, pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof TurretBaseBlockEntity base) {
            for (int slot = 0; slot < base.inventory().getSlots(); slot++) {
                popResource(level, pos, base.inventory().getStackInSlot(slot));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level,
            BlockPos pos) {
        if (!ModServerConfig.baseBreakable()) {
            return 0.0F;
        }
        float hardness = ModServerConfig.base(tier).hardness();
        return super.getDestroyProgress(state, player, level, pos)
                * defaultDestroyTime() / hardness;
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos,
            Explosion explosion) {
        return ModServerConfig.baseBreakable()
                ? ModServerConfig.base(tier).blastResistance() : 6_000_000.0F;
    }
}
