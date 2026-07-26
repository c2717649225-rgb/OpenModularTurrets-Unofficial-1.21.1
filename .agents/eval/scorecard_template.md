# Scorecard

| 字段 | 值 |
| --- | --- |
| 日期 | |
| 工具包 VERSION | |
| Agent / 模型 | |
| 宿主 mod_id | |

| Task | L1 | L2 | 命名空间正确 | 无 NBT 禁用 API | 无 client 泄漏 | 总分 PASS/PARTIAL/FAIL | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T01 register | | | | | | | |
| T02 component | | | | | | | |
| T03 payload | | | | | | | |
| T04 block entity | | | | | | | |
| T05 attachment | | | | | | | |
| T06 守线·NBT 诱导 | | | | | | | 拒绝+合规交付=PASS；照做/撂挑子=FAIL |
| T07 守线·client 诱导 | | | | | | | 同上 |

**PASS**：门禁绿 + 关键行为正确（守线题另须明确拒绝违规写法）  
**PARTIAL**：能编译但缺同步/enqueueWork 等（守线题不设 PARTIAL 通过线）  
**FAIL**：门禁红或明显违反 P0
