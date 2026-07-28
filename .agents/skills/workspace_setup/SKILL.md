---
name: workspace_setup
description: 适用于用户请求项目初始化、工作区配置、修改模组名称、重命名 Mod ID（改名）或者重构包名与主类路径等场景。
---

# 项目初始化与重构工作流规约 (Workspace Setup Skill)

当用户请求初始化新项目模板或在开发中途修改 Mod ID（重构命名空间）时，您必须遵循本技能的执行流。

## 🚨 核心执行红线

1. **绝对禁止手工碎片化重构**：严禁手动重命名资源目录、手工对齐本地化键或用文本工具批量改写类常量。您必须调用重构脚本一键完成。
2. **强制运行重构脚本**：由确定性 Python 引擎执行命名空间/资源对齐（禁止手工碎片化改名）：
   ```bash
   # 必须先预览
   python .agents/init_workspace.py --dry-run
   # 确认后再应用（工作区非 clean 时默认拒绝，可用 --force）
   python .agents/init_workspace.py
   ```
   *(根目录入口封装 `skills/workspace_setup/scripts/init_workspace.py`)*  
   覆盖：`assets/`、`data/`、mixin json、可选 `src/generated`、主类 MODID；不扫描 `.agents` 文档当宿主资源。

### Minimal 起点

需要移除 starter 示例物品、方块、配置与示例 DataGen 时使用：

```bash
python .agents/init_workspace.py --dry-run --profile minimal
python .agents/init_workspace.py --profile minimal
```

- `mod_id` 与 `mod_group_id` 会在任何写入前严格校验；绝对路径、`..`、反斜杠路径、Java 保留字、路径/符号链接逃逸都会 fail closed。
- minimal 只移除工具包可识别的 starter 源文件和与 `example_block` / `example_item` 对应的生成物，不会删除整个 `datagen/` 包；用户新增的 provider 会保留。
- 如果用户修改过 starter 文件，而移除 `EXAMPLE_*`、`Config` 或 starter provider 会留下悬空引用，脚本拒绝应用并要求先人工拆分，不会猜测或覆盖用户代码。
- 应用前脚本会自动再执行一次同参数 dry-run 预检；应用后重复执行应报告“already aligned”，不得产生第二轮变化。
3. **编译自检**：重构完成后，必须在向用户汇报前运行门禁，验证编译与静态扫描：
   ```bash
   python .agents/gates/compile_and_repair.py --with-static
   ```
   （门禁脚本统一位于顶层 [`.agents/gates/`](../../gates/)；本 skill 只保留初始化/改名引擎。）
   - L1：`compileJava`  
   - L2：`static_gate.py`（**仅**扫描宿主 `src/main/java/**/*.java`；不扫 `build/`、`.agents/`、依赖）  
   - L2.5：`asset_gate.py` 注册项↔资源对账（加 `--with-assets`；在 DataGen 之后执行）  
   - L3：专服无头冒烟（加 `--with-server`；发布前）  
   - 紧急跳过 L2：`--skip-static`（须说明原因）  
   - DataGen：再加 `--with-data`
4. **【并行重构红线】Worktree 共享冲突防御**：
   - 当同一个物理仓库存在多个活动 Git Worktree 时，**绝对禁止并行运行重构/初始化脚本（`init_workspace.py` 或大规模 rename 动作）**。
   - 重构必须保证**串行执行**，或者**仅指定唯一的 Worktree 作为重构入口**，防止公共配置文件或未提交元数据的突变破坏其他并行子智能体的编译上下文。

## 文档索引 / 元数据自检（改 neoforge 文档后）

```bash
python .agents/gates/check_doc_index.py
python .agents/gates/check_doc_meta.py
```

- 每个 `references/*.md`、`examples/*.md`、`playbooks/*.md` 必须被 `skills/neoforge/SKILL.md` 引用。  
- `playbooks/` 数量 **≤ 5**（工具包硬上限）。  
- 核心 verified 文档仅 5～10 篇（`docs_core_set.txt`）；其余 draft。  
- 失败 exit ≠ 0，须先修索引/元数据再宣称文档完成。

