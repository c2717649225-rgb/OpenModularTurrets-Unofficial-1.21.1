package omtteam.openmodularturrets.network;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class PacketRateLimiter {
    enum Channel {
        BASE_COMMAND,
        TRUST_REQUEST,
        TRUST_COMMAND
    }

    private static final Map<MinecraftServer, Map<Key, ArrayDeque<Long>>> WINDOWS =
            new WeakHashMap<>();

    private PacketRateLimiter() {
    }

    static boolean allow(ServerPlayer player, Channel channel, int limit) {
        MinecraftServer server = player.getServer();
        if (server == null || limit <= 0) {
            return false;
        }
        long now = player.serverLevel().getGameTime();
        Map<Key, ArrayDeque<Long>> serverWindows =
                WINDOWS.computeIfAbsent(server, ignored -> new HashMap<>());
        Key key = new Key(player.getUUID(), channel);
        ArrayDeque<Long> events =
                serverWindows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        while (!events.isEmpty() && events.peekFirst() <= now - 20L) {
            events.removeFirst();
        }
        if (events.size() >= limit) {
            return false;
        }
        events.addLast(now);
        if (serverWindows.size() > 4_096) {
            serverWindows.entrySet().removeIf(entry -> {
                ArrayDeque<Long> ticks = entry.getValue();
                while (!ticks.isEmpty() && ticks.peekFirst() <= now - 20L) {
                    ticks.removeFirst();
                }
                return ticks.isEmpty();
            });
        }
        return true;
    }

    private record Key(UUID player, Channel channel) {
    }
}
