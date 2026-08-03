package omtteam.openmodularturrets.data;

import javax.annotation.Nullable;

public enum AccessLevel {
    NONE(0),
    VIEW(1),
    USE(2),
    ADMIN(3);

    private final int id;

    AccessLevel(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean allows(AccessLevel required) {
        return id >= required.id;
    }

    public static AccessLevel byId(int id) {
        AccessLevel value = byIdStrict(id);
        return value == null ? NONE : value;
    }

    @Nullable
    public static AccessLevel byIdStrict(int id) {
        for (AccessLevel value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        return null;
    }
}
