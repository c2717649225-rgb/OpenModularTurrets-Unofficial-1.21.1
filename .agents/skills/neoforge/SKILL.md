---
name: neoforge_modding
description: >
  Minecraft 1.21.1 NeoForge 模组实现。在编写/修改 Java 注册、组件、方块实体、
  网络、客户端渲染、DataGen 等时使用。先读本文件红线与阅读规则，按需只打开
  1～2 个 references 专题文档。不要通读整个 references 目录。
---

# NeoForge 1.21.1 Modding Core Engine

---

## 📖 1. 阅读与交付纪律 (MANDATORY)

### 阅读规则
1. 先阅读并严格遵守本文件「2. 🚨 1.21.1 物理硬红线与 Pre-emptive 避坑指南」与第 1 节「写码后验证」两部分内容。
2. 根据任务在「3. 索引导航」中寻找对应专题：**首先只阅读 1 个** reference 专题文件。
3. 仅当仍缺失必要 API 或有额外关联逻辑时，再阅读 **第 2 个** 专题文件。
4. **绝对禁止**一次性打开 3 个及以上的 references 专题文件，严禁为追求“全面”而通读整个目录。
5. `examples/`、`playbooks/` 与 `references/quick_skeletons.md` 同样计入「第 1 或第 2 个」限额配额。  
6. `playbooks/` **全集固定 5 篇**（见索引表）；禁止为新主题继续堆 playbook。

### 写码前（MCP-first）
* 涉及原版/NeoForge API 时：先 MCP `search_class` / `list_methods` / `read_file`（或等价读源码）确认签名，再写码。
* `references/` 与 examples 仅为地图与避坑；**与真源码冲突时以源码为准**。
* 仅 [docs_core_set.txt](docs_core_set.txt) 内 5～10 篇为 `verified`+`pin_neo`；其余 reference 视为 **draft**，不得当唯一依据。

### 写码后验证
* 汇报前必须运行 L1：`python .agents/skills/workspace_setup/scripts/compile_and_repair.py`
* L2 已落地时必须加 `--with-static`（仅扫描宿主 `src/main/java`，见 static_gate 规格）。
* 涉及 DeferredRegister 或 DataGen 时加 `--with-data`。
* 修改 references/examples/playbooks 后跑：`python .agents/skills/workspace_setup/scripts/check_doc_index.py`
* 元数据唯一真源：宿主 `gradle.properties` 与 `neoforge.mods.toml`，禁止硬编码 Mod ID/包名。
* 宣称完成须附：变更路径 + 门禁通过输出（见 `AGENTS.md` 完成证据协议）。

---

## 🚨 2. 1.21.1 物理硬红线与 Pre-emptive 避坑指南

*   **ItemStack NBT 物理禁用**：绝对禁止混用 1.20.x 的 NBT 读写（如 `getOrCreateTag()`）。必须 100% 使用类型安全的 Data Components 框架。
*   **Record Codec 顺序绝对对齐**：在 `RecordCodecBuilder.create` 中字段声明顺序必须与 Java Record 主构造器中的参数顺序 **100% 绝对一致**，否则会导致 ClassCastException 游戏崩档。
*   **物理客户端隔离**：所有 Renderer、Model、Screen 等 client 类必须隔离在专属包名下。通用逻辑绝对禁止直接引用 client 包。
*   **网络 Payload 线程安全**：Handler 默认运行在网络线程，任何涉及修改世界、玩家状态的操作必须包裹在 `context.enqueueWork(...)` 中提交给主线程。
*   **延迟解包安全**：类静态成员或静态初始化块（static block）中，**绝对禁止直接对注册项调用 `.get()`**（必须延迟在运行期或事件监听中访问）。
*   **事件总线订阅口径对齐**：使用 `@EventBusSubscriber` 订阅时**一律省略 bus 属性**（由 IModBusEvent 等事件类基类自动路由），且监听方法必须是 **static** 方法。细节规范请按需阅读 [references/event_system.md](references/event_system.md)。
*   **StreamCodec 字段及容量限制**：`StreamCodec.composite` 最多只支持 6 个字段。当字段达到 7 个及以上时，必须手动使用 `StreamCodec.of(encoder, decoder)` 进行声明。在网络同步中传输 `ItemStack` 时，StreamCodec 的泛型必须声明为 `net.minecraft.network.RegistryFriendlyByteBuf` 而非 `ByteBuf`。

