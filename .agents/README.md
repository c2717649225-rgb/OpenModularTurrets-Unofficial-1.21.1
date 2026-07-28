# .agents — 通用 NeoForge 1.21.1 AI 工具包

面向 **Minecraft Java 1.21.1 + NeoForge 21.1.x** 的可复用 AI 辅助开发套件。  
可拷贝到**任意**同版本 NeoForge 工程使用；**不绑定**某一具体玩法模组。

当前 monorepo 若带有 starter 示例源码，仅作可编译宿主；产品边界是 **`.agents/` 目录本身**。

---

## 5 分钟接入

1. **挂载项目规则**  
   将 [AGENTS.md](./AGENTS.md) 注册为 AI 客户端的项目规则 / 系统提示。

2. **激活 MCP 源码探针**  
   ```json
   {
     "mcpServers": {
       "minecraft-mcp": {
         "command": "python",
         "args": [
           "/ABS/PATH/TO/PROJECT/.agents/mcp/minecraft_mcp.py"
         ]
       }
     }
   }
   ```
   将 `args` 换成**本机**该文件的绝对路径。首次使用会在 `mcp/` 下生成本机缓存（已 gitignore，勿提交）。

3. **（可选）工作区改名**  
   编辑宿主工程 `gradle.properties` 中的 `mod_id` / `mod_group_id` / `mod_name` 后，让 AI 执行初始化，或手动：  
   `python .agents/init_workspace.py`  
   （底层调用 `skills/workspace_setup/scripts/init_workspace.py`。）

4. **快速质量档位**
   ```bash
   python .agents/gates/pipeline.py --profile fast
   ```
   `fast` 跑文档信任链 + L1/L2；`major` 再强制 L0 合同、DataGen、L2.5 与 L4 GameTest，并生成验收追踪报告；`release` 再强制生成物 Git 零漂移、L3 专服启动与旗舰评测协议完整性。

---

## 目录说明

| 路径 | 用途 |
| --- | --- |
| [AGENTS.md](./AGENTS.md) | 面向 AI 的硬红线（常驻） |
| [mcp/](./mcp/) | 源码探针 `minecraft_mcp.py`（缓存/日志不入库） |
| [gates/](./gates/) | 一键质量档位与门禁：合同 L0、编译 L1、静态 L2、资源 L2.5、行为 L4、验收追踪、专服 L3、文档自检 |
| [contracts/](./contracts/) | Major 功能通用 JSON Schema；宿主实际合同放 `docs/features/` |
| [scaffolds/](./scaffolds/) | Major 合同与 GameTest 的防假绿脚手架 |
| [studio/](./studio/) | provisional 执行策略、外置哈希链证据账本、封存 Runner 与独立 Verifier |
| [eval/](./eval/) | T01–T07 微能力回归 + 六场景旗舰生产评测协议 |
| [skills/neoforge/](./skills/neoforge/) | 领域知识：SKILL 索引、references、examples、playbooks |
| [skills/workspace_setup/](./skills/workspace_setup/) | 初始化与改名（`init_workspace.py` 确定性重构引擎） |
| [skills/systematic-debugging/](./skills/systematic-debugging/) | 按需：排障 |
| [skills/task_monitor/](./skills/task_monitor/) | 按需：长任务监控 |
| [_archive/](./_archive/) | **禁读归档**（见其 README）；非默认 skill |

---

## 卫生与可移植性

- **勿提交**：`mcp/mcp_jar_cache.json`、`mcp/mcp_error.log`、任意 `__pycache__` / `.env`。  
- **勿写本机绝对路径**进工具包文档（MCP 配置示例用占位符）。  
- **勿加载** `_archive/` 内 skill，除非用户明确要求。  
- 拷贝到其他工程时：复制整个 `.agents/` 即可；MCP 会按新工程根目录重扫依赖。

---

## 默认 skill 白名单

日常仅允许：

1. `neoforge`  
2. `workspace_setup`  
3. `systematic-debugging`（按需）  
4. `task_monitor`（按需）

过程型 superpowers 等已归档，不在默认路径。

---

## 拷贝到其他工程

1. 复制整个 `.agents/` 目录到目标 NeoForge 1.21.1 工程根目录。  
2. 挂载 `AGENTS.md`，配置 MCP 指向**目标工程**内的 `minecraft_mcp.py`。  
3. 运行快速档位：
   `python .agents/gates/pipeline.py --profile fast`
4. 不要复制某个玩法模组的设计文档进 `.agents`；工具包保持平台通用。

## 质量自检（维护工具包时）

```bash
python .agents/gates/check_doc_index.py
python .agents/gates/check_doc_meta.py
python .agents/gates/static_gate.py
python .agents/gates/asset_gate.py --strict-datagen-layout --warnings-as-errors
python -m unittest discover -s .agents/gates -p "test_*.py"
python -m unittest discover -s .agents/eval -p "test_*.py"
python .agents/eval/flagship/benchmark.py validate-suite

# 在干净 Git 工作区验证 DataGen 可复现性与生成 JSON
python .agents/gates/compile_and_repair.py --with-data --with-assets --verify-data-clean
```

Major 开发先从 [合同脚手架](./scaffolds/major_feature/) 生成 `docs/features/*.json`，再从 [GameTest 脚手架](./scaffolds/gametest/) 创建真实行为测试，最后运行：

```bash
python .agents/gates/pipeline.py --profile major

# 可选：所有 v2 必选验收项都必须绑定到精确运行时 GameTest 符号
python .agents/gates/pipeline.py --profile major --strict-traceability
```

L4 会把严格源码发现、官方注解的编译后字节码和 GameTestServer 内的精确运行时符号集合绑定起来；仅伪造控制台 “all passed” 文本不能通过。普通 `major` 始终生成 `build/reports/traceability-gate.json` 供迁移观察；`--strict-traceability` 才把 v2 必选验收覆盖不足升级为阻断。该证据证明的是已声明的行为，不替代玩法设计与人工体验审核。

任务评测见 [eval/](./eval/)：T01–T07 守基础能力，`eval/flagship/` 用六个跨系统场景做固定模型版本的重复实测。它衡量自治能力，不把“能编译”包装成“能做旗舰模组”。

## 版本

见 [VERSION](./VERSION)。  
`docs_verified_set` 决定可信边界；`docs_core_set` 只是 5～10 篇 verified 文档的默认候选集，不会自动装载，实际仍按任务读取 1～2 篇。API 真源始终以宿主依赖 + MCP 源码为准。
