package omtteam.openmodularturrets.compat.jade;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.blockentity.TurretHeadBlockEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

public enum TurretHeadJadeServerProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(OpenModularTurrets.MOD_ID, "turret_head");

    @Override
    public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
        if (accessor.getBlockEntity() instanceof TurretHeadBlockEntity turret) {
            tag.putString("Definition", turret.definition().id());
            tag.putInt("RequiredBaseTier", turret.definition().requiredBaseTier());
            tag.putInt("BaseRange", turret.definition().baseRange());
            tag.putFloat("Damage", turret.definition().damage());
            tag.putInt("EnergyCost", turret.definition().energyCost());
        }
    }

    @Override
    public ResourceLocation getUid() {
        return ID;
    }
}
