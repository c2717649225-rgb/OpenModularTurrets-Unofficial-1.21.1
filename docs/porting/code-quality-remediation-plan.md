# 代码质量整改完整计划 (Code Quality Remediation Plan)

> 依据：2026-08-25 全量代码审查（116 个 Java 文件 / ~15,200 行，综合评级 B+/A-）。
> 性质：**行为保持不变**的整改（除 A1 击杀记账收敛外，所有批次不得改变任何可观测玩法行为）。
> 前置事实：当前工作树存在一批未提交的重构改动（`blockentity/base/`、`turret/behavior/` 等），`compileJava` 已验证通过。

---

## 0. 总原则与范围边界

1. **每批次独立交付**：一个 Phase = 一个 commit，单独编译、单独过门禁、可独立 revert。
2. **不改的东西**：玩法数值、存档 NBT key、网络 payload schema、注册 ID、资源文件、`zh_cn.json`。
3. **回归网**：现有 57 个 GameTest 方法必须全程保持绿色；任何批次若导致测试失败，先修代码而不是改测试预期。
4. **分级遵循 AGENTS.md**：Phase A 属 Major → 先落 `docs/features/*.contract.json` 合同过 L0 再动码；Phase B/C/D 属 Minor → 直接写码 + 门禁。

---

## 1. 批次总览与执行顺序

| 批次 | 主题 | 级别 | 预估工作量 | 依赖 |
|---|---|---|---|---|
| P0 | 基线固化 | — | 0.5h | 无 |
| A | 架构收敛（击杀记账 / TurretDefinition 构造器 / 死代码） | Major | 4~6h | P0 |
| B | 热路径性能修复 | Minor | 2~3h | P0 |
| C | 可读性治理（共享工具 / FQN / 魔法数字） | Minor | 1.5~2h | A3（muzzleOrigin 与策略包相关） |
| D | 封装与卫生（低优先级杂项） | Minor | 2~4h | 无 |
| E | 结构性优化（可选，单列决策） | Major | 各 0.5 天 | A~D 完成后 |

执行顺序：**P0 → A → B → C → D →（E 视决策）**。B 与 C 中除 C1 外互不依赖，可在 A 之后并行推进。

---

## 2. P0 — 基线固化（必做前置）

工作树当前不干净，禁止在其上叠加整改。

1. 将现有未提交重构按其本义整理为一个 commit（refactor: extract base sub-components and volley strategies），附 L1+L4 通过输出。
2. 在干净基线上跑一次全量门禁并存档输出：
   - `python .agents/run.py .agents/gates/compile_and_repair.py --with-static`
   - `python .agents/run.py .agents/gates/gametest_gate.py --require-tests --run`
3. 记录基线测试清单（57 个方法名），作为后续批次的对照表。

**验收**：`git status` 干净；门禁输出已存档。

---

## 3. Phase A — 架构收敛（Major）

### A0. 合同固化（先行）
按 `.agents/scaffolds/major_feature/major-feature.contract.json` 落地 `docs/features/combat-accounting-convergence.contract.json`：
- `summary`: Kill accounting converges into TurretCombatService; synchronous volley kills are recorded in exactly one place.
- `server_authority.authoritative_state`: 仅 logical_server 记账 kills/playerKills；记账幂等（一次击杀恰好 +1）。
- `persistence`: 不新增字段（复用 base 的 `kills` / `player_kills`，schema version 5 不变）。
- 验收项映射到下文 A1/A2/A3 的 GameTest。
- 运行 `python .agents/run.py .agents/gates/contract_gate.py --require` 通过后再写码。

### A1. 击杀记账统一到 TurretCombatService

**现状问题**
- 光束击杀在 `BeamVolleyStrategy.execute` 内部记账（BeamVolleyStrategy.java:113-115）。
- `TurretHeadBlockEntity.serverTick` 还保留一段按 `ShotKind != BEAM` 判定的记账分支（TurretHeadBlockEntity.java:119-122）——复核确认该分支在当前策略集下**实际不可达**（全库仅 3 处 `.hurt(`：光束策略 1 处同步、投射物实体 2 处异步；非光束策略均不在 volley 返回前造成伤害），属死分支。
- 投射物击杀因弹丸飞行是异步的，在 `TurretProjectileEntity.damage()` 里经 `sourceBase()` 记账——这部分**合法且保留**。