### 💡 占位符自适应规则
下列 references 专题与骨架文档中的 `{{MOD_GROUP}}`、`{{MODID}}`、`{{MAIN_CLASS}}` 均为符号占位符。在写入代码前，必须先从 `gradle.properties` 和 `neoforge.mods.toml` 读取真实的包路径与 Mod ID 进行符号替换，严禁机械化复制。

---

## 🧱 3. 可复制极简骨架
当编写注册、自定义数据组件、BlockEntity 或自定义网络数据包，需要复制最短最简可行性骨架时，请阅读：
[**`references/quick_skeletons.md`**](references/quick_skeletons.md) *(注意：此文件占用 1~2 个 references 限额配额)*

---

## 📂 4. 100% 导航索引大表 (按需单篇查阅，禁止批量全部打开)

### 🧪 核心系统与注册项
| 任务类型 | 目标专题路径 (READ ONLY) |
| :--- | :--- |
| NBT 替代与自定义数据组件 (Data Components) | [references/data_components.md](references/data_components.md) |
| BlockEntity 物品栏、能力接口 (Capability) 与 Attachments | [references/capabilities_attachments.md](references/capabilities_attachments.md) |
| BlockEntity 基础、网络同步与 BlockState 保存 | [references/block_entities.md](references/block_entities.md) |
| 自定义网络数据包 (Payloads) 与 StreamCodec | [references/network_payloads.md](references/network_payloads.md) |
| 高维架构设计、模组间解耦与并发安全机制 | [references/architecture_design.md](references/architecture_design.md) |
| 容器 GUI 菜单、屏幕 (Menus, Screens) | [references/menus_screens.md](references/menus_screens.md) |
| 配置文件 (Config specs) 与 TOML 重载监听 | [references/configuration.md](references/configuration.md) |
| 模组访问转换器 (Access Transformers) 配置 | [references/access_transformers.md](references/access_transformers.md) |
| 常见模组开发反模式与规避指南 (Anti-Patterns) | [references/anti_patterns.md](references/anti_patterns.md) |

### 🧱 方块、物品与掉落物定制
| 任务类型 | 目标专题路径 (READ ONLY) |
| :--- | :--- |
| 自定义方块、红石、门与作物 | [references/custom_blocks.md](references/custom_blocks.md) |
| 自定义装备、剑、护甲、弓与工具 | [references/custom_gear.md](references/custom_gear.md) |
| 物品基本属性、2D 动画与悬停属性 | [references/item_properties.md](references/item_properties.md) |
| 方块状态、物品模型 JSON 与 掉落表 DataGen | [references/blockstates_models_datagen.md](references/blockstates_models_datagen.md) |
| 自定义配方序列化器 (Recipe Serializer) & MapCodecs | [references/custom_recipes.md](references/custom_recipes.md) |
| 配方/标签 DataGen (Recipes/Tags DataGen) | [references/recipes_standard_datagen.md](references/recipes_standard_datagen.md) |
| 物品悬停提示 (Tooltips)、Lore 与格式化 | [references/item_tooltips.md](references/item_tooltips.md) |
| 自定义伤害类型 (Damage Types) 与伤害源 | [references/damage_types.md](references/damage_types.md) |
| 方块/物品颜色处理器 (Color Handlers) | [references/color_handlers.md](references/color_handlers.md) |
| 声音注册与触发 (Sounds) | [references/sounds.md](references/sounds.md) |

