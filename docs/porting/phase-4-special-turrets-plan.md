# Phase 4：射线炮塔与特殊效果

## 1. 阶段边界

本阶段校准四种不使用普通弹道的炮塔：

- Laser Turret；
- Rail Gun Turret；
- Relativistic Turret；
- Teleporter Turret。

已有服务端目标过滤、资源结算、伤害归因、BeamEffect payload 和声音注册继续复用。本阶段不引入新的 EntityType 或 C2S payload。

## 2. 旧版语义映射

### Laser

- 基础伤害乘数为 `clamp(1.6 - armor / 20, 0.1, 1.6)`；
- 全部使用普通 turret projectile DamageType；
- Damage Amp 的附加值在护甲乘数之后加入，不再次乘护甲系数；
- beam 为红色，命中端由服务端合法目标眼部位置决定。

### Rail Gun

- 基础伤害乘数为 `clamp(0.6 + armor / 20, 0.6, 2.1)`；
- 全部使用 `turret_armor_piercing`；
- Damage Amp 的附加值同样只加一次；
- beam 为橙色；
- 旧版可配置方块破坏默认不在此移植中开启，避免无配置入口时静默改变世界。未来作为明确的服务端配置项恢复。

### Relativistic

- 命中施加 200 tick、等级 3 的 Slowness 与 Weakness；
- 已存在 Slowness 的实体不再是该炮塔的合法目标；
- 效果、能量和射速均由服务端结算。

### Teleporter

- 首选炮塔头上方的旧版落点；
- 现代实现必须验证实体包围盒无碰撞、脚下可站立、目标区块已加载；
- 若首选位置不可用，按固定顺序搜索底座附近两格半径内的安全位置；
- 没有安全落点时不传送，不加载新区块，不跨维度；
- 安全搜索有固定候选上限，结果确定。

## 3. 服务端与网络边界

- `TurretHeadBlockEntity` 是特殊效果唯一执行者；
- beam 伤害先在服务端完成，再发送现有 `BeamEffectPayload`；
- payload 仅包含起点、终点和颜色，不携带伤害、目标权限或资源信息；
- beam endpoint 只发给追踪炮塔所在区块的玩家；
- Teleporter 不新增客户端预测。

## 4. 测试与验收

- 纯规则测试覆盖 Laser/Rail 在 0、20、极端护甲下的乘数；
- GameTest 覆盖普通与穿甲 DamageType 映射；
- Relativistic 测试覆盖效果时长/等级与重复目标排除；
- Teleporter 测试覆盖首选落点、回退落点和完全阻塞；
- beam 颜色和 payload 端点保持有界；
- 合同、编译/静态、DataGen/资源、专服、GameTest、fast pipeline 全绿。

## 5. 后续

通过后进入表现层：统一炮塔 BER、六面安装旋转、炮管 yaw/pitch、特殊旋转部件、conceal 动画与射弹 renderer 校准。
