# OMT 架构成熟度提升方案

状态：`approved-executing`

执行批次：`architecture-maturity-20260807`

项目：Minecraft 1.21.1 / NeoForge 21.1.234 / `openmodularturrets`

## 1. 目标和判断标准

本方案不是第二轮无证据的大规模拆类，而是把已经稳定的运行时架构提升到更接近长期维护项目的工程成熟度：边界可自动审计、验证可重复、压力证据可复用、扩展规则可交接、发布前质量线可执行。

本批次的成功标准：

- 任何贡献者都能从项目文档恢复架构不变量、门禁命令和证据位置；
- 服务层、客户端边界、状态所有权和缓存所有权有项目侧自动审计，而不是只靠人工记忆；
- GitHub Actions（若宿主平台运行）能复现文档、合同、编译、DataGen、静态、资源、GameTest 和专服门禁；
- 固定压力与真实旧存档证据仍可被一条审计命令消费，不把一次聊天结论当作质量证明；
- 新增炮塔遵循定义驱动路径，不需要修改 Base/Head 核心分支；
- 不引入玩法、性能、存档、网络或客户端语义变化。

“业界顶峰”不作为可验证的交付标签。本方案只承诺可审计的工程能力和可重复的质量证据。

## 2. 当前事实基线

阶段 0 必须把以下内容当作实测输入，而不是方案中的假设：

| 项目 | 当前事实 | 本批次处理 |
| --- | --- | --- |
| Base 聚合根 | `TurretBaseBlockEntity` 仍是能量、库存、附件、所有权、信任、统计和持久化的唯一所有者，约 1271 行 | 保留聚合根；没有 profiler 证据不再拆分 |
| Head 编排 | `TurretHeadBlockEntity` 约 326 行，拥有冷却、目标展示、瞄准和自身持久化 | 保留生命周期编排；目标/战斗规则由服务承载 |
| 服务边界 | `TurretTargetingService`、`TurretCombatService` 不反向依赖具体 Base/Head；世界/权限查询走窄适配器 | 增加自动审计，禁止回退 |
| 客户端隔离 | common/server 已无 `net.minecraft.client` 引用；Tooltip 和缓存清理由客户端类承载 | 增加自动审计和 CI 复核 |
| 缓存 | ammo/range/capacity 缓存已有明确键和失效链，`setRemoved()` 清除派生视图 | 不重做缓存；只消费既有 ledger |
| 行为验证 | 当前 L0/L1/L2/L2.5/L3/L4 已通过，L4 为 58/58 | 作为回归基线，不篡改测试数量假设 |
| 压力验证 | 100 Base/100 Head/100 目标固定 fixture，最终 candidate 中位 p95 `2.147 ms`，预算通过 | 复用证据；增加证据完整性审计，不把 JFR 稀疏采样解释成无热点 |
| 存档验证 | 真实旧存档两次加载/保存/重载通过，源存档未修改 | 作为持久化不变量，不重新设计 key |

## 3. 冻结不变量和非目标

以下内容在本批次冻结：

- 注册 ID、Mod ID、方块/物品/实体身份和配置默认值不变；
- Base、Head、Projectile 的 NBT/save/load key、旧 `active`/`mode` 兼容读取不变；
- Payload 类型、注册 ID、字段顺序、编码、发送时机和客户端视觉语义不变；
- Base 和 Head 的状态所有权不迁移；不创建第二个库存、能量、附件、信任或持久化 owner；
- 不把真实世界、实体、玩家、队伍或权限查询伪装成无状态纯规则；
- 不在 common/server 引入客户端类；不以短路判断为理由保留物理 client import；
- 不新增默认启用的每 Tick 日志、计时器、网络包或诊断分支；
- 不拆 `TurretProjectileEntity` 的移动、追踪、碰撞，除非另有 profiler 证据和独立合同；
- 不修改 `.agents/` 工具包；本批次只使用其门禁，不迭代其实现；
- 不启动客户端进行分散式测试；自动化完成后只保留一轮集中人工客户端验收。

本批次明确不做：

