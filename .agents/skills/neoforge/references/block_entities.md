---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-27
---
# NeoForge 1.21.1 Block Entities Guide

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 本参考指南中所有示例代码的 `com.tutorial.tutorialmod` 均为占位。写入前必须根据 `gradle.properties` 的真实 Group ID，并执行 `init_workspace.py` 重构为当前项目的真实命名空间，严禁硬编码提交。

In Minecraft 1.21.1, Block Entities (Tile Entities) are used for blocks that require data storage (like chests, machines) or active tick processing (like furnaces).

---

## 1. Defining a Block Entity

Note that in 1.20.5+, save/load methods (`saveAdditional` / `loadAdditional`) now accept a **`HolderLookup.Provider`** parameter. You must pass this provider to registry-based operations (like saving ItemStacks or fluids).

```java
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class MyMachineBlockEntity extends BlockEntity {
    private int progress = 0;

    public MyMachineBlockEntity(BlockPos pos, BlockState state) {
        super(MyBlockEntities.MY_MACHINE_TYPE.get(), pos, state);
    }

    // 1. Loading data from NBT
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.progress = tag.getInt("Progress");
    }

    // 2. Saving data to NBT
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Progress", this.progress);
    }

    // Example custom method
    public void tickServer() {
        this.progress++;
        if (this.progress >= 100) {
            this.progress = 0;
            // Execute completion logic...
            setChanged(); // Marks the block entity as dirty so it saves to disk
        }
    }
}
```

---

## 2. Registering the Block Entity Type

```java
public class MyBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = 
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MyMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MyMachineBlockEntity>> MY_MACHINE_TYPE =
        BLOCK_ENTITIES.register("my_machine", () -> BlockEntityType.Builder.of(
            MyMachineBlockEntity::new, 
            MyBlocks.MY_MACHINE_BLOCK.get() // Register the block associated with this BE
        ).build(null));
}
```

---

## 3. Creating the Block Class

The Block class must implement `EntityBlock` to support the Block Entity.

```java
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class MyMachineBlock extends BaseEntityBlock {
    public MyMachineBlock(Properties properties) {
        super(properties);
    }

    // Instantiates the Block Entity
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MyMachineBlockEntity(pos, state);
    }

    // Required by BaseEntityBlock to render properly (defaults to INVISIBLE if not overridden)
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // Attaches the server/client ticker
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // We only tick on the server side in this example
        return level.isClientSide ? null : createTickerHelper(type, MyBlockEntities.MY_MACHINE_TYPE.get(), 
            (lvl, pos, st, be) -> be.tickServer()
        );
    }
}
```

---

## 4. Syncing Block Entity Data to Client

To sync block entity data from server to client (e.g. for GUI rendering or block animation changes):

```java
// Inside your BlockEntity class:

// 1. Package the update packet sent to client when chunk loads
@Override
public Packet<ClientGamePacketListener> getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);
}

// 2. Package the data tag (NBT) sent with the update packet
@Override
public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    CompoundTag tag = new CompoundTag();
    saveAdditional(tag, registries);
    return tag;
}

// 3. Client handles receiving the update packet:
// By default, super.onDataPacket reads the tag via loadAdditional. 
// If you have custom client-side processing, override onDataPacket:
// @Override
// public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
//     super.onDataPacket(net, pkt, registries);
// }
```

When you update data on the server and want to force an immediate client sync, call:
```java
this.setChanged();
level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
```

---

## 5. Block Entity Data Components (Pick-up & Drop Data Transfer)

In Minecraft 1.21.1, when a block is middle-clicked (picked) in creative mode, or broken to drop itself as an item with its block entity data intact, **`BlockEntityTag` is obsolete**. 

Instead, custom data transfer between the Block Entity and the Item's **Data Components** must be handled by overriding `collectImplicitComponents` and `applyImplicitComponents` on the `BlockEntity` class:

```java
// Inside your BlockEntity class (e.g. MyMachineBlockEntity):

// 1. Write block entity fields to the item's components when itemized
@Override
protected void collectImplicitComponents(net.minecraft.core.component.DataComponentMap.Builder components) {
    super.collectImplicitComponents(components);
    // Write your custom components directly to the builder
    components.set(ModComponents.ENERGY.get(), this.progress);
}

// 2. Read components back from the item when the block is placed
@Override
protected void applyImplicitComponents(BlockEntity.DataComponentInput componentInput) {
    super.applyImplicitComponents(componentInput);
    // Read components using the Supplier helper or direct type
    this.progress = componentInput.getOrDefault(ModComponents.ENERGY.get(), 0);
}
```

