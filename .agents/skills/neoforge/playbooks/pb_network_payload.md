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
5. 明确 Handler 执行线程：`PayloadRegistrar` 默认是 `HandlerThread.MAIN`，可直接执行已校验的主线程逻辑。
6. 只有显式 `.executesOn(HandlerThread.NETWORK)` 时，网络线程阶段才需限制为纯数据计算；回写世界/玩家状态必须 `context.enqueueWork(...)`，并处理返回 Future 的异常。
7. 客户端发包入口放在 client 隔离类中。

## 只读 1 个 reference
- [network_payloads.md](../references/network_payloads.md)

## MCP 必查
- `CustomPacketPayload`、`StreamCodec`、`PayloadRegistrar`、`HandlerThread`、`IPayloadContext`

## 禁止
- 显式 `HandlerThread.NETWORK` 的 Handler 直接访问或修改 Level/Entity
- 忽略 `executesOn(...)` 会返回新 registrar，导致线程配置实际未生效
- 超过 6 字段仍硬上 composite  
- common 直接 import client 发包工具类

## 门禁
```bash
python .agents/run.py .agents/gates/compile_and_repair.py --with-static
```

## 完成证据
- 变更路径 + L1+L2  
- 指明每个 Handler 的注册方向与执行线程；默认 MAIN 不强制重复 `enqueueWork`
- 若使用 NETWORK，指出纯计算边界、`enqueueWork` 回写位置与 Future 异常处理
- payload id 命名空间 = 当前 `mod_id`
