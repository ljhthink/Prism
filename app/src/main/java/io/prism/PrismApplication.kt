package io.prism

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.objectbox.BoxStore
import io.prism.data.KnowledgeBaseRepository
import io.prism.data.McpServerRepository
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfigRepository
import io.prism.document.Chunker
import io.prism.document.DocumentParserRegistry
import io.prism.embedding.Embedder
import io.prism.embedding.EmbedderFactory
import io.prism.embedding.NullEmbedder
import io.prism.embedding.OnnxEmbedder
import io.prism.fs.FilesystemMcpServer
import io.prism.fs.FilesystemRootStore
import io.prism.fs.SafFileAccess
import io.prism.fs.UiConfirmationGate
import io.prism.ingestion.IngestionPipeline
import io.prism.network.LocalMcpToolProvider
import io.prism.network.McpClientManager
import io.prism.network.McpToolProvider
import io.prism.network.McpToolProviderDispatcher
import io.prism.network.OpenAICompatibleProvider
import io.prism.security.ApiKeyRepository
import io.prism.security.CryptoService
import io.prism.security.KeystoreCryptoService
import io.prism.crossapp.AppAvailabilityChecker
import io.prism.crossapp.AppLauncherBridge
import io.prism.crossapp.CrossAppLauncher
import io.prism.crossapp.CrossAppLocalToolExecutor
import io.prism.crossapp.SchemeRegistry
import io.prism.data.MemoryRepository
import io.prism.data.SkillRepository
import io.prism.data.UserProfileRepository
import io.prism.memory.ConversationSummarizer
import io.prism.memory.CrossSessionMemoryManager
import io.prism.memory.MemoryConfigRepository
import io.prism.memory.SlidingWindowMemoryManager
import io.prism.memory.UserProfileManager
import io.prism.skill.SkillRegistry
import io.prism.tier.PerformanceTier
import io.prism.tier.TierConfigRepository
import io.prism.tier.TierManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prism 应用入口 —— 初始化 ObjectBox 数据库与加密服务。
 *
 * 在 [onCreate] 中构建 [BoxStore] 单例，供全应用持久化模块使用
 * （知识库 / 记忆系统 / Provider 配置等）。
 * [cryptoService] 延迟初始化，首次访问时创建 Android Keystore 主密钥。
 * [providerConfigRepository] 延迟初始化，供 UI 层读取激活 Provider（ADR-002 4.5）。
 * [apiKeyRepository] 延迟初始化，供 UI 层安全地读写加密 API Key（ADR-003）。
 *
 * DataStore 必须是进程级单例 —— 通过顶层委托 [dataStore] 保证，避免多实例崩溃。
 */
class PrismApplication : Application() {

    lateinit var boxStore: BoxStore
        private set

    /** 加密服务（延迟初始化，首次访问时创建 Keystore 主密钥） */
    val cryptoService: CryptoService by lazy { KeystoreCryptoService(this) }

    /** Provider 配置仓库（延迟初始化，供 UI 读取激活 Provider） */
    val providerConfigRepository: ProviderConfigRepository by lazy { ProviderConfigRepository(boxStore) }

    /** API Key 加密存储仓库（DataStore 单例 + 加密服务） */
    val apiKeyRepository: ApiKeyRepository by lazy { ApiKeyRepository(dataStore, cryptoService) }

