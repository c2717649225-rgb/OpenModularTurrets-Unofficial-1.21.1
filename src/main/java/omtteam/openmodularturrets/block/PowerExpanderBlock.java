package omtteam.openmodularturrets.block;

import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.data.BaseTier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PowerExpanderBlock extends BaseAttachmentBlock {
    private final int tier;

    public PowerExpanderBlock(int tier, Properties properties) {
        super(properties);
        this.tier = Math.clamp(tier, 1, 5);
    }

    public int tier() {
        return tier;
    }

    public int extraCapacity() {
        return ModServerConfig.powerExpanderCapacity(BaseTier.values()[tier - 1]);
    }

    @Override
    protected boolean ownerSneakRemovalEnabled() {
        return true;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
            CollisionContext context) {
        return expanderShape(state.getValue(FACING));
    }
}
