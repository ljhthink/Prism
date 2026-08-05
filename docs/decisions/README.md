# 架构决策记录（ADR）索引

> 本目录存放 Prism 项目的架构决策记录。依 CLAUDE.md 第十七节管理。

## ADR 列表

| 编号 | 标题 | 状态 | 日期 | 文件 |
|---|---|---|---|---|
| ADR-001 | Prism 技术栈与架构选型 | Proposed | 2026-08-02 | [ADR-001-prism-tech-stack.md](ADR-001-prism-tech-stack.md) |
| ADR-002 | Prism 聊天 UI 架构（US-005） | Proposed | 2026-08-05 | [ADR-002-prism-chat-ui-architecture.md](ADR-002-prism-chat-ui-architecture.md) |
| ADR-003 | Provider 配置详情页接入（设置模块） | Proposed | 2026-08-05 | [ADR-003-prism-provider-config-settings.md](ADR-003-prism-provider-config-settings.md) |
| ADR-004 | OpenAI 兼容 Provider 流式请求（US-006/US-007） | Accepted | 2026-08-05 | [ADR-004-prism-provider-streaming.md](ADR-004-prism-provider-streaming.md) |
| ADR-005 | MCP Kotlin SDK Client 集成（US-008） | Accepted | 2026-08-06 | [ADR-005-mcp-client-integration.md](ADR-005-mcp-client-integration.md) |

## ADR 生命周期

```
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
