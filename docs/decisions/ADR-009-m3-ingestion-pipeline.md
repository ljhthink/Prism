# ADR-009: M3 摄入管线编排（US-016）

> 从 `docs/templates/adr-template.md` 复制新建，依 CLAUDE.md 第十七节。
> 本 ADR 记录 M3「个人知识库 RAG」摄入管线（IngestionPipeline）的编排决策：组件串联、进度观察、错误降级、事务边界、协程取消、写入路径。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-07（Proposed → Accepted 2026-08-09，M3 里程碑审计 TKN-M3-MILESTONE-AUDIT-001 同步） |
| 决策者 | 主 Agent（基于 code-archaeologist 考古报告 TKN-US016-ARCH-001 + web-access 调研 + sequential-thinking 推演） |
| 关联文档 | [ADR-007](ADR-007-m3-rag-tech-stack.md) / [ADR-008](ADR-008-m3-knowledgebase-model.md) / [PRD.md](../PRD.md) US-003 / [prd.json](../../prd.json) US-016 |
| 上游调研 | [US-016 摄入管线源码考古报告](../reports/2026-08-07-us016-ingestion-archaeology.md) + web-access RAG 摄入管线最佳实践调研（LlamaIndex IngestionPipeline / Kotlin 协程取消官方文档，2026-08-07） |
| 风险等级 | P2 跨模块（集成 4 个既有模块，无接口/契约/依赖变更，新增管线编排层） |

## 背景（Context）

PRD US-003 验收 2「摄入→切片→嵌入→入库全链路」要求用户导入文档后自动完成端到端 RAG 摄入。US-016 落地 `IngestionPipeline`，串联既有 4 个组件：

1. **DocumentParser**（US-012，[DocumentParser.kt](../../app/src/main/java/io/prism/document/DocumentParser.kt)）：`fun parse(input: InputStream): String`，非 suspend，失败抛 `DocumentParseException`
2. **Chunker**（US-013，[Chunker.kt](../../app/src/main/java/io/prism/document/Chunker.kt)）：`fun chunk(text: String): List<String>`，非 suspend，空输入返回空列表
3. **Embedder**（US-014，[Embedder.kt](../../app/src/main/java/io/prism/embedding/Embedder.kt)）：`fun embed(text: String): FloatArray`，非 suspend，失败抛 `EmbeddingException(stage)`，**返回非 nullable**
4. **KnowledgeBaseRepository**（US-015，[KnowledgeBaseRepository.kt](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)）：持有 `chunkBox` 但**无公开 put 方法**，需扩展

考古报告（TKN-US016-ARCH-001）确认的关键事实：
- 4 个组件**全部 blocking 非 suspend**；OnnxEmbedder.embed 全程持 `ReentrantLock`（~100ms/次，BR-concurrency-002）
- 无任何组件用 `Result<T>`；DocumentParser 抛 `DocumentParseException`，OnnxEmbedder 抛 `EmbeddingException(stage)`
- OnnxEmbedder 按需加载 session 缓存，管线多次调用不会反复加载模型；`close` 后不可复用（抛 IllegalStateException）
- KnowledgeChunk 是项目唯一无 Repository 封装的实体，既有测试直接 `boxStore.boxFor(KnowledgeChunk).put()`

US-016 验收标准：
- AC-1：IngestionPipeline：解析→切片→嵌入→写入指定库
- AC-2：摄入进度与错误可观察
- AC-3：嵌入为 null 的片段不建索引并提示
- AC-4：摄入管线集成测试通过
- AC-5：Typecheck passes

设计未决问题（考古报告 §7.3）：
1. 写入路径（扩展 KnowledgeBaseRepository.addChunk / 新建 KnowledgeChunkRepository / 直接 Box.put）？
2. 嵌入失败降级策略（skip / retry / fail-fast）？
3. 事务边界（chunk 级 put / 文档级 runInTx）？
4. 协程取消传播（embed 持锁不可中断）？
5. 进度观察模型（StateFlow / Flow<Event> / callback）？

本 ADR 锁定以上 5 项决策。

## 决策（Decision）

### 5.1 组件串联：parse→chunk→embed→store 链式，仿 LlamaIndex IngestionPipeline transformations

```kotlin
class IngestionPipeline(
    private val parserRegistry: DocumentParserRegistry,
    private val chunker: Chunker,
    private val embedder: Embedder,
    private val repository: KnowledgeBaseRepository
)
```

