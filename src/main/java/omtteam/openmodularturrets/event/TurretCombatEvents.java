package omtteam.openmodularturrets.event;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.damage.TurretDamageSource;
import omtteam.openmodularturrets.config.ModServerConfig;

import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

@EventBusSubscriber(modid = OpenModularTurrets.MOD_ID)
public final class TurretCombatEvents {
    private TurretCombatEvents() {
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getSource() instanceof TurretDamageSource turretSource)) {
            return;
        }
        if (turretSource.context().suppressLoot()) {
            event.getDrops().clear();
            return;
        }
        int fakeDropsLevel = turretSource.context().fakeDropsLevel();
        if (!ModServerConfig.turretKillsDropLoot()
                && !(ModServerConfig.lootAddonsOverride() && fakeDropsLevel >= 0)) {
            event.getDrops().clear();
        }
    }
}
