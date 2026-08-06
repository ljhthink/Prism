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
import io.prism.data.McpServerRepository
import io.prism.data.MyObjectBox
import io.prism.data.ProviderConfigRepository
import io.prism.fs.FilesystemMcpServer
import io.prism.fs.FilesystemRootStore
import io.prism.fs.SafFileAccess
import io.prism.fs.UiConfirmationGate
import io.prism.network.LocalMcpToolProvider
import io.prism.network.McpClientManager
import io.prism.network.McpToolProvider
import io.prism.network.McpToolProviderDispatcher
import io.prism.network.OpenAICompatibleProvider
import io.prism.security.ApiKeyRepository
import io.prism.security.CryptoService
import io.prism.security.KeystoreCryptoService
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
    }

    /** 应用级协程作用域（后台任务，如授权根目录异步加载）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 授权根目录注册/加载互斥锁（C3：串行化加载与注册，避免覆盖竞态）。 */
    private val rootsMutex = Mutex()

    /** DataStore 进程级单例（多实例会崩溃，必须惰性单例）。 */
    private val Context.dataStore by preferencesDataStore(name = "prism_api_keys")

    /** 文件系统授权根目录 DataStore 进程级单例（ADR-006 5.3）。 */
    private val Context.filesystemRootsDataStore by preferencesDataStore(name = "prism_filesystem_roots")
}
