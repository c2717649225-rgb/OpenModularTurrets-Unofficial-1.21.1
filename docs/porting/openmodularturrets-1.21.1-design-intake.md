# Open Modular Turrets 1.12 → 1.21.1 NeoForge 移植设计梳理（待确认）

状态：`draft / analysis-only`

目标平台：Minecraft 1.21.1、NeoForge 21.1.234、Java 21

参考源码：

- `D:\c128\mods\reference-sources\OpenModularTurrets-1.12`
  - 分支：`1.12`
  - 提交：`3625d9dd3904a98bdf9adce11a58f3b7ed8444c1`
- `D:\c128\mods\reference-sources\OMLib-1.12`
  - 分支：`1.12`
  - 提交：`2a70145d7ee2993c765048146e184bfd6597c059`

本文件只定义移植边界与映射，不包含 Java 实现代码。用户确认后才进入 Major 合同和实现阶段。

## 1. 最小可玩闭环与范围

最小可玩闭环：

> 玩家合成并放置一个分级炮塔基座，给基座供能、装填弹药、安装炮塔头，配置目标与权限；炮塔由服务端寻找合法目标、消耗能量/弹药并攻击，客户端正确显示炮塔转向、射线/弹道、声音和 GUI 状态；保存重载与重新连接后状态一致。

数据载体：

| 数据 | 权威载体 |
|---|---|
| 基座能量、物品栏、目标规则、本地权限、伪装、统计 | `TurretBaseBlockEntity` |
| 炮塔头模式、瞄准角、优先级 | `TurretHeadBlockEntity` |
| 弹药扩展器物品栏 | `InventoryExpanderBlockEntity` |
| 全局信任表、共享所有权 | `SavedData` |
| 记忆卡复制出的基座配置 | 自定义 `DataComponentType<MemoryCardProfile>` |
| 基座等级、扩展器类型/等级、朝向等静态身份 | 注册项或 `BlockState`，不重复存入 BE |
| 当前目标、路径/邻接缓存、射击冷却缓存 | 运行时临时状态，不持久化 |

玩家交互：

- 基座与扩展器容器 GUI；
- 基座配置、目标和权限页；
- 记忆卡蹲下交互；
- 炮塔头、弹道、射线、声音和粒子世界反馈；
- 能量、物品和流体自动化通过 NeoForge capability。

第一版明确不做：

- 直接加载或原地升级 1.12.2 的世界存档；
- 第一阶段恢复 ComputerCraft、OpenComputers、IC2、Tesla、旧 RF API、HWYLA/Waila；
- 在没有第二个 1.21 消费者前重建独立的通用 OMLib；
- 移植 OMLib 的 `debug_tool`、`multi_tool` 和作为内部掉落技巧使用的 `fake_sword`；
- 在未定义实际流体与用途前恢复未完成的 Potentia 流体系统。

## 2. 前置处理结论：合并 OMLib 的必要子集

建议：**合并，不保留独立前置模组/JAR**。

但“合并”不是把两个旧仓库逐文件粘贴，而是把 OMT 仍需的能力重写为同一模组内的内部模块：

- `security`：所有权、本地信任、全局信任、共享所有权；
- `machine`：能量、物品栏、机器模式；
- `camo`：伪装状态与客户端模型数据；
- `networkgraph`：线缆/控制器图（兼容阶段）；
- `client`：GUI 公共控件、射线效果；
- `api`：仅保留确实需要给后续模组调用的 OMT API。

理由：

1. OMT 源码中有 180 条 OMLib import，涉及 58 个不同类型，依赖并不是几个可替换方法，而是贯穿 BE 继承、权限、GUI、渲染、网络和能源。
2. NeoForge 1.21.1 已直接提供 Item/Energy/Fluid capability、Attachments、Payload 和现代菜单能力；旧 OMLib 的许多通用层不应逐字复刻。
3. 分成两个 JAR 会产生两套生命周期、网络协议、存档迁移、发布版本与测试矩阵，但目前没有第二个已确认的 1.21 消费者。
4. OMLib 的 `network_cable` 对 OMT 本体没有完整闭环，主要服务外部控制器/联动；它应在兼容阶段并入 `openmodularturrets` 命名空间，而不是为了一个方块维持整个前置。
5. 等将来确实移植 OMPD 等第二个消费者后，再从已验证的内部模块提取独立库，风险更低。

### 2.1 许可证是开始实现前的硬决策

