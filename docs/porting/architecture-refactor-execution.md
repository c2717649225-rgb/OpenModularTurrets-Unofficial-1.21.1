# OMT 架构优雅化执行记录

状态：`verifying`（自动化门禁已闭环；唯一一轮人工客户端验收待收尾）  
执行批次：`architecture-refactor-20260806`  
项目：Minecraft 1.21.1 / NeoForge 21.1.234 / `openmodularturrets`

## 1. 执行边界

本文件只记录模组本身的架构整理与验证；`.agents/` 是外部工具包，本批次不修改、不格式化、不重构，也不把工具包迭代纳入完成条件。

允许的改动仅限于：

- 降低 Base/Head BlockEntity 的职责耦合，建立明确的纯规则、目标选择和战斗执行边界。
- 保持服务器权威、主线程所有权、现有注册 ID、BlockEntity 存档 key、网络 Payload 字段和客户端视觉语义不变。
- 为本项目建立可重复的压力场景与证据采集入口；证据写到仓库外的持久绝对路径。
- 为每个结构阶段补充最小的行为/迁移断言；不为了“架构覆盖率”制造空壳测试。

明确不做：玩法重平衡、配置默认值调整、注册 ID 改名、存档 key 改名、Payload 协议升级、客户端视觉重做、跨平台抽象、Projectile 移动/追踪拆分（除非 JFR 证明它是独立热点）。

## 2. 工作区和基线

开始时工作区已有用户改动。它们全部属于输入，执行期间不得使用 `git reset --hard`、`git checkout --` 或 `git clean` 覆盖/删除。基线不是旧 `git HEAD`，而是本文件创建前的当前已优化脏工作区快照。

固定证据根目录：

```text
D:\c128\phase25-evidence\architecture-refactor-20260806\
```

`run/` 已在仓库 `.gitignore` 中；Jade/客户端运行目录不作为基线或提交内容。基线/候选 manifest、日志、JFR、统计 JSON 和存档检查结果均写入上述目录，不写入仓库和系统 TEMP。

基线 ID：`architecture-refactor-20260806-baseline-optimized-dirty`  
候选 ID：由 `tools/architecture_refactor_pressure.ps1` 生成，必须包含当前源码摘要和执行时间。

## 3. 不变量账本

### 3.1 Base BlockEntity 持久化 key

`TurretBaseBlockEntity.saveAdditional/loadAdditional` 的既有 key 在拆分前冻结如下：

```text
data_version, owner, owner_name, owner_team, energy, inventory,
mode_id, active(legacy read), mode(legacy read), redstone_powered,
use_global_trust, local_trust_revision, attack_hostile, attack_neutral,
attack_players, multi_targeting, range, shots_fired, kills, player_kills,
addon_render_mask, camouflage_state, camouflage_light_value,
camouflage_light_opacity, local_trust[player,name,access]
```

其中 `active`、`mode` 是旧数据读取兼容路径；不能因为当前写入使用 `mode_id` 就删除读取分支。`inventory` 必须继续使用 Data Components/NeoForge 1.21.1 的 `serializeNBT(registries)` 路径。

### 3.2 Head BlockEntity 持久化和更新 key

```text
save/load: data_version, cooldown, aim_yaw, aim_pitch,
           priority_max_health, priority_missing_health,
           priority_distance, priority_armor, priority_player
update tag: target, aim_yaw, aim_pitch, priority_* 
```

`target` 是客户端显示同步字段，不能写入服务端持久化 schema；`aim_*` 仍由轻量 `TurretAimPayload` 承担每发视觉更新。

### 3.3 Projectile 持久化 key

```text
data_version, projectile_kind, damage, damage_amp_level,
fake_drops_level, suppress_loot, grenade_hit, source_base_pos, target_uuid
```

Projectile 的移动、追踪、碰撞和伤害结果在本批次保持同一实体内；只有 JFR 在同一场景中证明拆分带来可测收益时，才单独提出第二批次。

### 3.4 网络和客户端红线

- 保留现有 Payload 类型、字段顺序、编码方式和注册 ID。
- `TurretAimPayload` 继续只承载瞄准展示数据；`BeamEffectPayload` 继续只承载光束视觉数据。
- common/server 类不新增 `net.minecraft.client` 依赖；客户端桥接继续位于 `omtteam.openmodularturrets.client`。
- 服务层不持有 Head 并回调 Head，避免 `Head → Service → Head` 循环；服务层只接受不可变输入、`ServerLevel`、位置、实体和 Base 所有权接口/只读视图。