- 为了行数均衡而拆分 `TurretBaseBlockEntity`；
- 预先引入跨加载器平台抽象；
- 把压力测试扩展成没有真实决策用途的复杂 profiler 平台；
- 用文档或 CI 绿色替代真实 GameTest、旧存档和人工客户端证据。

## 4. 分阶段执行计划

### 阶段 0：事实账本、合同和恢复点

动作：

1. 创建本方案、Major 合同和独立执行记录；
2. 读取现有架构优化执行记录、合同、压力比较、旧存档报告和 L4 报告；
3. 记录源码 hash、Java/NeoForge/Gradle 元数据、工作区状态和 `.agents` 未修改状态；
4. 自动对账新合同的 `test_id` 与实际脚本/门禁命令；
5. 将所有“已有证据”标为可复核输入，不在新批次中伪造新的性能提升结论。

退出条件：方案 hash 可绑定到合同；证据根目录可恢复；不变量和回滚点明确。

### 阶段 1：项目侧架构成熟度审计

新增 `tools/architecture_maturity_audit.ps1`，只读检查以下事实：

- service 包不 import `TurretBaseBlockEntity` 或 `TurretHeadBlockEntity`；
- common/server Java 不 import `net.minecraft.client`；
- `TurretCombatContext`、`TurretTargetingWorldQueries`、`TurretVolleyResourcesView` 仍存在且由正确 owner 实现；
- Base/Head 的持久化入口和缓存 owner 未漂移；
- 客户端换世界、断开、资源重载清理入口存在；
- architecture-optimization 的设计 hash、L4 报告、旧存档报告和压力 compare 可被读取；
- `.agents` 没有工作区差异；
- CI 工作流存在并调用项目允许的门禁。

实现边界：脚本只做文件/类级别的粗粒度事实审计，不用脆弱的正则模拟 Java AST，也不把方法内部大括号匹配当作安全证明。需要语义证明的项目仍交给编译器、GameTest、专服和运行证据。

退出条件：审计生成 JSON，关键检查全为 `pass`；任何失败都阻止阶段继续，不通过修改阈值掩盖。

### 阶段 2：可重复 CI 和发布前质量线

新增项目侧 `.github/workflows/quality.yml`，不修改 `.agents`，使用 Java 21 和 Python 3.10，执行：

- 文档索引和文档元数据检查；
- Major pipeline（L0、L1、L2、DataGen、L2.5、L4 及合同追踪）；
- 独立专服启动门禁；
- 项目侧成熟度审计；
- 上传 `build/reports`、GameTest 报告和成熟度审计作为构建 artifact。

压力测试和真实旧存档不强行放入每次 PR：它们依赖固定宿主证据目录和真实旧存档输入，继续由本地/发布候选流程运行；CI 必须检查其协议和脚本存在，但不能伪造外部证据。

退出条件：workflow YAML 可读、命令与本地门禁一致、无秘密/绝对本机路径、`.agents` 仍未修改。

### 阶段 3：可观测性和压力证据治理

本阶段优先治理证据，不默认改运行时代码：

- 将已有压力 fixture 的 mean/p50/p95/p99/max、样本数、退出码和 JFR attach 状态纳入审计；
- 明确“直接 tick 计时是主判据，JFR 是独立佐证”；JFR 样本不足时记录为覆盖不足，不推导无热点；
- 记录未来可扩展的压力矩阵：空闲 100 炮塔、100 炮塔持续开火、目标频繁变化、附件拓扑变化、区块/世界生命周期；
- 只有当新矩阵暴露实际热点时，才另立合同增加诊断或优化；不为了“有监控”而给每 Tick 增加默认成本。

退出条件：当前固定压力和旧存档证据可由审计命令复核，未来矩阵被记录为后续入口而非虚假已完成项。

### 阶段 4：扩展规范和交接能力

新增 `docs/porting/architecture-extension-guide.md`，明确新增炮塔的定义、注册、ProjectileKind/战斗映射、资源规则、GameTest 和压力 fixture 接入路径，并明确禁止在 Base/Head 增加具体炮塔分支。