- OMT `1.12` 分支根 `LICENSE` 是 GPL-3.0。
- OMLib `1.12` 分支根 `LICENSE` 是 MIT。
- 当前宿主工程 `gradle.properties` 仍写着 `mod_license=All Rights Reserved`。

如果复用或改写 OMT 的 GPL 源码并发布，目标工程不能继续以 All Rights Reserved 交付；应采用 GPL-3.0 兼容许可并保留 OMLib 的 MIT notice。旧材质/声音的授权还需单独复核，不能只根据源码许可证推断素材许可。

因此本方案的默认前提是：**允许目标移植版以 GPL-3.0 兼容方式发布，并完成素材授权清单**。若不能接受，必须改为不复制代码的 clean-room 行为重实现，并替换/重新授权素材，工作量和范围会明显变化。

## 3. 注册项总览

### 3.1 旧版真实注册面

旧 OMT 注册：

- 15 个 Block registry id；
- 15 个对应 BlockItem registry id；
- 6 个普通 Item registry id，但 metadata 实际承载 38 个玩家可见物品变体；
- 15 个 BlockEntity id；
- 5 个已注册 projectile EntityType；
- 18 个 SoundEvent；
- 2 个有容器的 GUI（基座、物品扩展器）和 3 个配置型屏幕；
- 12 个 OMT 网络包。

旧 OMLib 注册：

- 1 个 `network_cable` Block + BlockItem；
- 1 个线缆 BlockEntity；
- 3 个物品（debug tool、fake sword、multi tool）；
- 10 个网络包。

旧代码存在的注册问题：

- `grenade_turret` 的方块及物品渲染启用条件误用了 `laser_turret.enabled`；
- `PotatoProjectile` 和 `DisposableTurretProjectile` 会被生成，但没有出现在旧 `ModEntities` 注册表；
- `plasma_turret` 的物品 TESR/渲染器引用了 grenade 资源；
- 运行时配置决定“是否注册某个炮塔”，会导致客户端/服务端注册表随配置漂移。

1.21.1 方案中所有注册项必须静态存在；配置只决定配方可用性或运行行为，不决定注册表是否出现。

### 3.2 建议的 Block 注册（核心 28 个）

旧版用 metadata 表示 5 个基座和 10 个扩展器。1.21.1 中建议拆成独立 Block，避免在物品、配方、模型、掉落和放置时携带“类型 metadata/组件”。

#### 基座（5）

- `turret_base_tier_one`
- `turret_base_tier_two`
- `turret_base_tier_three`
- `turret_base_tier_four`
- `turret_base_tier_five`

#### 扩展器（10）

- `expander_inv_tier_one`
- `expander_inv_tier_two`
- `expander_inv_tier_three`
- `expander_inv_tier_four`
- `expander_inv_tier_five`
- `expander_power_tier_one`
- `expander_power_tier_two`
- `expander_power_tier_three`
- `expander_power_tier_four`
- `expander_power_tier_five`

#### 基座附属与控制（2）

- `base_addon_loot_deleter`
- `lever_block`

#### 炮塔头（11）

- `disposable_item_turret`
- `potato_cannon_turret`
- `machine_gun_turret`
- `incendiary_turret`
- `grenade_turret`
- `relativistic_turret`
- `rocket_turret`
- `teleporter_turret`
- `laser_turret`
- `rail_gun_turret`
- `plasma_turret`

每个 Block 注册一个同 id 的 BlockItem，因此核心 BlockItem 也是 28 个。

兼容阶段可追加：

- `network_cable`：由旧 `omlib:network_cable` 迁入 `openmodularturrets` 命名空间。没有控制器消费方时不进入最小闭环。

### 3.3 建议的普通 Item 注册（38 个）

旧 metadata item 全部拆为独立 Item。它们的类型由 registry id 表达，不需要自定义“物品种类”Data Component。

#### 分级中间件（15）

- `sensor_tier_one` ～ `sensor_tier_five`
- `chamber_tier_one` ～ `chamber_tier_five`
- `barrel_tier_one` ～ `barrel_tier_five`

#### 普通中间件（1）

- `io_bus`

#### Addon（8）

- `addon_concealer`
- `addon_damage_amp`
- `addon_potentia`
- `addon_recycler`
- `addon_redstone_reactor`
- `addon_serial_port`
- `addon_solar_panel`
- `addon_fake_drops`

说明：

- `addon_potentia` 第一版只保留物品/配方兼容还是完全延后，需要确认；旧实现的功能代码已被注释/未完成。
- `addon_serial_port` 依赖网络控制器闭环，建议和 `network_cable` 一起放到兼容阶段。

#### Upgrade（5）

