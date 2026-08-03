package omtteam.openmodularturrets;

import com.mojang.logging.LogUtils;
import omtteam.openmodularturrets.registration.ModBlocks;
import omtteam.openmodularturrets.registration.ModBlockEntities;
import omtteam.openmodularturrets.registration.ModCapabilities;
import omtteam.openmodularturrets.registration.ModCreativeTabs;
import omtteam.openmodularturrets.registration.ModDataComponents;
import omtteam.openmodularturrets.registration.ModItems;
import omtteam.openmodularturrets.registration.ModEntities;
import omtteam.openmodularturrets.registration.ModSounds;
import omtteam.openmodularturrets.registration.ModMenus;
import omtteam.openmodularturrets.network.ModNetwork;
import omtteam.openmodularturrets.config.ModServerConfig;
import org.slf4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;

@Mod(OpenModularTurrets.MOD_ID)
public final class OpenModularTurrets {
    public static final String MOD_ID = "openmodularturrets";
    public static final Logger LOGGER = LogUtils.getLogger();

    public OpenModularTurrets(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, ModServerConfig.SPEC);
        ModDataComponents.COMPONENTS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModEntities.register(modEventBus);
        ModSounds.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        modEventBus.addListener(ModCapabilities::register);
        modEventBus.addListener(ModNetwork::register);
    }
}
