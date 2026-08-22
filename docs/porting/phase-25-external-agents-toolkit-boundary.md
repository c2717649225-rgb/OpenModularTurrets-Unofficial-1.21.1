# Phase 25 - 外部 `.agents` 工具包边界与变更转交说明

Date: 2026-08-05

Status: external_dependency_contract

关联主方案：[Phase 25 - 风险审计与验证收口方案](phase-25-risk-audit-and-validation-plan.md)

本文件只定义 OpenModularTurrets-Unofficial 对外部 `.agents` 工具包的使用边界和反馈转交格式，不是 `.agents` 工具包的开发计划。`.agents` 的源码、门禁、测试、文档和版本迭代属于另一个项目；本项目只把它作为 NeoForge 门禁工具使用。

## 1. 所有权与固定输入

- `.agents/` 是外部工具包，不是本模组的运行时源码、资源或发布产物。
- 工具包当前版本从 `.agents/VERSION` 读取；本阶段基线为 1.3.1。
- `.agents/` 被项目 `.gitignore` 忽略。忽略状态不等于它可信，也不等于它进入了模组候选；每个审计 candidate 都必须记录工具包相对文件清单、大小和 SHA-256。
- `.agents/AGENTS.md` 是工具包真源。根目录 `AGENTS.md` 的镜像关系仍按现有项目规则处理，但 Phase 25 不执行同步、不修改真源，也不把同步工作列为模组验收项。
- 本项目不能把本地修改后的门禁结果描述为任意干净克隆都能复现的项目能力。

工具包输入固定后，以下字段必须在外部证据中保持一致：

| 字段 | 要求 |
| --- | --- |
| `toolkit_root` | 实际加载的 `.agents` 绝对路径；不得只写缓存通配符 |
| `toolkit_version` | `.agents/VERSION` 的原文值 |
| `toolkit_manifest` | 所有非运行时工具包文件的相对 POSIX 路径、大小和 SHA-256 |
| `loaded_scripts` | 本次实际调用的 `run.py` 和 gate 脚本绝对路径 |
| `candidate_id` | 与模组审计 candidate 一一对应 |
| `source_manifest_sha256` | 创建审计副本前后的工具包清单哈希 |

如果工具包清单在一个 candidate 生命周期内发生变化，立即停止该 candidate；不能在本项目内批准例外或继续追加证据。

manifest 明确排除工具包 README 所列的本机运行时文件：任意 `__pycache__/`、`*.pyc`、`mcp/mcp_jar_cache.json`、`mcp/mcp_error.log` 和 `.env`。这些文件可以在隔离副本中由 Python/MCP 生成，但不能成为工具包版本身份的一部分；排除规则本身必须写入本次证据或 runner 元数据。

## 2. 本项目允许的使用方式

主方案可以：

- 通过 `python .agents/run.py ...` 调用现有门禁；
- 在隔离审计副本中复制一份哈希完全相同的工具包，供脚本按自身路径发现 `gates/`、`skills/` 和 `contracts/`；
- 在候选 worktree 中提供同一份只读工具包运行输入。工具包文件属于 ignored external input，不能被 `git add --all` 纳入模组提交或生产 JAR；
- 保存门禁的完整 stdout/stderr、实际 argv、工作目录、退出码、超时信息、工具包版本和 manifest 哈希；
- 将工具包发现的缺口或误报写入外部反馈记录，并在模组主方案中保持保守结论。

如果 gate 脚本只能通过自身相对路径找到项目根或同目录资源，优先在隔离副本中挂载不可变工具包；如果该脚本支持 `--project-dir`，可以从外部工具包根调用并显式指定模组候选目录。无论采用哪种方式，都必须保存实际加载路径和候选目录，不能只凭终端当前目录推断。

工具包自身的 `unittest`、文档索引、reference 元数据和工具包质量线不是本模组的 Phase 25 验收项。需要确认工具包自身质量时，应在工具包所属项目执行其专用流程，并把结果作为外部版本输入，而不是在本项目修改后“顺手修好”。

## 3. 明确禁止的本项目动作

以下动作不属于模组项目执行范围：

- 修改 `.agents/gates/static_gate.py`、`asset_gate.py`、`pipeline.py`、`run.py` 或其他门禁实现；
- 在 `.agents/gates/test_*.py` 中增加 fixture、回归测试或修改测试期望；
- 创建或修改 `.agents/gates/client_isolation_allowlist.json` 等工具包白名单；
- 修改 `.agents/AGENTS.md`、`.agents/skills/`、`VERSION`、core/verified 文档集合或工具包 schema；
- 为了让 L2 变绿而把 Common→Client 例外写入外部工具包；
- 把工具包的本地补丁、生成缓存、`__pycache__`、MCP 缓存或绝对路径写进模组提交；
- 把工具包版本升级、工具包源码提交或 AGENTS 镜像同步混入模组运行时修复、DataGen 或 Release candidate。

