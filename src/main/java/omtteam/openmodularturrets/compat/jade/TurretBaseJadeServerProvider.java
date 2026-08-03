package omtteam.openmodularturrets.compat.jade;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.blockentity.TurretBaseBlockEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

public enum TurretBaseJadeServerProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, "turret_base");

    @Override
    public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof TurretBaseBlockEntity base) {
            tag.putBoolean("Active", base.active());
            tag.putInt("Energy", base.energy().getEnergyStored());
            tag.putInt("MaxEnergy", base.energy().getMaxEnergyStored());
            tag.putString("Owner", base.ownerName());
            tag.putInt("Kills", (int) Math.min(Integer.MAX_VALUE, base.kills()));
            tag.putInt("PlayerKills", (int) Math.min(Integer.MAX_VALUE, base.playerKills()));
        }
    }

    @Override
    public ResourceLocation getUid() {
        return ID;
    }
}
