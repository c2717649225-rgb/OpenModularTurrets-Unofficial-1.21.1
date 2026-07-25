---
status: verified
pin_minecraft: 1.21.1
pin_neo: 21.1.x
last_verified: 2026-07-25
---
# NeoForge 1.21.1 常见开发地雷与反例对照表 (Anti-Patterns)

> [!WARNING]
> **⚠️ 示例包名禁原样粘贴**：
> 下方所有示例及 references 中的 `com.tutorial.tutorialmod` 均为占位。写入前必须通过读取 `gradle.properties`（获取真实 Group/MOD ID）并执行 `init_workspace.py` 动态重构为当前项目的真实命名空间，严禁硬编码提交。


这是一个专门面向 AI 和模组开发者的避坑反例指南。以下 6 个地雷在 1.21.1 中会导致直接编译报错、启动闪退或存档损坏。请对比 Bad 写法与 Good 写法：

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