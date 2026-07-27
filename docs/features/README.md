# 项目 Major 功能合同

本目录保存当前宿主项目的 Major 功能合同；`.agents/contracts/` 只保存可复用的 Schema 和通用说明。

v1 合同继续受支持；它通过缺少 `schema_version` 识别。新合同使用
provisional v2 脚手架：

```powershell
Copy-Item `
  .agents/scaffolds/major_feature_v2/major-feature.contract.json `
  docs/features/my_feature.contract.json
```

以稳定、唯一的功能 ID 命名合同，例如 `world_progression.contract.json`。脚手架中的 `$schema` 已按本目录指向：

```text
../../.agents/contracts/major-feature-v2.schema.json
```

替换全部 `{{...}}` / `TODO` / `TBD` 类占位符后运行：

```powershell
python .agents/gates/contract_gate.py --require
```

合同 ID 是依赖图节点；重命名必须同步更新其他合同的 `dependencies.features`。

不要手工覆盖旧合同来“升级”。使用迁移器生成新 draft 和 diff：

```powershell
python .agents/contracts/migrate_v1_to_v2.py `
  docs/features/legacy.contract.json `
  --output docs/features/legacy-v2.contract.json `
  --diff build/reports/legacy-v1-to-v2.diff
```

迁移结果中的 `review_required` 是必须由设计/维护者解决的决策清单；风险等级、
可观察断言和设计来源确认完毕后才能清空并推进合同状态。
