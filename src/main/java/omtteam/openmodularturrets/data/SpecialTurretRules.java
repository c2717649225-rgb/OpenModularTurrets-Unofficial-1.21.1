package omtteam.openmodularturrets.data;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import omtteam.openmodularturrets.damage.ModDamageTypes;

public final class SpecialTurretRules {
    public static final int LASER_BEAM_COLOR = 0xFF1A00;
    public static final int RAIL_BEAM_COLOR = 0xFF8000;

    private SpecialTurretRules() {
    }

    public static float beamDamageMultiplier(TurretDefinition definition, int armor) {
        int safeArmor = Math.max(0, armor);
        return switch (definition) {
            case LASER -> (float) Math.clamp(1.6D - safeArmor / 20.0D, 0.1D, 1.6D);
            case RAIL_GUN -> (float) Math.clamp(0.6D + safeArmor / 20.0D, 0.6D, 2.1D);
            default -> 1.0F;
        };
    }

    public static int beamColor(TurretDefinition definition) {
        return definition == TurretDefinition.RAIL_GUN
                ? RAIL_BEAM_COLOR : LASER_BEAM_COLOR;
    }

    public static ResourceKey<DamageType> beamDamageType(TurretDefinition definition) {
        return definition == TurretDefinition.RAIL_GUN
                ? ModDamageTypes.TURRET_ARMOR_PIERCING
                : ModDamageTypes.TURRET_PROJECTILE;
    }

    public static boolean acceptsTarget(TurretDefinition definition, LivingEntity entity) {
        return definition != TurretDefinition.RELATIVISTIC
                || !entity.hasEffect(MobEffects.MOVEMENT_SLOWDOWN);
    }

    /**
     * Legacy special turrets paid the scatter energy multiplier but executed their
     * teleport or status effect only once. Projectile and beam turrets execute the
     * complete volley.
     */
    public static int shotExecutions(TurretDefinition definition, int projectileCount) {
        return switch (definition.shotKind()) {
            case TELEPORT, RELATIVISTIC -> 1;
            default -> Math.max(1, projectileCount);
        };
    }

    public static Vec3 rocketHomingVelocity(Vec3 projectilePosition, Vec3 targetEyePosition) {
        Vec3 direction = targetEyePosition.subtract(projectilePosition);
        return direction.lengthSqr() <= 1.0E-6D
                ? Vec3.ZERO : direction.normalize().scale(0.24D);
    }

    public static boolean railgunCanDestroyBlock(float hardness, boolean enabled) {
        return enabled && hardness < 200.0F;
    }
}
