package omtteam.openmodularturrets.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Shared muzzle geometry used by both target-selection line-of-sight checks
 * and volley execution so rays and projectiles always leave the head from the
 * same point.
 */
public final class TurretGeometryRules {
    /**
     * Clearance from the head-cell center so spawned projectiles and hitscan
     * rays do not start inside their own turret block.
     */
    private static final double MUZZLE_CLEARANCE = 1.0D;

    private TurretGeometryRules() {
    }

    public static Vec3 muzzleOrigin(BlockPos headPos, Vec3 targetPosition) {
        Vec3 center = Vec3.atCenterOf(headPos);
        Vec3 direction = targetPosition.subtract(center);
        if (direction.lengthSqr() < 1.0E-7D) {
            return center;
        }
        return center.add(direction.normalize().scale(MUZZLE_CLEARANCE));
    }
}
