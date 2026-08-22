package omtteam.openmodularturrets.client;

import net.minecraft.client.gui.screens.Screen;

/**
 * Client-only input bridge for common tooltip assembly.
 *
 * <p>Keeping the {@code Screen} reference in the physical client package
 * prevents common item code from resolving a client-only class on a dedicated
 * server.  The common caller still guards the bridge with the current
 * distribution before invoking it.</p>
 */
public final class ClientTooltipUtil {
    private ClientTooltipUtil() {
    }

    public static boolean hasShiftDown() {
        return Screen.hasShiftDown();
    }
}
