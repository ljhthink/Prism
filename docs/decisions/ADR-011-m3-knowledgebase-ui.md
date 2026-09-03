# ADR-011: M3 知识库管理 UI 架构（US-018）

> 从 `docs/templates/adr-template.md` 复制新建，依 CLAUDE.md 第十七节。
> 本 ADR 记录 M3「个人知识库 RAG」US-018 知识库管理 UI 的架构决策：导航入口、ViewModel 与 UiState 设计、文档导入 SAF 集成、IngestionEvent 收集与错误映射。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-07（Proposed → Accepted 2026-08-09，M3 里程碑审计 TKN-M3-MILESTONE-AUDIT-001 同步） |
| 决策者 | 主 Agent（基于 code-archaeologist 考古报告 + 用户确认） |
| 关联文档 | [ADR-008](ADR-008-m3-knowledgebase-model.md) / [ADR-009](ADR-009-m3-ingestion-pipeline.md) / [ADR-010](ADR-010-m3-vector-retrieval.md) / [PRD.md](../PRD.md) US-003 / [prd.json](../../prd.json) US-018 |
| 上游调研 | [US-018 知识库管理 UI 源码考古报告](../reports/2026-08-07-us018-kb-ui-archaeology.md) |
| 风险等级 | P2 跨模块（修改 PrismApplication 暴露 5 个新依赖、新建 ViewModel、改造既有 Screen） |

## 背景（Context）

PRD US-018 要求在 App 内管理知识库、导入文档并查看进度。考古报告 §0 揭示三项核心未决：

1. **R-1 入口决策冲突**：既有 [KnowledgeBaseScreen.kt](../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt) 是底部导航一级 Tab（Mock 原型），项目零二级路由先例。任务说明曾称「设置页二级页面」，与现状冲突。
2. **R-2 PrismApplication 未暴露数据层依赖**：`KnowledgeBaseRepository` / `IngestionPipeline` / `DocumentParserRegistry` / `Chunker` / `Embedder` 均未在 PrismApplication 字段中暴露，ViewModel 无从注入。
3. **R-3 既有 KnowledgeBaseScreen 是纯 Mock 原型**：硬编码 `kbSpaces` / `recentDocs`，`ImportSheet` 全 `onClick = {}` 空实现，须替换数据源与交互逻辑。

主 Agent 已与用户确认 R-1 决策为「保留一级 Tab，改造 Mock→真实」。本 ADR 锁定 US-018 全部架构决策，作为编码前置。

## 决策（Decision）

### 5.1 导航入口：保留底部导航一级 Tab，改造既有 KnowledgeBaseScreen

**理由**：

- 既有 KnowledgeBaseScreen 已是 4 Tab 之一（[PrismApp.kt:59](../../app/src/main/java/io/prism/ui/PrismApp.kt)），用户已建立心智模型。
- 项目零二级路由先例，引入需新增 NavHost 嵌套图概念，破坏既有 4 Tab 简洁架构。
- 用户已明确选择（2026-08-07 对话）：「保留一级 Tab，改造 Mock→真实」。
- 改造路径：删除 Mock 数据类与硬编码列表，接入 ViewModel + StateFlow，复用 Bento 卡片视觉骨架。

### 5.2 依赖注入：PrismApplication 新增 5 个 `by lazy` 字段

```kotlin
// PrismApplication.kt 新增字段
val knowledgeBaseRepository: KnowledgeBaseRepository by lazy { KnowledgeBaseRepository(boxStore) }
val documentParserRegistry: DocumentParserRegistry by lazy { DocumentParserRegistry() }
val chunker: Chunker by lazy { Chunker(chunkSize = 512, overlap = 64) }
val embedder: Embedder by lazy {
    assets.open(EmbedderFactory.DEFAULT_MODEL_PATH).use { m ->
        assets.open(EmbedderFactory.DEFAULT_VOCAB_PATH).use { v ->
            EmbedderFactory.create(m, v)
        }
    }
}
val ingestionPipeline: IngestionPipeline by lazy {
    IngestionPipeline(documentParserRegistry, chunker, embedder, knowledgeBaseRepository)
}
```

**参数选型**：

