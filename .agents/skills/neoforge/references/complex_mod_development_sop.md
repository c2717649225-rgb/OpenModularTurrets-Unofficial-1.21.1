---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-30
---
# 复杂/大型模组开发与移植通用 7 阶段 SOP (Complex Mod Stepped Pipeline)

> [!IMPORTANT]
> **物理规则**：为防止 AI 在处理大型/复杂模组（如包含大量物品/方块、BlockEntity、实体、GUI、能力或世界生成）时由于上下文过载导致**偷工减料、丢三落四或写空方法**，必须遵循本 7 阶段步进 SOP。

---

## 🎯 阶段 0：开局需求评估与动态剪枝 (Scope Intake & Dynamic Pruning)

在开始写码前，AI 必须根据目标模组的真实功能，明确标记每个 Phase 为 `Active`（激活）或 `Skipped`（剪枝跳过）：

| 阶段 | 涵盖功能 | 剪枝跳过条件 (Skipped Condition) |
| --- | --- | --- |
| **Phase 1** | 物品/方块/页签注册、语言包与纹理贴图 | 必然激活 (Active) |
| **Phase 2** | 自定义 RecipeType/Serializer、掉落表、Tags | 无自定义配方或特殊掉落表时可剪枝 |
| **Phase 3** | Data Components (1.21.1) 与 BlockEntity 持久化 | 无数据组件与无 BlockEntity 状态存盘时可剪枝 |
| **Phase 4** | FE/Fluid/ItemHandler Capabilities & Mixins | 无能量/流体/槽位接线且无 Mixin 底层切面时可剪枝 |
| **Phase 5** | 弹道实体、NPC/Boss 实体、EntityRenderer 与 AI Goal | **无自定义实体或无投掷物时必须显式剪枝跳过** |
| **Phase 6** | 矿石生成、Jigsaw 结构遗迹、Biomes 配置 | **无世界生成或无自然结构时必须显式剪枝跳过** |
| **Phase 7** | Menu GUI、C2S/S2C Payload 网络同步与 GameTest | 无复杂 UI 菜单与无联机同步时可简化 |

---

## 🚀 7 阶段步进 SOP 详细执行流程

### Phase 1: 基础骨架与资源先行 (Foundation & Assets First)
1. 先建立包结构，将贴图与资源文件迁移至 `src/main/resources/assets/<modid>/textures/`；
   - **老模组移植资源规则**：1.21.1 方块与物品纹理使用单数目录 `textures/block/` 与 `textures/item/`。移植第一步将旧版 `textures/blocks/`、`textures/items/` 分别迁移为 `block/`、`item/`，并同步更新模型和 DataGen 引用；
2. 完成 `DeferredRegister` 对 Item/Block/CreativeTab 的声明与注册；
3. 执行 `python .agents/run.py .agents/gates/pipeline.py --profile fast` 验证 L2.5 资源对账；

### Phase 2: 配方树与数据生成 (Custom Recipe Systems & DataGen)
1. 编写自定义 `RecipeType` 与 `RecipeSerializer`（若有）；
2. 继承 `RecipeProvider` 与 `LootTableProvider` 完成 DataGen Provider 写码（拒绝空实现）；
3. 执行 `./gradlew runData` 验证 JSON 物理产出；

### Phase 3: 数据组件与持久化 (Data Components & BlockEntity)
1. 注册 1.21.1 `DataComponentType`（禁止 1.20.x NBT `getOrCreateTag`）；
2. 编写 `BlockEntity` / `SavedData` 的 `loadAdditional` 和 `saveAdditional` 读写路径；
3. 静态门禁防范：确认静态块/字段中无 eager `.get()` 延迟解包隐患；

### Phase 4: 能力对接与底层切面 (Capabilities & Mixins)
1. 在 `RegisterCapabilitiesEvent` 统一注册 `Capabilities.ItemHandler.BLOCK`、`Capabilities.EnergyStorage.BLOCK` 与 `Capabilities.FluidHandler.BLOCK`；
2. 编写必要的 Mixin 切面（严格限制暴露范围，必须使用 `@Unique` 私有辅助）；

### Phase 5: 实体、投掷物与 AI 行为 (Entities & Projectiles)
1. 注册 `EntityType` 与对应的 `Mob` / `ThrowableItemProjectile` 实现；
2. 在 `EntityRenderersEvent.RegisterRenderers` 注册渲染器；
3. 在 `EntityAttributeCreationEvent` 绑定生命值与移动速度等默认属性；

### Phase 6: 世界生成与结构 (Worldgen & Structures)
1. 在 `data/<modid>/worldgen/` 放置 `configured_feature` 与 `placed_feature`；
2. 配置 Jigsaw template pool 与结构模板；

### Phase 7: 界面、网络与 GameTest 验证 (UI, Network & Validation)
1. 编写 `AbstractContainerMenu` 与 `AbstractContainerScreen` 客户端绑定；
2. 使用 `PayloadRegistrar` 注册 `CustomPacketPayload`（默认主线程执行）；
3. 编写针对新增核心机制的独立 `@GameTest`；
4. 阶段验收执行 `python .agents/run.py .agents/gates/pipeline.py --profile major`；只有准备发布时才运行 `--profile release`。

---

## 移植审计产物

老模组移植开始前，使用 [`../../../scaffolds/porting/porting_audit.template.md`](../../../scaffolds/porting/porting_audit.template.md) 记录旧版行为、源码依据、现代 API 映射、资源来源和已知偏差。Major 验收项与 GameTest 追踪仍写入 `docs/features/*.contract.json`，不要建立第二套合同。
