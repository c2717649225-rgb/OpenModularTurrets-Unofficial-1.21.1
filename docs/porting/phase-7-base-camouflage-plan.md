# Phase 7：基地伪装与动态光照

## 1. 完成口径

恢复 1.12.2 基地伪装核心语义：所有者可用合法 BlockItem 设置基地外观，潜行空手清除；
伪装状态、发光值和遮光值可存盘、同步并在重载后保持；tier 4/5 可在控制界面管理
0–15 光照参数。客户端只渲染服务端同步结果。

## 2. 服务端状态

- `TurretBaseBlockEntity` schema 升至 4。
- 新增可空 `camouflageState`、`camouflageLightValue`、`camouflageLightOpacity`。
- 复制状态必须满足：
  - 非空气；
  - `RenderShape.MODEL`；
  - 无 BlockEntity；
  - 不是任意 OMT 基地；
  - 在真实世界位置具有完整方块碰撞形状。
- 设置与清除仅限 owner；物品不消耗。
- 发光值和遮光值严格限制 0–15；只有 tier 4/5 owner 可通过菜单修改。
- 旧 schema 1–3 默认无伪装、发光 0、遮光 15。

## 3. 方块与渲染

- 基地 BlockState 增加 `camouflaged` 与 `light_level`。
- `camouflaged=true` 时静态基地模型隐藏，由基地 BER 绘制复制方块模型。
- BER 使用复制状态自己的 render types、真实 level/pos tint 与 ModelData。
- 复制状态不改变基地碰撞、硬度、掉落、权限、声音或能力。
- `getLightBlock` 仅消费 BE 的 0–15 遮光值；每次变更通知 light engine。

## 4. 网络与 GUI

- 不新增 Payload 类型；扩展现有 `BaseCommand`：
  - `SET_CAMOUFLAGE_LIGHT`
  - `SET_CAMOUFLAGE_OPACITY`
  - `CLEAR_CAMOUFLAGE`
- 复用统一 `BaseCommandService` 验证容器会话和 owner。
- `TurretBaseMenu` 同步伪装存在性、发光与遮光。
- `TurretBaseScreen` 增加伪装设置页；tier 4/5 owner 可调整参数，所有 owner 可清除。
- 所有显示文本进入 en_us/zh_cn DataGen。

## 5. 验收

- L0 Major 合同通过。
- 至少两个真实 GameTest 覆盖存盘/权限/非法状态和光照边界。
- L1/L2、DataGen、L2.5、L3 与 L4 全绿。
- 客户端烟测确认石头、玻璃、草方块等模型正常显示且无递归/缺材质。

## 6. 非目标

- 不复制目标方块的碰撞、方块实体、随机 tick、掉落或交互。
- 不支持流体、箱子、床、门等非完整单方块模型。
- 不恢复已经废弃的独立 OMLib 模型加载器。
- Phase 7 implementation evidence: production camouflage state, persistence, network commands, menu synchronization, client renderer and UI are implemented in the corresponding `omtteam.openmodularturrets` classes.
- GameTest traceability: `base_camouflage_persistence` -> `OpenModularTurretsGameTests#baseCamouflagePersistenceAndValidation`; `base_camouflage_authority` -> that test plus `#baseCamouflageLightContract`; `base_camouflage_render_contract` -> `#baseCamouflageLightContract`.
- Verification: compile/static PASS; DataGen and asset reconciliation PASS (287 JSON files); L4 PASS with all 33 required tests.
- Verification now also includes dedicated-server smoke PASS with zero ERROR lines. Remaining verification is the manual client visual matrix (stone, glass, tinted full blocks, light values 0/15).
