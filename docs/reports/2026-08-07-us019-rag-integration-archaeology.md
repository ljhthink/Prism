# 源码考古报告：US-019 实现 RAG 对话集成

> 从 `docs/templates/reports/archaeology-template.md` 复制新建，依 CLAUDE.md 第 3.1 节简化版。
> 由 code-archaeologist 子 Agent 生成，为 US-019「实现 RAG 对话集成」提供接口契约与风险清单。

| 项目 | 内容 |
|---|---|
| 执行 Agent | code-archaeologist |
| 任务令牌 | TKN-US019-ARCH-001 |
| 考古日期 | 2026-08-07 |
| 考古目标 | US-019 RAG 对话集成（对话流 / Provider / 检索嵌入 / DI / 知识库 UI 五模块） |
| 考古模式 | 简化版考古（模块职责 / 接口契约 / 依赖图 / 关键问题答复 / 风险清单 / 入门路径） |
| 风险等级 | P2 跨模块（改造 ConversationViewModel + ChatStreamProvider + ChatMessage + ConversationScreen，跨对话流/检索嵌入两域） |
| 关联 ADR | [ADR-002](../decisions/ADR-002-prism-chat-ui-architecture.md) / [ADR-004](../decisions/ADR-004-prism-provider-streaming.md) / [ADR-007](../decisions/ADR-007-m3-rag-tech-stack.md) / [ADR-010](../decisions/ADR-010-m3-vector-retrieval.md) / [ADR-011](../decisions/ADR-011-m3-knowledgebase-ui.md) |
| 关联规则 | BR-concurrency-002（OnnxEmbedder 全程持锁）/ BR-concurrency-004（StateFlow 原子 CAS）/ BR-interface-003（过滤空占位消息）/ BR-error-handling-004（catch 不静默吞） |
| 上游考古 | [US-017 检索考古](./2026-08-07-us017-retrieval-archaeology.md)（search 接口已稳定实现）/ [US-018 KB UI 考古](./2026-08-07-us018-kb-ui-archaeology.md) |
| 技术栈 | Kotlin 2.3.21、Jetpack Compose、ObjectBox 5.4.2（HNSW）、onnxruntime 1.27.0、Ktor SSE |

---

## 0. 路径校准说明

任务令牌中部分路径与实际代码结构有出入，本报告一律以实际路径为准：

| 任务令牌描述 | 实际路径 |
|---|---|
| `io/prism/ui/conversation/ConversationViewModel.kt` | `io/prism/ui/chat/ConversationViewModel.kt` |
| `io/prism/ui/conversation/ConversationScreen.kt` | `io/prism/ui/chat/ConversationScreen.kt` |
| `io/prism/chat/`（如存在） | 不存在；消息数据类位于 `io/prism/ui/model/ChatMessage.kt` |
| `io/prism/provider/OpenAICompatibleProvider.kt` | `io/prism/network/OpenAICompatibleProvider.kt` |
| RetrievalResult 含 `startOffset/endOffset` | **不存在**；实际字段为 `documentTitle/chunkIndex`（见 §1.3） |

---

## 1. 模块职责与接口契约

### 1.1 对话流模块（核心改造目标）

#### ConversationViewModel

- 路径：`../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt`
- 职责：管理消息列表 StateFlow、发送消息、Provider 切换、流式 token 收集
- 当前依赖（构造注入，`:37-40`）：`ProviderConfigRepository` + `ChatStreamProvider`（接口）
- **未依赖** `Embedder` / `KnowledgeBaseRepository`（US-019 须新增）

关键接口契约：

| 成员 | 签名 | 行号 | 说明 |
|---|---|---|---|
| `messages` | `StateFlow<List<ChatMessage>>` | `:46` | 消息列表（只读） |
| `isTyping` | `StateFlow<Boolean>` | `:50` | AI 回复中标志 |
| `activeProvider` | `StateFlow<ProviderConfig?>` | `:53` | 当前激活 Provider |
| `providers` | `StateFlow<List<ProviderConfig>>` | `:57` | 全部已配置 Provider |
| `setActiveProvider` | `(id: Long) -> Unit` | `:61` | 切换 Provider |
| `sendMessage` | `(text: String) -> Unit` | `:73` | 发送消息入口（**RAG 注入点**） |
| `appendDelta` | `(aiId: Long, delta: String) -> Unit` | `:112` | 增量追加（**非原子 RMW**，见 R-5） |
| `Factory` | `ViewModelProvider.Factory` | `:120` | DI 入口（**须扩展**） |

`sendMessage` 当前流程（`:73-109`）：

```text
trim 输入 → 追加 USER 消息 → 追加空 AI 占位 → isTyping=true
  → 取 active Provider
  → history = _messages.value.filterNot { 空 AI 消息 }（:94，BR-interface-003）
  → provider.streamChat(active, history)（:95）
  → collect: Delta→appendDelta / Done→isTyping=false / Error→appendDelta(⚠️)+isTyping=false
```