- `upgrade_accuracy`
- `upgrade_efficiency`
- `upgrade_fire_rate`
- `upgrade_range`
- `upgrade_scatter_shot`

#### Ammo（6）

- `ammo_blazing_clay`
- `ammo_bullet`
- `ammo_ferro_slug`
- `ammo_grenade`
- `ammo_rocket`
- `ammo_fake_disposable`

#### 可用物品（3）

- `throwable_bullet`
- `throwable_grenade`
- `memory_card`

总计：

- 核心 Block：28；
- 核心 BlockItem：28；
- 普通 Item：38；
- 玩家可见核心 Item registry id：66。

### 3.4 BlockEntity 注册映射

不建议照搬旧版“一种炮塔子类一个 BE type”。现代结构可把身份放在 Block/定义表中，把动态状态放在共享 BE 中。

| 旧 BE | 旧数量 | 1.21.1 BE type | 说明 |
|---|---:|---|---|
| `TurretBase` | 1 | `turret_base` | 同时支持 5 个基座 Block，tier 从 Block 定义取得 |
| 11 个 TurretHead 子类 | 11 | `turret_head` | 同时支持 11 个炮塔 Block，行为由 `TurretDefinition`/Block 身份分派 |
| `Expander` | 1 | `inventory_expander` | 只给 5 个物品扩展器；5 个能量扩展器无动态状态，可不带 BE |
| `BaseAddon` | 1 | 无 | 类型由独立 Block 表达，朝向用 BlockState，效果由相邻基座读取 |
| `LeverTileEntity` | 1 | 无 | 朝向/开关使用 BlockState 和邻居更新，避免每 tick BE |
| `TileEntityCable` | 1 | `network_cable`（兼容阶段） | 图缓存为运行时状态，不持久化 |

核心因此为 3 个 BE type；兼容线缆加入后为 4 个。

### 3.5 其他注册项

#### EntityType（建议 7）

- `disposable_item_projectile`
- `potato_projectile`
- `bullet_projectile`
- `blazing_clay_projectile`
- `grenade_projectile`
- `rocket_projectile`
- `plasma_projectile`

激光、磁轨炮、相对论炮和传送炮走服务端射线判定，不注册投射物实体。

#### MenuType（2）

- `turret_base`
- `inventory_expander`

目标配置、权限配置和常规配置改成基座菜单的标签页/子屏幕，绑定同一个服务端 Menu 生命周期。旧 `MessageOpenGUITile` / `MessageCloseGUITile` 不再需要。

#### DataComponentType（1）

- `memory_card_profile`

#### SoundEvent（18，沿用旧 id）

- `turret_deploy`
- `turret_retract`
- `warning`
- `bullet_hit`
- `rail_gun_hit`
- `laser_hit`
- `disposable`
- `grenade`
- `machine_gun`
- `incendiary`
- `laser`
- `potato`
- `rail_gun`
- `plasma_launch`
- `relativistic`
- `rocket`
- `teleport`
- `amped`

#### DamageType（数据驱动，建议 4）

- `turret_projectile`
- `turret_beam`
- `turret_explosion`
- `turret_teleport`

它们替代旧 fake sword、`turret_hit`/`dont_drop_loot` 实体 tag 技巧，并让死亡消息、护甲/无敌帧、掉落策略和联动有明确语义。

#### ParticleType

第一版不注册自定义粒子。旧代码使用的 smoke、flame、portal、redstone 等均可用原版粒子；批量粒子优先使用服务端原生粒子发送。

#### CreativeModeTab

- `openmodularturrets`：一个页签，按基座 → 扩展器 → 炮塔 → addon/upgrade → ammo → components 排序。

#### RecipeSerializer

第一版不需要自定义 RecipeSerializer。标准有序/无序配方、标签和 NeoForge 条件足够；禁止继续用运行时配置动态改变注册表。

## 4. 旧 NBT → 1.21.1 数据模型

### 4.1 关键原则

**Data Components 只用于 ItemStack 数据，不是 NBT 的通用替代品。**

- 记忆卡旧 ItemStack NBT → 自定义 Data Component；
- BE 旧 NBT → `saveAdditional/loadAdditional(CompoundTag, HolderLookup.Provider)`；
- 全局世界权限旧 world capability → `SavedData`；
- 物品栏 → `ItemStackHandler.serializeNBT/deserializeNBT(HolderLookup.Provider, ...)`；
- 能量/容量 → 能量值持久化，容量和 IO 从注册定义/配置派生；
- Block 身份、tier、朝向 → Block registry id 或 BlockState；
- 客户端展示状态 → BE update tag、Menu DataSlot 或定向 S2C Payload。

