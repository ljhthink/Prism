# ADR-012: M3 RAG 对话集成架构（US-019）

> 从 `docs/templates/adr-template.md` 复制新建，依 CLAUDE.md 第十七节。
> 本 ADR 记录 M3「个人知识库 RAG」US-019 RAG 对话集成的架构决策：RAG 注入点、检索触发策略、引用标注方案、分库检索 UI、降级策略、性能考量。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-07 |
| 决策者 | 主 Agent（基于 code-archaeologist 考古报告 + web-access 调研 + sequential-thinking 推演） |
| 关联文档 | [ADR-007](ADR-007-m3-rag-tech-stack.md) / [ADR-008](ADR-008-m3-knowledgebase-model.md) / [ADR-010](ADR-010-m3-vector-retrieval.md) / [ADR-011](ADR-011-m3-knowledgebase-ui.md) / [PRD.md](../PRD.md) US-003 / [prd.json](../../prd.json) US-019 |
| 上游调研 | [US-019 RAG 对话集成源码考古报告](../reports/2026-08-07-us019-rag-integration-archaeology.md) |
| 风险等级 | P2 跨模块（改造 ConversationViewModel + ConversationScreen + 扩展 ChatStreamProvider 接口 + 新增 RAG 注入器） |
| 审查闭环 | guardrail-enforcer 两轮（TKN-US019-RAG-GUARDRAIL-001 有条件通过 1 HIGH+4 MEDIUM → TKN-US019-RAG-GUARDRAIL-002 通过）+ ac-verifier（TKN-US019-RAG-ACCEPTANCE-001，5/6 AC 完全通过，AC-2 UI 入口为已知 GAP 不阻断） |

## 背景（Context）

PRD US-019 要求：对话时检索 top-k 片段注入 prompt、可指定库或全库检索、AI 回答标注引用来源（文件名+片段位置）、无引用时主动说明。这是 M3 RAG 收尾故事，需把 US-017 向量检索能力注入 US-005/006/007 对话流。

考古报告揭示五项核心未决：

1. **R-3 [高] ChatStreamProvider 接口无 system prompt 注入点**：[ChatStreamProvider.kt:23](../../app/src/main/java/io/prism/provider/ChatStreamProvider.kt) `streamChat(config, messages)` 无 systemPrompt 参数；[OpenAICompatibleProvider.kt:222](../../app/src/main/java/io/prism/provider/OpenAICompatibleProvider.kt) `Role.toRequestRole` 只映射 user/assistant，**Role 枚举无 SYSTEM**（[ChatMessage.kt:4](../../app/src/main/java/io/prism/chat/ChatMessage.kt)）。RAG context 须作为 system 消息注入，当前接口不支持。

2. **R-4 [中] ChatMessage.source 单字段无法承载多引用来源**：[ChatMessage.kt:22-23](../../app/src/main/java/io/prism/chat/ChatMessage.kt) `source: String?` 单字段；[ConversationScreen.kt:291](../../app/src/main/java/io/prism/ui/conversation/ConversationScreen.kt) `SourceChip` 渲染单个 String。RAG top-k 检索返回多条 RetrievalResult，需展示多个引用。

3. **R-5 [中] appendDelta 非原子 RMW 违反 BR-concurrency-004**：[ConversationViewModel.kt:113](../../app/src/main/java/io/prism/ui/conversation/ConversationViewModel.kt) `_messages.value = _messages.value.map { ... }`（非原子读-改-写）。RAG 注入后若引入检索协程并发写 `_messages`，会触发 lost update。

4. **R-6 [中] search 同步阻塞 + 无相似度阈值过滤**：[KnowledgeBaseRepository.kt:249](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) `search` 同步方法；:229-232 注释明确阈值过滤由调用方决定。若不设阈值，无关检索结果会污染 RAG context。

5. **R-7 [中] 误导性 UI 文案与未实现的 @知识库 语法**：[ConversationScreen.kt:355](../../app/src/main/java/io/prism/ui/conversation/ConversationScreen.kt) "正在调用 MCP 检索知识库…" 硬编码（实际无 MCP 检索）；:388 输入框占位符 "@知识库 检索…" 未实现。

