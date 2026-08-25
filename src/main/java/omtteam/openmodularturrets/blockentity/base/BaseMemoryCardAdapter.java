package omtteam.openmodularturrets.blockentity.base;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import omtteam.openmodularturrets.data.BaseMode;
import omtteam.openmodularturrets.data.MemoryCardProfile;

/**
 * Adapter handling export and import conversions between memory card data profiles and base settings.
 */
public final class BaseMemoryCardAdapter {
    private BaseMemoryCardAdapter() {
    }

    public static MemoryCardProfile exportProfile(int configuredRange, BaseMode mode,
                                                  boolean attackHostile, boolean attackNeutral,
                                                  boolean attackPlayers, boolean multiTargeting,
                                                  Map<UUID, BaseTrustManager.LocalTrustEntry> trustSnapshot) {
        List<MemoryCardProfile.TrustEntry> trust = trustSnapshot.values().stream()
                .map(entry -> new MemoryCardProfile.TrustEntry(
                        entry.player(), entry.name(), entry.access()))
                .toList();
        return new MemoryCardProfile(MemoryCardProfile.CURRENT_SCHEMA, configuredRange, mode.id(),
                attackHostile, attackNeutral, attackPlayers, multiTargeting, trust, true);
    }

    public static void applyTrust(BaseTrustManager trustManager,
                                  List<MemoryCardProfile.TrustEntry> trustEntries,
                                  UUID owner) {
        trustManager.clear();
        for (MemoryCardProfile.TrustEntry entry : trustEntries) {
            trustManager.setLocalTrust(entry.player(), entry.name(), entry.access(), owner);
        }
    }
}
