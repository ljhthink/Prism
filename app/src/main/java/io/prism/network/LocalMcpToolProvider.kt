package io.prism.network

import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.prism.data.McpServerConfig
import io.prism.fs.FilesystemMcpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地 MCP 工具提供者 —— 以进程内方式桥接 [FilesystemMcpServer] 并实现 [McpToolProvider]（ADR-006 5.5）。
 *
 * 每次 listTools / callTool 建立一对 [InProcessTransport] + `server.createSession` + `client.connect`，
 * 完整走 MCP 握手（initialize → tools/list → tools/call），复用 [McpClientManager] 相同调用路径，
 * 调用完成后统一清理（finally 中关闭 client / session / transport），避免会话与资源泄漏。
 *
 * **错误语义**（对齐 [McpToolProvider] 契约）：
 * - listTools：连接失败返回空列表
 * - callTool：连接失败返回通用错误文案（不泄露内部细节，CWE-209）
 * - 协程取消重新抛出（结构化并发）
 */
class LocalMcpToolProvider(
    private val filesystemMcpServer: FilesystemMcpServer
) : McpToolProvider {

    override suspend fun listTools(config: McpServerConfig): List<String> = withContext(Dispatchers.IO) {
        var client: Client? = null
        var session: ServerSession? = null
        var transports: Pair<InProcessTransport, InProcessTransport>? = null
        try {
            transports = InProcessTransport.createPair()
            // 先建立 Server 会话（内部 connect 不阻塞等待握手），再 connect Client 触发 initialize。
            session = filesystemMcpServer.server.createSession(transports.second)
            client = Client(Implementation(CLIENT_NAME, CLIENT_VERSION))
            client.connect(transports.first)
            client.listTools().tools.map { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // C4：catch 兜底记录（BR-error-handling-004），不输出内部异常细节（CWE-209）
            Log.w(LOG_TAG, "listTools 本地工具枚举失败，已降级为空列表")
            emptyList()
        } finally {
            closeQuietly(client, session, transports)
        }
    }

    override suspend fun callTool(
        config: McpServerConfig,
        name: String,
        arguments: Map<String, Any?>
    ): String = withContext(Dispatchers.IO) {
        var client: Client? = null
        var session: ServerSession? = null
        var transports: Pair<InProcessTransport, InProcessTransport>? = null
        try {
            transports = InProcessTransport.createPair()
            session = filesystemMcpServer.server.createSession(transports.second)
            client = Client(Implementation(CLIENT_NAME, CLIENT_VERSION))
            client.connect(transports.first)
            val result = client.callTool(name, arguments)
            renderResult(result.content, result.isError == true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // C4：catch 兜底记录（BR-error-handling-004），不输出内部异常细节（CWE-209）
            Log.w(LOG_TAG, "callTool 本地工具调用失败，已降级为通用错误文案")
            "工具调用失败"
        } finally {
            closeQuietly(client, session, transports)
        }
    }

    /**
     * 将 MCP 工具调用结果 [ContentBlock] 列表渲染为文本（与 [McpClientManager] 语义一致）。
     *
     * 仅提取 [TextContent] 文本块，过滤空白后以换行连接；错误时前置「工具执行出错」标记。
     */
    internal fun renderResult(
        content: List<io.modelcontextprotocol.kotlin.sdk.types.ContentBlock>,
        isError: Boolean
    ): String {
        val text = content.mapNotNull { (it as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)?.text }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return if (isError) "工具执行出错：$text" else text
    }

    /** 关闭 Client / Server 会话 / 进程内传输，忽略关闭异常（释放资源不阻断流程）。 */
    private suspend fun closeQuietly(
        client: Client?,
        session: ServerSession?,
        transports: Pair<InProcessTransport, InProcessTransport>?
    ) {
        try { client?.close() } catch (_: Exception) { }
        try { session?.close() } catch (_: Exception) { }
        try {
            transports?.let { (a, b) -> a.close(); b.close() }
        } catch (_: Exception) { }
    }

    private companion object {
        const val CLIENT_NAME = "Prism"
        const val CLIENT_VERSION = "1.0.0"
        const val LOG_TAG = "LocalMcpToolProvider"
    }
}