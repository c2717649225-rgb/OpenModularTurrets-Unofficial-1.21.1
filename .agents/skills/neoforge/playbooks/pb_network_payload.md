# Playbook: 网络 Payload（C2S / S2C）

## 适用 / 不适用
- **适用**：自定义 `CustomPacketPayload` + `StreamCodec` + 注册 handler。  
- **不适用**：仅客户端 UI、不改游戏状态的本地逻辑。

## 前置
1. 读 `mod_id` 用于 `ResourceLocation` / payload type id。  
2. MCP 确认当前 Neo 版本 `PayloadRegistrar` 注册方式。

## 步骤
1. 定义 `record` 实现 `CustomPacketPayload`：`TYPE` + `STREAM_CODEC`。  
2. `StreamCodec.composite` ≤ 6 字段；更多用 `StreamCodec.of`。  
3. 含 `ItemStack` 等注册项时使用 `RegistryFriendlyByteBuf`。  
4. 在正确事件中注册 payload 与 handler（方向 C2S/S2C）。  
5. Handler 内改世界/玩家：**必须** `context.enqueueWork(...)`。  
6. 客户端发包入口放在 client 隔离类中。

## 只读 1 个 reference
- [network_payloads.md](../references/network_payloads.md)

## MCP 必查
- `CustomPacketPayload`、`StreamCodec`、`PayloadRegistrar`、`IPayloadContext`

## 禁止
- 网络线程直接改 Level/Entity  
- 超过 6 字段仍硬上 composite  
- common 直接 import client 发包工具类

## 门禁
```bash
python .agents/gates/compile_and_repair.py --with-static
```

## 完成证据
- 变更路径 + L1+L2  
- 指出 enqueueWork 位置  
- payload id 命名空间 = 当前 `mod_id`
