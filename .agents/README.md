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

4. **编译门禁**  
   ```bash
   python .agents/gates/compile_and_repair.py --with-static
   ```
   分级：`--with-static`（L2 静态红线扫描）→ `--with-data`（DataGen）→ `--with-assets`（L2.5 注册项↔资源对账，DataGen 后执行）→ `--with-server`（L3 专服无头冒烟，发布前）。

---

## 目录说明

| 路径 | 用途 |
| --- | --- |
| [AGENTS.md](./AGENTS.md) | 面向 AI 的硬红线（常驻） |
| [mcp/](./mcp/) | 源码探针 `minecraft_mcp.py`（缓存/日志不入库） |
| [gates/](./gates/) | 门禁脚本：编译 L1、静态 L2、资源对账 L2.5、专服冒烟 L3、文档自检；附崩溃分诊 `crash_triage.py`（排障入口，非门禁） |
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
3. 运行 L1（建议 L2）：  
   `python .agents/gates/compile_and_repair.py --with-static`  
4. 不要复制某个玩法模组的设计文档进 `.agents`；工具包保持平台通用。

## 质量自检（维护工具包时）

```bash
python .agents/gates/check_doc_index.py
python .agents/gates/check_doc_meta.py
python .agents/gates/static_gate.py
python .agents/gates/asset_gate.py
```

任务评测见 [eval/](./eval/)：Prompt 交给 Agent，`python .agents/eval/grade.py T0x` 一键批卷（PASS/PARTIAL/FAIL），主观项人工复核。

## 版本

见 [VERSION](./VERSION)。  
`docs_core_set` 限制 verified 文档 5～10 篇；API 真源以宿主依赖 + MCP 源码为准。
