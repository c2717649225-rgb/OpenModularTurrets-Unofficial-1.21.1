package omtteam.openmodularturrets.block;

import com.mojang.serialization.MapCodec;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.blockentity.ManualChargerBlockEntity;
import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The tier-one base's legacy hand crank. */
public final class ManualChargerBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = box(1.6D, 1.6D, 1.6D,
            14.4D, 14.4D, 14.4D);

    public ManualChargerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return MapCodec.unit(this);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ManualChargerBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = findBase(context.getLevel(), context.getClickedPos(),
                context.getClickedFace().getOpposite());
        return facing == null ? null : defaultBlockState().setValue(FACING, facing);
    }

    @Nullable
    private static Direction findBase(LevelReader level, BlockPos pos,
            @Nullable Direction preferred) {
        if (preferred != null && preferred.getAxis().isHorizontal()
                && isTierOneBase(level, pos.relative(preferred))) {
            return preferred;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isTierOneBase(level, pos.relative(direction))) {
                return direction;
            }
        }
        return null;
    }

    private static boolean isTierOneBase(LevelReader level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof TurretBaseBlockEntity base
                && base.tier().level() == 1;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return findBase(level, pos, state.getValue(FACING)) != null;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction,
            BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Direction facing = findBase(level, pos, state.getValue(FACING));
        if (facing == null) {
            // The legacy LeverBlock used destroyBlock(pos, true) when its
            // tier-one base disappeared.  Returning AIR directly would make
            // the placed hand crank vanish without dropping its item.
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.destroyBlock(pos, true);
            }
            return Blocks.AIR.defaultBlockState();
        }
        return state.setValue(FACING, facing);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel
                && level.getBlockEntity(pos) instanceof ManualChargerBlockEntity charger
                && level.getBlockEntity(pos.relative(state.getValue(FACING)))
                        instanceof TurretBaseBlockEntity base
                && base.tier().level() == 1
                && charger.startTurn()) {
            base.energy().receiveEnergy(50, false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return SHAPE;
    }
}