每个自有持久化根写入 `schema_version=1`，迁移函数显式处理旧字段，不能依赖“字段不存在就默认为 0”掩盖损坏。

### 4.2 记忆卡：唯一必需的自定义 Data Component

旧结构：

```text
ItemStack.tag.data
  targetingSettings:
    targetPlayers
    targetMobs
    targetPassive
    range
    maxRange
  multiTargeting
  mode
  trustedPlayers:
    useGlobal
    trustedPlayers[]:
      name
      UUID
      accessLevel
```

建议组件：

```text
memory_card_profile:
  schemaVersion: int = 1
  targetPlayers: boolean
  targetHostiles: boolean
  targetPassives: boolean
  desiredRange: int
  multiTargeting: boolean
  machineMode: enum/string id
  useGlobalTrust: boolean
  trustedPlayers: bounded list<TrustedAccess>
    uuid: UUID
    lastKnownName: bounded string
    accessLevel: enum/string id
```

规则：

- 使用不可变 record + `Codec` + `StreamCodec`；
- `desiredRange` 在写入和应用时都限制为非负值；
- **不把旧 `maxRange` 当成记忆卡权威值**。目标基座的最大范围由 tier、配置和扩展器重新计算，再 clamp `desiredRange`，避免高阶卡覆盖低阶基座上限；
- 权限列表设置上限（建议 64），名字和枚举长度有界；
- 只有基座 owner/admin 能写入或应用记忆卡；
- 蹲下清空时只移除 `memory_card_profile`，不清空 ItemStack 的全部组件；
- metadata 0/1/2 不再存在，三个 usable item 是三个独立注册项；
- 旧 flat 字段 `attacksPlayers/attacksMobs/attacksNeutrals/currentMaxRange/upperBoundMaxRange` 只作为导入器兼容输入。

### 4.3 TurretBaseBlockEntity 持久化

| 旧字段 | 1.21.1 归属 | 处理 |
|---|---|---|
| `owner.{uuid,name,team_name}` | BE `ownerUuid` + 可选 `lastKnownName` | UUID 权威；team 每次从 scoreboard 查询，不持久化旧快照 |
| `maxStorage`, `maxIO` | 基座定义 + ServerConfig + 邻接扩展器 | 不持久化，避免配置更新后旧值覆盖 |
| `energyStored` | BE energy storage | 持久化并 clamp 到重算容量 |
| `Items` / `Inventory.Items` / `Slots` | `ItemStackHandler` | 新 schema 只写一个稳定 key；旧 key 由迁移器读取 |
| `active` | 派生状态 | 由 mode + 当前红石输入计算，不持久化 |
| `redstone` | 邻居输入缓存 | 邻居更新时重算，不持久化 |
| `mode` | BE enum id | 持久化字符串 id；不再持久化 ordinal |
| `tank` | 暂不移植 | 旧 Potentia 未完成；确认后可独立加入 |
| `useGlobal`, `trustedPlayers[]` | BE local security policy | 持久化 UUID + access id，有界列表 |
| `shouldConcealTurrets` | 派生状态 | 由 addon/配置计算，不持久化 |
| `multiTargeting` | BE targeting policy | 持久化 |
| `forceFire` | BE targeting policy | 若 UI/API 仍暴露则持久化，否则移除 |
| `tier` | Block 定义 | 不持久化 |
| `kills`, `playerKills` | BE statistics | 两者都持久化；修复旧版只读不写/写错 playerKills 的 bug |
| `targetingSettings.target*` | BE targeting policy | 持久化 |
| `targetingSettings.range` | BE desired range | 持久化并 clamp |
| `targetingSettings.maxRange` | 派生状态 | 不持久化 |
| `camoBlockRegName` + `camoBlockMeta` | BE `BlockState` | 使用现代 BlockState NBT/Codec；不存在的 block 安全回退到默认基座外观 |
| `light_value`, `light_opacity` | BE camo settings | 持久化且限制 0..15；若新版渲染/光照不支持动态 opacity，应删除该 UI，而不是伪同步 |

旧代码的 `targetingSettings.equals` 将 `maxRange` 错误地与另一个对象的 `range` 比较；新 record 自动值相等可消除此 bug。

### 4.4 TurretHeadBlockEntity 持久化

