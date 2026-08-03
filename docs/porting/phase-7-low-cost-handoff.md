# Phase 7 低成本模型交接清单

## 目的

本清单承接 `Phase 7: Base Camouflage`。核心架构由当前阶段固定，后续模型主要完成重复性实现、测试补齐、资源生成和门禁修复，不应重新设计协议或数据模型。

配套资料：

- `docs/porting/phase-7-base-camouflage-audit.md`
- `docs/porting/phase-7-base-camouflage-plan.md`
- `docs/features/base_camouflage.contract.json`
- `.agents/AGENTS.md`
- `.agents/skills/neoforge/SKILL.md`

所有命令必须通过 `python .agents/run.py ...` 执行。

## 已固定的设计

- 方块实体保存被复制的 `BlockState`、发光等级 `0..15`、遮光等级 `0..15`。
- `TurretBaseBlock` 只镜像两个可观察属性：
  - `camouflaged`
  - `light_level`
- 伪装不复制目标方块的碰撞、方块实体、掉落、交互或业务逻辑。
- 只允许所有者设置或清除伪装。
- 只接受非空气、无方块实体、`MODEL` 渲染且具有完整碰撞体的普通方块。
- 使用现有 `BaseCommandPayload`，不新增只传整数的 payload 类型。
- Tier 4/5 所有者可以配置亮度和遮光；低 Tier 只能使用默认值。
- 客户端通过 `TurretBaseBlockEntityRenderer` 渲染目标方块模型。
- 存档版本已提升为 `4`；缺少新字段的旧存档必须回退为无伪装、亮度 `0`、遮光 `15`。

## 当前已修改的生产文件

- `TurretBaseBlock.java`
- `TurretBaseBlockEntity.java`
- `BaseCommand.java`
- `BaseCommandService.java`
- `TurretBaseMenu.java`
- `TurretBaseScreen.java`
- `ModClientEvents.java`
- `TurretBaseBlockEntityRenderer.java`（新增）
- `ModLanguageProvider.java`

不要回退这些文件，也不要改动已固定的命令 ID：

- `SET_CAMOUFLAGE_LIGHT = 8`
- `SET_CAMOUFLAGE_OPACITY = 9`
- `CLEAR_CAMOUFLAGE = 10`

## 机械任务 A：GameTest

在现有 `OpenModularTurretsGameTests.java` 中补测试，不另建测试框架。

### `baseCamouflagePersistenceAndValidation`

至少断言：

1. 非所有者不能设置伪装。
2. 空气、带方块实体的方块、非完整方块被拒绝。
3. 所有者可设置石头伪装。
4. 方块状态的 `camouflaged` 变为 `true`。
5. `saveWithFullMetadata` 后使用 `BlockEntity.loadStatic` 重载，复制的方块状态仍为石头。
6. 清除后 `camouflaged` 为 `false`。
7. 删除新 NBT 字段模拟旧存档，默认值为无伪装、亮度 `0`、遮光 `15`。

### `baseCamouflageLightContract`

至少断言：

1. Tier 5 所有者可设置亮度 `15` 和遮光 `0`。
2. `light_level` 镜像为 `15`。
3. `-1`、`16` 被拒绝，状态不变。
4. Tier 3 所有者不能修改亮度或遮光。

测试名称应与 `base_camouflage.contract.json` 的 traceability 对齐。

## 机械任务 B：客户端界面收尾

- 保存亮度、遮光四个 `+/-` 按钮引用。
- 在 `containerTick` 或现有刷新函数中，根据 `0..15` 边界动态设置 `active`。
- 未应用伪装时禁用清除按钮。
- Tier 1–3 禁用亮度和遮光按钮，但仍显示只读默认值。
- 不改变页面结构或新增网络 payload。

## 机械任务 C：语言和资源生成

- 运行 DataGen 生成 `en_us.json` 和 `zh_cn.json`。
- 确认以下键都存在且占位符数量一致：
  - `gui.openmodularturrets.camouflage`
  - `gui.openmodularturrets.camouflage.light`
  - `gui.openmodularturrets.camouflage.opacity`
  - `gui.openmodularturrets.camouflage.clear`
  - `gui.openmodularturrets.camouflage.applied`
  - `gui.openmodularturrets.camouflage.none`
  - `gui.openmodularturrets.camouflage.hint`
  - `message.openmodularturrets.camouflage_rejected`
  - `message.openmodularturrets.camouflage_clear_rejected`
- 不需要新增纹理；伪装页复用现有 configure GUI 纹理。
- 检查新增 BlockState 属性是否仍由现有 turret base blockstate DataGen 覆盖全部组合。

## 机械任务 D：静态清理

- 仅移除确定未使用的 import、字段和局部变量。
- 不做大范围格式化，不重写现有乱码或历史翻译。
- 检查客户端类没有从 common/server 路径加载。
- 检查 `BaseCommand` ID 唯一且旧 ID 未变化。

## 验证顺序

每一步失败就修复并重跑当前步，不要跳过。

1. `python .agents/run.py .agents/gates/compile_and_repair.py --with-static`
2. `python .agents/run.py .agents/gates/feature_test.py --feature base_camouflage --level L4`
3. `python .agents/run.py .agents/gates/pipeline.py --profile fast`
4. `python .agents/run.py .agents/gates/pipeline.py --profile major`

若 `feature_test.py` 的 CLI 参数与这里不同，先运行 `--help`，不要绕过 gate。

## 人工客户端验收

1. 所有者手持石头右击 Tier 5 基座：外观变成石头，手中物品不消耗。
2. 重进世界：伪装仍存在。
3. 非所有者不能覆盖或清除。
4. 潜行空手右击：伪装被清除。
5. Tier 5 GUI 调整亮度/遮光后，光照实时更新且重进世界保持。
6. Tier 3 GUI 不允许调整亮度/遮光。
7. 伪装后碰撞、菜单、拆除和掉落仍按基座处理。
8. 客户端日志没有 OMT 相关 missing model、missing texture 或 renderer 异常。

## 完成定义

- 合同状态改为 `verified`。
- 两个 GameTest 均可重复通过。
- fast 与 major pipeline 均为 PASS。
- 在 `phase-7-base-camouflage-plan.md` 追加实际实现记录、偏差和门禁结果。
- 不得仅凭“可以启动游戏”宣称 Phase 7 完成。

## 当前执行结果

- 已完成：GameTest、界面边界刷新、双语 DataGen、damage-type tag DataProvider、静态清理。
- 已通过：L0 contract、L1/L2、L2.5、L4（33/33）、strict major pipeline、L3 dedicated-server smoke。
- 尚未自动化证明：客户端视觉矩阵；需要实际客户端窗口检查石头、玻璃、带色方块以及亮度 0/15。
