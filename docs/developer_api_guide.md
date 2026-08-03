# OpenModularTurrets 1.21.1 开发者 API 与扩展接入指南

欢迎使用 **OpenModularTurrets (OMT) 1.21.1 NeoForge** 官方开发者指南。本指南旨在指导第三方模组开发者如何接入 OMT 体系、扩展自定义炮塔、添加新弹药与 Addon 芯片、以及监听防御阵列事件。

---

## 目录
1. [架构概述](#架构概述)
2. [注册自定义炮塔 (Custom Turrets)](#注册自定义炮塔)
3. [扩展自定义 Addon 芯片 (Custom Addons)](#扩展自定义-addon-芯片)
4. [添加自定义升级与弹药 (Upgrades & Ammo)](#添加自定义升级与弹药)
5. [事件系统与防御钩子 (Events & Hooks)](#事件系统与防御钩子)

---

## 1. 架构概述

OMT 1.21.1 采用了高度规则化的抽象设计：
- **`TurretDefinition`**：定义炮塔的基础射程、伤害、射速、能耗、基础基座等级与允许的 Addon 掩码。
- **`TurretAddonRules`**：定义 Addon 芯片在安装到基座后的全局逻辑增益（太阳能发电、伤害倍率、物品回收率等）。
- **`TurretUpgradeRules`**：定义插在基座升级槽中的算法插值（散弹数量、射程累加、精度提升等）。

---

## 2. 注册自定义炮塔

要添加一款新的炮塔（如 `CustomBeamTurret`），请按以下步骤操作：

### 步骤 2.1：定义炮塔规格
在扩展模组中，创建对应 `TurretDefinition` 规则配置：
```java
public class MyModTurretDefinitions {
    // 注册自定义炮塔基础属性：需 Level 3 基座，射程 20 格，伤害 10.0，能耗 50 FE/t，基础射速 10 ticks/发
    public static final int REQUIRED_BASE_TIER = 3;
    public static final int RANGE = 20;
}
```

### 步骤 2.2：注册方块与 BlockEntity
注册对应的 `TurretHeadBlock` 与 `TurretHeadBlockEntity`：
```java
public static final DeferredBlock<TurretHeadBlock> MY_TURRET = BLOCKS.registerBlock(
    "my_custom_turret",
    properties -> new TurretHeadBlock(TurretDefinition.MACHINE_GUN, properties),
    BlockBehaviour.Properties.of().noOcclusion()
);
```

---

## 3. 扩展自定义 Addon 芯片

OMT 支持在基座的 Addon 槽位中插入各种功能的拓展芯片。

### Addon 掩码位图（Addon Render Bitmask）
- **Bit 0 (`& 1`)**：伤害放大器 (`Damage Amp`)
- **Bit 1 (`& 2`)**：太阳能面板 (`Solar Panel`)
- **Bit 2 (`& 4`)**：红石反应堆 (`Redstone Reactor`)

扩展自定义 Addon 逻辑示例：
```java
@EventBusSubscriber(modid = "mymod")
public class MyAddonHandler {
    @SubscribeEvent
    public static void onBaseTick(TurretBaseTickEvent event) {
        if (event.getBase().hasAddon("mymod:shield_generator")) {
            // 在此处注入防护盾磁场算力
        }
    }
}
```

---

## 4. 添加自定义升级与弹药

### 添加自定义弹药 (Custom Ammunition)
只需继承 `Item` 并包含 `ModTags.Items.AMMO` 标签：
```json
// data/c/tags/item/ammo.json
{
  "values": [
    "mymod:custom_plasma_cell"
  ]
}
```

---

## 5. 事件系统与防御钩子

OMT 提供了丰富的服务端开火与目标锁定事件，便于第三方模组实现保护区域与防守统计：

```java
@EventBusSubscriber(modid = "mymod")
public class TurretEventHooks {
    @SubscribeEvent
    public static void onTurretFire(TurretFireEvent.Pre event) {
        // 在开火前检查领地插件保护（如 Towny / WorldGuard）
        if (ClaimProtectionAPI.isProtected(event.getTarget())) {
            event.setCanceled(true); // 取消开火
        }
    }
}
```

---

## 总结
通过基于 NeoForge 21.1.x 的规则模式与 DataGen 支持，第三方开发者可以极为轻松地为 OpenModularTurrets 拓展丰富的防御生态。
