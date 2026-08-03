package omtteam.openmodularturrets.network;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * Pure reducer for client trust data. The caller owns the returned immutable state, so closing or
 * replacing a menu cannot leak entries into another menu session.
 */
public final class ClientTrustSnapshot {
    private ClientTrustSnapshot() {
    }

    public static State begin(Session session) {
        return new State(session, Map.of());
    }

    public static State reduce(State current, Session activeSession,
            TrustSnapshotPayload payload) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(activeSession, "activeSession");
        Objects.requireNonNull(payload, "payload");
        if (!current.session().equals(activeSession)
                || !activeSession.matches(payload)) {
            return current;
        }
        TrustScope scope = TrustScope.byId(payload.scopeId());
        if (scope == null) {
            return current;
        }
        Snapshot previous = current.snapshots().get(scope);
        if (previous != null && payload.revision() <= previous.revision()) {
            return current;
        }
        Snapshot replacement = new Snapshot(payload.revision(), payload.entries());
        EnumMap<TrustScope, Snapshot> snapshots = new EnumMap<>(TrustScope.class);
        snapshots.putAll(current.snapshots());
        snapshots.put(scope, replacement);
        return new State(activeSession, snapshots);
    }

    public record Session(int containerId, BlockPos pos, UUID owner) {
        public Session {
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(owner, "owner");
            if (containerId < 0) {
                throw new IllegalArgumentException("containerId must be non-negative");
            }
        }

        private boolean matches(TrustSnapshotPayload payload) {
            return containerId == payload.containerId()
                    && pos.equals(payload.pos())
                    && owner.equals(payload.owner());
        }
    }

    public record State(Session session, Map<TrustScope, Snapshot> snapshots) {
        public State {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(snapshots, "snapshots");
            snapshots = Map.copyOf(snapshots);
        }

        public Snapshot snapshot(TrustScope scope) {
            return snapshots.getOrDefault(scope, Snapshot.EMPTY);
        }
    }

    public record Snapshot(long revision, List<TrustSnapshotPayload.Entry> entries) {
        private static final Snapshot EMPTY = new Snapshot(0L, List.of());

        public Snapshot {
            Objects.requireNonNull(entries, "entries");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must be non-negative");
            }
            if (entries.size() > TrustSnapshotPayload.MAX_ENTRIES) {
                throw new IllegalArgumentException("Too many trust snapshot entries");
            }
            entries = List.copyOf(entries);
        }
    }
}
