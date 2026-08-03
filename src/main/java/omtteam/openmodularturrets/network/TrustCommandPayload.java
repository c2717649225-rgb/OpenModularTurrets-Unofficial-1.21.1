package omtteam.openmodularturrets.network;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.data.AccessLevel;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrustCommandPayload(
        int containerId,
        BlockPos pos,
        int scopeId,
        int operationId,
        String targetInput,
        int accessId,
        long expectedRevision)
        implements CustomPacketPayload {
    public static final int MAX_TARGET_INPUT_LENGTH = 40;
    public static final Type<TrustCommandPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, "trust_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrustCommandPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeVarInt(payload.containerId);
                        buffer.writeBlockPos(payload.pos);
                        buffer.writeVarInt(payload.scopeId);
                        buffer.writeVarInt(payload.operationId);
                        buffer.writeUtf(payload.targetInput, MAX_TARGET_INPUT_LENGTH);
                        buffer.writeVarInt(payload.accessId);
                        buffer.writeVarLong(payload.expectedRevision);
                    },
                    buffer -> {
                        try {
                            return new TrustCommandPayload(
                                    buffer.readVarInt(),
                                    buffer.readBlockPos(),
                                    buffer.readVarInt(),
                                    buffer.readVarInt(),
                                    buffer.readUtf(MAX_TARGET_INPUT_LENGTH),
                                    buffer.readVarInt(),
                                    buffer.readVarLong());
                        } catch (IllegalArgumentException exception) {
                            throw new DecoderException("Invalid trust command payload", exception);
                        }
                    });

    public TrustCommandPayload {
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
        if (TrustScope.byId(scopeId) == null
                || TrustOperation.byId(operationId) == null
                || AccessLevel.byIdStrict(accessId) == null) {
            throw new IllegalArgumentException("Invalid trust command enum id");
        }
        if (targetInput == null || targetInput.isBlank()
                || targetInput.length() > MAX_TARGET_INPUT_LENGTH) {
            throw new IllegalArgumentException(
                    "targetInput must contain 1-" + MAX_TARGET_INPUT_LENGTH + " characters");
        }
        if (targetInput.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("targetInput must not contain control characters");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must be non-negative");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
