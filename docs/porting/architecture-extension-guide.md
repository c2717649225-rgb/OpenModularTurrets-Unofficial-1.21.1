# OMT 炮塔扩展架构指南

本文是新增炮塔的交接规范。目标是让新增内容沿既有定义驱动路径进入系统，而不是把具体炮塔分支塞回 Base/Head 核心循环。

## 1. 所有权边界

- `TurretBaseBlockEntity` 只拥有 Base 的库存、能量、附件、所有权、信任、统计和持久化状态；
- `TurretHeadBlockEntity` 只拥有 Head 的冷却、目标展示、瞄准和 Head 持久化状态，并负责生命周期编排；
- `TurretDefinition` 描述炮塔静态能力和规则输入；
- `TurretCombatService` 负责从定义和窄上下文执行开火；
- `TurretTargetingService` 负责目标选择，世界/权限查询由显式适配器提供；
- `TurretProjectileEntity` 负责投射物自身的移动、碰撞和持久化上下文；
- client 包只负责渲染、Tooltip、粒子和客户端缓存。

新增炮塔不得成为第二个状态 owner，也不得让 service 反向持有或回调 Base/Head 具体类。

## 2. 标准接入路径

按以下顺序完成：

1. 在 `TurretDefinition` 中添加静态定义和默认规则输入；
2. 在注册层使用该定义创建 Head/相关方块，不在 Base/Head 编写具体炮塔 `if`/`switch` 分支；
3. 在 `TurretCombatService` 的定义到 `ProjectileKind` 映射处添加必要的纯映射；
4. 只有当新炮塔确实需要新资源事务或规则时，才在 `TurretUpgradeRules` 或明确的规则类中添加纯计算；
5. 为定义、注册、战斗、资源消耗和持久化补充 GameTest；
6. 将代表性炮塔加入固定压力 fixture，或在方案中记录为什么不适合压力场景；
7. 运行 L0/L1/L2/L2.5/L3/L4、旧存档和压力证据；
8. 最后才做一次客户端 GUI、Jade、Tooltip、Beam 和投射物人工验收。

## 3. 禁止做法

- 在 `TurretBaseBlockEntity` 或 `TurretHeadBlockEntity` 中按具体炮塔 ID 添加行为分支；
- 为了“解耦”复制库存、能量、附件或持久化字段；
- 让规则类直接查询 `Level`、`Entity` 或玩家权限；
- 让 common/server 直接 import `net.minecraft.client`；
- 修改已有存档 key、Payload 字段顺序或注册 ID；
- 没有 profiler 和行为测试就拆分 `TurretProjectileEntity` 热路径；
- 以新增一层包装类代替明确的状态所有权。

## 4. 交付清单

新增炮塔的 PR/批次记录至少要回答：

| 问题 | 必须给出的证据 |
| --- | --- |
| 定义是否驱动注册？ | `TurretDefinition` 与注册代码路径 |
| 核心循环是否无具体炮塔分支？ | `architecture_phase5_audit.ps1` 或等价报告 |
| 资源成本是否纯规则、事务是否仍由 Base 执行？ | 规则测试和 Base 调用点 |
| 投射物上下文是否完整？ | Projectile GameTest 和持久化测试 |
| 是否改变存档或网络？ | key ledger、Payload 静态审查、旧存档报告 |
| 是否改变性能？ | 固定压力指标和 JFR/解释 |
| 客户端是否正常？ | 一次集中人工客户端验收 |

现有土豆炮是参考样例：定义 → 注册 → 战斗映射 → GameTest → 压力 fixture。该样例证明扩展路径，不授权复制具体玩法或绕过合同。