Make sure that your custom block item inherits block entity components by default. For container blocks, you can also implement `minecraft:container` component.

---

## 6. 1.21.1 现代能力系统绑定 (Capabilities Central Registration)

**硬性红线**：1.21.1 已经**彻底废除**了旧版重写 `getCapability` 和使用 `LazyOptional` 获取流体、能量、物品栏接口的机制。所有的方块实体功能接口公开，**必须**在 **MOD 事件总线** 上监听 **`RegisterCapabilitiesEvent`** 进行静态绑定。

`RegisterCapabilitiesEvent` 属于 `IModBusEvent`，须先读取宿主精确 `neo_version`：**21.1.0～21.1.180** 的 `@EventBusSubscriber` 默认只订阅 `Bus.GAME`，因此必须显式写 `bus = EventBusSubscriber.Bus.MOD`；**21.1.181+** 会按事件类型自动分流，应省略 `bus`。下例按 21.1.181+ 编写；旧版本须按上述规则替换注解。

### 6.1 事件订阅与静态绑定示例
```java
package com.tutorial.tutorialmod.event;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.block.entity.MyMachineBlockEntity;
import com.tutorial.tutorialmod.block.entity.ModBlockEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@EventBusSubscriber(modid = TutorialMod.MODID) // 21.1.181+ 自动分流；21.1.0～21.1.180 须显式 bus = EventBusSubscriber.Bus.MOD
public class ModCapabilityRegistrar {

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // 绑定 MyMachineBlockEntity 的 IItemHandler (物品接口) 到北面
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,      // 能力定义
                ModBlockEntities.MY_MACHINE_TYPE.get(), // 方块实体类型
                (be, side) -> {
                    // be 为当前实例，side 为漏斗等机器访问的方块面 (Direction)
                    return be.getItemHandler(side); // 返回具体的 ItemStackHandler
                }
        );
    }
}
```

---

## ⚠️ 1.21.1 方块实体常见编译错误与自愈

*   **编译报错**：`cannot find symbol: method registerProjectileBehavior / getCapability`
    *   ❌ 错误：在自定义方块实体中试图重写 `@Override getCapability(...)`。
    *   ✅ 修正：删除该方法，改用 `RegisterCapabilitiesEvent` 在 MOD 总线上集中注册能力（详见第 6 节）。
*   **编译报错**：`custom BE class is not abstract and does not override ... getTicker` (或者方法引用强转错误)
    *   ❌ 错误：在方块类的 `getTicker` 中，直接将方法引用转型为 `BlockEntityTicker`，如：
        `return (BlockEntityTicker<T>) MyMachineBlockEntity::tickServer;`。
    *   ✅ 修正：这是 Java 泛型擦除限制。**必须**使用父类 `BaseEntityBlock` 提供的泛型辅助工厂方法 `createTickerHelper` 进行转换包装：
        ```java
        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
            return level.isClientSide ? null : createTickerHelper(type, MyBlockEntities.MY_MACHINE_TYPE.get(), 
                (lvl, pos, st, be) -> be.tickServer()
            );
        }
        ```
*   **编译报错**：`incompatible types: Tag cannot be converted to CompoundTag` (在保存列表或物品堆时)
    *   ❌ 错误：`CompoundTag itemTag = stack.saveOptional(registries);`。
    *   ✅ 修正：在 1.21.1 中，`saveOptional(...)` 方法返回基类 `Tag`，而在将其 put 塞入父 Tag 或是 `ListTag` 前，**必须**显式强制类型转换：
        ```java
        CompoundTag itemTag = (CompoundTag) stack.saveOptional(registries);
        ```
*   **运行时闪退**：`NullPointerException` (在客户端 setup 注册 BEWLR 特殊渲染时)
    *   ❌ 错误：在静态块或主类构造中直接实例化 BEWLR（`BlockEntityWithoutLevelRenderer`）或者是注册渲染。
    *   ✅ 修正：客户端的特殊物品渲染器必须隔离在客户端侧，并且必须在 `RegisterClientExtensionsEvent` 注册事件中配合 `IClientItemExtensions` 绑定注册。

