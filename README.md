# Prism

> 手机端 AI 聊天 Agent 应用 —— MCP + Skills + 个人知识库 + 记忆系统 + BYOK 多端点 + 轻量跨 App 调用，六位一体的个人 AI Agent 平台。

## 项目状态

🚧 **M0 脚手架 + M1 数据层 + 安全层 + BYOK Provider 配置 + 聊天 UI + 流式请求 + Provider 切换已完成（US-001~US-007 通过 guardrail 审查 + ac-verifier 验收），推进 M2 MCP Client（US-008）**（2026-08-06）

- 平台：仅 Android（API 26+，Android 8.0+）
- 算力：纯云端 BYOK（用户自配 OpenAI/Claude/Ollama 等端点）
- 商业模式：个人开源免费 + 自发布（GitHub Releases / F-Droid / PGY）
- 协议：Apache 2.0
- 技术栈：见 [ADR-001](docs/decisions/ADR-001-prism-tech-stack.md)

## 文档索引（Diátaxis）

### Tutorial（教程）

- 本 README（新人入门入口）

### How-to Guide（操作指南）

- [docs/templates/](docs/templates/README.md) —— PRD / ARCH / ADR / Task 等模板

### Explanation（解释说明 / ADR）

- [docs/decisions/](docs/decisions/README.md) —— 架构决策记录
  - [ADR-001 Prism 技术栈与架构选型](docs/decisions/ADR-001-prism-tech-stack.md)（Accepted）
  - [ADR-002 Prism 聊天 UI 架构（US-005）](docs/decisions/ADR-002-prism-chat-ui-architecture.md)（Proposed）
  - [ADR-003 Provider 配置详情页接入（设置模块）](docs/decisions/ADR-003-prism-provider-config-settings.md)（Accepted）
  - [ADR-004 Prism Provider 流式请求（US-006/US-007）](docs/decisions/ADR-004-prism-provider-streaming.md)（Accepted）

### Reference（参考 / 报告）