## 4. 压力测试协议

入口：`tools/architecture_refactor_pressure.ps1`；场景：`ArchitecturePressureGameTests#architecturePressureFixture`。由于项目 L4 门禁要求 GameTest namespace 等于真实 Mod ID，压力类与普通 GameTest 共用 `openmodularturrets` namespace，但有独立 holder 类、结构名和唯一 metrics marker；脚本以该 marker 作为压力样本边界。

固定场景和环境：

- 100 个 Tier 5 Base + 100 个 Potato turret head，按固定网格放置。
- 100 个无 AI、无重力、不会自然移动的高生命目标实体。
- 固定 `gradle.properties`、NeoForge、Java 21、JVM 参数、配置和工作区。
- 200 tick 预热，随后 200 tick 采样；baseline/candidate 各 3 次。
- fixture 在服务器主线程每个采样 tick 只记录一次 `System.nanoTime` 间隔，结束时一次性输出 200 个 tick interval 的 mean/p50/p95/p99/max；这个同机、同 fixture 的 tick interval 是主要判定指标。
- 同时通过 JFR 记录 `minecraft.ServerTickTime` 和 `jdk.ExecutionSample`；每次记录保存原始 JFR、Gradle 输出和 Git/source manifest。JFR 用于独立 corroboration 和热点定位，不因高速 dev server 的 1 秒周期而伪造 200 个样本。
- 若 fixture 样本不足、GameTest 未完成或 JFR 未附着，报告为失败，不用不相关的外部数据替代。

验收口径：

1. 所有 3 次 baseline/candidate 均完成固定场景，GameTest 无失败。
2. 候选 fixture tick interval p95 不得比同机 baseline p95 增加超过 10%；若 baseline p95 小于 1 ms，则同时要求绝对增量不超过 0.25 ms，避免小数噪声夸大百分比。
3. JFR 必须附着并包含 `minecraft.ServerTickTime` 或 `jdk.ExecutionSample` 记录；`ExecutionSample` 应能定位到 OMT 热点，没有热点证据的重构不以“理论上更快”结案。
4. 阶段 1 的同步计数/包体检查必须确认没有恢复每 tick 完整 BlockEntity 广播。

## 5. 阶段和回滚点

| 阶段 | 内容 | 完成条件 | 回滚点 |
| --- | --- | --- | --- |
| 0 | 固化 manifest、压力场景、JFR 协议、存档 key ledger | baseline 三次可重现；未改玩法代码 | 删除本阶段新增工具/测试/文档 |
| 1 | 纯规则快照和 Base 只读状态视图 | L0/L1/L2；既有存档 round-trip 与旧存档加载通过 | 仅回退快照/规则新增文件 |
| 2 | Base 的所有权/信任/目标许可边界整理 | Base 旧存档字段逐项对账；权限 GameTest 全绿 | 恢复 Base 原方法，保留账本 |
| 3 | Head 目标选择服务化 | `potatoTurretAcquiresVisibleHostileAndFires`、遮挡目标测试和压力场景全绿 | 恢复 Head 寻敌调用，服务类可删除 |
| 4 | Head 战斗执行服务化 | 土豆炮 Demo、Projectile/Beam/特殊炮台测试全绿 | 恢复 Head 开火分派；不拆 Projectile |
| 5 | 同步/Jade/客户端桥接整理 | Payload/GUI/Jade 数据不回归；L2 客户端隔离通过 | 恢复旧桥接，不改协议 |
| 6 | 扩展性证明 | 新增/复核土豆炮仅通过定义/规则/注册接入，不修改核心循环 | 只保留既有注册代码 |
| 7 | 最终验证 | 压测、L0/L1/L2/L2.5/L3/L4 全绿；DataGen diff 审查 | 按最近阶段回滚 |

每阶段执行：`contract_gate` → 编译/静态 → 相关 GameTest → 存档检查；涉及资源时再执行 DataGen 和资产对账。DataGen 产生的 diff 必须人工审查，只接受与本阶段有关的有意义变更，拒绝无意义重排。

## 6. 存档验证顺序

