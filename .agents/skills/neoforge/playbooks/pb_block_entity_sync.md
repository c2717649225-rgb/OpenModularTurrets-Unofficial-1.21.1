# Playbook: BlockEntity 保存与同步

## 适用 / 不适用
- **适用**：方块实体状态保存、客户端同步、与方块绑定。  
- **不适用**：纯物品数据（Data Component）、全局世界数据（SavedData）。

## 前置
1. 读包名 / `mod_id`。  
2. 先有对应 Block（可先走注册 playbook）。

## 步骤
1. 注册 `BlockEntityType` + BE 类。  
2. `saveAdditional` / `loadAdditional` 使用带 `HolderLookup.Provider` 的 1.21 签名（MCP 核对）。  
3. 需要客户端显示时实现同步路径（如 `getUpdatePacket` / `getUpdateTag` 等，以源码为准）。  
4. 容器/物品栏用 handler，能力暴露见 capabilities reference（本 playbook 不展开）。  
5. 客户端 BER 必须 `Dist.CLIENT` 隔离。

## 只读 1 个 reference
- [block_entities.md](../references/block_entities.md)

## MCP 必查
- `BlockEntity`、`BlockEntityType`、`saveAdditional`、`loadAdditional`

## 禁止
- 旧版无 Provider 的 save/load 签名  
- BER/渲染代码进 common  
- 静态 `.get()` 解包

## 门禁
```bash
python .agents/gates/compile_and_repair.py --with-static
```

## 完成证据
- 变更路径 + L1+L2  
- 说明保存字段与同步策略各一句
