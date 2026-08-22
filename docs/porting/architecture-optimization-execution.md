# OMT 架构优化执行记录

状态：`phase-7-pending-manual-client`

执行批次：`architecture-optimization-20260807`

项目：Minecraft 1.21.1 / NeoForge 21.1.234 / `openmodularturrets`

方案：`docs/porting/architecture-optimization-plan.md`

合同：`docs/features/architecture_optimization.contract.json`

外部证据根目录：

```text
D:\c128\phase25-evidence\architecture-optimization-20260807\
```

## 0. 恢复协议

上下文压缩或跨会话后，严格按以下顺序恢复：

1. 读取本执行记录和方案文档；
2. 读取 `docs/features/architecture_optimization.contract.json`，确认其 `design_source.sha256` 与方案当前 hash 一致；
3. 运行 `git status --short`，保留既有脏工作区，不清理、不重置；
4. 读取外部证据根目录下最新的 `phase-status.json`、`contract-test-audit.json`、`baseline-manifest.json`、`cache-invalidation-ledger.json`、`client-import-audit.txt` 和 `sync-path-ledger.json`；
5. 读取本文件最后的“当前阶段交接”，从第一个未完成阶段继续；
6. 不从聊天记录推断某个门禁、存档或客户端测试已经通过。

每次阶段结束都必须更新本文件：状态、时间、变更文件、命令、证据、未决风险、回滚点和下一步。

## 1. 固定不变量

以下内容在本批次冻结，除非另立合同并重新审核：

- 注册 ID、Mod ID、配置默认值和玩法数值不变；
- Base、Head、Projectile 的现有存档 key 不变，旧 `active`/`mode` 读取兼容路径不删除；
- Payload 类型、注册 ID、字段顺序、编码和客户端视觉语义不变；
- `TurretBaseBlockEntity` 是 Base 能量、库存、附件、所有权、统计和持久化状态的唯一所有者；
- `TurretHeadBlockEntity` 是 Head 冷却、目标展示、瞄准和 Head 持久化状态的唯一所有者；
- 服务层不持有或回调 `TurretHeadBlockEntity`；
- common/server 不加载客户端类；
- 所有 Level、Entity、玩家、Capability 和 BlockEntity 访问留在受支持的服务器主线程路径；
- `.agents/` 不修改、不格式化、不重构、不纳入完成条件；
- 既有缓存只有在阶段 0 证明失效缺口后才允许修改；没有缺口时 2B 可以零代码变更通过。

## 2. 阶段状态总表

| 阶段 | 状态 | 变更范围 | 证据 | 回滚点 |
| --- | --- | --- | --- | --- |
| 0 基线/合同/账本 | `completed_with_deferred_manual_client` | 合同、执行记录、项目侧审计脚本、外部证据 | `phase-status.json`：缓存无开放失效缺口、持久化 ledger pass、client import 已清零、baseline pass；人工客户端留到阶段 7 | 删除本阶段新增项目文档/工具，不动模组代码 |
| 1 窄上下文/端口 | `completed` | 目标/战斗服务的最小输入与服务器适配器 | L0/L1/L2、L4 58/58、真实旧存档往返；原计划状态视图进一步收窄为实际需要的 `range` 标量后再次 L4 58/58 | 恢复服务签名并删除阶段 1 接口 |
| 2A 所有权/信任 | `completed_audit_only` | 纯规则和显式服务器适配 | `phase2-audit.json`：现有 `OwnershipRules`/`TargetingRules` 和 Base 查询边界足够，无新增包装层 | 恢复阶段 1 查询参数 |
| 2B 既有缓存复核 | `completed_with_minimal_lifecycle_patch` | 先审计，必要时最小补丁 | `cache-invalidation-ledger.json`、`phase2-audit.json`；Base `setRemoved()` 清理派生附件视图 | 删除 `setRemoved()` 补丁并恢复原缓存实现 |
| 2C 资源消耗 | `completed_audit_only` | 能量/资源规则，不重包同步 | `phase2-audit.json`；纯公式仍在 `TurretUpgradeRules`，事务仍唯一归 Base，同步三路径未重包 | 恢复阶段 1 资源参数 |
| 2D 视觉同步 | `optional` | 只有发现清晰收益才做 | 未开始 | 不执行 |
| 3 Head/目标/战斗 | `completed` | 只做有证据的边界收紧 | 服务无 Head/BlockEntity 反向依赖；L0/L1/L2/L4、旧存档通过 | 按服务回滚 |
| 4 网络/客户端边界 | `completed_automation_pending_manual` | 精确 client bridge 和生命周期 | common client import pass；`ClientTooltipUtil`、F3+T reload listener、断开/换世界清理；L3/L4 通过 | 恢复旧桥接和客户端清理入口 |
| 5 扩展性证明 | `completed_audit_only` | 土豆炮依赖路径审计 | `phase5-audit.json`：定义→注册→战斗映射，Head/Base 无 Potato 分支；土豆 GameTest/压力 fixture 存在 | 仅保留既有代码 |
| 6 性能/生命周期 | `completed` | 同机三次 baseline/candidate、JFR 和生命周期复核 | 最终 candidate p95 `2.3227 / 2.1470 / 2.0960 ms`，中位 `2.1470 ms`；相对 baseline `2.0660 ms` 增量 `0.0810 ms`，低于 `0.2066 ms` 预算；旧存档往返 pass | 回滚仅限性能相关补丁，保留已验证的边界/客户端生命周期修复 |
| 7 最终收口 | `pending_manual_client` | 自动化后唯一一轮人工客户端验收 | 自动化已收口；待用户一次性检查 GUI、Jade、tooltip、Beam、投射物、换世界、断开重连、F3+T、正常退出 | 合同保持 `implementing`，人工通过后再收口 |

