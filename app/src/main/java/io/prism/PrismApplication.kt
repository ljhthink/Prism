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
import io.prism.data.SessionRepository
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
import io.prism.network.WebSearchLocalToolExecutor
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
            // UXR6 问题 5（TTFT）：共享 client 此前未安装 HttpTimeout，`timeout {}` DSL 为 no-op，
            // 连接挂起时（首 token 前的网络 RTT 无超时保护）会无限等待。此处仅配 connect/socket
            // 超时（连接建连 + 读 socket 空闲），**不配 requestTimeoutMillis** —— 流式 SSE 长连接
            // 会被整请求超时误杀（对话>30s 即断流）。
            install(HttpTimeout) {
                connectTimeoutMillis = STREAM_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = STREAM_SOCKET_TIMEOUT_MS
            }
        }
    }

    /** OpenAI 兼容 Provider 流式请求（依赖 httpClient + apiKeyRepository） */
    val openAICompatibleProvider: OpenAICompatibleProvider by lazy {
        OpenAICompatibleProvider(httpClient, apiKeyRepository)
    }

    /** MCP Server 配置仓库（延迟初始化，供 UI 读取/管理 MCP Server 配置） */
    val mcpServerRepository: McpServerRepository by lazy { McpServerRepository(boxStore) }

    /**
     * 会话历史仓库（UX-001 问题 4，ADR-021）—— 历史对话记录 CRUD。
     *
     * 依赖 [boxStore]（ObjectBox 单例），会话列表 StateFlow 供
     * [io.prism.ui.conversationlist.ConversationListScreen] 订阅，持久化由
     * [io.prism.ui.chat.ConversationViewModel]（[io.prism.data.Session.messagesJson]）触发。
     */
    val sessionRepository: SessionRepository by lazy { SessionRepository(boxStore) }

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
        FilesystemMcpServer(
            safFileAccess,
            confirmationGate,
            // UXR3 问题 10（ADR-023，guardrail M-2 修复）：AUTO 模式跳过确认门禁，
            // 使「所有工具直接放行」声明在文件系统工具上同样成立
            approvalModeProvider = { toolApprovalConfigRepository.getMode() }
        )
    }

    /** 本地 MCP 工具提供者（进程内桥接 Filesystem Server，ADR-006 5.5；Fetch 用独立 fetchHttpClient） */
    val localMcpToolProvider: LocalMcpToolProvider by lazy {
        LocalMcpToolProvider(filesystemMcpServer, fetchHttpClient)
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
            // UXR9 US-901：多语言嵌入模型（paraphrase-multilingual-MiniLM-L12-v2 qint8，
            // ~113MB）+ Unigram tokenizer（tokenizer.json ~9MB）。英文 MiniLM 对中文语义
            // 区分度差（无关中文片段余弦 0.4~0.7），多语言模型是中文 RAG 治本方案。
            assets.open(EmbedderFactory.DEFAULT_MODEL_PATH).use { modelInput ->
                assets.open(EmbedderFactory.DEFAULT_TOKENIZER_PATH).use { tokenizerInput ->
                    EmbedderFactory.createMultilingual(modelInput, tokenizerInput)
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
     * **问题 8b（ADR-020）**：本地工具分支由 [compositeLocalToolExecutor] 组合承载
     * （M6 跨 App `cross_app__*` + 联网搜索 `web_search__*`），SkillExecutor 零改动感知全部本地工具。
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
            localToolExecutor = compositeLocalToolExecutor,
            // UXR3 问题 10（ADR-023）：工具审批模式 —— 从配置仓库实时读取（运行时切换即时生效）
            approvalModeProvider = { toolApprovalConfigRepository.getMode() },
            // UXR11 U2（ADR-033）：工具回路轮间退避 —— 生产显式注入 2s，摊开多轮 LLM
            // 请求、降低瞬时 RPM 峰值触发 429 概率（构造器默认 0 供单测不等待）。
            interRoundDelayMs = io.prism.skill.SkillExecutor.TOOL_LOOP_INTER_ROUND_DELAY_MS
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
     * 联网搜索专用 HTTP 客户端（问题 8b，ADR-020）。
     *
     * **与共享 [httpClient] 的差异**：
     * - `install(HttpTimeout)`：启用请求级超时配置（共享 client 未安装此插件，`timeout {}` DSL 为 no-op）
     * - 不安装 SSE 插件（搜索非流式对话，无需 SSE）
     * - `expectSuccess = true`：非 2xx（如 Bing 反爬 403/429）抛异常，由执行器降级为错误文案
     *
     * 仿 [downloadHttpClient] 模式（US-028），独立 client 避免与共享 client 的 SSE 插件耦合。
     */
    val searchHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(HttpTimeout) {
                requestTimeoutMillis = SEARCH_REQUEST_TIMEOUT_MS
            }
        }
    }

    /**
     * Fetch MCP 工具专用 HTTP 客户端（UXR9 Bug5 修复，TKN-UXR9-ARCHAEOLOGY-001）。
     *
     * **与 [searchHttpClient] 的差异**：
     * - `expectSuccess = false`：非 2xx **返回响应**交由 [LocalMcpToolProvider] 按状态码
     *   生成可诊断文案（如 "HTTP 404"）。此前复用 searchHttpClient（expectSuccess=true），
     *   非 2xx 在 `client.get` 处抛异常落入通用失败文案"抓取失败：网络错误"，且工具内
     *   的 `if (!response.status.isSuccess())` 分支成为**不可达死代码**（测试-生产行为漂移）。
     * - 独立超时（Fetch 目标站多样，给足缓冲）
     */
    val fetchHttpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            // UXR11 U3（ADR-033）修复：Ktor 3.x **HttpRedirect 默认安装且默认跟随重定向**
            //（maxJumps=20，网络调研实证：Ktor 文档 "By default, Ktor HTTP client does follow
            // redirections"）。此前 Q-LOW-3 注释假设「OkHttp engine 默认不跟随 3xx」在 Ktor 3.3.3
            // 下**不成立** → client.get 会在内部跟随重定向（绕过 LocalMcpToolProvider.fetchWithRedirects
            // 的逐跳 SSRF 复检与 3 跳上限，且重定向到内网地址可被跟随 = SSRF 纵深防御被突破；
            // 超过 20 跳抛 SendCountExceedException → "抓取失败：网络错误"）。**显式禁用**，
            // 使 3xx 原样返回给 fetchWithRedirects 做 SSRF 校验后手动跟随（唯一重定向路径）。
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = FETCH_REQUEST_TIMEOUT_MS
            }
        }
    }

    /**
     * 联网搜索本地工具执行器（问题 8b，ADR-020）—— 实现 LocalToolExecutor 接口。
     *
     * 注册 `web_search__search` 工具，通过 Bing RSS 端点（零配置免费、国内可访问）检索，
     * 由 [skillExecutor] 的本地工具分支调用。
     */
    val webSearchLocalToolExecutor: WebSearchLocalToolExecutor by lazy {
        WebSearchLocalToolExecutor(searchHttpClient)
    }

    /**
     * 知识库本地工具执行器（UXR4 问题 2/3，ADR-024）—— 实现 LocalToolExecutor 接口。
     *
     * 注册 `knowledge_base__search` / `knowledge_base__list_documents` /
     * `knowledge_base__get_document_content` 三个工具，使 LLM 能主动枚举/检索/读取
     * Prism 知识库（解决 LLM 误把知识库当 Filesystem、RAG 只见第一篇的问题）。
     * 依赖 [embedder] + [knowledgeBaseRepository]，由 [compositeLocalToolExecutor] 注入。
     */
    val knowledgeBaseLocalToolExecutor: io.prism.network.KnowledgeBaseLocalToolExecutor by lazy {
        io.prism.network.KnowledgeBaseLocalToolExecutor(embedder, knowledgeBaseRepository)
    }

    /**
     * 文档生成本地工具执行器（O4/PRD UXR8）—— 实现 LocalToolExecutor 接口。
     *
     * `document__create_docx` / `document__create_xlsx`：LLM 输出 Markdown / 表格数据 →
     * POI（M3 已引入，零新依赖）生成文件，保存到应用外部私有 Documents 目录。
     */
    val documentLocalToolExecutor: io.prism.document.DocumentLocalToolExecutor by lazy {
        io.prism.document.DocumentLocalToolExecutor(
            baseDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                ?: filesDir.resolve("Documents")
        )
    }

    /**
     * 反问/澄清本地工具执行器（UXR8 N2 Phase 2，ADR-030）—— `ask_user__ask`。
     *
     * LLM 面对需求歧义时主动向用户澄清：工具"执行"= 返回特殊标记前缀，
     * [skillExecutor] 检测后发射 [io.prism.network.StreamEvent.AskUser] 并中断回路，
     * UI 展示提问卡片，用户答复作为下一条 user 消息进入下一轮。无 Android 依赖。
     */
    val askUserLocalToolExecutor: io.prism.skill.AskUserLocalToolExecutor by lazy {
        io.prism.skill.AskUserLocalToolExecutor()
    }

    /**
     * 复合本地工具执行器（问题 8b，ADR-020；UXR4 问题 2/3，ADR-024 扩展；O4/PRD UXR8 扩展；
     * N2/ADR-030 扩展）—— 组合 M6 跨 App + 联网搜索 + 知识库 + 文档生成 + 反问。
     *
     * 将 [crossAppLocalToolExecutor]（`cross_app__*`）、[webSearchLocalToolExecutor]
     * （`web_search__*`）、[knowledgeBaseLocalToolExecutor]（`knowledge_base__*`）、
     * [documentLocalToolExecutor]（`document__*`）与 [askUserLocalToolExecutor]
     * （`ask_user__*`）组合为单个 [io.prism.skill.LocalToolExecutor] 门面，
     * 注入 [skillExecutor]，使 SkillExecutor 零改动感知全部本地工具。
     */
    val compositeLocalToolExecutor: io.prism.skill.CompositeLocalToolExecutor by lazy {
        io.prism.skill.CompositeLocalToolExecutor(
            listOf(
                crossAppLocalToolExecutor,
                webSearchLocalToolExecutor,
                knowledgeBaseLocalToolExecutor,
                documentLocalToolExecutor,
                askUserLocalToolExecutor
            )
        )
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
     * 深度思考配置仓库（问题 8a，ADR-020）—— 持久化深度思考开关与思考强度。
     *
     * 使用独立 DataStore（`prism_thinking_config`），与 API Key / 文件系统根目录 / 记忆配置 /
     * 档位 DataStore 隔离。默认关闭（避免向不兼容端点发送 thinking 参数），用户可在设置中开启。
     *
     * 由 [io.prism.ui.chat.ConversationViewModel] 读取决定是否发送 thinking/reasoning_effort，
     * 由 [io.prism.ui.settings.SettingsViewModel]（US-042 设置 UI）写入用户偏好。
     */
    val thinkingConfigRepository: io.prism.config.ThinkingConfigRepository by lazy {
        io.prism.config.ThinkingConfigRepository(thinkingConfigDataStore)
    }

    /**
     * 工具审批模式配置仓库（UXR3 问题 10，ADR-023）—— 持久化 LLM 工具调用权限策略。
     *
     * 使用独立 DataStore（`prism_tool_approval`），与 API Key / 文件系统根目录 / 记忆配置 /
     * 档位 / 思考 DataStore 隔离。默认 MANUAL（手动审批，安全优先）。
     *
     * 由 [skillExecutor]（作为 approvalModeProvider）与 [io.prism.ui.settings.SettingsViewModel]
     * 读取/写入。切换模式运行时即时生效（无需重启）。
     */
    val toolApprovalConfigRepository: io.prism.config.ToolApprovalConfigRepository by lazy {
        io.prism.config.ToolApprovalConfigRepository(toolApprovalDataStore)
    }

    /**
     * RAG 检索目标配置仓库（UXR8 Bug1，ADR-028）—— 持久化 RagTarget 三态模式。
     *
     * 使用独立 DataStore（`prism_rag_config`），与 API Key / 文件系统根目录 / 记忆配置 /
     * 档位 / 思考 / 审批 DataStore 隔离。默认 [io.prism.rag.RagTarget.AllLibraries]
     * （对齐 ADR-012 5.2「默认开启」）。
     *
     * 由 [io.prism.ui.chat.ConversationViewModel] 读取恢复用户上次的 RAG 模式、
     * 写入用户切换（setRagTarget），解决「用户关闭 RAG 后新对话又被重置为全库」的
     * UXR8 Bug1 根因（考古 TKN-UXR8-ARCHAEOLOGY-001）。
     */
    val ragTargetConfigRepository: io.prism.config.RagTargetConfigRepository by lazy {
        io.prism.config.RagTargetConfigRepository(ragConfigDataStore)
    }

    /**
     * 用户规则配置仓库（UXR8 N1，ADR-030）—— 持久化「关于我」+「如何回答」双字段。
     *
     * 使用独立 DataStore（`prism_user_rules`），与 API Key / 思考 / 记忆 / RAG / 审批
     * DataStore 隔离。默认空（未配置规则时不注入 userRules 层，向后兼容）。
     *
     * 由 [io.prism.ui.chat.ConversationViewModel] 读取并作为 systemPrompt 最高优先级层
     * 注入（persona 之后、RAG 之前）；由 [io.prism.ui.settings.SettingsViewModel]
     * 写入用户偏好（设置页双字段编辑器）。
     */
    val userRulesRepository: io.prism.config.UserRulesRepository by lazy {
        io.prism.config.UserRulesRepository(userRulesDataStore)
    }

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
        // UXR9 US-904 AC-2：注入 conversationSummarizer，会话结束时对有价值内容做 LLM 摘要入库
        //（失败由 CrossSessionMemoryManager 内部降级为规则抽取逐对存储）。
        CrossSessionMemoryManager(embedder, memoryRepository, conversationSummarizer)
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
        // R1（UXR10 真机修复，ADR-032）：pdfbox-android 需初始化资源加载器（字体等）。
        // 若 PDFBoxResourceLoader 未初始化，pdfbox 在访问字体资源时报错；初始化失败不阻断启动。
        runCatching {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        }.onFailure { e ->
            android.util.Log.w("PrismApplication", "PDFBoxResourceLoader init failed", e)
        }
        // UXR9 US-901（guardrail Q-HIGH-2 修复）：embedder 是重资源 lazy（113MB ONNX 读取 +
        // 9MB tokenizer.json 解析 + 250k 词条 HashMap 构建）。若由 ConversationViewModel.Factory
        // （主线程）首次触发会同步阻塞导致首开聊天页卡顿/ANR。此处用 appScope(IO) 预预热，
        // 将加载移出主线程。by lazy 默认 SYNCHRONIZED 线程安全：IO 预热失败不缓存，后续
        // 访问（含主线程）会重试，优雅降级。
        appScope.launch {
            runCatching { embedder }.onFailure { e ->
                android.util.Log.e("PrismApplication", "embedder 预热失败（后续按需重试）", e)
            }
        }
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

    /** 深度思考配置 DataStore 进程级单例（问题 8a，ADR-020，思考开关 + 强度持久化）。 */
    private val Context.thinkingConfigDataStore by preferencesDataStore(name = "prism_thinking_config")

    /** 工具审批模式配置 DataStore 进程级单例（UXR3 问题 10，ADR-023，审批模式持久化）。 */
    private val Context.toolApprovalDataStore by preferencesDataStore(name = "prism_tool_approval")

    /** RAG 检索目标配置 DataStore 进程级单例（UXR8 Bug1，ADR-028，RagTarget 持久化）。 */
    private val Context.ragConfigDataStore by preferencesDataStore(name = "prism_rag_config")

    /** 用户规则配置 DataStore 进程级单例（UXR8 N1，ADR-030，关于我+如何回答持久化）。 */
    private val Context.userRulesDataStore by preferencesDataStore(name = "prism_user_rules")

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

        /**
         * 联网搜索请求超时（问题 8b，ADR-020）。
         *
         * Bing RSS 检索为同步等待结果，设置 10s 上限避免网络抖动长时间阻塞工具回路
         * （SkillExecutor 单次工具调用外层还有 30s withTimeout 兜底）。
         */
        private const val SEARCH_REQUEST_TIMEOUT_MS = 10_000L

        /** Fetch MCP 工具请求超时（UXR9 Bug5 修复）：目标站多样，15s 缓冲。 */
        private const val FETCH_REQUEST_TIMEOUT_MS = 15_000L

        /**
         * UXR6 问题 5：流式共享 client 的建连超时（毫秒）。
         *
         * 首 token 前的网络 RTT 若因建连挂起会无限等待（共享 client 此前无 HttpTimeout）。
         * 仅约束建连与 socket 空闲读，**不约束整请求时长**（流式 SSE 长连接）。
         */
        internal const val STREAM_CONNECT_TIMEOUT_MS = 15_000L

        /**
         * UXR6 问题 5：流式共享 client 的 socket 读空闲超时（毫秒）。
         *
         * 两次数据读取间隔超过该值视为连接挂起（断流）。SSE 心跳/分块间隔通常远小于此。
         */
        internal const val STREAM_SOCKET_TIMEOUT_MS = 60_000L
    }
}