web-access 调研结论（5 个主题）：

- **RAG prompt 模板**：BuildRag Canonical Template — system prompt 短而严格（grounding/fallback/citation rules），context 放 user message，每个 chunk 标注 `chunk_id` + `source`
- **引用标注**：inline citation（生成时让 LLM 嵌入 `[来源N]`）优于 post-hoc（后处理匹配）；citation-shaped hallucination 需两层策略
- **OpenAI system message**：Chat Completions 不持久化状态，每次重发 system + context(user) + history + query
- **检索触发**：always-on / on-demand / adaptive 三种；4GB 低端机端侧嵌入成本考虑，用户手动开关 + 默认开启最合适
- **Android RAG**：端侧嵌入 + 云端 LLM 混合架构，本地检索 3-5 chunks 即可

## 决策（Decision）

### 5.1 RAG 注入点：新增 RagContextBuilder，在 ConversationViewModel.sendMessage() 前拦截

**理由**：

- sendMessage 是消息发送唯一入口，RAG 检索须在此触发
- 新建独立组件 RagContextBuilder 职责单一（embed → search → prompt 拼接），便于测试与复用
- 不污染 Provider 层，RAG 是 ViewModel 层编排逻辑

**流程**：

```text
用户发送消息
    │
    ▼
ConversationViewModel.sendMessage(text)
    │
    ▼
if (ragEnabled) {
    withContext(Dispatchers.IO) {
        val queryVector = embedder.embed(text)           // ~100ms 串行持锁
        val results = repository.search(queryVector, k=3, kbId)
            .filter { it.similarity >= 0.3f }            // 相似度阈值过滤（R-6）
        val ragContext = RagContextBuilder.build(results) // 拼 context block
        val systemPrompt = RagContextBuilder.systemPrompt() // RAG grounding rules
        provider.streamChat(active, history, systemPrompt, ragContext)
    }
} else {
    provider.streamChat(active, history)                  // 普通对话
}
```

### 5.2 检索触发策略：用户手动开关 + always-on + 默认开启

**理由**：

- 4GB 低端机端侧嵌入 embed(query) p99 短文本 2ms（US-014 基线），检索 search p50<200us（US-017 基线），总成本<5ms 可接受
- 意图识别需额外 LLM 调用，成本高且增加延迟，不适合端侧
- 用户可在对话页切换 RAG 开关（三态：全库 / 指定库 / 关闭）
- 开关状态持久化到 DataStore

**三态选项**：

- **全库检索**（默认）：`kbId=null`，跨所有库检索
- **指定库**：`kbId=具体库id`，单选；库列表从 KnowledgeBaseRepository.knowledgeBases 取
- **关闭 RAG**：不检索，普通对话

### 5.3 引用标注方案：inline citation + generation prompt 强制 + SourceCard 列表 UI

**prompt 模板**（基于 BuildRag Canonical Template，适配中文）：

```text
system prompt:
你是 Prism AI 助手。当提供【知识库片段】时，请遵循：
1. 优先使用【知识库片段】中的信息回答问题
2. 引用知识库片段时使用 [来源N] 格式，N 为片段编号
3. 若知识库片段未提供答案，明确说明「知识库中未找到相关内容，以下回答基于模型自身知识」
4. 不捏造来源，不引用未提供的片段编号

user message:
【知识库片段】
[来源1] 文件=文档A.pdf 片段=1
内容...

[来源2] 文件=文档B.md 片段=3
内容...
【END 知识库片段】

{用户原始问题}
```

**引用来源 UI**：

- 扩展 ChatMessage：`source: String?` → `sources: List<Citation>`，Citation 数据类含 `index: Int` / `documentTitle: String` / `chunkIndex: Int` / `similarity: Float`
- SourceChip 改为渲染列表：每个 Citation 一个胶囊，显示「文档A #1」
- 引用来源在流式开始前就确定（检索阶段），附在 AI 占位消息上，无需等待 Done

**不做 post-hoc 解析**：增加复杂度，端侧不值；依赖 AI 遵循 prompt 指令标注 `[来源N]`

### 5.4 ChatStreamProvider 接口扩展：新增 systemPrompt 参数

