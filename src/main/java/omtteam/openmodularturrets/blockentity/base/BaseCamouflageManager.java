package omtteam.openmodularturrets.blockentity.base;

import java.util.Optional;
import javax.annotation.Nullable;

import omtteam.openmodularturrets.block.TurretBaseBlock;
import omtteam.openmodularturrets.config.ModServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * High-cohesion sub-component managing base camouflage blocks, light opacity, and emission levels.
 */
public final class BaseCamouflageManager {
    @Nullable
    private BlockState camouflageState;
    private int camouflageLightValue;
    private int camouflageLightOpacity = 15;

    public BaseCamouflageManager() {
    }

    public Optional<BlockState> camouflageState() {
        return Optional.ofNullable(camouflageState);
    }

    public int lightValue() {
        return camouflageLightValue;
    }

    public int lightOpacity() {
        return camouflageLightOpacity;
    }

    public boolean setCamouflage(BlockState state, Level level, BlockPos pos) {
        if (!ModServerConfig.allowBaseCamouflage() || !isValidCamouflage(state, level, pos)) {
            return false;
        }
        if (state.equals(camouflageState)) {
            return false;
        }
        camouflageState = state;
        return true;
    }

    public boolean clearCamouflage() {
        if (camouflageState == null) {
            return false;
        }
        camouflageState = null;
        return true;
    }

    public boolean setLightValue(int value) {
        if (!ModServerConfig.allowBaseCamouflage() || value < 0 || value > 15 || camouflageLightValue == value) {
            return false;
        }
        camouflageLightValue = value;
        return true;
    }

    public boolean setLightOpacity(int value) {
        if (!ModServerConfig.allowBaseCamouflage() || value < 0 || value > 15 || camouflageLightOpacity == value) {
            return false;
        }
        camouflageLightOpacity = value;
        return true;
    }

    public boolean isValidCamouflage(BlockState state, Level level, BlockPos pos) {
        return !state.isAir()
                && state.getRenderShape() == RenderShape.MODEL
                && !state.hasBlockEntity()
                && !(state.getBlock() instanceof TurretBaseBlock)
                && Block.isShapeFullBlock(state.getCollisionShape(level, pos));
    }

    public void saveNbt(CompoundTag tag) {
        if (camouflageState != null) {
            tag.put("camouflage_state", NbtUtils.writeBlockState(camouflageState));
        }
        tag.putInt("camouflage_light_value", camouflageLightValue);
        tag.putInt("camouflage_light_opacity", camouflageLightOpacity);
    }

    public void loadNbt(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains("camouflage_state", Tag.TAG_COMPOUND)) {
            BlockState loaded = NbtUtils.readBlockState(
                    registries.lookupOrThrow(Registries.BLOCK),
                    tag.getCompound("camouflage_state"));
            if (!loaded.isAir()
                    && loaded.getRenderShape() == RenderShape.MODEL
                    && !loaded.hasBlockEntity()
                    && !(loaded.getBlock() instanceof TurretBaseBlock)) {
                camouflageState = loaded;
            }
        }
        if (tag.contains("camouflage_light_value", Tag.TAG_INT)) {
            camouflageLightValue = Math.clamp(tag.getInt("camouflage_light_value"), 0, 15);
        }
        if (tag.contains("camouflage_light_opacity", Tag.TAG_INT)) {
            camouflageLightOpacity = Math.clamp(tag.getInt("camouflage_light_opacity"), 0, 15);
        }
    }
}
