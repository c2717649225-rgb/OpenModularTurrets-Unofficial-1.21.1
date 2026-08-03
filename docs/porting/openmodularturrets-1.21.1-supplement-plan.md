# OpenModularTurrets 1.21.1 NeoForge 移植补充计划

## 1. 目的与完成口径

本文补充初版设计梳理，专门处理“当前底座已能运行，但尚未达到 1.12.2 机制与表现等价”的剩余工作。

完成度分为三层，不能混用：

1. **可运行**：注册、存档、联机和 Dedicated Server 正常，自动门禁全绿。
2. **机制等价**：旧版核心玩法的数值、弹道、权限、升级、附加组件、配方和交互均有明确映射与测试。
3. **表现等价**：旧版几何、材质、动画、声音、GUI 信息和操作入口均被正确消费，并经过客户端截图矩阵验收。

只有第 2、3 层全部完成，且最终人工验收通过，才可以称为“完整移植”。当前状态属于第 1 层，并只覆盖了部分第 2 层。

## 2. 审计基线

| 领域 | 旧版证据 | 当前状态 | 结论 |
|---|---:|---|---|
| 炮塔战斗 | 11 种炮塔；5 个注册射弹，另有 potato/disposable 射弹逻辑 | 全部由 `TurretHeadBlockEntity.fire` 直接结算 | P0：缺少真实弹道和命中生命周期 |
| 伤害 | 普通、爆炸、火焰、部分护甲穿透及来源归因 | 多数使用原版通用 DamageSource | P0：需数据驱动 DamageType |
| 炮塔定义 | Rocket tier 4、Laser tier 5；Relativistic 有 200 tick debuff；Teleporter 传送至炮塔附近 | tier/effect 与旧版不一致 | P0：先纠偏定义再扩展 |
| 配方 | 159 JSON，其中 61 个 vanilla 逻辑配方可无依赖迁移 | 0 recipe | P0：生存模式不可获取 |
| 声音 | 18 个实际注册 SoundEvent，旧资源另有未启用 windup | 0 SoundEvent、0 ogg | P0/P1：先审权属，再迁移实际使用事件 |
| 客户端 | 42 个客户端类；16 模型、14 TESR、3 射弹 renderer、完整 GUI | 3 个客户端类；无 BER/实体 renderer；Screen 仅色块 | P0/P1：表现层基本未移植 |
| 模型/纹理 | 炮塔动态模型、朝向、收放及 addon 外观 | 28 方块全部 `cube_all`；plasma 纹理映射错误 | P0：建立统一 BER，再逐型校准 |
| GUI | 5 个 tier base GUI、expander、configure、trusted players | 两个简化 Screen | P0/P1：缺少信息与入口 |
| 语言 | 6 locale、约 247–250 键/locale | en_us/zh_cn 各 75 键 | P1/P2：功能键和旧 locale 未补齐 |
| 升级/addon | accuracy、scatter、recycler、concealer、fake drops、loot deleter 等 | 仅少数简化效果 | P1：多数物品只是注册项 |
| 前置 OMLib | 权限、网络、工具与兼容抽象 | 必要能力已内聚到本模组 | 保持合并，不恢复独立前置 |

### 前置处理结论

继续采用**合并必要前置能力**的方案，不恢复独立 OMLib：

- OMLib 原本是 1.12 时代的共享框架，直接移植会扩大维护面，并重新引入已废弃的能力/网络/兼容抽象。
- 当前 OMT 只需要其中的权限、数据、少量工具语义；这些能力应以 OMT 自有、最小化的现代实现存在。
- 不合并旧 ComputerCraft/OpenComputers/IC2/Tesla/RF/Waila 等兼容层。未来如需兼容，按独立可选 integration 模块实现。
- 保留来源与许可证记录；纹理和声音按资源级 provenance 继续审计。

## 3. 分阶段实施

### Phase 0：基线与契约

- 冻结注册清单、差距矩阵和非目标。
- 每个 Major 阶段先建立 `docs/features/*.contract.json`。
- 所有旧数值必须有源码路径或旧配置字段证据，不凭空“平衡”。

验收：

- contract gate 通过。
- 补充计划与已有 design intake 不冲突。

### Phase 1：真实射弹与伤害闭环（当前实施阶段）

- 修正 Rocket/Laser tier、Relativistic 与 Teleporter 的明显语义偏差。
- 注册 7 个稳定 EntityType：
  - `disposable_item_projectile`
  - `potato_projectile`
  - `bullet_projectile`
  - `blazing_clay_projectile`
  - `grenade_projectile`
  - `rocket_projectile`
  - `plasma_projectile`
- 由逻辑服务端创建射弹；实体追踪负责位置和外观同步，不新增冗余 CustomPacketPayload。
- 迁移重力、最大寿命、直接命中、点燃、AoE、火箭追踪、榴弹引信和护甲穿透比例。
- 注册 4 个数据驱动 DamageType，并用 vanilla damage-type tags 表达 projectile/explosion/fire/bypasses armor。
- 射弹不得命中所属炮塔/基地、受信任玩家或非法目标；所有伤害保留来源基地用于统计。
- 客户端仅注册 renderer，不在 common code 引用客户端类。
- 增加实体状态、延迟命中、最大寿命和非法目标保护 GameTest。

验收：