发现上述工作有必要时，只能形成外部转交记录；本项目的 L2/P0 结论不得因此被静默降级。

## 4. 静态门禁能力缺口的外部转交

当前工具包的 Common→Client 检查可能不能覆盖：

- `net.minecraft.client.*` 的全限定引用、类型签名和非 import 位置；
- 模组自己的 `omtteam.openmodularturrets.client.*` 类型引用；
- 注释、字符串和 Java text block 中的伪客户端类名或伪注解；
- 嵌套类、匿名类、lambda 大括号和重载方法导致的范围误判；
- 宽泛的 `Dist.CLIENT` 文件级豁免；
- DataGen 客户端生成器与真正运行时客户端类之间的边界。

这些是工具包项目的改进输入，不是本模组的白名单授权。若工具包项目后续接手，目标行为至少应包括：

1. 对注释、字符串和 text block 做等长遮罩，保持源位置映射；
2. 以完整文件路径、嵌套类型和方法签名定位唯一范围；重载、重复符号、未闭合结构或无法确定范围时 fail-closed；
3. 覆盖 import、全限定引用、类型签名和初始化路径，不以单个 `Dist.CLIENT` 注解放行整文件；
4. 对 `OmtTooltips$ClientHelper`、`OmtJadePlugin#registerClient` 和 `ModNetwork#handleBeamEffect` 建立明确的正/负 fixture；其中前两个只是待审计样例，不是本项目批准的例外；
5. 将工具包版本、配置、规则 ID 和 fixture 结果绑定到工具包自己的回归证据。

在这些能力尚未进入固定工具包前，本模组采用以下处理：

- 运行现有 L2 并保存结果；
- 由模组主方案执行独立 P0 复查；
- 误报不能通过改工具包解决，只能对模组代码做物理隔离修复，或保留“待验证/未关闭”；
- 漏报不能用 L2 PASS 掩盖，必须把相关源码结论写入模组证据。

## 5. 外部反馈记录格式

反馈记录放在模组审计证据根目录或工具包项目自己的 issue/证据目录，不放进 `.agents/`：

~~~json
{
  "schema_version": 1,
  "owner": "external-agents-toolkit-project",
  "candidate_id": "phase25-<guid>",
  "toolkit_version": "1.3.1",
  "toolkit_manifest_sha256": "<sha256>",
  "gate": "static_gate|asset_gate|pipeline|other",
  "rule_id": "<rule-or-unknown>",
  "project_path": "<repository-relative-or-redacted>",
  "source_location": "<file>:<line>",
  "observed": "<actual output or finding>",
  "expected": "<desired behavior>",
  "reproduction": "<command and minimal fixture>",
  "mod_project_action": "refactor|keep-open|not-applicable",
  "status": "reported-not-fixed-here"
}
~~~

记录中不得写入本机绝对路径、JAR 私密缓存路径或未脱敏的个人目录；完整路径和日志仍可保存在本次 candidate 的外置证据目录中。

## 6. 版本变更后的重新验证

如果外部工具包项目交付了新版本：

1. 在工具包项目确认版本、变更清单、工具包自身测试和 manifest；
2. 在本项目创建全新的 candidate，不能覆盖旧 evidence；
3. 重新记录工具包完整 manifest 和加载路径；
4. 重新执行本模组的 L2、P0 复查、必要的 DataGen/Major/Release 流程；
5. 旧工具包证据只能保留为历史基线，不能支持新版本或新模组代码的完成声明。

工具包版本变化本身不会授权修改模组代码；模组代码变化也不会授权修改工具包。两边的变更必须各自建立证据链，再通过本边界文档关联。

## 7. 本项目验收口径

只要满足以下条件，主方案可以把 `.agents` 视为已固定的外部输入：

- 版本、文件清单、SHA-256 和实际加载路径齐全；
- 审计副本/候选中的工具包副本与基线清单一致；
- 没有对 `.agents` 的本地修改、白名单或版本升级；
- 门禁输出绑定到同一 `candidate_id`，并保留 stderr、退出码和工作目录；
- 工具包缺口已单独记录，且没有被 L2 PASS 或工具包白名单静默关闭；
- 模组主方案仍完成自己的 Jade、P0、DataGen、网络、Major、Release 和人工 GUI 验收。

未满足时，主方案最多标记为“部分审计”，不能宣称工具包迭代已完成，也不能把外部工具包问题当作本模组已经修复的问题。
