# Phase 3：底座规则、升级、目标策略与附加组件

## 1. 阶段目标

Phase 3 将旧版中仍是“注册占位”或简化实现的底座玩法恢复为服务端权威机制。
为了避免同时改动库存、战斗、掉落和能源造成难以定位的回归，本阶段拆为三个可独立验收的增量：

| 增量 | 范围 | 本轮状态 |
|---|---|---|
| Phase 3A | tier 槽位、升级堆叠与公式、底座总炮塔/同类炮塔上限、自动化库存边界 | 实施与验证 |
| Phase 3B | solar、redstone reactor、recycler、damage amp、fake drops、loot deleter、concealer | 待 3A 验收后实施 |
| Phase 3C | 目标优先级、队伍/驯服生物保护、multi-target、警告范围与告警 | 待 3B 验收后实施 |

本轮不把 Phase 3A 自动门禁通过解释为 Phase 3 全部完成。

## 2. 名称与兼容性

- 玩家可见名称改为 `OpenModularTurrets-Unofficial`。
- `mod_id=openmodularturrets`、Java 包名和所有注册 ID 保持不变，避免破坏已有 1.21.1 测试世界、配方、标签与资源路径。
- NeoForge 模组列表将原项目列入 credits，并明确“非官方且未获原团队背书”。
- 分发许可与第三方声音来源仍是单独的发布门禁；改名不会自动解决该问题。

## 3. Phase 3A 设计

### 3.1 tier 库存策略

内部库存保持 13 槽的稳定序列化布局，以免制造存档迁移：

- `0..8`：弹药；
- `9..10`：附加组件；
- `11..12`：升级。

有效槽位由底座 tier 决定：

| tier | 弹药槽 | addon 槽 | upgrade 槽 |
|---:|---:|---:|---:|
| 1 | 9 | 0 | 0 |
| 2–4 | 9 | 2 | 1 |
| 5 | 9 | 2 | 2 |

无效槽位拒绝玩家、shift-click 和 Capability 插入；旧存档若在无效槽有物品，
数据仍被保留并会在拆除底座时正常掉落，防止静默吞物。

### 3.2 升级语义

升级等级按有效升级槽内的 `ItemStack#getCount()` 求和，而不是按“有几个槽被占用”计数。
每种炮塔保留旧版默认参数：

- fire rate：`ceil(baseInterval / (1 + fireRateBonus * level))`，最少 1 tick；
- efficiency：`round(baseEnergy * max(0, 1 - 0.08 * level) * projectileCount)`；
- range：每个相邻炮塔的可达上限为 `baseRange + rangeBonus * level`；底座共享可配置射程的上限取所有相邻炮塔可达上限的最大值；
- accuracy：`baseDeviation / (1 + 0.2 * level)^1.5 * (1 + scatterLevel / 10)`；
- scatter：每级增加 1 发，弹药和能量按总发数原子检查、一次扣除。

Laser 的 fire-rate bonus 为 `0.125`，Rail Gun/Plasma 为 `0.2`，其余为 `0.1`；
Plasma 每级 range `+1`，其余每级 `+2`。无散布的炮塔保持精确弹道。

旧 `BaseSetting` 中的 `20/30/40/50/60` 是方块硬度，不是射程上限；当前
`BaseTier.maxRange` 属于错误建模，Phase 3A 会移除这个限制。没有相邻炮塔时，
底座当前有效射程为 0，但保留配置值，重新安装炮塔后再按新的动态上限裁剪。

### 3.3 炮塔附着限制

放置和存活检查只扫描底座相邻六面，复杂度固定为 O(6)：

- 炮塔 tier 不得高于底座 tier；
- 总炮塔数不得超过底座 `maxTurrets`；
- 同种炮塔不得超过 `TurretDefinition.maxSimultaneous`。

已合法放置的炮塔在邻居更新时不把自己重复计数；底座降级或结构变得非法时遵循原版语义脱落。

### 3.4 菜单与 Capability

- 菜单只创建该 tier 的有效 addon/upgrade 槽，玩家槽索引根据实际底座槽数计算。
- shift-click 只尝试可接受该物品的有效底座槽。
- `VIEW` 只允许观察；只有 `USE` 及以上权限可以放入、取出或 shift-click 物品。
- 外部 `Capabilities.ItemHandler.BLOCK` 仅暴露 9 个弹药槽；addon/upgrade 必须通过受权限保护的菜单管理。
- Energy capability 维持当前双向接口，能源方向策略留在 Phase 3B。

## 4. 服务端权威与持久化

- 所有升级等级、资源检查、散射发数和附着上限在逻辑服务端计算。
- 客户端不发送升级计算结果，也不新增 `CustomPacketPayload`。
- 库存仍使用当前 BlockEntity NBT；本增量不改变字段名和 schema version。
- UI 仅消费菜单公开的有效槽位；完整旧版 GUI 纹理与信息页属于后续表现阶段。

## 5. 验收矩阵

自动化：

1. `tiered_inventory_rules`：五个 tier 的有效槽位、无效槽拒绝、堆叠计数与旧物可取出。
2. `upgrade_formula_rules`：普通/Laser/Plasma 的射速、射程、效率、精度与 scatter 边界向量。
3. `turret_attachment_limits`：tier、总数、同类数，以及“现有炮塔不重复计数”。
4. `capability_ammo_boundary`：Capability 只报告 9 槽且拒绝 addon/upgrade。
5. 现有 projectile、配方、声音、存档、权限与性能 GameTest 全部继续通过。

门禁：

```text
python .agents/run.py .agents/gates/contract_gate.py docs/features --require
python .agents/run.py .agents/gates/compile_and_repair.py --with-data --with-static --with-assets --with-server
python .agents/run.py .agents/gates/gametest_gate.py --require-tests --run
python .agents/run.py .agents/gates/pipeline.py --profile fast
```

手工留项：

- 五个 tier 菜单槽位布局与 hover/shift-click；
- scatter 的实际视觉散布、弹药消耗与音效节奏；
- 六个安装方向分别达到上限时的放置反馈。

## 6. 后续顺序

Phase 3A 通过后按以下顺序继续：

1. Phase 3B-1：能源组件（solar、redstone reactor）及可重复的天气/红石 GameTest；
2. Phase 3B-2：recycler 与伤害/掉落附加组件，使用事件归因而不是实体持久 tag 污染；
3. Phase 3B-3：concealer 逻辑状态，视觉模型留给 BER 阶段；
4. Phase 3C-1：统一目标评分模型与五维优先级；
5. Phase 3C-2：队伍、主人、trusted players、驯服生物保护；
6. Phase 3C-3：multi-target、警告消息/声音及网络频率预算。