- `projectile_combat.contract.json` 由 `draft` 推进到 `verifying`。
- 编译、静态、DataGen、资产对账、Dedicated Server 与相关 GameTest 全绿。
- 手工确认至少 machine gun、incendiary、grenade、rocket、plasma 的飞行和命中特征。

### Phase 2：生存获取链与声音

- 用 DataGen 迁移 61 个无外部依赖的 vanilla 配方；旧 metadata 输出映射到独立 registry id。
- 对旧 Mekanism/EnderIO 配方只保留映射表，不把不存在的依赖物品替换成任意材料。
- 注册旧版实际使用的 18 个 SoundEvent。
- 在资源许可逐文件确认后迁移 ogg 和 `sounds.json`；无法确认的声音使用明确记录的替代音，不静默复制。
- 将发射、命中、部署/收回、警报声音接入服务端事件。

验收：

- 每个可见物品/方块要么有配方，要么在“仅创造/未来兼容”清单中有理由。
- sounds.json 引用、ogg 存在性与 SoundEvent 注册三方对账为零差异。

### Phase 3：底座规则、目标选择、升级与附加组件

- 恢复 tier 槽位：tier 1 无 addon/upgrade，tier 2–4 为 2 addon + 1 upgrade，tier 5 为 2 + 2。
- 恢复多炮塔/同类上限、主人/信任/队伍/驯服生物保护。
- 恢复 MAX_HP、剩余 HP、距离、护甲、玩家优先级及 multi-target。
- 实现 accuracy、scatter shot、efficiency、fire rate、range 的真实作用及叠加上限。
- 实现 recycler、concealer、fake drops、loot deleter。
- 修正 solar 天气条件；redstone reactor 恢复按周期消耗红石并发电，而不是凭信号无成本发电。

验收：

- 每个 addon/upgrade 至少一个正向和一个边界 GameTest。
- 权限和资源消费只由服务端决定。

### Phase 4：射线类炮塔与特殊效果

- Laser/Rail Gun 使用服务端 ray trace，客户端只接收有界 BeamEffect。
- Relativistic 恢复持续 Slowness/Weakness。
- Teleporter 恢复到炮塔/基地附近的安全落点选择。
- 校准伤害、能耗、射速、范围、scatter 与统计。

验收：

- 穿墙、跨维度、卸载区块、无安全落点和受保护目标均有负向测试。

### Phase 5：炮塔 BER、实体渲染与动画

- 建立单一 `TurretHeadBlockEntityRenderer` 框架，按定义选择 11 种几何/纹理。
- 使用已有 yaw/pitch 同步做插值，并正确处理六面安装方向。
- 恢复 conceal/deploy、damage amp/solar/redstone addon 外观。
- 恢复 relativistic crystal 和 teleporter spinner 动画。
- 为 7 个 EntityType 注册共享或分组 renderer；修复 plasma 独立纹理。

验收：

- 客户端截图矩阵：11 炮塔 × 安装方向 × idle/target/fire/conceal。
- Dedicated Server 不加载任何 renderer/model 类。

### Phase 6：完整 GUI 与网络交互

- 使用旧 GUI 素材恢复 tier 1–5 base 和 inventory expander 布局。
- 展示能源、范围、工作状态、统计和权限反馈。
- 恢复 configure 与 trusted players 界面及入口。
- 复用现有有界 C2S/S2C payload；只有确有新状态流时才增加 CustomPacketPayload。
- 所有按钮先在客户端做可用性提示，最终仍由服务端权限、距离和 menu 上下文验证。

验收：

- owner/admin/use/view/none 五种权限态截图与交互矩阵。
- 非法、越界、重放和高频 payload 不改变服务端状态。

### Phase 7：语言、兼容、性能与发布验收

- 补齐 en_us/zh_cn 的 GUI、tooltip、死亡消息和错误反馈。
- 按旧资源质量恢复 de_de/en_gb/pt_br/ru_ru。
- 完成 100 炮塔/射弹压力测试和联机长时间测试。
- 复核资源来源、THIRD_PARTY_NOTICES、许可证与分发包内容。
- 最终跑 fast/full pipeline、Dedicated Server、GameTest L4 及手工验收清单。

## 4. 门禁与完成规则

每个阶段都必须：

1. 先更新契约和测试覆盖，再写 Major 实现。
2. 运行受影响的最小编译/测试，修复后再跑阶段完整门禁。
3. DataGen 产物必须可重复生成；不手改 generated 文件伪造通过。
4. 静态 warning、无用 import、注册/模型/语言/纹理引用差异均为零。
5. 阶段自动门禁通过只代表“可以进入人工验收”，不代表整个移植完成。

最终完成命令至少包括：

```text
python .agents/run.py .agents/gates/compile_and_repair.py --with-data --with-static --with-assets --with-server
python .agents/run.py .agents/gates/gametest_gate.py --require-tests --run
python .agents/run.py .agents/gates/pipeline.py --profile fast
```

## 5. 明确非目标

- 直接加载或原地升级 1.12.2 世界。
- 恢复已废弃的 OMLib 独立发布。
- 首版恢复 ComputerCraft、OpenComputers、IC2、Tesla、旧 RF、Waila、Jade、JEI。
- 在没有原依赖时伪造 Mekanism/EnderIO 等兼容配方。
- 在未确认资源权属时直接发布旧声音文件。
