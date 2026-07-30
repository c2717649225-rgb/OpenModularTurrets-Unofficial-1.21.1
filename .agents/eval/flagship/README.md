# Flagship eval v1

这套评测回答一个比“能不能编译”更严格的问题：

> 指定模型能否在有限人工介入下，连续交付并维护大型 NeoForge
> 玩法系统，同时守住端侧、存档、性能、兼容和回归边界？

## 边界

- 仓库保存公开场景、结果格式、阈值和聚合器。
- AI 客户端、账号、密钥、任务驱动器和**隐藏行为测试**必须留在工具包外。
- 不允许被测 Agent 修改 gates、场景、fixture 或隐藏测试。
- 每个模型/版本/思考档位至少独立运行 5 次；不能只挑最好的一次。
- 一个结果文件只允许一种固定的模型、Agent 客户端、思考档位、工具权限和工具包
  版本；不同配置分别出报告，禁止混合平均。
- 《暮色森林》《灾变》只作为复杂度标杆；不得复制其受许可约束的代码或资产。

## 流程

1. 从干净、已提交的 starter 克隆独立工作区。
2. 固定模型完整版本、Agent 客户端版本、工具包版本和依赖锁定信息。
3. 只向 Agent 提供场景文件中的 `Prompt`；验收夹具由评测方持有。
4. 每个 Major 切片先提交功能合同，再实施并运行所需 gates。
5. 把每次结果写成下方格式，收集至少 5 次独立运行。
6. 验证并汇总：

```bash
python .agents/run.py .agents/eval/flagship/benchmark.py validate-suite
python .agents/run.py .agents/eval/flagship/benchmark.py report path/to/results.json
```

## 单次结果格式

```json
{
  "runs": [
    {
      "run_id": "I01-gemini-3.5-flash-001",
      "scenario_id": "I01",
      "model": "gemini-3.5-flash",
      "model_version": "exact-provider-version",
      "agent_runtime": "exact-client-and-version",
      "reasoning_effort": "high",
      "tool_profile": "filesystem+shell+minecraft-mcp",
      "toolkit_version": "1.3.0",
      "commit_sha": "full-git-sha",
      "result": "pass",
      "p0_escapes": 0,
      "repair_loops": 1,
      "behavior_tests_total": 18,
      "behavior_tests_passed": 18,
      "prior_behaviors_checked": 12,
      "regressions": 0,
      "human_minutes": 8,
      "gate_evidence": [
        "contract PASS",
        "L1+L2 PASS",
        "L4 18/18 PASS"
      ]
    }
  ]
}
```

`result=pass` 只能在隐藏行为测试和场景要求的全部门禁通过后记录。
模型主动暴露不可安全推断的设计缺口，不计作失败；擅自猜测并造成错误才计入。

## v1 发布阈值

- P0 逃逸为 0。
- 最多两轮修复后的通过率不低于 90%。
- 旧行为保持率不低于 95%。
- 每个场景至少 5 次独立运行。

这些是工具包的质量目标，不是对单个模组玩法品质的替代。美术、音效、战斗手感
和内容节奏仍需人工评审与真实玩家测试。