**方案选择**：扩展 ChatStreamProvider 接口加 systemPrompt 参数（方案 C，依赖倒置），而非扩展 Role 枚举（方案 A）

**理由**：

- 方案 C 更通用，符合依赖倒置原则，所有 Provider 实现均可受益
- 方案 A 需在 ViewModel 构造 history 时前置 system ChatMessage，污染消息历史
- system prompt 是稳定规则，与可变 history 分离更清晰

**接口变更**：

```kotlin
// ChatStreamProvider.kt
interface ChatStreamProvider {
    fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,  // 新增，可选
        ragContext: String? = null     // 新增，可选，作为 user message 前置
    ): Flow<StreamEvent>
}
```

**OpenAICompatibleProvider.buildRequestBody 改造**：

- 若 systemPrompt 非空，在 messages 列表最前插入 `MessageBody("system", systemPrompt)`
- 若 ragContext 非空，在最后一条 user message 前插入 `MessageBody("user", ragContext)`

### 5.5 降级策略：三级降级，RAG 是增强不是依赖

| 失败场景 | 降级行为 | 用户感知 |
|---|---|---|
| embed(query) 失败 | 跳过检索，普通对话 | Toast「查询嵌入失败，本次未检索知识库」 |
| search 失败或空结果 | 普通对话，system prompt 不注入 context | AI 自然回答，无引用来源 |
| 整个 RAG 注入异常 | try-catch 兜底，退化为普通对话 | 日志记录 simpleName，用户无感 |

**核心原则**：RAG 失败不影响基础对话，用户始终能得到 AI 回复

### 5.6 性能考量：IO 线程 + top-k=3 + 检索中状态

| 项 | 决策 | 依据 |
|---|---|---|
| 检索线程 | `withContext(Dispatchers.IO)` | OnnxEmbedder 全程持锁（BR-concurrency-002），search 同步阻塞，禁止 Main |
| top-k | k=3（4GB 低端机，ADR-007） | 端侧资源约束，3 片段足够提供 context 又不超 token 预算 |
| 相似度阈值 | similarity >= 0.3f | 过滤无关结果污染 context；阈值可配置 |
| 检索中状态 | UI 显示「检索知识库中…」TypingIndicator | embed ~100ms + search <1ms，用户可感知 |
| 锁竞争 | RAG 检索与摄入管线共享 OnnxEmbedder 单例 | 若同时运行，embed 排队 ~100ms/次，可接受 |

### 5.7 状态原子性：_messages 改用 update CAS（修复 R-5）

**问题**：[ConversationViewModel.kt:113](../../app/src/main/java/io/prism/ui/conversation/ConversationViewModel.kt) `_messages.value = _messages.value.map { ... }` 非原子 RMW，违反 BR-concurrency-004

**修复**：改用 `_messages.update { it.map { ... } }` 原子 CAS，与 KnowledgeBaseViewModel 一致（ADR-011 G-01 修复模式）

### 5.8 UI 文案修正：移除误导性占位符（修复 R-7）

| 位置 | 现状 | 修正 |
|---|---|---|
| ConversationScreen.kt:355 TypingIndicator | "正在调用 MCP 检索知识库…" | "正在思考…"（普通）/ "正在检索知识库…"（RAG 开启时） |
| ConversationScreen.kt:388 输入框占位符 | "输入问题，@知识库 检索…" | "输入问题…"（移除未实现的 @知识库 语法） |
| 新增 RAG 开关 | 无 | 对话页顶部 RAG 模式切换器（全库/指定库/关闭） |

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| **A: 扩展 Role 枚举加 SYSTEM，ViewModel 注入** | 改动小，不破坏接口 | 污染消息历史，system 消息混在 history 中；多 Provider 时需各自处理 |
| **B: 在 Provider.buildRequestBody 内部注入** | Provider 自治 | RAG 逻辑散落 Provider 层，违反单一职责；不同 Provider 重复实现 |
| **C: 扩展 ChatStreamProvider 接口加 systemPrompt 参数（选定）** | 依赖倒置，通用清晰；ViewModel 编排，Provider 执行 | 接口变更需所有实现适配（当前仅 OpenAICompatibleProvider） |
| **检索触发：意图识别 on-demand** | 节省无谓检索 | 需额外 LLM 调用，成本高延迟大，不适合端侧 |
| **检索触发：Agentic RAG adaptive** | 智能动态 | 实现复杂，端侧算力不足 |
| **引用标注：post-hoc 解析** | 不依赖 AI 遵循指令 | 增加复杂度，匹配准确率低 |
| **引用标注：结构化输出（JSON）** | 精确解析 | 多数 OpenAI 兼容端点不保证结构化输出；限制 AI 自然表达 |

