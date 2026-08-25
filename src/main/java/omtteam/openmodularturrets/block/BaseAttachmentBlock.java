package omtteam.openmodularturrets.block;

import omtteam.openmodularturrets.config.ModServerConfig;

import java.util.function.Predicate;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Shared six-way mounting behavior for blocks attached directly to a turret base.
 *
 * <p>The facing points from the attachment toward its base. Survival deliberately
 * checks every neighboring side so block states saved before the facing property
 * existed remain valid and can repair their visual orientation on the next
 * neighbor update.</p>
 */
public class BaseAttachmentBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public BaseAttachmentBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN));
    }

    protected boolean supportsBase(TurretBaseBlockEntity base) {
        return true;
    }

    protected boolean ownerSneakRemovalEnabled() {
        return false;
    }

    @Nullable
    protected Direction findBaseFacing(LevelReader level, BlockPos pos,
            @Nullable Direction preferred) {
        return findBaseFacing(level, pos, preferred, this::supportsBase);
    }

    @Nullable
    public static Direction findBaseFacing(LevelReader level, BlockPos pos,
            @Nullable Direction preferred, Predicate<TurretBaseBlockEntity> predicate) {
        if (preferred != null && isSupportedAt(level, pos, preferred, predicate)) {
            return preferred;
        }
        for (Direction direction : Direction.values()) {
            if (direction != preferred && isSupportedAt(level, pos, direction, predicate)) {
                return direction;
            }
        }
        return null;
    }

    private static boolean isSupportedAt(LevelReader level, BlockPos pos, Direction direction,
            Predicate<TurretBaseBlockEntity> predicate) {
        return level.getBlockEntity(pos.relative(direction))
                instanceof TurretBaseBlockEntity base && predicate.test(base);
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
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return findBaseFacing(level, pos, state.getValue(FACING)) != null;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction,
            BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Direction facing = findBaseFacing(level, pos, state.getValue(FACING));
        if (facing == null) {
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.destroyBlock(pos, true);
            }
            return Blocks.AIR.defaultBlockState();
        }
        return state.setValue(FACING, facing);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Exact 1.12 expander plate bounds: a centered 12x12 face, six pixels deep. */
    public static VoxelShape expanderShape(Direction facing) {
        return switch (facing) {
            case NORTH -> box(2, 2, 0, 14, 14, 6);
            case SOUTH -> box(2, 2, 10, 14, 14, 16);
            case WEST -> box(0, 2, 2, 6, 14, 14);
            case EAST -> box(10, 2, 2, 16, 14, 14);
            case DOWN -> box(2, 0, 2, 14, 6, 14);
            case UP -> box(2, 10, 2, 14, 16, 14);
        };
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        // The legacy AbstractBaseAttachment used the same centered 12x12x6
        // plate bounds as the inventory and power expanders.  Keeping this
        // default here prevents the base-addon loot deleter from exposing a
        // full-width selection/collision box that does not match its model.
        return expanderShape(state.getValue(FACING));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (!ownerSneakRemovalEnabled()) {
            return InteractionResult.PASS;
        }
        return handleOwnerSneakRemoval(level, pos, player, state.getValue(FACING));
    }

    static InteractionResult handleOwnerSneakRemoval(Level level, BlockPos pos,
            Player player, Direction facing) {
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide) {
            BlockPos basePos = pos.relative(facing);
            if (!(level.getBlockEntity(basePos) instanceof TurretBaseBlockEntity base)) {
                level.destroyBlock(pos, true, player);
            } else if (base.isOwner(player)) {
                level.destroyBlock(pos, true, player);
            } else {
                player.displayClientMessage(Component.translatable(
                        "message.openmodularturrets.access_denied"), true);
            }
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
        // Deliberately a fixed legacy value, unlike the tier-configurable base:
        // 1.12 attachments always used plain block blast resistance here and
        // only the breakable toggle is exposed through config.
        return 3.0F;
    }
}
