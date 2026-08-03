package omtteam.openmodularturrets.registration;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.menu.InventoryExpanderMenu;
import omtteam.openmodularturrets.menu.TurretBaseMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, OpenModularTurrets.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<TurretBaseMenu>> TURRET_BASE_MENU =
            MENUS.register("turret_base_menu", () -> IMenuTypeExtension.create(TurretBaseMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<InventoryExpanderMenu>>
            INVENTORY_EXPANDER_MENU = MENUS.register("inventory_expander_menu",
                    () -> IMenuTypeExtension.create(InventoryExpanderMenu::new));

    private ModMenus() {
    }
}
