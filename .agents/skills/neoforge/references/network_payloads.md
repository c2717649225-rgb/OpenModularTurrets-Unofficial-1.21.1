---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-25
---
# NeoForge 1.21.1 Networking & Payloads Guide

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。


In Minecraft 1.21.1, the network system has been modernized. Custom network packets are represented as **Payloads** implementing `CustomPacketPayload`.

---

## 1. Defining a Network Payload

A payload must specify:
1. A unique `CustomPacketPayload.Type<T>` identifier.
2. A `StreamCodec` to serialize/deserialize it over the network.

Here is a template for a payload that sends custom player stats from client to server (or vice-versa):

```java
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MyCustomPayload(int energyAmount, String message) implements CustomPacketPayload {
    
    // 1. Declare the Payload Type
    public static final Type<MyCustomPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MyMod.MODID, "my_custom_payload"));
    
    // 2. Define the Stream Codec (using RegistryFriendlyByteBuf for play network safety)
    // 使用 1.21 官方推荐的 StreamCodec.composite 声明式构建器，彻底废弃 FriendlyByteBuf 的手动读写逻辑
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, MyCustomPayload> STREAM_CODEC = StreamCodec.composite(
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT, MyCustomPayload::energyAmount,
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8, MyCustomPayload::message,
        MyCustomPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

---

## 2. Registering the Payload

Payloads must be registered inside the `RegisterPayloadHandlersEvent` on the **Mod Event Bus**.

```java
@EventBusSubscriber(modid = MyMod.MODID)
public class NetworkRegistry {
    
    @SubscribeEvent
    public static void registerPackets(final RegisterPayloadHandlersEvent event) {
        // ⚠️ registrar(String) 的参数是【网络协议版本】，不是 mod id
        //（真源 javadoc: "The network version. May not be empty"）
        final PayloadRegistrar registrar = event.registrar("1.0.0");
            
        // Registering a payload sent from Client to Server
        registrar.playToServer(
            MyCustomPayload.TYPE,
            MyCustomPayload.STREAM_CODEC,
            MyServerPayloadHandler::handle
        );
        
        // Registering a payload sent from Server to Client
        // registrar.playToClient(
        //     MyClientPayload.TYPE,
        //     MyClientPayload.STREAM_CODEC,
        //     MyClientPayloadHandler::handle
        // );
    }
}
```

---

## 3. Handling the Payload

> [!IMPORTANT]
> **网络线程高压红线 (Thread Safety Warning)**：
> 网络数据包的处理句柄（Handler）默认在**网络线程**（非主线程）中被调用。**绝对禁止直接在 `handle` 方法中修改游戏世界状态**（如修改玩家属性、破坏或放置方块、修改物品栏、生成实体等），否则会导致极难排查的随机多线程死锁或数据损坏。
> 必须使用 **`context.enqueueWork(() -> { ... })`** 将所有游戏逻辑操作包裹并交由主游戏线程调度执行！

### Server-side Handler (Runs on Server)

```java
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class MyServerPayloadHandler {
    public static void handle(final MyCustomPayload payload, final IPayloadContext context) {
        // Enqueue the work to execute on the main game thread
        context.enqueueWork(() -> {
            // Get the sending player
            var player = context.player();
            
            // Execute gameplay logic safely
            int energy = payload.energyAmount();
            String msg = payload.message();
            
            player.sendSystemMessage(Component.literal("Received energy update: " + energy + " | " + msg));
        });
    }
}
```

### Client-side Handler (Runs on Client)

```java
public class MyClientPayloadHandler {
    public static void handle(final MyClientPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            // Client-side execution (e.g., updating client GUI, opening client screen)
            var player = context.player();
            // Client side logic...
        });
    }
}
```

---

## 4. Sending Payloads

Use `PacketDistributor` to send payloads.

```java
// 1. From Client to Server:
PacketDistributor.sendToServer(new MyCustomPayload(100, "Hello Server!"));

// 2. From Server to a specific Player:
PacketDistributor.sendToPlayer(serverPlayer, new MyClientPayload(...));

// 3. From Server to all players tracking a block/chunk:
PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(pos), new MyClientPayload(...));

