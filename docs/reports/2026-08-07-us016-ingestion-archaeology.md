# 源码考古报告：US-016 摄入管线前置接口契约探查

> 从 `docs/templates/reports/archaeology-template.md` 复制新建，依 CLAUDE.md 第三节 3.1（简化版考古）。
> 由 code-archaeologist 子 Agent 生成，聚焦 IngestionPipeline 将集成的 4 个既有组件的接口契约与写入路径。

| 项目 | 内容 |
|---|---|
| 执行 Agent | code-archaeologist |
| 任务令牌 | TKN-US016-ARCH-001 |
| 考古日期 | 2026-08-07 |
| 考古目标 | US-016 摄入管线前置：DocumentParser / Chunker / OnnxEmbedder / KnowledgeBaseRepository / KnowledgeChunk 写入路径 |
| 考古模式 | 完整考古（简化版，聚焦接口契约） |
| 项目根 | d:\s0611\code\Prism |
| 主 Agent 自问盲区1 | 4 个组件的精确方法签名/返回类型/线程模型/错误传播方式 |
| 主 Agent 自问盲区2 | KnowledgeChunk 写入路径（Box.put vs Repository 封装）；OnnxEmbedder 资源生命周期在管线多次调用时是否反复加载 |

---

## 0. 核心结论速览（回答主 Agent 两个关键问题）

### Q1：OnnxEmbedder.embed 返回类型是否 nullable？

**否。返回非 nullable `FloatArray`。**

- 接口契约：[Embedder.kt](../../app/src/main/java/io/prism/embedding/Embedder.kt) `fun embed(text: String): FloatArray`（第 31 行）
- 实现契约：[OnnxEmbedder.kt](../../app/src/main/java/io/prism/embedding/OnnxEmbedder.kt) `override fun embed(text: String): FloatArray = lock.withLock { ... }`（第 79 行）

失败时**抛 `EmbeddingException`**（含 `Stage` 枚举：MODEL_LOAD / TOKENIZER_INIT / INFERENCE / POOLING / UNLOAD），**不返回 null**。证据见 [EmbeddingException.kt](../../app/src/main/java/io/prism/embedding/EmbeddingException.kt) 第 8-20 行。

**这对 AC-3「嵌入为 null 的片段不建索引并提示」的影响**：`OnnxEmbedder.embed` 永不返回 null，因此「嵌入为 null」只能由 IngestionPipeline 主动制造——即 `embed` 抛异常时 catch，将 `chunk.embedding` 保持为 null（字段默认值）后仍入库。详见 §5 风险点 R-1。

### Q2：KnowledgeChunk 是否有 Repository 封装？

**没有。KnowledgeChunk 是项目中唯一无 Repository 封装的实体。**

- [KnowledgeBaseRepository.kt](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) 第 37 行持有 `private val chunkBox: Box<KnowledgeChunk>`，但**仅用于级联删除（`remove`/`removeAll`）与计数（`chunkCount`）**，无任何公开的 `put`/`addChunk`/`saveChunk` 方法。
- ADR-008 §背景明确记载：「KnowledgeChunk 是唯一无 Repository 封装的实体，零业务依赖，仅 5 个测试文件直接操作 `Box`」。
- 既有测试先例（[KnowledgeChunkCrudTest.kt](../../app/src/test/java/io/prism/data/KnowledgeChunkCrudTest.kt) 第 32/44 行、[KnowledgeChunkVectorSearchTest.kt](../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) 第 33/52 行）均直接 `boxStore.boxFor(KnowledgeChunk::class.java).put(chunk)`。

**IngestionPipeline 写入 chunk 的路径须由主 Agent 决策**，详见 §5 风险点 R-2。

---

## 1. 模块职责（一句话）

| 组件 | 包路径 | 职责 |
|---|---|---|
| DocumentParser | `io.prism.document` | 将文档输入流解析为纯文本（PDF/DOCX/XLSX/MD/TXT/CSV），US-012 实现 |
| Chunker | `io.prism.document` | 将长文本按段落/句子边界切分为可检索片段（可配置 chunkSize/overlap），US-013 实现 |
| OnnxEmbedder | `io.prism.embedding` | 端侧 ONNX 推理，将文本编码为 384 维 L2 归一化向量，US-014 实现 |
| KnowledgeBaseRepository | `io.prism.data` | 知识库分库（KnowledgeBase 实体）CRUD + 级联删除 chunk + chunk 计数，US-015 实现 |