| 旧字段 | 1.21.1 处理 |
|---|---|
| `ticksBeforeFire` | 运行时冷却；通常不持久化，重载后从安全初值开始 |
| `shouldConceal` | 由基座/addon 派生，不持久化 |
| `autoFire` | 若玩法保留，持久化 boolean |
| `priorityMaxHP`, `priorityHPRemaining`, `priorityDistance`, `priorityArmor`, `priorityPlayer` | 用有界优先级 record/list，存稳定 enum id，不存 ordinal |
| `pitch`, `yaw` | 可持久化用于重载后的视觉连续性；同时进入最小 update tag |
| `minPitch/maxPitch/minYaw/maxYaw` | 由安装朝向和炮塔定义派生，不持久化 |
| base side / turret tier | 由 BlockState、相邻基座和炮塔定义派生 |
| 当前 target、速度、accuracy/scattershot cache | 运行时状态，不持久化 |

### 4.5 InventoryExpanderBlockEntity 持久化

| 旧字段 | 1.21.1 处理 |
|---|---|
| `Items` | 9-slot ItemStackHandler，持久化 |
| `powerExpander` | 独立 Block id 已表达；不持久化 |
| `tier` | 独立 Block id 已表达；不持久化 |
| `direction` | BlockState FACING；不持久化到 BE |
| owner | 使用相邻基座 owner，不在扩展器复制一份 |

物品扩展器 slot limit 必须审查。旧代码尝试把单槽上限设为 `2^(tier+2)`，但现代 ItemStack 本身仍受物品最大堆叠数约束；不能依靠超大 stack 复现容量，应改成更多虚拟槽、分页库存或明确保持 9 槽标准堆叠。

### 4.6 世界级权限

旧 `GlobalTrustRegister` 与 `OwnerShareRegister` 是挂在 World 上的 capability。

1.21.1 建议合并为一个 `SecuritySavedData`：

```text
schemaVersion
globalTrustByOwner: Map<owner UUID, List<TrustedAccess>>
sharedOwnersByOwner: Map<owner UUID, Set<shared UUID>>
```

规则：

- 只在 logical server 修改；
- 每次变更 `setDirty()`；
- UUID 权威，名字仅作 UI 缓存；
- 客户端不会自动同步 SavedData，只在授权玩家打开权限页时发送该 owner 的最小快照；
- 禁止像旧代码那样把所有玩家的完整全局信任表广播给所有客户端；
- 全局变更必须验证 sender 就是 owner/有管理权限，不能信任 payload 里的 `owner` 字段。

### 4.7 网络线缆图

旧 `OMLibNetwork` 的 device map、递归扫描与 controller 引用不应持久化：

- 方块位置和方块本身是权威输入；
- 图是可丢弃缓存；
- 放置/破坏、邻居变化、chunk load/unload 时局部失效；
- 邻接 capability/设备查询必须缓存，避免每 tick 全图递归；
- 若兼容阶段没有实际 controller 消费者，整套图系统暂不实现。

## 5. 网络与 BlockEntity 同步方案

### 5.1 不需要 CustomPacketPayload 的同步

| 场景 | 机制 |
|---|---|
| 基座/扩展器物品栏 | Menu slot 同步 |
| 能量、range、kills、flags 等 GUI 标量 | `ContainerData` / DataSlot |
| 打开基座/扩展器 GUI | 服务端 `openMenu` + 菜单 opening data |
| GUI 关闭与 viewer 生命周期 | Menu 自身生命周期，不发 open/close 包 |
| 基座初始世界渲染状态 | `getUpdateTag` + `ClientboundBlockEntityDataPacket.create(this)` |
| 基座伪装改变 | `setChanged()` + `sendBlockUpdated`，update tag 只含渲染所需字段 |
| 普通烟雾/火焰/传送门粒子 | 原版粒子网络 |
| Projectile 位置 | EntityType 自带实体追踪 |

禁止把完整 BE 存档 NBT直接作为 update tag。初始/变更 update tag 只包含：

- 基座：camo BlockState、light value（若保留）、active/concealed 等渲染字段；
- 炮塔头：初始 yaw/pitch、concealed、autoFire（若影响模型）；
- 不含库存、owner UUID、信任列表、SavedData、服务端缓存。

### 5.2 必需的 CustomPacketPayload（建议 5 种）

#### C2S 1：`BaseCommandPayload`

字段：

- `BlockPos pos`
- `BaseCommand action`
- `int operand`

覆盖旧包：

- ToggleAttackMobs / NeutralMobs / Players；
- AdjustRange；
- SetBaseTargetingType；
- ToggleMode；
- AdjustLightValue / LightOpacity；
- DropTurrets / DropBase。

