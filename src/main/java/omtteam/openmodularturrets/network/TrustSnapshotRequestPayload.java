package omtteam.openmodularturrets.network;

import omtteam.openmodularturrets.OpenModularTurrets;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrustSnapshotRequestPayload(int containerId, BlockPos pos, int scopeId)
        implements CustomPacketPayload {
    public static final Type<TrustSnapshotRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    OpenModularTurrets.MOD_ID, "trust_snapshot_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrustSnapshotRequestPayload>
            STREAM_CODEC = StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, TrustSnapshotRequestPayload::containerId,
                    BlockPos.STREAM_CODEC, TrustSnapshotRequestPayload::pos,
                    ByteBufCodecs.VAR_INT, TrustSnapshotRequestPayload::scopeId,
                    TrustSnapshotRequestPayload::new);

    public TrustSnapshotRequestPayload {
        if (containerId < 0 || TrustScope.byId(scopeId) == null) {
            throw new IllegalArgumentException("Invalid trust snapshot request");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
