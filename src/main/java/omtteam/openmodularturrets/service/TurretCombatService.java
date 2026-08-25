package omtteam.openmodularturrets.service;

import omtteam.openmodularturrets.data.SpecialTurretRules;
import omtteam.openmodularturrets.data.TurretCombatContext;
import omtteam.openmodularturrets.data.TurretDefinition;
import omtteam.openmodularturrets.data.TurretVolleyResourcesView;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Server-authoritative volley execution orchestrator.
 * Delegates the physical shot execution to the polymorphic VolleyStrategy
 * defined by the turret behavior.
 */
public final class TurretCombatService {
    private TurretCombatService() {
    }

    public static CombatResult executeVolley(ServerLevel level, BlockPos headPos,
            LivingEntity target, TurretDefinition definition,
            TurretVolleyResourcesView resources,
            TurretCombatContext combatContext) {
        boolean aliveBefore = target.isAlive();
        int executions = SpecialTurretRules.shotExecutions(
                definition, resources.projectileCount());
        for (int shot = 0; shot < executions; shot++) {
            definition.volleyStrategy().execute(level, headPos, target,
                    definition, resources.ammo(), combatContext);
        }
        return new CombatResult(aliveBefore, target.isAlive(), executions);
    }

    public record CombatResult(boolean targetAliveBefore, boolean targetAliveAfter,
            int executions) {
    }
}
