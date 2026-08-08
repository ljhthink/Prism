# 源码考古报告：US-018 知识库管理 UI 前置探查

> 从 `docs/templates/reports/archaeology-template.md` 复制新建，依 CLAUDE.md 第三节 3.1（简化版考古）。
> 由 code-archaeologist 子 Agent 生成，聚焦 US-018「知识库管理 UI」将集成的既有 UI 架构、设置页模式、ViewModel 注入、Repository/Pipeline 接口契约与可复用组件。

| 项目 | 内容 |
|---|---|
| 执行 Agent | code-archaeologist |
| 任务令牌 | TKN-US018-ARCH-001 |
| 考古日期 | 2026-08-07 |
| 考古目标 | US-018 知识库管理 UI：列表页 + 创建/删除 + 导入文档 + 进度 + 错误提示 |
| 考古模式 | 简化版（聚焦 UI 接入点 + 接口契约 + 可复用组件 + 风险清单） |
| 项目根 | d:\s0611\code\Prism |
| 主 Agent 自问盲区1 | US-018 任务说明称「导航入口已确定：设置页二级页面」，但既有 KnowledgeBaseScreen 是底部 Tab 一级页面——入口决策与现状冲突，需明确 |
| 主 Agent 自问盲区2 | PrismApplication 尚未暴露 KnowledgeBaseRepository / IngestionPipeline / DocumentParserRegistry / Chunker / Embedder，US-018 接入数据层前须先补齐 Application 注入 |

---

## 0. 核心结论速览

### Q1：US-018 入口是设置页二级页面还是底部 Tab 一级页面？

**现状与任务说明冲突，须主 Agent 在 ADR 中明确决策。**

- 现状：[KnowledgeBaseScreen.kt](../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt) 是底部导航 4 Tab 之一（「知识库」Tab），在 [PrismApp.kt:59](../../app/src/main/java/io/prism/ui/PrismApp.kt) `bottomNavItems` 中注册，路由 `PrismDestinations.KNOWLEDGE = "knowledge"`（[PrismApp.kt:51](../../app/src/main/java/io/prism/ui/PrismApp.kt)）。
- 任务说明：US-018「导航入口已确定：设置页二级页面」。
- ADR 现状：[ADR-008](../../docs/decisions/ADR-008-m3-knowledgebase-model.md) 第 49 行仅称 KnowledgeBaseScreen 是「UI Mock」，**未规定 US-018 最终入口**；[ADR-007](../../docs/decisions/ADR-007-m3-rag-tech-stack.md) 第 129 行只提「知识库 UI 模块」，未指定层级。
- 影响面：若改为设置页二级页面，需从 `bottomNavItems` 移除「知识库」Tab，在 [SettingsScreen.kt](../../app/src/main/java/io/prism/ui/settings/SettingsScreen.kt) 新增一行 `SetRow` 跳转，并在 NavHost 增加二级路由。NavHost 当前**仅支持 4 个一级路由**（[PrismApp.kt:99-108](../../app/src/main/java/io/prism/ui/PrismApp.kt)），无任何二级页面先例。

### Q2：数据层依赖是否已在 PrismApplication 暴露？

**否。US-018 须先在 PrismApplication 补齐 5 个依赖的暴露。**

[PrismApplication.kt](../../app/src/main/java/io/prism/PrismApplication.kt) 当前暴露：`boxStore` / `providerConfigRepository` / `apiKeyRepository` / `mcpServerRepository` / `mcpClientManager` / `mcpToolProviderDispatcher` / `safFileAccess` / `filesystemMcpServer` 等。**未暴露**：

- `KnowledgeBaseRepository`（US-015 已实现，[KnowledgeBaseRepository.kt:34](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)）
- `IngestionPipeline`（US-016 已实现，[IngestionPipeline.kt:59](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)）
- `DocumentParserRegistry`（US-012，[DocumentParserRegistry.kt:18](../../app/src/main/java/io/prism/document/DocumentParserRegistry.kt)）
- `Chunker`（US-013，[Chunker.kt:24](../../app/src/main/java/io/prism/document/Chunker.kt)）
- `Embedder` / `OnnxEmbedder`（US-014，需经 [EmbedderFactory.create](../../app/src/main/java/io/prism/embedding/EmbedderFactory.kt) 从 `assets` 加载模型）

US-018 ViewModel 须通过 `viewModelFactory` initializer 从 PrismApplication 注入上述依赖（仿 [SettingsViewModel.Factory](../../app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt) 第 119-124 行模式）。

### Q3：既有 KnowledgeBaseScreen 是可用的还是 Mock？

**纯 Mock 原型，US-018 须替换其数据源并补齐创建/删除/导入逻辑。**

[KnowledgeBaseScreen.kt](../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt) 第 58-97 行定义了私有 Mock 数据类 `KbSpace` / `RecentDoc` 与硬编码列表 `kbSpaces` / `recentDocs`，顶栏副标题「3 个库 · 128 文档 · 4,502 分片」是写死字符串（第 119 行）。`ImportSheet`（第 168 行）所有按钮 `onClick = {}` 空实现。该文件可作为**视觉骨架复用**（Bento 卡片、渐变进度条 `PrismIndexBar`），但数据层与交互逻辑须全部重写。

---

## 1. UI 架构大图景

