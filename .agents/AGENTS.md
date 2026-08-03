# 模组开发项目规范 (Minecraft Modding Rules)

> [!IMPORTANT]
> **P0 为物理硬红线，违反可能直接导致游戏崩溃或存档损坏，必须优先遵守。P1 为推荐规范。**  
> 本文件服务于**通用** NeoForge 1.21.1 AI 工具包；不绑定任何具体玩法模组。

---

## 📌 项目元数据自适应规范
- **版本锚点**: Minecraft 1.21.1 + NeoForge 21.1.x（以宿主 `gradle.properties` 中的精确 `neo_version` 为准；21.1.x 内也存在 API 行为分界）。
- **真元数据源**: 写码或生成资源前，先从宿主 `gradle.properties` 读取 `mod_id`、`mod_group_id` 与精确版本，再以实际 `@Mod` 主类的 `package` / Mod ID 交叉确认；发布元数据与依赖范围读取 `src/main/templates/META-INF/neoforge.mods.toml`（若宿主使用其他 Gradle 元数据模板路径，则读取其真实配置）。禁止假定 TOML 位于项目根目录或写死模板默认值。

---

## 🚨 部分一：P0 级别 - 物理硬红线 (Hard Constraints)

1. **ItemStack NBT 禁用**：数据读写必须 100% 使用类型安全的 Data Components。禁止 `getOrCreateTag` 等 1.20.x NBT API。
2. **Record Codec 工厂参数对齐**：`RecordCodecBuilder.group(...)` 的字段顺序必须匹配 `.apply(...)` 工厂函数的入参顺序。使用 `MyRecord::new` 时，这才等同于 record component / 主构造器顺序；显式适配 lambda 可以合法重排，但映射必须逐项可审计。错配可能导致编译失败、类型异常或值被静默写入错误字段。
3. **物理客户端隔离**：Renderer / Model / Screen 等必须隔离在 `Dist.CLIENT` 侧；通用逻辑禁止直接引用 `net.minecraft.client`。
4. **网络 Payload 执行线程**：`PayloadRegistrar` 默认在接收端**主线程**调用 Handler，无需重复 `enqueueWork`。只有显式使用 `.executesOn(HandlerThread.NETWORK)` 的 Handler 才运行在网络线程；此时网络线程阶段不得访问或修改 `Level` / `Entity` / 玩家状态，回写游戏状态必须通过 `context.enqueueWork(...)` 切回主线程，并处理其 `CompletableFuture` 异常。
5. **延迟解包安全**：静态字段/静态块中禁止对注册项直接 `.get()`。
6. **事件总线订阅（按 NeoForge 小版本）**：`@EventBusSubscriber` 的监听方法必须 `static`。先读取宿主精确 `neo_version`：在 **21.1.0～21.1.180** 中注解默认 `Bus.GAME`，监听 `IModBusEvent` 须显式 `bus = Bus.MOD`；从 **21.1.181 起**会自动订阅并分流到正确总线，应省略 `bus`。细节见 [event_system.md](skills/neoforge/references/event_system.md)。
   （`modEventBus.addListener(...)` 手动注册的实例方法合法，勿与上条混淆。）
7. **MCP-first（真源优先）**：涉及原版/NeoForge API 时，写码前须用 MCP（`search_class` / `list_methods` / `read_file`）或等价源码确认签名。`references/` 与模型先验不得覆盖真源码；冲突时以源码为准。
8. **完成证据协议**：宣称「已完成 / 已修复 / 可运行」必须同时具备：  
   - 变更文件路径列表  
   - L1 编译门禁通过输出（见下方命令）  
   - L2 静态门禁通过输出（`--with-static` 已落地时强制）  
   - 涉及注册/DataGen 时：是否执行 `--with-data` 及生成物说明，并附 L2.5 `--with-assets` 对账输出  
   - Major 功能：L0 合同通过 + 至少一个针对新增行为的真实 `@GameTest` + L4 全绿输出；同时附“变更/合同验收项 → `GameTestClass#method`”人工映射。L4 只证明发现的测试已执行且全绿，不自动证明测试与本次变更相关；“没有测试”不得视为通过
   - 发布声明：须有非 dry-run 的 `pipeline.py --profile release` 全绿，或等价附齐上述证据、`--verify-data-clean` 零漂移、L3 独立服务端启动与旗舰评测协议完整性输出
   无上述证据禁止使用完成表述。

