package omtteam.openmodularturrets.data;

import javax.annotation.Nullable;

/**
 * Redstone control modes in the stable order used by the 1.12 OMLib enum.
 */
public enum BaseMode {
    ALWAYS_ON(0),
    ALWAYS_OFF(1),
    INVERTED(2),
    NONINVERTED(3);

    public static final BaseMode DEFAULT = INVERTED;

    private final int id;

    BaseMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean isActive(boolean powered) {
        return switch (this) {
            case ALWAYS_ON -> true;
            case ALWAYS_OFF -> false;
            case INVERTED -> !powered;
            case NONINVERTED -> powered;
        };
    }

    public BaseMode next() {
        return switch (this) {
            case ALWAYS_ON -> ALWAYS_OFF;
            case ALWAYS_OFF -> INVERTED;
            case INVERTED -> NONINVERTED;
            case NONINVERTED -> ALWAYS_ON;
        };
    }

    @Nullable
    public static BaseMode byId(int id) {
        for (BaseMode mode : values()) {
            if (mode.id == id) {
                return mode;
            }
        }
        return null;
    }

    public static BaseMode byIdOrDefault(int id) {
        BaseMode mode = byId(id);
        return mode == null ? DEFAULT : mode;
    }
}
