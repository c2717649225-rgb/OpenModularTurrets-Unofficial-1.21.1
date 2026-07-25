# Eval harness v0（人工 / 半自动）

用于度量「通用工具包」是否帮助 Agent 正确完成 **平台能力** 任务。  
**不是**某一玩法模组的验收。

## 范围

- 5 个任务，与 5 个 playbooks 一一对应。  
- 默认人工评分；可后续接自动 Agent runner。

## 如何跑（人工）

1. 准备干净 1.21.1 NeoForge 宿主（可复制本 starter，改 `mod_id` 后 `init_workspace --dry-run` / apply）。  
2. 挂载 `AGENTS.md`，启用 MCP。  
3. 将 `tasks/T0x_*.md` 中的 **Prompt** 交给 Agent（不要粘贴「评分要点」段）。  
4. Agent 完成后收集证据：路径列表 +  
   `python .agents/skills/workspace_setup/scripts/compile_and_repair.py --with-static`  
5. 用 [scorecard_template.md](./scorecard_template.md) 打分。

## 通过线（建议）

- 单任务：L1+L2 通过 + 无 client 泄漏 + 无玩法硬编码偏题  
- 工具包版本发布：5/5 任务至少 4 个 PASS（允许 1 个 PARTIAL）
