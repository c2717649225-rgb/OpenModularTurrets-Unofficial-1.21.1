# T01 — 注册示例物品与方块

## Prompt（给 Agent）

在本 NeoForge 1.21.1 工程中新增一个示例方块与对应物品，并加入创造模式物品栏。  
遵守 `.agents/AGENTS.md`。写码前读 `gradle.properties`。完成后跑 L1+L2 门禁并给出证据，  
最后运行 `python .agents/eval/grade.py T01` 自检，将其输出一并附上。

## 允许阅读

- `playbooks/pb_register_item_block.md`  
- 至多再 1 个 reference/example  

## 评分要点（勿发给 Agent）

- [ ] DeferredRegister 使用当前 mod_id  
- [ ] L1+L2 通过  
- [ ] 无 `net.minecraft.client` 进 common  

**机器批卷**：`python .agents/eval/grade.py T01`（以 git 基线的新增代码为准；PARTIAL=未入创造页签）
