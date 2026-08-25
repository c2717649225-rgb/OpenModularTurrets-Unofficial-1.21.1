package omtteam.openmodularturrets.block;

import com.mojang.serialization.MapCodec;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.blockentity.InventoryExpanderBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.config.ModServerConfig;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
public final class InventoryExpanderBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BaseAttachmentBlock.FACING;
    private final int tier;

    public InventoryExpanderBlock(int tier, Properties properties) {
        super(properties);
        this.tier = tier;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN));
    }

    public int tier() {
        return tier;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InventoryExpanderBlockEntity(pos, state);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level,
            BlockPos pos) {
        return findBaseFacing(level, pos, state.getValue(FACING)) != null;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clickedBase = context.getClickedFace().getOpposite();
        Direction facing = findBaseFacing(context.getLevel(), context.getClickedPos(), clickedBase);
        return facing == null ? defaultBlockState()
                : defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction,
            BlockState neighborState, LevelAccessor level,
            BlockPos pos, BlockPos neighborPos) {
        Direction facing = findBaseFacing(level, pos, state.getValue(FACING));
        if (facing == null) {
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.destroyBlock(pos, true);
            }
            return Blocks.AIR.defaultBlockState();
        }
        return state.setValue(FACING, facing);
    }

    @Nullable
    private Direction findBaseFacing(LevelReader level, BlockPos pos,
            @Nullable Direction preferred) {
        return BaseAttachmentBlock.findBaseFacing(level, pos, preferred, base -> true);
    }

    @Override
    protected void createBlockStateDefinition(
            StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return BaseAttachmentBlock.expanderShape(state.getValue(FACING));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        InteractionResult removal = BaseAttachmentBlock.handleOwnerSneakRemoval(
                level, pos, player, state.getValue(FACING));
        if (removal != InteractionResult.PASS) {
            return removal;
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof MenuProvider provider) {
            serverPlayer.openMenu(provider, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level,
            BlockPos pos) {
        if (!ModServerConfig.attachmentsBreakable()) {
            return 0.0F;
        }
        return super.getDestroyProgress(state, player, level, pos)
                * defaultDestroyTime() / 3.0F;
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos,
            Explosion explosion) {
        return 3.0F;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos,
            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof InventoryExpanderBlockEntity expander) {
            for (int slot = 0; slot < expander.inventory().getSlots(); slot++) {
                popResource(level, pos, expander.inventory().getStackInSlot(slot));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
