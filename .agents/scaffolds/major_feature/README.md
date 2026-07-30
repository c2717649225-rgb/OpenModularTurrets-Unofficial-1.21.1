# Major 功能合同脚手架

复制 `major-feature.contract.json`，不要直接编辑模板：

```powershell
Copy-Item `
  .agents/scaffolds/major_feature/major-feature.contract.json `
  docs/features/my_feature.contract.json
```

模板故意保留 `{{...}}` 占位符，因此原样运行门禁必定失败。这可以阻止 AI 把未作出的设计决定伪装成完整合同。

填写顺序建议：

1. 最小可玩结果、服务端权威状态和 non-goals；
2. 持久化 schema 与迁移；
3. 每条网络流的验证、限流和覆盖测试；
4. client/common 边界、注册项和资产；
5. 量化性能预算；
6. 自动测试 argv、人工步骤和依赖；
7. 运行 `python .agents/run.py .agents/gates/contract_gate.py --require`。

若功能不需要持久化或网络，仍要保留对应对象并显式使用 `required: false`、`scope/format/strategy: "none"` 或空 `flows`；不能删除问题。
