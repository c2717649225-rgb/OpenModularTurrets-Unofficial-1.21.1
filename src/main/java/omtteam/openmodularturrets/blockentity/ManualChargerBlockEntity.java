package omtteam.openmodularturrets.blockentity;

import javax.annotation.Nullable;

import omtteam.openmodularturrets.registration.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class ManualChargerBlockEntity extends BlockEntity {
    public static final int TURN_TICKS = 12;
    private long turnStartedAt = Long.MIN_VALUE;

    public ManualChargerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MANUAL_CHARGER.value(), pos, state);
    }

    public boolean startTurn() {
        if (level == null || isTurning(level.getGameTime())) {
            return false;
        }
        turnStartedAt = level.getGameTime();
        setChanged();
        // The block state does not change here, so vanilla sendBlockUpdated
        // would never push the BE update packet - the client animation would
        // never start.  Broadcast the update explicitly.
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getServer().getPlayerList().broadcastAll(
                    ClientboundBlockEntityDataPacket.create(this),
                    serverLevel.dimension());
        }
        return true;
    }

    public boolean isTurning(long gameTime) {
        return turnStartedAt != Long.MIN_VALUE
                && gameTime >= turnStartedAt
                && gameTime - turnStartedAt < TURN_TICKS;
    }

    public float animationRadians(float partialTick) {
        if (level == null || !isTurning(level.getGameTime())) {
            return 0.0F;
        }
        return ((level.getGameTime() - turnStartedAt) + partialTick) * 30.0F / 55.0F;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong("turn_started_at", turnStartedAt);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("turn_started_at")) {
            turnStartedAt = tag.getLong("turn_started_at");
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("turn_started_at", turnStartedAt);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains("turn_started_at")) {
            turnStartedAt = tag.getLong("turn_started_at");
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