**改法**
1. `TurretCombatService.executeVolley(...)`：循环结束后统一判定
   `boolean killed = aliveBefore && !target.isAlive(); if (killed) combatContext.recordKill(target);`
   并把 `killed` 并入 `CombatResult`（替换或补充现字段）。
2. 删除 `BeamVolleyStrategy` 内部的 `recordKill` 调用（保留 hurt 与音效逻辑不动）。
3. 删除 `TurretHeadBlockEntity.serverTick` 中的 `ShotKind != BEAM` 分支判断，改为直接消费 `CombatResult.killed()`。
4. 在 `TurretProjectileEntity` 异步记账处补一行注释，说明为何投射物路径无法收敛进 service（死亡发生在 volley 返回之后）。

**风险与对策**
- 多发散射（executions>1）：现 Head BE 逻辑本来就是整轮齐射后判定一次，service 层判定语义一致。
- 幂等性：`aliveBefore` 取自 executeVolley 入口，一轮只记一次；光束策略删除内部记账后无双记可能。
- 新增回归测试：`combatKillAccountingContract`——激光炮塔击杀假人后断言 `base.kills()==1 && base.playerKills()` 按目标类型正确；机枪同理。

### A2. TurretDefinition 构造器改造（消除 17 参位置字面量）

**现状问题**
每个枚举常量一行塞入 13 个连续数字字面量（TurretDefinition.java:27-61），int/float/double 混排下错位无法被编译器捕获，是移植数值最危险的隐性 bug 源。

**改法**（枚举内私有 Builder，外部 API 零变化）
```java
DISPOSABLE(def("disposable_item_turret")
        .tier(1).range(10).interval(25).damage(2.0F).energy(2)
        .accuracyDeviation(50.0D).maxSimultaneous(4)
        .fireRate(0.1D).rangeUp(2).amp(0.05F)
        .accuracyUp(0.2D).efficiencyUp(0.08D).recycler(0.10D)
        .ammo(ModTags.Items.DISPOSABLE_AMMO).kind(ShotKind.PROJECTILE)
        .volley(new ProjectileVolleyStrategy(ProjectileKind.DISPOSABLE, 1.6F))),
```
- 私有静态工厂 `def(String id)` 返回 Builder；Builder 逐 setter 校验非负/范围，`build()` 产出常量。
- 所有 getter（含 config 委托与 `default*()` 系列）、序列化行为完全不变。

**风险与对策（数值漂移是本任务唯一实质风险）**
- 金标网：扩展现有 `legacyConfigDefaults` GameTest（原 OpenModularTurretsGameTests.java:276，现位于 ConfigDefinitionGameTests），为全部 11 个定义 × 13 个数值字段建立显式期望值断言表（从改造前源码抄录）。改造前后各跑一遍，值不变才允许合入。

### A3. 死代码、双轨制与特判清理

1. **删除 `BaseAddonEngine.runSolarCycle()`**（BaseAddonEngine.java:39-47，无调用方）；`serverTick` 中的太阳能条件加注释说明与 1.12 原版行为的对应关系（isDay/isRaining/above(2)/above(3) air）。
2. **`fixedSpecialSound` 数据化**（TurretHeadBlockEntity.java:110-115）：将 RELATIVISTIC/TELEPORTER 的固定音量/音调下沉为 definition 侧元数据（如在 `ShotKind` 或新 `SoundProfile` 小 record 上声明 `fixedLaunchSound`），Head BE 只读元数据，不再枚举身份特判。
3. **`ShotKind` 双轨评估**（只评估，不在本批强改）：确认 ShotKind 的全部消费方（渲染/音效/记账）；若记账已在 A1 收敛后仅剩渲染与音效用途，则在文档记录"ShotKind=表现层分类、VolleyStrategy=行为层分类"的边界结论，暂不合并。

**验收**：A0 合同验收项 + 新增 `specialSoundMetadataDriven` 测试（断言两类炮塔发射音量/音调取自元数据而非硬编码分支）。

---

## 4. Phase B — 热路径性能修复