---

---

## 🛠️ 部分二：P1 级别 - 工程开发规范 (Guidelines)

1. **资源生成与 DataGen**：配方、掉落表、模型、标签等 JSON 须经 `DataProvider` + 门禁更新；禁止手写（`zh_cn.json` 与 metadata 除外）。目录名单数（`loot_table`、`recipe` 等）。交付/发布线标准见 [quality_bar.md](skills/neoforge/references/quality_bar.md)。
2. **命名空间与标签**：跨模组通用标签用 `c:`（如 `c:gems/ruby`），禁用 `forge:` / `neoforge:` 作通用标签前缀。
3. **自测纠错优先**：改码后以编译器与门禁输出为准，禁止空口断言。门禁未拦住的实际错误必须回流为门禁规则或 anti_patterns 条目（登记于其尾表）方可关闭。
4. **精确最小编辑**：只做最小补丁；改 Mod ID/包名须走 `init_workspace` 脚本，禁止手工碎片化重构。
5. **模组移植 SOP (Assets First)**：在 Mod 移植或重构任务中，完成 Java 注册类编写前，必须第一动作把参考仓库的 `textures/` 目录连同子目录一键迁移至宿主工程的 `src/main/resources/assets/<modid>/textures/` 下，绝不允许先写 Java 注册而漏移材质图片。
6. **任务剧本**：若存在匹配的 `playbooks/`（全集仅 5 个平台能力），先读 1 个 playbook 再读其指定的 1 个 reference。

---

## 🚀 部分三：按需加载与门禁

1. **默认路径**：`AGENTS.md` → `neoforge/`（按需 1～2 篇 reference/playbook）→ 写码 → 门禁 → 证据汇报。
2. **任务分级**：
   - **Minor**：直接写码 + 门禁，禁止空转。
   - **Major**（实体/网络/Mixin/世界生成/存档格式/大重构）：先短方案，并用 `.agents/scaffolds/major_feature/` 在宿主 `docs/features/` 固化机器可检查合同；确认后再写。
3. **白名单 skill**（仅此 4 个；`_archive/` **禁止加载**）：
   - `neoforge` / `workspace_setup` / `systematic-debugging`（按需）/ `task_monitor`（按需）
4. **外部双 Agent**：以用户提示与本文件为准；勿复活归档 superpowers 链。协作文档外置，禁止本机绝对路径写入工具包。
5. **门禁命令**：
- 一键档位: `python .agents/run.py .agents/gates/pipeline.py --profile fast|major|release`
- 索引自检: `python .agents/run.py .agents/gates/check_doc_index.py`
- 文档元数据: `python .agents/run.py .agents/gates/check_doc_meta.py`
- Major 合同 L0: `python .agents/run.py .agents/gates/contract_gate.py --require`
- 编译 L1: `python .agents/run.py .agents/gates/compile_and_repair.py`（`--with-data` 生成 JSON）
   - 编译+静态 L1+L2: 同上加 `--with-static`；资源对账 L2.5 加 `--with-assets`
- 行为测试 L4: `python .agents/run.py .agents/gates/gametest_gate.py --require-tests --run`
- 专服冒烟 L3: `python .agents/run.py .agents/gates/compile_and_repair.py --with-server`
- 仅 L2 / L2.5: `python .agents/run.py .agents/gates/static_gate.py` / `python .agents/run.py .agents/gates/asset_gate.py`
- 初始化预览/应用: `python .agents/run.py .agents/init_workspace.py --dry-run` / `python .agents/run.py .agents/init_workspace.py`
- 评测批卷（仅评测场景，完整流程先读 `eval/README.md`）: `python .agents/run.py .agents/eval/grade.py T01..T07|all`，输出计入完成证据
- 旗舰评测协议: `python .agents/run.py .agents/eval/flagship/benchmark.py validate-suite|report RESULTS`
