# 架构决策记录（ADR）索引

> 本目录存放 Prism 项目的架构决策记录。依 CLAUDE.md 第十七节管理。

## ADR 列表

| 编号 | 标题 | 状态 | 日期 | 文件 |
|---|---|---|---|---|
| ADR-001 | Prism 技术栈与架构选型 | Accepted | 2026-08-02 | [ADR-001-prism-tech-stack.md](ADR-001-prism-tech-stack.md) |
| ADR-002 | Prism 聊天 UI 架构（US-005） | Proposed | 2026-08-05 | [ADR-002-prism-chat-ui-architecture.md](ADR-002-prism-chat-ui-architecture.md) |
| ADR-003 | Provider 配置详情页接入（设置模块） | Accepted | 2026-08-05 | [ADR-003-prism-provider-config-settings.md](ADR-003-prism-provider-config-settings.md) |
| ADR-004 | OpenAI 兼容 Provider 流式请求（US-006/US-007） | Accepted | 2026-08-05 | [ADR-004-prism-provider-streaming.md](ADR-004-prism-provider-streaming.md) |
| ADR-005 | MCP Kotlin SDK Client 集成（US-008） | Accepted | 2026-08-06 | [ADR-005-mcp-client-integration.md](ADR-005-mcp-client-integration.md) |
| ADR-006 | 内置 Filesystem MCP Server（US-009） | Accepted | 2026-08-06 | [ADR-006-filesystem-mcp-server.md](ADR-006-filesystem-mcp-server.md) |
| ADR-007 | M3 个人知识库 RAG 技术栈（US-003） | Accepted | 2026-08-06 | [ADR-007-m3-rag-tech-stack.md](ADR-007-m3-rag-tech-stack.md) |
| ADR-008 | M3 知识库分库数据模型（US-015） | Accepted | 2026-08-07 | [ADR-008-m3-knowledgebase-model.md](ADR-008-m3-knowledgebase-model.md) |
| ADR-009 | M3 摄入管线编排（US-016） | Accepted | 2026-08-07 | [ADR-009-m3-ingestion-pipeline.md](ADR-009-m3-ingestion-pipeline.md) |
| ADR-010 | M3 向量检索（US-017） | Accepted | 2026-08-07 | [ADR-010-m3-vector-retrieval.md](ADR-010-m3-vector-retrieval.md) |
| ADR-011 | M3 知识库管理 UI 架构（US-018） | Accepted | 2026-08-07 | [ADR-011-m3-knowledgebase-ui.md](ADR-011-m3-knowledgebase-ui.md) |
| ADR-012 | M3 RAG 对话集成架构（US-019） | Accepted | 2026-08-07 | [ADR-012-m3-rag-conversation-integration.md](ADR-012-m3-rag-conversation-integration.md) |
| ADR-013 | M4 Skills 系统架构（US-004） | Accepted | 2026-08-09 | [ADR-013-m4-skills-system-architecture.md](ADR-013-m4-skills-system-architecture.md) |
| ADR-014 | M4 LLM tool_calling 接口扩展（US-023~US-025） | Accepted | 2026-08-09 | [ADR-014-m4-toolcalling-interface.md](ADR-014-m4-toolcalling-interface.md) |
| ADR-015 | M5 三层记忆系统架构（US-005） | Accepted | 2026-08-10 | [ADR-015-m5-memory-system-architecture.md](ADR-015-m5-memory-system-architecture.md) |
| ADR-016 | M6 跨 App 调用架构（US-037） | Accepted | 2026-08-11 | [ADR-016-m6-cross-app-integration.md](ADR-016-m6-cross-app-integration.md) |
| ADR-017 | M7 设备适配与降级架构（US-007） | Accepted | 2026-08-11 | [ADR-017-m7-device-adaptation.md](ADR-017-m7-device-adaptation.md) |
| ADR-018 | M8 集成与发布架构（US-044~US-047） | Accepted | 2026-08-11 | [ADR-018-m8-release-architecture.md](ADR-018-m8-release-architecture.md) |

## ADR 生命周期

```text
Proposed → Accepted → Deprecated / Superseded
```

- **Proposed**：已提交，尚未评审通过
- **Accepted**：经 guardrail-enforcer 审查并随 PR 合并后成为规范
- **Deprecated**：决策已失效，保留以供历史参考
- **Superseded**：被新 ADR 取代，旧 ADR 必须链接到新 ADR

## 触发条件（CLAUDE.md 第十七节）

出现以下任一情况时必须新建 ADR：

1. 引入新的第三方库、框架、中间件或云服务
2. 修改现有架构的模块划分或核心接口
3. 变更 DevOps、CI/CD、部署或监控方案
4. 变更安全策略、认证授权机制或数据处理方式
5. 变更文档治理规则、版本管理策略或子 Agent 分工
6. 选择一种实现方案而明确排除其他可行方案
7. 任何可能对其他模块产生长期影响的决策