**理由**：
- web-access 调研 LlamaIndex [IngestionPipeline](https://llamaindex.openml.io/python/framework/module_guides/loading/ingestion_pipeline/) 确认 transformations 链式（splitter→embedder→vector store）是业界标准模式。
- 4 个组件职责单一、接口稳定，管线仅做编排不做转换，符合单一职责。
- 构造注入便于测试用 Fake 替身隔离（BR-testing-001）。

### 5.2 写入路径：扩展 `KnowledgeBaseRepository.addChunk`

```kotlin
// KnowledgeBaseRepository.kt 新增
fun addChunk(chunk: KnowledgeChunk): Long {
    require(chunk.knowledgeBaseId >= 0) { "knowledgeBaseId 不能为负数" }
    return chunkBox.put(chunk)
}
```

**理由**：
- 考古报告 R-2 建议此方案：KnowledgeBaseRepository 已持有 `chunkBox` 且有级联删除/计数先例，扩展 `addChunk` 保持单一写入入口。
- 否决新建 `KnowledgeChunkRepository`：US-015 已确立 KnowledgeChunk 由 KB Repository 代管模式，新建会破坏既有架构一致性。
- 否决直接 `boxStore.boxFor(KnowledgeChunk).put()`：散落写入路径，无法统一校验 knowledgeBaseId 合法性。
- `addChunk` 不刷新 `_knowledgeBases` Flow（chunk 增删不影响 KB 列表），但 `chunkCount` 查询会反映新值。

### 5.3 进度观察：`Flow<IngestionEvent>` 事件流，chunk 边界 emit

```kotlin
sealed class IngestionEvent {
    object Started : IngestionEvent()
    data class Parsed(val textLength: Int) : IngestionEvent()
    data class Chunked(val totalChunks: Int) : IngestionEvent()
    data class ChunkEmbedded(val index: Int, val total: Int, val title: String) : IngestionEvent()
    data class ChunkSkipped(val index: Int, val total: Int, val title: String, val reason: String) : IngestionEvent()
    data class Completed(val result: IngestionResult) : IngestionEvent()
    data class Failed(val throwable: Throwable) : IngestionEvent()
}

fun ingest(
    fileName: String,
    input: InputStream,
    knowledgeBaseId: Long,
    chunkTitlePrefix: String? = null
): Flow<IngestionEvent> = flow { ... }
```

**理由**：
- `Flow<IngestionEvent>` 比 `StateFlow<IngestionProgress>` 更适合：摄入是事件流（Started→Parsed→Chunked→ChunkEmbedded×N→Completed），非状态快照。
- 比 callback 模式更符合 Kotlin 协程习惯，调用方 `collect` 时自动支持背压与取消。
- chunk 边界 emit（非 embed 锁内 emit），避免 OnnxEmbedder 锁竞争（考古 R-5）。
- `Failed` 事件区分可恢复错误（解析失败、单 chunk 嵌入失败已降级）与致命错误（管线级异常）：
  - DocumentParseException → emit `Failed` + 终止管线（无法继续无文本）
  - EmbeddingException → emit `ChunkSkipped` + 继续下一 chunk（AC-3 降级）
  - 其他 Exception → emit `Failed` + 终止

### 5.4 嵌入失败降级：catch `EmbeddingException` → `embedding = null` → 仍入库 → emit `ChunkSkipped`

```kotlin
val embedding: FloatArray? = try {
    embedder.embed(chunkText)
} catch (e: EmbeddingException) {
    emit(IngestionEvent.ChunkSkipped(index, total, title, "嵌入失败: ${e.stage}"))
    null
}
val chunk = KnowledgeChunk(
    title = title,
    content = chunkText,
    embedding = embedding,  // null 时 HNSW 索引自动排除
    knowledgeBaseId = knowledgeBaseId
)
repository.addChunk(chunk)
```

**理由**：
- AC-3「嵌入为 null 的片段不建索引并提示」：OnnxEmbedder.embed 返回非 nullable FloatArray，失败抛 EmbeddingException。IngestionPipeline 主动 catch → 保持 `embedding = null` → HNSW 索引自动排除（[KnowledgeChunkVectorSearchTest.kt:73-86](../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) 已验证）→ 入库仍可按文本检索（US-017 全文检索兜底）。
- web-access 调研确认 per-step graceful degradation 是 RAG 业界共识（[theneuralbase.com](https://theneuralbase.com/rag-fundamentals/learn/intermediate/error-handling-per-step/)）：单 chunk 失败不应中断整管线，避免一个坏 chunk 导致整文档摄入失败。
- 否决 retry：OnnxEmbedder 无重试契约，端侧推理失败多为模型/输入问题，重试浪费资源且大概率仍失败。
- 否决 fail-fast：违反 AC-3「提示」语义（提示暗示继续处理其他 chunk）。
- 遵守 BR-error-handling-004：catch 块通过 emit `ChunkSkipped` 事件记录可诊断信息（stage + index），不静默吞异常。EmbeddingException 不含密钥/请求体，stage 枚举可安全暴露。

### 5.5 事务边界：chunk 级独立 `addChunk`，不强制文档级 `runInTx`

**理由**：
- 嵌入是昂贵操作（~100ms/chunk），文档级事务若中途 OOM 全回滚会丢失已嵌入结果，违反「贵重结果不回滚」原则。
- chunk 级 put 失败不影响已入库 chunk，符合 AC-3 降级语义。
- HNSW 索引下 batch put 与逐条 put 性能差异小（ObjectBox 官方建议事务粒度匹配业务不变式，此处无跨 chunk 不变式需维护）。
- 否决文档级 `runInTx`：考古 R-3 明确指出无业务不变式需要原子性保证（每个 chunk 独立可检索）。
- BR-concurrency-001 适用条件不满足：本场景非「多步骤修改维护业务不变式」，而是「独立项逐条写入」。

### 5.6 协程取消：`flow {}` 内 chunk 边界 `coroutineContext.ensureActive()`

```kotlin
flow {
    emit(IngestionEvent.Started)
    // ... parse + chunk ...
    chunks.forEachIndexed { index, chunkText ->
        coroutineContext.ensureActive()  // chunk 边界检查取消
        // ... embed + addChunk ...
    }
    emit(IngestionEvent.Completed(result))
}
```

**理由**：
- web-access 调研 Kotlin 官方 [Cancellation and timeouts](https://kotlinlang.org/docs/cancellation-and-timeouts.html) 文档确认：`ensureActive()` 是长循环检查取消的标准模式。
- OnnxEmbedder.embed 持 `ReentrantLock` 不可中断（BR-concurrency-002），单次 ~100ms 可接受；在 chunk 边界（即每次 embed 前）检查取消，最坏延迟 100ms 响应取消。
- `flow {}` builder 内每个 `emit` 也是挂起点，自然支持取消传播。
- **禁止 catch CancellationException**（Kotlin 协程铁律，[fixdevs.com](https://fixdevs.com/blog/kotlin-coroutine-scope-cancelled/) 调研确认）：catch 块只 catch `EmbeddingException`，不 catch `Exception`/`Throwable`，避免吞 `CancellationException` 导致 zombie 协程。

### 5.7 InputStream 生命周期：管线内 `input.use {}` 保证关闭

```kotlin
fun ingest(..., input: InputStream, ...): Flow<IngestionEvent> = flow {
    input.use { stream ->
        // parse + chunk + embed + store
    }
}
```

**理由**：
- DocumentParser.parse 契约规定「调用方负责关闭」（[DocumentParser.kt:14](../../app/src/main/java/io/prism/document/DocumentParser.kt)）。
- `use {}` 保证无论正常返回还是异常，InputStream 都被关闭，避免文件句柄泄漏。
- flow {} 内 use {} 块在协程取消时也会通过 finally 关闭（InputStream.close 非 suspend，可在 Cancelling 状态执行）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| suspend fun ingest() 返回 IngestionResult | 签名简单 | 无法实时推送进度，AC-2 需额外 callback 参数，破坏单一返回值语义 |
| StateFlow<IngestionProgress> 进度状态 | 状态快照语义清晰 | 摄入是事件流非状态，ChunkSkipped/Completed 等事件用状态表达别扭；StateFlow 需持有 MutableStateFlow 字段，管线无状态设计被破坏 |
| callback 回调进度 | 简单直接 | 非 Kotlin 习惯，无法自动支持取消/背压；回调嵌套深 |
| 新建 KnowledgeChunkRepository | 实体封装一致 | US-015 已确立 KB Repository 代管 chunk 模式，新建破坏一致性；过度封装 |
| 直接 Box.put | 无需扩展 Repository | 散落写入路径，无法统一校验 knowledgeBaseId；违反单一写入入口 |
| 文档级 runInTx 事务 | 原子性强 | 嵌入昂贵，中途失败回滚丢失已嵌入结果；无业务不变式需保证 |
| 嵌入失败 retry | 提高成功率 | OnnxEmbedder 无重试契约；端侧失败多为确定性错误，重试浪费资源 |
| 嵌入失败 fail-fast | 快速暴露问题 | 违反 AC-3「提示」语义；单 chunk 失败导致整文档摄入失败，用户体验差 |
| while(isActive) 检查取消 | 直观 | ensureActive() 更 idiomatic，且在非循环结构（forEachIndexed）中适用 |

## 后果（Consequences）

- 正面后果：
  - 管线编排清晰，4 组件职责单一，管线仅做串联
  - Flow<IngestionEvent> 实时进度，调用方 collect 即可观察，自动支持取消/背压
  - 嵌入失败降级为 null embedding + ChunkSkipped 事件，符合 AC-3，单 chunk 不阻断整文档
  - chunk 级独立 put 保留贵重嵌入结果，无文档级回滚风险
  - ensureActive() 在 chunk 边界检查取消，最坏 100ms 响应延迟可接受
  - 扩展 KnowledgeBaseRepository.addChunk 保持单一写入入口，校验统一
- 负面后果 / 代价：
  - Flow<IngestionEvent> 调用方需协程作用域 collect，比同步 API 稍复杂
  - 嵌入失败的 chunk 仍入库（embedding=null），占用存储但不参与向量检索；US-018 UI 需提示用户「N 个片段未建索引」
  - chunk 级独立 put 在极端场景（写入中途崩溃）可能留下部分入库状态，但符合 RAG 摄入语义（部分可用优于全不可用）
- 需要同步更新的文档或代码：
  - 新增 `app/src/main/java/io/prism/ingestion/IngestionPipeline.kt`
  - 新增 `app/src/main/java/io/prism/ingestion/IngestionEvent.kt`
  - 新增 `app/src/main/java/io/prism/ingestion/IngestionResult.kt`
  - 修改 `app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt`：新增 `addChunk` 方法
  - 新增 `app/src/test/java/io/prism/ingestion/IngestionPipelineTest.kt`
  - `docs/decisions/README.md` / `README.md`：ADR 索引同步
  - `prd.json`：US-016 `passes=true` + notes 补 ADR-009 引用

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 嵌入失败的 chunk 占用存储不参与检索 | 低 | US-018 UI 显示「N 个片段未建索引」提示；后续可加「重新嵌入」功能 |
| 协程取消时 OnnxEmbedder 持锁中无法立即响应 | 低 | 单次 embed ~100ms，最坏延迟可接受；ensureActive 在 chunk 边界检查 |
| Flow collect 异常未处理导致崩溃 | 中 | Failed 事件封装异常，调用方 collect 时处理；DocumentParseException 等致命错误通过 Failed 事件传递而非抛出 |
| InputStream 在协程取消时未关闭 | 中 | `input.use {}` 在 finally 关闭，close 非 suspend 可在 Cancelling 状态执行 |
| addChunk 与级联删除并发竞态 | 低 | ObjectBox Box.put 线程安全；级联删除在 runInTx 内，addChunk 短事务，竞态窗口小 |
| 大文档 chunk 数多导致 Flow emit 频繁 | 低 | chunk 边界 emit（~100ms/次），频率可接受；US-018 UI 可节流 |

## 参考

- [US-016 摄入管线源码考古报告](../reports/2026-08-07-us016-ingestion-archaeology.md)
- [LlamaIndex IngestionPipeline](https://llamaindex.openml.io/python/framework/module_guides/loading/ingestion_pipeline/) —— transformations 链式编排模式
- [RAG Error handling per step](https://theneuralbase.com/rag-fundamentals/learn/intermediate/error-handling-per-step/) —— per-step graceful degradation 最佳实践
- [Kotlin Cancellation and timeouts](https://kotlinlang.org/docs/cancellation-and-timeouts.html) —— ensureActive() 官方文档
- [Fix Kotlin Coroutine Scope Cancelled](https://fixdevs.com/blog/kotlin-coroutine-scope-cancelled/) —— CancellationException 不可吞
- [ADR-007](ADR-007-m3-rag-tech-stack.md)：M3 RAG 技术栈
- [ADR-008](ADR-008-m3-knowledgebase-model.md)：知识库分库数据模型 / 级联删除事务模式
- [BR-concurrency-001](../behavioral-rules.md)：多步骤数据库状态变更必须事务保证原子性（本场景不适用，无业务不变式）
- [BR-concurrency-002](../behavioral-rules.md)：生命周期资源并发访问须覆盖 close 路径（OnnxEmbedder 全程持锁）
- [BR-error-handling-004](../behavioral-rules.md)：catch 兜底异常须输出结构日志（ChunkSkipped 事件记录 stage）
