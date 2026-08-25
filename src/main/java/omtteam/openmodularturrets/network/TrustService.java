package omtteam.openmodularturrets.network;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.menu.TurretBaseMenu;
import omtteam.openmodularturrets.security.SecuritySavedData;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server trust-list operations shared by packet handlers and GameTests.
 */
public final class TrustService {
    private TrustService() {
    }

    public static boolean requestSnapshot(ServerPlayer player,
            TrustSnapshotRequestPayload payload) {
        TrustScope scope = TrustScope.byId(payload.scopeId());
        if (scope == null) {
            return false;
        }
        return BaseCommandService.validateOpenBase(player, payload.containerId(),
                payload.pos(), AccessLevel.ADMIN)
                .map(menu -> {
                    if (!mayManageScope(player, menu.base(), scope)) {
                        return false;
                    }
                    sendSnapshot(player, menu, scope);
                    return true;
                })
                .orElse(false);
    }

    public static boolean applyResolved(ServerPlayer player, TrustCommandPayload payload,
            UUID target, String name) {
        TrustScope scope = TrustScope.byId(payload.scopeId());
        TrustOperation operation = TrustOperation.byId(payload.operationId());
        AccessLevel access = AccessLevel.byIdStrict(payload.accessId());
        if (scope == null || operation == null || access == null) {
            return false;
        }
        TurretBaseMenu menu = BaseCommandService.validateOpenBase(player,
                payload.containerId(), payload.pos(), AccessLevel.ADMIN).orElse(null);
        if (menu == null || menu.base().owner().isEmpty()
                || menu.base().owner().orElseThrow().equals(target)
                || !mayManageScope(player, menu.base(), scope)) {
            return false;
        }
        TurretBaseBlockEntity base = menu.base();
        long currentRevision = revision(player, base, scope);
        if (payload.expectedRevision() != currentRevision) {
            sendSnapshot(player, menu, scope);
            return false;
        }

        boolean changed = switch (scope) {
            case LOCAL -> mutateLocal(player, base, operation, target, name, access);
            case GLOBAL -> mutateGlobal(player, base, operation, target, name, access);
        };
        if (changed) {
            broadcastSnapshotViewers(player, base, scope);
        } else {
            sendSnapshot(player, menu, scope);
        }
        return changed;
    }

    public static long revision(ServerPlayer player, TurretBaseBlockEntity base,
            TrustScope scope) {
        if (scope == TrustScope.LOCAL) {
            return base.localTrustRevision();
        }
        return base.owner().map(owner ->
                SecuritySavedData.get(player.serverLevel()).revision(owner)).orElse(0L);
    }

    public static TrustSnapshotPayload snapshot(ServerPlayer player, TurretBaseMenu menu,
            TrustScope scope) {
        TurretBaseBlockEntity base = menu.base();
        UUID owner = base.owner().orElse(ClientTrustSnapshot.NULL_SESSION_OWNER);
        List<TrustSnapshotPayload.Entry> entries;
        if (scope == TrustScope.LOCAL) {
            entries = base.localTrustSnapshot().values().stream()
                    .sorted(Comparator.comparing(entry -> entry.player().toString()))
                    .limit(TrustSnapshotPayload.MAX_ENTRIES)
                    .map(entry -> new TrustSnapshotPayload.Entry(entry.player(),
                            displayName(entry.name(), entry.player()), entry.access().id()))
                    .toList();
        } else {
            entries = SecuritySavedData.get(player.serverLevel()).snapshot(owner).values()
                    .stream()
                    .sorted(Comparator.comparing(entry -> entry.player().toString()))
                    .limit(TrustSnapshotPayload.MAX_ENTRIES)
                    .map(entry -> new TrustSnapshotPayload.Entry(entry.player(),
                            displayName(entry.name(), entry.player()), entry.access().id()))
                    .toList();
        }
        return new TrustSnapshotPayload(menu.containerId, base.getBlockPos(), owner,
                scope.id(), revision(player, base, scope), entries);
    }

    private static boolean mutateLocal(ServerPlayer actor, TurretBaseBlockEntity base,
            TrustOperation operation, UUID target, String name, AccessLevel access) {
        return switch (operation) {
            case ADD -> !base.hasLocalTrust(target)
                    && base.setLocalTrust(actor, target, name, access);
            case REMOVE -> base.removeLocalTrust(actor, target);
            case SET_LEVEL -> base.hasLocalTrust(target)
                    && base.setLocalTrust(actor, target, name, access);
        };
    }

    private static boolean mutateGlobal(ServerPlayer actor, TurretBaseBlockEntity base,
            TrustOperation operation, UUID target, String name, AccessLevel access) {
        UUID owner = base.owner().orElseThrow();
        SecuritySavedData security = SecuritySavedData.get(actor.serverLevel());
        return switch (operation) {
            case ADD -> !security.hasEntry(owner, target)
                    && security.setAccess(owner, target, name, access);
            case REMOVE -> security.removeAccess(owner, target);
            case SET_LEVEL -> security.hasEntry(owner, target)
                    && security.setAccess(owner, target, name, access);
        };
    }

    private static void broadcastSnapshotViewers(ServerPlayer actor,
            TurretBaseBlockEntity base, TrustScope scope) {
        if (actor.getServer() == null) {
            return;
        }
        for (ServerPlayer viewer : actor.getServer().getPlayerList().getPlayers()) {
            if (viewer.containerMenu instanceof TurretBaseMenu menu
                    && menu.base() == base
                    && menu.stillValid(viewer)
                    && base.accessFor(viewer) == AccessLevel.ADMIN) {
                sendSnapshot(viewer, menu, scope);
            }
        }
    }

    private static void sendSnapshot(ServerPlayer player, TurretBaseMenu menu,
            TrustScope scope) {
        PacketDistributor.sendToPlayer(player, snapshot(player, menu, scope));
    }

    private static boolean mayManageScope(ServerPlayer player,
            TurretBaseBlockEntity base, TrustScope requestedScope) {
        if (base.isOwner(player)) {
            return true;
        }
        TrustScope activeScope = base.useGlobalTrust()
                ? TrustScope.GLOBAL : TrustScope.LOCAL;
        // A delegated admin may manage only the currently selected local list.
        // Global trust is shared across every base owned by the same player, so it
        // remains owner-only and inactive lists cannot be probed with crafted packets.
        return requestedScope == TrustScope.LOCAL && activeScope == TrustScope.LOCAL;
    }

    private static String displayName(String name, UUID player) {
        return name == null || name.isBlank() ? player.toString() : name;
    }
}
