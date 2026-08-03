# 模组物品悬浮提示 (Tooltip / Shift 详情) 开发规范

> **适用范围**：OpenModularTurrets 及其衍生扩展模组  
> **核心原则**：100% 忠实还原 1.12.2 原版风味与色彩规约，同时为现代化体验提供统一灰色的精准运行数值补充。物理客户端 100% 隔离，确保专服启动安全。

---

## 📌 一、 视觉色彩与排版分层规约

所有支持 Shift 展开的物品提示均采用标准的**四层视觉结构**，严格遵循以下色彩代号（`ChatFormatting`）：

```
[按 Shift 查看详细信息]                   <- 第一层：未按 Shift 摘要 (ChatFormatting.GRAY / 斜体)
                                         <- 空行
--能量信息--                             <- 第二层：段落小标题 (按类型指定高亮色彩)
能量上限: 50000 RF                        <- 第三层：主属性键值对 (键与冒号为 ChatFormatting.GRAY，数值为 ChatFormatting.WHITE)
                                         <- 空行
--附加物--                               <- 段落小标题 (ChatFormatting.GREEN)
2x附件插槽                               <- 插槽说明 (ChatFormatting.GRAY 柔和灰色)
1x升级组件插槽                            <- 插槽说明 (ChatFormatting.GRAY 柔和灰色)
炮塔上限: 2                              <- 键与冒号 ChatFormatting.GRAY，数值 ChatFormatting.WHITE
                                         <- 空行
天空无遮挡且为白天时：+10 FE/t             <- 移植版补充效果数值 (统一 ChatFormatting.GRAY 柔和灰色，绝不抢主风味)
                                         <- 空行
我叫你放手，先生！                        <- 第四层：暗灰风味落款 (ChatFormatting.DARK_GRAY)
```

---

## 🎨 二、 分步色彩代号参照表

| 提示元素类型 | 规范色彩代号 (`ChatFormatting`) | 典型示例文本 | 规则说明 |
| :--- | :--- | :--- | :--- |
| **未按 Shift 提示** | `ChatFormatting.GRAY` | `[按 Shift 查看详细信息]` | 保持轻量提示 |
| **能量段标题** | `ChatFormatting.AQUA` | `--能量信息--` | 必须带前后双短横线 `--` |
| **附加物段标题** | `ChatFormatting.GREEN` | `--附加物--` | 必须带前后双短横线 `--` |
| **插件分类标题** | `ChatFormatting.RED` | `炮塔附件` | 插件类独占小标题，全框全局仅输出一次 |
| **升级分类标题** | `ChatFormatting.BLUE` | `炮塔升级组件` | 升级类独占小标题，全框全局仅输出一次 |
| **扩容/战利品标题**| `ChatFormatting.GOLD` | `炮塔基座存储扩容` | 扩展机械标题，无双短横线 |
| **属性键名与冒号** | `ChatFormatting.GRAY` | `能量上限: ` | 键名与冒号统一为灰色，不抢数值眼球 |
| **属性具体数值** | `ChatFormatting.WHITE` | `50000 RF` | 高亮纯白展示数字与单位 |
| **插槽槽位说明** | `ChatFormatting.GRAY` | `1x附件插槽` | 与 1.12.2 原版一致，使用灰色 |
| **原版基础描述句** | `ChatFormatting.WHITE` | `使得连接此基座的炮塔隐形.` | 保持原生白色，清晰传达功能 |
| **补充效果说明** | `ChatFormatting.GRAY` | `每级：降低 15%~30% 每发能量消耗` | **物理统一为灰色**，提供精准参数且视觉柔和 |
| **风味落款 (Flavour)**| `ChatFormatting.DARK_GRAY` | `DiggyDiggy Hole!` | 底部暗灰色经典原版落款 |

---

## 🚨 三、 物理客户端隔离与工程硬红线 (P0)

1. **绝对禁止 Common 侧直接引用 Client 类**：
   - 包含 `Screen.hasShiftDown()`、`ChatFormatting` 等 UI / 客户端类调用的方法，**必须 100% 隔离在 `omtteam.openmodularturrets.client` 包下**（例如 [OmtTooltips.java](file:///d:/c128/mods/neoforge-1.21.1-ai-starter/src/main/java/omtteam/openmodularturrets/client/OmtTooltips.java)）。
   - 通用类（Common Block/Item）绝不允许直接 `import net.minecraft.client.gui.screens.Screen`。否则在独立专用服务端（Dedicated Server）启动时，Jade/Waila 扫描或加载类时会直接引发 `NoClassDefFoundError` 崩溃。

2. **消除小标题重复 (Double Title Safety)**：
   - 控件悬浮入口函数（如 `appendItem()`）统一负责小标题的判定与输出；子处理函数（如 `appendAddonStats()`）**绝对禁止重复添加 `title()` 逻辑**。

3. **双语 DataGen 国际化对账**：
   - 所有文本 Key 必须在 `ModLanguageProvider` 中统一登记，禁止硬编码中文字符串。
   - 英文 Key（`en_us`）与中文 Key（`zh_cn`）必须保持 1:1 映射并经过 `--with-data` 校验。

---

## 🛠️ 四、 模组标准调用代码范式

```java
// 位于 client 包下的安全渲染入口
public static void appendHoverText(ItemStack stack, List<Component> lines) {
    if (!Screen.hasShiftDown()) {
        lines.add(Component.translatable("tooltip.openmodularturrets.hold_shift")
                .withStyle(ChatFormatting.GRAY));
        return;
    }

    // 按 Shift 展开：键灰值白 + 补充效果统一灰色
    lines.add(Component.empty());
    lines.add(Component.translatable("tooltip.openmodularturrets.addon.title")
            .withStyle(ChatFormatting.RED));
    lines.add(Component.empty());
    lines.add(Component.translatable("tooltip.openmodularturrets.addon.concealer.desc")
            .withStyle(ChatFormatting.WHITE));
    
    // 补充运行效果 (统一灰色)
    lines.add(Component.translatable("tooltip.openmodularturrets.addon.concealer.value", 2, 40)
            .withStyle(ChatFormatting.GRAY));
            
    // 风味落款 (暗灰色)
    lines.add(Component.empty());
    lines.add(Component.translatable("tooltip.openmodularturrets.addon.concealer.flavour")
            .withStyle(ChatFormatting.DARK_GRAY));
}
```
