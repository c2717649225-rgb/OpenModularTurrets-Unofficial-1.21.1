package omtteam.openmodularturrets.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Explicit server-world queries required by target selection.
 *
 * <p>This is deliberately separate from the scalar rule inputs supplied by
 * the caller: these answers depend on live world and permission state and are
 * not snapshot values.  The Base remains their owner; the targeting service
 * only consumes the narrow query surface.</p>
 */
public interface TurretTargetingWorldQueries {
    boolean isTargetClaimedBySibling(BlockPos requestingHead, LivingEntity entity);

    boolean mayDamage(LivingEntity entity);
}
