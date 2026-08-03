package omtteam.openmodularturrets.network;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.GameProfile;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.blockentity.TurretHeadBlockEntity;
import omtteam.openmodularturrets.client.render.BeamRenderCache;
import omtteam.openmodularturrets.data.TurretVisualRules;
import omtteam.openmodularturrets.menu.TurretBaseMenu;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.joml.Vector3f;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "2";

    private ModNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(BaseCommandPayload.TYPE, BaseCommandPayload.STREAM_CODEC,
                ModNetwork::handleBaseCommand);
        registrar.playToServer(TrustSnapshotRequestPayload.TYPE,
                TrustSnapshotRequestPayload.STREAM_CODEC,
                ModNetwork::handleTrustSnapshotRequest);
        registrar.playToServer(TrustCommandPayload.TYPE, TrustCommandPayload.STREAM_CODEC,
                ModNetwork::handleTrustCommand);
        registrar.playToClient(TrustSnapshotPayload.TYPE, TrustSnapshotPayload.STREAM_CODEC,
                ModNetwork::handleTrustSnapshot);
        registrar.playToClient(TurretAimPayload.TYPE, TurretAimPayload.STREAM_CODEC,
                ModNetwork::handleTurretAim);
        registrar.playToClient(BeamEffectPayload.TYPE, BeamEffectPayload.STREAM_CODEC,
                ModNetwork::handleBeamEffect);
    }

    private static void handleBaseCommand(BaseCommandPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && PacketRateLimiter.allow(player,
                        PacketRateLimiter.Channel.BASE_COMMAND, 20)) {
            BaseCommandService.apply(player, payload);
        }
    }

    private static void handleTrustSnapshotRequest(TrustSnapshotRequestPayload payload,
            IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && PacketRateLimiter.allow(player,
                        PacketRateLimiter.Channel.TRUST_REQUEST, 10)) {
            TrustService.requestSnapshot(player, payload);
        }
    }

    private static void handleTrustCommand(TrustCommandPayload payload,
            IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)
                || !PacketRateLimiter.allow(player,
                        PacketRateLimiter.Channel.TRUST_COMMAND, 10)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        resolveProfile(player, payload.targetInput())
                .thenAccept(profile -> server.execute(() -> profile.ifPresent(resolved ->
                        TrustService.applyResolved(player, payload,
                                resolved.getId(), resolved.getName()))))
                .exceptionally(error -> {
                    OpenModularTurrets.LOGGER.debug(
                            "Unable to resolve trust target {}", payload.targetInput(), error);
                    return null;
                });
    }

    private static CompletableFuture<Optional<GameProfile>> resolveProfile(
            ServerPlayer player, String input) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            UUID id = UUID.fromString(input);
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                return CompletableFuture.completedFuture(
                        Optional.of(online.getGameProfile()));
            }
            Optional<GameProfile> cached = server.getProfileCache().get(id);
            return CompletableFuture.completedFuture(cached.isPresent()
                    ? cached : Optional.of(new GameProfile(id, input)));
        } catch (IllegalArgumentException ignored) {
            // Continue with a bounded vanilla player-name lookup.
        }
        if (!input.matches("[A-Za-z0-9_]{1,16}")) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(input);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getGameProfile()));
        }
        return server.getProfileCache().getAsync(input);
    }

    private static void handleTrustSnapshot(TrustSnapshotPayload payload,
            IPayloadContext context) {
        if (context.player().containerMenu instanceof TurretBaseMenu menu) {
            menu.acceptTrustSnapshot(payload);
        }
    }

    private static void handleTurretAim(TurretAimPayload payload, IPayloadContext context) {
        if (context.player().level().getBlockEntity(payload.pos())
                instanceof TurretHeadBlockEntity turret) {
            turret.applyNetworkAim(payload.yaw(), payload.pitch(),
                    payload.targetEntityId());
        }
    }

    private static void handleBeamEffect(BeamEffectPayload payload, IPayloadContext context) {
        // Queue the translucent ray for its full legacy lifetime; the render
        // pass draws it as a lit line (see BeamRenderCache).
        BeamRenderCache.add(payload.start(), payload.end(), payload.color(),
                payload.alpha(), payload.durationTicks());
    }
}