---

## 2. 关键接口签名（精确到参数类型/返回类型/nullable/suspend）

### 2.1 DocumentParser（接口 + 注册表）

```kotlin
// app/src/main/java/io/prism/document/DocumentParser.kt:18
fun interface DocumentParser {
    fun parse(input: InputStream): String   // 第 26 行；非 suspend；返回非 nullable String
}

// app/src/main/java/io/prism/document/DocumentParserRegistry.kt:18
class DocumentParserRegistry {
    fun parserFor(fileName: String): DocumentParser   // 第 27 行；非 suspend；不支持格式抛 DocumentParseException
}
```

- **输入**：`java.io.InputStream`（由调用方负责关闭；实现类如 [PdfDocumentParser.kt](../../app/src/main/java/io/prism/document/PdfDocumentParser.kt) 第 28 行用 `input.readBytes()` 全量读入内存）
- **返回**：`String`（可能为空字符串，非 null）
- **异常**：`DocumentParseException(fileName: String, cause: Throwable? = null) : RuntimeException`（[DocumentParseException.kt](../../app/src/main/java/io/prism/document/DocumentParseException.kt) 第 12-14 行）
- **实现类**（均持有 `fileName: String`，无状态可复用）：`PdfDocumentParser` / `OfficeDocumentParser` / `PlainTextDocumentParser`，位于 `app/src/main/java/io/prism/document/`
- **格式枚举**：[DocumentType.kt](../../app/src/main/java/io/prism/document/DocumentType.kt) 第 12-24 行，支持 PDF/DOCX/XLSX/MD/TXT/CSV

### 2.2 Chunker

```kotlin
// app/src/main/java/io/prism/document/Chunker.kt:24
class Chunker(
    private val chunkSize: Int,
    private val overlap: Int
) {
    init {
        require(chunkSize > 0) { "chunkSize 必须 > 0，收到: $chunkSize" }
        require(overlap in 0 until chunkSize) { "overlap 必须位于 [0, chunkSize)，收到: $overlap" }
    }

    fun chunk(text: String): List<String>   // 第 42 行；非 suspend；返回非 nullable，空输入返回 emptyList
}
```

- **输入**：`String`（待切分文本）
- **返回**：`List<String>`（非 null；`text.isBlank()` 返回 `emptyList()`，见第 43 行）
- **异常**：构造时 `require` 抛 `IllegalArgumentException`（参数非法）；`chunk()` 本身不抛业务异常（纯函数）
- **线程安全**：无状态，可安全跨线程复用（类注释第 19 行）
- **切片策略**：段落优先 → 句子边界回退 → 词边界回退 → 硬切；overlap 跨段落生效（guardrail G-1）

### 2.3 OnnxEmbedder（实现 Embedder 接口）

```kotlin
// app/src/main/java/io/prism/embedding/Embedder.kt:22
interface Embedder : AutoCloseable {
    fun embed(text: String): FloatArray                                          // 第 31 行；非 nullable
    fun embedBatch(texts: List<String>): List<FloatArray> = texts.map { embed(it) }  // 第 39 行；默认逐条
    fun isLoaded(): Boolean                                                       // 第 46 行
    fun checkAndUnload(maxIdleMs: Long): Boolean                                  // 第 58 行；true=本次执行了卸载
    // 继承 AutoCloseable.close()
}

// app/src/main/java/io/prism/embedding/OnnxEmbedder.kt:48
class OnnxEmbedder(
    private val modelBytes: ByteArray,
    vocab: Map<String, Int>,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val maxSeqLen: Int = 512,
    private val embeddingDim: Int = 384
) : Embedder
```

