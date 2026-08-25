# Phase 7 基地伪装语义审计与现代映射

## 基本元数据

- 模组：OpenModularTurrets-Unofficial
- 源版本：Minecraft 1.12.2 Forge
- 目标版本：Minecraft 1.21.1 + NeoForge 21.1.234
- 旧包与新包：`omtteam.openmodularturrets` 保持不变
- 前置策略：只内聚 OMLib 所需语义，不恢复独立 OMLib

## 1. 旧版语义与源码证据

| 机制 | 旧版行为 | 源码证据 |
| --- | --- | --- |
| 伪装应用 | 所有者手持可放置的完整、非空气、无 TileEntity 方块右击基地；不消耗物品 | `OMLib-1.12/.../BlockAbstractCamoTileEntity.java#L86-L124` |
| 伪装清除 | 所有者潜行空手右击恢复基地默认外观 | `OMLib-1.12/.../BlockAbstractCamoTileEntity.java#L89-L98` |
| 状态持久化 | 保存复制方块状态、`light_value` 与 `light_opacity` | `OMLib-1.12/.../CamoSettings.java#L9-L50` |
| 光照设置 | 发光值和遮光值均为 0–15；旧 GUI 仅 tier 4/5 显示滑块 | `OpenModularTurrets-1.12/.../ConfigureGui.java#L77-L85` |
| 模型渲染 | 自定义 BakedModel 从 ExtendedBlockState 读取复制方块并返回其 quads | `OMLib-1.12/.../CamoBakedModel.java#L48-L68` |
| 动态光照 | 基地从 TileEntity 读取发光与遮光设置 | `OpenModularTurrets-1.12/.../BlockTurretBase.java#L150-L179` |

## 2. 1.21.1 映射

| 旧版方式 | 现代实现 | 约束 |
| --- | --- | --- |
| ExtendedBlockState / unlisted property | `TurretBaseBlockEntity` 同步复制 `BlockState`，基地 BlockState 只保存 `camouflaged` 与发光等级 | 不把任意复制方块属性展开到基地 BlockState |
| 手写方块注册名与 metadata NBT | `NbtUtils.writeBlockState/readBlockState` | 未知或非法状态安全回退为无伪装 |
| 自定义 BakedModel 注入 | `TurretBaseBlockEntityRenderer` 调用 `BlockRenderDispatcher.renderBatched` | 客户端类物理隔离；使用真实 level/pos 色调与 ModelData |
| 两个旧 IMessage | 扩展现有 `BaseCommandPayload` | 继续校验 menu、containerId、位置、距离、BE 身份与 owner |
| TileEntity 动态亮度 | 0–15 `IntegerProperty` 驱动 `lightLevel` | 状态变化触发原版光照重算 |
| TileEntity 动态遮光 | `TurretBaseBlock#getLightBlock` 查询同步 BE 值 | 每次修改显式 `checkBlock`，限制 0–15 |

## 3. 资源与来源

- 不新增第三方位图；继续复用已经过 GPL-3.0 来源记录的五级基地模型和材质。
- 被复制方块的模型与纹理由其注册命名空间提供，不复制到 OMT 资源目录。
- GUI 继续复用 `textures/gui/configure.png`。

## 4. 自动化验收映射

- `omtteam.openmodularturrets.gametest.BaseStateGameTests#baseCamouflagePersistenceAndValidation`
  - 合法/非法复制状态
  - 所有者权限
  - 清除语义
  - schema 4 存盘恢复
- `omtteam.openmodularturrets.gametest.BaseStateGameTests#baseCamouflageLightContract`
  - 0–15 边界
  - 发光 BlockState 同步
  - 遮光值与 tier 4/5 管理规则

## 5. 已知现代化偏差

| 偏差 | 理由与替代 |
| --- | --- |
| 不恢复 OMLib ExtendedBlockState 模型加载器 | API 已移除；BER 是 1.21.1 的物理安全替代 |
| 复制方块不继承其碰撞、掉落、声音或方块实体行为 | 伪装仅为视觉与显式光照设置，避免复制任意行为造成权限或存档漏洞 |
| 不接受带 BlockEntity、流体或不可见渲染的状态 | 防止递归渲染、动态数据缺失与客户端崩溃 |

