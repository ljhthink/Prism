# Prism

> 手机端 AI 聊天 Agent 应用 —— MCP + Skills + 个人知识库 + 记忆系统 + BYOK 多端点 + 轻量跨 App 调用，六位一体的个人 AI Agent 平台。

## 项目状态

🚧 **M0 脚手架 + M1 数据层 + 安全层 + BYOK Provider 配置 + 聊天 UI + 流式请求 + Provider 切换 + M2 MCP Client + M2 内置 Filesystem MCP Server + 预设远程 MCP Server 模板加载已完成（US-001~US-010 通过 guardrail 审查 + ac-verifier 验收）**（2026-08-06）

**M3 个人知识库 RAG 进行中（US-011~US-019，ADR-007 技术栈已定）**

- US-011 依赖落地 + KnowledgeChunk 向量索引 ✅（guardrail + ac-verifier 通过）
- US-012 文档解析器（PDF/DOCX/XLSX/MD/TXT）✅（guardrail 有条件通过 + ac-verifier 通过）
- US-013 文本切片器（段落边界优先 + overlap）✅（guardrail + ac-verifier 通过）
- US-014 端侧嵌入引擎（onnxruntime-android + all-MiniLM-L6-v2 INT8）✅（guardrial 两轮 + ac-verifier 通过，G-01 并发竞态阻断已修复）
- US-015 知识库分库数据模型（KnowledgeBase 实体 + Repository CRUD + 级联删除）✅（guardrail 两轮 + ac-verifier 通过，G-01 HNSW Query.remove bug #1209 已规避）

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
  - [ADR-005 MCP Kotlin SDK Client 集成（US-008）](docs/decisions/ADR-005-mcp-client-integration.md)（Accepted）
  - [ADR-006 内置 Filesystem MCP Server（US-009）](docs/decisions/ADR-006-filesystem-mcp-server.md)（Accepted）
  - [ADR-007 M3 个人知识库 RAG 技术栈（US-003）](docs/decisions/ADR-007-m3-rag-tech-stack.md)（Proposed）
  - [ADR-008 M3 知识库分库数据模型（US-015）](docs/decisions/ADR-008-m3-knowledgebase-model.md)（Proposed）

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
  - [2026-08-06-us008-mcp-client-archaeology.md](docs/reports/2026-08-06-us008-mcp-client-archaeology.md) —— US-008 MCP Client 集成源码考古（code-archaeologist）
  - [2026-08-06-us008-mcp-client-guardrail.md](docs/reports/2026-08-06-us008-mcp-client-guardrail.md) —— US-008 MCP Client 安全与质量审计（guardrail-enforcer，第一轮）
  - [2026-08-06-us008-mcp-client-guardrail-round2.md](docs/reports/2026-08-06-us008-mcp-client-guardrail-round2.md) —— US-008 MCP Client 安全与质量审计（guardrail-enforcer，第二轮）
  - [2026-08-06-us008-mcp-client-guardrail-round3.md](docs/reports/2026-08-06-us008-mcp-client-guardrail-round3.md) —— US-008 MCP Client 安全与质量审计（guardrail-enforcer，第三轮）
  - [2026-08-06-us008-mcp-client-guardrail-round4.md](docs/reports/2026-08-06-us008-mcp-client-guardrail-round4.md) —— US-008 MCP Client 安全与质量审计（guardrail-enforcer，第四轮，通过）
  - [2026-08-06-us008-mcp-integrationtest-guardrail.md](docs/reports/2026-08-06-us008-mcp-integrationtest-guardrail.md) —— US-008 真实 MCP Server 集成测试安全与质量审计（guardrail-enforcer，通过）
  - [2026-08-06-us008-mcp-client-acceptance.md](docs/reports/2026-08-06-us008-mcp-client-acceptance.md) —— US-008 MCP Client 集成验收测试（ac-verifier，有条件通过）
  - [2026-08-06-us008-mcp-client-acceptance-r2.md](docs/reports/2026-08-06-us008-mcp-client-acceptance-r2.md) —— US-008 MCP Client 集成验收复验（ac-verifier，通过）
  - [2026-08-06-us009-filesystem-mcp-archaeology.md](docs/reports/2026-08-06-us009-filesystem-mcp-archaeology.md) —— US-009 Filesystem MCP Server 源码考古与 SDK 复核（code-archaeologist）
  - [2026-08-06-us009-filesystem-mcp-guardrail.md](docs/reports/2026-08-06-us009-filesystem-mcp-guardrail.md) —— US-009 Filesystem MCP Server 安全与质量审计（guardrail-enforcer，两轮，通过）
  - [2026-08-06-us009-filesystem-mcp-acceptance.md](docs/reports/2026-08-06-us009-filesystem-mcp-acceptance.md) —— US-009 Filesystem MCP Server 验收测试（ac-verifier，通过）
  - [2026-08-06-us010-remote-templates-guardrail.md](docs/reports/2026-08-06-us010-remote-templates-guardrail.md) —— US-010 预设远程 MCP Server 模板安全与质量审计（guardrail-enforcer，条件通过 → 复审通过）
  - [2026-08-06-us010-remote-templates-acceptance.md](docs/reports/2026-08-06-us010-remote-templates-acceptance.md) —— US-010 预设远程 MCP Server 模板验收测试（ac-verifier，通过）
  - [2026-08-06-m0m2-milestone-audit.md](docs/reports/2026-08-06-m0m2-milestone-audit.md) —— M0-M2 首期里程碑交付审计（functional-validation-auditor，通过）
  - [2026-08-06-m3-rag-tech-selection.md](docs/reports/2026-08-06-m3-rag-tech-selection.md) —— M3 个人知识库 RAG 技术选型对比（tech-selection-researcher）
  - [2026-08-06-us011-deps-vectorindex-guardrail.md](docs/reports/2026-08-06-us011-deps-vectorindex-guardrail.md) —— US-011 依赖落地 + 向量索引安全与质量审计（guardrail-enforcer，通过）
  - [2026-08-06-us011-deps-vectorindex-acceptance.md](docs/reports/2026-08-06-us011-deps-vectorindex-acceptance.md) —— US-011 依赖落地 + 向量索引验收测试（ac-verifier，通过）
  - [2026-08-06-us012-document-parser-guardrail.md](docs/reports/2026-08-06-us012-document-parser-guardrail.md) —— US-012 文档解析器安全与质量审计（guardrail-enforcer，有条件通过）
  - [2026-08-06-us012-document-parser-acceptance.md](docs/reports/2026-08-06-us012-document-parser-acceptance.md) —— US-012 文档解析器验收测试（ac-verifier，通过）
  - [2026-08-06-us013-chunker-guardrail.md](docs/reports/2026-08-06-us013-chunker-guardrail.md) —— US-013 文本切片器安全与质量审计（guardrail-enforcer，通过）
  - [2026-08-06-us013-chunker-acceptance.md](docs/reports/2026-08-06-us013-chunker-acceptance.md) —— US-013 文本切片器验收测试（ac-verifier，通过）
  - [2026-08-07-us014-embedding-guardrail.md](docs/reports/2026-08-07-us014-embedding-guardrail.md) —— US-014 端侧嵌入引擎安全与质量审计（guardrail-enforcer，第一轮阻断，G-01 并发竞态）
  - [2026-08-07-us014-embedding-guardrail-round2.md](docs/reports/2026-08-07-us014-embedding-guardrail-round2.md) —— US-014 端侧嵌入引擎修复复审（guardrail-enforcer，第二轮通过，G-01~G-15 修复）
  - [2026-08-07-us014-embedding-acceptance.md](docs/reports/2026-08-07-us014-embedding-acceptance.md) —— US-014 端侧嵌入引擎验收测试（ac-verifier，通过，5/5 AC）
  - [2026-08-07-us015-data-archaeology.md](docs/reports/2026-08-07-us015-data-archaeology.md) —— US-015 知识库分库数据模型源码考古（code-archaeologist，简化版）
  - [2026-08-07-us015-knowledgebase-model-guardrail.md](docs/reports/2026-08-07-us015-knowledgebase-model-guardrail.md) —— US-015 知识库分库数据模型安全与质量审计（guardrail-enforcer，有条件通过，G-01 HNSW Query.remove 已知 bug 风险）
  - [2026-08-07-us015-knowledgebase-model-guardrail-round2.md](docs/reports/2026-08-07-us015-knowledgebase-model-guardrail-round2.md) —— US-015 知识库分库数据模型修复复审（guardrail-enforcer，第二轮通过，G-01~G-05 修复）
  - [2026-08-07-us015-knowledgebase-model-acceptance.md](docs/reports/2026-08-07-us015-knowledgebase-model-acceptance.md) —— US-015 知识库分库数据模型验收测试（ac-verifier，通过，5/5 AC，31 单元测试 + 全量 410 回归 0 失败）
  - [性能基线](docs/reports/perf/) —— 性能基线报告目录
    - [2026-08-02-us002-objectbox-crud-baseline.md](docs/reports/perf/2026-08-02-us002-objectbox-crud-baseline.md) —— US-002 ObjectBox CRUD 性能基线
    - [2026-08-02-us003-apikey-baseline.md](docs/reports/perf/2026-08-02-us003-apikey-baseline.md) —— US-003 API Key 加密存储性能基线
    - [2026-08-02-us004-provider-config-baseline.md](docs/reports/perf/2026-08-02-us004-provider-config-baseline.md) —— US-004 Provider 配置数据模型性能基线
    - [2026-08-07-us014-embedding-baseline.md](docs/reports/perf/2026-08-07-us014-embedding-baseline.md) —— US-014 端侧嵌入引擎性能基线（初版，JVM）

### 运维

- [docs/behavioral-rules.md](docs/behavioral-rules.md) —— 行为规则动态累积层
- `docs/runbooks/` —— 运维知识库（按需建立）

## 工作准则

- [CLAUDE.md](CLAUDE.md) —— AI 编程行为最高准则（必读）
