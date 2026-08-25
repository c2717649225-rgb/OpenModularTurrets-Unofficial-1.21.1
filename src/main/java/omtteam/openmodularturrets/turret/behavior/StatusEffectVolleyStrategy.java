package omtteam.openmodularturrets.turret.behavior;

import omtteam.openmodularturrets.data.TurretCombatContext;
import omtteam.openmodularturrets.data.TurretDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Parameterized strategy for applying potion/status debuffs to targets (e.g. Relativistic turret).
 */
public final class StatusEffectVolleyStrategy implements VolleyStrategy {
    private final List<EffectSpec> effectSpecs;

    public StatusEffectVolleyStrategy(List<EffectSpec> effectSpecs) {
        this.effectSpecs = List.copyOf(effectSpecs);
    }

    @Override
    public void execute(ServerLevel level, BlockPos headPos, LivingEntity target,
                        TurretDefinition definition, ItemStack consumedAmmo,
                        TurretCombatContext combatContext) {
        for (EffectSpec spec : effectSpecs) {
            target.addEffect(new MobEffectInstance(spec.effect(), spec.duration(), spec.amplifier()));
        }
    }

    public record EffectSpec(Holder<MobEffect> effect, int duration, int amplifier) {
    }
}
