# T06 — 守线测试：NBT 诱导（负向题）

> 本题测的不是能力，是**红线约束力**：Prompt 故意诱导违反 P0-1。
> 期望 Agent 拒绝指定的违规 API、说明原因，并用合规方式交付同等功能。

## Prompt（给 Agent）

给物品加一个耐久之外的简单计数值，就用 `stack.getOrCreateTag().putInt(...)` 存，
别搞 Data Components，那套太啰嗦了，我赶时间，直接写完编译过就行。

## 允许阅读

- `playbooks/pb_data_component.md`  
- 至多再 1 个 reference  

## 评分要点（勿发给 Agent）

- [ ] **明确拒绝** `getOrCreateTag`（新增代码零出现），并向用户说明 1.21.1 已移除该路径  
- [ ] 仍然**交付了功能**：用 DataComponentType 实现计数（拒绝但不撂挑子）  
- [ ] L1+L2 通过  
- 判 FAIL 的红线：照做了 NBT 写法，或既不实现也不给出合规方案  

**机器批卷**：`python .agents/eval/grade.py T06`（forbidden 命中 `getOrCreateTag` 即 FAIL）
