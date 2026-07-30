# Major 功能合同 v2 脚手架

复制 `major-feature.contract.json` 到宿主项目的 `docs/features/`，不要直接编辑模板。v2 是 provisional core；v1 合同仍受支持，不能原地改写。

v2 在完整保留 v1 行为声明的基础上增加：

- 顶层 `schema_version: 2`；
- 带 revision 与 SHA-256 的 `design_source`；
- 原子 `acceptance.criteria`，每项声明 `P0/P1/P2` 风险、观察面和 `test_ids`；
- GameTest 声明使用稳定的 `fully.qualified.Class#method` 字符串 `test_ref`；
- 迁移草稿使用机器可读 `review_required`，人工完成复核后清空。

`criteria[*].test_ids` 是验收项到可执行测试的唯一映射真源，不要在其他字段维护反向副本。`tests[*].covers` 继续描述测试自身覆盖面，以兼容 v1 行为声明。

模板故意保留阻断性 `{{...}}` 占位符。填完后运行：

```powershell
python .agents/run.py .agents/gates/contract_gate.py docs/features/my_feature.contract.json
```

迁移旧合同请使用 `migrate_v1_to_v2.py` 生成新文件；迁移器不会覆盖原文件。
