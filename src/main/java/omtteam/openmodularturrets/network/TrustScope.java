package omtteam.openmodularturrets.network;

import javax.annotation.Nullable;

/**
 * Identifies which independently revisioned trust list a command or snapshot addresses.
 */
public enum TrustScope {
    LOCAL(0),
    GLOBAL(1);

    private final int id;

    TrustScope(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    @Nullable
    public static TrustScope byId(int id) {
        for (TrustScope scope : values()) {
            if (scope.id == id) {
                return scope;
            }
        }
        return null;
    }
}
