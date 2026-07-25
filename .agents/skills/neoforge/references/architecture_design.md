---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-25
---
# 模组通用架构设计蓝图 (Architecture & Design Blueprint)

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。

---

## 📐 1. 核心设计原则 (Minecraft SOLID Guidelines)

1. **单一职责原则 (SRP)**：
   * `Block` 类仅负责方块物理定义，复杂状态与 Tick 逻辑必须交由 `BlockEntity` 处理。
   * 模型的注册与渲染逻辑必须与方块类完全剥离，交由 `BlockStateProvider` 与 `BlockEntityRenderer` 处理。
2. **开闭原则 (OCP)**：
   * 注册配方、世界生成、注册项扩展，必须监听平台提供的特定事件（如监听 `CreativeModeTabEvent`）或使用数据包（Biome Modifier），严禁直接使用 Mixin 强行修改底层核心类。
3. **接口隔离原则 (ISP)**：
   * 机器实体不得直接实现物品栏或流体处理接口。必须内部持有 `ItemStackHandler` / `FluidTank` 等封装实例，并在注册能力（Capabilities）时，按需向特定的面（Direction）暴露。
4. **迪米特法则 (LoD)**：
   * 方块实体需要与邻近方块交互时（如从旁边箱子抽取物品），必须通过 `level.getCapability(...)` 查询能力接口，严禁直接强转邻近方块实体类型并调用其私有方法。

---

## 🌐 2. 跨平台移植与解耦架构 (Portability & Decoupling)

*注：本模板默认是纯 NeoForge 模组，底层推荐采用 Attachment 数据存盘。如遇到跨平台移植至 Fabric 的特殊要求，建议采用如下**平台代理包装层（Platform Delegation Layer）**解耦架构：*

1. **业务逻辑与平台接口分离**：
   * 将核心逻辑（如物品交互、状态计算、实体 AI 属性决策）剥离至纯 Java 逻辑层。
   * 平台独有逻辑（如 NeoForge 的事件注册、能力系统、特有网络发包）完全封装在平台独立的适配层。
2. **使用 IPlatformHelper 模式**：
   * 建立通用的业务依赖接口（例如 `IPlatformHelper`），定义注册、发包、能力查询等方法。
   * 在运行时，通过服务加载器（ServiceLoader）或依赖注入动态注入当前平台的具体实现。

---

## ⚡ 3. 性能、异常与线程安全准则

1. **高频 Tick 严禁高开销操作**：
   * 所有的 `tick()` 方法中，绝对禁止进行任何 I/O 读写、高开销容器遍历（如每次 tick 遍历世界实体）以及高频垃圾对象分配。
   * 对于大型遍历或寻路，必须引入时间步长（Tick Cooldown）进行节流（Throttling）。
2. **并发线程安全**：
   * 多线程或异步场景下，优先使用 `ConcurrentHashMap`、`AtomicInteger`、`CopyOnWriteArrayList`。
3. **崩溃防御**：
   * 所有内部异常必须被捕获并优雅降级（如打印日志、安全移除实体），绝对禁止导致整个游戏物理客户端或服务端发生崩溃。

---

## 🏛️ 4. 模组分层架构与边界规范 (Decoupling & Bus Authority)

### 1. 物理端侧隔离与 Client/Common 边界 (Client Isolation)
*   **物理隔离原则**：Minecraft 的专用服务器 (Dedicated Server) 物理缺失 `net.minecraft.client` 命名空间下的所有类。
*   **注册隔离 (Registration Event)**：所有渲染器注册 (BER)、颜色处理器注册、粒子效果配置、客户端 Screen GUI 必须完全隔离在带 `@EventBusSubscriber(value = Dist.CLIENT)` 标记的客户端独立类中。
*   **通用包禁导客户端**：严禁在 common / server 业务包的类（如 Block, Item, BlockEntity 核心类）中直接 import 或引用 `net.minecraft.client`。
*   **单点跳转**：对客户端的调用必须通过 **物理隔离**（`client` 包 + `@EventBusSubscriber(value = Dist.CLIENT)` / `@Mod(..., dist = Dist.CLIENT)`）或平台 Proxy；**禁止**把 `OnlyIn` 当作主推荐路径（历史 API，易误导；见 anti_patterns / static_gate `onlyin_usage`）。

### 2. 数据权威性与服务端同步 (Server Authority)
*   **服务端为唯一数据权威 (Server is King)**：所有的生命值、魔法值、能量、物品栏修改，必须完全在服务端进行逻辑结算。
*   **数据包同步 (Packet Synced)**：当服务端数据发生变化时，通过自定义 Network Payload 向客户端分发同步 Packet。客户端收到 Packet 后仅用于界面显示与客户端视觉特效渲染，严禁在客户端直接修改核心业务数据状态。
*   **线程隔离**：网络 Payload Handler 默认运行在网络线程，任何涉及修改世界、玩家状态的操作，**必须**包裹在 `context.enqueueWork(...)` 中提交给主线程运行。

### 3. 事件总线归属判定与静态订阅规范 (Event Routing & Bus Authority)
*   **MOD/GAME 自动路由判定**：
    *   在 NeoForge 中，静态注解 `@EventBusSubscriber` **一律省略 `bus` 参数**。系统会根据事件参数类是否实现了 `IModBusEvent` 接口，在底层自动将该静态监听器路由分流至 **Mod 事件总线** 或 **Game 事件总线**。
    *   **Mod 总线事件**：FMLCommonSetupEvent、RegisterEvent、RegisterCapabilitiesEvent、EntityAttributeCreationEvent 等静态生命周期事件。
    *   **Game 总线事件**：PlayerTickEvent、LevelTickEvent、BlockEvent.BreakEvent 等游戏运行期事件。
    *   *注意*：如果采用手动监听模式，仍需在主类构造函数中显式对 `modEventBus.addListener(...)` 或 `NeoForge.EVENT_BUS.addListener(...)` 写入，此时须严加区分。
