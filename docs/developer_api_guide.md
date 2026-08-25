# OpenModularTurrets 1.21.1 开发者 API 与扩展接入指南

> **真实性约定**：本指南只记载可在源码中逐条验证的事实。所有引用的类、方法、
> 标签路径均在文末「文档-代码对照表」中给出出处。当前版本**不提供自定义事件总线**；
> 所有互操作基于 NeoForge 标准事件与 Minecraft 数据包机制。

---

## 1. 架构总览（扩展相关的核心类）

| 类 | 职责 | 第三方可触达性 |
| :--- | :--- | :--- |
| `data.TurretDefinition` | 炮塔规格封闭枚举（Builder 构造） | 只读；新增类型需修改本仓库源码 |
| `turret.behavior.VolleyStrategy` | 射击行为策略接口（投射物/光束/状态/传送） | 源码级扩展点 |
| `data.TurretAddonRules` / `TurretUpgradeRules` | 插件与升级的数值规则（纯函数） | 数值经服务端配置可调 |
| `registration.ModTags` | 弹药/插件/升级的物品标签定义 | **数据包可合并扩展（见第 2 节）** |
| `damage.TurretDamageSource` | 全部炮塔伤害的来源子类 | **标准事件可识别（见第 3 节）** |
| `blockentity.TurretBaseBlockEntity` | 能量与自动化能力暴露方 | **Capability 对接（见第 4 节）** |

---

## 2. 自定义弹药（数据包标签，零代码）

弹药接受逻辑由两条标签链决定：

1. **能否进入弹药槽**：物品必须属于 `openmodularturrets:ammo/all`；
2. **会被哪种炮塔消耗**：每种炮塔读取自己的子标签
   （`ammo/machine_gun`、`ammo/grenade`、`ammo/rocket`、`ammo/rail_gun`、
   `ammo/incendiary`、`ammo/potato`、`ammo/disposable`）。

由于 `ammo/all` 是通过 `#openmodularturrets:ammo/...` 引用聚合的，第三方数据包
只需向**一个子标签追加条目**即可同时满足两个条件。

示例——为机枪炮塔添加自定义弹药，在你的数据包（或模组 jar 的
`data/` 目录）中创建：

```json
// data/openmodularturrets/tags/item/ammo/machine_gun.json
{
  "values": [
    "mymod:heavy_bullet"
  ]
}
```

补充事实：
- 基座弹药槽同时接受红石燃料：`minecraft:redstone` 与 `minecraft:redstone_block`
  （供红石反应堆插件消耗）；
- 弹药是否必须消耗由服务端配置 `turrets_need_ammo` 控制；
- 插件槽 / 升级槽分别校验 `openmodularturrets:addons` 与
  `openmodularturrets:upgrades` 标签，且受基座等级槽位数限制。

---

## 3. 保护类模组互操作（NeoForge 标准事件）

炮塔造成的所有伤害都经由 `omtteam.openmodularturrets.damage.TurretDamageSource`
（`DamageSource` 子类）。保护类模组用标准的
`LivingIncomingDamageEvent` 即可识别并拦截：

```java
@EventBusSubscriber(modid = "mymod")
public class ProtectAgainstTurrets {

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getSource() instanceof TurretDamageSource turretSource) {
            TurretAttackContext ctx = turretSource.context();
            // ctx.sourceBasePos(): 开火的基座坐标，可用于领地判定
            if (ClaimResolver.isProtected(event.getEntity(), ctx.sourceBasePos())) {
                event.setCanceled(true);
            }
        }
    }
}
```

事实要点：
- 取消该事件即可靠地阻止伤害；炮塔侧击杀计数基于"目标实际死亡"判定，
  被拦截的目标不会被计入击杀统计；
- 安装了掉落类插件时，`source.getEntity()`（责任方）返回系统假人
  （GameProfile 名称为 `openmodularturrets:fakeplayer`），而
  `source.getDirectEntity()` 为 `null`；未安装掉落类插件时两者均为
  `null`——依赖"玩家造成伤害"判定的保护模组请以
  `instanceof TurretDamageSource` 为准。

---

