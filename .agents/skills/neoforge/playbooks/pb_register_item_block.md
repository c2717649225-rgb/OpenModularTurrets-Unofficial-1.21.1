# Playbook: 注册物品 / 方块 / 创造页签

## 适用 / 不适用
- **适用**：DeferredRegister 注册 Block、Item、CreativeModeTab。  
- **不适用**：复杂 BE、网络、Mixin（换其他 playbook / reference）。

## 前置
1. 读取宿主 `gradle.properties` → `mod_id`、`mod_group_id`。  
2. 确认主类与 `DeferredRegister` 已挂到 mod 事件总线。

## 步骤
1. 在合适的注册类中声明 `DeferredRegister.Blocks` / `Items` / `CreativeModeTab`。  
2. 用 `registerSimpleBlock` / `registerSimpleItem` 或完整 `register`。  
3. 方块需同步 `BlockItem`（`registerSimpleBlockItem` 或等价）。  
4. 创造页签 `displayItems` 中 `output.accept(...)`；或 `BuildCreativeModeTabContentsEvent`。  
5. 语言键写入 `assets/<mod_id>/lang/`（可手写 lang）。  
6. 模型/掉落等 JSON 走 DataGen（见 `blockstates_models_datagen`），勿手写生产资源。

## 只读 1 个 reference
- [quick_skeletons.md](../references/quick_skeletons.md) 或 [registration_example.md](../examples/registration_example.md)

## MCP 必查
- `DeferredRegister`、`DeferredBlock`、`DeferredItem`、`CreativeModeTab`

## 禁止
- 静态初始化 `.get()`  
- 硬编码错误 mod id  
- 通读整个 references 目录

## 门禁
```bash
python .agents/run.py .agents/gates/compile_and_repair.py --with-static
# 若新增需生成的 JSON：
python .agents/run.py .agents/gates/compile_and_repair.py --with-static --with-data
```

## 完成证据
- 变更路径列表  
- L1+L2 通过输出  
- 注册 id 与 `mod_id` 一致
