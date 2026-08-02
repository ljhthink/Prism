# 子 Agent 报告模板（目录）

> 本目录存放各子 Agent 报告的模板。依 CLAUDE.md 第 7.4 节，子 Agent 报告必须含任务令牌字段。

## 模板清单

| 模板 | 用途 | 生成 Agent |
|---|---|---|
| [acceptance-template.md](acceptance-template.md) | 验收测试报告 | ac-verifier |
| [archaeology-template.md](archaeology-template.md) | 源码考古报告 | code-archaeologist |
| [debug-template.md](debug-template.md) | 调试报告 | 主 Agent（TRAE-debugger） |
| [guardrail-template.md](guardrail-template.md) | 安全与质量审计报告 | guardrail-enforcer |

## 命名规范

`docs/reports/YYYY-MM-DD-<task>-<type>.md`

- type ∈ {acceptance, archaeology, debug, guardrail, bug, incident, postmortem}
