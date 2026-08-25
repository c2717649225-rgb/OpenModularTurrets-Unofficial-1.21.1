package omtteam.openmodularturrets.turret.behavior;

import omtteam.openmodularturrets.damage.TurretAttackContext;
import omtteam.openmodularturrets.data.TurretCombatContext;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.entity.ProjectileKind;
import omtteam.openmodularturrets.entity.TurretProjectileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Parameterized strategy for shooting physical entity projectiles (bullets, rockets, grenades, potatoes, etc.).
 */
public final class ProjectileVolleyStrategy implements VolleyStrategy {
    private final ProjectileKind projectileKind;
    private final float baseSpeed;

    public ProjectileVolleyStrategy(ProjectileKind projectileKind, float baseSpeed) {
        this.projectileKind = projectileKind;
        this.baseSpeed = baseSpeed;
    }

    public ProjectileKind projectileKind() {
        return projectileKind;
    }

    public float baseSpeed() {
        return baseSpeed;
    }

    @Override
    public void execute(ServerLevel level, BlockPos headPos, LivingEntity target,
                        TurretDefinition definition, ItemStack consumedAmmo,
                        TurretCombatContext combatContext) {
        TurretAttackContext attackContext = combatContext.attackContext();
        TurretProjectileEntity projectile = TurretProjectileEntity.create(level, projectileKind,
                attackContext.sourceBasePos(), target, definition,
                combatContext.damageAmpLevel(), attackContext, consumedAmmo);

        Vec3 targetPosition = target.getEyePosition();
        Vec3 origin = muzzleOrigin(headPos, targetPosition);
        Vec3 delta = targetPosition.subtract(origin);
        projectile.setPos(origin.x, origin.y, origin.z);
        projectile.shoot(delta.x, delta.y, delta.z, baseSpeed,
                combatContext.projectileInaccuracy(definition));
        level.addFreshEntity(projectile);
    }

    private static Vec3 muzzleOrigin(BlockPos headPos, Vec3 targetPosition) {
        Vec3 center = Vec3.atCenterOf(headPos);
        Vec3 direction = targetPosition.subtract(center);
        if (direction.lengthSqr() < 1.0E-7D) {
            return center;
        }
        return center.add(direction.normalize().scale(1.0D));
    }
}