    /** HTTP 客户端（OkHttp engine + SSE 插件，供流式对话使用，ADR-004 4.1） */
    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            // expectSuccess=true：非 2xx 抛 ClientRequestException（4xx），
            // 使 OpenAICompatibleProvider 能区分 401 鉴权失败与其他 4xx（US-007 LOW 修复；
            // 默认 false 时 4xx 被 SSE 插件转成普通异常，落入通用网络错误分支）。
            expectSuccess = true
            install(SSE)
        }
    }

    /** OpenAI 兼容 Provider 流式请求（依赖 httpClient + apiKeyRepository） */
    val openAICompatibleProvider: OpenAICompatibleProvider by lazy {
        OpenAICompatibleProvider(httpClient, apiKeyRepository)
    }

    /** MCP Server 配置仓库（延迟初始化，供 UI 读取/管理 MCP Server 配置） */
    val mcpServerRepository: McpServerRepository by lazy { McpServerRepository(boxStore) }

    /** MCP Client 连接层（依赖 httpClient + apiKeyRepository，ADR-005 5.3） */
    val mcpClientManager: McpClientManager by lazy {
        McpClientManager(httpClient, apiKeyRepository)
    }

    /** 工具调用确认门禁（US-009，ADR-006 5.4）—— Server 与 UI 确认宿主共享 */
    val confirmationGate: UiConfirmationGate by lazy { UiConfirmationGate() }

    /**
     * 注册一个 SAF 授权目录（ADR-006 5.3）。
     *
     * 由 UI 在 `ACTION_OPEN_DOCUMENT_TREE` 返回后调用：将树 URI 注册到 [safFileAccess]，
     * 并异步持久化到 [filesystemRootStore]（重启后保留）。逻辑目录名从目录显示名派生并清洗
     * （拒绝 `/` 与控制字符，C5），与已有根目录冲突时追加序号去重。
     *
     * 注册与 [onCreate] 的持久化根加载经 [rootsMutex] 串行化（C3），避免异步加载覆盖新授权。
     *
     * @param treeUri 用户选择的 SAF 树 URI（调用方已 `takePersistableUriPermission`）
     */
    fun registerFilesystemRoot(treeUri: Uri) {
        appScope.launch {
            rootsMutex.withLock {
                val display = runCatching { DocumentFile.fromTreeUri(this@PrismApplication, treeUri)?.name }
                    .getOrNull()?.takeIf { it.isNotBlank() && it.all { c -> c != '/' && !c.isISOControl() } }
                    ?: "root"
                var name = display
                var i = 1
                val existing = safFileAccess.rootsFlow.value.keys
                while (existing.contains(name)) {
                    i++
                    name = "$display-$i"
                }
                safFileAccess.addRoot(name, treeUri)
                runCatching { filesystemRootStore.putRoot(name, treeUri.toString()) }
            }
        }
    }

    /**
     * 移除一个 SAF 授权目录（ADR-006 5.3 / S1）。
     *
     * 先取回该根目录对应的 SAF 树 URI，对称调用 [android.content.ContentResolver.releasePersistableUriPermission]
     * 释放系统级持久化 URI 授权（BR-security-004，权限对称释放，避免 CWE-270 权限残留），
     * 再从 [safFileAccess] 移除逻辑根并异步持久化取消。
     */
    fun removeFilesystemRoot(name: String) {
        appScope.launch {
            rootsMutex.withLock {
                val uri = safFileAccess.uriFor(name)
                if (uri != null) {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                }
                safFileAccess.removeRoot(name)
                runCatching { filesystemRootStore.removeRoot(name) }
            }
        }
    }

    /** 文件系统授权根目录持久化仓库（ADR-006 5.3）—— 重启后仍保留授权目录 */
    val filesystemRootStore: FilesystemRootStore by lazy {
        FilesystemRootStore(filesystemRootsDataStore)
    }

    /** SAF 文件访问层（授权根目录注册表，ADR-006 5.3）—— 由 UI 授权目录后 addRoot */
    val safFileAccess: SafFileAccess by lazy { SafFileAccess(this) }

    /** 内置 Filesystem MCP Server（US-009，ADR-006 5.5）—— SAF 访问层 + 确认门禁 */
    val filesystemMcpServer: FilesystemMcpServer by lazy {
        FilesystemMcpServer(safFileAccess, confirmationGate)
    }

    /** 本地 MCP 工具提供者（进程内桥接 Filesystem Server，ADR-006 5.5） */
    val localMcpToolProvider: LocalMcpToolProvider by lazy {
        LocalMcpToolProvider(filesystemMcpServer)
    }

    /** MCP 工具提供者路由（按 serverType 分发 LOCAL/REMOTE，ADR-006 5.6） */
    val mcpToolProviderDispatcher: McpToolProvider by lazy {
        McpToolProviderDispatcher(localMcpToolProvider, mcpClientManager)
    }

    /**
     * 知识库分库仓库（US-015，ADR-008）—— 管理 KnowledgeBase CRUD 与级联删除。
     *
     * US-018 知识库管理 UI 经 [io.prism.ui.knowledge.KnowledgeBaseViewModel] 注入使用。
     */
    val knowledgeBaseRepository: KnowledgeBaseRepository by lazy { KnowledgeBaseRepository(boxStore) }

    /**
     * 文档解析器注册表（US-012，ADR-007 5.3）—— 按扩展名分发到 PDF/Office/Plain 解析器。
     *
     * 无状态组件，可安全跨协程复用。
     */
    val documentParserRegistry: DocumentParserRegistry by lazy { DocumentParserRegistry() }

    /**
     * 文本切片器（US-013，ADR-007 5.4）—— 段落边界优先 + overlap。
     *
     * 参数选型（ADR-011 5.2）：chunkSize=512（256–1024 中位默认），overlap=64（chunkSize/8，符合 RAG 最佳实践）。
     * 无状态组件，可安全跨协程复用。
     */
    val chunker: Chunker by lazy { Chunker(chunkSize = DEFAULT_CHUNK_SIZE, overlap = DEFAULT_CHUNK_OVERLAP) }

    /**
     * 设备档位配置仓库（US-040，ADR-017 4.4）—— 持久化用户手动覆盖的档位偏好。
     *
     * 使用独立 DataStore（`prism_tier_config`），与 API Key / 文件系统根目录 / 记忆配置 DataStore 隔离。
     * 默认 AUTO（使用 RAM 检测结果），用户可在设置中覆盖为四档之一。
     *
     * 由 [tierManager] 在 [onCreate] 中 `runBlocking` 一次性读取覆盖值，
     * 由 [io.prism.ui.settings.TierViewModel]（US-042）写入用户覆盖。
     */
    val tierConfigRepository: TierConfigRepository by lazy { TierConfigRepository(tierConfigDataStore) }

    /**
     * 设备档位管理器（US-040，ADR-017 4.1）—— RAM 检测 + 用户覆盖解析 + 当前档位暴露。
     *
     * **必须在 [onCreate] 中 [initialize]**，在任何 `by lazy` 注入访问 [TierManager.currentTier] 之前。
     * 由于 `by lazy` 首次访问发生在 ViewModel 构造时（远晚于 onCreate），此约束天然满足。
     *
     * **初始化**：[onCreate] 中同步检测 RAM（`ActivityManager.MemoryInfo.totalMem`）+ `runBlocking`
     * 读取 [tierConfigRepository] 的用户覆盖值，解析最终生效档位缓存到内存。
     *
     * **覆盖生效**（ADR-017 4.4）：用户修改覆盖后需重启 App 生效。[currentTier] 在 [initialize]
     * 后不可变，UI 修改覆盖仅持久化到 DataStore，下次启动时读取新值。
     *
     * 由 [embedder] / [crossSessionMemoryManager] 等 `by lazy` 注入读取 [currentTier] 决定加载策略，
     * 由 [io.prism.ui.chat.ConversationViewModel.Factory] 读取 [currentTier] 决定降级传参。
     */
    lateinit var tierManager: TierManager
        private set

    /**
     * 端侧嵌入引擎（US-014，ADR-007 5.2）—— onnxruntime-android 加载 all-MiniLM-L6-v2 INT8 模型。
     *
     * **M7 档位感知**（ADR-017 4.5）：
     * - FULL / STANDARD 档：经 [EmbedderFactory.create] 从 `assets/models/` 加载 ONNX 模型（~23MB）与 BERT 词表（~226KB）
     * - MINIMAL / CHAT_ONLY 档：返回 [NullEmbedder]，不加载模型，节省 ~23MB 内存
     *
     * 首次访问时延迟初始化，加载耗时 ~200ms（FULL/STANDARD 档），仅在用户首次进入知识库 Tab 时发生。
     *
     * **生命周期**（BR-concurrency-002）：OnnxEmbedder 全程持锁，单例化复用避免重复加载模型；
     * 协程取消时单次 embed ~100ms 不可中断，最坏延迟可接受。
     *
     * **闲置卸载**（ADR-017 4.5）：FULL 档 5min 闲置后 `session.close()`，STANDARD 档 2min。
     * 由 [startEmbedderUnloadScheduler] 在 [appScope] 中定时调用 [OnnxEmbedder.checkAndUnload]。
     */
    val embedder: Embedder by lazy {
        if (tierManager.currentTier.isEmbedderEnabled) {
            assets.open(EmbedderFactory.DEFAULT_MODEL_PATH).use { modelInput ->
                assets.open(EmbedderFactory.DEFAULT_VOCAB_PATH).use { vocabInput ->
                    EmbedderFactory.create(modelInput, vocabInput)
                }
            }
        } else {
            NullEmbedder()
        }
    }

    /**
     * 摄入管线（US-016，ADR-009）—— 串联 解析→切片→嵌入→写入 全链路。
     *
     * 由 [io.prism.ui.knowledge.KnowledgeBaseViewModel] 调用 `ingest()` 触发文档导入，
     * 返回 `Flow<IngestionEvent>` 供 ViewModel collect 观察进度。
     */
    val ingestionPipeline: IngestionPipeline by lazy {
        IngestionPipeline(documentParserRegistry, chunker, embedder, knowledgeBaseRepository)
    }

    /**
     * Skill 配置仓库（US-020，ADR-013 5.1）—— 管理 [io.prism.data.SkillConfig] 的 CRUD。
     *
     * 仿 [McpServerRepository] 模式，提供 [kotlinx.coroutines.flow.StateFlow] 供 UI 订阅。
     * Skill 允许多个并存启用（不需要单激活不变式），每个 Skill 独立 [io.prism.data.SkillConfig.isEnabled]。
     */
    val skillRepository: SkillRepository by lazy { SkillRepository(boxStore) }

    /**
     * Skill 注册中心（US-022，ADR-013 5.3）—— 扫描加载源 + 同步仓库 + 暴露已加载 Skill。
     *
     * 在 [onCreate] 中触发 [SkillRegistry.scanAndSync]（IO 协程，不阻塞 UI）：
     * 1. 扫描 `assets/skills/builtin/` 内置预设
     * 2. 扫描 `filesDir/skills/user/` 用户自建
     * 3. 扫描 `filesDir/skills/remote/` 远程下载
     * 4. 同步到 [skillRepository]（新增/更新/标记缺失）
     * 5. 刷新 [SkillRegistry.skills] StateFlow
     *
     * 单个 Skill 解析失败不影响其他 Skill（隔离失败，记录日志）。
     */
    val skillRegistry: SkillRegistry by lazy { SkillRegistry(this, skillRepository) }

    /**
     * Skill 执行记录仓库（US-029，ADR-013 5.7）—— 管理 [io.prism.data.SkillExecutionRecord] 的 CRUD。
     *
     * 持久化 Skill 执行历史（startedAt/finishedAt/status/toolCalls/errorMessage），
     * 供 Skill 详情页展示最近 10 次执行记录，跨会话审计。
     *
     * 依赖 [boxStore]（ObjectBox 单例）。无 Android Context 依赖（BR-testing-004 可测性）。
     */
    val skillExecutionRepository: io.prism.data.SkillExecutionRepository by lazy {
        io.prism.data.SkillExecutionRepository(boxStore)
    }

    /**
     * Skill 工具执行器（US-025，ADR-014 5.4）—— 编排「LLM 调工具 → 用户确认 → MCP 调用 → 结果回灌」回路。
     *
     * 依赖 [mcpToolProviderDispatcher]（接口 McpToolProvider 实现）+ [confirmationGate]（用户确认门禁）
     * + [skillExecutionRepository]（US-029 执行可观测，记录 [io.prism.data.SkillExecutionRecord]），
     * 不依赖 SkillRepository/SkillRegistry（tools + mcpServers 由调用方 Phase D ConversationViewModel 传入，
     * per phaseC 考古报告 R8：单一职责）。
     *
     * **安全边界**（ADR-014 5.5）：
     * - 用户确认：每个 tool_call 执行前通过 [confirmationGate]
     * - 超时防护：单次 callTool 包装 withTimeout（默认 30s）
     * - 循环防护：maxRounds=10 强制终止
     * - 失败降级：错误/超时/拒绝信息回灌给 LLM
     * - 命名空间隔离：tool name 格式 `skillName__toolName`，执行时去前缀
     *
     * **US-029 执行可观测**：[executeLoop] 在 finally 块持久化 [io.prism.data.SkillExecutionRecord]，
     * 仅当调用方传入 skillConfigId + skillName 时记录（详情见 [io.prism.skill.SkillExecutor]）。
     *
     * 由 [io.prism.ui.chat.ConversationViewModel] 在 Phase D（US-026）注入使用。
     */
    val skillExecutor: io.prism.skill.SkillExecutor by lazy {
        io.prism.skill.SkillExecutor(
            mcpToolProvider = mcpToolProviderDispatcher,
            confirmationGate = confirmationGate,
            skillExecutionRepository = skillExecutionRepository,
            localToolExecutor = crossAppLocalToolExecutor
        )
    }

    /**
     * 跨 App 调用兼容性清单仓库（M6，ADR-016 5.3）—— 从 `assets/cross-app/app_schemes.json` 加载。
     *
     * 首次访问时通过 `assets.open(...)` 同步加载（约 <1ms，JSON 文件 < 4KB），
     * 失败降级为空清单（跨 App 工具调用将返回 "无可用 App 配置"，不阻断应用启动）。
     *
     * **延迟初始化选择**：不用 [onCreate] 异步加载，原因——SchemeRegistry 仅在用户首次
     * 触发跨 App 工具调用时被 [crossAppLauncher] 访问，懒加载避免应用启动时无谓 IO。
     *
     * 由 [crossAppLauncher] / [crossAppLocalToolExecutor] 注入使用。
     */
    val schemeRegistry: SchemeRegistry by lazy {
        runCatching {
            assets.open(SchemeRegistry.DEFAULT_CONFIG_PATH).use { SchemeRegistry.load(it) }
        }.getOrElse { e ->
            android.util.Log.w("PrismApplication", "load schemeRegistry failed: ${e::class.simpleName}")
            SchemeRegistry.empty()
        }
    }

    /**
     * 跨 App 安装状态检测器（M6，ADR-016 5.3）—— 通过 PackageManager 检测目标 App 是否安装。
     *
     * **Android 11+ 包可见性**：依赖 AndroidManifest 的 `<queries>` 声明保证目标 App 可见
     * （不声明 QUERY_ALL_PACKAGES，遵循 Google Play 政策）。
     */
    val appAvailabilityChecker: AppAvailabilityChecker by lazy {
        AppAvailabilityChecker.fromContext(this)
    }

    /**
     * Activity 上下文桥接器（M6，ADR-016 R2 缓解）—— SharedFlow + CompletableDeferred 桥接模式。
     *
     * 由 [crossAppLauncher] 注入使用；UI 层（ConversationScreen）收集 [AppLauncherBridge.requests]
     * 流发起 `launcher.launch(intent)`，回调结果回灌 `bridge.respond(id, result)`。
     *
     * **生命周期**：[ConversationScreen] 在 `DisposableEffect` 中调用 [CrossAppLauncher.cancelAll]
     * （委托至 [AppLauncherBridge.cancelAll]），避免 Activity 销毁时 deferred 泄漏。
     */
    val appLauncherBridge: AppLauncherBridge by lazy { AppLauncherBridge() }

    /**
     * 跨 App 调用核心入口（M6，ADR-016 5.3）—— 组合 SchemeRegistry + AppAvailabilityChecker + AppLauncherBridge。
     *
     * 提供三个核心能力：
     * 1. [CrossAppLauncher.launchApp]：Deep Link 跳转
     * 2. [CrossAppLauncher.shareContent]：Share Sheet 分享
     * 3. [CrossAppLauncher.pickMedia]：系统 Picker 选取
     *
     * 由 [crossAppLocalToolExecutor] / ConversationViewModel.Factory 注入使用。
     */
    val crossAppLauncher: CrossAppLauncher by lazy {
        CrossAppLauncher(schemeRegistry, appAvailabilityChecker, appLauncherBridge)
    }

    /**
     * 跨 App 调用本地工具执行器（M6 Phase B，ADR-016 5.4）—— 实现 LocalToolExecutor 接口。
     *
     * 注册 `cross_app__open_app` / `cross_app__share_content` / `cross_app__pick_media` 三个工具，
     * 由 [skillExecutor] 的本地工具分支调用（[io.prism.skill.SkillExecutor.executeToolCall] 中
     * `localToolExecutor.handles(toolName)` 命中时走本地路径，否则走 MCP）。
     *
     * **DEF-01 修复**（M6 Phase B ac-verifier 验收报告）：
     * 此 lazy 声明 + [skillExecutor] 构造传入 `localToolExecutor = crossAppLocalToolExecutor`
     * 保证生产环境中本地工具分支可被触发（Phase B 编译通过但未注入的缺陷已修复）。
     */
    val crossAppLocalToolExecutor: CrossAppLocalToolExecutor by lazy {
        CrossAppLocalToolExecutor(crossAppLauncher)
    }

    /**
     * 远程 Skill 下载专用 HTTP 客户端（US-028，ADR-013 5.6，P2-03 修复）。
     *
     * **与共享 [httpClient] 的差异**：
     * - `install(HttpTimeout)`：启用请求级超时配置（共享 client 未安装此插件，`timeout {}` DSL 为 no-op）
     * - 不安装 SSE 插件（下载非流式对话，无需 SSE）
     *
     * **P2-03 重定向降级防护说明**：
     * Ktor 3.x 的 `HttpRedirect` 插件默认 `allowHttpsDowngrade=false`，自动拦截 https→http 降级重定向
     * （降级请求不会发出，返回 3xx 响应）。此为 P2-03 的主防护层。
     * [io.prism.skill.SkillDownloader.downloadToTmp] 中的最终 URL 协议校验为纵深防御兜底。
     *
     * 仍保留 `expectSuccess = true`（非 2xx 抛异常）。
     */
    val downloadHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(HttpTimeout)
        }
    }

    /**
     * 远程 Skill 下载器（US-028，ADR-013 5.6）—— HTTPS 下载 + 多层安全校验 + 原子安装。
     *
     * 依赖 [downloadHttpClient]（专用下载 client，HttpTimeout 插件已安装），
     * 不依赖 Context（`remoteSkillsDir` 由调用方 [io.prism.ui.capabilities.SkillsViewModel] 注入，
     * BR-testing-004 可测性）。
     *
     * **安全策略**（用户决策「标准校验」）：
     * - URL 协议白名单（仅 https）
     * - 重定向降级防护（最终 URL 协议二次校验，P2-03，CWE-918）
     * - Content-Length + 流式计数双校验（≤10MB）
     * - Content-Type 白名单
     * - ZIP slip 防护 + 总解压大小限制（≤50MB）+ 条目数限制（≤1000）
     * - YAML 沙箱解析（BR-security-004）
     * - 原子安装（backup-then-swap 模式）
     * - 30s 下载超时
     *
     * 由 [io.prism.ui.capabilities.SkillsViewModel] 在 Phase E（US-028）注入使用。
     */
    val skillDownloader: io.prism.skill.SkillDownloader by lazy {
        io.prism.skill.SkillDownloader(downloadHttpClient)
    }

    /**
     * 跨会话记忆仓库（US-030，ADR-015 5.1）—— 管理 [io.prism.data.MemoryRecord] 的 CRUD + 向量检索。
     *
     * M5 三层记忆系统 L2 层持久化层。复用 M3 ObjectBox HNSW 向量索引基建（KnowledgeChunk 模式）。
     * 由 [io.prism.memory.CrossSessionMemoryManager]（US-033）在会话结束时调用 save 持久化对话片段向量，
     * 在新会话首条消息时调用 searchByVector 检索 top-k 相关历史。
     *
     * 依赖 [boxStore]（ObjectBox 单例）。无 Android Context 依赖（BR-testing-004 可测性）。
     */
    val memoryRepository: MemoryRepository by lazy { MemoryRepository(boxStore) }

    /**
     * 用户画像仓库（US-031，ADR-015 5.2）—— 管理 [io.prism.data.UserProfile] 的 CRUD + upsert 唯一约束。
     *
     * M5 三层记忆系统 L3 层持久化层。存储用户偏好（显式用户设定 + 隐式 LLM 抽取）。
     * 由 [io.prism.memory.UserProfileManager]（US-034）调用 save 持久化偏好，
     * 由 [io.prism.ui.chat.ConversationViewModel] 在新会话时加载画像注入 systemPrompt 第三段。
     *
     * 依赖 [boxStore]（ObjectBox 单例）。无 Android Context 依赖（BR-testing-004 可测性）。
     */
    val userProfileRepository: UserProfileRepository by lazy { UserProfileRepository(boxStore) }

    /**
     * 记忆系统配置仓库（US-032，ADR-015 5.3）—— 持久化 L1 滑动窗口大小 N。
     *
     * 使用独立 DataStore（`prism_memory_config`），与 API Key / 文件系统根目录 DataStore 隔离。
     * 默认 N=10，可通过 [io.prism.memory.MemoryConfigRepository.setWindowSize] 运行时修改。
     *
     * 由 [slidingWindowMemoryManager] 读取 N 值，由记忆管理 UI（US-036）写入 N 值。
     */
    val memoryConfigRepository: MemoryConfigRepository by lazy { MemoryConfigRepository(memoryConfigDataStore) }

    /**
     * 对话摘要生成器（US-032，ADR-015 5.3）—— 使用 LLM 非流式请求对旧消息生成摘要。
     *
     * 依赖 [openAICompatibleProvider]（已实现 [io.prism.network.ChatCompletionProvider] 接口）。
     * [ProviderConfig] 在调用 [io.prism.memory.ConversationSummarizer.summarize] 时由调用方传入
     * （支持运行时切换 Provider）。
     *
     * 由 [slidingWindowMemoryManager] 在超出滑动窗口时调用生成旧消息摘要。
     */
    val conversationSummarizer: ConversationSummarizer by lazy {
        ConversationSummarizer(openAICompatibleProvider)
    }

    /**
     * L1 会话内滑动窗口记忆管理器（US-032，ADR-015 5.3）—— 保留最近 N 轮原始消息 + 摘要压缩。
     *
     * 依赖 [conversationSummarizer] + [memoryConfigRepository]。
     * 由 [io.prism.ui.chat.ConversationViewModel]（US-035）在 sendMessage 时调用
     * [io.prism.memory.SlidingWindowMemoryManager.processMessages] 管理上下文窗口。
     */
    val slidingWindowMemoryManager: SlidingWindowMemoryManager by lazy {
        SlidingWindowMemoryManager(conversationSummarizer, memoryConfigRepository)
    }

    /**
     * L2 跨会话记忆管理器（US-033，ADR-015 5.4）—— 向量化存储 + top-k 检索 + 防污染。
     *
     * 依赖 [embedder]（M3 OnnxEmbedder，384 维向量）+ [memoryRepository]（Phase A 已实现）。
     *
     * **职责**：
     * - 会话结束时 [io.prism.memory.CrossSessionMemoryManager.saveSessionMemories] 向量化存储关键对话
     * - 新会话开始时 [io.prism.memory.CrossSessionMemoryManager.retrieveRelevantMemories] top-k 检索相关历史
     * - [io.prism.memory.CrossSessionMemoryManager.formatMemoriesAsContext] 格式化为 systemPrompt section
     *
     * 由 [io.prism.ui.chat.ConversationViewModel]（US-035）在会话结束时和首条消息时调用。
     */
    val crossSessionMemoryManager: CrossSessionMemoryManager by lazy {
        CrossSessionMemoryManager(embedder, memoryRepository)
    }

    /**
     * L3 用户画像管理器（US-034，ADR-015 5.5）—— 显式偏好设定 + 隐式偏好 LLM 抽取 + 画像注入。
     *
     * 依赖 [openAICompatibleProvider]（已实现 [io.prism.network.ChatCompletionProvider] 接口，
     * Phase B 已扩展非流式 chatCompletion 方法）+ [userProfileRepository]（Phase A 已实现）。
     *
     * **职责**：
     * - 显式偏好 [io.prism.memory.UserProfileManager.setExplicitPreference]（用户 UI 设定）
     * - 隐式偏好 [io.prism.memory.UserProfileManager.extractImplicitPreferences]（LLM 抽取）
     * - 画像注入 [io.prism.memory.UserProfileManager.formatProfilesAsContext]（systemPrompt section）
     *
     * 由 [io.prism.ui.chat.ConversationViewModel]（US-035）在会话结束时抽取隐式偏好，
     * 在新会话时加载画像注入 systemPrompt 第三段。由记忆管理 UI（US-036）调用显式设定/查看/删除。
     */
    val userProfileManager: UserProfileManager by lazy {
        UserProfileManager(openAICompatibleProvider, userProfileRepository)
    }

    override fun onCreate() {
        super.onCreate()
        boxStore = MyObjectBox.builder()
            .androidContext(this)
            .build()
        // M7 设备适配（ADR-017 4.1）：必须在所有 by lazy 注入访问 currentTier 之前同步初始化
        // 内部通过 runBlocking 一次性读取 DataStore 覆盖值（<50ms），缓存到内存供后续注入读取
        tierManager = TierManager(
            tierDetector = TierManager.DefaultTierDetector(this),
            overrideReader = TierManager.DefaultOverrideReader(tierConfigRepository)
        ).also { it.initialize() }
        android.util.Log.i(
            "PrismApplication",
            "M7 tier initialized: detected=${tierManager.detectedTier} " +
                "override=${tierManager.currentOverride} current=${tierManager.currentTier} " +
                "ramBytes=${tierManager.totalRamBytes}"
        )
        // M7 嵌入闲置卸载调度（ADR-017 4.5）：FULL 档 5min / STANDARD 档 2min 闲置后 session.close()
        // MINIMAL/CHAT_ONLY 档不加载 embedder（NullEmbedder），checkIntervalMs=0 跳过调度
        startEmbedderUnloadScheduler()
        // 异步加载持久化授权根目录到 SAF 访问层（ADR-006 5.3），与 registerFilesystemRoot 经
        // rootsMutex 串行化（C3），失败静默（容错，不阻断启动）
        appScope.launch {
            rootsMutex.withLock {
                runCatching {
                    filesystemRootStore.loadRoots().forEach { (name, uri) ->
                        safFileAccess.addRoot(name, Uri.parse(uri))
                    }
                }
            }
        }
        // M4 Skills（ADR-013 5.3）：启动时扫描加载源并同步 SkillConfig 表
        // 失败不阻断启动（容错），单个 Skill 解析失败已在 SkillRegistry 内隔离
        // G-01 修复（BR-error-handling-007）：显式 try-catch，CancellationException 必须重抛，
        // 避免破坏结构化并发的取消传播（appScope 实际不会被取消，但规则合规性要求）
        appScope.launch {
            try {
                skillRegistry.scanAndSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("PrismApplication", "Skill scanAndSync failed", e)
            }
        }
    }

    /**
     * M7 嵌入闲置卸载调度器（ADR-017 4.5）。
     *
     * 在 [appScope] 中启动协程循环，按 [PerformanceTier.checkIntervalMs] 间隔调用
     * [OnnxEmbedder.checkAndUnload]，闲置超 [PerformanceTier.embedderIdleThresholdMs] 则
     * `session.close()` 释放 ~23MB 模型内存，下次 `embed()` 时自动重新加载。
     *
     * **档位感知**：
     * - FULL 档：60s 间隔检查，5min 闲置卸载
     * - STANDARD 档：30s 间隔检查，2min 闲置卸载
     * - MINIMAL / CHAT_ONLY 档：checkIntervalMs=0，直接跳过（NullEmbedder 无资源可卸载）
     *
     * **激活时机**：embedder 首次被 `by lazy` 访问后才会真正触发卸载（之前 session 未加载），
     * checkAndUnload 内部判断 session==null 时返回 false，无副作用。
     */
    private fun startEmbedderUnloadScheduler() {
        val tier = tierManager.currentTier
        val intervalMs = tier.checkIntervalMs
        if (intervalMs <= 0L) return  // MINIMAL/CHAT_ONLY 档不调度
        val idleThresholdMs = tier.embedderIdleThresholdMs
        appScope.launch {
            while (isActive) {
                delay(intervalMs)
                runCatching {
                    (embedder as? OnnxEmbedder)?.checkAndUnload(idleThresholdMs)
                }.onFailure { e ->
                    android.util.Log.w("PrismApplication", "checkAndUnload failed: ${e::class.simpleName}")
                }
            }
        }
    }

    /**
     * 应用级协程作用域（后台任务，如授权根目录异步加载 / M5 记忆持久化）。
     *
     * M5 Phase E（US-035）：[io.prism.ui.chat.ConversationViewModel.onCleared] 中
     * 会话结束 fire-and-forget 持久化 L2 跨会话记忆 + L3 隐式偏好抽取需使用此 scope
     * （viewModelScope 在 onCleared 时已取消，无法启动新协程）。SupervisorJob 保证
     * 子协程异常不互相取消，记忆持久化失败不影响应用其他部分。
     */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 授权根目录注册/加载互斥锁（C3：串行化加载与注册，避免覆盖竞态）。 */
    private val rootsMutex = Mutex()

    /** DataStore 进程级单例（多实例会崩溃，必须惰性单例）。 */
    private val Context.dataStore by preferencesDataStore(name = "prism_api_keys")

    /** 文件系统授权根目录 DataStore 进程级单例（ADR-006 5.3）。 */
    private val Context.filesystemRootsDataStore by preferencesDataStore(name = "prism_filesystem_roots")

    /** 记忆系统配置 DataStore 进程级单例（ADR-015 5.3，US-032 滑动窗口 N 持久化）。 */
    private val Context.memoryConfigDataStore by preferencesDataStore(name = "prism_memory_config")

    /** 设备档位配置 DataStore 进程级单例（ADR-017 4.4，US-040 用户手动覆盖持久化）。 */
    private val Context.tierConfigDataStore by preferencesDataStore(name = "prism_tier_config")

    companion object {
        /**
         * 默认切片大小（字符数，ADR-011 5.2）。
         *
         * ADR-007 5.4 推荐 256–1024 token 范围，512 为中位默认值。
         * all-MiniLM-L6-v2 最大输入 256 token，超出会被截断；512 字符约对应 200–300 token，
         * 在嵌入模型窗口内且保留充分上下文。
         */
        private const val DEFAULT_CHUNK_SIZE = 512

        /**
         * 默认切片重叠（字符数，ADR-011 5.2）。
         *
         * overlap = chunkSize / 8 ≈ 64，符合 RAG 最佳实践（保留上下文衔接又不过度冗余）。
         */
        private const val DEFAULT_CHUNK_OVERLAP = 64
    }
}
