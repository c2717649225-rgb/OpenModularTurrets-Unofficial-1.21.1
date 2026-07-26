---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-26
---
# NeoForge 1.21.1 常见开发地雷与反例对照表 (Anti-Patterns)

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。


这是一个专门面向 AI 和模组开发者的避坑反例指南。第 1～6 条为**崩溃级地雷**（编译报错、启动闪退或存档损坏）；第 7～11 条为**性能与兼容级地雷**（不崩溃，但让模组「能玩不好玩」——TPS 下降、GC 卡顿尖峰、与其他模组物理冲突）。请对比 Bad 写法与 Good 写法：

---

## 1. 物品 ItemStack 数据读写 (ItemStack NBT)

*   **痛点**：1.21.1 原版物理移除了旧的 NBT API。
*   **影响**：编译失败或运行期 NPE。

| ❌ 错误写法 (Bad - 废弃的 NBT) | 复合/正确写法 (Good - 1.21.1 Data Components) |
| :--- | :--- |
| `stack.getOrCreateTag().putInt("Mana", 10);`<br>`int m = stack.getTag().getInt("Mana");` | `stack.set(ModData.MANA.get(), 10);`<br>`int m = stack.getOrDefault(ModData.MANA.get(), 0);` |

---

## 2. Codec 字段声明顺序与 Record 构造器匹配

*   **痛点**：在编写 `RecordCodecBuilder` 时，Codec 内部的字段顺序与 Record 构造器的字段顺序不匹配。
*   **影响**：游戏启动正常，但读档反序列化时发生 ClassCastException，存档彻底损坏不可逆。

| ❌ 错误写法 (Bad - 顺序错位) | 复合/正确写法 (Good - 完美一致) |
| :--- | :--- |
| ```java<br>public record MyData(int mana, String name) {}<br>// Codec 声明中先 name 后 mana<br>RecordCodecBuilder.create(inst -> inst.group(<br>  Codec.STRING.fieldOf("name").forGetter(MyData::name),<br>  Codec.INT.fieldOf("mana").forGetter(MyData::mana)<br>).apply(inst, MyData::new));<br>``` | ```java<br>public record MyData(int mana, String name) {}<br>// Codec 声明顺序与 Record 构造器完全一致 (mana, name)<br>RecordCodecBuilder.create(inst -> inst.group(<br>  Codec.INT.fieldOf("mana").forGetter(MyData::mana),<br>  Codec.STRING.fieldOf("name").forGetter(MyData::name)<br>).apply(inst, MyData::new));<br>``` |

---

## 3. 静态加载期过早对注册项解包 (.get() NPE)

*   **痛点**：在类加载与静态初始化时直接访问 DeferredHolder / DeferredBlock 的实例。
*   **影响**：在 Registry 注册事件调度前触发 Registry not present 空指针异常，导致游戏启动闪退。

| ❌ 错误写法 (Bad - 静态解包) | 复合/正确写法 (Good - 延迟访问) |
| :--- | :--- |
| ```java<br>public class ModBlocks {<br>  public static final DeferredBlock<Block> RUBY_BLOCK = ...;<br>  // ❌ 直接在类加载时调用了 .get()<br>  public static final Block MY_BLOCK = RUBY_BLOCK.get();<br>}<br>``` | ```java<br>public class ModBlocks {<br>  public static final DeferredBlock<Block> RUBY_BLOCK = ...;<br>  // 🟢 延迟到运行期方法中通过 get() 访问<br>  public static Block getRuby() {<br>    return RUBY_BLOCK.get();<br>  }<br>}<br>``` |

---

## 4. 物理客户端隔离越界 (Client Code Leak)

*   **痛点**：通用 Tick 或事件类直接 import 了 `net.minecraft.client.*` 下的类。
*   **影响**：单机测试正常，但联机专用服务器（Dedicated Server）加载到该类时会由于缺失客户端库直接崩溃。

| ❌ 错误写法 (Bad - 混入客户端类) | 复合/正确写法 (Good - 物理隔离) |
| :--- | :--- |
| ```java<br>// ❌ 混在通用 Tick 事件里引用 net.minecraft.client<br>public class CommonEventHandler {<br>  @SubscribeEvent<br>  public void onPlayerTick(PlayerTickEvent.Post event) {<br>    // 编译没问题，专用服务器启动时会引发 NoClassDefFoundError 崩溃<br>    var playerModel = net.minecraft.client.model.PlayerModel.class;<br>  }<br>}<br>``` | ```java<br>// 🟢 完全将渲染和模型逻辑移至 .client 包下的类，用 Dist 标记隔离<br>@EventBusSubscriber(modid = MODID, value = Dist.CLIENT)<br>public class ClientRenderHandler {<br>  // 仅在客户端才会加载此类<br>  public static void renderModel() {<br>    var playerModel = net.minecraft.client.model.PlayerModel.class;<br>  }<br>}<br>``` |

