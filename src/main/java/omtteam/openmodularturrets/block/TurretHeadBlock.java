package omtteam.openmodularturrets.block;

import com.mojang.serialization.MapCodec;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.blockentity.TurretHeadBlockEntity;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.registration.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import org.joml.Vector3f;

public final class TurretHeadBlock extends BaseEntityBlock {
    public static final BooleanProperty CONCEALED = BooleanProperty.create("concealed");
    private static final VoxelShape SHAPE = box(3.2D, 3.2D, 3.2D,
            12.8D, 12.8D, 12.8D);

    private final TurretDefinition definition;

    public TurretHeadBlock(TurretDefinition definition, Properties properties) {
        super(properties);
        this.definition = definition;
        registerDefaultState(stateDefinition.any().setValue(CONCEALED, false));
    }

    public TurretDefinition definition() {
        return definition;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected void createBlockStateDefinition(
            StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(CONCEALED);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos,
            RandomSource random) {
        if (definition == TurretDefinition.RAIL_GUN && state.getValue(CONCEALED)) {
            return;
        }
        DustParticleOptions dust;
        double y;
        if (definition == TurretDefinition.RAIL_GUN) {
            dust = new DustParticleOptions(new Vector3f(0.0F, 0.25F, 1.0F), 1.0F);
            y = pos.getY();
        } else if (definition == TurretDefinition.RELATIVISTIC) {
            dust = new DustParticleOptions(new Vector3f(0.9F, 0.9F, 0.9F), 1.0F);
            y = pos.getY() + 0.5D;
        } else {
            return;
        }
        for (int i = 0; i < TurretVisualRules.IDLE_DUST_PARTICLES; i++) {
            level.addParticle(dust,
                    pos.getX() + 0.5D + random.nextGaussian() * 0.1D,
                    y,
                    pos.getZ() + 0.5D + random.nextGaussian() * 0.1D,
                    0.0D, 0.0D, 0.0D);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(pos.relative(direction))
                    instanceof TurretBaseBlockEntity base) {
                // The 1.12 block placement hook immediately promoted the
                // base's selected range when a newly attached turret had a
                // higher native range than the existing heads.
                base.updateRangeAfterTurretPlacement(pos, definition);
                return;
            }
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level,
            BlockPos pos, CollisionContext context) {
        return state.getValue(CONCEALED) ? Shapes.empty() : SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TurretHeadBlockEntity(pos, state);
    }

    @Override
    protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level,
            BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(pos.relative(direction)) instanceof TurretBaseBlockEntity base
                    && base.canSupportTurret(pos, definition)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction,
            BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!canSurvive(state, level, pos)) {
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.destroyBlock(pos, true);
            }
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModBlockEntities.TURRET_HEAD.value(),
                        TurretHeadBlockEntity::serverTick);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level,
            BlockPos pos) {
        return 0.0F;
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos,
            Explosion explosion) {
        return 6_000_000.0F;
    }
}
