package omtteam.openmodularturrets.item;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Standard OMT item with the legacy Shift-expanded information card. */
public class OmtTooltipItem extends Item {
    public OmtTooltipItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
            List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        OmtTooltips.append(this, tooltipComponents);
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}
