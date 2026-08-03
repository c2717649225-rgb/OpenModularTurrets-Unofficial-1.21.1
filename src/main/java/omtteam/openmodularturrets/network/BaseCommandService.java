package omtteam.openmodularturrets.network;

import java.util.Optional;

import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;
import omtteam.openmodularturrets.data.AccessLevel;
import omtteam.openmodularturrets.data.BaseMode;
import omtteam.openmodularturrets.menu.TurretBaseMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shared server-authoritative validation and application path used by payload handlers and tests.
 */
public final class BaseCommandService {
    private BaseCommandService() {
    }

    public static Optional<TurretBaseMenu> validateOpenBase(ServerPlayer player,
            int containerId, BlockPos pos, AccessLevel requiredAccess) {
        if (!(player.containerMenu instanceof TurretBaseMenu menu)
                || menu.containerId != containerId
                || !menu.base().getBlockPos().equals(pos)
                || !menu.stillValid(player)
                || player.level() != menu.base().getLevel()
                || player.distanceToSqr(pos.getCenter()) > 64.0D) {
            return Optional.empty();
        }
        ServerLevel level = player.serverLevel();
        TurretBaseBlockEntity base = menu.base();
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || base.isRemoved()
                || level.getBlockEntity(pos) != base
                || !base.accessFor(player).allows(requiredAccess)) {
            return Optional.empty();
        }
        return Optional.of(menu);
    }

    public static boolean apply(ServerPlayer player, BaseCommandPayload payload) {
        BaseCommand command = BaseCommand.byId(payload.commandId());
        if (command == null) {
            return false;
        }
        AccessLevel required = switch (command) {
            case SET_RANGE, SET_TARGET_FLAGS, SET_MULTI_TARGET,
                    ADJUST_RANGE, TOGGLE_MULTI_TARGET, TOGGLE_TARGET_FLAG ->
                    AccessLevel.USE;
            case SET_ACTIVE, SET_MODE, SET_TRUST_SCOPE, DROP_TURRETS, DROP_BASE,
                    SET_CAMOUFLAGE_LIGHT, SET_CAMOUFLAGE_OPACITY, CLEAR_CAMOUFLAGE,
                    CYCLE_MODE, ADJUST_CAMOUFLAGE_LIGHT,
                    ADJUST_CAMOUFLAGE_OPACITY ->
                    AccessLevel.ADMIN;
        };
        Optional<TurretBaseMenu> validated = validateOpenBase(
                player, payload.containerId(), payload.pos(), required);
        if (validated.isEmpty()) {
            return false;
        }
        TurretBaseBlockEntity base = validated.orElseThrow().base();
        return switch (command) {
            case SET_ACTIVE -> {
                if (payload.value() != 0 && payload.value() != 1) {
                    yield false;
                }
                base.setActive(payload.value() != 0);
                yield true;
            }
            case SET_RANGE -> {
                int maximum = base.maximumRange();
                if (payload.value() < 1 || payload.value() > maximum) {
                    yield false;
                }
                base.setRange(payload.value());
                yield true;
            }
            case ADJUST_RANGE -> {
                if (payload.value() != -1 && payload.value() != 1) {
                    yield false;
                }
                int adjusted = base.range() + payload.value();
                if (adjusted < 1 || adjusted > base.maximumRange()) {
                    yield false;
                }
                base.setRange(adjusted);
                yield true;
            }
            case SET_TARGET_FLAGS -> {
                if ((payload.value() & ~7) != 0) {
                    yield false;
                }
                base.setTargetFlags(
                        (payload.value() & 1) != 0,
                        (payload.value() & 2) != 0,
                        (payload.value() & 4) != 0);
                yield true;
            }
            case SET_MULTI_TARGET -> {
                if (payload.value() != 0 && payload.value() != 1) {
                    yield false;
                }
                base.setMultiTargeting(payload.value() != 0);
                yield true;
            }
            case TOGGLE_MULTI_TARGET -> {
                if (payload.value() != 0) {
                    yield false;
                }
                base.setMultiTargeting(!base.multiTargeting());
                yield true;
            }
            case TOGGLE_TARGET_FLAG -> {
                if (payload.value() != 1 && payload.value() != 2
                        && payload.value() != 4) {
                    yield false;
                }
                base.setTargetFlags(
                        base.attackHostile() ^ (payload.value() == 1),
                        base.attackNeutral() ^ (payload.value() == 2),
                        base.attackPlayers() ^ (payload.value() == 4));
                yield true;
            }
            case SET_MODE -> {
                BaseMode mode = BaseMode.byId(payload.value());
                if (mode == null) {
                    yield false;
                }
                base.setMode(mode);
                yield true;
            }
            case CYCLE_MODE -> {
                if (payload.value() != 0) {
                    yield false;
                }
                base.setMode(base.mode().next());
                yield true;
            }
            case SET_TRUST_SCOPE -> {
                if (payload.value() != 0 && payload.value() != 1) {
                    yield false;
                }
                // The selected scope defines who can administer this base. Restricting
                // the switch to the owner prevents a local ADMIN from switching the base
                // into a global list that grants that player no access.
                yield base.isOwner(player)
                        && base.setUseGlobalTrust(player, payload.value() != 0);
            }
            case DROP_TURRETS -> base.dropAdjacentTurrets(player) > 0;
            case DROP_BASE -> base.dropBase(player);
            case SET_CAMOUFLAGE_LIGHT ->
                    base.setCamouflageLightValue(player, payload.value());
            case SET_CAMOUFLAGE_OPACITY ->
                    base.setCamouflageLightOpacity(player, payload.value());
            case ADJUST_CAMOUFLAGE_LIGHT ->
                    isUnitStep(payload.value())
                            && base.setCamouflageLightValue(player,
                                    base.camouflageLightValue() + payload.value());
            case ADJUST_CAMOUFLAGE_OPACITY ->
                    isUnitStep(payload.value())
                            && base.setCamouflageLightOpacity(player,
                                    base.camouflageLightOpacity() + payload.value());
            case CLEAR_CAMOUFLAGE -> payload.value() == 0
                    && base.clearCamouflage(player);
        };
    }

    private static boolean isUnitStep(int value) {
        return value == -1 || value == 1;
    }
}