`action` 决定 operand 语义，例如 boolean 只能 0/1，range 必须非负并由服务端 clamp，light 必须 0..15，mode 必须是合法稳定 id 对应值。

服务端验证：

1. sender 的当前 dimension 与 `pos` 所在 level 一致；
2. chunk 已加载，目标确实是 TurretBase；
3. sender 与目标距离在交互范围内，且仍打开对应 Menu；破坏操作可要求更高权限；
4. 按 action 验证 OPEN_GUI / CHANGE_SETTINGS / ADMIN 权限；
5. 不接受客户端提供 owner、maxRange、能量或其他权威值；
6. 每玩家/每基座限流，滑杆变更合并；
7. 修改后 `setChanged()`，只同步需要的 Menu/渲染状态。

#### C2S 2：`TrustCommandPayload`

字段概念：

- scope：LOCAL_BASE / GLOBAL_OWNER / SHARED_OWNER；
- base pos（仅 LOCAL_BASE）；
- operation：ADD / REMOVE / SET_ACCESS / SET_USE_GLOBAL；
- target UUID；
- access level 或 boolean。

验证：

- sender 身份从 payload context 获取，不从包里传 owner；
- local 操作验证基座存在、距离、Menu 和 ADMIN 权限；
- global/shared 操作把 sender UUID 作为 owner key；
- target 数量、名字查询、access enum、payload 大小均有上限；
- 变更后只回传给当前授权 GUI viewer。

#### S2C 3：`TrustSnapshotPayload`

用途：

- 打开权限页时发送当前 base/owner 的局部权限视图；
- 成功变更后回送新快照；
- 不在登录时广播全服完整映射。

限制：

- entry 数建议最大 64；
- payload 建议不超过 8 KiB；
- 超限时分页，而不是扩大包；
- 客户端只作展示，不成为权威副本。

#### S2C 4：`TurretAimPayload`

字段：

- `BlockPos turretPos`
- `float yaw`
- `float pitch`

用途：

- 服务端权威瞄准角的增量同步；
- 只发给 tracking chunk 的客户端；
- 角度变化超过阈值才发，建议最多 10 次/秒/炮塔；
- 客户端在相邻快照间插值；
- 初始角度仍来自 BE update tag。

覆盖旧 `MessageUpdateTurret`。

#### S2C 5：`BeamEffectPayload`

字段概念：

- start Vec3；
- end Vec3；
- ARGB color；
- duration ticks；
- bloom flag / beam style。

用途：

- 激光、磁轨炮、相对论炮等命中结果的纯视觉事件；
- 服务端先完成射线判定和伤害，再向附近 tracking 玩家发效果；
- 客户端不得根据 beam 自行判伤。

覆盖旧 `MessageRenderRay`。旧 `MessageSpawnParticleQuad` 用原版粒子发送替代。

### 5.3 线程模型

NeoForge 21.1.234 的 `PayloadRegistrar` 默认 `HandlerThread.MAIN`，因此上述 handler 默认已在接收端主线程运行，不再照搬 1.12 的 `addScheduledTask`，也不重复 `enqueueWork`。

只有以后显式 `.executesOn(HandlerThread.NETWORK)` 时，才允许在网络线程做纯解码/计算，并通过 `context.enqueueWork` 回主线程访问 Level/Entity/玩家状态。

### 5.4 StreamCodec 约束

- play 阶段 payload 使用 `RegistryFriendlyByteBuf`；
- 7 个及以上独立字段不能用 `StreamCodec.composite`，应手写 `StreamCodec.of` 或把字段收束成嵌套 record；
- 所有列表先读有界长度，再分配；
- enum 使用稳定字符串/id 或显式编号，不直接信任 ordinal；
- BlockPos、UUID、ResourceLocation 使用现成 codec 时先以 21.1.234 真源码签名为准。

## 6. Capability 与机器边界

### TurretBase

- `Capabilities.ItemHandler.BLOCK`：暴露受限的弹药输入视图；
- `Capabilities.EnergyStorage.BLOCK`：暴露能量输入；
- FluidHandler 暂不注册，直到 Potentia 有明确闭环；
- 内部 13 槽保持旧布局语义：
  - 0..8 ammo；
  - tier 2..5：9..10 addon；
  - tier 2..4：11 upgrade；
  - tier 5：11..12 upgrade；
- 外部 capability 不应暴露 addon/upgrade 管理槽，避免自动化绕过校验；
- inventory/energy 的 `onContentsChanged`/receive/extract 触发 `setChanged()`。

### Inventory Expander

