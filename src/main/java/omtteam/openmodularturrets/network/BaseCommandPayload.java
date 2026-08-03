package omtteam.openmodularturrets.network;

import omtteam.openmodularturrets.OpenModularTurrets;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BaseCommandPayload(int containerId, BlockPos pos, int commandId, int value)
        implements CustomPacketPayload {
    public static final Type<BaseCommandPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, "base_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BaseCommandPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, BaseCommandPayload::containerId,
                    BlockPos.STREAM_CODEC, BaseCommandPayload::pos,
                    ByteBufCodecs.VAR_INT, BaseCommandPayload::commandId,
                    ByteBufCodecs.VAR_INT, BaseCommandPayload::value,
                    BaseCommandPayload::new);

    public BaseCommandPayload {
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
