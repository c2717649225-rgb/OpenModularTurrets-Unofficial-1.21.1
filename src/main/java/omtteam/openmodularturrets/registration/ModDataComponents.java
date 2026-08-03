package omtteam.openmodularturrets.registration;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.data.MemoryCardProfile;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, OpenModularTurrets.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<MemoryCardProfile>>
            MEMORY_CARD_PROFILE = COMPONENTS.register("memory_card_profile",
                    () -> DataComponentType.<MemoryCardProfile>builder()
                            .persistent(MemoryCardProfile.CODEC)
                            .networkSynchronized(MemoryCardProfile.STREAM_CODEC)
                            .build());

    private ModDataComponents() {
    }
}