## 4. 自动化对接（Capability）

基座通过 NeoForge Capability 向管道/机械类模组暴露以下接口：

| 能力 | 暴露内容 | 边界 |
| :--- | :--- | :--- |
| `Capabilities.ItemHandler.BLOCK` | 仅 9 个弹药槽（0–8） | 插件槽与升级槽**不**对外可见，管道无法抽取 |
| `Capabilities.EnergyStorage.BLOCK` | 基座储能 | 单刻最大接收功率 = 对应基座等级的 `max_receive` 配置 |
| 同上（InventoryExpander 方块） | 扩展器完整 9 格弹药仓 | 与基座共享弹药消耗逻辑 |

---

## 5. Jade 集成

安装 Jade 后自动显示：基座的开关状态、能量存量/上限、归属者名称与击杀统计，
以及炮塔头的规格参数。无需额外配置，第三方无需适配。

---

## 6. 源码级扩展（新增炮塔类型 / 可见插件）

`TurretDefinition` 是封闭枚举，**新增炮塔类型需要修改本仓库源码并重新编译**
（这是 1:1 移植定位下的有意取舍）。需要的触点清单如下，其中标 ⛔ 的位置是
不带 `default` 分支的穷举 switch——漏改会直接编译失败，属于有意的防漏设计：

| 触点 | 说明 |
| :--- | :--- |
| ⛔ `data.TurretDefinition` | 用命名 Builder 新增常量（每个字段自带语义标签） |
| ⛔ `client.render.TurretHeadModel.createBodyLayer` | 新增模型几何分支 |
| ⛔ `registration.ModSounds.launchFor` / `impactFor` | 发射与命中音效映射 |
| `client.render.TurretHeadBlockEntityRenderer` | 纹理表 + 插件允许位 |
| `data.SpecialTurretRules` | 仅当引入新特殊行为时 |
| `data.TargetPriorityProfile.defaults` | 仅当需要专属默认索敌权重时 |
| `item.OmtTooltips.accuracyKey/ammoTypeKey/turretFlavourKey` | 工具提示文案（穷举，漏改编译失败） |
| `datagen.ModLanguageProvider` | en_us 与 zh_cn 条目 |
| `datagen.ModRecipeProvider` | 合成配方 |
| `registration.ModBlocks` + 模型层 | 方块注册与烘焙层 |
| GameTest | 在对应域持有者类补一条契约测试 |

**可见插件**（需要在模型上渲染的新 Addon）额外遵循
`data.TurretVisualRules.ADDON_MASK_*` 的位分配流程（见其 Javadoc）：
领取下一个空闲位 → 提升 `ADDON_MASK_ALL` → 渲染器增加覆盖模型 →
确保持久化掩码宽度充足。

任何数值改动都会被 `ConfigDefinitionGameTests#turretDefinitionGoldenDefaultsContract`
的金标断言表拦截——更新数值时必须显式同步该测试的期望值。

---

## 附：文档-代码对照表

| 本文档断言 | 源码锚点 |
| :--- | :--- |
| 标签定义与命名空间 | `registration.ModTags`；生成物 `src/generated/resources/data/openmodularturrets/tags/item/` |
| `ammo/all` 经 `#` 引用聚合子标签 | `src/generated/resources/data/openmodularturrets/tags/item/ammo/all.json` |
| 槽位接受规则（弹药标签 / 红石燃料） | `TurretBaseBlockEntity` 匿名 `ItemStackHandler.isItemValid` |
| 伤害来源子类与上下文 | `damage.TurretDamageSource`、`damage.TurretAttackContext` |
| 假人 Profile 名称 | `TurretDamageSource.FAKE_PLAYER_PROFILE` |
| Capability 暴露面 | `registration.ModCapabilities.register` |
| 掩码位分配 | `data.TurretVisualRules.ADDON_MASK_*` 及其 Javadoc |
| 金标数值测试 | `gametest.ConfigDefinitionGameTests#turretDefinitionGoldenDefaultsContract` |

*本文档依据提交 7ee7304 时点的源码审计重写；若上表任一锚点失效，请视为文档过期并先行修正。*
