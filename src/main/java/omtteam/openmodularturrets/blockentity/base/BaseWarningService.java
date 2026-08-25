package omtteam.openmodularturrets.blockentity.base;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import omtteam.openmodularturrets.config.ModServerConfig;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.registration.ModSounds;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * High-cohesion sub-component managing intruder warning messages, sounds, and cooldown throttles.
 */
public final class BaseWarningService {
    public static final long WARNING_COOLDOWN = 12_000L;
    public static final int MAX_WARNING_COOLDOWNS = 128;
    private final Map<UUID, Long> warningCooldowns = new HashMap<>();

    public BaseWarningService() {
    }

    public void warnNearbyPlayers(WarningContext ctx) {
        if (!(ctx.level() instanceof ServerLevel serverLevel)
                || (!ModServerConfig.warningMessage() && !ModServerConfig.warningSound())) {
            return;
        }
        long now = serverLevel.getGameTime();
        warningCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        int warningRange = ctx.configuredRange() + ModServerConfig.warningDistance();
        BlockPos pos = ctx.worldPosition();
        AABB area = new AABB(pos).inflate(warningRange);
        for (Player player : serverLevel.getEntitiesOfClass(Player.class, area)) {
            if (ctx.accessFor(player).allows(AccessLevel.USE)) {
                continue;
            }
            if (warningCooldowns.containsKey(player.getUUID())) {
                continue;
            }
            if (warningCooldowns.size() >= MAX_WARNING_COOLDOWNS) {
                UUID oldest = warningCooldowns.entrySet().stream()
                        .min(Comparator.comparingLong(Map.Entry::getValue))
                        .map(Map.Entry::getKey)
                        .orElse(null);
                if (oldest != null) {
                    warningCooldowns.remove(oldest);
                }
            }
            if (ModServerConfig.warningSound()) {
                serverLevel.playSound(null, pos, ModSounds.WARNING.value(),
                        SoundSource.BLOCKS, ModServerConfig.turretSoundVolume(), 1.0F);
            }
            if (ModServerConfig.warningMessage()) {
                player.displayClientMessage(
                        Component.translatable("message.openmodularturrets.target_warning"), true);
            }
            warningCooldowns.put(player.getUUID(), now + WARNING_COOLDOWN);
        }
    }
}
