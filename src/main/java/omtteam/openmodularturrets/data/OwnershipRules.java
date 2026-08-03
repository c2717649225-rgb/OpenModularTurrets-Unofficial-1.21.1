package omtteam.openmodularturrets.data;

import java.util.UUID;

/**
 * Identity and protection rules retained from OMLib 1.12.2.  Offline-mode
 * compatibility deliberately falls back to case-insensitive player names only
 * when the server explicitly enables it.
 */
public final class OwnershipRules {
    private OwnershipRules() {
    }

    public static boolean matches(UUID storedId, String storedName, UUID candidateId,
            String candidateName, boolean offlineModeSupport) {
        if (storedId != null && storedId.equals(candidateId)) {
            return true;
        }
        return offlineModeSupport && !isBlank(storedName) && !isBlank(candidateName)
                && storedName.equalsIgnoreCase(candidateName);
    }

    public static boolean opIsProtected(boolean canOpAccessOwnedBlocks, boolean isOperator) {
        return canOpAccessOwnedBlocks && isOperator;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