### B1. `isTrusted(Player)` 去除快照拷贝
- 现状：`trustManager.snapshot()` = `Map.copyOf(localTrust)`（≤128 项），在 mayTarget/mayDamage 热路径按候选实体逐个调用（TurretBaseBlockEntity.java:476）。
- 改法：`BaseTrustManager` 新增 `boolean matchesOffline(UUID, String name)` 直接遍历活 map；`isTrusted(Player)` 改为先 `contains(uuid)` 直查、未命中且 offlineModeSupport 时走 matchesOffline。`snapshot()` 保留给菜单/内存卡等低频导出场景。

### B2. `isOwnerTeamMember` 副作用外移
- 现状：查询谓词内更新 `ownerTeamName` 并 `markForSave()`（TurretBaseBlockEntity.java:451-461）。
- 改法：
  1. 谓词改为纯读（用现有缓存值比较队伍名）；
  2. 队伍名刷新移到 `serverTick` 低频节拍（复用 WARNING_SCAN_INTERVAL 的错峰偏移，20 tick 一次），仅在 owner 在线且队伍名变化时写回 + markForSave。
- 效果：目标扫描热路径零 setChanged、零隐藏突变。

### B3. FakePlayer 装备原型缓存
- 现状：每次 hurt 都 `new ItemStack(DIAMOND_SWORD)` + 注册表查找 + enchant（TurretDamageSource.java:46-58）。
- 改法：按 `fakeDropsLevel`（-1~3，天然有界）惰性构建原型剑并缓存于静态数组；使用时 `prototype.copy()`。luck 属性 setBaseValue 保留（廉价且必须逐次设置）。

### B4. 常量语义修正
- `TARGET_SCAN_INTERVAL` 在 serverTick:184 实际用于能量钳制：重命名为 `ENERGY_CLAMP_INTERVAL`（新建常量，10 不变），并在钳制处补注释说明意图（扩展器拆除导致容量收缩后回收超额能量）。

**验收**：L1+L2 绿；`statePersistence`、`networkAuthority` 等相关 GameTest 绿；B2 后以日志断言确认扫描路径无新增 setChanged（可用临时计数器验证后移除）。

---

## 5. Phase C — 可读性治理

### C1. 提取共享 `muzzleOrigin`
- 三处完全相同实现：ProjectileVolleyStrategy.java:52、BeamVolleyStrategy.java:140、TurretTargetingService.java:160。
- 改法：落地到依赖底层的 `data/TurretGeometryRules.muzzleOrigin(BlockPos headPos, Vec3 targetPos)`（data 包是零依赖底层，三方均已依赖它）；三处改为委托，`MUZZLE_CLEARANCE` 常量随迁。

### C2. 清理 42 处内联 FQN
- 分布：TurretProjectileEntity（最多）、InventoryExpanderBlockEntity、TurretBaseBlockEntity、InventoryExpanderBlock、TurretDamageSource、BeamVolleyStrategy、TurretTargetingService、TurretHeadItemRenderer、两个 GameTest 类。
- 改法：全部提为 import；纯机械改动，逐文件提交粒度即可并入本批单 commit。
- 注意：GameTest 文件里的 FQN 一并处理但**不改任何断言内容**。

### C3. 榴弹引信魔法数字协议显式化
- `tickCount = 30`（TurretProjectileEntity.java:158）× `fuseExpired(age >= 39)`（ProjectileKind.java:52）跨文件隐式耦合。
- 改法：`ProjectileKind.GRENADE_FUSE_AGE_TICKS = 39`、`GRENADE_BOUNCE_FUSE_AGE_TICKS = 30`，两处引用常量并互相注释指向。

### C4. `explode()` 三元嵌套展开
- TurretProjectileEntity.java:203-206 改为普通 if/else，语义不变。

### C5. 杂项命名与注释
- `aggregateAmmoInventories` 返回值补 Javadoc 契约："返回内部缓存的只读视图，仅限当 tick 使用，不得跨 tick 持有"（BaseExpanderTopology.java:94）。
- `Menu` 中 `new java.util.UUID(0L,0L)` 提为 `ClientTrustSnapshot.NULL_SESSION_OWNER` 常量（TurretBaseMenu.java:167）。

---

## 6. Phase D — 封装与卫生（低优，可拆散执行）