### 1.1 入口与导航

- 入口 Activity：[MainActivity.kt:16](../../app/src/main/java/io/prism/MainActivity.kt)，`setContent { PrismTheme { PrismApp() } }`，`enableEdgeToEdge()`。
- 根 Composable：[PrismApp.kt:71](../../app/src/main/java/io/prism/ui/PrismApp.kt) `PrismApp()`。
- 导航方案：**Navigation Compose**（`androidx.navigation.compose`），`rememberNavController` + `NavHost`，4 个一级路由定义在 `PrismDestinations` object（[PrismApp.kt:49-54](../../app/src/main/java/io/prism/ui/PrismApp.kt)）。
- 底部导航：`PrismNavBar` + `PrismNavItem`（[PrismNavBar.kt](../../app/src/main/java/io/prism/ui/components/PrismNavBar.kt)），4 Tab：聊天 / 知识库 / 能力 / 设置。
- 导航策略：`popUpTo(startDestination) { saveState = true }` + `launchSingleTop = true` + `restoreState = true`（[PrismApp.kt:83-89](../../app/src/main/java/io/prism/ui/PrismApp.kt)），保留各 Tab 状态。
- **无二级路由先例**：当前 NavHost 仅 4 个 `composable(route)` 一级目的地，无 `navigation(startDestination) { composable(...) }` 嵌套图，也无带参数路由。US-018 若做设置页二级页面，需新增路由定义并处理返回栈。

### 1.2 UI 文件清单

```
app/src/main/java/io/prism/ui/
├── PrismApp.kt                          # 根 Composable + NavHost + 4 Tab + 工具确认宿主
├── capabilities/
│   ├── CapabilitiesScreen.kt            # 能力中枢（MCP/Skills/记忆，含 SAF 授权先例）
│   └── CapabilitiesViewModel.kt         # MCP Server 配置 VM（最佳参考）
├── chat/
│   ├── ConversationScreen.kt            # 聊天屏（流式 + 错误内联展示）
│   └── ConversationViewModel.kt         # 聊天 VM（Flow collect 模式参考）
├── components/                          # 可复用组件库（11 个）
│   ├── KnowledgeGraphEmptyState.kt      # Lottie 空态插画
│   ├── PrismAvatar.kt
│   ├── PrismButton.kt                   # Primary/Ghost/Danger 三变体
│   ├── PrismCard.kt                     # 实心卡片（v0.4 去玻璃）
│   ├── PrismField.kt                    # 表单字段（label/input/hint/secret/trailing）
│   ├── PrismGlassCard.kt                # 半透明玻璃卡（v0.2 旧，仍被聊天/Mock KB 屏使用）
│   ├── PrismNavBar.kt                   # 底部导航
│   ├── PrismSegmented.kt                # 分段选择器
│   ├── PrismSheet.kt                    # 底部弹层（L3 表面）
│   ├── PrismSheetHost.kt                # 弹层宿主（遮罩 + 上滑动效）
│   ├── PrismStatusDot.kt                # 状态点
│   ├── PrismSwitch.kt                   # 开关
│   ├── PrismTopBar.kt                   # 顶栏 + PrismTopBarAction
│   └── StarField.kt                     # 深空背景
├── conversationlist/ConversationListScreen.kt
├── knowledge/KnowledgeBaseScreen.kt     # 既有 Mock 原型（US-018 改造对象）
├── model/ChatMessage.kt
├── settings/
│   ├── SettingsScreen.kt                # 设置页（US-018 入口参考）
│   └── SettingsViewModel.kt             # 设置 VM（注入模式参考）
└── theme/
    ├── Color.kt                         # 深空玻璃色板（v0.4）
    ├── PrismTheme.kt                    # M3 darkColorScheme（深色专属）
    ├── Shape.kt
    └── Typography.kt
```

### 1.3 主题与 Material 版本

- Material 3（`androidx.compose.material3`，BOM 管理，[build.gradle.kts:80](../../app/build.gradle.kts)）。
- 主题：[PrismTheme.kt:19](../../app/src/main/java/io/prism/ui/theme/PrismTheme.kt) `PrismTheme(darkTheme = true)`，**深色专属**，`darkColorScheme` 恒为深空，不随系统明暗切换。
- 色板：[Color.kt](../../app/src/main/java/io/prism/ui/theme/Color.kt) 第 76-119 行「深空玻璃 v0.4」扩展色。US-018 须复用的语义色：
  - `PrismBg`（屏底）/ `PrismPanel`（L1 卡片）/ `PrismPanel2`（L2 浮层）/ `PrismPanel3`（L3 弹层）
  - `PrismIndigo`（品牌主色）/ `PrismCyan`（功能光·青）/ `PrismMint`（功能光·薄荷·成功）/ `PrismWarning`（琥珀·待处理）/ `PrismDanger`（玫红·错误）
  - `PrismText` / `PrismTextDim` / `PrismTextFaint`（三级文本）
  - `PrismLine` / `PrismLineStrong`（描边）
- **设计规范**：v0.4 实体化表面（去半透明玻璃），新代码应优先用 `PrismCard`（实心）而非 `PrismGlassCard`（旧 v0.2 半透明）。

---

## 2. 设置页模式（US-018 入口参考）

### 2.1 SettingsScreen 结构

