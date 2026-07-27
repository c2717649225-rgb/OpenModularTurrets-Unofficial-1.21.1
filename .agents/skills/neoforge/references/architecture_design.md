---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-27
---
# 模组通用架构设计蓝图 (Architecture & Design Blueprint)

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。

---

## 📐 1. 核心设计原则 (Minecraft SOLID Guidelines)

1. **单一职责原则 (SRP)**：
   * `Block` 适合承载不依赖任意实例数据的方块行为，例如交互、放置规则、形状、`BlockState` 转换、随机 Tick 与计划 Tick。
   * 只有当每个方块位置确实需要超出 `BlockState` 容量的持久数据、物品栏或独立生命周期时才引入 `BlockEntity`；不要为简单计时或少量枚举状态机械地创建 `BlockEntity`。
   * 静态方块/物品模型优先走 JSON 模型与 DataGen；`BlockEntityRenderer` 只用于无法由普通烘焙模型表达的动态或特殊渲染。渲染注册仍须与通用方块逻辑物理隔离。
2. **开闭原则 (OCP)**：
   * 注册、配方、世界生成与兼容扩展优先使用 NeoForge 注册器、事件、Data Map、Biome Modifier 或其他公开扩展点。
   * Mixin 是缺少公开扩展点时的最后手段，而不是一律禁止；使用前必须记录注入目标、版本依据、失败模式与回归测试，并把注入范围压到最小。
3. **接口隔离原则 (ISP)**：
   * 机器内部状态应有明确所有者。组合 `ItemStackHandler` / `FluidTank` 通常便于持久化与校验，但不是强制形式；不要为了“符合模式”增加没有价值的包装层。
   * 对外自动化边界通过 NeoForge Capabilities 按需暴露，并明确不同 `Direction` 的访问规则。内部实现细节不应泄漏给调用方。
4. **迪米特法则 (LoD)**：
   * 与未知或跨模组的邻近方块交互时，通过 `level.getCapability(...)` 查询能力接口，不要假定对方的具体 `BlockEntity` 类型。
   * 同一模组内部若确有稳定的领域接口，可以显式依赖该接口；仍应避免跨越多层对象链直接修改他人的内部状态。

---

## 🌐 2. 跨平台移植与解耦架构 (Portability & Decoupling)

*注：本模板默认是纯 NeoForge 模组。只有产品路线已经确认需要 Fabric 等第二平台时，才引入平台适配层；单平台项目不要预先承担跨平台抽象成本。确需移植时可采用如下**平台代理包装层（Platform Delegation Layer）**：*

1. **业务逻辑与平台接口分离**：
   * 将核心逻辑（如物品交互、状态计算、实体 AI 属性决策）剥离至纯 Java 逻辑层。
   * 平台独有逻辑（如 NeoForge 的事件注册、能力系统、特有网络发包）完全封装在平台独立的适配层。
2. **使用 IPlatformHelper 模式**：
   * 只为确实存在平台差异且被业务层需要的能力建立窄接口；不要把所有注册与 API 调用塞进一个巨型 `IPlatformHelper`。
   * 具体装配方式应服从所选多加载器架构；只有该架构明确需要时才使用 `ServiceLoader` 或依赖注入。

---

## ⚡ 3. 性能、异常与线程安全准则

1. **高频 Tick 严禁高开销操作**：
   * 主线程 `tick()` 不得执行阻塞 I/O、无界世界扫描或其他已知长耗时操作；对分配与容器遍历应先设预算并用分析器验证热点，避免凭直觉做无效优化。
   * 大型遍历、寻路或批处理应设置明确的每 Tick 工作预算；在不破坏玩法语义时可用 Tick Cooldown、分批处理或缓存降低峰值。
2. **并发线程安全**：
   * `Level`、`Entity`、`BlockEntity`、玩家背包及多数游戏状态按逻辑主线程所有权处理；不要把并发集合当成允许异步访问游戏对象的通行证。
   * 异步任务只接收不可变快照或独立纯数据，完成后通过受支持的调度入口把结果提交回主线程，并在回写前重新校验对象仍然有效。
   * 真正共享的纯 Java 数据结构应按访问模式选择同步策略：读多写极少时才考虑 `CopyOnWriteArrayList`，计数竞争才考虑原子类，键值并发访问才考虑 `ConcurrentHashMap`；先定义所有权与一致性要求，再选容器。
3. **崩溃防御**：
   * 只在能够恢复的边界捕获预期异常，例如可选集成、外部输入解码或异步任务完成；日志必须包含操作、对象标识与原始异常。
   * 不要用宽泛 `catch (Exception)` 包住 Tick、注册或存档核心路径，也不要静默吞错或“安全移除”未知故障对象；这会隐藏不变量破坏并可能继续污染存档。
   * 对程序错误和已破坏的不变量应快速失败并保留完整因果链。只有定义了可验证恢复策略时才降级；`CompletableFuture` 必须显式处理异常。

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
*   **线程隔离**：`PayloadRegistrar` 默认把 Handler 调度到接收端主线程，因此默认注册不需要再套一层 `context.enqueueWork(...)`。只有显式 `.executesOn(HandlerThread.NETWORK)` 的 Handler 才在网络线程运行；其异步阶段不得触碰世界/玩家状态，回写必须 `enqueueWork` 并处理返回 Future 的异常。

### 3. 事件总线归属判定与静态订阅规范 (Event Routing & Bus Authority)
*   **MOD/GAME 路由按精确 NeoForge 版本判定**：
    *   先读取宿主 `gradle.properties` 的 `neo_version`。21.1.0～21.1.180 中，`@EventBusSubscriber` 默认 `Bus.GAME`，监听 `IModBusEvent` 须显式 `bus = Bus.MOD`；21.1.181+ 才会按事件类型自动分流并应省略 `bus`。
    *   无论哪条版本分支，注解订阅方法都必须是 `static`；不要把 21.1.234 的写法反推为整个 21.1.x 都相同。
    *   **Mod 总线事件**：FMLCommonSetupEvent、RegisterEvent、RegisterCapabilitiesEvent、EntityAttributeCreationEvent 等静态生命周期事件。
    *   **Game 总线事件**：PlayerTickEvent、LevelTickEvent、BlockEvent.BreakEvent 等游戏运行期事件。
    *   *注意*：如果采用手动监听模式，仍需在主类构造函数中显式对 `modEventBus.addListener(...)` 或 `NeoForge.EVENT_BUS.addListener(...)` 写入，此时须严加区分。