#### ConversationScreen

- 路径：`../../app/src/main/java/io/prism/ui/chat/ConversationScreen.kt`
- 职责：消息渲染、输入框、Provider 选择器
- 关键组件：

| 组件 | 行号 | 说明 |
|---|---|---|
| `ConversationScreen` | `:88` | 顶层 Composable |
| `ProviderChip` | `:156` | Provider 胶囊（**RAG 库选择器可复用此模式**） |
| `ProviderSelectorSheet` | `:186` | Provider 切换弹层（**RAG 库选择弹层可仿此**） |
| `MessageBubble` | `:239` | 消息气泡（用户渐变 / AI 玻璃卡） |
| `SourceChip` | `:303` | **已存在的引用来源胶囊**，渲染 `message.source: String?`，单 String，`maxLines=1` |
| `TypingIndicator` | `:324` | 打字指示，硬编码文案「正在调用 MCP 检索知识库…」（`:355`，**误导性**） |
| `MessageInputBar` | `:367` | 输入框，占位符「输入问题，@知识库 检索…」（`:388`，**未实现语法**） |

#### ChatMessage 数据类

- 路径：`../../app/src/main/java/io/prism/ui/model/ChatMessage.kt`
- 字段（`:17-24`）：

```kotlin
data class ChatMessage(
    val id: Long,
    val role: Role,              // enum Role { USER, ASSISTANT }（:4，无 SYSTEM）
    val content: String,
    val timestamp: Long,
    val source: String? = null   // 单字段引用来源（US-003 预留），RAG 多来源须扩展
)
```

### 1.2 Provider 流式请求模块（prompt 注入点）

#### ChatStreamProvider 接口

- 路径：`../../app/src/main/java/io/prism/network/ChatStreamProvider.kt`
- 契约（`:15-23`）：

```kotlin
interface ChatStreamProvider {
    fun streamChat(config: ProviderConfig, messages: List<ChatMessage>): Flow<StreamEvent>
}
```

- **无 systemPrompt 参数**。RAG context 注入须扩展此接口或在 ViewModel 层构造 system ChatMessage。

#### OpenAICompatibleProvider

- 路径：`../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt`
- 实现 `ChatStreamProvider`，构造注入 `HttpClient` + `ApiKeyRepository`（`:50-53`）
- `streamChat`（`:65-112`）：SSE 流式请求，`flowOn(Dispatchers.IO)`
- `buildRequestBody`（`:124-132`）：**system prompt 注入缺口**

```kotlin
internal fun buildRequestBody(config: ProviderConfig, messages: List<ChatMessage>): String {
    val requestMessages = messages.map { MessageBody(it.role.toRequestRole(), it.content) }
    // ↑ 直接转换，无前置 system 消息
    ...
}
```

- `Role.toRequestRole()`（`:222`）：`if (this == Role.USER) "user" else "assistant"` —— **无 system 映射**

#### StreamEvent

- 路径：`../../app/src/main/java/io/prism/network/StreamEvent.kt`
- 密封类（`:13-22`）：`Delta(content)` / `Done` / `Error(message)`，`when` 穷尽

#### 请求/响应数据类（private，OpenAICompatibleProvider.kt 内）

| 类 | 行号 | 字段 |
|---|---|---|
| `MessageBody` | `:196` | `role: String, content: String` |
| `ChatCompletionRequest` | `:200` | `model, messages: List<MessageBody>, stream: Boolean` |
| `ChatCompletionChunk` | `:208` | `choices: List<Choice>, usage: JsonElement?` |

### 1.3 检索与嵌入模块（RAG 上游）

#### KnowledgeBaseRepository.search（US-017 已实现，接口稳定）

- 路径：`../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt`
- 契约（`:249-284`）：

```kotlin
fun search(
    query: FloatArray,                      // 384 维向量（非文本！调用方须先 embed）
    k: Int = DEFAULT_SEARCH_K,              // 默认 5
    knowledgeBaseId: Long? = null           // null=全库, 0L=默认库, >0=指定库
): List<RetrievalResult>
```

- **同步阻塞方法**（无 suspend/Flow），ObjectBox 查询在调用线程执行
- 前置校验：`require(query.size == 384)`（`:254`）、`require(k > 0)`（`:258`）、`require(knowledgeBaseId == null || >= 0)`（`:259`）
- **无相似度阈值过滤**（`:229-232` 注释明确）：返回 top-k 不论相似度高低，阈值过滤由 US-019 调用方决定
- HNSW 近似性：可能返回 `results.size < k`（`:234-236`）
- Query 用 `use {}` 关闭（`:269`，BR-concurrency-003）

#### RetrievalResult 数据类

