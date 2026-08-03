package omtteam.openmodularturrets.registration;

import omtteam.openmodularturrets.OpenModularTurrets;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OpenModularTurrets.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN =
            CREATIVE_MODE_TABS.register(
                    "main",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.openmodularturrets"))
                            .withTabsBefore(CreativeModeTabs.COMBAT)
                            .icon(() -> ModItems.AMMO_ROCKET.get().getDefaultInstance())
                            .displayItems((parameters, output) ->
                                    ModItems.ITEMS.getEntries().forEach(item -> output.accept(item.value())))
                            .build());

    private ModCreativeTabs() {
    }
}