```

---

## 5. 注册表敏感网络序列化 (RegistryFriendlyByteBuf & Codecs)

在 Minecraft 1.21.1 中，如果您在网络封包中需要传输**游戏物品 (`ItemStack`)**、**方块 (`Block`)**、或者**具有 Holder 引用包装的注册表对象（如 `Holder<SoundEvent>`、`Holder<Biome>`）**，普通的字节流缓存 `ByteBuf` 会因为不具备底层的游戏注册上下文而崩溃。

必须使用 **`RegistryFriendlyByteBuf`** 声明您的 `StreamCodec`：

### 5.1 复杂网络封包 Record 模板
```java
package com.tutorial.tutorialmod.network;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;

public record ComplexSyncPayload(ItemStack itemStack, Holder<SoundEvent> soundHolder) implements CustomPacketPayload {

    public static final Type<ComplexSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tutorialmod", "complex_sync_payload"));

    // 声明使用 RegistryFriendlyByteBuf 作为缓冲区类型
    public static final StreamCodec<RegistryFriendlyByteBuf, ComplexSyncPayload> STREAM_CODEC = StreamCodec.composite(
            // 1. 序列化 ItemStack：直接使用 Minecraft 内置的 ItemStack.STREAM_CODEC (它是注册表敏感的)
            ItemStack.STREAM_CODEC, ComplexSyncPayload::itemStack,
            // 2. 序列化 Holder<SoundEvent>：使用 ByteBufCodecs.holder 将注册项缩减为网络 ID 传输
            ByteBufCodecs.holder(Registries.SOUND_EVENT, SoundEvent.STREAM_CODEC), ComplexSyncPayload::soundHolder,
            ComplexSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

### 5.2 核心网络 Codec 总结
*   **网络传输 ItemStack**：首选 `ItemStack.STREAM_CODEC` 或 `ItemStack.OPTIONAL_STREAM_CODEC`。
*   **网络传输注册项（纯对象，如 Item/Block）**：使用 `ByteBufCodecs.registry(Registries.ITEM)` 或 `ByteBufCodecs.registry(Registries.BLOCK)`，在传输时自动映射为紧凑的整数 ID，避免庞大的全量序列化。
*   **网络传输 Holder**：使用 `ByteBufCodecs.holder(RegistryKey, ElementStreamCodec)` 或 `ByteBufCodecs.holderRegistry(RegistryKey)` 传递关联关系。

---

## ⚠️ 1.21.1 网络包高频编译错误防御与自愈

*   **编译报错**：`no suitable method found for composite(StreamCodec<ByteBuf,Integer>, ...)`
    *   **原因**：字段数超限。`StreamCodec.composite` 只能接收最多 6 个属性的复合。
    *   ❌ 错误：对含有 7 个或更多字段的 Payload Record 使用 `composite`。
    *   ✅ 修正：改用 `StreamCodec.of` 手写它的 `encode` 与 `decode` 逻辑：
        ```java
        public static final StreamCodec<ByteBuf, MyPayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> {
                ByteBufCodecs.VAR_INT.encode(buf, val.field1());
                ByteBufCodecs.STRING_UTF8.encode(buf, val.field2());
                // ... encode remainder
            },
            buf -> new MyPayload(
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf)
                // ... decode remainder (MUST keep exact same order!)
            )
        );
        ```
*   **编译报错**：`incompatible types: StreamCodec<RegistryFriendlyByteBuf,ItemStack> cannot be converted to StreamCodec<ByteBuf,Object>`
    *   ❌ 错误：在包含 `ItemStack.STREAM_CODEC` 的复合 StreamCodec 中将泛型声明为 `ByteBuf`。
    *   ✅ 修正：凡是包含 ItemStack、BlockPos、Component 等注册表类型的字段，StreamCodec 泛型必须改为 `RegistryFriendlyByteBuf`。
*   **编译报错**：`cannot find symbol: method nullable() location: interface ByteBufCodecs`
    *   ❌ 错误：`ByteBufCodecs.nullable()`。
    *   ✅ 修正：1.21.1 中不存在 nullable，可空值统一使用 `ByteBufCodecs::optional`（并在 getter 中转换为 `Optional.ofNullable`，在构造器中用 `.orElse(null)` 解包还原）。