- 路径：`../../app/src/main/java/io/prism/data/RetrievalResult.kt`
- 字段（`:28-36`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `chunkId` | `Long` | KnowledgeChunk id |
| `content` | `String` | 分块原文 |
| `title` | `String` | 原文 `${documentTitle}#${index+1}` |
| `similarity` | `Double` | 相似度 ∈ [-1, 1]（1 - COSINE 距离） |
| `documentTitle` | `String` | 解析自 title 的文档标题 |
| `chunkIndex` | `Int?` | 1-based 分块序号 |
| `knowledgeBaseId` | `Long` | 所属库 id |

- **无 `startOffset/endOffset` 字段**（任务令牌描述有误，见 §0）

#### Embedder 接口

- 路径：`../../app/src/main/java/io/prism/embedding/Embedder.kt`
- 契约（`:22-58`）：

```kotlin
interface Embedder : AutoCloseable {
    fun embed(text: String): FloatArray          // 384 维 L2 归一化，非 nullable
    fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    fun isLoaded(): Boolean
    fun checkAndUnload(maxIdleMs: Long): Boolean
}
```

#### OnnxEmbedder 实现

- 路径：`../../app/src/main/java/io/prism/embedding/OnnxEmbedder.kt`
- `embed`（`:79-125`）：**全程持 `ReentrantLock` 串行化**（`:79` `lock.withLock`，BR-concurrency-002）
- 对短文本（query）无特殊处理：统一走 `tokenizer.encode(text, maxSeqLen=512)`（`:83`）
- 单次 embed ~100ms 量级（`:27` 注释）
- `close` 后 `embed` 抛 `IllegalStateException`（`:80`，BR-error-handling-005）
- 按需加载：首次 embed 触发 `ensureLoadedLocked` 加载 ~23MB 模型（`:163-200`）

### 1.4 依赖注入入口

#### PrismApplication

- 路径：`../../app/src/main/java/io/prism/PrismApplication.kt`
- **已暴露** RAG 所需依赖：

| 依赖 | 行号 | 说明 |
|---|---|---|
| `embedder: Embedder` | `:202-208` | lazy 从 assets 加载 ONNX 模型 + 词表，单例复用 |
| `knowledgeBaseRepository: KnowledgeBaseRepository` | `:176` | lazy，KnowledgeBase CRUD + search |
| `providerConfigRepository` | `:61` | 既有 |
| `openAICompatibleProvider` | `:78` | 既有 |

- `embedder` 注释（`:199-200`）明确：单例化复用避免重复加载；协程取消时单次 embed ~100ms 不可中断
- **ConversationViewModel.Factory（ConversationViewModel.kt:120-128）当前仅注入前两者**，须新增 `embedder` + `knowledgeBaseRepository`

### 1.5 知识库 UI 模块（参考分库选择 UI）

#### KnowledgeBaseViewModel

- 路径：`../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseViewModel.kt`
- `libraries: StateFlow<List<KnowledgeBase>>`（`:153`）：订阅 `repository.knowledgeBases`，按 createdAt 升序，**不含虚拟默认库**
- `chunkCounts: Map<Long, Long>`（`:95`）：各库 chunk 计数
- `defaultKbChunkCount: Long`（`:94`）：默认库 chunk 计数
- 摄入在 `Dispatchers.IO` 协程 collect（`:349`），避免 OnnxEmbedder 阻塞主线程 —— **RAG 检索应借鉴此模式**
- `_uiState.update { it.copy(...) }` 原子 CAS（BR-concurrency-004）

#### KnowledgeBase 数据类

- 路径：`../../app/src/main/java/io/prism/data/KnowledgeBase.kt`
- 字段（`:36-39`）：`id: Long`、`name: String`、`createdAt: Long`
- 默认库 `id=0L` 不持久化（`:22-25`）

---

## 2. 依赖关系图

### 2.1 当前依赖图（RAG 注入前）

```text
ConversationScreen
    └─ ConversationViewModel
         ├─ ProviderConfigRepository（既有）
         └─ ChatStreamProvider（接口）
               └─ OpenAICompatibleProvider（实现）
                    ├─ HttpClient
                    └─ ApiKeyRepository

【孤立】Embedder / KnowledgeBaseRepository 已在 PrismApplication 暴露，
        但未注入 ConversationViewModel
```

### 2.2 RAG 注入后预期依赖图

```mermaid
graph TD
    subgraph 对话流（改造）
        CS[ConversationScreen]
        CVM[ConversationViewModel]
        CSM[ChatMessage<br/>须扩展 sources: List]
    end
    subgraph Provider（须支持 system 注入）
        CSP[ChatStreamProvider 接口<br/>须加 systemPrompt 参数]
        OAP[OpenAICompatibleProvider<br/>buildRequestBody 注入 system]
    end
    subgraph 检索嵌入（复用）
        E[Embedder<br/>embed 串行持锁]
        KBR[KnowledgeBaseRepository<br/>search 同步阻塞]
        RR[RetrievalResult]
    end
    subgraph DI
        PA[PrismApplication<br/>embedder + knowledgeBaseRepository]
    end

    CS --> CVM
    CVM -->|新增依赖| E
    CVM -->|新增依赖| KBR
    CVM --> CSP
    CSP --> OAP
    KBR --> RR
    PA -.注入.-> CVM
```

