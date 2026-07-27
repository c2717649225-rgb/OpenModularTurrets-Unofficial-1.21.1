---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-27
---
# Minecraft 1.21.1 数据组件 (Data Components) 参考指南

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 本参考指南中所有示例代码的 `com.tutorial.tutorialmod` 均为占位。写入前必须根据 `gradle.properties` 的真实 Group ID，并执行 `init_workspace.py` 重构为当前项目的真实命名空间，严禁硬编码提交。

在 Minecraft 1.20.5 及更高版本（包括 1.21.1）中，原有的无结构 NBT 物品数据存储系统被彻底废除，取而代之的是类型安全、经过验证的**数据组件 (Data Components)**。所有物品的数据读写、同步和保存都必须使用该系统。

---

## 1. 数据组件的基本操作 (ItemStack API)

在代码中操作 `ItemStack` 的组件数据时，常用的 API 如下：

### 1.1 写入/修改组件数据 (`set`)
```java
ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
// 写入一个整型组件（例如：自定义能量值）
stack.set(ModComponents.ENERGY.get(), 100);

// 写入一个复杂的对象组件（例如：包含名字和等级的 Record）
stack.set(ModComponents.OWNER.get(), new OwnerData("PlayerName", 5));
```

### 1.2 读取组件数据 (`get` 和 `getOrDefault`)
```java
// 1. 直接读取。如果物品没有该组件，会返回 null
Integer energy = stack.get(ModComponents.ENERGY.get());
if (energy != null) {
    // 处理能量逻辑
}

// 2. 读取并提供默认值。如果物品没有该组件，返回传入的默认值
int currentEnergy = stack.getOrDefault(ModComponents.ENERGY.get(), 0);
```

### 1.3 动态更新组件数据 (`update`)
推荐使用 `update` 来修改已有值，这可以避免手动获取并空指针判断的麻烦：
```java
// 语法：stack.update(组件类型, 默认值, 更新操作的函数式接口)
// 下面代码实现将能量值增加 10 点（如果不存在则默认 0 + 10）
stack.update(ModComponents.ENERGY.get(), 0, energy -> energy + 10);
```

### 1.4 移除组件 (`remove`)
```java
// 从 ItemStack 上完全移除该组件
stack.remove(ModComponents.ENERGY.get());
```

---

## 2. 注册自定义数据组件

自定义的数据组件必须使用 `DeferredRegister` 进行注册。所有的组件数据如果需要在存档中保存，必须提供一个 `Codec`；如果需要同步到客户端，必须标记为 `networkSynchronized`。

### 2.1 简单类型组件注册 (例如：Integer)
```java
package com.tutorial.tutorialmod.component;

import com.tutorial.tutorialmod.TutorialMod;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, TutorialMod.MODID);

    // 注册一个名为 "energy" 的整数组件
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENERGY =
            DATA_COMPONENT_TYPES.register("energy", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT) // 存档持久化支持
                    .networkSynchronized(ByteBufCodecs.VAR_INT) // 网络同步支持 (VarInt)
                    .build()
            );
}
```

### 2.2 复杂对象类型组件注册 (使用 Record 和 Codec)

> [!CAUTION]
> **⚠️ P0 物理红线：Codec 字段与构造工厂参数对齐**
> `RecordCodecBuilder` 的 `instance.group(...)` 字段顺序必须匹配 `.apply(...)` 工厂函数的入参顺序。使用 `OwnerData::new` 这类 record 主构造器引用时，才等同于 record component 的声明顺序；显式适配 lambda（例如 `(name, level) -> new OwnerData(name, level)`）可以合法表达不同的 group 顺序。
> 真正危险的是字段值与工厂入参错位：它可能在编译期暴露，也可能在运行时产生类型异常，或在同类型字段之间静默交换值。不要把“JSON 字段顺序”与“工厂参数映射”混为一谈。
> `StreamCodec.composite(...)` 同理：每个 Codec/getter 对的顺序必须匹配末尾构造函数的入参顺序。它最多组合 6 个**字段**；7 个及以上字段请使用自定义 `StreamCodec.of`。

如果要存储包含多个字段的数据，应先编写一个 `Record`：

```java
package com.tutorial.tutorialmod.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record OwnerData(String name, int level) {
    // 1. 用于存档读写的 Codec (定义字段序列化规则)
    public static final Codec<OwnerData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("name").forGetter(OwnerData::name),
                    Codec.INT.fieldOf("level").forGetter(OwnerData::level)
            ).apply(instance, OwnerData::new)
    );

    // 2. 用于网络同步的 StreamCodec
    public static final StreamCodec<FriendlyByteBuf, OwnerData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, OwnerData::name,
            ByteBufCodecs.VAR_INT, OwnerData::level,
            OwnerData::new
    );
}
```

