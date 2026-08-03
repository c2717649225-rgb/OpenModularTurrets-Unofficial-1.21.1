package omtteam.openmodularturrets.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.HashSet;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.data.AccessLevel;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TrustSnapshotPayload(
        int containerId,
        BlockPos pos,
        UUID owner,
        int scopeId,
        long revision,
        List<Entry> entries) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_NAME_LENGTH = 40;
    public static final Type<TrustSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, "trust_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TrustSnapshotPayload> STREAM_CODEC =
            StreamCodec.of(TrustSnapshotPayload::encode, TrustSnapshotPayload::decode);

    public TrustSnapshotPayload {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(entries, "entries");
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (TrustScope.byId(scopeId) == null) {
            throw new IllegalArgumentException("Invalid trust snapshot scope");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many trust snapshot entries");
        }
        HashSet<UUID> players = new HashSet<>();
        for (Entry entry : entries) {
            if (!players.add(entry.player())) {
                throw new IllegalArgumentException("Duplicate trust snapshot player");
            }
        }
        entries = List.copyOf(entries);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, TrustSnapshotPayload payload) {
        buffer.writeVarInt(payload.containerId);
        buffer.writeBlockPos(payload.pos);
        buffer.writeUUID(payload.owner);
        buffer.writeVarInt(payload.scopeId);
        buffer.writeVarLong(payload.revision);
        buffer.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            buffer.writeUUID(entry.player());
            buffer.writeUtf(entry.name(), MAX_NAME_LENGTH);
            buffer.writeVarInt(entry.accessId());
        }
    }

    private static TrustSnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
        try {
            int containerId = buffer.readVarInt();
            BlockPos pos = buffer.readBlockPos();
            UUID owner = buffer.readUUID();
            int scopeId = buffer.readVarInt();
            long revision = buffer.readVarLong();
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_ENTRIES) {
                throw new DecoderException(
                        "Trust snapshot entry count " + size + " exceeds " + MAX_ENTRIES);
            }
            List<Entry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                entries.add(new Entry(
                        buffer.readUUID(),
                        buffer.readUtf(MAX_NAME_LENGTH),
                        buffer.readVarInt()));
            }
            return new TrustSnapshotPayload(
                    containerId, pos, owner, scopeId, revision, entries);
        } catch (DecoderException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new DecoderException("Invalid trust snapshot payload", exception);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Entry(UUID player, String name, int accessId) {
        public Entry {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(name, "name");
            if (name.length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "Trust snapshot name exceeds " + MAX_NAME_LENGTH + " characters");
            }
            if (name.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "Trust snapshot name must not contain control characters");
            }
            if (AccessLevel.byIdStrict(accessId) == null) {
                throw new IllegalArgumentException("Invalid trust snapshot access id");
            }
        }
    }
}
