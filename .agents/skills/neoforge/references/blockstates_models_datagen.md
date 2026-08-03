# NeoForge 1.21.1 材质、模型与掉落表数据生成 (DataGen) 指南

> [!IMPORTANT]
> **1.21.1 纹理路径规范**：
> 方块与物品纹理使用单数目录 `textures/block/` 与 `textures/item/`。
> 老模组移植时，将旧版复数路径 `textures/blocks/`、`textures/items/` 迁移为单数目录，并同步修改模型与 DataGen 引用，否则客户端会产生 missing texture / atlas 警告。

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。


在 1.21.1 模组开发中，手写数以百计的方块状态 (BlockState)、方块模型 (Block Model)、物品模型 (Item Model) 以及方块掉落表 (Loot Table) 的 JSON 极其繁琐且极易出错。根据 `AGENTS.md` 规约，**强烈建议使用 NeoForge 的 DataGen 系统自动生成这些资源**。

以下是实现上述所有资源自动生成的完整标准蓝图。

---

## 1. 生成方块状态与模型 (`BlockStateProvider`)

方块状态生成器同时负责生成对应的 `blockstates/*.json` 以及方块的 `models/block/*.json` 模型文件：

```java
package com.tutorial.tutorialmod.datagen;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.block.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModBlockStateProvider extends BlockStateProvider {

    public ModBlockStateProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, TutorialMod.MODID, exFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        // 1. 生成普通的立方体方块状态及模型 (如红宝石块，使用 block/ruby_block.png 贴图)
        simpleBlock(ModBlocks.RUBY_BLOCK.get());
        simpleBlock(ModBlocks.RUBY_ORE.get());

        // 2. 生成带朝向状态的自定义机器方块模型 (如粉碎机 Crusher)
        // 假设机器的前面、侧面、顶部贴图分别为：crusher_front, crusher_side, crusher_top
        registerCrusher(ModBlocks.CRUSHER.get());

        // 3. 生成柱状/上下异色方块 (如柱状机器底座 Turret Base)
        // 使用 models().cubeColumn 或 models().cubeBottomTop
        ModelFile columnModel = models().cubeColumn("turret_base", modLoc("block/turret_base_side"), modLoc("block/turret_base_top"));
        simpleBlockWithItem(ModBlocks.TURRET_BASE.get(), columnModel);
    }

    private void registerCrusher(Block block) {
        // 声明六面材质对应的贴图文件路径 (在 assets/tutorialmod/textures/block/ 下)
        ResourceLocation side = modLoc("block/crusher_side");
        ResourceLocation top = modLoc("block/crusher_top");
        ResourceLocation front = modLoc("block/crusher_front");

        // 构建一个多贴图方向的立方体模型 (定义在 models/block/crusher.json)
        var model = models().cube("crusher", side, side, front, side, top, top);

        // 为该方块的 FACING 属性生成朝向旋转 JSON
        getVariantBuilder(block).forAllStates(state -> {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return ConfiguredModel.builder()
                    .modelFile(model)
                    .rotationY(((int) facing.toYRot() + 180) % 360) // 根据朝向计算 Y 轴旋转角
                    .build();
        });
    }
}
```

---

## 2. 生成物品模型 (`ItemModelProvider`)

物品模型分为普通物品模型（继承 `item/generated`）以及手持工具/武器模型（继承 `item/handheld`）：

```java
package com.tutorial.tutorialmod.datagen;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.item.ModItems;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModItemModelProvider extends ItemModelProvider {

    public ModItemModelProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, TutorialMod.MODID, exFileHelper);
    }

    @Override
    protected void registerModels() {
        // 1. 生成普通平铺物品模型 (继承 item/generated)
        // 对应的贴图在 assets/tutorialmod/textures/item/ruby.png
        simpleItem(ModItems.RUBY.get());
        simpleItem(ModItems.RAW_RUBY.get());

        // 2. 生成手持工具/武器模型 (继承 item/handheld)
        // 对应的贴图在 assets/tutorialmod/textures/item/ruby_pickaxe.png
        handheldItem(ModItems.RUBY_PICKAXE.get());
        handheldItem(ModItems.RUBY_SWORD.get());
    }

    private void simpleItem(Item item) {
        String name = item.toString(); // 获取物品的注册名
        withExistingParent(name, mcLoc("item/generated"))
                .texture("layer0", modLoc("item/" + name));
    }

    private void handheldItem(Item item) {
        String name = item.toString();
        withExistingParent(name, mcLoc("item/handheld"))
                .texture("layer0", modLoc("item/" + name));
    }
}
```