## 后果（Consequences）

- **正面后果**：
  - M3 RAG 闭环完成，对话可检索知识库并标注引用来源
  - ChatStreamProvider 接口扩展支持 system prompt，未来 MCP 工具调用等场景可复用
  - ChatMessage 扩展为多引用来源，UI 更丰富
  - 状态原子性修复，消除并发写隐患

- **负面后果 / 代价**：
  - ChatStreamProvider 接口变更，需更新所有实现（当前仅 OpenAICompatibleProvider）
  - RAG 检索增加 ~100ms 延迟（embed 串行持锁），用户可感知
  - system prompt 注入增加 token 消耗（每轮多 ~200 token）
  - 依赖 AI 遵循 prompt 指令标注引用，存在 citation-shaped hallucination 风险

- **需要同步更新的文档或代码**：
  - ChatStreamProvider 接口 + OpenAICompatibleProvider 实现
  - ConversationViewModel（新增 RAG 注入逻辑 + 依赖）
  - ConversationScreen（RAG 开关 UI + SourceChip 列表 + 文案修正）
  - ChatMessage（source → sources: List<Citation>）
  - PrismApplication（ConversationViewModel.Factory 新增 embedder + knowledgeBaseRepository 注入）
  - 新增 RagContextBuilder 组件
  - 新增 RAG 配置持久化（DataStore）

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| OnnxEmbedder 串行持锁阻塞 UI 协程（R-1） | 高 | RAG 检索全链路在 Dispatchers.IO 协程执行，UI 显示检索中状态 |
| RAG 检索与摄入管线锁竞争（R-2） | 高 | embed 排队 ~100ms/次可接受；UI 提示检索中；未来可评估检索时暂停摄入 |
| ChatStreamProvider 接口变更影响既有调用（R-3） | 高 | systemPrompt/ragContext 参数设默认值 null，既有调用零改动 |
| ChatMessage 结构变更影响序列化（R-4） | 中 | source → sources 是破坏性变更，但 ChatMessage 仅内存使用无持久化，影响可控 |
| Citation-shaped hallucination（AI 伪造引用） | 中 | prompt 强制规则 + 用户可点击引用来源核对；不做 post-hoc 解析（端侧成本不值） |
| 相似度阈值 0.3 过滤导致空结果（R-6） | 中 | 空结果时降级为普通对话，AI 自然回答无引用 |
| 误导性 UI 文案残留（R-7） | 低 | 编码时统一修正，guardrail 审查覆盖 |

## 参考

- [US-019 RAG 对话集成源码考古报告](../reports/2026-08-07-us019-rag-integration-archaeology.md)
- [BuildRag Prompt Engineering](https://buildrag.com/tutorials/rag-components/prompt-engineering/)
- [How to Add Citations to RAG Answers](https://ai-tldr.dev/learn/rag/advanced-rag/add-citations-to-rag/)
- [OpenAI Chat Completions multi-turn RAG](https://community.openai.com/t/how-to-structure-system-prompt-rag-context-and-user-input-for-multi-turn-rag-based-chatbots-using-openai-chat-completions/1292995)
- [RAG on Android: Local Vector Cache + Cloud Retrieval](https://dzone.com/articles/rag-android-local-cache-cloud-retrieval)
- [ADR-007 M3 RAG 技术栈](ADR-007-m3-rag-tech-stack.md)
- [ADR-010 M3 向量检索](ADR-010-m3-vector-retrieval.md)
- [ADR-011 M3 知识库管理 UI](ADR-011-m3-knowledgebase-ui.md)
- [behavioral-rules.md](../behavioral-rules.md) BR-concurrency-002 / BR-concurrency-004
