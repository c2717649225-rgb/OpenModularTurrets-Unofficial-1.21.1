# Major 功能合同

这里仅存放 NeoForge 1.21.1 **Major** 功能合同的通用规范。宿主项目的实际合同位于仓库根 `docs/features/`，不得写入可复用的 `.agents` 工具包。

合同不是玩法文档，也不替代源码核验；它把跨端、存档、网络、性能和验收约束固定为实现前后的共同边界。

## 文件

- `major-feature.schema.json`：保持兼容的 v1 JSON Schema；v1 合同不声明
  `schema_version`。
- `major-feature-v2.schema.json`：provisional v2 core；显式声明
  `schema_version: 2`，增加设计来源、原子验收项、风险等级和稳定测试引用。
- `docs/features/*.json`：宿主项目的实际功能合同。
- `.agents/scaffolds/major_feature/major-feature.contract.json`：带显式占位符的可复制起点。
- `.agents/scaffolds/major_feature_v2/major-feature.contract.json`：v2 起点。
- `migrate_v1_to_v2.py`：从有效 v1 合同生成新的 v2 review draft、语义变更清单和 diff；拒绝原地迁移或覆盖既有输出。

合同必须声明：

- `id`、递增 `version` 与生命周期 `status`；
- 逻辑服务端权威状态和客户端输入策略；
- 持久化 schema、版本与迁移/失败策略；
- 每条 C2S/S2C 流的验证、限流、载荷上限和测试；
- common/client 边界与独立服务端验证；
- 注册项、标签、生成/手工资产和素材许可检查；
- 可测量的性能预算；
- 功能/模组依赖；
- argv 形式的自动测试、人工检查及明确的 non-goals。

## 门禁

```powershell
# 默认按每份合同自动分派：缺少 schema_version 为 v1，值 2 为 v2；
# 未知版本失败。没有合同时也失败
python .agents/run.py .agents/gates/contract_gate.py --require

# 检查一个文件或任意目录
python .agents/run.py .agents/gates/contract_gate.py path/to/feature.json
python .agents/run.py .agents/gates/contract_gate.py path/to/contracts

# 同时生成机器可读报告
python .agents/run.py .agents/gates/contract_gate.py --require `
  --json-report build/reports/major-feature-contracts.json

# JSON-only stdout，适合 CI
python .agents/run.py .agents/gates/contract_gate.py --require --json-report
```

目录输入会递归检查所有 `.json`，但忽略 `*.schema.json`。重叠输入会按真实路径去重。门禁仅使用 Python 标准库。

门禁验证测试声明和交叉引用，**不会执行合同中的命令**；命令必须是 argv 数组，不得写成含 `&&`、`|` 等控制符的 shell 字符串。实际执行由后续测试/CI 阶段负责。

`--require` 只表示“至少存在一份合同”，不代表合同有效；任何 JSON、Schema、语义、重复 ID 或依赖环错误仍会使进程返回 1。

迁移 v1 时始终写入新文件。所有无法从旧合同确定的设计决策会留在
`review_required`；输出保持 `draft`，复核并清空这些记录前不能进入
`approved` 或后续状态：

```powershell
python .agents/run.py .agents/contracts/migrate_v1_to_v2.py `
  docs/features/legacy.contract.json `
  --output docs/features/feature-v2.contract.json `
  --diff build/reports/feature-v1-to-v2.diff `
  --json-report build/reports/feature-v1-to-v2.json
```