以现有土豆炮路径作为可执行样例：定义 → 注册 → 战斗映射 → GameTest → 压力 fixture。审计只证明当前扩展边界，不新增玩法内容。

退出条件：新贡献者可按文档完成路径定位；phase 5 Potato 审计继续通过。

### 阶段 5：收口和一次性验收

自动化顺序：

1. 文档索引/元数据；
2. 新合同 L0 和 test executor audit；
3. 项目侧成熟度审计；
4. Major pipeline；
5. 独立 L3；
6. 既有压力 compare 和真实旧存档报告复核；
7. `.agents` 和工作区变更审查。

自动化全绿后，用户进行唯一一次客户端检查：GUI 能量/配置、Jade、Shift tooltip、Beam、投射物、换世界、断开/重连、F3+T、正常退出。任何失败只做最小修复，并从受影响门禁重新开始。

## 5. 验收矩阵

| 验收项 | 必须证据 | 失败处理 |
| --- | --- | --- |
| 架构边界未漂移 | `architecture_maturity_audit.json`、L2、L3 | 查明具体 import/owner 漂移；不放宽脚本 |
| 合同与执行器一致 | 新合同 test audit、L0 | 修合同或执行器映射，不把声明当通过 |
| 行为/存档/资源不变 | L4、旧存档、DataGen/L2.5 | 回滚对应代码；禁止接受无意义生成漂移 |
| 性能预算不回退 | 既有压力 compare、完整 metrics、JFR 状态 | 先复测宿主噪声，再看 profiler；不改预算 |
| CI 可复现 | workflow 静态审查和本地同命令通过 | 修 workflow，不修改 `.agents` 逃避门禁 |
| 扩展边界真实 | Potato 审计、扩展指南、GameTest | 找出核心分支或注册耦合后另立小合同 |
| 客户端生命周期 | 最后一轮人工检查 | 只修可复现问题并重跑相关证据 |

## 6. 风险与回滚

| 风险 | 预防 | 回滚 |
| --- | --- | --- |
| CI 环境与 Windows 宿主差异 | CI 不替代本地压力/旧存档；命令使用官方门禁 | 删除 workflow，不影响模组运行时代码 |
| 审计脚本误报 | 只做类/文件级检查，输出证据和解释 | 修正审计规则，不修改被审计代码 |
| 新文档/合同 hash 漂移 | 写码前绑定方案 hash，变更后重新计算 | 回滚文档/合同变更并重跑 L0 |
| 为可观测性引入 Tick 成本 | 默认不加运行时诊断；只消费已有压力日志 | 删除诊断改动，保留原运行时 |
| 继续拆 Base 导致状态分裂 | 本方案冻结 Base owner，除非另立合同 | 恢复最后一个边界切片，保留账本 |
| 工作区已有用户改动 | 每阶段记录 `git status` 和文件 hash | 不 reset/clean；只回滚本批次新增文件 |

## 7. 交付物和恢复协议

项目内：

- `docs/porting/architecture-maturity-plan.md`：稳定方案和不变量；
- `docs/porting/architecture-maturity-execution.md`：实际执行记录和交接点；
- `docs/features/architecture_maturity.contract.json`：Major 合同；
- `docs/porting/architecture-extension-guide.md`：扩展交接规范；
- `tools/architecture_maturity_audit.ps1`：项目侧事实审计；
- `.github/workflows/quality.yml`：项目侧 CI 门禁。

外部证据根目录：

```text
D:\c128\phase25-evidence\architecture-maturity-20260807\
```

上下文压缩或跨会话后，先读本方案、合同、执行记录，再读取成熟度证据根目录的 `maturity-audit.json`、`contract-test-audit.json` 和 `phase5-audit.json`；压力/旧存档继续从上一批只读证据根目录 `D:\c128\phase25-evidence\architecture-optimization-20260807\` 读取 `pressure-comparison.json` 和 `old-save-input.json`；最新 `gametest-gate.json` 从项目 `build/reports/` 读取。不得从聊天记忆推断阶段通过。