### 🚀 高级特性 (Mixin、世界生成、流体与实体)
| 任务类型 | 目标专题路径 (READ ONLY) |
| :--- | :--- |
| Configured Features & Placed Ores 矿石生成 | [references/worldgen_ores.md](references/worldgen_ores.md) |
| Mixin 注入、重定向 (Redirect) 与 Accessor | [references/mixins.md](references/mixins.md) |
| 实体模型 (BBModel) 与 渲染器 (BBModel Renderers) | [references/custom_entity_models.md](references/custom_entity_models.md) |
| 实体属性、AI 行为树与实体 tick 优化 | [references/custom_entities.md](references/custom_entities.md) |
| BlockEntity 专属特殊渲染器 (BER) | [references/block_entity_renderers.md](references/block_entity_renderers.md) |
| 客户端 HUD overlay 渲染图层 | [references/hud_overlay_layers.md](references/hud_overlay_layers.md) |
| 自定义维度、传送门 (Dimensions, Portals) | [references/custom_dimensions.md](references/custom_dimensions.md) |
| 自定义生物群系 (Biomes) 与气候属性 | [references/custom_biomes.md](references/custom_biomes.md) |
| 村民交易 (Villager Trades) 与职业等级 | [references/villager_trades.md](references/villager_trades.md) |
| 全局掉落修改器 (GLM) | [references/global_loot_modifiers.md](references/global_loot_modifiers.md) |
| 快捷键绑定与输入映射 (Keybindings) | [references/keybindings_input.md](references/keybindings_input.md) |
| JEI 模组集成与配方展示 | [references/jei_integration.md](references/jei_integration.md) |
| 事件总线监听机制与优先级 (Event System) | [references/event_system.md](references/event_system.md) |
| 自定义粒子效果与粒子提供器 (Particles) | [references/custom_particles.md](references/custom_particles.md) |
| 自定义流体、流体罐与流体桶 (Fluids) | [references/custom_fluids.md](references/custom_fluids.md) |
| 保存与加载世界数据 (SavedData) | [references/saved_data.md](references/saved_data.md) |
| 自定义附魔、数据驱动 RegistrySetBuilder | [references/custom_enchantments.md](references/custom_enchantments.md) |
| 药水效果与炼药配方注册 (Brewing) | [references/potions_brewing.md](references/potions_brewing.md) |
| 进度与成就 (Advancements) DataGen | [references/advancements_datagen.md](references/advancements_datagen.md) |
| 自定义指令 (Commands) 与参数解析器 | [references/custom_commands.md](references/custom_commands.md) |
| 数据映射表 (Data Maps) 驱动元数据 | [references/data_maps.md](references/data_maps.md) |

### 💡 蓝图与完整案例
| 任务类型 | 目标案例路径 (READ ONLY) |
| :--- | :--- |
| 标准物品/方块注册完整实现 | [examples/registration_example.md](examples/registration_example.md) |
| 创造模式物品栏 Tab 配置 | [examples/creative_tab_config_example.md](examples/creative_tab_config_example.md) |
| 掉落物、状态与方块模型 DataGen 案例 | [examples/datagen_example.md](examples/datagen_example.md) |
| 合成配方、物品 Tags 标签 DataGen 案例 | [examples/recipes_tags_example.md](examples/recipes_tags_example.md) |
| 多端发布平台解耦架构 (Platform Decoupling) | [examples/platform_decoupling_example.md](examples/platform_decoupling_example.md) |

### 📋 任务剧本 Playbooks（全集仅 5，禁止再增）
> 优先于通读 references。每个 playbook 计入「1～2 篇」限额。更多主题请查上方 references，**不要**再加第 6 个 playbook。

| 任务类型 | Playbook (READ ONLY) |
| :--- | :--- |
| 注册物品/方块/创造页签 | [playbooks/pb_register_item_block.md](playbooks/pb_register_item_block.md) |
| 自定义 Data Component | [playbooks/pb_data_component.md](playbooks/pb_data_component.md) |
| 网络 Payload C2S/S2C | [playbooks/pb_network_payload.md](playbooks/pb_network_payload.md) |
| BlockEntity 保存与同步 | [playbooks/pb_block_entity_sync.md](playbooks/pb_block_entity_sync.md) |
| Attachment 玩家/实体数据 | [playbooks/pb_attachment_player_data.md](playbooks/pb_attachment_player_data.md) |

---

## 🛠️ 5. MCP 探针调用简要三步指南 (READ ONLY)
当需要快速阅读或反查 Minecraft 源码或 NeoForge 依赖源码时：
1. **检索定位**：调用 `search_class` 或者是 `grep_source` 定位特定名称或引用（若返回 suggested_read 则直接读取 suggested_read 下的真源码绝对路径）。
2. **偏移导航**：对大型类（如 `LivingEntity`），调用 `list_methods` 快速查明方法签名偏移行号。
3. **范围读取**：调用 `read_file` 传入真源码绝对路径并配置 `start_line` / `end_line` 读取代码（避开 1500 行软上限限制）。
