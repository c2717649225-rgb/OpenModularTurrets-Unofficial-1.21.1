# Eval harness v1（机器批卷 + 人工复核）

用于度量「通用工具包」是否帮助 Agent 正确完成 **平台能力** 任务。  
**不是**某一玩法模组的验收。

## 范围

- 5 个任务，与 5 个 playbooks 一一对应。  
- **批卷已半自动化**（`grade.py`）；「让 Agent 做题」仍由人触发（自动 runner 可后续接）。

## 如何跑

1. 准备干净 1.21.1 NeoForge 宿主（可复制本 starter，改 `mod_id` 后 `init_workspace --dry-run` / apply），**git commit 一个基线**。  
2. 挂载 `AGENTS.md`，启用 MCP。  
3. 将 `tasks/T0x_*.md` 中的 **Prompt** 交给 Agent（不要粘贴「评分要点」段）。  
4. Agent 完成后**机器批卷**：  
   `python .agents/eval/grade.py T0x`（单题）或 `python .agents/eval/grade.py all`  
   - 以 `git diff` 新增代码 + 未跟踪 `.java` 为语料，防止 starter 既有代码假阳性；基线非 HEAD 时加 `--since <ref>`  
   - 自动判 PASS / PARTIAL / FAIL（含 L1+L2 门禁；退出码 0/2/1），断言与 API 特征已对照 neoforge-21.1.234 源码  
5. 人工复核机器管不到的主观项（命名合理性、是否偏题），把结果抄入 [scorecard_template.md](./scorecard_template.md)。

## 通过线（建议）

- 单任务：L1+L2 通过 + 无 client 泄漏 + 无玩法硬编码偏题  
- 工具包版本发布：5/5 任务至少 4 个 PASS（允许 1 个 PARTIAL）
