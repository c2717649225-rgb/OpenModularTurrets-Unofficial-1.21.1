---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-25
---
# NeoForge 1.21.1 能力系统与数据附加系统 (Capabilities & Data Attachments)

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 本参考指南中所有示例代码的 `com.tutorial.tutorialmod` 均为占位。写入前必须根据 `gradle.properties` 的真实 Group ID，并执行 `init_workspace.py` 重构为当前项目的真实命名空间，严禁硬编码提交。

在 NeoForge 1.21.1 中，传统的 Forge Capability 系统经历了彻底的重构：
1. **数据附加系统 (Data Attachments)**：专门用于在实体、区块和方块实体上**持久化附加自定义数据**（取代了旧版通过 Capability 保存自定义 NBT 的做法）。
2. **能力系统 (Capabilities)**：专门用于**在方块、方块实体、实体之间共享功能接口**（如 `IItemHandler` 物品物品栏、`IEnergyStorage` 能量）。旧版的 `LazyOptional` 被完全移除。

---

## 1. 数据附加系统 (Data Attachments)

数据附加系统可以将数据（支持序列化保存）直接附加到任何实现了 `AttachmentHolder` 接口的类上，主要包括：**实体 (Entity)**、**区块 (LevelChunk)** 和 **方块实体 (BlockEntity)**。

### 1.1 注册 AttachmentType
使用 `DeferredRegister` 注册附加数据类型。注册时应通过 `.serialize()` 绑定一个序列化 `Codec`（如果是复杂数据则使用 `serializable()` 绑定 `INBTSerializable` 接口）：

```java
package com.tutorial.tutorialmod.attachment;

import com.tutorial.tutorialmod.TutorialMod;
import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, TutorialMod.MODID);

    // 1. 注册简单的持久化整数（例如：玩家的魔法值）
    public static final Supplier<AttachmentType<Integer>> MANA = ATTACHMENT_TYPES.register(
            "mana",
            () -> AttachmentType.builder(() -> 0) // 默认值提供者
                    .serialize(Codec.INT)          // 存档序列化 Codec
                    .copyOnDeath()                  // 玩家死亡时复制数据（保留属性）
                    .build()
    );

    // 2. 注册复杂的序列化容器数据（例如：方块实体的背包）
    public static final Supplier<AttachmentType<ItemStackHandler>> ITEM_HANDLER = ATTACHMENT_TYPES.register(
            "item_handler",
            () -> AttachmentType.serializable(() -> new ItemStackHandler(9)) // 绑定可序列化的物品栏
                    .build()
    );
}
```
*提示：切记在主类构造器中调用 `ModAttachments.ATTACHMENT_TYPES.register(modEventBus)` 注册事件总线！*

### 1.2 读写附加数据 (Usage)
```java
// 1. 获取附加数据。如果数据不存在，会自动使用 AttachmentType 的 builder 提供的默认值生成
int currentMana = player.getData(ModAttachments.MANA.get());

// 2. 修改附加数据
player.setData(ModAttachments.MANA.get(), currentMana + 10);

// 3. 检查是否有特定的数据附加项
boolean hasMana = player.hasData(ModAttachments.MANA.get());
```

---

## 2. 现代能力系统 (Capabilities)

能力系统允许你向外界公开标准接口。例如你的机器方块实体需要暴露一个 `IItemHandler` 给旁边的漏斗，或者暴露一个 `IEnergyStorage` 给电线。

1.21.1 废除了旧的 `getCapability` 方法和 `LazyOptional` 容器，取而代之的是在 **MOD 事件总线** 上订阅 `RegisterCapabilitiesEvent` 来注册静态绑定。

### 2.1 方块实体能力注册示例
```java
package com.tutorial.tutorialmod.capability;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.block.entity.MyMachineBlockEntity;
import com.tutorial.tutorialmod.block.entity.ModBlockEntities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@EventBusSubscriber(modid = TutorialMod.MODID) // 1.21.1+ 已废弃 bus 参数，系统会自动通过 IModBusEvent 路由
public class ModCapabilityRegistrar {

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // 注册方块实体的 ItemHandler 能力（用于传送物品）
        // 参数：能力定义（这里使用 NeoForge 内置的 ItemHandler.BLOCK）、方块实体类型、提供者接口
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.MY_MACHINE.get(),
                (blockEntity, side) -> {
                    // blockEntity 为当前读取的方块实体实例，side 为被查询的方块面（可用于限制输入输出）
                    return blockEntity.getItemHandler(side);
                }
        );
        
        // 注册方块实体的 EnergyStorage 能力（用于接收/输出电力）
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.MY_MACHINE.get(),
                (blockEntity, side) -> blockEntity.getEnergyStorage(side)
        );
    }
}
```

