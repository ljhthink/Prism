# Prism

> 手机端 AI 聊天 Agent 应用 —— MCP + Skills + 个人知识库 + 记忆系统 + BYOK 多端点 + 轻量跨 App 调用，六位一体的个人 AI Agent 平台。

## 项目状态

🚧 **M0 脚手架已完成（US-001 通过 guardrail 三轮审查 + ac-verifier 验收），进入 M1 编码阶段**（2026-08-02）

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

### Reference（参考 / 报告）

- [docs/PRD.md](docs/PRD.md) —— 产品需求文档 v0.1
- [prd.json](prd.json) —— Ralph 格式任务分解（M0-M2 首期 10 个用户故事）
- [docs/reports/](docs/reports/) —— 调研与考古报告
  - [2026-08-02-prism-feasibility-research.md](docs/reports/2026-08-02-prism-feasibility-research.md) —— 可行性调研汇报 v1.0
  - [2026-08-02-prism-tech-selection.md](docs/reports/2026-08-02-prism-tech-selection.md) —— 技术选型对比分析（tech-selection-researcher）
  - [2026-08-02-continuous-learning-archaeology.md](docs/reports/2026-08-02-continuous-learning-archaeology.md) —— Continuous-learning 考古（code-archaeologist）
  - [2026-08-02-openclaw-archaeology.md](docs/reports/2026-08-02-openclaw-archaeology.md) —— OpenClaw/NullClaw 考古（code-archaeologist）
  - [2026-08-02-us001-m0-scaffold-guardrail.md](docs/reports/2026-08-02-us001-m0-scaffold-guardrail.md) —— US-001 M0 脚手架安全与质量审计（guardrail-enforcer，三轮）
  - [2026-08-02-us001-m0-scaffold-acceptance.md](docs/reports/2026-08-02-us001-m0-scaffold-acceptance.md) —— US-001 M0 脚手架验收测试（ac-verifier）

### 运维

- [docs/behavioral-rules.md](docs/behavioral-rules.md) —— 行为规则动态累积层
- `docs/runbooks/` —— 运维知识库（按需建立）

## 工作准则

- [CLAUDE.md](CLAUDE.md) —— AI 编程行为最高准则（必读）
