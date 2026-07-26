# 模组开发项目规范 (Minecraft Modding Rules)

> [!IMPORTANT]
> **P0 为物理硬红线，违反可能直接导致游戏崩溃或存档损坏，必须优先遵守。P1 为推荐规范。**  
> 本文件服务于**通用** NeoForge 1.21.1 AI 工具包；不绑定任何具体玩法模组。

---

## 📌 项目元数据自适应规范
- **版本锚点**: Minecraft 1.21.1 + NeoForge 21.1.x（以宿主 `gradle.properties` 为准）。
- **真元数据源**: 写码或生成资源前，**必须先读取**宿主工程的 `gradle.properties` 与 `neoforge.mods.toml`，获取真实 Mod ID 与 Java 包名；禁止写死模板默认值。

---

## 🚨 部分一：P0 级别 - 物理硬红线 (Hard Constraints)

1. **ItemStack NBT 禁用**：数据读写必须 100% 使用类型安全的 Data Components。禁止 `getOrCreateTag` 等 1.20.x NBT API。
2. **Record Codec 字段顺序一致性**：Codec 字段声明顺序必须与 Java Record 构造器参数顺序 100% 一致。
3. **物理客户端隔离**：Renderer / Model / Screen 等必须隔离在 `Dist.CLIENT` 侧；通用逻辑禁止直接引用 `net.minecraft.client`。
4. **网络 Payload 线程隔离**：Handler 默认在网络线程；改世界/玩家状态必须 `context.enqueueWork(...)`。
5. **延迟解包安全**：静态字段/静态块中禁止对注册项直接 `.get()`。
6. **事件总线订阅**：`@EventBusSubscriber` 一律省略 `bus`；其监听方法必须 `static`。细节见 [event_system.md](skills/neoforge/references/event_system.md)。  
   （`modEventBus.addListener(...)` 手动注册的实例方法合法，勿与上条混淆。）
7. **MCP-first（真源优先）**：涉及原版/NeoForge API 时，写码前须用 MCP（`search_class` / `list_methods` / `read_file`）或等价源码确认签名。`references/` 与模型先验不得覆盖真源码；冲突时以源码为准。
8. **完成证据协议**：宣称「已完成 / 已修复 / 可运行」必须同时具备：  
   - 变更文件路径列表  
   - L1 编译门禁通过输出（见下方命令）  
   - L2 静态门禁通过输出（`--with-static` 已落地时强制）  
   - 涉及注册/DataGen 时：是否执行 `--with-data` 及生成物说明，并附 L2.5 `--with-assets` 对账输出  
   无上述证据禁止使用完成表述。

---

## 🛠️ 部分二：P1 级别 - 工程开发规范 (Guidelines)

1. **资源生成与 DataGen**：配方、掉落表、模型、标签等 JSON 须经 `DataProvider` + 门禁更新；禁止手写（`zh_cn.json` 与 metadata 除外）。目录名单数（`loot_table`、`recipe` 等）。交付/发布线标准见 [quality_bar.md](skills/neoforge/references/quality_bar.md)。
2. **命名空间与标签**：跨模组通用标签用 `c:`（如 `c:gems/ruby`），禁用 `forge:` / `neoforge:` 作通用标签前缀。
3. **自测纠错优先**：改码后以编译器与门禁脚本输出为准，禁止空口断言。
4. **精确最小编辑**：只做最小补丁；改 Mod ID/包名须走 `init_workspace` 脚本，禁止手工碎片化重构。
5. **任务剧本**：若存在匹配的 `playbooks/`（全集仅 5 个平台能力），先读 1 个 playbook 再读其指定的 1 个 reference。

---

## 🚀 部分三：按需加载与门禁

1. **默认路径**：`AGENTS.md` → `neoforge/`（按需 1～2 篇 reference/playbook）→ 写码 → 门禁 → 证据汇报。
2. **任务分级**：
   - **Minor**：直接写码 + 门禁，禁止空转。
   - **Major**（实体/网络/Mixin/大重构）：先短方案，确认后再写。
3. **白名单 skill**（仅此 4 个；`_archive/` **禁止加载**）：
   - `neoforge` / `workspace_setup` / `systematic-debugging`（按需）/ `task_monitor`（按需）
4. **外部双 Agent**：以用户提示与本文件为准；勿复活归档 superpowers 链。协作文档外置，禁止本机绝对路径写入工具包。
5. **门禁命令**：
   - 索引自检: `python .agents/skills/workspace_setup/scripts/check_doc_index.py`
   - 文档元数据: `python .agents/skills/workspace_setup/scripts/check_doc_meta.py`
   - 编译 L1: `python .agents/skills/workspace_setup/scripts/compile_and_repair.py`（`--with-data` 生成 JSON）
   - 编译+静态 L1+L2: 同上加 `--with-static`；资源对账 L2.5 加 `--with-assets`；专服冒烟 L3 加 `--with-server`
   - 仅 L2 / L2.5: `python .agents/skills/workspace_setup/scripts/static_gate.py` / `asset_gate.py`
   - 初始化预览/应用: `python .agents/init_workspace.py --dry-run` / `python .agents/init_workspace.py`
   - 评测批卷（仅执行 `eval/tasks/` 任务时）: `python .agents/eval/grade.py T01..T05|all`，输出计入完成证据
