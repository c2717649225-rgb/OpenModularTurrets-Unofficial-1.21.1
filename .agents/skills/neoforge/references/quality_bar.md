---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-26
---
# 发布质量线 (Quality Bar) — 「优质模组」的可验收定义

> **何时读本文**：宣称一个功能「完成」之前；准备发布（CurseForge / Modrinth）之前。
> 本文与 P0 红线的关系：P0 管「不崩」，本文管「优质」。每条标准都绑定**验收方式**——
> 无法验收的标准不写入本文。

---

## 1. 两条线

| 线 | 含义 | 最低门禁组合 |
| :--- | :--- | :--- |
| **MVP 线** | 功能在游戏里可用、不崩、不残 | `compile_and_repair.py --with-static --with-assets`（改注册/DataGen 时加 `--with-data`） |
| **发布线** | 可上架公共平台，专服可跑，双语完整 | MVP 线全部 + `--with-server` + 本文 §3 人工项全过 |

---

## 2. 自动验收项（门禁强制）

| # | 标准 | 验收方式 |
| :--- | :--- | :--- |
| A1 | 每个注册 item/block：模型、blockstate、loot_table、`en_us` 键 100% 齐全，模型引用无悬空 | **L2.5** `asset_gate.py`（error 级零容忍） |
| A2 | 无 P0 崩溃写法（NBT/静态解包/Codec 顺序/线程/EventBus/客户端泄漏/StreamCodec 超限） | **L2** `static_gate.py`（error 级零容忍） |
| A3 | 专用服务器（Dedicated Server）可无头启动到 `Done` 且无 FATAL | **L3** `--with-server` |
| A4 | 数据资源（配方/掉落/标签/进度）全部经 DataGen 生成，`runData` 可复现 | `--with-data` 后 git diff 干净 |
| A5 | `zh_cn.json` 存在（工具包默认双语；asset_gate warning 级提示） | **L2.5** warning 清零 |

## 3. 人工/AI 自查项（发布前逐条过）

| # | 标准 | 验收方式 |
| :--- | :--- | :--- |
| M1 | 玩法数值魔数（伤害/概率/冷却等）进 `ModConfigSpec`，不硬编码 | grep 自查 + review |
| M2 | 无 `@Overwrite` Mixin；修改原版优先级：事件 > AT > `@Inject` 最小注入（见 anti_patterns §11） | grep `@Overwrite` 为零 |
| M3 | 无 anti_patterns §7~10 性能反模式（热路径分配 / 无节流 ticker / 重事件监听 / 未缓存 capability） | 逐条对照自查 |
| M4 | 日志走 mod 专属 slf4j logger；无 `System.out.println`；`--with-server` 输出的 ERROR 行逐条有解释 | grep + L3 输出 review |
| M5 | `neoforge.mods.toml`：描述、作者、license、依赖版本范围（neoforge/minecraft）真实完整 | 人工核对 |
| M6 | 每个新物品/方块可在创造模式页签中找到（自有 tab 或挂接原版 tab） | 人工/AI 对照注册清单 |
| M7 | 跨模组通用标签用 `c:` 命名空间（P1-2），不发明私有等价标签 | grep 自查 |
| M8 | （强化项）Major 玩法功能附带 GameTest，`gradlew runGameTestServer` 全绿 | GameTest 输出 |

---

## 4. 与完成证据协议的衔接

- 宣称「MVP 完成」：附 A1/A2 门禁输出（+ 涉及 DataGen 时 A4）。
- 宣称「可发布」：附 A1~A5 全部输出 + §3 逐条自查结论（过/不过/不适用 + 一句理由）。
- 任何一条「不适用」都必须给出理由（例：纯 API 库模组无注册物品 → A1 不适用）。

> 本文是 `AGENTS.md` P1 的延伸细化；与门禁脚本行为冲突时，以脚本实际输出为准并回改本文。

## 5. 路线图（升级触发条件，防「按需」变「永不」）

- **GameTest → gates（L4）**：触发条件 = **第一个真实 Major 玩法模组用本工具包开发时**。
  落地内容：`--with-gametest` 集成、gametest reference 一篇、M8 从「强化项」升为 Major 功能硬性要求。
  在此之前不预建——按 2026-07 评审结论，为未出现的需求预建基础设施属于过早优化。
