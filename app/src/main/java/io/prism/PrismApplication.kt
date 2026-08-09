package io.prism

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
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
import io.prism.data.SkillRepository
import io.prism.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
     * 端侧嵌入引擎（US-014，ADR-007 5.2）—— onnxruntime-android 加载 all-MiniLM-L6-v2 INT8 模型。
     *
     * 经 [EmbedderFactory.create] 从 `assets/models/` 加载 ONNX 模型（~23MB）与 BERT 词表（~226KB）。
     * 首次访问时延迟初始化，加载耗时 ~200ms，仅在用户首次进入知识库 Tab 时发生。
     *
     * **生命周期**（BR-concurrency-002）：OnnxEmbedder 全程持锁，单例化复用避免重复加载模型；
     * 协程取消时单次 embed ~100ms 不可中断，最坏延迟可接受。
     */
    val embedder: Embedder by lazy {
        assets.open(EmbedderFactory.DEFAULT_MODEL_PATH).use { modelInput ->
            assets.open(EmbedderFactory.DEFAULT_VOCAB_PATH).use { vocabInput ->
                EmbedderFactory.create(modelInput, vocabInput)
            }
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

    override fun onCreate() {
        super.onCreate()
        boxStore = MyObjectBox.builder()
            .androidContext(this)
            .build()
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

    /** 应用级协程作用域（后台任务，如授权根目录异步加载）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 授权根目录注册/加载互斥锁（C3：串行化加载与注册，避免覆盖竞态）。 */
    private val rootsMutex = Mutex()

    /** DataStore 进程级单例（多实例会崩溃，必须惰性单例）。 */
    private val Context.dataStore by preferencesDataStore(name = "prism_api_keys")

    /** 文件系统授权根目录 DataStore 进程级单例（ADR-006 5.3）。 */
    private val Context.filesystemRootsDataStore by preferencesDataStore(name = "prism_filesystem_roots")

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
