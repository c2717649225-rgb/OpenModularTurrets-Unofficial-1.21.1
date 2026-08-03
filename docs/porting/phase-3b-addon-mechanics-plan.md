# Phase 3B：附加组件机制移植计划

## 1. 本阶段边界

Phase 3B 恢复七类已经注册、但仍缺少或错误实现的附加组件机制：

1. Solar Panel；
2. Redstone Reactor；
3. Recycler；
4. Damage Amp；
5. Fake Drops；
6. Loot Deleter；
7. Concealer。

本阶段只实现逻辑服务端规则、必要的同步状态与可重复 GameTest。炮塔附加几何、伸缩动画、最终 BER、完整旧版 GUI 信息页仍属于后续表现阶段。

## 2. 明确的兼容性决策

### 2.1 Solar Panel

采用旧版 tooltip 所表达的玩家规则，而不复制旧调用位置造成的缺陷：

- 每个安装了 Solar Panel 的底座每 tick 最多产生 10 FE；
- 必须是白天、无雨，并且 `basePos.above(2)` 可见天空；
- 不要求有炮塔头，也不按相邻炮塔数量倍增；
- 不受底座 active 开关影响；
- 多个 Solar Panel 不叠加。

### 2.2 Redstone Reactor

恢复旧版实际资源循环：

- 每 20 server ticks 尝试一次；
- 不依赖邻接红石信号，也不依赖底座 active；
- 空余容量严格大于 14,400 FE 时优先消耗一个红石块并产生 14,400 FE；
- 否则空余容量严格大于 1,600 FE 时消耗一个红石粉并产生 1,600 FE；
- 燃料从底座弹药库存及相邻 Inventory Expander 中按既有顺序查找；
- 发电绕过外部 Energy Capability 的单次接收速率，但绝不超过容量；
- 多个 Reactor 不叠加。

### 2.3 Recycler

旧配置声明 10% 的 `recyclerNegateChance`，但旧实现把 `nextInt(99)` 直接与 `0.1` 比较，实际约为 1%。本移植按配置意图修复为：

- 安装一个或多个 Recycler 的效果相同；
- 每次完整 volley 独立进行一次 10% 判定；
- 成功时保留该 volley 的全部弹药，但能量和射击计数仍照常结算；
- 判定失败时原子消耗全部所需弹药；
- 旧版从未使用的 `recyclerAddChance` 不凭空实现，避免制造不存在的弹药复制机制；
- Reactor 的燃料消耗不受 Recycler 影响。

### 2.4 Damage Amp

按旧版炮塔专属公式恢复，不再使用统一基础伤害乘数：

`finalDamage = baseDamage + floor(currentHealth) * turretCoefficient * ampLevel`

- projectile 在发射时快照 amp 等级；
- beam 在开火时使用当前 amp 等级；
- 每个受爆炸影响的目标分别按自己的当前生命值计算；
- Disposable/Potato/Incendiary 为 0.05，Machine/Laser 为 0.06，
  Grenade/Rocket 为 0.08，Rail/Plasma 为 0.10，Relativistic/Teleporter 为 0；
- 现代实现统一保留浮点额外伤害，修复旧 projectile 因复合赋值产生的意外整数截断。

### 2.5 Fake Drops 与 Loot Deleter

不移植旧版永久 scoreboard tag 与 FakePlayer：

- 所有炮塔伤害统一使用短命的 `TurretDamageSource`；
- 伤害源只携带不可变的底座位置、Fake Drops 等级、Loot Deleter 快照，不持有 BlockEntity；
- Fake Drops 等级保持旧映射：无 addon 为 -1，一个为 0，两个为 1，三个为 2，四个及以上为 3；
- 在 `LivingDropsEvent` 中，仅对真正造成死亡的炮塔伤害应用效果；
- Loot Deleter 在发射或 beam 命中时快照六面相邻方块，致死时清空物品掉落；
- Fake Drops 对已生成、非空的掉落执行有界 bonus-count pass；等级 0 只保留“炮塔掉落上下文”而不增加数量，等级 1～3 每个现有掉落最多增加对应等级数量；
- 该安全映射不声称恢复任意第三方 loot table 的 player-only 条件；这类兼容属于后续可选适配，而不是重新引入 FakePlayer。

### 2.6 Concealer

- Concealer 是存在性效果，不按堆叠增强；
- 炮塔连续 40 tick 没有合法目标后进入 concealed 状态；
- 找到目标、addon 被移除、底座失效或停用时立即展开；
- concealed 是派生运行时状态，通过 BlockState 同步，不写入 BlockEntity NBT；
- 当前模型对 concealed 两个 variant 使用相同基础模型，最终伸缩模型与动画留给 BER 阶段。

## 3. 服务端权威与持久化

- addon 等级只从底座服务端库存读取；
- projectile 必须持久化发射时快照的 amp、fake drops 与 suppress loot 上下文；
- 不增加 ItemStack NBT；本阶段没有适合迁移为 Data Component 的物品实例数据；
- 不增加客户端到服务端 payload；
- Concealer 使用原生 BlockState 更新，Fake Drops/Loot Deleter 使用服务端死亡事件，不需要新增 `CustomPacketPayload`。

## 4. 实施顺序

1. 提取 `TurretAddonRules` 纯规则，固定能量、伤害、等级与概率边界；
2. 修正底座 tick、燃料接纳与原子 volley 资源结算；
3. 引入 `TurretDamageSource` 和统一伤害入口；
4. 接入掉落事件，移除旧的全局/持久归因需求；
5. 添加 concealed BlockState 与 40 tick 状态机；
6. 扩展 GameTest，覆盖正向、反向、边界和非炮塔控制组；
7. 运行合同、编译、静态、DataGen、资源、专服、GameTest 与 fast pipeline。

## 5. 非目标与后续

- Phase 3C 的目标优先级、队伍/宠物保护、multi-target 与警告系统；
- 最终炮塔 BER、addon 外挂几何、conceal 伸缩动画；
- 对所有第三方 loot table 的 player-only 条件做通用兼容；
- 新增运行时配置界面；
- 修改 `openmodularturrets` mod id、包名或注册 ID。