[SettingsScreen.kt:68](../../app/src/main/java/io/prism/ui/settings/SettingsScreen.kt) 采用「`LazyColumn` + 分组 + 行」模式：

- 顶栏：`PrismTopBar(title = "设置", subtitle = "偏好 · 安全 · 设备")`（第 86 行）。
- 分组标题：私有 `SetSection(title)` Composable（第 487 行），`PrismTextDim` 12sp 小字。
- 设置行：私有 `SetRow(icon, iconColor, title, subtitle, trailing, onClick)` Composable（第 499 行），用 `PrismGlassCard` 包裹（注：v0.4 应改 `PrismCard`，但既有代码仍用 GlassCard），含图标盒 + 标题/副标题 + 可选尾随控件 + 可选点击。
- 弹层跳转：`var providerListVisible by remember { mutableStateOf(false) }` 状态驱动，`PrismSheetHost(visible = ..., onDismiss = ...) { Sheet() }`（第 169-188 行）。

**US-018 若选设置页二级页面入口**，可在「模型与端点」分组下新增一行 `SetRow(title = "知识库管理", onClick = { /* 导航到二级路由 */ })`。但当前 SettingsScreen 的弹层模式是「同页 Sheet」而非「跳转新页」，二级页面需新增 NavHost 路由。

### 2.2 Provider 配置详情页跳转模式

设置页内的 Provider 配置**不走二级路由**，而是用 `PrismSheetHost` 弹层：

- `providerListVisible` 控制 Provider 列表弹层（[SettingsScreen.kt:169](../../app/src/main/java/io/prism/ui/settings/SettingsScreen.kt)）。
- `selectedProvider != null` 控制详情编辑弹层（第 180 行），由 `viewModel.selectProvider(config)` 触发。
- 整个设置页 3 个弹层（Provider 列表 / Provider 详情 / API Key）均用 `PrismSheet` + `PrismSheetHost` 实现，无任何 `navController.navigate`。

**对比 CapabilitiesScreen**：[CapabilitiesScreen.kt:181](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) MCP Server 配置也用 `PrismSheetHost` 弹层，同样无二级路由。

**结论**：项目至今**无二级页面先例**，所有「详情编辑」均用底部弹层 `PrismSheet` 承载。US-018 若坚持「设置页二级页面」需破例新增路由；若沿用项目模式，可在设置页内用 Sheet 承载知识库列表 + 导入。主 Agent 须在 ADR 中决策。

---

## 3. 既有 KnowledgeBaseScreen 现状（Mock 原型）

[KnowledgeBaseScreen.kt](../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt) 关键点：

| 元素 | 位置 | 现状 | US-018 处理 |
|---|---|---|---|
| Mock 数据类 `KbSpace` | 第 58-66 行 | `name/docs/chunks/updated/indexed/citations/glow` | 删除，改用 `KnowledgeBase` 实体 + 运行时聚合 |
| Mock 列表 `kbSpaces` | 第 78-82 行 | 硬编码 3 条（工作/学习/个人） | 改用 `viewModel.knowledgeBases.collectAsState()` |
| Mock `RecentDoc` | 第 85-97 行 | 硬编码 2 条 + `progress: Int` | 删除或改用摄入进度状态 |
| 顶栏副标题 | 第 119 行 | 写死「3 个库 · 128 文档 · 4,502 分片」 | 改用运行时聚合（`kbRepository.chunkCount(id)`） |
| `ImportSheet` | 第 168-240 行 | 来源类型/目标库/路径/分片大小，全 `onClick = {}` | 接入 SAF 文件选择 + `IngestionPipeline.ingest` |
| `KbSpaceCard` | 第 259 行 | Bento 卡 + 光晕 + `StatBlock` | 视觉可复用，数据替换 |
| `PrismIndexBar` | 第 382 行 | 渐变流光进度条（自定义，非 M3） | 进度展示可复用，或改用 M3 `LinearProgressIndicator` |
| `SectionHeader` | 第 244 行 | 私有，与 CapabilitiesScreen 同名重复 | 可提取共享，或各自保留 |

**ADR-008 第 49 行明确**：Mock 的 `docs/chunks/updated/indexed/citations/glow` 属「视图层装饰，运行时计算即可满足」。ADR-008 第 137 行：「默认库不持久化，UI 层需特殊处理『虚拟默认库』入口（与 KnowledgeBase 表记录区分）」。

---

## 4. ViewModel 模式

### 4.1 注入模式（统一模式，3 个先例）

所有 ViewModel 通过 `viewModelFactory` initializer 从 `PrismApplication` 注入依赖，**无 Hilt/Dagger**：

```kotlin
// SettingsViewModel.kt:119
val Factory: ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
        SettingsViewModel(app.providerConfigRepository, app.apiKeyRepository)
    }
}
```

同样模式见 [ConversationViewModel.kt:120](../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt)、[CapabilitiesViewModel.kt:167](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt)。Composable 侧用 `viewModel(factory = XxxViewModel.Factory)`（[SettingsScreen.kt:69](../../app/src/main/java/io/prism/ui/settings/SettingsScreen.kt)）。

**US-018 须新建 `KnowledgeBaseViewModel`**，构造注入 `KnowledgeBaseRepository` + `IngestionPipeline`（+ 可能的 `Embedder` 用于 `checkAndUnload` 调度），Factory 从 PrismApplication 取依赖。

