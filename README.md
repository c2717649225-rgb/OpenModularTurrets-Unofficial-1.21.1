# NeoForge 1.21.1 AI Starter

[![Build](https://github.com/c2717649225-rgb/neoforge-1.21.1-ai-starter/actions/workflows/build.yml/badge.svg)](https://github.com/c2717649225-rgb/neoforge-1.21.1-ai-starter/actions/workflows/build.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)

## 这个项目是干什么的？

这是一个面向 **Minecraft Java 版 1.21.1 + NeoForge** 的**开箱即用开发起点**。  
你 clone / 使用本模板之后，会同时得到：

1. 一套 **1.21.1 NeoForge 模组工程模板**（能编译、能跑客户端）；  
2. 一个 **`.agents` 文件夹**：内含工具、skills、MCP 探针等，用来帮助 AI 按统一红线写代码、查源码、做编译自检。

---

## 仓库结构（两部分）

| | 部分 | 是什么 | 主要提供 |
|---|------|--------|----------|
| ① | **[`.agents/`](.agents/)** | AI 辅助开发**工具包**（可拷到其他 1.21.1 NeoForge 工程复用） | 开发红线、NeoForge skills/参考文档、源码 MCP 探针、初始化与编译自检脚本 |
| ② | **其余目录** | 模组开发**模板**（Gradle / NeoForge 工程） | `src/`、`build.gradle`、`gradle.properties`、`gradlew`、最小示例 `tutorialmod` |

```text
neoforge-1.21.1-ai-starter/
├── .agents/           ← ① AI 工具包
├── src/               ← ② 模组源码（tutorialmod starter）
├── build.gradle       ← ② 构建
├── gradle.properties  ← ② 模组元数据（mod_id 等）
├── gradlew / .bat     ← ② Gradle Wrapper
└── README.md          ← 本页（GitHub 首页）
```

- **开新模组**：整仓当模板 → 改元数据 → 初始化 → 接 MCP → 写代码。  
- **只要 AI 能力**：只拷 `.agents/` 到已有工程（版本需同为 1.21.1 / Neo 21.1.x）。  
- `tutorialmod` 只是可编译骨架，不是完整玩法。

> GitHub **Languages** 会偏 Python/JS（工具包脚本多）；模组本体仍是 **Java + Gradle**。

更细的文件级说明见下方 [`.agents` 目录说明](#agents-目录说明点开查看)（可折叠）。

---

## 核心 Skills（白名单仅 4 个）

| Skill | 用途 |
|-------|------|
| `neoforge_modding` | 1.21.1 写法、references、playbooks、MCP 检索剧本、编译门禁 |
| `workspace_setup` | 改名/初始化与门禁脚本（`python .agents/init_workspace.py`） |
| `systematic-debugging` | 按需：崩溃与诡异 bug 的根因排障 |
| `task_monitor` | 按需：长 Gradle/Git 任务防假死监控 |

> [!NOTE]
> 过程型 skills（`brainstorming`、`test-driven-development`、多代理等）已移入 `.agents/_archive/`，**非默认路径、默认禁止加载**（口径见 [`.agents/AGENTS.md`](.agents/AGENTS.md) 部分三）。交付前的编译/静态证据要求已并入 `AGENTS.md` 的「完成证据协议」。

---

## 环境要求

- **JDK 21**（`JAVA_HOME` 或 PATH；`java -version` 显示 21）
- **Python 3**（init / MCP / 自检脚本）
- Git；首次构建会拉取依赖，需网络与足够磁盘

---

## 起手四步

### 1. 配置元数据

编辑 `gradle.properties`：

```properties
mod_id=yourmod
mod_name=Your Mod Name
mod_group_id=com.yourpackage.yourmod
```

### 2. 初始化工作区

对 AI 说：`帮我初始化一下这个工作区`  

或在终端执行：

```bash
python .agents/init_workspace.py
```

按 `gradle.properties` 对齐包名、资源命名空间、主类常量、mixins 等。

### 3. 注册源码 MCP（必需）

脚本：`.agents/mcp/minecraft_mcp.py`  

在 AI 客户端的 MCP 配置中注册；步骤见 [`.agents/README.md`](.agents/README.md)。  
未接入时 AI 不应凭记忆编造原版 / NeoForge API。

### 4. 编译自检

```bash
# 默认：仅 compileJava（L1）
python .agents/skills/workspace_setup/scripts/compile_and_repair.py

# 推荐：编译 + 静态扫描（L1+L2）
python .agents/skills/workspace_setup/scripts/compile_and_repair.py --with-static

# 改了注册项或 DataGen Provider 时：DataGen + 资源对账（L2.5）
python .agents/skills/workspace_setup/scripts/compile_and_repair.py --with-data --with-assets

# 发布前：追加专用服务器无头冒烟（L3，较慢）
python .agents/skills/workspace_setup/scripts/compile_and_repair.py --with-static --with-assets --with-server
```

可选：`./gradlew runClient`

---

## `.agents` 目录说明（点开查看）

<details>
<summary><strong>目录树</strong></summary>

```text
.agents/
├── AGENTS.md           # AI 红线（请加载到客户端）
├── README.md           # 接入说明（MCP 等）
├── VERSION             # 工具包版本与目标平台
├── agent_workflow.md   # 可选：外部多智能体协作指针（非默认路径）
├── init_workspace.py   # 一键初始化入口
├── mcp/
│   └── minecraft_mcp.py
├── eval/               # 人工任务评测（T01–T05 + 记分卡）
├── _archive/           # 禁读归档（过程型 superpowers 等）
└── skills/
    ├── neoforge/             # ★ 1.21.1 写法与 references
    ├── workspace_setup/      # 改名 / 编译与静态门禁脚本
    ├── systematic-debugging/ # 按需：排障
    └── task_monitor/         # 按需：长任务监控
```

本机生成、不入库：`mcp/mcp_jar_cache.json`、`mcp/mcp_error.log`、`**/__pycache__/`（见根与 `mcp/` 下的 `.gitignore`）。

</details>

<details>
<summary><strong>根文件</strong></summary>

| 路径 | 作用 |
|------|------|
| [`AGENTS.md`](.agents/AGENTS.md) | 全局红线：Data Components、客户端隔离、网络线程、DataGen 例外、MCP 门禁、编译分级等 |
| [`README.md`](.agents/README.md) | 5 分钟接入与 MCP 注册说明 |
| [`VERSION`](.agents/VERSION) | 版本与平台锚定（如 1.1.0 / MC 1.21.1 / Neo 21.1.x） |
| [`agent_workflow.md`](.agents/agent_workflow.md) | 可选的外部多智能体协作指针；默认单 Agent 路径与证据协议见其正文 |
| [`init_workspace.py`](.agents/init_workspace.py) | 初始化入口（转发到 `workspace_setup` 下真实脚本） |

</details>

<details>
<summary><strong>mcp/ 源码探针</strong></summary>

| 文件 | 作用 |
|------|------|
| [`minecraft_mcp.py`](.agents/mcp/minecraft_mcp.py) | 本地 MCP：搜索/阅读 Gradle 缓存与项目中的 MC、NeoForge 源码（如 `search_class`、`grep_source`） |

接入方式见 [`.agents/README.md`](.agents/README.md)。缓存文件仅本机使用。

</details>

<details>
<summary><strong>skills/ 一览</strong></summary>

每个子目录有一份 `SKILL.md`，供 AI 按任务选用。**白名单仅以下 4 个：**

| 目录 | 作用 |
|------|------|
| **neoforge** | 核心：1.21.1 约束、代码骨架、`references/` 专题、`examples/`、`playbooks/`；约定 compile / `--with-static` / `--with-data` |
| **workspace_setup** | 改名与初始化流程；内含 `init_workspace.py`、`compile_and_repair.py`、`static_gate.py`、`asset_gate.py` 与文档自检脚本 |
| systematic-debugging | 按需：根因优先的系统化排错（含崩溃报告分析） |
| task_monitor | 按需：长 Gradle/Git 后台任务防假死监控 |

过程型 skills（brainstorming、writing-plans、test-driven-development、code-review 系列等）已整体移入 [`_archive/`](.agents/_archive/)，非默认路径、默认禁止加载；说明见 [`_archive/README.md`](.agents/_archive/README.md)。

</details>

<details>
<summary><strong>neoforge 与脚本细节</strong></summary>

**neoforge/**

| 内容 | 作用 |
|------|------|
| `SKILL.md` | 总入口：红线、reference 索引（一次最多读 1～2 篇专题） |
| `references/*.md` | 按主题的写法（组件、网络、实体、Mixin、DataGen 等 40+ 篇） |
| `examples/*.md` | 注册、创造栏、DataGen、配方标签等完整案例（5 篇） |
| `playbooks/*.md` | 高频任务剧本（全集固定 5 篇，禁止再增） |
| `docs_core_set.txt` | verified 核心文档清单（5～10 篇，锚定 Neo 21.1.x） |

文档中的包名可能是占位符或 `tutorialmod`；写入工程前须按当前 `gradle.properties` 替换。

**workspace_setup/scripts/**

| 脚本 | 作用 |
|------|------|
| `init_workspace.py` | 对齐 `assets/`/`data/` 命名空间、mixin json、主类 MODID（先 `--dry-run` 预览） |
| `compile_and_repair.py` | 统一入口：L1 `compileJava`；`--with-static`（L2）→ `--with-data`（DataGen）→ `--with-assets`（L2.5）→ `--with-server`（L3）；失败时给出报错上下文 |
| `static_gate.py` | L2 静态门禁：P0 崩溃写法扫描（仅扫宿主 `src/main/java`） |
| `asset_gate.py` | L2.5 资源对账：注册项 ↔ 模型/blockstate/loot/lang/贴图引用闭环 |
| `check_doc_index.py` / `check_doc_meta.py` | 文档索引完整性与核心集元数据自检 |

</details>

---

## 相关链接

- 红线全文：[`.agents/AGENTS.md`](.agents/AGENTS.md)（根目录 [`AGENTS.md`](AGENTS.md) 仅作指针）  
- 接入 Checklist：[`.agents/README.md`](.agents/README.md)  
- 许可：[`LICENSE`](LICENSE)（MIT）；MDK 原文见 [`TEMPLATE_LICENSE.txt`](TEMPLATE_LICENSE.txt)  
- 你发布的具体模组：在 `gradle.properties` 的 `mod_license` 自行声明  

---

## English

**NeoForge 1.21.1 AI Starter** — ready-to-use **Minecraft 1.21.1 / NeoForge 21.1.x** setup in two parts:

| Part | Role |
|------|------|
| **`.agents/`** | AI modding toolkit (rules, skills, local source MCP, init & compile scripts). Reusable in other 1.21.1 NeoForge projects. |
| **Rest of the repo** | Mod template (Gradle scaffold + `tutorialmod` starter). |

1. Set `mod_id` / `mod_name` / `mod_group_id` in `gradle.properties`  
2. `python .agents/init_workspace.py`  
3. Register `.agents/mcp/minecraft_mcp.py` as MCP ([guide](.agents/README.md))  
4. `python .agents/skills/workspace_setup/scripts/compile_and_repair.py`  

Requires **JDK 21** and **Python 3**. Folder details: expandable section **「.agents 目录说明」** above.
