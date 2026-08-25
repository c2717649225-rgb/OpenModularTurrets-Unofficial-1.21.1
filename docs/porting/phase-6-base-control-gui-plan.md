# Phase 6：基地控制、信任安全与完整 GUI

## 1. 完成口径

本阶段恢复 1.12.2 基地控制面的完整核心语义，并将其迁移到服务端权威的
1.21.1 Menu/Payload 管线：

- 四种稳定运行模式：`ALWAYS_ON`、`ALWAYS_OFF`、`INVERTED`、
  `NONINVERTED`；
- 邻接红石信号、有效运行状态与炮塔开火严格遵循旧版真值表；
- 主基地、目标配置、信任管理和物品栏扩展器使用保留的旧版纹理与槽位；
- VIEW/USE/ADMIN 权限分别对应只读、修改设置、管理信任与拆除操作；
- 本地/全局信任列表二选一，`NONE` 仍是受信成员，删除是独立操作；
- 所有 C2S 写操作绑定当前 `containerId`、位置、维度、距离和真实 BlockEntity；
- 快照具有会话键、scope 与单调 revision，旧界面或乱序包不能覆盖新状态。

## 2. 服务端状态与迁移

### 2.1 运行模式

- 新增带显式稳定 id 的 `BaseMode`，id 与旧 ordinal 一致：
  `ALWAYS_ON=0`、`ALWAYS_OFF=1`、`INVERTED=2`、`NONINVERTED=3`。
- 缺省模式为 `INVERTED`；循环顺序保持旧版顺序。
- `active` 不再是独立设置，而由 mode 与 `level.hasNeighborSignal(pos)` 派生。
- 放置、邻居更新和服务端 tick 会重采样红石；仅在值变化时同步。
- BlockEntity 数据版本升至 3，保存 mode、trust scope、统计和信任 revision。
- 读取当前 v2 的 `active` 时迁移为 `ALWAYS_ON`/`ALWAYS_OFF`，读取旧
  `mode`/`mode_id` 时按 0..3 稳定映射，未来未知值安全回退 `INVERTED`。
- Memory Card schema 升至 3 并保存 mode；schema 1/2 继续兼容。

### 2.2 信任语义

- 本地与全局条目均保存 UUID、last-known name、AccessLevel。
- `NONE` 条目保留成员身份并继续获得友军保护，但没有 GUI 权限。
- REMOVE 独立于 SET_LEVEL；写相同值、删除不存在条目均为 no-op。
- 基地保存 `use_global_trust`，默认 false；有效权限只读取选中的 scope。
- local ADMIN 可改本地列表；全局列表只允许 owner 本人修改。
- 每个 scope 有单调 revision，成功语义变更才递增。

## 3. Menu 与网络

### 3.1 ContainerData

同步 tier、mode、redstone、effective active、range/max range、target flags、
multi-target、access、trust scope、energy/max energy、kills、player kills 和 shots。
超过 16 位的数据按 16-bit word 拆分，禁止截断。

### 3.2 C2S/S2C

- `BaseCommandPayload(containerId,pos,command,value)`：
  mode、range、target flags、multi-target、trust scope、drop turrets/base。
- `TrustSnapshotRequestPayload(containerId,pos,scope)`：
  打开信任页或切 scope 时请求权威快照。
- `TrustCommandPayload(containerId,pos,scope,operation,target,access,expectedRevision)`：
  ADD、REMOVE、SET_LEVEL；用户名有长度上限并在服务端解析为 UUID。
- `TrustSnapshotPayload(containerId,pos,owner,scope,revision,entries)`：
  最多 64 条，稳定排序，条目含 UUID、受限显示名和合法 access id。

统一 validator 必须验证当前 menu、containerId、stillValid、维度、距离、位置、
区块已加载、BlockEntity 身份与权限。限流使用严格滚动窗口，不能在时间窗边界双倍突发。

## 4. GUI

### 4.1 基地主页

- 主纹理为各 tier 的 `textures/gui/turret_base_tier_*.png`，主体 176x166；
- 恢复 3x3 ammo、tier 化 addon/upgrade 槽与玩家背包坐标；
- 恢复 14x51 能量条、范围、Owner、Mode、Redstone、Active、Kills、
  Player Kills、Shots 与信任 scope；
- 右侧控制区提供 mode、configure、single/multi、drop base、drop turrets；
- 写控件严格按 VIEW/USE/ADMIN 禁用。

### 4.2 配置与信任页

- 配置页复用 `configure.png`，提供三类目标开关与信任入口；
- 尚未实现的 camouflage light value/opacity 显示为不可用并明确留给后续伪装阶段，
  不伪造状态；
- 信任页复用 `trusted_players.png`，支持 local/global scope、滚动列表、按用户名添加、
  删除、权限升降和返回；
- 快照仅在当前 menu 会话、位置、owner、scope 匹配且 revision 不回退时应用。

### 4.3 Inventory Expander

恢复 `expander_inv.png`、3x3 扩展槽和旧版玩家背包坐标，保留现有权限与距离校验。

## 5. 安全与验收

- GameTest 覆盖四模式真值表、红石变化、v2/v3 迁移、信任 NONE 成员、
  local/global scope、权限矩阵、no-op/revision、严格 operand 边界和拆除语义；
- 命令服务与 handler 共用同一 validator，测试不得只测旁路纯函数；
- 客户端烟测验证五个 tier、三页切换、能量 0/50/100%、槽位对齐和零 missing texture；
- DataGen、静态扫描、资源对账、dedicated server、GameTest 与 fast pipeline 全绿。

## 6. 非目标与后续

- 基地 camouflage 方块状态、光照值和光不透明度在后续伪装阶段实现；
- 第三方计算机/探针兼容在兼容性阶段实现；
- 最终人工截图矩阵与多 GUI scale 检查保留为发布验收项。

## 7. 实施与自动化验收记录

- 已恢复四种稳定 ID 的基地运行模式、红石真值表、v1/v2/v3 数据迁移及
  Memory Card schema 1/2/3 兼容。
- 已实现带 `containerId`、位置、权限与 revision 校验的基地控制和信任
  CustomPacketPayload；全局信任域仅所有者可管理，本地管理员仅能管理当前启用的
  本地信任域。
- 已恢复五级基地、配置、信任和库存扩展器 GUI 纹理；VIEW 只读、USE 可操作物品
  与目标配置、ADMIN/owner 执行管理操作。
- `omtteam.openmodularturrets.gametest.SecurityTrustGameTests#clientTrustSnapshotReducerContract`：
  快照会话、scope、revision 与非法枚举边界。
- `omtteam.openmodularturrets.gametest.ConfigDefinitionGameTests#menuWideContainerDataRoundTrip`：
  32/64 位 ContainerData 拆装。
- `omtteam.openmodularturrets.gametest.SecurityTrustGameTests#trustScopeMembershipAndSecurityRevision`：
  NONE 成员语义、本地/全局互斥、no-op/REMOVE 与 revision。
- L1/L2/L2.5/DataGen、L3 dedicated server 与 L4 31/31 GameTests 已通过；
  合同进入 `verifying`。五级基地在 GUI scale 1/2/3 下的人工截图矩阵保留到发布审计。
