---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-28
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
    
    // registerSimpleBlock registers only the Block.
    public static final DeferredBlock<Block> RUBY_BLOCK = BLOCKS.registerSimpleBlock("ruby_block", 
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(5.0f).sound(SoundType.METAL));
    // Register the matching BlockItem separately.
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

### 5. Minimal On-Demand Structure Check (Not a Complete Multiblock System)

> [!NOTE]
> 这个示例只在玩家右键时检查四个固定偏移，适合作为最小验证探针。它**不会**持久化 formed 状态、处理旋转、自动响应邻块变化、强制加载区块、同步客户端或提供控制器 BlockEntity。正式多方块系统应按玩法合同补齐这些行为并用 GameTest 覆盖；需要自动失效时可从 `neighborChanged` / `onPlace` / `onRemove` 等状态变化入口触发并做去抖，不要无条件在每 Tick 全量扫描。

```java
// Minimal right-click validation probe; this is not a formed-state controller.
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

> [!TIP]
> 只需要原版 Jigsaw 行为时，优先在数据包中直接使用 `minecraft:jigsaw`，不必复制这个 Java 子类。下面的骨架仅适用于确实需要自定义 `StructureType` / CODEC 的场景；结构类型注册、template pool JSON、structure set JSON 与 NBT 模板仍需分别提供。

```java
// Verified against Minecraft 1.21.1 / NeoForge 21.1.234 sources.
// Templates are loaded from data/<modid>/structure/*.nbt by the template pools.
public class MyJigsawStructure extends Structure {
    private static final DimensionPadding DEFAULT_DIMENSION_PADDING = DimensionPadding.ZERO;
    private static final LiquidSettings DEFAULT_LIQUID_SETTINGS = LiquidSettings.APPLY_WATERLOGGING;

    public static final MapCodec<MyJigsawStructure> CODEC = RecordCodecBuilder.<MyJigsawStructure>mapCodec(instance ->
        instance.group(
            settingsCodec(instance),
            StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(s -> s.startPool),
            ResourceLocation.CODEC.optionalFieldOf("start_jigsaw_name").forGetter(s -> s.startJigsawName),
            Codec.intRange(0, 20).fieldOf("size").forGetter(s -> s.maxDepth),
            HeightProvider.CODEC.fieldOf("start_height").forGetter(s -> s.startHeight),
            Codec.BOOL.fieldOf("use_expansion_hack").forGetter(s -> s.useExpansionHack),
            Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap").forGetter(s -> s.projectStartToHeightmap),
            Codec.intRange(1, 128).fieldOf("max_distance_from_center").forGetter(s -> s.maxDistanceFromCenter),
            Codec.list(PoolAliasBinding.CODEC).optionalFieldOf("pool_aliases", List.of()).forGetter(s -> s.poolAliases),
            DimensionPadding.CODEC.optionalFieldOf("dimension_padding", DEFAULT_DIMENSION_PADDING)
                .forGetter(s -> s.dimensionPadding),
            LiquidSettings.CODEC.optionalFieldOf("liquid_settings", DEFAULT_LIQUID_SETTINGS)
                .forGetter(s -> s.liquidSettings)
        ).apply(instance, MyJigsawStructure::new)
    ).validate(MyJigsawStructure::verifyRange);

    private final Holder<StructureTemplatePool> startPool;
    private final Optional<ResourceLocation> startJigsawName;
    private final int maxDepth;
    private final HeightProvider startHeight;
    private final boolean useExpansionHack;
    private final Optional<Heightmap.Types> projectStartToHeightmap;
    private final int maxDistanceFromCenter;
    private final List<PoolAliasBinding> poolAliases;
    private final DimensionPadding dimensionPadding;
    private final LiquidSettings liquidSettings;

    public MyJigsawStructure(Structure.StructureSettings settings,
                             Holder<StructureTemplatePool> startPool,
                             Optional<ResourceLocation> startJigsawName,
                             int maxDepth,
                             HeightProvider startHeight,
                             boolean useExpansionHack,
                             Optional<Heightmap.Types> projectStartToHeightmap,
                             int maxDistanceFromCenter,
                             List<PoolAliasBinding> poolAliases,
                             DimensionPadding dimensionPadding,
                             LiquidSettings liquidSettings) {
        super(settings);
        this.startPool = startPool;
        this.startJigsawName = startJigsawName;
        this.maxDepth = maxDepth;
        this.startHeight = startHeight;
        this.useExpansionHack = useExpansionHack;
        this.projectStartToHeightmap = projectStartToHeightmap;
        this.maxDistanceFromCenter = maxDistanceFromCenter;
        this.poolAliases = poolAliases;
        this.dimensionPadding = dimensionPadding;
        this.liquidSettings = liquidSettings;
    }

    private static DataResult<MyJigsawStructure> verifyRange(MyJigsawStructure structure) {
        int terrainPadding = switch (structure.terrainAdaptation()) {
            case NONE -> 0;
            case BURY, BEARD_THIN, BEARD_BOX, ENCAPSULATE -> 12;
        };
        return structure.maxDistanceFromCenter + terrainPadding > 128
            ? DataResult.error(() -> "Structure size including terrain adaptation must not exceed 128")
            : DataResult.success(structure);
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        int minY = this.startHeight.sample(context.random(), new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor()));
        BlockPos blockpos = new BlockPos(context.chunkPos().getMinBlockX(), minY, context.chunkPos().getMinBlockZ());
        return JigsawPlacement.addPieces(
            context,
            this.startPool,
            this.startJigsawName,
            this.maxDepth,
            blockpos,
            this.useExpansionHack,
            this.projectStartToHeightmap,
            this.maxDistanceFromCenter,
            PoolAliasLookup.create(this.poolAliases, blockpos, context.seed()),
            this.dimensionPadding,
            this.liquidSettings
        );
    }

    @Override
    public StructureType<?> type() { return ModStructures.MY_JIGSAW.get(); }
}
```
