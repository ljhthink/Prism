# ADR-015: M5 三层记忆系统架构（US-005）

> 从 `docs/templates/adr-template.md` 复制新建，依 CLAUDE.md 第十七节。
> 本 ADR 记录 M5「三层记忆系统」整体架构决策：L1 会话内滑动窗口+摘要压缩、L2 跨会话向量化检索+防污染、L3 用户画像偏好抽取、记忆管理 UI。

| 项目 | 内容 |
| --- | --- |
| 状态 | Proposed |
| 日期 | 2026-08-10 |
| 决策者 | 主 Agent（基于 code-archaeologist 考古报告 TKN-M5-ARCH-001 + web-access 调研 mem0/CALMem/Agent-Memory + 用户需求 PRD US-005） |
| 关联文档 | [ADR-007](ADR-007-m3-document-ingestion.md) / [ADR-009](ADR-009-m3-ingestion-pipeline.md) / [ADR-010](ADR-010-m3-vector-retrieval.md) / [ADR-012](ADR-012-m3-rag-conversation-integration.md) / [PRD.md](../PRD.md) US-005 / [prd.json](../../prd.json) US-030~US-036 |
| 上游调研 | [M5 记忆系统基建考古](../reports/2026-08-10-m5-archaeology.md) / web-access 调研（mem0 / CALMem arXiv:2605.20724 / Agent-Memory / Oracle AI Memory） |
| 风险等级 | P2 跨模块（复用 M3 基建 + 扩展 ConversationViewModel 上下文注入 + OpenAICompatibleProvider 非流式扩展） |

## 背景（Context）

PRD US-005 要求：App 记住用户偏好和跨会话历史，越用越懂我，且不让旧上下文污染新对话。验收标准包括：
- L1 会话内：滑动窗口 + 每 N 轮摘要压缩（可配置 N，默认 10）
- L2 跨会话：对话历史向量化存入 ObjectBox，新会话按当前话题 top-k 检索（默认 k=3）
- L3 用户画像：显式偏好（用户设定）+ 隐式偏好（从对话抽取），结构化存储
- 防污染：新会话不自动加载旧会话全文，仅加载检索结果 + 画像
- 用户可查看/编辑/删除记忆，可一键清除

业界调研（mem0/CALMem/Agent-Memory）证实三层记忆架构是业界共识：
- mem0：Conversation → Session → User → Org 四层
- CALMem（arXiv:2605.20724）：Episodic Memory（滑动窗口向量）+ Semantic Memory（结构化事实）
- Agent-Memory：STM（FIFO）→ MTM（主题分段）→ LTM（画像+知识）

M3 已建立可复用的向量搜索基建：Embedder（all-MiniLM-L6-v2，384 维）+ ObjectBox HNSW 向量索引 + KnowledgeBaseRepository.search。

## 决策（Decision）

采用三层记忆架构，复用 M3 向量基建，扩展 OpenAICompatibleProvider 支持非流式请求：

1. **L1 会话内记忆**：SlidingWindowMemoryManager（滑动窗口 N 轮 + 摘要压缩），ConversationSummarizer 使用 LLM 非流式请求生成摘要
2. **L2 跨会话记忆**：CrossSessionMemoryManager（对话结束向量化存储 + 新会话 top-k 检索），复用 Embedder + ObjectBox HNSW，**防污染：仅注入检索结果，不加载旧会话全文**
3. **L3 用户画像**：UserProfileManager（显式偏好 UI 设定 + 隐式偏好 LLM 抽取），结构化存储到 ObjectBox
4. **上下文注入**：ConversationViewModel 扩展 systemPrompt 合并顺序：base → RAG context → L1 摘要 → L2 跨会话记忆 → L3 用户画像 → Skill systemPrompt
5. **OpenAICompatibleProvider 扩展**：新增 `chatCompletion`（stream=false）非流式方法，供 ConversationSummarizer 和 UserProfileManager 使用

选择此方案因为：复用 M3 基建（零新依赖）+ 业界验证的三层架构 + 防污染是核心设计约束。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 纯滑动窗口（无 L2/L3） | 实现最简单 | 不满足 PRD 跨会话记忆和用户画像需求 |
| 引入 mem0 SDK | 成熟方案 | Python 生态，无 Android/Kotlin SDK，不可直接复用 |
| 自建独立向量存储 | 解耦 | 重复造轮子，M3 ObjectBox HNSW 已验证可用 |
| 全文加载旧会话 | 上下文完整 | 违反防污染要求，token 消耗爆炸 |

## 后果（Consequences）

- 正面后果：
  - 复用 M3 基建，零新依赖
  - 三层架构业界验证，可扩展
  - 防污染机制保证新会话上下文干净
  - 用户对记忆有完全控制权（GDPR 式）
- 负面后果 / 代价：
  - OpenAICompatibleProvider 需扩展非流式请求（H-1 阻塞）
  - ConversationViewModel 上下文注入链路变复杂（6 层合并）
  - Embedder 串行锁瓶颈（H-2，RAG + 记忆向量化并发时串行等待）
  - 摘要压缩和偏好抽取消耗额外 LLM 调用（成本）
- 需要同步更新的文档或代码：
  - prd.json US-030~US-036
  - README.md 文档索引
  - ConversationViewModel（上下文注入扩展）
  - OpenAICompatibleProvider（非流式扩展）
  - PrismApplication（DI 扩展）
  - objectbox default.json（新实体 schema）

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| H-1：OpenAICompatibleProvider 无非流式请求 | 高 | US-032 优先实现非流式 chatCompletion 扩展 |
| H-2：Embedder 串行锁瓶颈 | 中 | 向量化操作在 IO 协程执行，不阻塞 UI；未来可考虑 Embedder 实例池 |
| H-4：ObjectBox HNSW 删除 bug #1209 | 中 | MemoryRepository 删除用 findIds + Box.remove 模式（考古报告 R-15） |
| 摘要/抽取消耗 LLM 调用 | 中 | 失败降级为跳过/截断，不阻断对话 |
| 上下文注入顺序错误 | 中 | 集成测试验证合并顺序（US-035 AC-5） |

## 参考

- [mem0 Memory Types](https://docs.mem0.ai/core-concepts/memory-types)
- [CALMem: Application-Layer Dual Memory](https://arxiv.org/html/2605.20724v1)
- [Agent-Memory (MemoryOS 复现)](https://github.com/77z-zhou/Agent-Memory)
- [Oracle: Agent Memory for Long Conversations](https://blogs.oracle.com/developers/which-agent-memory-approach-is-best-for-long-conversations)
- [M5 记忆系统基建考古](../reports/2026-08-10-m5-archaeology.md)