### 2.3 RAG 请求时序（预期）

```text
用户发送消息
  │
  ▼
ConversationViewModel.sendMessage
  ├─ [新增] 若启用 RAG：
  │    ├─ embedder.embed(queryText)  → FloatArray(384)  [IO 协程, ~100ms 持锁]
  │    ├─ knowledgeBaseRepository.search(vector, k, kbId)  [IO 协程, 同步阻塞]
  │    ├─ 过滤 similarity > threshold（调用方责任）
  │    └─ 拼 system prompt = RAG context + 检索结果
  ├─ 构造 history（含 system ChatMessage 或传 systemPrompt 参数）
  ├─ provider.streamChat(active, history, systemPrompt?)  [Flow<StreamEvent>]
  └─ collect: Delta→appendDelta / Done→[新增] 追加引用来源 + isTyping=false
```

---

## 3. 八个关键问题答复

### Q1：ConversationViewModel 当前如何构造 messages 序列？system prompt 在哪一层注入？

**当前无 system prompt 注入。** 证据：

- `sendMessage`（ConversationViewModel.kt:94）：`val history = _messages.value.filterNot { it.role == Role.ASSISTANT && it.content.isEmpty() }` —— 直接从 `_messages.value` 过滤空 AI 消息，**无 system 消息前置**。
- `sendMessage`（ConversationViewModel.kt:95）：`provider.streamChat(active, history)` —— history 直传 Provider。
- `buildRequestBody`（OpenAICompatibleProvider.kt:125）：`messages.map { MessageBody(it.role.toRequestRole(), it.content) }` —— 直接转换，**无 system 前置**。
- `Role` 枚举（ChatMessage.kt:4）：`enum class Role { USER, ASSISTANT }` —— **无 SYSTEM**。

**RAG context 注入点决策**：三层均无 system 支持，US-019 须选择注入层（见 R-3）。推荐方案 C：扩展 `ChatStreamProvider.streamChat` 加 `systemPrompt: String?` 参数，符合依赖倒置，对所有 Provider 通用。

### Q2：OpenAICompatibleProvider 是否支持 system role 消息？多 system 消息如何处理？

**不支持 system role。** 证据：

- `Role.toRequestRole()`（OpenAICompatibleProvider.kt:222）：`if (this == Role.USER) "user" else "assistant"` —— Role 枚举无 SYSTEM，无法产生 "system"。
- `MessageBody`（OpenAICompatibleProvider.kt:196）：`role: String` 字段理论上可传 "system"，但当前无路径产生。
- 无多 system 消息特殊处理逻辑。

**结论**：OpenAI API 本身支持 system role（甚至多条），但当前代码无此路径。US-019 须扩展 Role 枚举加 SYSTEM（方案 A）或扩展 ChatStreamProvider 接口加 systemPrompt 参数（方案 C）。若用方案 C，`buildRequestBody` 须在 `messages.map` 前前置一个 `MessageBody("system", systemPrompt)`。

### Q3：OnnxEmbedder 是否对短文本（query）嵌入有特殊处理？并发约束是什么？

**无特殊处理。** 证据：

- `embed`（OnnxEmbedder.kt:79-125）：统一走 `tokenizer.encode(text, maxSeqLen=512)`（`:83`），短文本自然处理，与文档 chunk 走同一路径。
- 返回 384 维 L2 归一化向量（`meanPoolAndNormalize`，`:224-263`）。

**并发约束**（BR-concurrency-002）：

- `embed` 全程持 `ReentrantLock`（OnnxEmbedder.kt:79 `lock.withLock`），**串行化**。
- 单次 embed ~100ms 量级（OnnxEmbedder.kt:27 注释）。
- `close` 后 `embed` 抛 `IllegalStateException`（OnnxEmbedder.kt:80）。
- Embedder 接口注释（Embedder.kt:17）声称"可并发调用"，但 OnnxEmbedder 实际串行化 —— 接口契约与实现有偏差，以实现为准。

**结论**：query embedding 必须在 `Dispatchers.IO` 调用，禁止在 Main。RAG 检索与摄入管线（若同时运行）会竞争锁（见 R-2）。

### Q4：KnowledgeBaseRepository.search 的线程模型？是否阻塞？query 参数是文本还是向量？

