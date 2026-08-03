package omtteam.openmodularturrets.network;

import javax.annotation.Nullable;

/**
 * A stable wire-level trust mutation intent. IDs must not depend on enum ordinals.
 */
public enum TrustOperation {
    ADD(0),
    REMOVE(1),
    SET_LEVEL(2);

    private final int id;

    TrustOperation(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    @Nullable
    public static TrustOperation byId(int id) {
        for (TrustOperation operation : values()) {
            if (operation.id == id) {
                return operation;
            }
        }
        return null;
    }
}