- `Chunker(chunkSize = 512, overlap = 64)`：ADR-007 5.4 推荐 256–1024 token 范围，512 是中位默认值；overlap = chunkSize/8 ≈ 64，符合 RAG 最佳实践（保留上下文衔接又不过度冗余）。
- `Embedder` 经 `EmbedderFactory.create` 从 `assets/models/` 加载 ONNX 模型（既有资源，US-014 已落地）。
- 全部 `by lazy`：首次访问时初始化，避免 Application.onCreate 阻塞启动；embedder 加载耗时 ~200ms，仅在用户首次进入知识库 Tab 时发生。

**理由**：

- 与既有 `providerConfigRepository` / `mcpServerRepository` 等 `by lazy` 注入模式完全一致（考古报告 §4.1）。
- 无 Hilt/Dagger，沿用项目既有手动注入风格。
- `Embedder` 单例化：OnnxEmbedder 持久加载模型（~23MB 内存），不应每次实例化；HNSW 索引无状态，可安全跨协程复用（BR-concurrency-002 已规避）。

### 5.3 ViewModel 架构：UiState 模式 + IngestionEvent 收集

```kotlin
class KnowledgeBaseViewModel(
    private val repository: KnowledgeBaseRepository,
    private val pipeline: IngestionPipeline
) : ViewModel() {

    data class KnowledgeBaseUiState(
        val isLoading: Boolean = true,
        val libraries: List<KnowledgeBase> = emptyList(),
        val defaultKbChunkCount: Long = 0L,
        val chunkCounts: Map<Long, Long> = emptyMap(),
        val createLibraryError: String? = null,
        val deleteLibraryError: String? = null,
        val ingestionState: IngestionUiState = IngestionUiState.Idle
    )

    sealed interface IngestionUiState {
        data object Idle : IngestionUiState
        data class Running(val documentTitle: String, val embedded: Int, val total: Int, val skipped: Int) : IngestionUiState
        data class Completed(val documentTitle: String, val embedded: Int, val skipped: Int, val durationMs: Long) : IngestionUiState
        data class Failed(val documentTitle: String, val message: String) : IngestionUiState
    }
}
```

**理由**：

- UiState 单一数据类聚合所有 UI 状态，避免多 StateFlow 散落（[SettingsViewModel](../../app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt) / [CapabilitiesViewModel](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt) 既有模式）。
- `IngestionUiState` sealed interface 仿 `CapabilitiesViewModel.TestState`（连接测试状态机），表达 Idle/Running/Completed/Failed 四态。
- `IngestionEvent.ChunkEmbedded` / `ChunkSkipped` 累计映射到 `Running.embedded/skipped`；`Completed` 映射到 `Completed`；`Failed` 安全映射通用文案（见 5.5）。
- `chunkCounts: Map<Long, Long>` 缓存每个库的 chunk 计数，列表渲染时按 id 查找，避免重复查询。

### 5.4 文档导入：SAF OpenDocument + ContentResolver.openInputStream

```kotlin
// KnowledgeBaseScreen.kt
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri ->
    if (uri != null) {
        val fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: "document"
        viewModel.startIngestion(uri, fileName, targetKbId)
    }
}
PrismButton(text = "选择文件", onClick = {
    launcher.launch(SUPPORTED_MIME_TYPES)
})
```

**ViewModel 侧**：

```kotlin
fun startIngestion(uri: Uri, fileName: String, knowledgeBaseId: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: return@launch  // 安全降级：URI 失效时不崩溃
        pipeline.ingest(fileName, input, knowledgeBaseId)
            .collect { event -> mapEventToUiState(event, fileName) }
    }
}
```

**理由**：

- 沿用 [CapabilitiesScreen.kt:748](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) SAF 授权先例，改用 `OpenDocument`（选单文件）替代 `OpenDocumentTree`（选目录）。
- `OpenDocument` 选单文件**无需** `takePersistableUriPermission`：导入完即关流，一次性读取即可（考古报告 §6.5）。
- `contentResolver.openInputStream(uri)` 返回 InputStream，直接传给 `pipeline.ingest`（管线内 `input.use {}` 负责关闭，ADR-009 5.7）。
- `flowOn(Dispatchers.IO)` 或 `viewModelScope.launch(Dispatchers.IO)` collect：避免 OnnxEmbedder 阻塞主线程（R-4）。
- MIME 限制：`arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/plain", "text/markdown", "text/csv")`，覆盖 DocumentType 支持的 6 种格式（R-8）。

### 5.5 错误安全映射：Failed.throwable 仅供日志，UI 展示通用文案

