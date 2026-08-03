package omtteam.openmodularturrets.client.compat.jade;

import omtteam.openmodularturrets.compat.jade.TurretHeadJadeServerProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public enum TurretHeadJadeClientProvider implements IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!accessor.getServerData().contains("RequiredBaseTier")) {
            return;
        }
        int tier = accessor.getServerData().getInt("RequiredBaseTier");
        int range = accessor.getServerData().getInt("BaseRange");
        float damage = accessor.getServerData().getFloat("Damage");
        int energyCost = accessor.getServerData().getInt("EnergyCost");

        tooltip.add(Component.translatable("tooltip.openmodularturrets.tier", tier)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("gui.openmodularturrets.range", range)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.openmodularturrets.damage_stat", String.format("%.1f", damage / 2.0F))
                .withStyle(ChatFormatting.WHITE));
        tooltip.add(Component.translatable("tooltip.openmodularturrets.energy_per_shot", energyCost)
                .withStyle(ChatFormatting.WHITE));
    }

    @Override
    public ResourceLocation getUid() {
        return TurretHeadJadeServerProvider.ID;
    }
}
