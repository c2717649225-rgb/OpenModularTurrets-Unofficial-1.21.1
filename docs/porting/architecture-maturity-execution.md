# OMT 架构成熟度提升执行记录

状态：`phase-5-automated-pass-pending-manual-client`

执行批次：`architecture-maturity-20260807`

项目：Minecraft 1.21.1 / NeoForge 21.1.234 / `openmodularturrets`

方案：`docs/porting/architecture-maturity-plan.md`

合同：`docs/features/architecture_maturity.contract.json`

方案 SHA-256（绑定值）：

```text
ac31b85823a4526c3cc0eadfb2ed42cf5ac0c3027bdcacce3f97cfb412661c8c
```

外部证据根目录：

```text
D:\c128\phase25-evidence\architecture-maturity-20260807\
```

## 0. 恢复协议

跨会话或上下文压缩后：

1. 读取本记录、成熟度方案和 Major 合同；
2. 计算方案 SHA-256，必须与合同 `design_source.sha256` 一致；
3. 运行 `git status --short`，保留既有脏工作区，不 reset、不 clean；
4. 读取成熟度证据根目录的 `maturity-audit.json`、`contract-test-audit.json`、`phase5-audit.json`；再读取上一批只读证据根目录 `D:\c128\phase25-evidence\architecture-optimization-20260807\` 的 `pressure-comparison.json`、`old-save-input.json`，以及项目 `build/reports/gametest-gate.json`；
5. 只从本记录的阶段状态继续，不从聊天记忆推断通过；
6. 确认 `.agents` 没有本批次修改。

## 1. 冻结不变量

- 不修改注册 ID、Mod ID、配置默认值、玩法数值、存档 key 或 Payload schema；
- `TurretBaseBlockEntity` 继续是 Base 可变状态和持久化的唯一 owner；
- `TurretHeadBlockEntity` 继续是 Head 冷却、目标展示、瞄准和自身持久化的唯一 owner；
- service 不反向依赖具体 Base/Head；common/server 不引用客户端类；
- 不新增默认启用的 Tick 诊断、网络包或日志成本；
- 不修改 `.agents`；
- 自动化完成前不启动客户端，最后只进行一轮人工客户端验收。

## 2. 阶段状态

| 阶段 | 状态 | 产物 | 退出条件 |
| --- | --- | --- | --- |
| 0 事实账本/合同 | `completed` | 方案、合同、执行记录、hash 和既有证据对账 | 14 合同 L0 通过，7/7 新执行器对账通过 |
| 1 成熟度审计 | `completed` | `tools/architecture_maturity_audit.ps1`、`maturity-audit.json` | 关键边界、证据、CI 和 `.agents` 检查全 pass |
| 2 CI 质量线 | `completed_local_review` | `.github/workflows/quality.yml`、YAML parse | workflow 命令与本地门禁一致；尚未在远端 GitHub runner 执行 |
| 3 压力/诊断治理 | `completed_evidence_reuse` | 上一批压力 compare、旧存档报告、成熟度审计 | 不引入默认运行时成本，既有证据可复核 |
| 4 扩展交接 | `completed` | `architecture-extension-guide.md`、Potato 审计 | 定义驱动扩展路径可执行 |
| 5 最终门禁 | `completed_automated` | L0/L1/L2/L2.5/L3/L4、审计和文档证据 | 自动化全绿，转入一次人工客户端验收 |
| 6 人工收口 | `pending_manual_client` | 用户一次性客户端检查 | 通过后更新合同状态；失败则按影响范围回滚 |

## 3. 阶段 0 初始事实

上一批架构优化已留下可复用证据：

- `architecture-optimization-20260807` 的 Major pipeline 已通过；
- L4 报告为 58/58，通过数 58、失败数 0；
- 压力 compare：baseline 中位 p95 `2.0660 ms`，candidate 中位 p95 `2.1470 ms`，增量 `0.0810 ms`，预算 `0.2066 ms`；
- 真实旧存档 `old-save-input.json` 状态为 `pass`，源 `level.dat` 未被修改；
- common/client 审计通过，阶段 2/5 审计通过；
- `.agents` 没有工作区差异。

这些是本批次的输入证据，不代表新的成熟度合同已经通过。

## 4. 执行日志

| 时间 | 阶段 | 命令/动作 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 2026-08-07 | 0 | 创建方案、合同、执行记录，绑定 plan SHA-256 | 通过 | `ac31b85823a4526c3cc0eadfb2ed42cf5ac0c3027bdcacce3f97cfb412661c8c` |
| 2026-08-07 | 0 | `architecture_contract_test_audit.ps1`（新合同） | 8/8 声明解析，0 errors | `contract-test-audit.json` |
| 2026-08-07 | 1 | `architecture_maturity_audit.ps1 -RequireRetainedEvidence` | pass，0 failed checks | `maturity-audit.json` |
| 2026-08-07 | 2 | PowerShell 解析 + Python YAML 解析 `.github/workflows/quality.yml` | 通过；远端 workflow 尚未实际运行 | workflow 文件 |
| 2026-08-07 | 4 | `architecture_phase5_audit.ps1` | pass | `phase5-audit.json` |
| 2026-08-07 | 5 | `contract_gate.py --require` | 14 contracts，0 errors | L0 输出 |
| 2026-08-07 | 5 | `pipeline.py --profile major --gametest-timeout 900` | PASS；L1/L2/DataGen/L2.5/L4 58/58 | `build/reports/`、pipeline log |
| 2026-08-07 | 5 | `compile_and_repair.py --with-server` | L1/L3 PASS；Server reached Done，0 ERROR | L3 输出 |
| 2026-08-07 | 5 | 复核上一批压力 compare 和旧存档报告 | pressure pass；old-save pass | `D:\c128\phase25-evidence\architecture-optimization-20260807\` |
| 2026-08-07 | 5 | 独立 CI canary 审计（无外部压力/旧存档输入） | structural checks pass；外部证据明确为 `not_evaluated_external_evidence_not_present` | `D:\c128\phase25-evidence\architecture-maturity-20260807-ci-canary\maturity-audit.json` |
| 2026-08-07 | 5 | 最终 `architecture_maturity_audit.ps1 -RequireRetainedEvidence` | pass；0 failed checks；GameTest 58/58、压力/旧存档均被读取 | `D:\c128\phase25-evidence\architecture-maturity-20260807\maturity-audit.json` |

## 5. 变更文件

本批次新增或修改文件必须逐项登记；继承的用户脏改动不在此列表中：

| 文件 | 类型 | 行为影响 | 状态 |
| --- | --- | --- | --- |
| `docs/porting/architecture-maturity-plan.md` | 方案 | 无运行时影响 | 已新增 |
| `docs/porting/architecture-maturity-execution.md` | 执行记录 | 无运行时影响 | 已新增 |
| `docs/features/architecture_maturity.contract.json` | Major 合同 | 无运行时影响 | 已新增、L0 通过 |
| `tools/architecture_maturity_audit.ps1` | 项目侧只读审计 | 无运行时影响 | 已新增、本地正式审计通过 |
| `.github/workflows/quality.yml` | CI 配置 | 只影响 CI | 已新增、YAML 解析通过；远端未执行 |
| `docs/porting/architecture-extension-guide.md` | 扩展文档 | 无运行时影响 | 已新增、扩展审计通过 |

## 6. 当前交接

自动化阶段已收口。唯一剩余事项是客户端人工验收，且本批次没有新增运行时代码：

1. 用户在一次客户端会话中检查 GUI 能量/配置、Jade、Shift tooltip、Beam、投射物、换世界、断开/重连、F3+T 和正常退出；
2. 若全部通过，将本合同从 `implementing` 更新为最终允许状态，并把阶段 6 标为通过；
3. 若发现问题，只修复可复现问题并重跑受影响门禁，不扩大范围；
4. 远端 GitHub Actions 尚未执行，当前只完成 workflow YAML 和本地同命令审查；这不是伪造的 CI 通过证据。

任何阶段失败都必须记录根因、保留失败证据和明确回滚点，不得通过降低预算、删除测试或修改 `.agents` 绕过。
