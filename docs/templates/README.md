# 模板索引

> 依 CLAUDE.md 第五节，新增 PRD/ARCH/ADR/Task 时必须从本目录复制对应模板。

## 通用模板

| 模板 | 用途 |
| --- | --- |
| [adr-template.md](adr-template.md) | 架构决策记录 |
| [prd-template.md](prd-template.md) | 产品需求文档 |
| [arch-template.md](arch-template.md) | 架构设计文档 |
| [bug-report-template.md](bug-report-template.md) | 项目后期用户手动测试发现 Bug 后的结构化报告 |
| [postmortem-template.md](postmortem-template.md) | 运维事件事后复盘（blameless） |
| [runbook-template.md](runbook-template.md) | 运维知识库条目（RCA 阶段 RAG 检索复用） |
| [behavioral-rules-template.md](behavioral-rules-template.md) | 行为规则累积文件（动态经验沉淀） |
| [incident-report-template.md](incident-report-template.md) | 运维事件实时处置记录 |
| [consistency-audit-template.md](consistency-audit-template.md) | 里程碑一致性审计报告 |
| [error-code-registry-template.md](error-code-registry-template.md) | 错误码全局注册表 |
| [performance-baseline-template.md](performance-baseline-template.md) | 性能基线（ac-verifier 回退检查依据） |

## 子 Agent 报告模板

> 存于 `reports/` 子目录。依 CLAUDE.md 第 7.4 节，子 Agent 报告必须含任务令牌字段。

| 模板 | 用途 | 生成 Agent |
| --- | --- | --- |
| [reports/acceptance-template.md](reports/acceptance-template.md) | 验收测试报告 | ac-verifier |
| [reports/archaeology-template.md](reports/archaeology-template.md) | 源码考古报告 | code-archaeologist |
| [reports/debug-template.md](reports/debug-template.md) | 调试报告 | 主 Agent（TRAE-debugger） |
| [reports/guardrail-template.md](reports/guardrail-template.md) | 安全与质量审计报告 | guardrail-enforcer |

## 命名规范

- 模板文件：`<name>-template.md`
- 报告文件：`docs/reports/YYYY-MM-DD-<task>-<type>.md`
- type ∈ {acceptance, archaeology, debug, guardrail, bug, incident, postmortem}
