# 可选：多智能体协作指针 (Optional Multi-Agent Workflow)

本文件**不是**默认开发路径的一部分。  
`.agents` 是**通用** NeoForge 1.21.1 AI 工具包；具体双 Agent 审稿/执行流程由使用方自行配置。

## 1. 默认单 Agent 路径（本包内建）

1. 挂载 [AGENTS.md](./AGENTS.md) 红线。  
2. 按需加载 `skills/neoforge` 等白名单 skill。  
3. 写码后跑门禁：  
   `python .agents/skills/workspace_setup/scripts/compile_and_repair.py`  
   （L2 落地后加 `--with-static`）。  
4. 宣称完成时提供**物理证据**（路径、diff、门禁输出），禁止空口完成。

## 2. 外部双 Agent / 审稿流程（可选）

若团队另有「审稿方 / 执行方」工作流文档：

- 将文档放在**本仓库之外**或由环境变量/本地未跟踪配置指向；  
- **禁止**在本工具包内写入某台机器的绝对路径（如 `D:\...`）；  
- 本包职责仅限：NeoForge 领域 skill、MCP 源码探针、编译/静态门禁与初始化脚本。

## 3. 证据协议（与 AGENTS.md 对齐）

响应审稿、否定问题、汇报完成时，均需提供：

- 变更文件的物理路径  
- 相关代码 diff 片段或引用  
- 门禁脚本的通过输出  

无物理证据不得宣称完成。
