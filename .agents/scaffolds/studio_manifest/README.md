# Studio Manifest 脚手架

将 `mod-studio.json` 复制到宿主项目的 `docs/studio/mod-studio.json`，不要直接编辑模板。

v1 provisional core 只冻结项目 ID、四个版本锚点、版本化设计输入、已批准资产以及显式启用的 capability pack。不要提前加入客户端矩阵、性能预算、存档策略、发布策略或任务图。

填写规则：

- `versions` 必须来自宿主工程的真实配置，不使用工具包示例值；
- `design_sources` 与 `approved_assets` 只允许仓库相对路径；
- `sha256` 是对应文件原始字节的小写 SHA-256；
- `enabled_packs` 使用 `{ "id": "...", "schema_version": 1 }`，没有已批准 pack 时保留空数组；
- 模板中的 `{{...}}` 都是阻断性占位符，不能提交为宿主 Manifest。

示例复制命令：

```powershell
New-Item -ItemType Directory -Force docs/studio | Out-Null
Copy-Item .agents/scaffolds/studio_manifest/mod-studio.json `
  docs/studio/mod-studio.json
```