- **签名**（KnowledgeBaseRepository.kt:249-253）：`fun search(query: FloatArray, k: Int = 5, knowledgeBaseId: Long? = null): List<RetrievalResult>`
- **query 是向量**（`FloatArray` 384 维），不是文本。调用方须先 `embedder.embed(queryText)` 转向量。
- **同步阻塞方法**（无 suspend/Flow），ObjectBox `findWithScores()` 在调用线程执行。
- 维度校验 `require(query.size == 384)`（KnowledgeBaseRepository.kt:254）。
- kbId 三态：`null`=全库，`0L`=默认库，`>0`=指定库（KnowledgeBaseRepository.kt:213-219）。

**结论**：调用方须 (1) 先 `embed(queryText)` 得向量；(2) 在 IO 调度器调用 `search`。两步都阻塞，须在 `Dispatchers.IO` 协程（参考 KnowledgeBaseViewModel.startIngestion:349 模式）。

### Q5：PrismApplication 是否已暴露 OnnxEmbedder？ConversationViewModel 当前依赖哪些组件？

- **已暴露** `embedder: Embedder`（PrismApplication.kt:202-208，lazy 从 assets 加载 ONNX 模型 + 词表，单例）。
- **已暴露** `knowledgeBaseRepository: KnowledgeBaseRepository`（PrismApplication.kt:176）。
- `ConversationViewModel.Factory`（ConversationViewModel.kt:120-128）当前仅注入 `app.providerConfigRepository` + `app.openAICompatibleProvider`。

**结论**：DI 改造范围小 —— 在 `ConversationViewModel` 构造函数新增 `embedder: Embedder` + `knowledgeBaseRepository: KnowledgeBaseRepository`，Factory 从 `app.embedder` / `app.knowledgeBaseRepository` 注入即可。`embedder` 是单例 lazy，复用安全。

### Q6：ConversationScreen 当前消息渲染组件？是否支持富文本/附加元信息展示？

- `MessageBubble`（ConversationScreen.kt:239-299）：用户消息用渐变 Box + Text；AI 消息用 `PrismGlassCard` + Text + `SourceChip`。
- `SourceChip`（ConversationScreen.kt:303-320）：**已存在**，渲染 `message.source: String?`，薄荷色胶囊，`maxLines=1`。调用点（`:291-293`）：`message.source?.let { src -> SourceChip(src, ...) }`。
- **当前只支持单个 source 字符串**，RAG top-k 多来源须扩展。
- 无富文本/Markdown 渲染，AI 消息纯 `Text`。

**结论**：引用来源 UI 已有雏形（SourceChip），但须从单 String 扩展为多来源列表。RAG context 拼接为纯文本即可（无 Markdown 渲染需求）。

### Q7：是否已存在 RAG 相关的半成品代码、TODO、注释？

全局搜索结果（源码 + 测试）：

| 搜索模式 | 结果 |
|---|---|
| `systemPrompt` / `system prompt` | **零匹配**（无 system prompt 代码） |
| `TODO.*RAG` / `TODO.*检索` | **零匹配**（无半成品 TODO） |
| `@知识库` | 仅 ConversationScreen.kt:388 输入框占位符文案（未实现语法） |
| `RAG` | 仅注释提及（Chunker/PrismApplication/RetrievalResult KDoc），无实现代码 |
| `引用来源` | ChatMessage.kt:22（source 字段注释，US-003 预留）、ConversationScreen.kt:236/301/303（SourceChip）、theme（薄荷色功能色） |
| `citation` | 仅 KnowledgeBase.kt:18 注释（"不持久化 citations 统计字段"），无代码 |
| `正在调用 MCP 检索知识库` | ConversationScreen.kt:355 TypingIndicator 硬编码文案（**误导性**，实际无 MCP 检索） |

**结论**：**当前无任何 RAG 集成半成品代码**，仅占位符文案和预留字段（`ChatMessage.source`）。US-019 从零开始集成，无冲突风险。

### Q8：流式响应如何累积？是否能在流式结束后追加「引用来源」区块？

- 流式累积在 `sendMessage` 的 `stream.collect`（ConversationViewModel.kt:98-107）：
  - `Delta` → `appendDelta(aiId, event.content)`（`:100`）
  - `Done` → `_isTyping.value = false`（`:101`）—— **当前仅置标志，无追加引用**
  - `Error` → `appendDelta(aiId, "\n\n⚠️ ${event.message}")` + `_isTyping.value = false`（`:102-105`）
- `appendDelta`（ConversationViewModel.kt:112-116）：`_messages.value = _messages.value.map { if (msg.id == aiId) msg.copy(content = msg.content + delta) else msg }` —— **非原子 RMW**（见 R-5）。

**引用来源追加时机**：

- RetrievalResult 列表在检索阶段（streamChat 前）就已知。
- 可在 `Done` 分支追加：`_messages.value = _messages.value.map { if (msg.id == aiId) msg.copy(sources = retrievalResults) else msg }`。
- 或在检索完成后立即附在 AI 占位消息上（streamChat 前就设 sources）。
- **须扩展 ChatMessage 数据结构**（当前 `source: String?` 单字段，见 R-4）。

