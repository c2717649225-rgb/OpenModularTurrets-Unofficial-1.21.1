package omtteam.openmodularturrets.turret.behavior;

import omtteam.openmodularturrets.data.TurretCombatContext;
import omtteam.openmodularturrets.data.TurretDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Parameterized, stateless volley execution strategy for a turret type.
 * All dynamic parameters (damage, config values, accuracy) are passed through
 * context at execution time, ensuring full thread safety and config hot-reload support.
 */
@FunctionalInterface
public interface VolleyStrategy {
    void execute(ServerLevel level, BlockPos headPos, LivingEntity target,
                 TurretDefinition definition, ItemStack consumedAmmo,
                 TurretCombatContext combatContext);
}