- **`embed` 返回**：`FloatArray`（384 维 L2 归一化向量，**非 nullable**）
- **`embed` 异常**：`EmbeddingException(stage: Stage, message: String, cause: Throwable?) : RuntimeException`（[EmbeddingException.kt](../../app/src/main/java/io/prism/embedding/EmbeddingException.kt) 第 8-20 行）；`Stage` 枚举：`MODEL_LOAD` / `TOKENIZER_INIT` / `INFERENCE` / `POOLING` / `UNLOAD`
- **`embed` 前置校验**：`require(!closed)`（第 80 行）——`close()` 后调用 `embed` 抛 `IllegalStateException`（BR-error-handling-005）
- **资源生命周期**：
  - 按需加载：首次 `embed` 触发 `ensureLoadedLocked()` 创建 `OrtSession`（第 163 行）
  - 闲置卸载：`checkAndUnload(maxIdleMs)` 由上层定时调度，超时则 `session.close()`（第 129-142 行）
  - 永久关闭：`close()` 置 `closed=true`，后续 `embed` 抛异常（第 144-156 行）
  - **管线多次调用 embed 不会反复加载模型**：`session` 字段缓存，仅首次加载（第 164-167 行 `session?.let { return it }`）
- **线程安全**：`ReentrantLock`，`embed` 全程持锁（BR-concurrency-002，第 79 行 `lock.withLock`）；端侧单用户串行化可接受

### 2.4 KnowledgeBaseRepository

```kotlin
// app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt:34
class KnowledgeBaseRepository(private val boxStore: BoxStore) {
    fun save(config: KnowledgeBase): Long                    // 第 53 行；返回 id
    fun get(id: Long): KnowledgeBase?                        // 第 66 行；id=0 返回 null，id<0 抛异常
    fun getAll(): List<KnowledgeBase>                        // 第 77 行；按 createdAt 升序
    fun findByName(name: String): KnowledgeBase?             // 第 85 行
    fun remove(id: Long)                                     // 第 108 行；runInTx 级联删 chunk；id=0 抛异常
    fun removeAll()                                          // 第 140 行；runInTx；仅自建库
    fun chunkCount(id: Long): Long                           // 第 170 行；id>=0，id<0 抛异常

    companion object {
        const val DEFAULT_KB_ID: Long = 0L                   // 第 187 行；虚拟默认库
    }
}
```

- **无 `addChunk`/`putChunk`/`saveChunk` 方法**——`chunkBox` 是 `private`（第 37 行），仅用于级联删除与计数
- **事务**：`boxStore.runInTx { ... }`（第 113、141 行）
- **默认库语义**：`knowledgeBaseId = 0L` 代表虚拟默认库，不持久化为 KnowledgeBase 记录（ADR-008 5.3）

### 2.5 KnowledgeChunk 实体（写入目标）

```kotlin
// app/src/main/java/io/prism/data/KnowledgeChunk.kt:29
@Entity
data class KnowledgeChunk(
    @Id var id: Long = 0,
    var title: String,
    var content: String,
    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray? = null,     // 第 34 行；nullable！null = 未建索引
    var knowledgeBaseId: Long = 0L         // 第 35 行；0L = 虚拟默认库
)
```

- `embedding` 是 **`FloatArray?`（nullable）**，默认 null
- HNSW 索引自动排除 `embedding=null` 的记录（[KnowledgeChunkVectorSearchTest.kt](../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) 第 73-86 行验证）
- `KnowledgeBase` 实体见 [KnowledgeBase.kt](../../app/src/main/java/io/prism/data/KnowledgeBase.kt) 第 36-39 行：`id/name/createdAt` 三字段

---

## 3. 线程模型

| 组件 | suspend? | blocking? | 调度器建议 | 并发契约 |
|---|---|---|---|---|
| DocumentParser.parse | 否 | 是（IO + CPU，PDFBox/POI 同步） | `Dispatchers.IO` | 无状态可并发 |
| DocumentParserRegistry.parserFor | 否 | 否（纯内存分发） | 任意 | 无状态 |
| Chunker.chunk | 否 | 否（纯 CPU 计算，微秒级） | 任意 | 无状态可并发 |
| OnnxEmbedder.embed | 否 | **是（~100ms/次，全程持锁）** | `Dispatchers.IO`（不可在 Main） | ReentrantLock 串行化，BR-concurrency-002 |
| OnnxEmbedder.embedBatch | 否 | 是（逐条 embed） | `Dispatchers.IO` | 同 embed |
| Box.put（KnowledgeChunk） | 否 | 是（磁盘 IO） | `Dispatchers.IO` | ObjectBox Box 线程安全 |

**关键**：4 个组件**全部是 blocking 非 suspend**。IngestionPipeline 若用协程，必须 `withContext(Dispatchers.IO)` 包裹，且 `embed` 持锁期间**不可被协程取消**（取消只能在 chunk 边界生效）。

---

## 4. 错误传播