---

## 5. 网络数据包 Handler 线程安全 (Thread Safety)

*   **痛点**：网络 Payload 的 Handler 默认在网络异步线程运行，直接修改游戏世界状态。
*   **影响**：引发异步线程冲突，导致游戏随机卡死、实体同步发生致命空指针。

| ❌ 错误写法 (Bad - 异步修改状态) | 复合/正确写法 (Good - 提交主线程) |
| :--- | :--- |
| ```java<br>public static void handle(SyncDataPayload payload, IPayloadContext context) {<br>  // ❌ 错误：在网络异步线程上直接操作世界和实体数据<br>  context.player().level().setBlock(pos, state, 3);<br>}<br>``` | ```java<br>public static void handle(SyncDataPayload payload, IPayloadContext context) {<br>  // 🟢 正确：使用 context.enqueueWork 将任务提交给游戏主线程<br>  context.enqueueWork(() -> {<br>    context.player().level().setBlock(pos, state, 3);<br>  });<br>}<br>``` |

---

## 6. `@EventBusSubscriber` 监听方法非 static (EventBus Static Subscription)

*   **痛点**：使用 `@EventBusSubscriber` 静态注解进行类自动订阅时，事件监听方法未声明为 `static`。
*   **影响**：系统在类加载及事件自动注册时无法对其进行有效绑定，导致对应的事件处理逻辑**静默不触发**。

| ❌ 错误写法 (Bad - 非 static 监听) | 🟢 正确写法 (Good - static 静态监听) |
| :--- | :--- |
| ```java<br>// ❌ 监听方法非 static，系统将无法自动注册其订阅监听<br>@EventBusSubscriber(modid = MODID)<br>public class CapabilityRegistrar {<br>  @SubscribeEvent<br>  public void registerCaps(RegisterCapabilitiesEvent event) { ... }<br>}<br>``` | ```java<br>// 🟢 监听方法为 static 静态，系统在类加载时合规自动订阅<br>@EventBusSubscriber(modid = MODID)<br>public class CapabilityRegistrar {<br>  @SubscribeEvent<br>  public static void registerCaps(RegisterCapabilitiesEvent event) { ... }<br>}<br>``` |

> **一律省略 `bus` 说明**：
> 自 1.21.1 起，`@EventBusSubscriber` 的 `bus` 参数属性一律省略。系统会在底层根据事件参数类是否实现了 `IModBusEvent` 接口，自动判定并分流路由到对应的 Mod 或 Game 事件总线上；但注解下的订阅监听方法本身，**必须 100% 声明为 `static` 静态方法**，否则无法自动注册。

---

## 7. Tick 热路径中每帧分配对象与 Stream API (Hot-Path Allocation)

*   **痛点**：在 `tick` / 渲染等每秒 20+ 次的热路径中 `new` 临时对象（`BlockPos`、集合）或使用 Stream 链式 API。
*   **影响**：不崩溃，但持续制造 GC 压力，表现为周期性卡顿尖峰（stutter），实体多时 TPS 显著下降。

| ❌ 错误写法 (Bad - 每 tick 分配) | 🟢 正确写法 (Good - 复用与朴素循环) |
| :--- | :--- |
| ```java<br>// ❌ 每 tick 都走 Stream + 装箱 + 新集合<br>public void serverTick() {<br>  List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box)<br>      .stream().filter(e -> !e.getItem().isEmpty())<br>      .collect(Collectors.toList());<br>  for (var e : items) { /* ... */ }<br>  BlockPos above = new BlockPos(getBlockPos().getX(), getBlockPos().getY() + 1, getBlockPos().getZ());<br>}<br>``` | ```java<br>// 🟢 朴素 for 循环 + MutableBlockPos 复用<br>private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();<br>public void serverTick() {<br>  for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {<br>    if (e.getItem().isEmpty()) continue;<br>    /* ... */<br>  }<br>  cursor.setWithOffset(getBlockPos(), Direction.UP);<br>}<br>``` |

---

## 8. BlockEntity 每 tick 全速扫描、无节流无早退 (Unthrottled Ticker)

*   **痛点**：`serverTick` 每一 tick 都执行昂贵操作（邻居 `getBlockState` 扫描、全物品栏遍历、配方匹配），且不区分客户端/服务端。
*   **影响**：单个方块开销 ×20/秒 ×放置数量，量产后 TPS 崩盘。

| ❌ 错误写法 (Bad - 全速全端扫描) | 🟢 正确写法 (Good - 早退 + 节流 + 缓存) |
| :--- | :--- |
| ```java<br>public static void tick(Level level, BlockPos pos, BlockState state, MyBE be) {<br>  // ❌ 客户端也在跑；每 tick 扫 6 个邻居 + 匹配配方<br>  for (Direction d : Direction.values()) {<br>    level.getBlockState(pos.relative(d));<br>  }<br>  be.matchRecipe();<br>}<br>``` | ```java<br>public static void tick(Level level, BlockPos pos, BlockState state, MyBE be) {<br>  if (level.isClientSide) return; // 🟢 服务端专属逻辑早退<br>  if (level.getGameTime() % 20 != 0) return; // 🟢 1 秒节流<br>  be.refreshCachedNeighbors(); // 🟢 结果缓存，邻居变更事件中失效<br>}<br>``` |

