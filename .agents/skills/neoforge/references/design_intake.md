---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-27
---

# 玩法需求拆解 SOP (Design Intake)

> **何时读本文件**：用户给出的是**玩法愿景**（"我要一个魔法/机械/饰品模组"）而非具体编码任务时。
> 本文件属于**设计阶段**，不占实现阶段「1～2 篇 reference」限额；拆解完成进入实现时，再按下表映射按需取文档。
> 输出物 = 一份任务清单（见文末模板），经用户确认后逐项实现。

---

## 1. 需求澄清三问（信息不足时先问，最多一轮）

1. **范围**：这个玩法的最小可玩闭环是什么？（例：魔力系统 → "有魔力值、能消耗、能恢复、有一个消耗魔力的物品" 就是闭环；魔法书 GUI、天赋树都是后续迭代）
2. **载体**：数据挂在谁身上？（玩家 → Attachment；物品 → Data Component；方块位置 → BlockEntity；世界全局 → SavedData）
3. **交互面**：玩家怎么感知和操作？（HUD 显示 / GUI 菜单 / 手持右键 / 指令 / 纯被动）

答案直接决定下表选哪几行。**不要跳过澄清直接开写 Major 级玩法。**

---

## 2. 玩法 → 平台能力映射表（核心速查）

| 玩法概念 | 能力组合（按依赖顺序） | 入口文档 |
| :--- | :--- | :--- |
| 新材料 / 矿石装备线 | 注册 → 矿石生成 → 配方/标签 DataGen → 装备属性 | pb_register_item_block → worldgen_ores → recipes_standard_datagen → custom_gear |
| 消耗品 / 食物 / 药剂 | 注册 → 食物属性 →（可选）药水效果 | pb_register_item_block → item_properties → potions_brewing |
| 玩家系统（魔力/体温/饰品/技能） | Attachment → **网络同步** → HUD 显示 | pb_attachment_player_data → pb_network_payload → hud_overlay_layers |
| 带数据的物品（充能/绑定/模式切换） | Data Component → 使用逻辑 → Tooltip 展示 | pb_data_component → item_properties → item_tooltips |
| 机器 / 工作台 / 容器 | BlockEntity → 物品栏 Capability → GUI 菜单 →（自定义配方时）Recipe Serializer | pb_block_entity_sync → capabilities_attachments → menus_screens → custom_recipes |
| 管道 / 线缆 / 物流 | BlockEntity → 邻居 Capability（**必须缓存**） | pb_block_entity_sync → capabilities_attachments + anti_patterns§10 |
| 新生物 / 宠物 | 实体注册 + 属性 AI → 模型渲染 → 音效 | custom_entities → custom_entity_models → sounds |
| Boss / 战斗机制 | 实体 → 自定义伤害类型 → 粒子/音效反馈 | custom_entities → damage_types → custom_particles |
| 新维度 / 群系 | 维度 + 传送门 → 群系 → 地物 | custom_dimensions → custom_biomes → worldgen_ores |
| 农作物 / 植物 | 作物方块 → 种子/果实物品 →（野生生成时）worldgen | custom_blocks → item_properties → worldgen_ores |
| 装饰方块系（含变体） | 注册 → 状态/模型 DataGen | pb_register_item_block → blockstates_models_datagen |
| 全局规则改动（掉落/合成/耐久） | 全局掉落修改器 / 数据映射 / 事件拦截 | global_loot_modifiers → data_maps → event_system |
| 村民职业 / 交易 | 交易注册 | villager_trades |
| 指令 / 管理工具 | Brigadier 指令 | custom_commands |
| 世界级进度 / 团队数据 | SavedData →（展示时）网络同步 | saved_data → pb_network_payload |
| 与其他模组联动 | `c:` 标签 → JEI 展示 → 解耦架构 | recipes_standard_datagen → jei_integration → architecture_design |

---

## 3. 网络同步判定（AI 最常漏的一步）

**规则：服务端权威数据要在客户端看到（HUD/GUI/渲染/Tooltip），就必须显式同步；Attachment 与 SavedData 都不会自动同步。**

| 场景 | 是否需要 Payload |
| :--- | :--- |
| 魔力值显示在 HUD | ✅ 必须（登录/变更时 S2C） |
| BlockEntity 数据进 GUI 菜单 | ⭕ Menu 的 `ContainerData`/slot 机制已覆盖简单场景；复杂结构才需自定义 Payload |
| BlockEntity 数据用于世界内渲染 | ✅ `getUpdateTag`/`getUpdatePacket` 路径（见 pb_block_entity_sync） |
| 仅服务端逻辑（掉落判定、指令） | ❌ 不需要，禁止为"以防万一"加同步 |
| 客户端按键触发服务端行为 | ✅ C2S Payload + `enqueueWork`（P0-4） |

---

## 4. 任务分级与排序（对齐 AGENTS.md 部分三）

- **Minor**（直接写码+门禁）：注册项、配方/标签、简单 Data Component、Tooltip、创造页签。
- **Major**（先出短方案确认再写）：实体、网络 Payload、Mixin、BlockEntity+GUI 链、维度、跨模组架构。
- 排序原则：**先地基后楼层**——注册 → 数据载体 → 逻辑 → 同步 → 展示 → DataGen 资源补全；每完成一项跑门禁，不攒大批量。

---

## 5. 反过度工程红线

1. **一个物品的需求不要做成一个系统**：用户要"一把喷火剑"，交付物是剑 + 火焰效果，不是"元素武器框架"。
2. **禁止预留抽象层**：没有第二个实现之前，不写接口/抽象基类/注册框架之上的自建框架。
3. **常量先行，Config 后置**：数值先用常量交付闭环；用户提出要调参时再上 `ModConfigSpec`（configuration.md）。
4. **最小闭环优先交付**：按第 1 问的闭环拆第一批任务；"顺便把 XX 也做了"一律进 backlog 待确认。
5. **每个任务映射到表中一行**：拆出的任务若无法对应任何能力组合，先质疑拆解本身。

---

## 6. 输出模板（拆解交付物）

```markdown
## <玩法名> 实现拆解（待确认）
最小闭环：<一句话>
| # | 任务 | 分级 | 能力/入口文档 | 依赖 |
|---|------|------|--------------|------|
| 1 | 注册 xx 物品与页签 | Minor | pb_register_item_block | - |
| 2 | xx Attachment + 登录同步 | Major | pb_attachment / pb_network | 1 |
| 3 | HUD 魔力条 | Minor | hud_overlay_layers | 2 |
backlog（本轮不做）：<超出闭环的项>
```

确认后逐项进入实现循环：读映射文档（此时计入 1～2 篇限额）→ MCP 核实 → 写码 → 门禁 → 证据。