### 4.2 状态暴露模式

- **只读列表状态**：`val xxx: StateFlow<List<T>> = repository.xxx.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`（[SettingsViewModel.kt:40](../../app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt)、[CapabilitiesViewModel.kt:51](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt)）。
- **可变选中状态**：`private val _selected = MutableStateFlow<T?>(null)` + `val selected: StateFlow<T?> = _selected.asStateFlow()`（[SettingsViewModel.kt:47-49](../../app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt)）。
- **操作型状态**（如测试连接）：`sealed interface TestState` + `MutableStateFlow<TestState>`（[CapabilitiesViewModel.kt:59-68](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt)）。US-018 摄入进度可仿此用 sealed interface 表达 `Idle/Running(progress)/Completed(result)/Failed(msg)`。
- **CRUD 操作**：`fun save(config): Long` / `fun remove(id)` / `fun delete(config)` 等同步方法直接转发 Repository（[SettingsViewModel.kt:61-91](../../app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt)）。
- **协程启动**：`viewModelScope.launch { ... }` 包裹 suspend 调用（[SettingsViewModel.kt:100](../../app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt) `loadApiKey`）。

### 4.3 Composable 收集模式

统一用 `collectAsState()`（**未用** `collectAsStateWithLifecycle`）：

- `val providers by viewModel.providers.collectAsState()`（[SettingsScreen.kt:76](../../app/src/main/java/io/prism/ui/settings/SettingsScreen.kt)）。
- `val servers by viewModel.servers.collectAsState()`（[CapabilitiesScreen.kt:108](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt)）。
- Flow 状态观测：[CapabilitiesScreen.kt:236](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) `flow.collectAsState(initial = ...)`（含 `remember(key)` 控制重建）。

> 注：`collectAsStateWithLifecycle` 未在依赖中（需 `androidx.lifecycle:lifecycle-runtime-compose`），项目统一用 `collectAsState()`。US-018 应保持一致。

---

## 5. Repository / Pipeline 接口契约（UI 接入点）

### 5.1 KnowledgeBaseRepository（US-015，已实现）

[KnowledgeBaseRepository.kt:34](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)

```kotlin
class KnowledgeBaseRepository(boxStore: BoxStore) {
    val knowledgeBases: StateFlow<List<KnowledgeBase>>          // 第 41 行；自建库列表，不含默认库
    fun save(config: KnowledgeBase): Long                       // 第 53 行；id=0 新建，>0 更新，返回 id
    fun get(id: Long): KnowledgeBase?                           // 第 66 行；id=0 返回 null，id<0 抛异常
    fun getAll(): List<KnowledgeBase>                           // 第 77 行；按 createdAt 升序
    fun findByName(name: String): KnowledgeBase?                // 第 85 行
    fun remove(id: Long)                                        // 第 108 行；runInTx 级联删 chunk；id=0 抛异常
    fun removeAll()                                             // 第 140 行；仅自建库
    fun addChunk(chunk: KnowledgeChunk): Long                   // 第 175 行；US-016 写入入口
    fun chunkCount(id: Long): Long                              // 第 194 行；0L=默认库计数，<0 抛异常
    fun search(query: FloatArray, k: Int, knowledgeBaseId: Long?): List<RetrievalResult>  // 第 249 行；US-017
    companion object { const val DEFAULT_KB_ID: Long = 0L }     // 第 324 行；虚拟默认库
}
```

- `KnowledgeBase` 实体：[KnowledgeBase.kt:36](../../app/src/main/java/io/prism/data/KnowledgeBase.kt)，仅 `id/name/createdAt` 三字段，**无统计字段**（运行时聚合）。
- **UI 列表页**：订阅 `knowledgeBases` StateFlow；统计 `docs/chunks` 须调用 `chunkCount(id)` 逐库聚合（ADR-008 5.1）。
- **默认库语义**：`id=0L` 是虚拟默认库，不在 `knowledgeBases` Flow 中。UI 须单独处理「默认库」入口（ADR-008 5.3，第 81 行）。
- **删除约束**：`remove(0L)` 抛 `IllegalArgumentException`，UI 删除按钮须对默认库禁用。

### 5.2 IngestionPipeline（US-016，已实现）

[IngestionPipeline.kt:59](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)

```kotlin
class IngestionPipeline(
    parserRegistry: DocumentParserRegistry,
    chunker: Chunker,
    embedder: Embedder,
    repository: KnowledgeBaseRepository
) {
    fun ingest(
        fileName: String,
        input: InputStream,
        knowledgeBaseId: Long,
        documentTitle: String = defaultTitle(fileName)
    ): Flow<IngestionEvent>                                     // 第 85 行；非 suspend，返回事件流
}
```

- **`input` 由调用方打开**，管线内 `input.use {}` 关闭（[IngestionPipeline.kt:110](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt)）。
- **`knowledgeBaseId` 校验**：`<0` 抛 `IllegalArgumentException`（第 94-103 行）；`0L` 合法（默认库）。
- **非 suspend**：`ingest` 返回 `Flow`，调用方须在协程中 `collect`。所有内部 blocking 调用（解析/嵌入/写库）在 `flow {}` 内执行，**未显式 `withContext(Dispatchers.IO)`**——US-018 ViewModel collect 时应 `flowOn(Dispatchers.IO)` 或在 `viewModelScope.launch(Dispatchers.IO)` 中 collect，避免阻塞主线程。

