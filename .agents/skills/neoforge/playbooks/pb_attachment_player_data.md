# Playbook: Attachment 存储玩家（或实体）数据

## 适用 / 不适用
- **适用**：挂到 Entity/Player 等上的持久化/同步数据（NeoForge Attachment）。  
- **不适用**：ItemStack 数据（用 Data Component）、方块内状态（用 BE）。

## 前置
1. 读 `mod_id` / 包名。  
2. MCP 确认 `AttachmentType` 注册与 `getData`/`setData` API。

## 步骤
1. 定义数据载体 + Codec（record 顺序一致）。  
2. 注册 `AttachmentType`（DeferredRegister 到正确 registry）。  
3. 在服务端逻辑读写 attachment；客户端仅显示需同步时按文档配置 copy/sync。  
4. 不要把权威状态只放在客户端。  
5. 门禁 L1+L2。

## 只读 1 个 reference
- [capabilities_attachments.md](../references/capabilities_attachments.md)

## MCP 必查
- `AttachmentType`、相关注册与 getter API

## 禁止
- 用 Item NBT 冒充玩家数据  
- 仅客户端修改权威数据  
- 静态过早 `.get()`

## 门禁
```bash
python .agents/skills/workspace_setup/scripts/compile_and_repair.py --with-static
```

## 完成证据
- 变更路径 + L1+L2  
- 说明数据挂载目标（Player/Entity）与是否同步
