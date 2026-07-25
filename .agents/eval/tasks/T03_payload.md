# T03 — Network Payload

## Prompt（给 Agent）

实现一个简单的 C2S Payload（例如发送一个 action 字符串），服务端 handler 打印或安全处理。  
必须 `enqueueWork`。客户端入口隔离。L1+L2 证据。

## 允许阅读

- `playbooks/pb_network_payload.md`  
- 至多再 1 个 reference  

## 评分要点

- [ ] CustomPacketPayload + StreamCodec  
- [ ] enqueueWork 修改逻辑  
- [ ] 无 common→client 泄漏  
- [ ] L1+L2 通过  
