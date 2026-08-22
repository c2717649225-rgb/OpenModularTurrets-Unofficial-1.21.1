package omtteam.openmodularturrets.data;

import net.minecraft.world.item.ItemStack;

/**
 * Result view of one Base resource reservation.
 *
 * <p>The concrete reservation record stays owned by the Base for source/API
 * compatibility; combat execution does not need to know that owner type.</p>
 */
public interface TurretVolleyResourcesView {
    ItemStack ammo();

    int projectileCount();
}
