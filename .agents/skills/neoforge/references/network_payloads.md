---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-27
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
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MyCustomPayload(int energyAmount, String message) implements CustomPacketPayload {
    
    // 1. Declare the Payload Type
    public static final Type<MyCustomPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MyMod.MODID, "my_custom_payload"));
    
    // 2. 只有基本值字段，使用最窄的 ByteBuf 即可；注册表敏感字段见第 5 节。
    public static final StreamCodec<ByteBuf, MyCustomPayload> STREAM_CODEC = StreamCodec.composite(
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
            MyServerPayloadHandler::handleOnMain
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
> **线程真值（NeoForge 21.1.x）**：
> `PayloadRegistrar` 初始使用 `HandlerThread.MAIN`，因此 Handler **默认在接收端主线程调用**。默认注册下可以直接执行经过校验的世界/玩家状态修改，机械地再套 `context.enqueueWork(...)` 没有必要。
>
> 只有注册链显式调用 `.executesOn(HandlerThread.NETWORK)` 时，后续由该 registrar 注册的 Handler 才运行在网络线程。此时网络线程阶段只能做不访问游戏对象的纯计算；任何 `Level` / `Entity` / 玩家状态回写必须通过 `context.enqueueWork(...)` 返回主线程，并处理其 `CompletableFuture` 异常。
>
> 真源：[NeoForge 1.21–1.21.1 Payload 文档](https://docs.neoforged.net/docs/1.21.1/networking/payload/)；`neoforge-21.1.234` 源码中 `PayloadRegistrar.thread` 初值为 `HandlerThread.MAIN`，`executesOn` 返回带新线程配置的 registrar 副本。

### 3.1 默认模式：主线程 Handler

#### Server-side Handler

```java
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class MyServerPayloadHandler {
    public static void handleOnMain(final MyCustomPayload payload, final IPayloadContext context) {
        // 默认已经在服务端主线程；C2S 输入仍然是不可信输入，先做权限与范围校验。
        var player = context.player();
        int energy = Mth.clamp(payload.energyAmount(), 0, MAX_ENERGY);
        String msg = payload.message();

        player.sendSystemMessage(Component.literal("Received energy update: " + energy + " | " + msg));
    }
}
```

#### Client-side Handler

```java
public class MyClientPayloadHandler {
    public static void handleOnMain(final MyClientPayload payload, final IPayloadContext context) {
        // 默认已经在客户端主/渲染线程；该类仍必须位于物理客户端隔离范围。
        var player = context.player();
        // Client side logic...
    }
}
```

### 3.2 显式 NETWORK 模式：计算与回写分离

只有确有较重、且可完全脱离游戏对象执行的计算时才切换到网络线程。`executesOn` 返回新 registrar，必须保存返回值：

```java
@SubscribeEvent
public static void registerPackets(final RegisterPayloadHandlersEvent event) {
    final PayloadRegistrar networkRegistrar = event.registrar("1.0.0")
        .executesOn(HandlerThread.NETWORK);

    networkRegistrar.playToServer(
        MyCustomPayload.TYPE,
        MyCustomPayload.STREAM_CODEC,
        MyServerPayloadHandler::handleOnNetwork
    );
}
```

```java
public static void handleOnNetwork(
        final MyCustomPayload payload,
        final IPayloadContext context
) {
    // 这里只处理 payload 自带的纯数据；不要读取 Level、Entity、玩家背包或注册表可变状态。
    final int validatedEnergy = validateAndCompute(payload.energyAmount());

    context.enqueueWork(() -> {
        // 已回到服务端主线程；再次校验发送者当前状态后再执行权威修改。
        var player = context.player();
        applyEnergy(player, validatedEnergy);
    }).exceptionally(error -> {
        LOGGER.error("Failed to apply MyCustomPayload", error);
        context.disconnect(Component.translatable("my_mod.networking.failed"));
        return null;
    });
}
```

若 `enqueueWork` 返回的 Future 异常未被处理，异常可能被吞掉，导致“发包成功但逻辑无效果”的静默故障。

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
    *   ✅ 修正：只要任一字段使用 `ItemStack.STREAM_CODEC` 等要求注册表上下文的字段 Codec，外层复合 Codec 的缓冲区类型就必须兼容 `RegistryFriendlyByteBuf`。不要仅凭值类型猜测；以字段 Codec 的泛型签名为准。
*   **编译报错**：`cannot find symbol: method nullable() location: interface ByteBufCodecs`
    *   ❌ 错误：`ByteBufCodecs.nullable()`。
    *   ✅ 修正：1.21.1 中不存在 nullable，可空值统一使用 `ByteBufCodecs::optional`（并在 getter 中转换为 `Optional.ofNullable`，在构造器中用 `.orElse(null)` 解包还原）。
