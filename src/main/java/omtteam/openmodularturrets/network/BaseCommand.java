package omtteam.openmodularturrets.network;

import javax.annotation.Nullable;

public enum BaseCommand {
    SET_ACTIVE(0),
    SET_RANGE(1),
    SET_TARGET_FLAGS(2),
    SET_MULTI_TARGET(3),
    SET_MODE(4),
    SET_TRUST_SCOPE(5),
    DROP_TURRETS(6),
    DROP_BASE(7),
    SET_CAMOUFLAGE_LIGHT(8),
    SET_CAMOUFLAGE_OPACITY(9),
    CLEAR_CAMOUFLAGE(10),
    ADJUST_RANGE(11),
    CYCLE_MODE(12),
    TOGGLE_MULTI_TARGET(13),
    TOGGLE_TARGET_FLAG(14),
    ADJUST_CAMOUFLAGE_LIGHT(15),
    ADJUST_CAMOUFLAGE_OPACITY(16);

    private final int id;

    BaseCommand(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    @Nullable
    public static BaseCommand byId(int id) {
        for (BaseCommand command : values()) {
            if (command.id == id) {
                return command;
            }
        }
        return null;
    }
}
