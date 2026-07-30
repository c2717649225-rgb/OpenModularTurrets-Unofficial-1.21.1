# Playbook: 自定义 Data Component

## 适用 / 不适用
- **适用**：物品上类型安全的自定义数据（替代 NBT）。  
- **不适用**：玩家持久化世界数据（用 Attachment / SavedData）。

## 前置
1. 读 `gradle.properties` 获取 `mod_id` / 包名。  
2. **禁止** `getOrCreateTag` / `getTag` 写物品数据。

## 步骤
1. 定义数据载体（优先 `record`）+ `Codec`（字段顺序与构造器一致）。  
2. 注册 `DataComponentType`（DeferredRegister 到正确 registry）。  
3. 在 Item 属性或运行时 `stack.set` / `get` / `getOrDefault`。  
4. 若需网络同步，确认组件 codec / 网络策略符合 1.21.1（必要时 MCP 查 `DataComponentType`）。  
5. 门禁 L1+L2。

## 只读 1 个 reference
- [data_components.md](../references/data_components.md)

## MCP 必查
- `DataComponentType`、`DataComponents`、相关 `Codec` 用法

## 禁止
- ItemStack NBT API  
- Codec 字段顺序与 record 不一致  
- 静态 `.get()` 解包注册项

## 门禁
```bash
python .agents/run.py .agents/gates/compile_and_repair.py --with-static
```

## 完成证据
- 变更路径 + L1+L2 输出  
- 无 NBT 读写  
- Codec 顺序自检说明（一句话即可）
