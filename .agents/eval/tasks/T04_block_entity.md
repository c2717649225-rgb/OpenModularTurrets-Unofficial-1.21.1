# T04 — BlockEntity

## Prompt（给 Agent）

为示例方块增加 BlockEntity，保存一个整数字段到世界，重进游戏后仍在。  
使用 1.21 的 save/load 签名。L1+L2 证据，  
并运行 `python .agents/eval/grade.py T04` 自检、附其输出。

## 允许阅读

- `playbooks/pb_block_entity_sync.md`  
- 至多再 1 个 reference  

## 评分要点

- [ ] BlockEntityType 注册  
- [ ] HolderLookup 相关签名正确（以 MCP 为准）  
- [ ] L1+L2 通过  

**机器批卷**：`python .agents/eval/grade.py T04`（PARTIAL=缺 loadAdditional 或同步路径）