```kotlin
private fun mapFailedToMessage(throwable: Throwable): String = when (throwable) {
    is DocumentParseException -> "文档格式不支持或已损坏"
    else -> "文档摄入失败，请检查文件或重试"
}
```

**理由**：

- [IngestionEvent.kt:60-67](../../app/src/main/java/io/prism/ingestion/IngestionEvent.kt) 明确安全约定：`throwable.message` / 堆栈禁止展示给用户。
- BR-error-handling-003：UI 不暴露内部路径/类名/堆栈。
- `DocumentParseException` 是已知致命错误（解析失败），可识别为「格式不支持」；其他异常统一为「摄入失败」。
- `throwable` 仅在 ViewModel 内部用 `Log.w` 记录结构化日志（不含密钥/路径），不写入 UI 状态。

### 5.6 默认库 UI 入口：列表顶部独立「默认库」卡

```kotlin
// KnowledgeBaseScreen.kt
item { DefaultKbCard(chunkCount = state.defaultKbChunkCount) }
items(state.libraries) { kb ->
    KbSpaceCard(kb, state.chunkCounts[kb.id] ?: 0L, ...)
}
```

**理由**：

- ADR-008 5.3：默认库 0L 不在 `knowledgeBases` Flow 中，UI 须单独处理入口。
- 默认库禁用删除/重命名按钮（`remove(0L)` 会抛 IllegalArgumentException，ADR-008 5.4）。
- 默认库 chunk 计数通过 `repository.chunkCount(0L)` 聚合，与自建库同模式。
- 默认库可被选为导入目标（用户不创建任何库时仍可导入文档）。

### 5.7 进度节流策略：单次摄入仅维护最新 Running 状态

**理由**：

- R-5：IngestionPipeline chunk 边界 emit（~100ms/次），大文档 chunk 多时 emit 频繁。
- ViewModel `MutableStateFlow<IngestionUiState>` 每次 emit 直接 `.value =` 赋值，自动 conflate（StateFlow 默认 conflate 最新值）。
- 无需额外 `sample()` / `conflate()` 操作符，StateFlow 语义足够（考古报告 §4.2 既有模式）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 改设置页二级页面（移除 Tab + 新增 NavHost 路由） | 任务说明原意 | 1) 项目零二级路由先例；2) 破坏既有 4 Tab 简洁架构；3) 用户已决策保留一级 Tab |
| 设置页内 Sheet 承载知识库列表（零路由变更） | 沿用项目 Sheet 模式 | 1) 设置页 Sheet 已用于 Provider 编辑，再加 KB 列表过于拥挤；2) KB 是一级功能不应埋在设置二级；3) 既有 Tab 已为知识库预留入口，浪费 |
| 使用 Hilt/Dagger 依赖注入 | 标准 Android DI | 1) 项目零 Hilt 先例；2) 引入新框架触发 P3 流程；3) `by lazy` 已满足需求 |
| Turbine 测试库 | StateFlow 测试更简洁 | 1) 项目未引入；2) 既有 `launch { collect }` 模式成熟；3) 引入新依赖触发 tech-selection |
| `collectAsStateWithLifecycle` | lifecycle-aware 订阅 | 1) 项目未引入 `lifecycle-runtime-compose`；2) 既有 4 个 ViewModel 均用 `collectAsState`；3) 保持一致性 |
| 直接 `box.remove` 删除库 | 简单 | 1) 跳过级联删除事务；2) 留下 chunk 残留；3) 违反 ADR-008 5.4 / BR-concurrency-001 |
| 文档选择用 `OpenDocumentTree` | 与 Filesystem MCP Server 一致 | 1) 选目录语义不符（用户想选单个文档）；2) 持久化 URI 权限无必要（一次性导入） |
| `Failed.throwable.message` 直接展示 | 调试方便 | 1) 违反 IngestionEvent 安全约定；2) 可能泄露内部路径/堆栈；3) 违反 BR-error-handling-003 |
| 文档级 runInTx 事务保证原子性 | 全成功或全回滚 | 1) ADR-009 5.5 已否决（嵌入昂贵，中途失败丢失已嵌入结果）；2) chunk 级独立 put 保留贵重结果 |
| 引入 Compose UI 测试（androidTest） | 验证 UI 交互 | 1) 项目零 instrumented 测试先例；2) 需新增 `androidTestImplementation` 依赖与 manifest；3) 本 US 用 JVM ViewModel 单测覆盖核心逻辑即可 |

