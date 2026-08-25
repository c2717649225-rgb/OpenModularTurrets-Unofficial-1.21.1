package omtteam.openmodularturrets.turret.behavior;

import omtteam.openmodularturrets.data.TurretCombatContext;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretVisualRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Strategy for teleporting intrusive targets directly above the turret head cell.
 */
public final class TeleportVolleyStrategy implements VolleyStrategy {
    public static final TeleportVolleyStrategy INSTANCE = new TeleportVolleyStrategy();

    private TeleportVolleyStrategy() {
    }

    @Override
    public void execute(ServerLevel level, BlockPos headPos, LivingEntity target,
                        TurretDefinition definition, ItemStack consumedAmmo,
                        TurretCombatContext combatContext) {
        Vec3 destination = Vec3.atBottomCenterOf(headPos.above());
        target.teleportTo(destination.x, destination.y, destination.z);
        level.sendParticles(ParticleTypes.PORTAL,
                headPos.getX() + 0.5D, headPos.getY() + 0.5D,
                headPos.getZ() + 0.5D,
                TurretVisualRules.TELEPORT_BURST_PARTICLES,
                1.0D, 1.0D, 1.0D, 0.1D);
    }
}
