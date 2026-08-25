# 视觉资产规则（可移植）：模型/贴图错位的根因与门禁

> 目标读者：任何 NeoForge 1.21.x 项目的维护者与其 AI 协作者。
> 本文档与 `.agents/gates/asset_gate.py` 的 visual-integrity 规则族配套；
> 复制新项目时请**同时带走**：本文档 + asset_gate.py + visual-integrity.json 配置。

## 一、三类高发缺陷与根因

### 1. 附着方块"悬空 / 贴反基座"
同一物理意图要写三遍：Java 碰撞箱、JSON 模型几何、blockstate 旋转表。
三处语法互不相同，且没有编译器校验一致性。

旋转表的额外陷阱：**顺逆时针混用只影响 east/west/up/down 四向**，
north/south 因 0°/180° 对称而"看起来正常"——抽查容易误判已修复。

### 2. "模型在，表皮不对"
程序化模型的 UV 预算公式：`texOffs(u,v)` 的部件尺寸 `w×h×d` 需要
`u + 2*(w+d) ≤ 纹理宽` 且 `v + d + h ≤ 纹理高`。越界不崩溃，
GPU 直接采样别的区域像素——表现为花屏/串皮。

### 3. 引用悬空
贴图路径打错、注册了方块忘生成 blockstate/model——全部只在游戏内
变成紫黑方块或隐形方块，构建期一片绿。

## 二、门禁用法

```bash
python .agents/run.py .agents/gates/asset_gate.py          # 本地
# CI: quality-gates workflow 已内置（L0 → L1+L2 → L2.5 → L4）
```

新增的 error 级规则：
| 规则 | 抓什么 |
| :--- | :--- |
| `attachment_model_geometry` | 家族方块的模型盒 ≠ Java 碰撞板（板尺寸从 Java 源码实时解析） |
| `attachment_rotation_table_drift` | 同家族方块的旋转表不一致 |
| `model_uv_overflow` | 两种写法（helper box() / 链式 texOffs().addBox()）的 UV 越界 |
| `renderer_texture_missing` | BER/覆盖层硬编码贴图路径不存在 |

## 三、新项目适配步骤

1. 复制 `asset_gate.py` 与本文件到新项目；
2. 新建 `.agents/gates/visual-integrity.json`（可选）：
   ```json
   {
     "attachment_prefixes": ["your_attach_"],
     "plate_source": "src/main/java/yourpkg/block/YourAttachmentBlock.java"
   }
   ```
   不建该文件时使用 OpenModularTurrets 默认值；
3. 保证 `plate_source` 指向的 Java 里存在 `case NORTH -> box(x1,y1,z1,x2,y2,z2)`
   ——这是几何比对的唯一真源；
4. 把 asset gate 加入 CI。

## 四、给 AI 协作者的固定指令模板

> 改动 blockstates / models / textures / 程序化模型代码后，必须运行
> `python .agents/run.py .agents/gates/asset_gate.py` 并在汇报中附输出。
> 新增附着方块一律继承共享形状方法；新增程序化模型部件必须声明
> texOffs 预算并确认不越界。禁止凭记忆填写六向旋转表——以同家族
> 已验证方块为基准复制后仅做必要微调。

## 五、已知边界

- 门禁是静态检查，不证明游戏内最终视觉；旋转角度的引擎语义曾由人工
  游戏内验收兜底（见 ModBlockStateProvider 的历史注释）；
- Windows 本地克隆若开启 autocrlf，contract digest 会因换行符漂移而
  误报——CI(Linux) 不受影响；本地复验用
  `git clone -c core.autocrlf=false`。