---

## 9. 高频事件监听器内做全量扫描 (Heavy Event Listener)

*   **痛点**：在 `PlayerTickEvent` / `LevelTickEvent` 等高频事件中遍历全部实体、全物品栏或做字符串拼接日志。
*   **影响**：开销随玩家数与实体数线性放大，服务器越热闹越卡。

| ❌ 错误写法 (Bad - 高频全量) | 🟢 正确写法 (Good - 最廉价条件先行早退) |
| :--- | :--- |
| ```java<br>@SubscribeEvent<br>public static void onPlayerTick(PlayerTickEvent.Post event) {<br>  // ❌ 每玩家每 tick 遍历整个背包<br>  for (ItemStack s : event.getEntity().getInventory().items) {<br>    if (s.is(ModItems.CHARM.get())) applyBuff(event.getEntity());<br>  }<br>}<br>``` | ```java<br>@SubscribeEvent<br>public static void onPlayerTick(PlayerTickEvent.Post event) {<br>  Player p = event.getEntity();<br>  if (p.level().isClientSide) return;        // 🟢 最便宜的判断放最前<br>  if (p.tickCount % 20 != 0) return;          // 🟢 节流到 1 秒一次<br>  if (!p.getMainHandItem().is(ModItems.CHARM.get())) return; // 🟢 廉价单槽检查替代全背包<br>  applyBuff(p);<br>}<br>``` |

---

## 10. Capability 每次现查不缓存 (Uncached Capability Lookup)

*   **痛点**：BlockEntity 每 tick 对邻居调用 `level.getCapability(...)` 现场解析目标。
*   **影响**：能力查找含注册表与提供者链解析，热路径反复执行造成无谓开销。

| ❌ 错误写法 (Bad - 每 tick 现查) | 🟢 正确写法 (Good - BlockCapabilityCache) |
| :--- | :--- |
| ```java<br>public void serverTick() {<br>  // ❌ 每 tick 重新解析邻居的 ItemHandler<br>  IItemHandler h = level.getCapability(<br>      Capabilities.ItemHandler.BLOCK, worldPosition.above(), null);<br>  if (h != null) pushItems(h);<br>}<br>``` | ```java<br>// 🟢 建立一次缓存，邻居变化自动失效（NeoForge 官方 API）<br>private BlockCapabilityCache<IItemHandler, @Nullable Direction> targetCache;<br>public void onLoad() {<br>  if (level instanceof ServerLevel sl) {<br>    targetCache = BlockCapabilityCache.create(<br>        Capabilities.ItemHandler.BLOCK, sl, worldPosition.above(), Direction.DOWN);<br>  }<br>}<br>public void serverTick() {<br>  IItemHandler h = targetCache.getCapability();<br>  if (h != null) pushItems(h);<br>}<br>``` |

---

## 11. Mixin 滥用与 `@Overwrite` (Mixin Abuse)

*   **痛点**：事件或 Access Transformer 能解决的问题直接上 Mixin，甚至用 `@Overwrite` 整体替换原版方法。
*   **影响**：`@Overwrite` 与任何其他修改同一方法的模组**物理互斥**（后加载者静默覆盖前者）；原版小版本更新即碎裂。这是模组间兼容性事故的第一来源。

| ❌ 错误写法 (Bad - Overwrite 独占) | 🟢 正确写法 (Good - 事件优先，注入最小化) |
| :--- | :--- |
| ```java<br>@Mixin(Player.class)<br>public class PlayerMixin {<br>  // ❌ 整体替换方法：与其他模组物理冲突且版本脆弱<br>  @Overwrite<br>  public void jumpFromGround() {<br>    /* 自定义跳跃 */<br>  }<br>}<br>``` | ```java<br>// 🟢 第一优先：事件已覆盖绝大多数需求<br>@SubscribeEvent<br>public static void onJump(LivingEvent.LivingJumpEvent e) { /* ... */ }<br><br>// 🟢 确需 Mixin 时：@Inject 最小注入点 + cancellable，<br>// 字段/方法可见性问题优先用 Access Transformer 而非 Mixin<br>@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)<br>private void myJump(CallbackInfo ci) { /* ... */ }<br>``` |

> **修改原版行为的手段优先级**：NeoForge 事件 > Access Transformer（改可见性）> Mixin `@Inject` 最小注入 > `@Redirect`（须评估共存）。**`@Overwrite` 在本工具包内视为禁手**，除非用户明确要求并知晓兼容性代价。