| 组件 | 异常类型 | 是否 Result 包装 | 语义 |
|---|---|---|---|
| DocumentParser | `DocumentParseException(fileName, cause)` | 否，直接抛 | 解析失败（损坏/格式不支持） |
| DocumentParserRegistry | `DocumentParseException(fileName, IllegalArgumentException)` | 否，直接抛 | 扩展名不支持 |
| Chunker | `IllegalArgumentException`（构造时） | 否 | 参数非法；`chunk()` 不抛业务异常 |
| OnnxEmbedder | `EmbeddingException(stage, message, cause)` | 否，直接抛 | 推理/加载/pooling/unload 失败 |
| OnnxEmbedder（close 后） | `IllegalStateException`（`require(!closed)`） | 否 | BR-error-handling-005 |
| Box.put | ObjectBox 运行时异常 | 否 | 持久化失败 |

**无任何组件使用 `Result<T>` 包装**。IngestionPipeline 若需统一错误模型，须自行决定 catch 策略。AC-2「错误可观察」要求将异常转化为可观察事件。

---

## 5. 依赖图

```mermaid
graph TD
    subgraph "待建：io.prism.ingestion"
        IP[IngestionPipeline<br/>待实现]
    end

    subgraph "io.prism.document"
        DPR[DocumentParserRegistry]
        DP[DocumentParser<br/>fun interface]
        PDF[PdfDocumentParser]
        OFF[OfficeDocumentParser]
        TXT[PlainTextDocumentParser]
        CH[Chunker]
    end

    subgraph "io.prism.embedding"
        EMB[Embedder<br/>interface : AutoCloseable]
        ONNX[OnnxEmbedder]
        EEX[EmbeddingException]
    end

    subgraph "io.prism.data"
        KBR[KnowledgeBaseRepository]
        KC[KnowledgeChunk<br/>@Entity @HnswIndex]
        KB[KnowledgeBase<br/>@Entity]
        BS[BoxStore<br/>ObjectBox]
    end

    IP --> DPR
    IP --> CH
    IP --> EMB
    IP -.-> KBR
    IP -.-> BS
    DPR --> DP
    DP -.-> PDF
    DP -.-> OFF
    DP -.-> TXT
    ONNX -.->|implements| EMB
    ONNX --> EEX
    KBR --> BS
    KBR -.->|private chunkBox| KC
    KC -.->|knowledgeBaseId| KB
```

**IngestionPipeline 将依赖**：
- `DocumentParserRegistry`（解析分发）
- `Chunker`（切片）
- `Embedder`（接口，生产用 `OnnxEmbedder`，测试可注入 Fake）
- `BoxStore`（写 KnowledgeChunk）或扩展后的 `KnowledgeBaseRepository`
- 间接依赖：`DocumentParseException` / `EmbeddingException`（错误处理）

**虚线**表示 IngestionPipeline 设计阶段未定的依赖（写入路径选择，见 R-2）。

---

## 6. 风险清单