**结论**：可在 `Done` 分支追加引用来源，但更合理的是检索阶段就拿到 RetrievalResult，引用来源可立即附在 AI 占位消息上（用户能更早看到引用）或 Done 后追加。须扩展 ChatMessage。

---

## 4. 风险清单

| 风险 | 等级 | 证据 / 说明 | 建议 |
|---|---|---|---|
| **R-1 OnnxEmbedder 串行持锁阻塞 UI 协程** | 高 | OnnxEmbedder.kt:79 `lock.withLock`，BR-concurrency-002。RAG 检索需 `embed(query)` ~100ms + `search` 同步阻塞，若在 Main 协程调用会冻结 UI | RAG 检索全链路（embed + search）在 `Dispatchers.IO` 协程执行，参考 KnowledgeBaseViewModel.kt:349 模式。`sendMessage` 须改为在 IO 协程内完成检索后再 streamChat |
| **R-2 RAG 检索与摄入管线锁竞争** | 高 | OnnxEmbedder 单例（PrismApplication.kt:202），`embed` 全程持锁串行化。若用户在摄入文档时发起 RAG 对话，`embed(query)` 会与摄入的 `embed(chunk)` 排队，~100ms/次 | UI 层提示"检索中"；或检索时暂停摄入；评估最坏延迟（摄入中 RAG 首字延迟 = 排队 chunk 数 × 100ms）。KnowledgeBaseViewModel 已有"摄入中拒绝新任务"约束（:332），可参考做"摄入中 RAG 降级"策略 |
| **R-3 ChatStreamProvider 接口无 system prompt 注入点** | 高 | ChatStreamProvider.kt:23 `streamChat(config, messages)` 无 systemPrompt 参数；OpenAICompatibleProvider.kt:125 `buildRequestBody` 不注入 system；Role 枚举无 SYSTEM（ChatMessage.kt:4）。RAG context 须作为 system 消息注入，当前接口不支持 | 推荐方案 C：扩展 `ChatStreamProvider.streamChat` 加 `systemPrompt: String?` 参数，`buildRequestBody` 在 messages.map 前前置 `MessageBody("system", systemPrompt)`。符合依赖倒置，对所有 Provider 通用。备选方案 A：扩展 Role 加 SYSTEM，在 ViewModel 构造 history 时前置 system ChatMessage（但 toRequestRole 须同步加 SYSTEM 分支）。**须在 ADR 中记录选择** |
| **R-4 ChatMessage.source 单字段无法承载多引用来源** | 中 | ChatMessage.kt:22-23 `source: String?` 单字段；ConversationScreen.kt:291 SourceChip 渲染单个 String。RAG top-k 返回多条 RetrievalResult，需展示多个引用 | 扩展 `ChatMessage` 为 `sources: List<RetrievalResult>`（或 `List<Citation>` 数据类，含 documentTitle/chunkIndex/similarity）。`SourceChip` 改为渲染列表（`Column { sources.forEach { SourceChip(it) } }`）。注意向后兼容：保留 source 字段或迁移 |
| **R-5 appendDelta 非原子 RMW 违反 BR-concurrency-004** | 中 | ConversationViewModel.kt:113 `_messages.value = _messages.value.map { ... }`（非原子读-改-写）。当前单协程 collect 顺序执行，无并发写。但 RAG 注入后若引入检索协程并发写 `_messages`（如检索完成后追加 sources），会触发 lost update | 改用 `_messages.update { it.map { ... } }` 原子 CAS（与 KnowledgeBaseViewModel.kt:166/203 一致）。即使当前单协程，防御性改用 update 更安全 |
| **R-6 search 同步阻塞 + 无相似度阈值过滤** | 中 | KnowledgeBaseRepository.kt:249 `search` 同步方法；:229-232 注释明确阈值过滤由调用方决定。返回 top-k 不论相似度高低，若不设阈值，无关检索结果会污染 RAG context。HNSW 近似性可能 `results.size < k`（:234-236） | US-019 调用方设定相似度阈值（如 `results.filter { it.similarity > 0.3 }`），并处理 `results.size < k` 与空结果降级（无检索结果时直接走普通对话，不注入 RAG context）。阈值须在 ADR 中固化 |
| **R-7 误导性 UI 文案与未实现的 @知识库 语法** | 中 | ConversationScreen.kt:355 "正在调用 MCP 检索知识库…" 硬编码（实际无 MCP 检索）；:388 输入框占位符 "输入问题，@知识库 检索…" 未实现。RAG 集成后这些文案需修正或实现，否则误导用户 | TypingIndicator 文案改为真实检索状态（如"正在检索知识库…"，去掉 MCP 字样）；@知识库 语法若不实现应移除占位符改为通用提示，避免误导。若实现 @知识库 语法，须解析输入文本提取目标库 |
| **R-8 RetrievalResult 字段与任务令牌描述不一致** | 低 | RetrievalResult.kt:28-36 实际字段 `chunkId/content/title/similarity/documentTitle/chunkIndex/knowledgeBaseId`，**无 `startOffset/endOffset`**。任务令牌提到的 startOffset/endOffset 不存在 | US-019 设计引用来源 UI 时基于实际字段（`documentTitle` + `chunkIndex`），不依赖 startOffset/endOffset。引用来源展示格式如「{documentTitle} · #{chunkIndex}」 |

