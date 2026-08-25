package omtteam.openmodularturrets.security;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.data.OwnershipRules;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.Comparator;
public final class SecuritySavedData extends SavedData {
    private static final String FILE_ID = OpenModularTurrets.MOD_ID + "_security";
    private static final int DATA_VERSION = 2;
    private static final int MAX_OWNERS = 4_096;
    private static final int MAX_ENTRIES_PER_OWNER = 256;

    private final Map<UUID, Map<UUID, TrustEntry>> entries = new HashMap<>();
    private final Map<UUID, Long> revisions = new HashMap<>();

    public static SecuritySavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new Factory<>(SecuritySavedData::new, SecuritySavedData::load), FILE_ID);
    }

    public AccessLevel accessFor(UUID owner, UUID player) {
        return accessFor(owner, player, "", false);
    }

    public AccessLevel accessFor(UUID owner, UUID player, String playerName,
            boolean offlineModeSupport) {
        if (owner.equals(player)) {
            return AccessLevel.ADMIN;
        }
        Map<UUID, TrustEntry> ownerEntries = entries.getOrDefault(owner, Map.of());
        TrustEntry direct = ownerEntries.get(player);
        if (direct != null) {
            return direct.access();
        }
        if (!offlineModeSupport) {
            return AccessLevel.NONE;
        }
        return ownerEntries.values().stream()
                .filter(entry -> OwnershipRules.matches(entry.player(), entry.name(), player,
                        playerName, true))
                .map(TrustEntry::access)
                .max(Comparator.comparingInt(AccessLevel::id))
                .orElse(AccessLevel.NONE);
    }

    public boolean setAccess(UUID owner, UUID player, AccessLevel access) {
        return setAccess(owner, player, player.toString(), access);
    }

    public boolean setAccess(UUID owner, UUID player, String name, AccessLevel access) {
        if (owner.equals(player)) {
            return false;
        }
        Map<UUID, TrustEntry> ownerEntries = entries.get(owner);
        if (ownerEntries == null) {
            if (entries.size() >= MAX_OWNERS) {
                return false;
            }
            ownerEntries = new HashMap<>();
            entries.put(owner, ownerEntries);
        }
        if (!ownerEntries.containsKey(player) && ownerEntries.size() >= MAX_ENTRIES_PER_OWNER) {
            return false;
        }
        TrustEntry next = new TrustEntry(player, sanitizeName(name), access);
        if (next.equals(ownerEntries.get(player))) {
            return false;
        }
        ownerEntries.put(player, next);
        bumpRevision(owner);
        setDirty();
        return true;
    }

    public boolean removeAccess(UUID owner, UUID player) {
        Map<UUID, TrustEntry> ownerEntries = entries.get(owner);
        if (ownerEntries == null || ownerEntries.remove(player) == null) {
            return false;
        }
        if (ownerEntries.isEmpty()) {
            entries.remove(owner);
        }
        bumpRevision(owner);
        setDirty();
        return true;
    }

    public boolean hasEntry(UUID owner, UUID player) {
        return owner.equals(player)
                || entries.getOrDefault(owner, Map.of()).containsKey(player);
    }

    public boolean hasEntry(UUID owner, UUID player, String playerName,
            boolean offlineModeSupport) {
        return accessFor(owner, player, playerName, offlineModeSupport) != AccessLevel.NONE;
    }

    public long revision(UUID owner) {
        return revisions.getOrDefault(owner, 0L);
    }

    public Map<UUID, TrustEntry> snapshot(UUID owner) {
        return Map.copyOf(entries.getOrDefault(owner, Map.of()));
    }

    private void bumpRevision(UUID owner) {
        revisions.put(owner, revision(owner) + 1L);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("data_version", DATA_VERSION);
        ListTag owners = new ListTag();
        entries.forEach((owner, ownerEntries) -> {
            CompoundTag ownerTag = new CompoundTag();
            ownerTag.putUUID("owner", owner);
            ownerTag.putLong("revision", revision(owner));
            ListTag trusted = new ListTag();
            ownerEntries.forEach((player, trustEntry) -> {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("player", player);
                entry.putString("name", trustEntry.name());
                entry.putInt("access", trustEntry.access().id());
                trusted.add(entry);
            });
            ownerTag.put("trusted", trusted);
            owners.add(ownerTag);
        });
        tag.put("owners", owners);
        return tag;
    }

    private static SecuritySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SecuritySavedData data = new SecuritySavedData();
        ListTag owners = tag.getList("owners", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(owners.size(), MAX_OWNERS); i++) {
            CompoundTag ownerTag = owners.getCompound(i);
            if (!ownerTag.hasUUID("owner")) {
                continue;
            }
            UUID owner = ownerTag.getUUID("owner");
            Map<UUID, TrustEntry> ownerEntries = new HashMap<>();
            ListTag trusted = ownerTag.getList("trusted", Tag.TAG_COMPOUND);
            for (int j = 0; j < Math.min(trusted.size(), MAX_ENTRIES_PER_OWNER); j++) {
                CompoundTag entry = trusted.getCompound(j);
                if (entry.hasUUID("player")) {
                    UUID player = entry.getUUID("player");
                    if (!player.equals(owner)) {
                        ownerEntries.put(player, new TrustEntry(player,
                                sanitizeName(entry.getString("name")),
                                AccessLevel.byId(entry.getInt("access"))));
                    }
                }
            }
            if (!ownerEntries.isEmpty()) {
                data.entries.put(owner, ownerEntries);
            }
            long revision = Math.max(0L, ownerTag.getLong("revision"));
            if (revision > 0L) {
                data.revisions.put(owner, revision);
            }
        }
        return data;
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(name.length(), 40));
        name.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(40)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    public record TrustEntry(UUID player, String name, AccessLevel access) {
        public TrustEntry {
            name = sanitizeName(name);
        }
    }
}
