# Phase 5：模型、动画与客户端特效

## 1. 完成口径

本阶段把已经保留的旧版像素资源真正接入 1.21.1 渲染管线，消除以下占位：

- 11 种炮塔头不再以完整立方体显示；
- concealed 的两个状态不再指向同一可见几何；
- yaw/pitch 同步由 BER 消费；
- 六个底座相邻面上的炮塔具有正确安装姿态；
- Inventory/Power Expander 与 Loot Deleter 使用顶面/侧面纹理和附件厚度；
- Rocket 不再使用普通投掷物平面 renderer；
- beam 的 payload color 被实际消费；
- deploy/retract、Damage Amp 及特殊炮塔粒子具有受控表现。

## 2. 模型架构

### 2.1 炮塔头

- `TurretHeadBlock` 使用 `RenderShape.INVISIBLE`，世界模型完全由单一
  `TurretHeadBlockEntityRenderer` 负责；
- 每种 `TurretDefinition` 注册独立 `ModelLayerLocation` 与 `LayerDefinition`；
- 旧 `ModelRenderer.addBox` 数据按 1/16 单位迁移为 `MeshDefinition/PartDefinition`，
  不重新发明几何；
- 模型至少拆为 mount、yaw、pitch 三层，yaw 与 pitch 分别施加；
- BER 从相邻底座方向推导安装旋转，不把展示方向写成冗余持久数据；
- concealed 时隐藏几何，并在状态边沿播放一次部署/收回声音；连续伸缩插值使用客户端瞬态进度。

### 2.2 附件

- expander 与 Loot Deleter 增加稳定 `FACING` BlockState，指向所连接底座；
- 放置时只接受已加载、满足 tier 的相邻底座；
- 使用薄附件模型和 side/top 纹理，不再是 full cube；
- DataGen 为六向状态生成旋转，物品模型使用向上的标准展示方向。

### 2.3 Addon 外观

- Damage Amp、Solar Panel、Redstone Reactor 按旧 renderer 的允许位叠加；
- addon 是否显示只读取相邻底座服务端同步的库存状态；
- addon 模型不持有 BlockEntity 引用，不增加网络包；
- Concealer 自身不显示外挂几何。

## 3. 实体与粒子

- Rocket 使用独立 3D renderer 和 `blocks/rocket.png`；
- 其他物品型射弹继续共享 `ThrownItemRenderer`；
- Rocket、Plasma 和特殊炮塔粒子只在客户端 tick 中生成，并设置每实体/每炮塔预算；
- Beam 使用 payload 的 RGB 值生成 dust 粒子，长度采样最多 96；
- Teleporter 成功时由服务端发送有界原生实体事件或现有表现信号，客户端生成 portal burst；
- deploy/retract/amped 声音只在真实状态边沿或触发时播放。

## 4. 客户端边界

- 所有 `net.minecraft.client` 引用只存在于 `omtteam.openmodularturrets.client`；
- common 注册仅保存 BlockEntityType/EntityType 和同步值；
- Dedicated Server 必须在没有模型类的情况下正常启动；
- 不为可由 BlockState、实体追踪或现有 payload 表达的状态新增包。

## 5. 验收

- 11 个炮塔定义均有模型层、纹理和 BER 路由；
- 六个安装方向的旋转映射有纯规则测试；
- concealed 与非 concealed 的可见性不同；
- 10 个 expander、Loot Deleter 的六向 blockstate/model 引用完整；
- Plasma 明确复用旧版 Grenade 几何与纹理，不再错误使用 Incendiary；
- Rocket renderer、beam color、粒子频率有静态/客户端 smoke 证据；
- DataGen、资产、Dedicated Server、GameTest 和 fast pipeline 全绿；
- 最终截图矩阵作为人工验收项保留，不以自动门禁替代视觉确认。

## 6. 后续

通过后进入完整基地 GUI、红石模式、信任管理、伪装、配置层和发布审计。
