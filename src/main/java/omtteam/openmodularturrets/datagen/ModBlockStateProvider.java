package omtteam.openmodularturrets.datagen;

import omtteam.openmodularturrets.OpenModularTurrets;
import omtteam.openmodularturrets.registration.ModBlocks;
import omtteam.openmodularturrets.block.BaseAttachmentBlock;
import omtteam.openmodularturrets.block.InventoryExpanderBlock;
import omtteam.openmodularturrets.block.PowerExpanderBlock;
import omtteam.openmodularturrets.block.TurretHeadBlock;
import omtteam.openmodularturrets.block.ManualChargerBlock;

import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelBuilder.FaceRotation;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredBlock;

public final class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, OpenModularTurrets.MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        ModBlocks.ALL.forEach(block -> {
            String id = block.getId().getPath();
            if (block.get() instanceof InventoryExpanderBlock
                    || block.get() instanceof PowerExpanderBlock
                    || block.get() instanceof BaseAttachmentBlock) {
                registerAttachment(block.get(), id);
                return;
            }
            if (block.get() instanceof ManualChargerBlock charger) {
                ModelFile model = models().getBuilder(id)
                        .parent(new ModelFile.UncheckedModelFile(mcLoc("builtin/entity")))
                        .texture("particle", modLoc("block/lever_block"));
                getVariantBuilder(charger).forAllStates(state ->
                        ConfiguredModel.builder().modelFile(model).build());
                simpleBlockItem(charger, model);
                return;
            }
            ModelFile model = models().cubeAll(id, textureFor(block));
            if (block.get() instanceof TurretHeadBlock turret) {
                getVariantBuilder(turret).forAllStates(state ->
                        ConfiguredModel.builder().modelFile(model).build());
            } else {
                simpleBlockWithItem(block.get(), model);
            }
        });
    }

    private void registerAttachment(Block block, String id) {
        ResourceLocation side = modLoc("block/" + id + "_side");
        ResourceLocation top = modLoc("block/" + id + "_top");
        ModelFile model = models().withExistingParent(id, mcLoc("block/block"))
                .renderType("minecraft:cutout")
                .texture("particle", side)
                .texture("side", side)
                .texture("top", top)
                .element()
                    .from(2.0F, 2.0F, 0.0F)
                    .to(14.0F, 14.0F, 6.0F)
                    // UVs intentionally start at (0,0): the side/top textures
                    // are authored with their visible content in that region
                    // (the side texture is fully transparent below y=26, so the
                    // 0..12 v-range must not be shifted toward the element).
                    .face(Direction.DOWN).uvs(0, 0, 6, 12).rotation(FaceRotation.CLOCKWISE_90).texture("#side").end()
                    .face(Direction.UP).uvs(0, 0, 6, 12).rotation(FaceRotation.COUNTERCLOCKWISE_90).texture("#side").end()
                    .face(Direction.NORTH).uvs(0, 0, 12, 12).texture("#top").end()
                    .face(Direction.SOUTH).uvs(0, 0, 12, 12).texture("#top").end()
                    .face(Direction.WEST).uvs(0, 0, 6, 12).rotation(FaceRotation.UPSIDE_DOWN).texture("#side").end()
                    .face(Direction.EAST).uvs(0, 0, 6, 12).texture("#side").end()
                .end();
        getVariantBuilder(block).forAllStates(state -> {
            Direction facing = state.getValue(BaseAttachmentBlock.FACING);
            // Minecraft applies blockstate rotations as ROT_90_X_NEG /
            // ROT_90_Y_NEG (i.e. clockwise when viewed from the positive axis:
            // BlockModelRotation uses rotateYXZ(-y, -x, 0)).  The values below
            // therefore map each facing so the rendered 12x12x6 plate lands on
            // the same half of the cell as expanderShape()'s collision box.
            // A clockwise/counterclockwise mix here shifts the model along the
            // face normal (east/west/up/down all mismatch; north/south are
            // unaffected because 0/180 deg are direction-independent).
            int rotationX = switch (facing) {
                case UP -> 270;
                case DOWN -> 90;
                default -> 0;
            };
            int rotationY = switch (facing) {
                case EAST -> 90;
                case SOUTH -> 180;
                case WEST -> 270;
                default -> 0;
            };
            return ConfiguredModel.builder()
                    .modelFile(model)
                    .rotationX(rotationX)
                    .rotationY(rotationY)
                    .build();
        });
        simpleBlockItem(block, model);
    }

    private ResourceLocation textureFor(DeferredBlock<?> block) {
        String id = block.getId().getPath();
        return switch (id) {
            case "disposable_item_turret" -> modLoc("block/dispose_item_turret");
            case "plasma_turret" -> modLoc("block/grenade_turret");
            default -> {
                if (id.startsWith("expander_inv_") || id.startsWith("expander_power_")) {
                    yield modLoc("block/" + id + "_side");
                }
                yield modLoc("block/" + id);
            }
        };
    }
}
