# 老模组移植语义审计与架构设计 (Mod Migration Audit & Architecture)

## 📌 移植基本元数据
- **模组名称**: `MyLegacyMod`
- **源版本**: `1.12.2 / 1.20.1`
- **目标版本**: `Minecraft 1.21.1 + NeoForge 21.1.x`
- **主类与包名映射**: `com.legacy.mod` ➔ `com.modern.mod`

---

## 1. 旧版语义审计与源码引用 (Legacy Behavior Audit)

| 组件 / 机制 | 旧版行为逻辑简述 | 旧版源码引用路径与行号 |
| :--- | :--- | :--- |
| **TileEntityX** | 存盘时手写 NBT `NBTTagCompound.setTag("energy")` | `com/legacy/mod/TileEntityX.java#L45-L80` |
| **CustomPacketY** | 继承 `IMessage`，网络线程直接操作 `World` | `com/legacy/mod/network/CustomPacketY.java#L20-L50` |

---

## 2. 1.21.1 现代 API 实现方案 (Modern Mapping Plan)

| 旧版 API / 方式 | 1.21.1 现代 NeoForge 替代方案 | 落地注意事项 |
| :--- | :--- | :--- |
| `getOrCreateTag()` / NBT | `DataComponentType` 与类型安全数据组件 | 严禁使用旧版 NBT API (P0) |
| `TileEntity.readFromNBT` | `loadAdditional(CompoundTag, HolderLookup.Provider)` | 静态块中禁止 eager `.get()` |
| 旧版复数贴图 `textures/blocks/` | 单数路径 `textures/block/` 与 `textures/item/` | 移植第一动作重命名目录，杜绝 Missing Texture |
| `IMessage` 网络包 | `CustomPacketPayload` + `PayloadRegistrar` | 默认主线程执行，网络线程须 `enqueueWork` |

---

## 3. 资源来源与静态资产迁移清单 (Source Assets & Textures)

- [ ] **贴图目录单数重命名**: 已将 `textures/blocks/` 重命名为 `textures/block/`，`textures/items/` 重命名为 `textures/item/`；
- [ ] **模型引用检查**: 检查手写 JSON 模型中所有 `modid:blocks/` 引用，同步修改为 `modid:block/`；
- [ ] **双语语言包**: 确保 `assets/<modid>/lang/en_us.json` 与 `zh_cn.json` 覆盖全部新注册项。

---

## 4. 验收测试计划 (Acceptance Tests & @GameTest)

自动化等价性校验映射：
- `com.modern.mod.PortingGameTests#testTileEntityXEquivalence` ➔ 校验方块实体数据存盘与恢复等价性

---

## 5. 已知合理偏差与架构调整 (Known Deviations)

| 偏差点 | 调整理由与替代方案 | 审核人 |
| :--- | :--- | :--- |
| 移除旧版独占的自定义 IItemHandler 实现 | 1.21.1 直接统一监听 `RegisterCapabilitiesEvent` 注册标准 Capability | Lead Dev |