### 2.2 使能力失效与缓存清理 (Invalidation)
如果方块实体的内部数据（如电线断开或容器损坏）导致能力不再可用，必须通知世界通知邻近的方块缓存失效：
```java
public class MyMachineBlockEntity extends BlockEntity {
    // 逻辑代码中，如果容器内的物品栏被替换，或发电机被关闭：
    public void onBreak() {
        if (this.level != null) {
            // 使当前方块坐标下的能力缓存失效
            this.level.invalidateCapabilities(this.worldPosition);
        }
    }
}
```

### 2.3 查询邻近方块的能力
如果你想从外部（比如你的机器要主动从旁边的箱子抽物品或从旁边的电池充电），可以使用：
```java
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

// 在方块实体内部，查询北面（NORTH）邻近方块的 ItemHandler
BlockPos targetPos = this.worldPosition.relative(Direction.NORTH);
IItemHandler targetHandler = this.level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, Direction.SOUTH);
if (targetHandler != null) {
    // 执行物品传输逻辑...
}
```
*提示：如果是频繁的 tick 查询（例如漏斗每 tick 都在抽），强烈建议使用 `BlockCapabilityCache` 进行缓存查询以防卡顿。*

---

## 3. 流体能力与 FluidTank 保存机制 (Fluid Capabilities & NBT)

科技模组中经常有带液体储罐的机器（如发电机、炼油机）。

在 1.21.1 中，流体存储通过 **`FluidTank`** 进行。向外暴露该储罐需要注册 **`Capabilities.FluidHandler.BLOCK`**，并且保存流体数据时**必须传入 `HolderLookup.Provider`** 参数。

### 3.1 暴露流体能力 (Capability Registration)

在 `ModCapabilityRegistrar` 注册类中，将机器的流体储罐公开给外部管道或泵：

```java
        // 注册方块实体的 FluidHandler 能力（用于输入输出液体）
        event.registerBlockEntity(
                net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,
                ModBlockEntities.MY_MACHINE.get(),
                (blockEntity, side) -> {
                    // blockEntity 为当前读取的方块实体实例，side 为查询面
                    return blockEntity.getFluidHandler(side);
                }
        );
```

### 3.2 机器方块实体内部的 FluidTank 与 NBT 保存和读取

在方块实体中，定义 `FluidTank` 实例，并在 NBT 序列化中**传入 1.21.1 强制要求的 `registries` 参数**以持久化保存液体：

```java
package com.tutorial.tutorialmod.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

public class MyFluidMachineBlockEntity extends BlockEntity {

    // 1. 创建流体储罐，设置最大容量为 8000 mB (即 8 桶水)
    // 并且重写 onContentsChanged 以便在液体发生改变时自动标记区块需要存盘 (setChanged)
    private final FluidTank fluidTank = new FluidTank(8000) {
        @Override
        protected void onContentsChanged() {
            MyFluidMachineBlockEntity.this.setChanged();
        }
    };

    public MyFluidMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MY_MACHINE.get(), pos, state);
    }

    // 提供给 Capability 注册的 getter 接口
    public IFluidHandler getFluidHandler(net.minecraft.core.Direction side) {
        return this.fluidTank;
    }

    // 2. 1.21.1 核心：保存数据到 NBT 存档
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        
        // 关键红线：在 1.21.1 中，writeToNBT 必须传入 registries 查找提供者和要写入的 CompoundTag
        // 这将自动把流体数据以数据组件 (Data Component) 兼容的格式写入指定的 CompoundTag 中
        tag.put("MachineTank", this.fluidTank.writeToNBT(registries, new CompoundTag()));
    }

    // 3. 1.21.1 核心：从 NBT 存档读取数据恢复状态
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        
        // 关键红线：readFromNBT 同样必须传入 registries 查找提供者参数
        if (tag.contains("MachineTank")) {
            this.fluidTank.readFromNBT(registries, tag.getCompound("MachineTank"));
        }
    }
}
```
通过使用上述 **1.21.1 签名规范** 进行 `FluidTank` 保存和读取，您的科技机器将能安全、无损地保存任何自定义流体堆叠（FluidStack），完美杜绝读写阶段因缺少 registries 导致的游戏崩溃或存档损坏。

---

---

