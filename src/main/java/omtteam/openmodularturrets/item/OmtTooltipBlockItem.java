package omtteam.openmodularturrets.item;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** Block-item counterpart of {@link OmtTooltipItem}. */
public final class OmtTooltipBlockItem extends BlockItem {
    public OmtTooltipBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
            List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        OmtTooltips.append(this, tooltipComponents);
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}