## 后果（Consequences）

- 正面后果：
  - 既有 KnowledgeBaseScreen 视觉骨架（Bento 卡 / 渐变进度条 / ImportSheet 布局）得以复用，无需重做设计
  - ViewModel + UiState 模式与 SettingsViewModel/CapabilitiesViewModel 一致，认知负荷低
  - SAF OpenDocument 是 Android 标准文档选择方式，无需存储权限（R-9）
  - IngestionEvent 收集 + 状态映射让 UI 实时反映进度，符合 AC-3
  - 默认库独立入口处理清晰，与 ADR-008 5.3 虚拟库语义对齐
- 负面后果 / 代价：
  - PrismApplication 新增 5 个 lazy 字段，首次访问知识库 Tab 时有 ~200ms embedder 加载延迟（可接受）
  - ViewModel 持有 Application Context 引用（通过 Factory 注入），需注意生命周期（ViewModelScope 自动清理）
  - `chunkCounts` Map 在库数量多时可能增长，首期数据量小（4GB 低端机限制库容量），无需优化
  - 无 Compose UI 测试，UI 交互正确性依赖 ViewModel 单测 + 手动验证
- 需要同步更新的文档或代码：
  - `app/src/main/java/io/prism/PrismApplication.kt`：新增 5 个 lazy 字段
  - `app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseViewModel.kt`：新建
  - `app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt`：改造（删 Mock + 接入 VM）
  - `app/src/test/java/io/prism/ui/knowledge/KnowledgeBaseViewModelTest.kt`：新建
  - `docs/decisions/README.md` / `README.md`：ADR 索引同步
  - `prd.json`：US-018 `passes=true` + notes 补 ADR-011 引用

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| PrismApplication embedder 首次加载阻塞主线程 | 中 | `by lazy` 延迟初始化，仅在用户首次进入知识库 Tab 时触发；ViewModel 用 `Dispatchers.IO` collect 避免阻塞 |
| IngestionEvent 收集在主线程 collect | 高 | `viewModelScope.launch(Dispatchers.IO) { pipeline.ingest(...).collect {} }`，强制 IO 线程（R-4） |
| URI 失效导致 openInputStream 返回 null | 中 | ViewModel 内 `?: return@launch` 安全降级，不崩溃；UI 状态保持 Idle |
| 用户导入不支持的格式 | 低 | OpenDocument MIME 限制 + 选中后 DocumentType 二次校验；不支持的扩展名经 IngestionEvent.Failed → 「文档格式不支持」提示 |
| 默认库被误删 | 高 | UI 删除按钮对默认库禁用（`enabled = kb.id != DEFAULT_KB_ID`）；Repository 层 `require(id != 0L)` 二次防御 |
| 摄入过程中用户离开 Tab | 中 | `viewModelScope` 自动取消 collect；IngestionPipeline `ensureActive` 在 chunk 边界响应取消（ADR-009 5.6） |
| Failed.throwable 误展示给用户 | 高 | ViewModel `mapFailedToMessage` 按异常类型映射通用文案，禁止透传 `throwable.message`；guardrail-enforcer 安全审计专项检查 |
| chunkCounts Map 与列表不同步 | 低 | 每次库列表变化时同步刷新 chunkCounts（`viewModelScope.launch { refreshChunkCounts() }`） |
| 进度 emit 频繁导致 UI 重组开销 | 低 | StateFlow 默认 conflate 最新值；大文档 chunk 数多时自动节流 |

## 参考

- [US-018 知识库管理 UI 源码考古报告](../reports/2026-08-07-us018-kb-ui-archaeology.md)
- [ADR-008](ADR-008-m3-knowledgebase-model.md)：知识库分库数据模型 / 默认库虚拟语义 / 级联删除事务
- [ADR-009](ADR-009-m3-ingestion-pipeline.md)：摄入管线编排 / IngestionEvent 事件流 / 嵌入失败降级
- [ADR-010](ADR-010-m3-vector-retrieval.md)：向量检索（US-018 不直接调用，但 UI 须展示库内 chunk 数）
- [Android Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- [ActivityResultContracts.OpenDocument](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument)
- [Jetpack Compose StateFlow collectAsState](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- [BR-error-handling-003](../behavioral-rules.md)：UI 不暴露异常内部信息
- [BR-concurrency-002](../behavioral-rules.md)：OnnxEmbedder 持锁资源并发访问