| 风险 | 等级 | 证据 | 建议 |
|---|---|---|---|
| **R-1 OnnxEmbedder null 嵌入语义鸿沟（AC-3）** | 高 | `embed` 返回非 nullable `FloatArray`（[OnnxEmbedder.kt:79](../../app/src/main/java/io/prism/embedding/OnnxEmbedder.kt)）；AC-3 要求「嵌入为 null 的片段不建索引并提示」；ADR-007 风险表：「embedding 为 null 的记录参与检索 \| 中 \| UI 提示部分文档未建立索引」 | `embed` 抛 `EmbeddingException` 时 catch → `chunk.embedding` 保持 null → 仍 `put` 入库 → 向观察者报告失败。HNSW 自动排除 null（[KnowledgeChunkVectorSearchTest.kt:73-86](../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) 验证）。**主 Agent 须明确**：单 chunk 嵌入失败是降级继续（符合 AC-3「提示」语义）还是 fail-fast 中断。建议降级继续 + 暴露失败计数。须遵守 BR-error-handling-004（catch 须记录结构日志保留可诊断类别）。 |
| **R-2 KnowledgeChunk 写入路径无 Repository 封装** | 高 | [KnowledgeBaseRepository.kt:37](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) `chunkBox` 是 private 且无 put 方法；ADR-008 §背景「KnowledgeChunk 是唯一无 Repository 封装的实体」；既有测试直接 `boxStore.boxFor(KnowledgeChunk).put()` | 三选一：(A) IngestionPipeline 直接 `boxStore.boxFor(KnowledgeChunk).put()`（与测试一致，最简但散落）；(B) 扩展 `KnowledgeBaseRepository` 加 `addChunk(chunk)`/`addChunks(list)`（集中封装，但违背单一职责）；(C) 新建 `KnowledgeChunkRepository`（最规范但增文件）。**建议 (B)**：chunk 与 knowledgeBaseId 强关联，避免裸 Box 散落管线。主 Agent 须在 ADR 或报告中明确选择。 |
| **R-3 事务边界与 HNSW 索引交互** | 中 | BR-concurrency-001 要求多步骤 DB 变更用 `runInTx`；BR-concurrency-003 禁 `Query.remove()`（仅删除路径，put 不受影响）；[KnowledgeChunkVectorSearchTest.kt:52](../../app/src/test/java/io/prism/data/KnowledgeChunkVectorSearchTest.kt) put 带向量 chunk 后立即可检索 | 单 chunk `put` 无需事务；若需文档级原子性（整个文档的 chunk 批次全成功或全回滚），用 `boxStore.runInTx { chunks.forEach { box.put(it) } }`。**冲突点**：AC-3 降级继续（单 chunk 失败不回滚已成功 chunk）与文档级原子性互斥。建议：chunk 级独立 put（降级友好），不强制文档级事务。批量 put 大量带向量 chunk 可能触发 HNSW 索引重建开销，需性能验证。 |
| **R-4 协程取消不可中断 embed** | 中 | [OnnxEmbedder.kt:79](../../app/src/main/java/io/prism/embedding/OnnxEmbedder.kt) `embed` 全程持 `ReentrantLock`（BR-concurrency-002），~100ms/次，非 suspend | IngestionPipeline 用协程时，`withContext(Dispatchers.IO)` 内调用 `embed` 持锁期间无法响应 `isActive` 取消。协程取消只能在**chunk 边界**（embed 返回后、下一次 embed 前）生效。须在循环顶部检查 `coroutineContext.ensureActive()` 或 `isActive`，避免用户取消后仍处理剩余 chunk。 |
| **R-5 进度观察线程安全** | 中 | AC-2「摄入进度与错误可观察」；[KnowledgeBaseRepository.kt:39-41](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) 用 `MutableStateFlow` 暴露列表（项目有 StateFlow 先例）；embed 持锁期间不可更新进度 | 进度更新须在 chunk 边界（embed 锁外）emit，用 `MutableStateFlow<IngestionProgress>` 或 `SharedFlow<IngestionEvent>`。**禁止**在 `embed` 锁内 emit（持锁 emit 若观察者反向调用可能死锁）。进度模型建议：`IngestionProgress(total, processed, succeeded, failed, currentFile, currentChunk)`。 |
| **R-6 OnnxEmbedder close 后不可复用** | 低 | [OnnxEmbedder.kt:80](../../app/src/main/java/io/prism/embedding/OnnxEmbedder.kt) `require(!closed)`；BR-error-handling-005 | IngestionPipeline 若持有 `Embedder` 实例，须确保管线生命周期内不 `close()`；`close()` 应由更上层（如 Application/ViewModel）管理。管线异常退出后若需重试，须用新 `OnnxEmbedder` 实例。 |
| **R-7 DocumentParser 输入流由调用方关闭** | 低 | [DocumentParser.kt:23](../../app/src/main/java/io/prism/document/DocumentParser.kt) KDoc「由调用方负责关闭」；[PdfDocumentParser.kt:28](../../app/src/main/java/io/prism/document/PdfDocumentParser.kt) `input.readBytes()` 不关闭流 | IngestionPipeline 打开 `InputStream` 后须在 `finally`/`use {}` 中关闭，避免 SAF 句柄泄漏。 |
| **R-8 FloatArray equals 语义（BR-security-001）** | 低 | [KnowledgeChunk.kt:22-23](../../app/src/main/java/io/prism/data/KnowledgeChunk.kt) 注释明确 data class 含 FloatArray 字段，equals 用引用比较 | IngestionPipeline 若需按内容比较 KnowledgeChunk（如去重），须用 `contentEquals`，不可依赖 `==`。当前不影响 put/get。 |

---

## 7. 入门路径

