package omtteam.openmodularturrets.client;

import java.util.ArrayDeque;
import java.util.Deque;

import omtteam.openmodularturrets.entity.ProjectileKind;
import omtteam.openmodularturrets.entity.TurretProjectileEntity;
import omtteam.openmodularturrets.data.TurretVisualRules;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only visual effects for projectile states already synchronized by vanilla entity data.
 */
public final class ClientProjectileEffects {
    private static final Deque<TurretProjectileEntity> PROJECTILES = new ArrayDeque<>();
    private static ClientLevel trackedLevel;

    private ClientProjectileEffects() {
    }

    static void track(ClientLevel level, TurretProjectileEntity projectile) {
        switchLevel(level);
        if (!PROJECTILES.contains(projectile)) {
            PROJECTILES.addLast(projectile);
        }
    }

    static void untrack(TurretProjectileEntity projectile) {
        PROJECTILES.remove(projectile);
    }

    static void clear() {
        PROJECTILES.clear();
        trackedLevel = null;
    }

    /**
     * Call once from the post client-level tick while a {@link ClientLevel} is present.
     */
    public static void tickClientLevel(ClientLevel level) {
        switchLevel(level);
        int remainingParticles = TurretVisualRules.MAX_CLIENT_PROJECTILE_PARTICLES_PER_TICK;
        int toVisit = PROJECTILES.size();
        while (toVisit-- > 0 && !PROJECTILES.isEmpty()) {
            TurretProjectileEntity projectile = PROJECTILES.removeFirst();
            if (projectile.level() != level || projectile.isRemoved() || !projectile.isAlive()) {
                continue;
            }
            // Rotate the queue so a particle budget cannot permanently favor
            // projectiles that happened to join the level first.
            PROJECTILES.addLast(projectile);
            remainingParticles = spawnEffects(level, projectile, remainingParticles);
            if (remainingParticles == 0) {
                break;
            }
        }
    }

    private static void switchLevel(ClientLevel level) {
        if (trackedLevel != level) {
            PROJECTILES.clear();
            trackedLevel = level;
        }
    }

    private static int spawnEffects(ClientLevel level, TurretProjectileEntity projectile,
            int remainingParticles) {
        if (remainingParticles <= 0) {
            return 0;
        }
        Vec3 motion = projectile.getDeltaMovement();
        Vec3 wake = projectile.position().subtract(motion.scale(0.25D));
        if (projectile.isInWater()) {
            int count = Math.min(4, remainingParticles);
            for (int i = 0; i < count; i++) {
                level.addParticle(ParticleTypes.BUBBLE,
                        wake.x, wake.y, wake.z, motion.x, motion.y, motion.z);
            }
            return remainingParticles - count;
        }

        if (projectile.projectileKind() == ProjectileKind.ROCKET) {
            int count = Math.min(TurretVisualRules.ROCKET_TRAIL_PARTICLES,
                    remainingParticles);
            for (int i = 0; i < count; i++) {
                level.addParticle(ParticleTypes.SMOKE,
                        projectile.getX() + level.random.nextGaussian() * 0.1D,
                        projectile.getY() + level.random.nextGaussian() * 0.1D,
                        projectile.getZ() + level.random.nextGaussian() * 0.1D,
                        0.0D, 0.0D, 0.0D);
            }
            return remainingParticles - count;
        }
        return remainingParticles;
    }
}
