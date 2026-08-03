package omtteam.openmodularturrets.client.compat.jade;

import omtteam.openmodularturrets.compat.jade.TurretBaseJadeServerProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public enum TurretBaseJadeClientProvider implements IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!accessor.getServerData().contains("Active")) {
            return;
        }
        boolean active = accessor.getServerData().getBoolean("Active");
        int energy = accessor.getServerData().getInt("Energy");
        int maxEnergy = accessor.getServerData().getInt("MaxEnergy");
        String owner = accessor.getServerData().getString("Owner");
        int kills = accessor.getServerData().getInt("Kills");

        tooltip.add(Component.translatable("gui.openmodularturrets.active_state",
                active ? Component.translatable("gui.openmodularturrets.yes").withStyle(ChatFormatting.GREEN)
                        : Component.translatable("gui.openmodularturrets.no").withStyle(ChatFormatting.RED))
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.translatable("gui.openmodularturrets.energy", energy, maxEnergy)
                .withStyle(ChatFormatting.AQUA));

        if (!owner.isEmpty()) {
            tooltip.add(Component.translatable("gui.openmodularturrets.owner", owner)
                    .withStyle(ChatFormatting.GRAY));
        }

        tooltip.add(Component.translatable("gui.openmodularturrets.kills", kills)
                .withStyle(ChatFormatting.GRAY));
    }

    @Override
    public ResourceLocation getUid() {
        return TurretBaseJadeServerProvider.ID;
    }
}
