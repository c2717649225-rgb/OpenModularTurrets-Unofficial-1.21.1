package omtteam.openmodularturrets.blockentity.base;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;

import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.data.OwnershipRules;
import omtteam.openmodularturrets.security.SecuritySavedData;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * High-cohesion sub-component managing local trust maps, revisions, and permissions.
 */
public final class BaseTrustManager {
    public static final int MAX_LOCAL_TRUST = 128;
    private final Map<UUID, LocalTrustEntry> localTrust = new HashMap<>();
    private long localTrustRevision;

    public BaseTrustManager() {
    }

    public AccessLevel accessFor(Player player, @Nullable UUID owner, String ownerName,
                                 boolean useGlobalTrust, @Nullable Level level, boolean opCheck) {
        if (owner != null && OwnershipRules.matches(owner, ownerName, player.getUUID(),
                player.getGameProfile().getName(), ModServerConfig.offlineModeSupport())) {
            return AccessLevel.ADMIN;
        }
        if (useGlobalTrust) {
            if (level instanceof ServerLevel serverLevel && owner != null) {
                AccessLevel access = SecuritySavedData.get(serverLevel).accessFor(owner,
                        player.getUUID(), player.getGameProfile().getName(),
                        ModServerConfig.offlineModeSupport());
                return access != AccessLevel.NONE ? access : (opCheck ? AccessLevel.ADMIN : AccessLevel.NONE);
            }
            return opCheck ? AccessLevel.ADMIN : AccessLevel.NONE;
        }
        LocalTrustEntry direct = localTrust.get(player.getUUID());
        if (direct != null) {
            return direct.access();
        }
        if (ModServerConfig.offlineModeSupport()) {
            AccessLevel matched = localTrust.values().stream()
                    .filter(entry -> OwnershipRules.matches(entry.player(), entry.name(),
                            player.getUUID(), player.getGameProfile().getName(), true))
                    .map(LocalTrustEntry::access)
                    .max(Comparator.comparingInt(AccessLevel::id))
                    .orElse(AccessLevel.NONE);
            if (matched != AccessLevel.NONE) {
                return matched;
            }
        }
        return opCheck ? AccessLevel.ADMIN : AccessLevel.NONE;
    }

    public boolean setLocalTrust(UUID target, String name, AccessLevel access, @Nullable UUID owner) {
        if ((owner != null && target.equals(owner)) || (!localTrust.containsKey(target) && localTrust.size() >= MAX_LOCAL_TRUST)) {
            return false;
        }
        LocalTrustEntry next = new LocalTrustEntry(target, sanitizeName(name), access);
        if (next.equals(localTrust.get(target))) {
            return false;
        }
        localTrust.put(target, next);
        localTrustRevision++;
        return true;
    }

    public boolean removeLocalTrust(UUID target) {
        if (localTrust.remove(target) == null) {
            return false;
        }
        localTrustRevision++;
        return true;
    }

    public Map<UUID, LocalTrustEntry> snapshot() {
        return Map.copyOf(localTrust);
    }

    public boolean contains(UUID playerId) {
        return localTrust.containsKey(playerId);
    }

    /**
     * Offline-mode name fallback probed directly against the live map so the
     * targeting hot path never pays for a {@link #snapshot()} copy.
     */
    public boolean matchesByName(UUID candidateId, @Nullable String candidateName) {
        if (candidateName == null || candidateName.isBlank()) {
            return false;
        }
        for (LocalTrustEntry entry : localTrust.values()) {
            if (!entry.player().equals(candidateId)
                    && !entry.name().isEmpty()
                    && entry.name().equalsIgnoreCase(candidateName)) {
                return true;
            }
        }
        return false;
    }

    public long revision() {
        return localTrustRevision;
    }

    public void clear() {
        localTrust.clear();
        localTrustRevision++;
    }

    public void saveNbt(CompoundTag tag) {
        tag.putLong("local_trust_revision", localTrustRevision);
        ListTag trust = new ListTag();
        for (LocalTrustEntry entry : localTrust.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("player", entry.player());
            entryTag.putString("name", entry.name());
            entryTag.putInt("access", entry.access().id());
            trust.add(entryTag);
        }
        tag.put("local_trust", trust);
    }

    public void loadNbt(CompoundTag tag, @Nullable UUID owner) {
        if (tag.contains("local_trust_revision", Tag.TAG_LONG)) {
            localTrustRevision = tag.getLong("local_trust_revision");
        }
        if (tag.contains("local_trust", Tag.TAG_LIST)) {
            localTrust.clear();
            ListTag trust = tag.getList("local_trust", Tag.TAG_COMPOUND);
            for (int i = 0; i < Math.min(trust.size(), MAX_LOCAL_TRUST); i++) {
                CompoundTag entry = trust.getCompound(i);
                if (entry.hasUUID("player")) {
                    UUID playerId = entry.getUUID("player");
                    if (!playerId.equals(owner)) {
                        localTrust.put(playerId, new LocalTrustEntry(playerId,
                                sanitizeName(entry.getString("name")),
                                AccessLevel.byId(entry.getInt("access"))));
                    }
                }
            }
        }
    }

    private static String sanitizeName(@Nullable String name) {
        return name == null ? "" : name.trim();
    }

    public record LocalTrustEntry(UUID player, String name, AccessLevel access) {
    }
}
