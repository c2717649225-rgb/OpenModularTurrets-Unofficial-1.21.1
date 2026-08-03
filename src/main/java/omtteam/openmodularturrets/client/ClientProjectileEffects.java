package omtteam.openmodularturrets.client;

import omtteam.openmodularturrets.entity.ProjectileKind;
import omtteam.openmodularturrets.entity.TurretProjectileEntity;
import omtteam.openmodularturrets.data.TurretVisualRules;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only visual effects for projectile states already synchronized by vanilla entity data.
 */
public final class ClientProjectileEffects {
    private ClientProjectileEffects() {
    }

    /**
     * Call once from the post client-level tick while a {@link ClientLevel} is present.
     */
    public static void tickClientLevel(ClientLevel level) {
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof TurretProjectileEntity projectile && projectile.isAlive()) {
                spawnEffects(level, projectile);
            }
        }
    }

    private static void spawnEffects(ClientLevel level, TurretProjectileEntity projectile) {
        Vec3 motion = projectile.getDeltaMovement();
        Vec3 wake = projectile.position().subtract(motion.scale(0.25D));
        if (projectile.isInWater()) {
            for (int i = 0; i < 4; i++) {
                level.addParticle(ParticleTypes.BUBBLE,
                        wake.x, wake.y, wake.z, motion.x, motion.y, motion.z);
            }
            return;
        }

        if (projectile.projectileKind() == ProjectileKind.ROCKET) {
            for (int i = 0; i < TurretVisualRules.ROCKET_TRAIL_PARTICLES; i++) {
                level.addParticle(ParticleTypes.SMOKE,
                        projectile.getX() + level.random.nextGaussian() * 0.1D,
                        projectile.getY() + level.random.nextGaussian() * 0.1D,
                        projectile.getZ() + level.random.nextGaussian() * 0.1D,
                        0.0D, 0.0D, 0.0D);
            }
        }
    }
}