## 4. 能量能力与 EnergyStorage 保存机制 (Energy Capabilities & NBT)

在 NeoForge 1.21.1 中，能量（FE/RF）机制的核心实现类是 **`EnergyStorage`**。与流体储罐类似，机器方块实体需要创建并持久化保存此能量字段。

### 4.1 暴露能量能力
在 `ModCapabilityRegistrar` 注册类中，将方块实体的能量接口公开：
```java
        // 注册方块实体的 EnergyStorage 能力（电力接口）
        event.registerBlockEntity(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.MY_MACHINE.get(),
                (blockEntity, side) -> blockEntity.getEnergyStorage(side)
        );
```

### 4.2 机器方块实体内部的 EnergyStorage 读写与存盘
由于原版的 `EnergyStorage` **没有提供** 像 `FluidTank` 那样现成的双参 `writeToNBT(provider, tag)` 签名，我们需要手动使用 NBT（`IntTag` 或者是直接写入 CompoundTag）保存其持有的能量数值。或者我们可以继承 `EnergyStorage` 自定义其序列化逻辑：

```java
package com.tutorial.tutorialmod.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

public class MyEnergyMachineBlockEntity extends BlockEntity {

    // 1. 创建能量池：容量 100,000 FE，最大输入/输出均为 1000 FE
    // 重写 onEnergyChanged 或在修改能量时标记 setChanged
    private final EnergyStorage energyStorage = new EnergyStorage(100000, 1000, 1000) {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int received = super.receiveEnergy(maxReceive, simulate);
            if (received > 0 && !simulate) {
                MyEnergyMachineBlockEntity.this.setChanged();
            }
            return received;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int extracted = super.extractEnergy(maxExtract, simulate);
            if (extracted > 0 && !simulate) {
                MyEnergyMachineBlockEntity.this.setChanged();
            }
            return extracted;
        }
    };

    public MyEnergyMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MY_MACHINE.get(), pos, state);
    }

    public IEnergyStorage getEnergyStorage(net.minecraft.core.Direction side) {
        return this.energyStorage;
    }

    // 2. 将能量保存至 NBT (1.21.1 规范)
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 保存当前能量值
        tag.putInt("MachineEnergy", this.energyStorage.getEnergyStored());
    }

    // 3. 从 NBT 读取并恢复能量 (1.21.1 规范)
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("MachineEnergy")) {
            // 通过继承类中的特殊方法或是通过反射修改能量，在原版中推荐直接在自定义子类中覆写或提供 setter
            // 简单逻辑可以直接在此处重新赋值 (可覆写 EnergyStorage 并提供 setEnergy 方法)
            this.setEnergyValue(tag.getInt("MachineEnergy"));
        }
    }

    private void setEnergyValue(int energy) {
        // 自定义赋值逻辑，或者在子类中直接通过私有字段赋值
    }
}
```

---

## ⚠️ 1.21.1 数据附加与能力高频编译错误防御与自愈

*   **编译报错**：`reference to builder is ambiguous ... both method builder(Supplier) and builder(Function) match`
    *   ❌ 错误：`AttachmentType.builder(ArrayList::new)`（由于构造方法引用 `ArrayList::new` 同时满足 Supplier 与 Function 重载而导致歧义报错）。
    *   ✅ 修正：使用带显式类型见证的 Lambda 表达式定义：
        ```java
        private static final Supplier<AttachmentType<List<String>>> MY_DATA = ATTACHMENTS.register("my_data",
                () -> AttachmentType.<List<String>>builder(() -> new ArrayList<>())
                        .serialize(Codec.STRING.listOf())
                        .build());
        ```
*   **编译报错**：`does not override abstract method deserializeNBT(Provider,CompoundTag) in INBTSerializable`
    *   ❌ 错误：编写旧版的 `deserializeNBT(CompoundTag)`。
    *   ✅ 修正：1.21.1 强制要求反序列化方法传入 `HolderLookup.Provider` 参数以解析带有 Registry-bound 的 NBT 数据。
        ```java
        @Override
        public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
            // ...
        }
        ```
*   **运行时崩溃**：`ClassCastException / NullPointerException (LazyOptional cannot be cast to ...)`
    *   ❌ 错误：在任何逻辑中声明 `LazyOptional` 或使用 `getCapability` 获取邻近物品栏。
    *   ✅ 修正：1.21.1 **彻底删除了 `LazyOptional`**。获取能力一律返回裸类型接口或 `null`。请使用本指南第 2.3 节的现代 `level.getCapability(...)` 语法。