| 项 | 内容 | 文件 |
|---|---|---|
| D1 | `claim(Player)` 改为委托 `claim(uuid, name)` 消重 | TurretBaseBlockEntity.java:197-217 |
| D2 | `BaseEnergyStorage.stored` 收私有化，暴露 `setStoredClamped(int)` 供 load/clamp 使用 | TurretBaseBlockEntity.java:1115-1156 |
| D3 | `getUpdateTag` 是否继续下发 owner UUID 做决策记录（默认保持现状，补注释说明理由；如需收紧则客户端仅收 name） | TurretBaseBlockEntity.java:1081-1083 |
| D4 | EnderDragon `setHealth` 绕过伤害管线处补设计注释（为何不走 hurt()） | TurretProjectileEntity.java:273-276 |
| D5 | `TurretDamageSource.prepareFakePlayer` 中 InteractionHand 等 FQN 已在 C2 处理，此处仅复核 | — |

## 7. Phase E — 结构性优化（2026-08-25 执行结果）

1. **GameTest 大类拆分：已完成**（提交 183ddb9）。59 个测试按域拆为
   BaseState(12) / CombatTargeting(17) / AddonUpgrade(9) / SecurityTrust(7) /
   ConfigDefinition(15) 五个同命名空间持有者类；全部合同 test_ref 与阶段
   文档引用已重写；L4 复跑 61/61 全绿。
2. **爆炸伤害模型评估：已裁决**。经与
   `reference-sources/OpenModularTurrets-1.12` 四个弹种源码逐行对照，
   方形 AABB、无遮挡、双段清无敌帧均为原作设计；parity 结论已固化为
   `TurretProjectileEntity#damageArea` 的合同注释（19777e4），不再作为待办。
3. **TurretBaseScreen 拆分：评估后不做**。安全页与屏幕原语
   （addRenderableWidget/font/topPos）深度耦合，而当前环境无法做客户端
   视觉回归；文件本身已按页分节、命名一致，强行拆分是以稳定性换外观。
   若未来引入 GUI 自动化测试再重启此决策。

> 复核更正（2026-08-25）：原第 2 项 "referencehost 打包隔离" 经查证**撤回**——`build.gradle:138` 已 `exclude('dev/modstudio/referencehost/**')`，且 `build.gradle:158-174` 存在构建期泄漏守卫（检测到泄漏直接使构建失败），打包卫生已妥善处理，无需整改。

## 7.5 CI 门禁恢复（2026-08-25 新增并完成）

历史删除的自动门禁以双轨方式恢复：`.github/workflows/gates.yml`
（push/PR 触发 L0/L1/L2/L4，只读权限，不含 Secret）+ 本地可选 pre-push
快速门禁钩子（`tools/git-hooks/pre-push`，见 `tools/ci/README.md`）。

---

## 8. 门禁与证据协议（每批次统一执行）

| 检查 | 命令 | 适用 |
|---|---|---|
| L0 合同 | `python .agents/run.py .agents/gates/contract_gate.py --require` | Phase A 先行 |
| L1 编译 | `python .agents/run.py .agents/gates/compile_and_repair.py` | 全部 |
| L2 静态 | 上条加 `--with-static` | 全部 |
| L4 行为 | `python .agents/run.py .agents/gates/gametest_gate.py --require-tests --run` | 全部 |
| 完成证据 | 变更文件清单 + 各门禁输出 + "变更→GameTest#method" 映射表 | 按 AGENTS.md 协议 |

**提交切分**：P0、A、B、C、D 各一个 commit；commit message 用 `refactor:` / `perf:` / `style:` 前缀区分。

## 9. 回滚策略

- 每批独立 commit → 任一批次出问题 `git revert` 单批即可，不影响其他批次。
- A2 数值漂移属于"静默错误"类风险：金标断言表测试永久保留在测试套件中，后续任何人改数值都必须显式更新金标，防止再次无声漂移。
- A1 若 L4 出现历史测试失败：优先怀疑 CombatResult 字段语义变更波及面，回查 Head BE 消费点。

## 10. 明确不做（范围外）

- 不修改任何玩法数值/掉落/合成表。
- 不改存档 schema version（维持 base=5、head=2、security=2、projectile=3）。
- 不动网络 payload 字段与协议版本号（PROTOCOL_VERSION 维持 "2"）。
- 不引入新第三方依赖。