- `Capabilities.ItemHandler.BLOCK`；
- 只接受对应炮塔可用的 ammo tag；
- 邻接基座查询缓存并在邻居变化时失效。

### Power Expander

- 无 BE、无 capability；
- 基座从相邻 block 定义计算额外容量；
- 不把 `maxStorage` 写回存档。

### Ammo 与配方标签

建议从硬编码 `instanceof AmmoMetaItem`/白名单迁移为数据驱动 ItemTag：

- `openmodularturrets:ammo/all`
- `openmodularturrets:ammo/machine_gun`
- `openmodularturrets:ammo/incendiary`
- `openmodularturrets:ammo/grenade`
- `openmodularturrets:ammo/rocket`
- `openmodularturrets:ammo/rail_gun`
- `openmodularturrets:ammo/disposable`
- potato turret 可直接含 `minecraft:potatoes`

第三方模组通过 tag 扩展弹药，不再调用全局可变 `AmmoList`。

## 7. 服务端权威、安全与性能

### 权威规则

- 客户端只发送意图；
- 服务端独立读取 sender、base、权限、配置上限、弹药、能量和目标；
- GUI 文本、Tooltip、射线和动画均不能参与伤害判定；
- owner 和 trusted 身份使用 UUID；
- 玩家 team 每次从当前 scoreboard 关系判断，不信任保存的旧 team 名。

### 目标搜索

旧代码按固定 tick 周期扫描。新实现要求：

- 服务器配置保留搜索间隔；
- 按炮塔错峰，避免所有炮塔同 tick 扫描；
- 使用限定 AABB 与实体 predicate；
- 先过滤 owner/trusted/team/目标类别，再做昂贵视线判定；
- multi-target 也必须有目标数上限；
- 代表性压力测试至少覆盖 100 个基座/炮塔的稳态 tick。

### 网络图

- 禁止每 tick 递归扫描线缆；
- chunk unload 清理缓存引用；
- 不保存 BlockEntity 实例或跨维度裸引用；
- 若后续支持 controller，以 dimension + BlockPos 标识，并在每次使用时重新验证。

### 旧实体 tag/掉落逻辑

旧 `turret_hit`、`dont_drop_loot`、`fake_drops_N` 会残留在实体上且语义耦合。

新方案：

- DamageType 标识炮塔伤害；
- 必要的 fake drop 等级放入本次攻击上下文或短生命周期 attachment；
- 掉落控制用事件/Global Loot Modifier 检查 DamageSource；
- 不向被击中实体写永久 scoreboard tag；
- 不再生成 fake sword/FakePlayer 只为改变 looting。

## 8. 客户端与资源映射

- 所有 BER、EntityRenderer、Screen、模型适配、beam renderer 放在 `.client` 包；
- common 包不得 import `net.minecraft.client`；
- 11 个旧 TurretHead TESR 合并为一个 `TurretHeadRenderer`，按 turret definition 选择模型/材质；
- 7 个 projectile renderer 可共享 renderer 或按外观分组；
- camo 使用 BE 的最小模型数据/update tag；禁止把整个存档 NBT送给客户端；
- 手持/物品栏炮塔模型使用正常 item model 或合规的特殊 item renderer，不再使用 1.12 TESR item hook；
- 正式实现的第一步仍必须按项目 Assets First 规则复制完整 `textures/` 树，再开始 Java 注册；
- 旧模型格式、路径大小写、plasma/grenade 误引用需逐项对账；
- 配方、模型、blockstate、loot table、tag 用 DataGen；`zh_cn.json` 与 metadata 可手工维护。

## 9. 配置迁移

建议拆分：

- ServerConfig：
  - 每种炮塔 enabled/伤害/范围/射速/能耗/弹药耗量/最大同时数量；
  - 基座容量、IO、最大炮塔数、最大范围；
  - targeting 玩家/敌对/被动全局开关；
  - 爆炸是否破坏方块、掉落策略、trusted/team 伤害策略；
  - 搜索间隔与告警范围；
- ClientConfig：
  - beam bloom、粒子密度、动画/声音表现类选项。

规则：

- 注册项永远存在；
- `enabled=false` 禁止放置/工作或由配方条件隐藏，不从 registry 移除；
- server authoritative 数值由服务端配置决定；
- 配置热重载后重新 clamp range/energy，而不是保存旧 max 值。

## 10. 移植任务清单（待确认）

