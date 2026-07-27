---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-25
---
# Standard Few-Shot Skeletons (可复用骨架)

> [!IMPORTANT]
> **占位符自适应规则**：
> 下列骨架中的 `{{MOD_GROUP}}`、`{{MODID}}` 和 `{{MAIN_CLASS}}` 均为符号占位符。在将代码写入项目前，您**必须**先从 `gradle.properties` 和 `neoforge.mods.toml` 读取真实的包路径与 Mod ID，并将占位符替换为当前项目的真实命名空间，严禁机械化复制。
> *注：`references/` 目录下的所有 markdown 指南代码示例中的包名（如 `com.tutorial.tutorialmod`）一律视为示例，同样必须在写入前按当前项目进行替换。*
> **详细设计与 API 细节，请以对应的 references/*.md 单项专题指南为准。**

---

### 1. Block, Item, BlockEntity & Tab Registration
```java
package {{MOD_GROUP}}.registry;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks({{MAIN_CLASS}}.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems({{MAIN_CLASS}}.MODID);
    
    // Auto-registers BOTH Block and its BlockItem
    public static final DeferredBlock<Block> RUBY_BLOCK = BLOCKS.registerSimpleBlock("ruby_block", 
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(5.0f).sound(SoundType.METAL));
    public static final DeferredItem<BlockItem> RUBY_BLOCK_ITEM = ITEMS.registerSimpleBlockItem(RUBY_BLOCK);
}

// In main mod class (Creative Mode Tab injection listener)
private void addCreative(BuildCreativeModeTabContentsEvent event) {
    if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) event.accept(ModItems.RUBY.get());
}
```

---

### 2. Data Components & Attachments (Entity/Chunk custom data)
```java
package {{MOD_GROUP}}.registry;

public class ModData {
    // 1. Data Components (Stored on ItemStacks)
    public static final DeferredRegister.DataComponents COMPONENTS = 
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, {{MAIN_CLASS}}.MODID);
        
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> MANA = 
        COMPONENTS.registerComponentType("mana", builder -> builder.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.INT));

    // 2. Attachments (Stored on Entities, BlockEntities, or Chunks)
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = 
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, {{MAIN_CLASS}}.MODID);
        
    public static final Supplier<AttachmentType<Integer>> PLAYER_MANA = ATTACHMENTS.register("player_mana",
        () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).copyOnDeath().build());
        // Usage: player.getData(PLAYER_MANA.get()); player.setData(PLAYER_MANA.get(), 100);
}
```

---

### 3. Ticking BlockEntity with Capability & Save/Load (Machines)
```java
package {{MOD_GROUP}}.block.entity;

public class MyMachineBlockEntity extends BlockEntity {
    private final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override protected void onContentsChanged(int slot) { setChanged(); }
    };
    public MyMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MY_MACHINE.get(), pos, state);
    }
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Inventory", this.inventory.serializeNBT(registries)); // MUST pass registries
    }
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.inventory.deserializeNBT(registries, tag.getCompound("Inventory")); // MUST pass registries
    }
    public static void tick(Level level, BlockPos pos, BlockState state, MyMachineBlockEntity be) {
        if (level.isClientSide()) return;
        // Server tick logic
    }
}

// Capability Registration (Listen on MOD event bus in Main Class constructor)
// modEventBus.addListener(CapabilityRegistrar::registerCaps);
public class CapabilityRegistrar {
    public static void registerCaps(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, 
            ModBlockEntities.MY_MACHINE.get(), (be, side) -> be.inventory);
    }
}
```

---

### 4. RegistryFriendly Custom Network Payload
```java
package {{MOD_GROUP}}.network;

public record SyncDataPayload(ItemStack stack, int value) implements CustomPacketPayload {
    public static final Type<SyncDataPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath({{MAIN_CLASS}}.MODID, "sync"));
    
    // MUST use RegistryFriendlyByteBuf when transmitting ItemStack
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, SyncDataPayload> STREAM_CODEC = StreamCodec.composite(
        ItemStack.STREAM_CODEC, SyncDataPayload::stack,
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT, SyncDataPayload::value,
        SyncDataPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
```

---

### 5. Multiblock Controller (Event-Driven Multiblock Detection)
```java
// 物理禁止在 tick() 中轮询检测；仅在右键或状态改变时触发检测，零 Tick 开销
public class MultiblockControllerBlock extends Block {
    private static final BlockPos[] STRUCTURE_OFFSETS = new BlockPos[]{
        new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
        new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
    };

    public MultiblockControllerBlock(Properties properties) { super(properties); }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            boolean formed = checkStructure(level, pos);
            player.sendSystemMessage(Component.literal(formed ? "结构组装成功！" : "结构残缺！"));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    public boolean checkStructure(Level level, BlockPos controllerPos) {
        for (BlockPos offset : STRUCTURE_OFFSETS) {
            if (!level.getBlockState(controllerPos.offset(offset)).is(Blocks.STONE_BRICKS)) return false;
        }
        return true;
    }
}
```

---

### 6. Jigsaw Structure Worldgen Skeleton
```java
// 标准 Jigsaw 遗迹结构引入（从 data/modid/structure/ 加载 NBT）
public class MyJigsawStructure extends Structure {
    public static final MapCodec<MyJigsawStructure> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            settingsCodec(instance),
            StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(s -> s.startPool),
            ResourceLocation.CODEC.optionalFieldOf("start_jigsaw").forGetter(s -> s.startJigsaw),
            Codec.intRange(0, 30).fieldOf("size").forGetter(s -> s.maxDepth),
            HeightProvider.CODEC.fieldOf("start_height").forGetter(s -> s.startHeight),
            Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap").forGetter(s -> s.projectStartToHeightmap),
            Codec.intRange(1, 128).fieldOf("max_distance_from_center").forGetter(s -> s.maxDistanceFromCenter)
        ).apply(instance, MyJigsawStructure::new)
    );

    private final Holder<StructureTemplatePool> startPool;
    private final Optional<ResourceLocation> startJigsaw;
    private final int maxDepth;
    private final HeightProvider startHeight;
    private final Optional<Heightmap.Types> projectStartToHeightmap;
    private final int maxDistanceFromCenter;

    public MyJigsawStructure(StructureSettings settings, Holder<StructureTemplatePool> startPool,
                             Optional<ResourceLocation> startJigsaw, int maxDepth,
                             HeightProvider startHeight, Optional<Heightmap.Types> projectStartToHeightmap,
                             int maxDistanceFromCenter) {
        super(settings);
        this.startPool = startPool;
        this.startJigsaw = startJigsaw;
        this.maxDepth = maxDepth;
        this.startHeight = startHeight;
        this.projectStartToHeightmap = projectStartToHeightmap;
        this.maxDistanceFromCenter = maxDistanceFromCenter;
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        int minY = this.startHeight.sample(context.random(), new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor()));
        BlockPos blockpos = new BlockPos(context.chunkPos().getMinBlockX(), minY, context.chunkPos().getMinBlockZ());
        return JigsawPlacement.addPieces(context, this.startPool, this.startJigsaw, this.maxDepth, blockpos, false, this.projectStartToHeightmap, this.maxDistanceFromCenter);
    }

    @Override
    public StructureType<?> type() { return ModStructures.MY_JIGSAW.get(); }
}
```

