package omtteam.openmodularturrets.datagen;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.registration.ModBlocks;
import omtteam.openmodularturrets.registration.ModItems;

import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public final class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, OpenModularTurrets.MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        ModItems.REGULAR_ITEMS.forEach(item -> {
            String id = item.getId().getPath();
            String texture = id.equals("ammo_fake_disposable") ? "ammo_bullet" : id;
            withExistingParent(id, mcLoc("item/generated"))
                    .texture("layer0", modLoc("item/" + texture));
        });
        // Turret heads use the client BER-equivalent item renderer so their
        // inventory icon matches the placed model instead of a white cube.
        ModBlocks.ALL.forEach(block -> {
            if (block.get() instanceof TurretHeadBlock) {
                getBuilder(block.getId().getPath())
                        .parent(new ModelFile.UncheckedModelFile("builtin/entity"));
            }
        });
    }
}
