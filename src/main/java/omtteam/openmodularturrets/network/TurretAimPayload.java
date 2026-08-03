package omtteam.openmodularturrets.network;

import omtteam.openmodularturrets.OpenModularTurrets;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TurretAimPayload(BlockPos pos, float yaw, float pitch, int targetEntityId)
        implements CustomPacketPayload {
    public static final Type<TurretAimPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, "turret_aim"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TurretAimPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, TurretAimPayload::pos,
                    ByteBufCodecs.FLOAT, TurretAimPayload::yaw,
                    ByteBufCodecs.FLOAT, TurretAimPayload::pitch,
                    ByteBufCodecs.VAR_INT, TurretAimPayload::targetEntityId,
                    TurretAimPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