---

## 3. 生成方块掉落表 (`LootTableProvider`)

生成方块掉落表（Loot Tables）通常包含两部分：写明具体生成逻辑的 `BlockLootSubProvider`，以及对外包装的 `LootTableProvider` 容器：

### 3.1 编写方块掉落数据子提供者 (`BlockLootSubProvider`)

```java
package com.tutorial.tutorialmod.datagen.loot;

import com.tutorial.tutorialmod.block.ModBlocks;
import com.tutorial.tutorialmod.item.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.Set;
import java.util.stream.Collectors;

public class ModBlockLootSubProvider extends BlockLootSubProvider {

    protected ModBlockLootSubProvider(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        // 1. 掉落方块自身 (适用于大多数常规方块，如红宝石块、机器方块)
        this.dropSelf(ModBlocks.RUBY_BLOCK.get());
        this.dropSelf(ModBlocks.CRUSHER.get());

        // 2. 掉落非自身物品，且集成时运附魔 (适用于矿石，如挖掘红宝石矿掉落粗红宝石)
        this.add(ModBlocks.RUBY_ORE.get(), 
                block -> createOreDrop(block, ModItems.RAW_RUBY.get())
        );
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        // 必须返回当前模组注册的所有方块列表，数据生成器以此确保每一个方块都明确拥有掉落表
        return ModBlocks.BLOCKS.getEntries().stream()
                .map(holder -> (Block) holder.value())
                .collect(Collectors.toList());
    }
}
```

### 3.2 包装为 LootTableProvider 并注册到事件中

```java
package com.tutorial.tutorialmod.datagen;

import com.tutorial.tutorialmod.datagen.loot.ModBlockLootSubProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ModLootTableProvider {
    public static LootTableProvider create(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        return new LootTableProvider(
                output,
                Set.of(), // 可选的特定类型（如果写了BlockLootSubProvider，这里留空即可）
                // 传入我们定义的掉落表生成器配置
                List.of(new LootTableProvider.SubProviderEntry(
                        ModBlockLootSubProvider::new,
                        LootContextParamSets.BLOCK // 声明当前子提供者用于处理方块(BLOCK)级别掉落
                )),
                lookupProvider
        );
    }
}
```

---

## 4. 在 `DataGenerators.gatherData` 中完成配置集成

在您的主 `gatherData` 事件监听方法中进行以上生成器的注入注册：

```java
@SubscribeEvent
public static void gatherData(GatherDataEvent event) {
    DataGenerator generator = event.getGenerator();
    PackOutput packOutput = generator.getPackOutput();
    ExistingFileHelper existingFileHelper = event.getExistingFileHelper();
    CompletableFuture<HolderLookup.Provider> lookupProvider = event.getLookupProvider();

    // 1. 注册方块状态与模型生成器
    generator.addProvider(event.includeClient(), new ModBlockStateProvider(packOutput, existingFileHelper));
    
    // 2. 注册物品模型生成器
    generator.addProvider(event.includeClient(), new ModItemModelProvider(packOutput, existingFileHelper));

    // 3. 注册掉落表生成器
    generator.addProvider(event.includeServer(), ModLootTableProvider.create(packOutput, lookupProvider));
}
```

---

## 5. 生成物品与方块标签 (`TagsProvider`)

在 1.21.1 中，方块/物品等标签（Tags）也是通过 DataGen 自动输出 JSON 文件的。因为物品标签依赖方块标签（如复制原木、台阶等到物品标签上），必须正确传递 CompletableFuture 查找器。