---

## 5. 入门路径

### 5.1 实现应从哪个文件开始改

**从 `ConversationViewModel.kt` 开始改**，理由：

1. `sendMessage`（:73）是消息发送唯一入口，RAG 检索须在此触发。
2. 须新增 `embedder` + `knowledgeBaseRepository` 依赖（改构造函数 + Factory）。
3. 须在 `streamChat` 前插入：`embed(query)` → `search` → 过滤 → 拼 system prompt。
4. 须扩展 `ChatMessage` 承载多引用来源（改 `appendDelta` 与 `Done` 分支）。
5. `ChatStreamProvider` 接口扩展（systemPrompt 参数）须同步推进。

### 5.2 推荐阅读顺序（US-019 实现者）

1. **ConversationViewModel.kt** —— 理解 `sendMessage` 消息流与 `appendDelta` 累积逻辑（:73-116），确认 RAG 注入点。
2. **OpenAICompatibleProvider.kt** —— 理解 `buildRequestBody`（:124-132）与 `Role.toRequestRole`（:222），确认 system 注入缺口。
3. **ChatStreamProvider.kt** —— 理解接口契约（:15-23），决定是否扩展 systemPrompt 参数。
4. **KnowledgeBaseRepository.kt:249-284** —— 理解 `search` 接口契约（向量入参、同步阻塞、kbId 三态、无阈值过滤）。
5. **OnnxEmbedder.kt:79-125** —— 理解 `embed` 并发约束（串行持锁、~100ms、close 后不可复用）。
6. **PrismApplication.kt:176/202-208** —— 确认 `embedder` + `knowledgeBaseRepository` 已暴露，Factory 改造范围。
7. **ConversationScreen.kt:239-320** —— 理解 `MessageBubble` + `SourceChip` 现状，规划多引用来源 UI。
8. **KnowledgeBaseViewModel.kt:349** —— 借鉴 `Dispatchers.IO` collect 模式与 `_uiState.update` 原子 CAS。
9. **US-017 检索考古报告**（./2026-08-07-us017-retrieval-archaeology.md）—— 上游设计意图，确认 search 接口稳定性与相似度转换公式。
10. **behavioral-rules.md** —— BR-concurrency-002（embed 持锁）/ BR-concurrency-004（StateFlow CAS）/ BR-interface-003（过滤空占位）/ BR-error-handling-004（catch 不静默吞）。

### 5.3 改造清单（按依赖顺序）

| 顺序 | 文件 | 改动 |
|---|---|---|
| 1 | `ChatMessage.kt` | 扩展 `source: String?` → `sources: List<RetrievalResult>`（或 `List<Citation>`） |
| 2 | `ChatStreamProvider.kt` | `streamChat` 加 `systemPrompt: String?` 参数（方案 C） |
| 3 | `OpenAICompatibleProvider.kt` | `buildRequestBody` 在 messages.map 前前置 system MessageBody；`Role.toRequestRole` 无需改（system 由参数注入） |
| 4 | `ConversationViewModel.kt` | 构造函数加 `embedder` + `knowledgeBaseRepository`；`sendMessage` 在 IO 协程执行 embed+search+过滤+拼 system prompt；`Done` 分支追加 sources；`appendDelta` 改用 `_messages.update` |
| 5 | `ConversationViewModel.kt Factory` | 从 `app.embedder` / `app.knowledgeBaseRepository` 注入 |
| 6 | `ConversationScreen.kt` | `SourceChip` 改为渲染列表；`TypingIndicator` 文案修正；`MessageInputBar` 占位符修正 |
| 7 | （可选）新增 RAG 库选择器 | 仿 `ProviderSelectorSheet`（ConversationScreen.kt:186）模式，从 `knowledgeBaseRepository.knowledgeBases` 取库列表 |

---

## 6. 结论与建议

### 6.1 关键事实总结