## 3. 阶段 0 执行清单

### 3.1 合同和执行器对账

合同中的 `test_id` 是语义索引，不能只用 `rg` 搜索 ID 字符串判断测试存在。必须按 `test_ref` 和 `command` 解析：

| test_id | 实际执行器 | 当前对账状态 |
| --- | --- | --- |
| `architecture_optimization_contract_audit` | `tools/architecture_contract_test_audit.ps1` | `executor_present`；审计结果 `pass`，执行状态仍单独记录 |
| `architecture_optimization_behavior_gametest` | `OpenModularTurretsGameTests#potatoTurretAcquiresVisibleHostileAndFires` | `executor_present`；待本批次 L4 |
| `architecture_optimization_state_roundtrip` | `OpenModularTurretsGameTests#statePersistence` | `executor_present`；待本批次 L4 |
| `architecture_optimization_old_save_load` | `tools/architecture_refactor_pressure.ps1 -Mode old-save-check` | `executor_present`；本批次真实旧存档 `pass` |
| `architecture_optimization_boundary_static` | `.agents/gates/static_gate.py` | `executor_present`；待代码阶段门禁 |
| `architecture_optimization_attachment_behavior_gametest` | `OpenModularTurretsGameTests#turretAttachmentLimits` | `executor_present`；待本批次 L4 |
| `architecture_optimization_capacity_behavior_gametest` | `OpenModularTurretsGameTests#everyBaseTierHonorsTurretCapacity` | `executor_present`；待本批次 L4 |
| `architecture_optimization_pressure` | `tools/architecture_refactor_pressure.ps1` baseline/candidate/compare | `executor_present`；当前 baseline `pass`，candidate 尚未开始 |
| `architecture_optimization_dedicated_server` | `.agents/gates/compile_and_repair.py --with-server` | `executor_present`；待本批次阶段门禁 |
| `architecture_optimization_asset_contract` | DataGen/static/asset gates | `executor_present`；涉及资源变更时执行 |

`contract-test-audit.json` 中的 `executor_present` 只代表执行器解析成功；`execution_status=not_evaluated` 不代表测试通过。真实通过状态只能来自相应命令报告，并在本文件中单独登记。

### 3.2 账本和静态审计

阶段 0 证据状态：

- `baseline-manifest.json`：已生成，记录当前已优化脏工作区的源文件、环境和 Git 状态；
- `cache-invalidation-ledger.json`：已复核三组既有缓存的键、计算入口和失效链；`setRemoved()` 清理派生缓存已补齐，当前无开放的必需生命周期钩子；
- `client-import-audit.txt`：已重新扫描 common/server Java 文件，结果为 `pass`、0 命中；`OmtTooltips` 的 `Screen` 访问已移入客户端专属 `ClientTooltipUtil`；
- `sync-path-ledger.json`：已生成；`markForSave`、`markForSaveAndSync`、`sendBlockEntityUpdateToTracking` 三条既有语义路径已登记，阶段 2C 不重复包装；
- `persistence-key-ledger.json`：已生成；Base/Head/Projectile 的观测 tag 访问与冻结 save/load、update-tag 集合对账通过；
- `dependency-graph.md`：已更新；服务不再反向依赖 `TurretBaseBlockEntity`，Head 只向服务传入窄上下文，世界/权限查询保留在显式服务器适配器；
- `phase-status.json`：已重新生成，阶段 0 仅保留“人工客户端延后到阶段 7”的状态；
- `contract-test-audit.json`：已执行；10/10 合同 `test_id` 均解析到真实方法、脚本或门禁命令，执行器存在与实际通过状态分开记录。

账本和运行证据均位于：

```text
D:\c128\phase25-evidence\architecture-optimization-20260807\
```

### 3.3 性能基线