### 5.3 IngestionEvent（事件流类型，**非** IngestionProgress）

> **任务说明称 `Flow<IngestionProgress>`，实际类型为 `Flow<IngestionEvent>`**（sealed class）。US-018 须按 `IngestionEvent` 映射 UI 状态。

[IngestionEvent.kt:30](../../app/src/main/java/io/prism/ingestion/IngestionEvent.kt)

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
```

- **进度计算**：`ChunkEmbedded.index / Chunked.totalChunks`（`Chunked` 事件先于 chunk 事件到达，携带 `totalChunks`）。
- **未建索引提示**（AC-4）：`ChunkSkipped` 携带 `reason`，UI 须累计并提示「N 个片段未建索引」（ADR-009 第 204、219 行）。
- **失败提示**（AC-4）：`Failed(throwable)`。**安全约定**（[IngestionEvent.kt:60-67](../../app/src/main/java/io/prism/ingestion/IngestionEvent.kt)）：`throwable` 仅供日志，**禁止直接展示 `throwable.message` 或堆栈**，须映射为通用安全文案（如「文档摄入失败，请检查文件格式或重试」），按异常类型区分（`DocumentParseException` → 「文档格式不支持」）。遵循 BR-error-handling-003。

### 5.4 IngestionResult（Completed 载荷）

[IngestionResult.kt:17](../../app/src/main/java/io/prism/ingestion/IngestionResult.kt)

```kotlin
data class IngestionResult(
    val totalChunks: Int, val embeddedChunks: Int, val skippedChunks: Int,
    val skippedDetails: List<SkippedChunk>, val knowledgeBaseId: Long,
    val documentTitle: String, val durationMs: Long
)
data class SkippedChunk(val index: Int, val title: String, val reason: String)
```

- `embedded + skipped == total` 不变式（第 28 行 `require`）。
- `skippedDetails` 可用于 UI 展示「未建索引片段明细」。

### 5.5 文档解析与格式支持

[DocumentType.kt:12](../../app/src/main/java/io/prism/document/DocumentType.kt) 支持 6 种格式：`PDF/DOCX/XLSX/MD/TXT/CSV`。不支持的扩展名抛 `DocumentParseException`（[DocumentParserRegistry.kt:29](../../app/src/main/java/io/prism/document/DocumentParserRegistry.kt)），会经 `IngestionEvent.Failed` 传递。US-018 文件选择器应限制这 6 种扩展名。

---

## 6. 可复用 Compose 组件清单

| 组件 | 路径 | 用途 | US-018 复用建议 |
|---|---|---|---|
| `PrismTopBar` / `PrismTopBarAction` | [PrismTopBar.kt](../../app/src/main/java/io/prism/ui/components/PrismTopBar.kt) | 顶栏 + 标题/副标题/操作钮 | 直接复用，副标题展示「N 个库 · M 文档」 |
| `PrismCard` | [PrismCard.kt](../../app/src/main/java/io/prism/ui/components/PrismCard.kt) | 实心卡片（v0.4 推荐），支持 `onClick` | 列表项卡片，优先于 GlassCard |
| `PrismGlassCard` | [PrismGlassCard.kt](../../app/src/main/java/io/prism/ui/components/PrismGlassCard.kt) | 半透明玻璃卡（v0.2 旧） | 既有 KB Mock 屏在用；v0.4 建议新代码用 PrismCard |
| `PrismButton` | [PrismButton.kt](../../app/src/main/java/io/prism/ui/components/PrismButton.kt) | `Primary`/`Ghost`/`Danger` 三变体，支持 `leadingIcon` | 创建库/删除库/开始导入按钮 |
| `PrismField` | [PrismField.kt](../../app/src/main/java/io/prism/ui/components/PrismField.kt) | 表单字段（label/value/hint/secret/trailing） | 新建库弹层的库名输入 |
| `PrismSegmented` | [PrismSegmented.kt](../../app/src/main/java/io/prism/ui/components/PrismSegmented.kt) | 分段选择器 | 目标库选择（既有 KB Mock 已用） |
| `PrismSheet` + `PrismSheetHost` | [PrismSheet.kt](../../app/src/main/java/io/prism/ui/components/PrismSheet.kt) / [PrismSheetHost.kt](../../app/src/main/java/io/prism/ui/components/PrismSheetHost.kt) | 底部弹层（L3）+ 遮罩 + 上滑动效 | 新建库/导入文档/删除确认弹层 |
| `PrismSwitch` | [PrismSwitch.kt](../../app/src/main/java/io/prism/ui/components/PrismSwitch.kt) | 开关 | 可选（如库启用/禁用） |
| `PrismStatusDot` / `PrismDotState` | [PrismStatusDot.kt](../../app/src/main/java/io/prism/ui/components/PrismStatusDot.kt) | 状态点 | 库索引状态指示 |
| `KnowledgeGraphEmptyState` | [KnowledgeGraphEmptyState.kt](../../app/src/main/java/io/prism/ui/components/KnowledgeGraphEmptyState.kt) | Lottie 空态插画（`assets/animations/prism_knowledge_graph.json`） | 无知识库时空态展示 |
| `PrismNavBar` / `PrismNavItem` | [PrismNavBar.kt](../../app/src/main/java/io/prism/ui/components/PrismNavBar.kt) | 底部导航 | 若保留一级 Tab 则不动；若改二级页面则移除「知识库」项 |

### 6.1 对话框模式

- **`AlertDialog`**：[PrismApp.kt:137](../../app/src/main/java/io/prism/ui/PrismApp.kt) `ToolConfirmationHost` 用 M3 `AlertDialog`（`containerColor = PrismPanel2`，按钮 `TextButton`）。US-018 删除确认对话框可仿此。
- **底部弹层 `PrismSheet`**：项目主流模式，所有「详情编辑」均用此（SettingsScreen / CapabilitiesScreen / 既有 KB Mock 屏 `ImportSheet`）。

### 6.2 进度条模式

- **M3 `CircularProgressIndicator`**：[CapabilitiesScreen.kt:307](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) `ConnectionStatusBadge` 用 `CircularProgressIndicator(size=12.dp, strokeWidth=1.5.dp)` 展示「连接中」。
- **M3 `LinearProgressIndicator`**：项目中**未使用**。
- **自定义渐变进度条 `PrismIndexBar`**：[KnowledgeBaseScreen.kt:382](../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt)（私有），`Brush.horizontalGradient(listOf(PrismIndigo, PrismCyan))`。US-018 摄入进度可复用此样式，或改用 M3 `LinearProgressIndicator` 统一规范。
- **进度百分比文本**：既有 Mock 屏第 371 行 `"${doc.progress}%"`，`PrismMint` 色。

### 6.3 错误提示模式

- **内联错误文本**：[SettingsScreen.kt:435](../../app/src/main/java/io/prism/ui/settings/SettingsScreen.kt) `ValidationError(text)` 私有 Composable，`PrismDanger` 11sp，`padding(top=6.dp)`。[CapabilitiesScreen.kt:571](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) 同名重复实现。
- **聊天错误内联**：[ConversationViewModel.kt:103](../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt) `appendDelta(aiId, "\n\n⚠️ ${event.message}")`，错误以文本内联到 AI 消息。
- **Snackbar / SnackbarHost**：项目**未使用** Snackbar（无 `SnackbarHostState` 先例）。US-018 错误提示应沿用内联 `ValidationError` 或弹层文本模式，保持风格一致。

### 6.4 空状态展示模式

- **文本空态**：[CapabilitiesScreen.kt:325](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) `EmptySection(text)`，`PrismTextFaint` 12sp。
- **Lottie 空态插画**：`KnowledgeGraphEmptyState`（见上表），加载 `assets/animations/prism_knowledge_graph.json`。
- **既有 KB Mock 屏**：无空态处理（Mock 数据恒非空）。US-018 须新增「无知识库」空态，可结合 `KnowledgeGraphEmptyState` + `EmptySection` + `PrismButton("创建知识库")`。

### 6.5 SAF 文件选择先例（关键参考）

[CapabilitiesScreen.kt:692](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) `FilesystemAuthorizationSection` 是 US-018 文件选择的**最佳参考**：

```kotlin
val launcher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocumentTree()      // 第 748 行；选目录
) { uri -> /* takePersistableUriPermission + registerFilesystemRoot */ }
PrismButton(text = "选择授权目录", onClick = { launcher.launch(null) })
```

- US-018 导入文档应改用 `ActivityResultContracts.OpenDocument()`（选单个文件）而非 `OpenDocumentTree()`（选目录）。
- `OpenDocument` 的 `input` 参数为 `Array<String>`（MIME 类型），可用 `arrayOf("application/pdf", "text/plain", ...)` 或 `arrayOf("*/*")` 配合扩展名二次过滤。
- 选中后用 `context.contentResolver.openInputStream(uri)` 打开流，传给 `IngestionPipeline.ingest`。文件名从 `DocumentFile.fromSingleUri(context, uri)?.name` 获取（`androidx.documentfile` 已在依赖中，[build.gradle.kts:95](../../app/build.gradle.kts)）。
- `OpenDocument` 选单个文件**无需** `takePersistableUriPermission`（一次性读取即可，导入完即关流）；若需支持后台重试可考虑持久化 URI。

---

## 7. 风险清单

| 风险 | 等级 | 证据 | 建议 |
|---|---|---|---|
| **R-1 入口决策冲突** | 高 | 任务说明「设置页二级页面」vs 既有 KnowledgeBaseScreen 一级 Tab（[PrismApp.kt:59](../../app/src/main/java/io/prism/ui/PrismApp.kt)）；项目无二级路由先例 | 主 Agent 在 ADR 中明确：(A) 保留一级 Tab，改造既有屏；(B) 改设置页二级页面，移除 Tab + 新增 NavHost 路由；(C) 设置页内 Sheet 承载（沿用项目模式，零路由变更）。建议 (A) 或 (C)，规避 NavHost 二级路由新概念 |
| **R-2 PrismApplication 未暴露数据层依赖** | 高 | [PrismApplication.kt](../../app/src/main/java/io/prism/PrismApplication.kt) 无 `knowledgeBaseRepository` / `ingestionPipeline` / `parserRegistry` / `chunker` / `embedder` 字段 | US-018 须在 PrismApplication 新增 5 个 `by lazy` 字段。`Embedder` 需经 [EmbedderFactory.create](../../app/src/main/java/io/prism/embedding/EmbedderFactory.kt) 从 `assets` 加载模型（`models/model_qint8_arm64.onnx` + `models/vocab.txt`，[EmbedderFactory.kt:26-27](../../app/src/main/java/io/prism/embedding/EmbedderFactory.kt)）。`Chunker` 构造参数 `chunkSize/overlap` 须定值（参考 ADR-007 5.4，4GB 低端机小批次） |
| **R-3 无 androidTest 环境** | 高 | `app/src/` 下只有 `main` 与 `test`，无 `androidTest` 目录；[build.gradle.kts](../../app/build.gradle.kts) 无 `androidTestImplementation` 依赖，无 `androidx.compose.ui:ui-test-junit4` | 若 ac-verifier 要求 Compose UI 测试，须从零搭建：新建 `app/src/androidTest/`、添加 `androidTestImplementation(libs.androidx.compose.ui.test.junit4)` + `androidTestImplementation(libs.androidx.compose.ui.test.manifest)` + `androidTestUtil(libs.androidx.test.orchestrator)`。项目至今 0 个 instrumented 测试，需评估是否本 US 引入 |
| **R-4 OnnxEmbedder 阻塞主线程风险** | 中 | [IngestionPipeline.ingest](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) 非 suspend，内部 `flow {}` 未 `flowOn(IO)`；`OnnxEmbedder.embed` 全程持锁 ~100ms（BR-concurrency-002） | US-018 ViewModel collect 时须 `flowOn(Dispatchers.IO)` 或在 `viewModelScope.launch(Dispatchers.IO)` 中 collect，禁止在主线程 collect |
| **R-5 摄入进度 Flow emit 频繁** | 低 | ADR-009 第 224 行「chunk 边界 emit（~100ms/次），US-018 UI 可节流」 | 大文档 chunk 多时 emit 频繁，UI 重组开销。可 `conflate()` 或 `sample(100.ms)` 节流，或在 ViewModel 用 `MutableStateFlow` 聚合最新进度 |
| **R-6 默认库 UI 入口处理** | 中 | ADR-008 5.3：默认库 0L 不在 `knowledgeBases` Flow 中；[KnowledgeBaseRepository.kt:110](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt) `remove(0L)` 抛异常 | US-018 列表页须单独展示「默认库」入口（用 `chunkCount(0L)` 聚合计数），且默认库禁用删除/重命名按钮 |
| **R-7 Failed.throwable 安全展示** | 中 | [IngestionEvent.kt:60-67](../../app/src/main/java/io/prism/ingestion/IngestionEvent.kt) 禁止展示 `throwable.message`/堆栈；BR-error-handling-003 | US-018 须按异常类型映射通用文案：`DocumentParseException` →「文档格式不支持或已损坏」；其他 →「文档摄入失败，请重试」。`throwable` 仅写日志（结构化日志，禁含路径/堆栈给用户） |
| **R-8 文件选择 MIME 限制** | 低 | [DocumentType.kt:12](../../app/src/main/java/io/prism/document/DocumentType.kt) 仅支持 PDF/DOCX/XLSX/MD/TXT/CSV；不支持的扩展名抛 `DocumentParseException` | `OpenDocument` launcher 用 `arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/plain", "text/markdown", "text/csv")`，或 `arrayOf("*/*")` + 选中后用 `DocumentType.fromFileName` 二次校验，不支持则提示 |
| **R-9 Manifest 无存储权限声明（无需）** | 低 | [AndroidManifest.xml:5-6](../../app/src/main/AndroidManifest.xml) 仅声明 `INTERNET` + `ACCESS_NETWORK_STATE` | SAF `OpenDocument` 选择文件**无需** `READ_EXTERNAL_STORAGE`（经 ContentResolver 走 SAF 授权）。US-018 **不应**新增存储权限。导入流由 `contentResolver.openInputStream(uri)` 获取 |
| **R-10 主题一致性约束** | 低 | [PrismTheme.kt](../../app/src/main/java/io/prism/ui/theme/PrismTheme.kt) 深色专属；v0.4 实心表面 | US-018 须用深空色板（`PrismBg/PrismPanel/PrismPanel2`），新组件优先 `PrismCard` 而非 `PrismGlassCard`；进度色用 `PrismIndigo→PrismCyan` 渐变或 `PrismMint`；错误用 `PrismDanger` |
| **R-11 KnowledgeGraphEmptyState 资源依赖** | 低 | [KnowledgeGraphEmptyState.kt:34](../../app/src/main/java/io/prism/ui/components/KnowledgeGraphEmptyState.kt) 加载 `assets/animations/prism_knowledge_graph.json` | 须确认 assets 文件存在（Lottie 依赖已在 [build.gradle.kts:86](../../app/build.gradle.kts) `libs.lottie.compose`）。若文件缺失，`rememberLottieComposition` 会失败但不崩溃（progress=0） |
| **R-12 类型命名误解（IngestionProgress vs IngestionEvent）** | 低 | 任务说明称 `Flow<IngestionProgress>`，实际为 `Flow<IngestionEvent>`（sealed class） | 主 Agent 编码时须用 `IngestionEvent` 类型名，勿臆造 `IngestionProgress`。进度值从 `ChunkEmbedded.index/total` 计算 |

---

## 8. 入门路径（主 Agent 实现 US-018 推荐阅读顺序）

### 8.1 第一阶段：决策与契约理解（编码前必读）

1. [prd.json](../../prd.json) 第 260-273 行：US-018 验收标准（5 条）。
2. [ADR-008](../../docs/decisions/ADR-008-m3-knowledgebase-model.md) 5.1/5.3：统计字段运行时聚合 + 默认库虚拟语义。
3. [ADR-009](../../docs/decisions/ADR-009-m3-ingestion-pipeline.md) 5.4/后果：嵌入失败降级 + US-018 UI 提示「N 个片段未建索引」。
4. 本报告 §0 三个核心结论（入口冲突 / Application 注入 / Mock 现状）——主 Agent 须先决策 R-1。

### 8.2 第二阶段：UI 架构与模式参考（编码前阅读）

5. [PrismApp.kt](../../app/src/main/java/io/prism/ui/PrismApp.kt)：导航结构与路由定义（理解 4 Tab + NavHost）。
6. [SettingsScreen.kt](../../app/src/main/java/io/prism/ui/settings/SettingsScreen.kt) + [SettingsViewModel.kt](../../app/src/main/java/io/prism/ui/settings/SettingsViewModel.kt)：设置页 `SetRow` + `PrismSheetHost` 弹层 + ViewModel 注入模式（US-018 入口/VM 最佳参考）。
7. [CapabilitiesScreen.kt](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt) + [CapabilitiesViewModel.kt](../../app/src/main/java/io/prism/ui/capabilities/CapabilitiesViewModel.kt)：列表 + 弹层 + 连接测试（`sealed interface TestState`）+ **SAF 文件选择先例**（`FilesystemAuthorizationSection` 第 692 行）——US-018 导入文档的最佳参考。
8. [KnowledgeBaseScreen.kt](../../app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt)：既有 Mock 原型，视觉骨架（`KbSpaceCard`/`PrismIndexBar`/`ImportSheet`）可复用，数据层须重写。

### 8.3 第三阶段：数据层契约（接入前阅读）

9. [KnowledgeBaseRepository.kt](../../app/src/main/java/io/prism/data/KnowledgeBaseRepository.kt)：CRUD + `knowledgeBases` StateFlow + `chunkCount` 聚合 + 默认库约束。
10. [IngestionPipeline.kt](../../app/src/main/java/io/prism/ingestion/IngestionPipeline.kt) + [IngestionEvent.kt](../../app/src/main/java/io/prism/ingestion/IngestionEvent.kt) + [IngestionResult.kt](../../app/src/main/java/io/prism/ingestion/IngestionResult.kt)：`ingest` 签名 + 事件序列 + `Failed.throwable` 安全约定。
11. [PrismApplication.kt](../../app/src/main/java/io/prism/PrismApplication.kt)：理解现有 `by lazy` 注入模式，US-018 须在此新增 5 个依赖暴露。
12. [EmbedderFactory.kt](../../app/src/main/java/io/prism/embedding/EmbedderFactory.kt) + [Embedder.kt](../../app/src/main/java/io/prism/embedding/Embedder.kt)：`Embedder` 从 assets 加载模型的方式（若 US-018 需在 Application 构造 Embedder）。

### 8.4 第四阶段：组件复用（编码时查阅）

13. [components/](../../app/src/main/java/io/prism/ui/components/) 全部组件：见本报告 §6 可复用清单。
14. [theme/Color.kt](../../app/src/main/java/io/prism/ui/theme/Color.kt)：深空色板语义色。

---

## 9. 结论与建议

本次简化版考古已**逐文件读取源码**确认 US-018 的全部接入点，**无任何推测**。三个核心结论：

1. **入口决策冲突（R-1）须主 Agent 在 ADR 中先决策**：保留一级 Tab / 改设置页二级页面 / 设置页内 Sheet，三选一。项目至今无二级路由先例，建议 (A) 保留一级 Tab 改造既有屏，或 (C) 设置页内 Sheet 承载，规避 NavHost 二级路由新概念。
2. **PrismApplication 须补齐 5 个依赖暴露（R-2）**：`KnowledgeBaseRepository` / `IngestionPipeline` / `DocumentParserRegistry` / `Chunker` / `Embedder`。`Embedder` 须经 `EmbedderFactory.create` 从 `assets/models/` 加载 ONNX 模型。
3. **既有 KnowledgeBaseScreen 是纯 Mock 原型**：视觉骨架（Bento 卡 / 渐变进度条 / ImportSheet 布局）可复用，数据层与交互逻辑须全部重写。Mock 的统计字段须改运行时聚合（`chunkCount(id)`），默认库（0L）须单独处理入口。

12 项风险中 R-1/R-2/R-3 为高危：R-1 入口决策须 ADR 明确；R-2 Application 注入是数据层接入前置；R-3 androidTest 环境从零搭建，须评估是否本 US 引入 Compose UI 测试。`IngestionEvent`（非任务说明的 `IngestionProgress`）须按 sealed class 映射 UI 状态，`Failed.throwable` 须安全映射通用文案（BR-error-handling-003）。