### 7.1 IngestionPipeline 建议文件位置与命名

```
app/src/main/java/io/prism/ingestion/
├── IngestionPipeline.kt          # 管线主体（解析→切片→嵌入→写入）
├── IngestionProgress.kt          # 进度/事件模型（StateFlow/SharedFlow 载体）
└── IngestionException.kt         # 管线级异常（可选，封装 DocumentParseException/EmbeddingException）

app/src/test/java/io/prism/ingestion/
└── IngestionPipelineTest.kt      # 集成测试（AC-4）
```

- **包名**：`io.prism.ingestion`（新建包，与 `document`/`embedding`/`data` 平级，横跨三域）
- **构造依赖**：`DocumentParserRegistry` + `Chunker` + `Embedder` + `BoxStore`（或扩展后的 `KnowledgeBaseRepository`）

### 7.2 建议实现骨架

```kotlin
class IngestionPipeline(
    private val parserRegistry: DocumentParserRegistry,
    private val chunker: Chunker,
    private val embedder: Embedder,
    private val boxStore: BoxStore,            // 写 KnowledgeChunk
    // 或 private val kbRepository: KnowledgeBaseRepository  // 若扩展 addChunk
) {
    suspend fun ingest(
        fileName: String,
        input: InputStream,
        knowledgeBaseId: Long = KnowledgeBaseRepository.DEFAULT_KB_ID
    ): Flow<IngestionEvent> = flow {
        // 1. 解析
        val parser = parserRegistry.parserFor(fileName)
        val text = withContext(Dispatchers.IO) { input.use { parser.parse(it) } }
        // 2. 切片
        val chunks = chunker.chunk(text)
        // 3. 逐 chunk：嵌入（降级）→ 写入
        val chunkBox = boxStore.boxFor(KnowledgeChunk::class.java)
        for ((index, chunkText) in chunks.withIndex()) {
            ensureActive()  // R-4：chunk 边界响应取消
            val embedding = try {
                embedder.embed(chunkText)
            } catch (e: EmbeddingException) {
                // R-1：降级为 null，AC-3「不建索引并提示」
                emit(IngestionEvent.ChunkEmbeddedFailed(index, e.stage))
                null
            }
            val chunk = KnowledgeChunk(
                title = fileName,
                content = chunkText,
                embedding = embedding,
                knowledgeBaseId = knowledgeBaseId
            )
            chunkBox.put(chunk)
            emit(IngestionEvent.Progress(index + 1, chunks.size))
        }
    }
}
```

### 7.3 主 Agent 编码前须明确的决策点

1. **写入路径**（R-2）：扩展 `KnowledgeBaseRepository.addChunk` vs 新建 `KnowledgeChunkRepository` vs 直接 `Box.put`
2. **降级策略**（R-1）：单 chunk 嵌入失败降级继续 vs fail-fast 中断（建议降级继续）
3. **事务边界**（R-3）：chunk 级独立 put vs 文档级 `runInTx` 批次（建议独立 put）
4. **进度模型**（R-5）：`StateFlow<IngestionProgress>` vs `SharedFlow<IngestionEvent>` vs 两者结合
5. **AC-3「提示」载体**：错误事件如何传递给 UI（US-018 消费）

---

## 8. 结论与建议

本次简化版考古已**逐文件读取源码**确认 4 个组件的精确接口契约，**无任何推测**。两个核心问题明确回答：

1. **OnnxEmbedder.embed 返回非 nullable `FloatArray`**（[Embedder.kt:31](../../app/src/main/java/io/prism/embedding/Embedder.kt) + [OnnxEmbedder.kt:79](../../app/src/main/java/io/prism/embedding/OnnxEmbedder.kt)）。AC-3「嵌入为 null」须由 IngestionPipeline catch `EmbeddingException` 主动制造 null embedding。
2. **KnowledgeChunk 无 Repository 封装**（[KnowledgeBaseRepository.kt:37](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) private chunkBox + ADR-008 §背景）。写入路径须主 Agent 决策。

8 项风险中 R-1/R-2 为高危设计决策点，须主 Agent 在编码前明确并写入 ADR 或设计文档。建议 IngestionPipeline 置于新建 `io.prism.ingestion` 包，用协程 `Dispatchers.IO` + `Flow` 暴露进度，chunk 边界响应取消，embed 失败降级为 null embedding 继续处理。
