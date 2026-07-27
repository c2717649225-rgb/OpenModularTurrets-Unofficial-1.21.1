# T03 — Network Payload

## Prompt（给 Agent）

实现一个简单的 C2S Payload（例如发送一个 action 字符串），服务端 Handler 校验并安全处理。
`PayloadRegistrar` 默认 MAIN，可直接执行主线程逻辑，不要求重复 `enqueueWork`。如果显式使用
`.executesOn(HandlerThread.NETWORK)`，网络线程阶段只能做纯数据计算，状态回写必须 `enqueueWork`
并处理返回 Future 的异常。客户端入口隔离。附 L1+L2 证据，
并运行 `python .agents/eval/grade.py T03` 自检、附其输出。

## 允许阅读

- `playbooks/pb_network_payload.md`  
- 至多再 1 个 reference  

## 评分要点

- [ ] CustomPacketPayload + StreamCodec  
- [ ] Handler 注册方向与执行线程清晰；默认 MAIN 或显式 NETWORK 均与实现一致
- [ ] 仅在显式 NETWORK 且需要回写游戏状态时使用 enqueueWork，并处理 Future 异常
- [ ] 无 common→client 泄漏  
- [ ] L1+L2 通过  

**机器批卷**：`python .agents/eval/grade.py T03`。机器只对“显式 NETWORK + 可识别状态写入”
做条件检查；跨文件注册、纯计算边界和业务校验仍需人工复核。
