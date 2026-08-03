package omtteam.openmodularturrets.network;

import omtteam.openmodularturrets.OpenModularTurrets;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record BeamEffectPayload(Vec3 start, Vec3 end, int color, float alpha,
        int durationTicks) implements CustomPacketPayload {
    public static final Type<BeamEffectPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, "beam_effect"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BeamEffectPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeDouble(payload.start.x);
                        buffer.writeDouble(payload.start.y);
                        buffer.writeDouble(payload.start.z);
                        buffer.writeDouble(payload.end.x);
                        buffer.writeDouble(payload.end.y);
                        buffer.writeDouble(payload.end.z);
                        buffer.writeInt(payload.color);
                        buffer.writeFloat(payload.alpha);
                        buffer.writeVarInt(payload.durationTicks);
                    },
                    buffer -> new BeamEffectPayload(
                            new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                            new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                            buffer.readInt(),
                            buffer.readFloat(),
                            buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
