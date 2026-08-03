# Changelog — .agents/ 工具包

本文件记录 **AI 开发工具包（`.agents/` 目录本身）** 的版本变更与破坏性变更，**不记录**宿主模组的玩法变更。
本文件位于 `.agents/` 内，随工具包一起被拷贝分发；升级工具包前请先阅读本文件，避免本地定制被静默覆盖。

版本号锚点见 `.agents/VERSION`（当前 1.3.1 / Minecraft 1.21.1 / NeoForge 21.1.x）。

## [Unreleased]

### 新增
- **CI 精准触发**：`build.yml` 增加 `paths-ignore`，文档类路径（`docs/**`、`*.md`、`.agents/_archive/**`、`.agents/skills/**/references/**`、`.agents/*.md`、`.github/workflows/agents.yml`）的改动不再触发 Gradle + GameTestServer 全量构建；`.agents/gates/**` 等代码改动仍照常触发全量验证（门禁代码改动需要真实环境回归）。
- **Windows 全量门禁**：`agents.yml` 的 `toolkit-windows` job 补齐全部纯 Python 门禁（doc index / doc meta / static / asset / contract）与 eval 单测，消除 Windows 路径分隔符、编码、ANSI 输出的跨平台风险盲区。
- **行数护栏 `meta_gate.py`**：标准库实现（零第三方依赖），对 `.agents/**/*.py` 单文件行数设 >1500 行 warning、>2500 行 fail，防止门禁脚本无限膨胀。已接入 `agents.yml`。
- **证据账本接入**：`pipeline.py` 支持环境变量 `TOOLKIT_EVIDENCE_LEDGER`——设置时把 `--json-report` 报告内容 sha256 追加为一条 `PIPELINE_RESULT` 事件；未设置时行为不变（向后兼容），release 未配置时仅 warning 不 fail，不破坏"拷贝即用"。
- **轻量可移植性 job**：`agents.yml` 新增 `portability` job，把 `.agents/` 拷贝进临时骨架宿主（含 `gradle.properties` 桩与最小 `src/main/java`），跑纯 Python 门禁，验证"拷贝即用"的产品边界；不跑含 L1 Gradle 编译的 `--profile fast`，控制 CI 成本。
- **`check_update.py`**：对比上游新版与本地定制版两个 `.agents/` 目录，输出"上游改了哪些 / 你改了哪些 / 冲突文件清单"，升级前运行一次；内容比较做 CRLF 规范化，Windows 下不会因换行符误报整文件改动。
- **`environment_check.py`**：环境自检工具——诊断默认 `python` 版本、PATH 中 python 条目顺序与 py launcher 可用版本，输出分级结论与安全修复指引（`PY_PYTHON` / 系统 PATH 重排 / `run.py` 兜底）；标准库实现。
- **`run.py` 兜底提示**：当默认 `python` 过旧、启动器不得不选用兜底解释器时，向 stderr 打印一次性环境修复提示（保持 3.6 兼容语法，功能与退出码不变）。

### 破坏性变更
- 无（本次全部为增量变更，向后兼容）。

## [1.3.1] — 基线

首个正式记录版本。此前工具包版本间差异无留存记录；本条目仅作基线锚点。
- 平台锚定：Minecraft 1.21.1 / NeoForge 21.1.x
- 提供：门禁体系（L0 合同 / L1 编译 / L2 静态 / L2.5 资源 / L3 专服 / L4 GameTest）、MCP 源码探针、初始化脚本、四档 pipeline 配置。
