package omtteam.openmodularturrets.data;

import omtteam.openmodularturrets.damage.TurretAttackContext;

import net.minecraft.world.entity.LivingEntity;

/**
 * Narrow server-side context used while a volley is executed.
 *
 * <p>The implementation owns the authoritative Base state.  This interface
 * exposes only the values and mutations that combat execution already needs;
 * it is not a second state holder and must only be used on the server thread.</p>
 */
public interface TurretCombatContext {
    int damageAmpLevel();

    int accuracyUpgradeLevel();

    int scatterShotUpgradeLevel();

    TurretAttackContext attackContext();

    float projectileInaccuracy(TurretDefinition definition);

    boolean mayDamage(LivingEntity entity);

    void recordKill(LivingEntity target);
}
