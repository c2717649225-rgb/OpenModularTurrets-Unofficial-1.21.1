package omtteam.openmodularturrets.network;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.blockentity.TurretHeadBlockEntity;
import omtteam.openmodularturrets.menu.TurretBaseMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "2";
    private static volatile Consumer<BeamEffectPayload> beamEffectHandler = payload -> {
    };

    private ModNetwork() {
    }

    /**
     * Installs the physical-client implementation for the client-bound beam
     * payload.  The common network registry keeps only this common callback
     * slot; the client event subscriber supplies the renderer at client setup.
     */
    public static void installBeamEffectHandler(Consumer<BeamEffectPayload> handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Beam effect handler must not be null");
        }
        beamEffectHandler = handler;
    }

    /**
     * Sends a vanilla block-entity update only to players tracking its chunk.
     * Block-entity state is local to the chunk, so dimension-wide broadcasts
     * waste bandwidth and become expensive when a turret fires or changes
     * state frequently.
     */
    public static void sendBlockEntityUpdateToTracking(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        ClientboundBlockEntityDataPacket packet = ClientboundBlockEntityDataPacket.create(blockEntity);
        for (ServerPlayer player : serverLevel.getChunkSource().chunkMap
                .getPlayers(new ChunkPos(pos), false)) {
            player.connection.send(packet);
        }
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
            // The input here is a raw UUID string; never store it as the profile
            // name, otherwise offline-mode name matching breaks for entries added
            // by UUID while the profile cache has no record (audit F-B1).
            return CompletableFuture.completedFuture(cached.isPresent()
                    ? cached : Optional.of(new GameProfile(id, "")));
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
        // The registrar's default MAIN-thread wrapper already puts this
        // callback on the client game thread.  The actual renderer is
        // installed from the physical-client event subscriber.
        beamEffectHandler.accept(payload);
    }
}