然后在注册类中进行注册：
```java
public static final DeferredHolder<DataComponentType<?>, DataComponentType<OwnerData>> OWNER =
        DATA_COMPONENT_TYPES.register("owner", () -> DataComponentType.<OwnerData>builder()
                .persistent(OwnerData.CODEC)
                .networkSynchronized(OwnerData.STREAM_CODEC)
                .build()
        );
```

### 2.3 复合嵌套类型组件注册 (包含 List 和 Map)

对于复杂的数据对象（例如存储一组生物的属性列表，以及键值对的扩展数据），可使用以下包含列表和 Map 的高级 Record 模板：

> [!CAUTION]
> **Codec 映射红线**：同 2.2 节所述，字段编解码顺序必须匹配实际传给 `.apply(...)` / `StreamCodec.composite(...)` 的构造工厂参数；使用 `ComplexStats::new` 时即为 record component 顺序。

```java
package com.tutorial.tutorialmod.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import java.util.List;
import java.util.Map;

public record ComplexStats(List<OwnerData> followers, Map<String, Integer> attributes) {
    
    // 1. 复杂 Codec：使用 .listOf() 与 Codec.unboundedMap() 进行嵌套反序列化
    public static final Codec<ComplexStats> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    OwnerData.CODEC.listOf().fieldOf("followers").forGetter(ComplexStats::followers),
                    Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("attributes").forGetter(ComplexStats::attributes)
            ).apply(instance, ComplexStats::new)
    );

    // 2. 复杂 StreamCodec：使用 ByteBufCodecs.list() 与 ByteBufCodecs.map() 自动封装集合序列化
    public static final StreamCodec<RegistryFriendlyByteBuf, ComplexStats> STREAM_CODEC = StreamCodec.composite(
            OwnerData.STREAM_CODEC.apply(ByteBufCodecs.list()), ComplexStats::followers,
            ByteBufCodecs.map(java.util.LinkedHashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), ComplexStats::attributes,
            ComplexStats::new
    );
}
```

注册组件：
```java
public static final DeferredHolder<DataComponentType<?>, DataComponentType<ComplexStats>> COMPLEX_STATS =
        DATA_COMPONENT_TYPES.register("complex_stats", () -> DataComponentType.<ComplexStats>builder()
                .persistent(ComplexStats.CODEC)
                .networkSynchronized(ComplexStats.STREAM_CODEC)
                .build()
        );
```

---

## 3. 游戏内命令与 JSON 格式差异对照 (NBT vs Components)

在 1.21.1 中，游戏内指令的物品 NBT 部分全部转化为组件数组格式（使用中括号 `[]` 替代花括号 `{}`，组件名包含命名空间）：

* **给予附魔武器**：
  * *1.20.4 旧版 (NBT)*：
    `/give @p diamond_sword{Enchantments:[{id:"sharpness",lvl:5}]}`
  * *1.21.1 新版 (Component)*：
    `/give @p diamond_sword[enchantments={levels:{"minecraft:sharpness":5}}]`
    
* **给予自定义名称物品**：
  * *1.20.4 旧版 (NBT)*：
    `/give @p stone{display:{Name:'{"text":"My Stone"}'}}`
  * *1.21.1 新版 (Component)*：
    `/give @p stone[custom_name='{"text":"My Stone"}']`

* **写入自定义未定义数据 (Custom Data)**：
  * *1.20.4 旧版 (NBT)*：
    `/give @p stick{my_val:100}`
  * *1.21.1 新版 (Component)*：
    `/give @p stick[custom_data={my_val:100}]`

---

## ⚠️ 1.21.1 数据组件高频编译错误防御与自愈

*   **编译报错**：`cannot find symbol: method getOrCreateTag() / getTag() / setTag() location: class ItemStack`
    *   ❌ 错误：`stack.getOrCreateTag().putInt("MyKey", 1);`
    *   ✅ 修正：使用 `DataComponents.CUSTOM_DATA`
        ```java
        import net.minecraft.core.component.DataComponents;
        import net.minecraft.world.item.component.CustomData;

        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putInt("MyKey", 1);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        ```
*   **编译报错**：`cannot find symbol: method setHoverName(Component) location: variable stack of type ItemStack`
    *   ❌ 错误：`stack.setHoverName(name);`
    *   ✅ 修正：`stack.set(DataComponents.CUSTOM_NAME, name);`
*   **编译报错**：`cannot find symbol: class PotionUtils` / `variable POTION location: class DataComponents`
    *   ❌ 错误：使用 `PotionUtils.getPotion(...)` 或 `DataComponents.POTION`。
    *   ✅ 修正：1.21.1 彻底删除了 PotionUtils。药水组件应改为 `DataComponents.POTION_CONTENTS` 并配合 `PotionContents` record 存取。