1. **US-019 是从零集成**：当前无任何 RAG 半成品代码（Q7），仅占位符文案和预留字段。
2. **DI 改造范围小**：`embedder` + `knowledgeBaseRepository` 已在 `PrismApplication` 暴露（Q5），仅需扩展 `ConversationViewModel` 构造函数与 Factory。
3. **system prompt 注入是核心缺口**：三层（Role 枚举 / ChatStreamProvider 接口 / buildRequestBody）均无 system 支持（Q1/Q2），须选择注入方案并记 ADR。
4. **检索上游接口稳定**：`search` 已由 US-017 实现并验收，向量入参、同步阻塞、无阈值过滤的契约明确（Q4）。
5. **并发约束清晰**：`embed` 串行持锁 ~100ms（Q3），`search` 同步阻塞，全链路须在 IO 协程。
6. **引用来源 UI 有雏形**：`SourceChip` 已存在但仅支持单 String（Q6/Q8），须扩展为多来源列表。

### 6.2 强制建议

1. **system 注入方案须记 ADR**（R-3）：推荐方案 C（扩展 ChatStreamProvider 接口），触发 ADR-017.1 条件 2/7。
2. **相似度阈值须记 ADR**（R-6）：固化阈值（如 0.3）与空结果降级策略。
3. **全链路 IO 协程**（R-1）：embed + search 须在 `Dispatchers.IO`，参考 KnowledgeBaseViewModel.kt:349。
4. **`_messages.update` 原子 CAS**（R-5）：与 BR-concurrency-004 一致，避免 RAG 注入引入并发写导致 lost update。
5. **扩展 ChatMessage 须评估向后兼容**（R-4）：现有测试可能依赖 `source` 字段。
6. **修正误导性文案**（R-7）：TypingIndicator 与输入框占位符。
7. **基于实际 RetrievalResult 字段设计 UI**（R-8）：用 `documentTitle` + `chunkIndex`，不依赖不存在的 startOffset/endOffset。

### 6.3 上下游契约确认

| 契约 | 状态 | 证据 |
|---|---|---|
| `search(query: FloatArray, k, kbId)` 向量入参、同步阻塞 | 已确认 | KnowledgeBaseRepository.kt:249-284 |
| `RetrievalResult` 7 字段（无 startOffset/endOffset） | 已确认 | RetrievalResult.kt:28-36 |
| `embed(text): FloatArray(384)` 串行持锁 ~100ms | 已确认 | OnnxEmbedder.kt:79, BR-concurrency-002 |
| `embedder` / `knowledgeBaseRepository` 已在 DI 暴露 | 已确认 | PrismApplication.kt:176/202 |
| `ChatStreamProvider` 无 systemPrompt 参数 | 已确认 | ChatStreamProvider.kt:23 |
| `Role` 枚举无 SYSTEM | 已确认 | ChatMessage.kt:4 |
| `ChatMessage.source` 单 String | 已确认 | ChatMessage.kt:22-23 |
| `SourceChip` 渲染单 String | 已确认 | ConversationScreen.kt:303 |
| 无 RAG 半成品代码 | 已确认 | 全局搜索零匹配（Q7） |

---

## 7. 证据索引

| 证据 | 路径 |
|---|---|
| ConversationViewModel | `../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt` |
| ConversationScreen | `../../app/src/main/java/io/prism/ui/chat/ConversationScreen.kt` |
| ChatMessage | `../../app/src/main/java/io/prism/ui/model/ChatMessage.kt` |
| ChatStreamProvider 接口 | `../../app/src/main/java/io/prism/network/ChatStreamProvider.kt` |
| OpenAICompatibleProvider | `../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt` |
| StreamEvent | `../../app/src/main/java/io/prism/network/StreamEvent.kt` |
| KnowledgeBaseRepository | `../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt` |
| RetrievalResult | `../../app/src/main/java/io/prism/data/RetrievalResult.kt` |
| KnowledgeBase | `../../app/src/main/java/io/prism/data/KnowledgeBase.kt` |
| Embedder 接口 | `../../app/src/main/java/io/prism/embedding/Embedder.kt` |
| OnnxEmbedder 实现 | `../../app/src/main/java/io/prism/embedding/OnnxEmbedder.kt` |
| PrismApplication（DI 入口） | `../../app/src/main/java/io/prism/PrismApplication.kt` |
| KnowledgeBaseViewModel（参考） | `../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseViewModel.kt` |
| US-017 检索考古报告 | `./2026-08-07-us017-retrieval-archaeology.md` |
| US-018 KB UI 考古报告 | `./2026-08-07-us018-kb-ui-archaeology.md` |
| 行为规则 | `../behavioral-rules.md` |
| ADR-002 对话 UI 架构 | `../decisions/ADR-002-prism-chat-ui-architecture.md` |
| ADR-004 Provider 流式 | `../decisions/ADR-004-prism-provider-streaming.md` |
| ADR-007 RAG 技术栈 | `../decisions/ADR-007-m3-rag-tech-stack.md` |
| ADR-010 向量检索 | `../decisions/ADR-010-m3-vector-retrieval.md` |
| ADR-011 知识库 UI | `../decisions/ADR-011-m3-knowledgebase-ui.md` |