| # | 任务 | 分级 | 能力/入口文档 | 依赖 |
|---:|---|---|---|---|
| 0 | 确认 GPL-3.0 兼容发布、素材授权、是否保留 Potentia/Serial Port | Major 决策 | design intake | - |
| 1 | 用 workspace setup 把模板改为 `openmodularturrets`，固化包名和许可 | Major | workspace_setup | 0 |
| 2 | Assets First：完整复制 OMT/必要 OMLib textures/sounds，再做资产清单 | Minor | assets SOP | 1 |
| 3 | 注册 28 blocks、66 items、creative tab、18 sounds | Minor/批量 | pb_register_item_block | 2 |
| 4 | `memory_card_profile` Data Component + Codec/StreamCodec + 清空/复制规则 | Major（存档格式） | pb_data_component | 3 |
| 5 | 3 个核心 BE、版本化持久化、item/energy capability 与邻接缓存 | Major | pb_block_entity_sync + capabilities | 3 |
| 6 | 基座/扩展器 Menu、Screen、ContainerData、权限标签页 | Major | menus_screens | 5 |
| 7 | `SecuritySavedData`、本地/全局/共享所有权模型 | Major | saved_data | 5 |
| 8 | 5 个 CustomPacketPayload、验证、限流和最小同步 | Major | pb_network_payload | 6,7 |
| 9 | 统一炮塔头运行时、目标选择、能量/弹药消耗 | Major | custom blocks/entities | 5 |
| 10 | 7 个 projectile EntityType + 4 个 DamageType + 掉落策略 | Major | custom_entities + damage_types | 9 |
| 11 | BER、projectile renderer、beam effect、声音/原版粒子 | Major | BER/entity models | 8,9,10 |
| 12 | Config、DataGen（配方/标签/模型/掉落/语言） | Major | configuration + DataGen | 3..11 |
| 13 | GameTests：持久化、权限、网络滥用、弹药/能量、目标、掉落、邻接缓存 | Major | quality gate | 4..12 |
| 14 | 兼容阶段：network cable、serial port、外部 controller API | Major/backlog | architecture design | 核心闭环稳定 |
| 15 | 可选联动：ComputerCraft/OpenComputers/TOP/Jade/JEI | backlog | integration docs | 14 或核心闭环 |

实现时不会一次加载所有专题文档；每个任务按项目规则只读取最相关的 1～2 篇 reference，并先用 MCP 核对 NeoForge 21.1.234 真源码。

## 11. 确认点

开始写代码前需要确认以下方案：

1. 接受“OMLib 必要能力并入 OMT 单一 JAR”，不发布独立 OMLib；
2. 接受目标工程采用 GPL-3.0 兼容许可并保留 MIT notice，素材另做授权核验；
3. 接受 metadata block/item 拆成独立注册项：28 blocks、38 普通 items；
4. 接受核心只保留 3 个共享 BE type，不照搬 16 个旧 BE type；
5. 接受仅记忆卡使用自定义 Data Component，BE/世界数据分别留在 BE NBT/SavedData；
6. 接受 5 个 payload 的最小网络面，GUI slot/scalar 和世界初始渲染走原生同步；
7. 接受第一阶段暂缓 Potentia、network cable/serial port 与旧可选联动；
8. 接受不承诺直接加载 1.12 世界；若需要旧存档导入，另立离线迁移工具合同。

## 12. 已核对的 1.21.1 真源码边界

本设计已针对 NeoForge 21.1.234 源码核对：

- `BlockEntity.loadAdditional/saveAdditional` 都接收 `HolderLookup.Provider`；
- `BlockEntity.getUpdatePacket()` 默认返回 null，`getUpdateTag()` 默认空 tag；
- `ClientboundBlockEntityDataPacket.create(this)` 是标准 BE 更新包路径；
- `BlockEntity.saveToItem` 使用 BlockEntity data component 路径；
- NeoForge `ItemStackHandler` 的序列化/反序列化接收 `HolderLookup.Provider`；
- `Capabilities.ItemHandler.BLOCK`、`EnergyStorage.BLOCK`、`FluidHandler.BLOCK` 是当前 block capability；
- `RegisterCapabilitiesEvent.registerBlockEntity` 是 BE capability 注册入口；
- `PayloadRegistrar.playToClient/playToServer` 使用 `RegistryFriendlyByteBuf`；
- `PayloadRegistrar` 初始 handler thread 是 MAIN；
- `SavedData.save` 接收 `HolderLookup.Provider`，变更必须 `setDirty()`；
- `NbtUtils.writeBlockState/readBlockState` 可用于现代 camo BlockState。

这些核对只证明平台 API 方向成立；正式实现仍需在每个任务写码前再次按所用具体签名做 MCP 探针。
