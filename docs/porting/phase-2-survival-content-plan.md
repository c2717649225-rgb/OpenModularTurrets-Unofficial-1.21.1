# Phase 2：生存获取链、声音与物品语义实施计划

## 1. 阶段目标

本阶段让当前“创造模式可取用”的内容进入可验证的生存模式闭环，并恢复战斗最基本的听觉反馈。范围严格限定为：

- 迁移旧版不依赖其他模组的 vanilla 配方；
- 修正 disposable 与 projectile-render-token 的弹药语义；
- 注册并接入旧版实际使用的声音事件；
- 补齐 Memory Card 清除和提示交互；
- 建立配方、声音、标签和交互的自动对账。

本阶段不实现 Mekanism/EnderIO 配方、最终 GUI、炮塔 BER、完整 addon/upgrade 机制，也不为旧版原本没有配方的内容凭空设计配方。

## 2. 旧版事实基线

### 2.1 配方

旧资源目录共有 159 个 JSON：

- 61 个 vanilla 配方族；
- 47 个 Mekanism 变体；
- 48 个 EnderIO 变体；
- 2 个旧 recipe framework 常量/工厂文件；
- 1 个重复扩展名的 Mekanism 文件。

本阶段只迁移 61 个 vanilla 配方，覆盖：

- 15 个 tiered intermediate：sensor、chamber、barrel；
- `io_bus`；
- 5 个 turret base；
- 10 个 inventory/power expander；
- 10 个旧版有配方的 turret；
- lever 与 loot deleter；
- 7 个不依赖外部模组的 addon；
- 5 个 upgrade；
- 5 个正式 ammo；
- memory card。

以下项目保持无默认生存配方，并在验收报告中显式列出：

- `addon_potentia`：旧功能已被注释且依赖已排除；
- `ammo_fake_disposable`：内部哨兵/渲染兼容项，不是玩家弹药；
- `throwable_bullet`、`throwable_grenade`：旧 projectile renderer token；
- `plasma_turret`：旧 vanilla 资源中没有配方，需后续独立设计决策。

### 2.2 弹药标签

- `throwable_bullet`、`throwable_grenade` 不应进入正式炮塔弹药标签。
- disposable turret 的默认可获得弹药恢复为 cobblestone 与 planks 语义。
- `ammo_fake_disposable` 保留 registry id 兼容性，但不再作为默认生存弹药来源。

### 2.3 声音

旧版 Java 实际注册 18 个 SoundEvent：

`amped`、`bullet_hit`、`disposable`、`grenade`、`incendiary`、`laser`、
`laser_hit`、`machine_gun`、`plasma_launch`、`potato`、`rail_gun`、
`rail_gun_hit`、`relativistic`、`rocket`、`teleport`、`turret_deploy`、
`turret_retract`、`warning`。

`windup.ogg` 只出现在旧注释代码中，未注册，不进入本阶段。

声音文件即使来源仓库采用 GPL-3.0，也仍需保存逐文件来源与哈希；本阶段允许在开发树中迁移并测试，但在 provenance 状态未闭环前不得声称“音频可无条件重新分发”。

## 3. 实施步骤

### Step A：功能契约与对账规则

- 新增 `survival_content_audio.contract.json`。
- 契约列出 61 配方目标、18 SoundEvent、弹药 tag 和 Memory Card 交互。
- 先运行 contract gate，失败不得进入交付状态。

### Step B：RecipeProvider

- 新增 `ModRecipeProvider` 并接入 `GatherDataEvent`。
- 逐个读取旧 vanilla JSON，保留 pattern、ingredient 和 output count。
- 旧 metadata 输出映射为当前独立 registry id。
- 旧 ore dictionary：
  - 有可靠现代公共 tag 时映射到 `c:`；
  - 原版已有稳定 tag 时优先使用原版 tag；
  - 没有可靠公共 tag 时使用旧配方对应的 vanilla item，禁止猜测第三方 tag。
- 每个配方具有唯一 ResourceLocation 和至少一个合理解锁条件。

### Step C：弹药标签纠偏

- bullets 只包含正式 bullet ammo。
- grenades 只包含正式 grenade ammo。
- disposable ammo 使用可获得的 cobblestone/planks tag 或明确 vanilla 集合。
- `ammo/all` 继续聚合子标签，不直接塞入 renderer token。

### Step D：声音注册与资源

- 新增 `ModSounds` DeferredRegister，共 18 个稳定 id。
- 主类注册 SoundEvent。
- 生成或维护 `sounds.json`，确保 18 事件与 18 个 ogg 一一对账。
- 服务端在发射/命中位置播放声音，由原版声音包同步到附近客户端。
- projectile 命中音由射弹实体触发；ray hit 音由服务端 ray 结算触发。
- deploy/retract、warning、amped 只在对应状态机制真实存在时接入，不用错误时机“占位播放”。

### Step E：Memory Card

- 蹲下对空气或非基座使用时，只移除 `memory_card_profile` Data Component。
- 不清除 ItemStack 的名称、附魔或其他组件。
- tooltip 显示空卡/已存配置及安全的概要字段。
- 服务端负责实际清除，客户端只返回一致的交互结果。

### Step F：测试与门禁

自动检查至少覆盖：

- 生成 recipe 数量为 61；
- 61 个配方没有旧 metadata id、旧 `forge:` tag 或第三方依赖；
- 明确的 5 个 creative-only 项没有被误生成配方；
- bullets/grenades/disposable 标签成员正确；
- 18 SoundEvent、sounds.json 条目、18 ogg 三方一致；
- Memory Card 清除后只丢失 profile 组件；
- Dedicated Server 不加载客户端声音类。

## 4. 验收标准

阶段自动验收：

```text
python .agents/run.py .agents/gates/contract_gate.py --require
python .agents/run.py .agents/gates/compile_and_repair.py --with-data --with-static --with-assets
python .agents/run.py .agents/gates/gametest_gate.py --require-tests --run
python .agents/run.py .agents/gates/compile_and_repair.py --with-server
python .agents/run.py .agents/gates/pipeline.py --profile fast
```

人工验收：

1. 在生存模式沿 tier 1 到 tier 5 中间件链合成，确认数量和剩余物正确。
2. 合成五种正式弹药，确认配方数量与旧版一致。
3. 用 cobblestone/planks 驱动 disposable turret，确认消耗的实物成为弹丸外观。
4. 分别触发 projectile 与 ray 炮塔，确认发射/命中声音只播放一次且具备空间衰减。
5. 给 Memory Card 添加自定义名称，再清除 profile，确认名称仍保留。

## 5. 风险与停止条件

- 旧音频逐文件 provenance 未确认：允许开发验证，不允许给出发布级授权结论。
- 旧配方引用不存在或语义不明确的 ore dictionary：停止该配方并记录，不做任意替代。
- DataGen 出现重复 recipe id 或生成数量不是 61：阶段不得标记完成。
- 声音文件缺失、sounds.json 多余或注册项未引用：资产对账不得放行。
