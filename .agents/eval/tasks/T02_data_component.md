# T02 — Data Component

## Prompt（给 Agent）

为物品增加一个自定义 Data Component（例如整数 `example_charge`），支持读写。  
禁止 ItemStack NBT API。遵守 AGENTS.md，MCP-first，完成后 L1+L2 证据，  
并运行 `python .agents/eval/grade.py T02` 自检、附其输出。

## 允许阅读

- `playbooks/pb_data_component.md`  
- 至多再 1 个 reference  

## 评分要点

- [ ] 使用 DataComponentType，无 getOrCreateTag  
- [ ] Codec 与 record 字段顺序一致（static_gate `codec_field_order` 规则自动查）  
- [ ] L1+L2 通过  

**机器批卷**：`python .agents/eval/grade.py T02`