### 5.1 编写方块标签提供者 (`BlockTagsProvider`)
```java
package com.tutorial.tutorialmod.datagen;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.block.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public class ModBlockTagProvider extends BlockTagsProvider {

    public ModBlockTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                               @Nullable ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, TutorialMod.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // 定义哪些方块需要用镐挖掘，并且需要铁级或以上工具
        tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .add(ModBlocks.RUBY_BLOCK.get())
                .add(ModBlocks.RUBY_ORE.get());

        tag(BlockTags.NEEDS_IRON_TOOL)
                .add(ModBlocks.RUBY_ORE.get());
    }
}
```

### 5.2 编写物品标签提供者 (`ItemTagsProvider`)
```java
package com.tutorial.tutorialmod.datagen;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.item.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public class ModItemTagProvider extends ItemTagsProvider {

    public ModItemTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                              CompletableFuture<net.minecraft.data.tags.TagsProvider.TagLookup<Block>> blockTags,
                              @Nullable ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, blockTags, TutorialMod.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // 添加物品到可附魔剑类标签
        tag(ItemTags.SWORD_ENCHANTABLE)
                .add(ModItems.RUBY_SWORD.get());
    }
}
```

### 5.3 在 `DataGenerators.gatherData` 中完成配置集成
```java
    // 在 gatherData 方法中：
    
    // 1. 注册方块标签（必须首先注册，且持有 blockTagProvider 实例）
    ModBlockTagProvider blockTagProvider = new ModBlockTagProvider(packOutput, lookupProvider, existingFileHelper);
    generator.addProvider(event.includeServer(), blockTagProvider);

    // 2. 注册物品标签（传入 blockTagProvider.contentsGetter()）
    generator.addProvider(event.includeServer(), new ModItemTagProvider(
            packOutput, lookupProvider, blockTagProvider.contentsGetter(), existingFileHelper));
```

---

## ⚠️ 1.21.1 标签与掉落表 DataGen 高频编译错误防御与自愈

*   **编译报错**：`cannot find symbol: class ItemTagsProvider location: package net.neoforged.neoforge.common.data`
    *   ❌ 错误：`import net.neoforged.neoforge.common.data.ItemTagsProvider;`。
    *   ✅ 修正：1.21.1 的 `ItemTagsProvider` 属于 vanilla 原版包下，必须从 `net.minecraft.data.tags.ItemTagsProvider` 导入（只有 `BlockTagsProvider` 属于 NeoForge 扩展）。
*   **编译报错**：`cannot find symbol: class TagLookup`
    *   ❌ 错误：在构造函数中直接声明 `CompletableFuture<TagLookup<Block>> blockTags`。
    *   ✅ 修正：`TagLookup` 是 `TagsProvider` 的内部静态接口，无法直接作为顶层类解包引用。必须指定外围限定符，声明并导入为：
        ```java
        CompletableFuture<net.minecraft.data.tags.TagsProvider.TagLookup<Block>> blockTags
        ```
*   **编译报错**：`cannot find symbol: variable MINEABLE_WITH_PICKAXE location: class ItemTags`
    *   ❌ 错误：在物品标签提供者中编写 `copy(BlockTags.MINEABLE_WITH_PICKAXE, ItemTags.MINEABLE_WITH_PICKAXE)`。
    *   ✅ 修正：**物品侧绝对没有 MINEABLE_WITH_PICKAXE 标签**！只有方块才拥有该挖掘标签。
*   **编译报错**：`no suitable method found for of(TagKey,...,TagKey,...)` (在 Map 声明中)
    *   ❌ 错误：使用 `Map.of(KEY1, VAL1, ... KEY11, VAL11)` 声明了超过 10 对键值。
    *   ✅ 修正：Java 标准库 `Map.of` 只能传递最多 10 对参数。多于 10 对参数必须改用 `Map.ofEntries(Map.entry(K1, V1), ...)` 组合。

```