- [docs/PRD.md](docs/PRD.md) —— 产品需求文档 v0.1
- [prd.json](prd.json) —— Ralph 格式任务分解（M0-M2 首期 10 个用户故事）
- [docs/reports/](docs/reports/) —— 调研与考古报告
  - [2026-08-02-prism-feasibility-research.md](docs/reports/2026-08-02-prism-feasibility-research.md) —— 可行性调研汇报 v1.0
  - [2026-08-02-prism-tech-selection.md](docs/reports/2026-08-02-prism-tech-selection.md) —— 技术选型对比分析（tech-selection-researcher）
  - [2026-08-02-continuous-learning-archaeology.md](docs/reports/2026-08-02-continuous-learning-archaeology.md) —— Continuous-learning 考古（code-archaeologist）
  - [2026-08-02-openclaw-archaeology.md](docs/reports/2026-08-02-openclaw-archaeology.md) —— OpenClaw/NullClaw 考古（code-archaeologist）
  - [2026-08-02-us002-objectbox-archaeology.md](docs/reports/2026-08-02-us002-objectbox-archaeology.md) —— US-002 ObjectBox 集成源码考古（code-archaeologist）
  - [2026-08-02-us003-apikey-archaeology.md](docs/reports/2026-08-02-us003-apikey-archaeology.md) —— US-003 API Key 加密存储源码考古（code-archaeologist）
  - [2026-08-02-us001-m0-scaffold-guardrail.md](docs/reports/2026-08-02-us001-m0-scaffold-guardrail.md) —— US-001 M0 脚手架安全与质量审计（guardrail-enforcer，三轮）
  - [2026-08-02-us002-objectbox-guardrail.md](docs/reports/2026-08-02-us002-objectbox-guardrail.md) —— US-002 ObjectBox 安全与质量审计（guardrail-enforcer）
  - [2026-08-02-us003-apikey-guardrail.md](docs/reports/2026-08-02-us003-apikey-guardrail.md) —— US-003 API Key 加密存储安全与质量审计（guardrail-enforcer）
  - [2026-08-02-us004-provider-config-guardrail.md](docs/reports/2026-08-02-us004-provider-config-guardrail.md) —— US-004 Provider 配置数据模型安全与质量审计（guardrail-enforcer）
  - [2026-08-02-us001-m0-scaffold-acceptance.md](docs/reports/2026-08-02-us001-m0-scaffold-acceptance.md) —— US-001 M0 脚手架验收测试（ac-verifier）
  - [2026-08-02-us002-objectbox-acceptance.md](docs/reports/2026-08-02-us002-objectbox-acceptance.md) —— US-002 ObjectBox 数据库基础验收测试（ac-verifier）
  - [2026-08-02-us003-apikey-acceptance.md](docs/reports/2026-08-02-us003-apikey-acceptance.md) —— US-003 API Key 加密存储验收测试（ac-verifier）
  - [2026-08-02-us004-provider-config-acceptance.md](docs/reports/2026-08-02-us004-provider-config-acceptance.md) —— US-004 Provider 配置数据模型验收测试（ac-verifier）
  - [2026-08-05-ui-config-guardrail.md](docs/reports/2026-08-05-ui-config-guardrail.md) —— v0.4 UI 配置弹层安全与质量审计（guardrail-enforcer，两轮）
  - [2026-08-05-ui-config-acceptance.md](docs/reports/2026-08-05-ui-config-acceptance.md) —— v0.4 UI 配置弹层验收测试（ac-verifier）
  - [2026-08-05-settings-provider-guardrail.md](docs/reports/2026-08-05-settings-provider-guardrail.md) —— Provider 配置详情页接入安全与质量审计（guardrail-enforcer，两轮）
  - [2026-08-05-settings-provider-acceptance.md](docs/reports/2026-08-05-settings-provider-acceptance.md) —— Provider 配置详情页接入验收测试（ac-verifier）
  - [2026-08-05-settings-provider-guardrail-round2.md](docs/reports/2026-08-05-settings-provider-guardrail-round2.md) —— Provider 配置详情页接入增量安全与质量审计（guardrail-enforcer，R2）
  - [2026-08-05-settings-provider-acceptance-round2.md](docs/reports/2026-08-05-settings-provider-acceptance-round2.md) —— Provider 配置详情页接入增量验收测试（ac-verifier，R2）
  - [2026-08-05-us006-provider-streaming-tech-selection.md](docs/reports/2026-08-05-us006-provider-streaming-tech-selection.md) —— US-006 流式请求技术选型对比（tech-selection-researcher）
  - [2026-08-05-us006-provider-streaming-guardrail.md](docs/reports/2026-08-05-us006-provider-streaming-guardrail.md) —— US-006 流式请求安全与质量审计（guardrail-enforcer，阻断）
  - [2026-08-05-us006-guardrail.md](docs/reports/2026-08-05-us006-guardrail.md) —— US-006 流式请求 CR-01~CR-05 修复复审（guardrail-enforcer，条件通过）
  - [2026-08-06-us006-guardrail-recheck.md](docs/reports/2026-08-06-us006-guardrail-recheck.md) —— US-006 流式请求 CR-02 残留修复复审（guardrail-enforcer，通过）
  - [2026-08-06-us006-acceptance.md](docs/reports/2026-08-06-us006-acceptance.md) —— US-006 流式请求验收测试（ac-verifier，通过）
  - [2026-08-06-us007-guardrail.md](docs/reports/2026-08-06-us007-guardrail.md) —— US-007 Provider 切换安全与质量审计（guardrail-enforcer，有条件通过）
  - [2026-08-06-us007-guardrail-round2.md](docs/reports/2026-08-06-us007-guardrail-round2.md) —— US-007 Provider 切换修复复审（guardrail-enforcer，通过）
  - [2026-08-06-us007-acceptance.md](docs/reports/2026-08-06-us007-acceptance.md) —— US-007 Provider 切换验收测试（ac-verifier，通过）
  - [性能基线](docs/reports/perf/) —— 性能基线报告目录
    - [2026-08-02-us002-objectbox-crud-baseline.md](docs/reports/perf/2026-08-02-us002-objectbox-crud-baseline.md) —— US-002 ObjectBox CRUD 性能基线
    - [2026-08-02-us003-apikey-baseline.md](docs/reports/perf/2026-08-02-us003-apikey-baseline.md) —— US-003 API Key 加密存储性能基线
    - [2026-08-02-us004-provider-config-baseline.md](docs/reports/perf/2026-08-02-us004-provider-config-baseline.md) —— US-004 Provider 配置数据模型性能基线

### 运维

- [docs/behavioral-rules.md](docs/behavioral-rules.md) —— 行为规则动态累积层
- `docs/runbooks/` —— 运维知识库（按需建立）

## 工作准则

- [CLAUDE.md](CLAUDE.md) —— AI 编程行为最高准则（必读）
