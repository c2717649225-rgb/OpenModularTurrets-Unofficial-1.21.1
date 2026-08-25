package omtteam.openmodularturrets.blockentity.base;

import omtteam.openmodularturrets.data.AccessLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Narrow context interface for turret warning triggers.
 */
public interface WarningContext {
    int configuredRange();

    BlockPos worldPosition();

    Level level();

    AccessLevel accessFor(Player player);
}