在任何 Base/Head 拆分前，先执行当前代码的 round-trip 并保存字段摘要。阶段 1、2、3、4 结束后重复：

1. 新对象写入全部代表性字段并 `saveWithFullMetadata`。
2. 同一版本加载并逐项核对 key/value。
3. 从 `D:\c128\phase25-evidence\phase25-runtime-20260806-oldsave3\world` 的真实旧存档加载，确认 Base/Head/Projectile 可读且未产生丢失/权限提升。
4. 保存加载后的真实旧存档，再次加载并对账。

若只完成新对象往返而没有真实旧存档加载，阶段不得标记完成。

## 7. 人工客户端收尾

自动化完成后只进行一轮人工客户端验收，由操作者完成并记录结果；AI 不替代真人宣称通过。场景包括：GUI 能量/配置、Jade Provider、Shift tooltip、Beam、投射物、换世界、断开重连、F3+T、正常退出。最终客户端验收前不反复启动游戏。

## 8. 本批次执行结果（2026-08-06）

### 8.1 代码与资源变更

- 架构拆分、服务边界、客户端清理和 GameTest 回归修复均已完成；服务层不持有 `TurretHeadBlockEntity`，玩法、注册 ID、存档 key 和 Payload 字段协议保持不变。
- 首轮 major 流水线曾因新增 `ArchitecturePressureGameTests` 缺少其按类名解析的结构资源而失败：`Missing test structure: openmodularturrets:architecturepressuregametests.smoke`。根因已确认并以最小补丁修复：新增 `src/main/snbt/data/openmodularturrets/structure/architecturepressuregametests.smoke.snbt`，经 DataGen 生成对应 NBT；未修改游戏逻辑。
- `--with-data` 生成 291 个 JSON；本阶段新增的结构 NBT 与既有 Jade 语言键均已人工审查，没有无意义重排。

### 8.2 自动化门禁结论

| 门禁/证据 | 结果 | 关键输出或位置 |
| --- | --- | --- |
| L0 合同 | PASS | `contract_gate`：12 份合同，0 errors |
| L1 编译 | PASS | `compileJava` 100% |
| L2 静态 | PASS | 99 个 Java 文件，0 errors / 0 warnings |
| L2.5 资源对账 | PASS | `asset_gate --strict-datagen-layout`，0 errors |
| L3 专服 | PASS | `runServer` 到 `Done`，0 ERROR，优雅停止 |
| L4 GameTest | PASS | 58/58 required tests；压测夹具 100 bases/100 targets，1200 shots |
| major 流水线 | PASS | `pipeline.py --profile major --gametest-timeout 900` |

正式压测对比证据位于 `D:\c128\phase25-evidence\architecture-refactor-20260806\`：baseline 中位数 p95 为 `3.4427 ms`，candidate 中位数 p95 为 `3.6328 ms`，增量 `0.1901 ms`，预算 `0.34427 ms`，判定 `pass`。六次正式 JFR 均包含 `jdk.ExecutionSample` 与 OMT 栈帧；JFR 仅作为独立佐证和热点定位，不据此宣称“重构后更快”。真实旧存档加载/保存/再次加载证据 `old-save-input.json` 状态为 `pass`，源存档未被修改。

`build/reports/traceability-gate.json` 保留为 advisory 结果：它会把专服、DataGen、压测和人工客户端等非 GameTest 验收项列为 L4 无法证明的未覆盖项；本批次未运行 `--strict-traceability`，因此不把该报告的 advisory `failed` 误报为代码或流水线失败。上述非 GameTest 项以外置旧存档、压测、L3 和本记录中的命令证据为准。

### 8.3 尚未关闭的唯一事项

只剩第 7 节规定的唯一一轮真人客户端验收：GUI 能量/配置、Jade、Shift tooltip、Beam、投射物、换世界、断开/重连、F3+T、正常退出。完成前合同保持 `verifying`，不得宣称最终客户端验收完成。

## 9. 压缩恢复协议

上下文压缩后先读本文件，从“阶段和回滚点”找到第一个未完成阶段，再读取 `git status --short` 和证据根目录下最新 `candidate-manifest.json`。不得从旧聊天内容推断已完成；只能以本文件、门禁输出、GameTest 报告和 manifest 为准。每次阶段结束时更新本文件的状态、变更文件列表、命令输出摘要和下一步。
