# Phase 8：1.12.2 机制与表现对账（2026-07-31）

## 基准与范围

本轮只以 `D:/c128/mods/reference-sources/OpenModularTurrets-1.12` 与
`OMLib-1.12` 为行为基准。没有新增玩法；所有改动仅用于恢复旧版判定、范围同步
或 1.21.1 的方块/渲染等价物。

## 已修复

- `BaseAttachmentBlock` 的默认碰撞/选择框恢复为旧版 12×12×6 附件板尺寸，修复
  Loot Deleter 的全宽碰撞框与可见模型错位。
- `TurretBaseBlock` 恢复旧版六个正交方向不允许基座互相紧贴的放置规则，避免
  附件归属歧义。
- `TurretHeadBlock` 与 `TurretBaseBlockEntity` 恢复旧版炮台放置钩子：新炮台的
  原生范围高于当前最大值时，基座的当前/最大范围立即晋级。

## 已核对且保持不变

- 五档物品栏扩展器和五档能量扩展器不检查基座等级；这是 1.12.2 的规则。
- 手摇充能器只允许连接一级基座，并且只支持水平四面；这是旧版限制，不是移植
  bug。
- 11 种炮台的所需基座等级、同类上限、每个基座总数量和附加槽位与旧版一致。
- 扩展器六向模型旋转保留当前 1.21.1 表：`DOWN=90`、`UP=270`、`EAST=90`、
  `WEST=270`、`NORTH=0`、`SOUTH=180`。NeoForge 的 `BlockModelRotation` 使用负
  四元数角度，不能直接照搬 1.12 Forge JSON 中的数值。
- 旧版 42 个方块纹理、58 个物品纹理均已完成路径和 SHA-256 对账；28 个注册
  方块的 blockstate、方块模型和物品模型均可解析。炮台头、摇柄继续使用与旧版
  等价的 BER/物品自定义渲染路径。

## 自动化证据

- `compile_and_repair.py --with-data --with-static --with-assets`：编译、静态、
  DataGen（291 个 JSON）和资源对账全部 PASS，静态错误/警告为 0。
- `gametest_gate.py --require-tests --run`：39/39 required GameTest PASS。
- `pipeline.py --profile fast`：PASS。
- `gradlew.bat build --no-daemon --console=plain`：BUILD SUCCESSFUL。

## 下一次接力顺序

1. 只读复核 1.12.2 与当前剩余行为差异（可选 OMLib/ComputerCraft 等集成、未完
   成 Potentia 流体按设计暂不纳入），先形成单个 Major 合同。
2. 优先完成客户端人工验证矩阵：11 炮台 × 六安装面、扩展器六面、摇柄四面、
   明暗环境、物品栏与世界渲染；若发现黑模/错位，先用最小渲染测试定位再改。
3. 再处理确有 1.12.2 证据的剩余功能或资源，并逐阶段运行 compile/static/DataGen/
   assets/GameTest/pipeline 门禁。

不得因为截图中的视觉现象直接改变射程、槽位、炮台等级或红石模式；先证明是
渲染/同步迁移差异，再做兼容修复。
