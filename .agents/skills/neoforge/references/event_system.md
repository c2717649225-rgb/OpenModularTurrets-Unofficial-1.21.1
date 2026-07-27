---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-27
---
# NeoForge 1.21.1 Event System Guidelines

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。


NeoForge uses an event-driven architecture. There are two primary event buses you must use correctly.

## 1. The Two Event Buses

| Bus Name | How to Access | Event Types Handled |
| :--- | :--- | :--- |
| **Mod Event Bus** (Mod Bus) | Passed into the Mod constructor as `IEventBus modEventBus`. | Mod startup lifecycle events (Setup, Client/Server setup, Registry events, registering networking payloads, registering capabilities). |
| **NeoForge Event Bus** (Game Bus) | `NeoForge.EVENT_BUS` | Game loop events (Tick events, player interaction, block breaks, entity spawning, server start/stop events, capability queries). |

---

## 2. Registering Events

### Method A: Manual Listener Registration (Recommended for Mod Bus)

Register specific listener methods directly to the bus in your main mod constructor:

```java
public class MyMod {
    public MyMod(IEventBus modEventBus, ModContainer modContainer) {
        // Registering listener to the Mod Event Bus
        modEventBus.addListener(this::commonSetup);
        
        // Registering listener to the NeoForge Game Event Bus
        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Mod lifecycle setup code
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        // Runs every tick for players
    }
}
```

### Method B: `@EventBusSubscriber` Annotation (Automatic Discovery)

Use this annotation on classes containing static event handler methods annotated with `@SubscribeEvent`.

```java
// Registering to the NeoForge Game Event Bus automatically
@EventBusSubscriber(modid = MyMod.MODID)
public class GameEventHandler {
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        System.out.println("Block broken by: " + event.getPlayer().getName().getString());
    }
}
```

`@EventBusSubscriber` 在 NeoForge 21.1.x 内存在明确的小版本分界，不能只按 Minecraft 1.21.1 判断。写注解前先读取宿主 `gradle.properties` 的精确 `neo_version`：

| NeoForge 版本 | 注解订阅语义 |
| :--- | :--- |
| **21.1.0～21.1.180** | 默认 `Bus.GAME`。监听 `IModBusEvent` 时必须显式 `bus = Bus.MOD`；Game 事件可省略或显式写 `Bus.GAME`。 |
| **21.1.181+** | 自动同时注册到正确总线，并由事件类型分流；应省略 `bus` 参数。 |

两条分支中，`@EventBusSubscriber` 下的 `@SubscribeEvent` 方法都必须是 `static`。下面是 Mod Bus 生命周期事件的两种对应写法：

```java
// NeoForge 21.1.0～21.1.180
import net.neoforged.fml.common.EventBusSubscriber.Bus;

@EventBusSubscriber(modid = MyMod.MODID, bus = Bus.MOD)
public class LegacyModLifecycleHandler {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Client-side initialization code
    }
}
```

```java
// NeoForge 21.1.181+：按 IModBusEvent 自动分流
@EventBusSubscriber(modid = MyMod.MODID)
public class ModLifecycleHandler {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Client-side initialization code
    }
}
```

物理客户端隔离仍由 `value = Dist.CLIENT` 表达；旧分支若监听 Mod Bus 事件，还要同时指定 `bus = Bus.MOD`：

```java
// 21.1.0～21.1.180 的客户端 Mod Bus 类：再加 bus = Bus.MOD
// 21.1.181+：保持下方写法，省略 bus
@EventBusSubscriber(modid = MyMod.MODID, value = Dist.CLIENT)
public class ClientOnlyHandler {
    // Methods here will only load on the physical client
}
```

---

## 3. ⚠️ 1.21.1 事件系统重大变更与编译错误自愈

### 3.1 核心事件更名与彻底删除 (Event Replacement)
- **`LivingTickEvent` 彻底删除**：
  - ❌ 错误：`public static void onLivingTick(LivingTickEvent event) { ... }`
  - ✅ 修正：1.21.1 中必须改为监听 `EntityTickEvent.Post` (或 `Pre`)，然后在内部进行类型判断：
    ```java
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity living) {
            // 业务逻辑
        }
    }
    ```
- **`TickEvent` 彻底删除**：
  - ❌ 错误：`net.neoforged.neoforge.event.TickEvent.PlayerTickEvent`。
  - ✅ 修正：`TickEvent` 被整包移除，其下子类被重构为独立的事件类。如玩家 Tick 事件改用 `net.neoforged.neoforge.event.tick.PlayerTickEvent`。
- **`LivingHurtEvent` 彻底删除**：
  - ❌ 错误：试图使用 `LivingHurtEvent` 拦截伤害前属性。
  - ✅ 修正：1.21.1 废除了生物受伤事件，拦截受到伤害前属性一律使用 **`LivingIncomingDamageEvent`**。

### 3.2 玩家与实体获取 API 不可一刀切
- ❌ 错误：假设所有事件都统一使用 `getPlayer()`，或反过来假设所有事件都统一使用 `getEntity()`。
- ✅ 修正：读取具体事件类真源码确认 getter。以 NeoForge 21.1.234 为例，`BlockEvent.BreakEvent#getPlayer()` 仍然存在并返回触发玩家；许多继承 `EntityEvent` / `PlayerEvent` 的事件则通过 `getEntity()` 暴露实体。不要做全局机械替换。

### 3.3 伤害源属性检测 (DamageSource Tags)
- ❌ 错误：使用 `source.isBypassInvul()`、`source.isMagic()`、`source.isFire()`、`source.isExplosion()` 等 boolean 判断伤害类型属性。
- ✅ 修正：1.21.1 中上述 boolean 快捷方法全部被删除。检测伤害源属性**必须**使用全新的标签系统 (Damage Type Tags)：
  ```java
  import net.minecraft.tags.DamageTypeTags;
  
  if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) { ... }
  if (source.is(DamageTypeTags.IS_FIRE)) { ... }
  ```

### 3.4 玩家克隆与数据迁移 (PlayerEvent.Clone)
- ❌ 错误：在 `PlayerEvent.Clone` 中，使用 `original.reviveCaps()` 和 `original.invalidateCaps()` 来迁移 Capability 数据。
- ✅ 修正：1.21.1 彻底删除了这两个方法。直接通过 Attachment 机制进行复制：
  ```java
  @SubscribeEvent
  public static void onPlayerClone(PlayerEvent.Clone event) {
      Player original = event.getOriginal();
      Player newPlayer = event.getEntity();
      
      // 数据附件直接迁移
      if (original.hasData(ModAttachments.MANA)) {
          newPlayer.setData(ModAttachments.MANA, original.getData(ModAttachments.MANA));
      }
  }
  ```
