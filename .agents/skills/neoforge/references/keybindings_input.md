# NeoForge 1.21.1 键盘绑定与客户端输入监听指南

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。


在很多模组中，玩家需要通过特定按键来主动释放技能、切换状态或打开自定义界面。这需要我们在**物理客户端**注册按键，并在监听到按键事件后，**通过网络包通知服务端执行核心逻辑**。

---

## 1. 声明与注册按键绑定 (KeyMapping)

所有按键的注册必须在物理客户端事件总线上完成，使用 `RegisterKeyMappingsEvent` 进行注册。为了避免专用服务器加载崩溃，将按键实例和事件接收器物理隔离在客户端：

```java
package com.tutorial.tutorialmod.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.tutorial.tutorialmod.TutorialMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.util.Lazy;
import org.lwjgl.glfw.GLFW;

// value = Dist.CLIENT 确保此事件订阅器仅在物理客户端被加载
@EventBusSubscriber(modid = TutorialMod.MODID, value = Dist.CLIENT)
public class ClientKeyRegistrar {

    // 使用 Lazy 延迟初始化按键实例，防止类加载顺序导致的问题
    public static final Lazy<KeyMapping> WOLF_SKILL_KEY = Lazy.of(() -> new KeyMapping(
            "key.tutorialmod.example_action",              // 按键名称的翻译 Key
            InputConstants.Type.KEYSYM,                     // 按键类型 (键盘按键)
            GLFW.GLFW_KEY_V,                                // 默认键位 (V 键)
            "key.categories.tutorialmod"                    // 键位在“控制设置”菜单中的分类组翻译 Key
    ));

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        // 注册按键
        event.register(WOLF_SKILL_KEY.get());
    }
}
```

在语言文件（`zh_cn.json`）中添加本地化名称：
```json
{
  "key.categories.tutorialmod": "Example Mod Keys",
  "key.tutorialmod.example_action": "Example Active Action"
}
```

---

## 2. 监听按键输入事件 (Key Input Event)

当按键被按下时，我们需要在**客户端游戏事件总线**上捕获输入动作。在 1.21.1 中，**强烈建议在 `ClientTickEvent.Post` 事件中通过循环 `consumeClick()` 消费按键**，这比在 `InputEvent.Key` 中监听更加稳定且不漏判定：

```java
package com.tutorial.tutorialmod.client;

import com.tutorial.tutorialmod.TutorialMod;
import com.tutorial.tutorialmod.network.payload.ServerboundActionPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

// 监听游戏运行期 Tick，使用 Bus.GAME 事件总线，且必须只在物理客户端加载
@EventBusSubscriber(modid = TutorialMod.MODID, value = Dist.CLIENT)
public class ClientInputHandler {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        
        // 1. 安全检查：确保当前游戏已加载（玩家不为 null）且没有打开任何游戏界面（如背包或聊天栏）
        if (mc.player != null && mc.screen == null) {
            
            // 2. 用 while 循环检测我们注册的按键是否被按下（一次 Tick 可能会消费多次点击）
            while (ClientKeyRegistrar.WOLF_SKILL_KEY.get().consumeClick()) {
                
                // 3. 核心：键盘输入是一个纯客户端事件，服务端完全不知道玩家按了什么。
                // 我们必须在这里向服务器发送一个自定义网络包，通知服务器玩家释放了技能。
                // 绝对禁止在这里直接修改玩家属性或世界数据！
                sendActionToServer();
            }
        }
    }

    private static void sendActionToServer() {
        // 使用 NeoForge 1.21.1 管道向服务端发送同步包
        // 这里的 ServerboundActionPayload 是我们自定义实现的 CustomPacketPayload
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new ServerboundActionPayload("example_action")
        );
    }
}
```

---


## 3. 服务端接收并处理按键逻辑

服务端网络包接收器接收到客户端的按键封包后，才执行真实的逻辑。这是物理双端安全的标准链路：

```java
package com.tutorial.tutorialmod.network.handler;

import com.tutorial.tutorialmod.network.payload.ServerboundActionPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ServerActionPacketHandler {

    public static void handle(final ServerboundActionPayload payload, final IPayloadContext context) {
        // 切换回服务端主线程安全地修改数据
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            
            if ("example_action".equals(payload.actionName())) {
                // 1. Validate player state (cooldown, inventory, etc.)
                // 2. Apply server-authoritative gameplay effect
                System.out.println("Player " + player.getName().getString() + " triggered example_action on server.");
            }
        });
    }
}
```
通过这种**客户端注册/捕获 -> 发包通知 -> 服务端线程校验与执行**的经典链路，可确保任何自定义按键机制的安全稳定运行，完美杜绝反作弊失效与服务端类未找到奔溃。