package omtteam.openmodularturrets.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public record MemoryCardProfile(
        int schemaVersion,
        int range,
        int modeId,
        boolean attackHostile,
        boolean attackNeutral,
        boolean attackPlayers,
        boolean multiTargeting,
        List<TrustEntry> trustEntries,
        boolean trustCarried) {
    public static final int CURRENT_SCHEMA = 4;
    /** First schema whose serialized form carries the trusted-player list. */
    public static final int TRUST_SCHEMA = 4;
    public static final int MAX_RANGE = 64;
    public static final MemoryCardProfile DEFAULT =
            new MemoryCardProfile(CURRENT_SCHEMA, 10, BaseMode.DEFAULT.id(),
                    false, true, false, false, List.of(), true);

    public static final Codec<MemoryCardProfile> CODEC = Serialized.CODEC.flatXmap(
            MemoryCardProfile::decode,
            profile -> DataResult.success(new Serialized(
                    CURRENT_SCHEMA,
                    profile.range,
                    Optional.empty(),
                    Optional.of(profile.modeId),
                    profile.attackHostile,
                    profile.attackNeutral,
                    profile.attackPlayers,
                    profile.multiTargeting,
                    profile.carriesTrust()
                            ? Optional.of(profile.trustEntries)
                            : Optional.empty())));

    public static final StreamCodec<RegistryFriendlyByteBuf, MemoryCardProfile> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, profile) -> {
                        buffer.writeVarInt(profile.schemaVersion);
                        buffer.writeVarInt(profile.range);
                        buffer.writeVarInt(profile.modeId);
                        buffer.writeBoolean(profile.attackHostile);
                        buffer.writeBoolean(profile.attackNeutral);
                        buffer.writeBoolean(profile.attackPlayers);
                        buffer.writeBoolean(profile.multiTargeting);
                        buffer.writeBoolean(profile.carriesTrust());
                        if (profile.carriesTrust()) {
                            writeTrust(buffer, profile.trustEntries);
                        }
                    },
                    buffer -> {
                        int schemaVersion = buffer.readVarInt();
                        int range = buffer.readVarInt();
                        int modeId = schemaVersion <= 2
                                ? modeFromLegacyActive(buffer.readBoolean()).id()
                                : buffer.readVarInt();
                        boolean attackHostile = buffer.readBoolean();
                        boolean attackNeutral = buffer.readBoolean();
                        boolean attackPlayers = buffer.readBoolean();
                        boolean multiTargeting = buffer.readBoolean();
                        boolean trustCarried = false;
                        List<TrustEntry> trustEntries = List.of();
                        if (schemaVersion >= TRUST_SCHEMA) {
                            trustCarried = buffer.readBoolean();
                            if (trustCarried) {
                                trustEntries = readTrust(buffer);
                            }
                        }
                        return new MemoryCardProfile(
                                schemaVersion,
                                range,
                                modeId,
                                attackHostile,
                                attackNeutral,
                                attackPlayers,
                                multiTargeting,
                                trustEntries,
                                trustCarried);
                    });

    public MemoryCardProfile {
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA) {
            throw new IllegalArgumentException("Unsupported memory-card schema " + schemaVersion);
        }
        if (BaseMode.byId(modeId) == null) {
            throw new IllegalArgumentException("Unsupported base mode " + modeId);
        }
        schemaVersion = CURRENT_SCHEMA;
        range = Math.clamp(range, 1, MAX_RANGE);
        trustEntries = trustEntries == null ? List.of() : List.copyOf(trustEntries);
    }

    /**
     * Compatibility constructor for schema 1/2 callers until the base itself owns a mode.
     */
    public MemoryCardProfile(int schemaVersion, int range, boolean active,
            boolean attackHostile, boolean attackNeutral, boolean attackPlayers,
            boolean multiTargeting) {
        this(schemaVersion, range, modeFromLegacyActive(active).id(),
                attackHostile, attackNeutral, attackPlayers, multiTargeting, List.of(), false);
    }

    public BaseMode mode() {
        return BaseMode.byIdOrDefault(modeId);
    }

    /**
     * True when the serialized card actually carried a trusted-player list.
     * Older cards (schema 1-3) must leave the destination base's trust untouched
     * on load, even though their in-memory schema is normalized to CURRENT_SCHEMA.
     */
    public boolean carriesTrust() {
        return trustCarried;
    }

    /**
     * Temporary compatibility view for code that predates redstone-aware base modes.
     */
    public boolean active() {
        return mode().isActive(false);
    }

    private static BaseMode modeFromLegacyActive(boolean active) {
        return active ? BaseMode.ALWAYS_ON : BaseMode.ALWAYS_OFF;
    }

    private static DataResult<MemoryCardProfile> decode(Serialized serialized) {
        if (serialized.schemaVersion <= 2) {
            if (serialized.active.isEmpty()) {
                return DataResult.error(() ->
                        "Memory-card schema " + serialized.schemaVersion
                                + " requires the active field");
            }
            return DataResult.success(new MemoryCardProfile(
                    serialized.schemaVersion,
                    serialized.range,
                    modeFromLegacyActive(serialized.active.orElseThrow()).id(),
                    serialized.attackHostile,
                    serialized.attackNeutral,
                    serialized.attackPlayers,
                    serialized.multiTargeting,
                    List.of(),
                    false));
        }
        if (serialized.modeId.isEmpty()) {
            return DataResult.error(() ->
                    "Memory-card schema 3 requires the mode field");
        }
        return DataResult.success(new MemoryCardProfile(
                serialized.schemaVersion,
                serialized.range,
                serialized.modeId.orElseThrow(),
                serialized.attackHostile,
                serialized.attackNeutral,
                serialized.attackPlayers,
                serialized.multiTargeting,
                serialized.trust.orElse(List.of()),
                serialized.trust.isPresent()));
    }

    private static void writeTrust(RegistryFriendlyByteBuf buffer, List<TrustEntry> entries) {
        buffer.writeVarInt(entries.size());
        for (TrustEntry entry : entries) {
            buffer.writeUUID(entry.player());
            buffer.writeUtf(entry.name());
            buffer.writeVarInt(entry.access().id());
        }
    }

    private static List<TrustEntry> readTrust(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<TrustEntry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(new TrustEntry(
                    buffer.readUUID(),
                    buffer.readUtf(),
                    AccessLevel.byId(buffer.readVarInt())));
        }
        return List.copyOf(entries);
    }

    private record Serialized(
            int schemaVersion,
            int range,
            Optional<Boolean> active,
            Optional<Integer> modeId,
            boolean attackHostile,
            boolean attackNeutral,
            boolean attackPlayers,
            boolean multiTargeting,
            Optional<List<TrustEntry>> trust) {
        private static final Codec<Serialized> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.intRange(1, CURRENT_SCHEMA)
                                .fieldOf("schema_version").forGetter(Serialized::schemaVersion),
                        Codec.intRange(1, MAX_RANGE)
                                .fieldOf("range").forGetter(Serialized::range),
                        Codec.BOOL.optionalFieldOf("active").forGetter(Serialized::active),
                        Codec.intRange(0, 3).optionalFieldOf("mode")
                                .forGetter(Serialized::modeId),
                        Codec.BOOL.fieldOf("attack_hostile")
                                .forGetter(Serialized::attackHostile),
                        Codec.BOOL.fieldOf("attack_neutral")
                                .forGetter(Serialized::attackNeutral),
                        Codec.BOOL.fieldOf("attack_players")
                                .forGetter(Serialized::attackPlayers),
                        Codec.BOOL.optionalFieldOf("multi_targeting", false)
                                .forGetter(Serialized::multiTargeting),
                        Codec.list(TrustEntry.CODEC)
                                .optionalFieldOf("trust")
                                .forGetter(Serialized::trust)
                ).apply(instance, Serialized::new));
    }

    /**
     * A single trusted-player entry captured by the memory card, mirroring the
     * legacy {@code trustedPlayers} NBT that the 1.12 base wrote to cards.
     */
    public record TrustEntry(UUID player, String name, AccessLevel access) {
        public static final Codec<TrustEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.CODEC.fieldOf("player").forGetter(TrustEntry::player),
                        Codec.STRING.optionalFieldOf("name", "").forGetter(TrustEntry::name),
                        Codec.intRange(0, 3).fieldOf("access")
                                .forGetter(entry -> entry.access().id())
                ).apply(instance, (player, name, access) ->
                        new TrustEntry(player, name, AccessLevel.byId(access))));
    }
}