基线必须使用当前已优化工作区，固定 100 个 Tier 5 基座、100 个土豆炮头和 100 个固定目标。baseline 至少三次，保持同一机器、Java、JVM 参数、配置和运行入口。

阶段 0 只冻结基线，不宣称候选改动收益。每次运行必须保存：

- 原始 GameTest stdout/stderr；
- fixture mean/p50/p95/p99/max 和样本数；
- JFR 原文件、`minecraft.ServerTickTime`/`jdk.ExecutionSample` 摘要；
- run manifest 和源码 hash；
- 退出码、GameTest 结果和异常摘要。

## 4. 阶段 0 实际结果

### 4.1 已完成

- `architecture_contract_test_audit.ps1` 已执行：10 个合同声明均解析到真实方法、脚本或门禁命令；
- Major 合同门禁已通过：13 份合同、0 errors；设计文档 hash 与合同一致；
- 三次当前工作区 baseline 已完成，每次 fixture 200 样本、退出码 0、JFR 已附着；p95 为 `2.0660 / 1.9458 / 2.3127 ms`，基线中位数 p95 为 `2.0660 ms`；
- 真实旧存档检查已通过：复制副本两次专服加载/保存/重载均优雅退出，`save_observed=true`、`reload_observed=true`，源存档未被修改；
- 缓存生命周期审计通过：既有 ammo/range/capacity 缓存未重复创建，`setRemoved()` 清除派生附件视图；
- common/server 客户端引用审计通过；`ClientTooltipUtil` 承担惰性客户端桥接，`ClientGameEvents` 增加 F3+T reload 清理入口；
- 阶段 1 的 `TurretTargetingService`/`TurretCombatService` 不再依赖具体 Base/Head，目标规则输入只传实际需要的 `range` 标量，世界/权限查询仍由 Base 显式适配；
- `phase2-audit.json` 和 `phase5-audit.json` 均为 pass；2A/2B/2C 未重复制造已有所有权、缓存、同步或资源事务包装层，土豆炮路径审计通过；
- L0、L1、L2、L2.5、DataGen、L3 和独立 L4 已通过；最新独立 L4 为 58/58；
- `.agents/` 未修改；既有脏工作区继续由 manifest、源文件 hash 和外部证据根目录隔离记录。

### 4.2 未决风险

- 自动化性能预算已关闭：最终三次 candidate p95 为 `2.3227 / 2.1470 / 2.0960 ms`，中位 `2.1470 ms`；baseline 中位 `2.0660 ms`，增量 `0.0810 ms`，compare 判定 `pass=true`；
- JFR 的 OMT 栈样本偏少，仍只作为独立佐证，不能把稀疏采样解释为“没有热点”；固定 fixture 的直接计时和三次一致性是本阶段主要判据；
- 最终客户端验收尚未执行，必须由用户完成唯一一轮 GUI/Jade/tooltip/Beam/投射物/换世界/断开重连/F3+T/正常退出检查；
- 合同追踪报告中仍可能存在与本批次非一一对应的提示性 uncovered 条目；它们不替代 L0/L1/L2/L4，也不应被当成行为失败；
- 工作区仍是继承的脏状态，不得通过 reset/clean 消除差异。

### 4.3 阶段 0 退出条件

阶段 0 已达到“自动化证据完成、人工客户端延后”的退出状态：

- 新合同 L0 通过且设计文档 hash 一致；
- 合同执行器对账通过，所有 required test ID 都有真实方法、脚本或门禁命令；
- Base/Head/Projectile persistence key ledger、缓存失效 ledger 和三条保存/同步路径 ledger 均完成；
- common/server 客户端引用审计通过；
- baseline 三次完成，数据和 JFR 完整；
- 当前批次未改变注册 ID、存档 key、Payload schema、玩法数值或客户端/服务端语义；
- `.agents/` 没有任何修改；
- 人工客户端检查明确保留到阶段 7，不被“服务器/门禁通过”替代。

## 5. 当前阶段交接

当前工作位于阶段 7：代码架构切片、生命周期边界、旧存档闭环、Major 自动化门禁和固定压力预算均已收口；唯一剩余事项是用户进行一轮集中式客户端人工验收。

下一步顺序：

1. 自动化证据已完成：Major pipeline、真实旧存档往返和最终压力 compare 均为 pass；对应原始日志和 JSON 保存在外部证据根目录；
2. 不再启动客户端或拆分测试；请用户在一次客户端会话中集中检查 GUI 能量/配置、Jade、Shift tooltip、Beam、投射物、换世界、断开/重连、F3+T 和正常退出；
3. 若人工检查发现问题，只针对可复现问题做最小修复，并从受影响的门禁/旧存档/压力证据重新开始；若全部通过，更新合同状态与本记录，形成最终交付证据；
4. 在人工验收完成前，不宣称本批次最终完成；所有已通过的自动化证据仍保持有效且可独立复核。
