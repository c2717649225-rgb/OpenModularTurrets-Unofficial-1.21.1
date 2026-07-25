# NeoForge 1.21.1 物品提示信息 (Tooltips) 与物理防崩指南

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。


在 Minecraft 中，为物品配置详细的信息提示（Tooltip）是必不可少的。例如：按住 **Shift** 展开详尽的背景介绍或机制说明，按住 **Ctrl** 展开能量、附魔数值指标。

在 1.21.1 中，信息提示方法 `appendHoverText` 的**方法签名发生了彻底改变**。此外，如果在提示中检测按键，若处理不当，会导致**专用服务器加载时直接崩溃**（Server Classloading Crash）。

---

## 1. 1.21.1 物品提示方法签名 (TooltipContext)

在 1.21.1 中，`appendHoverText` 废除了旧版的 `Level` 参数，引入了全新的 `Item.TooltipContext`：

```java
package com.tutorial.tutorialmod.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import java.util.List;

public class MyCustomItem extends Item {

    public MyCustomItem(Properties properties) {
        super(properties);
    }

    // 1.21.1 正确的方法覆写签名
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, 
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        
        // 追加一行普通说明
        tooltipComponents.add(Component.translatable("tooltip.tutorialmod.my_item.desc"));
    }
}
```

---

## 2. 物理双端防崩溃设计（Shift / Ctrl 按键检测红线）

玩家在屏幕上查看提示、按下 Shift 键，是**纯客户端 GUI 事件**。`Screen`（`net.minecraft.client.gui.screens.Screen`）是**物理客户端专属类**，在专用服务端 Jar 包中完全不存在。

> [!CAUTION]
> **致命红线：JVM 类加载验证崩溃**
> 如果您直接在 `appendHoverText` 中写：
> `if (Screen.hasShiftDown()) { ... }`
> 当您的模组安装在专用服务器（Dedicated Server）上时，Java 虚拟机在加载该物品类并验证字节码时，**会因为找不到 `Screen` 类直接报 `NoClassDefFoundError` 导致服务器启动崩溃！**
> 仅仅使用 `if (FMLEnvironment.dist == Dist.CLIENT)` 进行包裹是不够的，因为类加载器在编译方法签名时依然会去寻找引用的类。

### 安全的解决办法：隔离客户端助手类

必须将所有对 `Screen` 的直接引用放置在**仅客户端加载的辅助类**中。通过这种延迟加载（Lazy Loading）手段保护服务端：

#### 2.1 创建客户端专属辅助类 (ClientTooltipUtil)
*   **注意**：此辅助类只会在物理客户端被类加载器解析。

```java
package com.tutorial.tutorialmod.client;

import net.minecraft.client.gui.screens.Screen;

/**
 * 放在 client 包（或仅由 Dist.CLIENT 类引用）。
 * 不要使用 @OnlyIn；包隔离 + 下方 FMLEnvironment 守卫即可。
 */
public class ClientTooltipUtil {

    public static boolean isShiftDown() {
        return Screen.hasShiftDown();
    }

    public static boolean isCtrlDown() {
        return Screen.hasControlDown();
    }
}
```

#### 2.2 在共享 Item 类中安全调用
*   利用 `FMLEnvironment.dist` 进行守卫拦截，确保服务端不会解析客户端辅助类：

```java
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, 
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);

        // 1. 守卫拦截：如果当前处于物理服务端，则不加载任何客户端类，防止崩溃
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            
            // 2. 调用客户端辅助类，间接访问 Screen
            if (com.tutorial.tutorialmod.client.ClientTooltipUtil.isShiftDown()) {
                // 如果按住了 Shift，追加详尽文字
                tooltipComponents.add(Component.translatable("tooltip.tutorialmod.my_item.shift_info")
                        .withStyle(net.minecraft.ChatFormatting.GOLD));
            } else {
                // 如果未按住 Shift，提示按住 Shift 查看详情
                tooltipComponents.add(Component.translatable("tooltip.tutorialmod.my_item.press_shift")
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        }
    }
```

---

## 3. 本地化翻译配置 (zh_cn.json)

配置相应的彩色和提示语翻译：

```json
{
  "tooltip.tutorialmod.my_item.desc": "§7A sample item description for tooltips.",
  "tooltip.tutorialmod.my_item.press_shift": "按住 [Shift] 键查看详细描述",
  "tooltip.tutorialmod.my_item.shift_info": "§6Extra details:§f hold Shift to reveal advanced stats or lore for this sample item."
}
```
通过这种**类加载屏障隔离设计**，您可以为模组量身定做出极其炫酷的多功能 Shift/Ctrl 物品信息提示卡片，同时 100% 确保服务端在联机部署时不会发生任何类加载崩溃。