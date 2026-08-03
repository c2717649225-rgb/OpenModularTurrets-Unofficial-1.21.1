package omtteam.openmodularturrets.compat.jade;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.block.TurretBaseBlock;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.client.compat.jade.TurretBaseJadeClientProvider;
import omtteam.openmodularturrets.client.compat.jade.TurretHeadJadeClientProvider;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin(OpenModularTurrets.MOD_ID)
public class OmtJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(TurretBaseJadeServerProvider.INSTANCE, TurretBaseBlock.class);
        registration.registerBlockDataProvider(TurretHeadJadeServerProvider.INSTANCE, TurretHeadBlock.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(TurretBaseJadeClientProvider.INSTANCE, TurretBaseBlock.class);
        registration.registerBlockComponent(TurretHeadJadeClientProvider.INSTANCE, TurretHeadBlock.class);
    }
}
