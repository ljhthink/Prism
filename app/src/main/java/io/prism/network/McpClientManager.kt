package io.prism.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.prism.data.McpServerConfig
import io.prism.security.ApiKeyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP Client 连接层 —— 基于官方 MCP Kotlin SDK（0.12.0）实现 [McpToolProvider]。
 *
 * 通过 [StreamableHttpClientTransport] 与远程 MCP Server 建立 Streamable HTTP 连接，
 * 复用 [PrismApplication.httpClient]（ADR-004 4.1）与 [ApiKeyRepository]（明文只读一次即用）。
 *
 * **连接生命周期**：每次 listTools / callTool 建立一个临时 [Client] 会话，
 * 调用完成后在 finally 中 `close()` 释放传输与进行中请求，避免连接泄漏。
 *
 * **安全**：API Key 明文仅在使用瞬间读取（[ApiKeyRepository.readApiKeyOnce]），
 * 不落盘、不记录日志；鉴权头通过 [resolveHeaders] 注入，自定义头不覆盖 Authorization。
 *
 * **错误语义**（对齐 [McpToolProvider] 契约）：
 * - listTools：连接失败返回空列表（UI 展示为空态，不崩溃）
 * - callTool：连接失败返回错误描述文本（UI 展示错误）
 * - 协程取消重新抛出，不吞掉（结构化并发，CR-01）
 *
 * **可测性**（ADR-005 5.4）：请求头拼装抽离为 [resolveHeaders] 纯函数、
 * 结果渲染抽离为 [renderResult] 纯函数，单元测试直接覆盖；端到端事务由真实
 * MCP Server 集成测试验证。
 *
 * ADR-005 5.3：连接层仿 OpenAICompatibleProvider（依赖注入 + 纯函数抽取）。
 */
class McpClientManager(
    private val httpClient: HttpClient,
    private val apiKeyRepository: ApiKeyRepository
) : McpToolProvider {

    /**
     * 列出 MCP Server 上可用的工具名称。
     *
     * @param config 目标 MCP Server 配置
     * @return 工具名称列表；连接失败返回空列表
     */
    override suspend fun listTools(config: McpServerConfig): List<String> = withContext(Dispatchers.IO) {
        withRetryOnStaleConnection(config, "listTools") { client ->
            client.listTools().tools.map { it.name }
        }.getOrElse { emptyList() }
    }

    /**
     * 连接诊断（v1 批次16 US-1602）：带错误分类的连接测试。
     *
     * 失败时返回结构化 [McpConnectionDiagnostic]（错误类别 + 用户可读原因），
     * 供设置页「测试连接」与列表连接徽章展示具体失败层。
     */
    override suspend fun diagnose(config: McpServerConfig): McpConnectionDiagnostic = withContext(Dispatchers.IO) {
        withRetryOnStaleConnection(config, "diagnose") { client ->
            client.listTools().tools.map { it.name }
        }.fold(
            onSuccess = { tools ->
                // 连接成功但工具列表为空 → success=true + toolCount=0（语义：连接成功）
                McpConnectionDiagnostic(success = true, toolCount = tools.size)
            },
            onFailure = { e ->
                val (kind, message) = classifyError(e)
                McpConnectionDiagnostic(success = false, errorKind = kind, errorMessage = message)
            }
        )
    }

    /**
     * 陈旧连接重试包装（v1 批次16 US-1601）。
     *
     * **根因（真机日志 2026-09-03）**：USB 重插 / adb reverse 链路重置后，OkHttp 连接池中
     * 的陈旧连接在 POST（MCP initialize 非幂等）上不自动重试，抛
     * `unexpected end of stream` → 表现为「连接不稳定，重试又好了」。
     * 修复：失败后立即用**新建连接**重试一次（失败连接已被 OkHttp 池剔除，重试即走新 socket）。
     */
    private suspend fun <T> withRetryOnStaleConnection(
        config: McpServerConfig,
        op: String,
        block: suspend (Client) -> T
    ): Result<T> {
        var client: Client? = null
        try {
            client = connect(config)
            return Result.success(block(client))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val firstError = e
            closeQuietly(client)
            client = null
            // 仅对链路类瞬态错误重试一次（认证/协议错误重试无意义）。
            // security: no-retry-on-calltool —— 带写副作用的 callTool 不经本包装（见 callTool 注释）。
            if (isStaleConnectionError(firstError)) {
                try {
                    client = connect(config)
                    return Result.success(block(client))
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    Log.w(LOG_TAG, "mcp $op retry failed url=${sanitizeUrlForLog(config.baseUrl)} err=${e2::class.simpleName}: ${e2.message?.take(160)}")
                    return Result.failure(e2)
                }
            }
            Log.w(LOG_TAG, "mcp $op failed url=${sanitizeUrlForLog(config.baseUrl)} err=${firstError::class.simpleName}: ${firstError.message?.take(160)}")
            return Result.failure(firstError)
        } finally {
            closeQuietly(client)
        }
    }

    /**
     * 判定是否为链路类瞬态错误（陈旧连接/网络中断）——仅此类错误值得重试。
     * internal 供单测（v1 批次16 M-2 修复：H-1 重试条件覆盖）。
     */
    internal fun isStaleConnectionError(e: Exception): Boolean =
        e is java.io.IOException &&
            (e.message?.contains("unexpected end of stream", ignoreCase = true) == true ||
                e.message?.contains("Connection reset", ignoreCase = true) == true ||
                e::class.simpleName == "ConnectException")

    /**
     * 返回远程 MCP Server 上可用的工具定义（DEF-008，Bug-3）。
     *
     * 供 [ConversationViewModel] 注入到 LLM `tools` 列表，使 LLM 能感知并调用远程 MCP 工具。
     * 连接失败返回空列表（不注入，不阻断对话）。
     */
    override suspend fun describeTools(config: McpServerConfig): List<ToolDefinition> = withContext(Dispatchers.IO) {
        withRetryOnStaleConnection(config, "describeTools") { client ->
            client.listTools().tools.map { tool ->
                ToolDefinition(
                    function = ToolDefinition.FunctionDef(
                        name = tool.name,
                        description = tool.description ?: tool.name,
                        parameters = mcpSchemaToParameters(tool)
                    )
                )
            }
        }.getOrElse {
            // v1 批次15.1：同 listTools——补可诊断日志（此前静默返回空列表）。
            // 实际错误详情经 withRetryOnStaleConnection 内部日志输出。
            emptyList()
        }
    }

    /** 将 MCP [Tool] 的 inputSchema 构造为 LLM 可用的 JSON schema（DEF-008）。 */
    private fun mcpSchemaToParameters(tool: Tool): kotlinx.serialization.json.JsonElement = buildJsonObject {
        put("type", "object")
        tool.inputSchema.properties?.let { put("properties", it) }
        val required = tool.inputSchema.required
        if (!required.isNullOrEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

    /**
     * 调用 MCP Server 上的指定工具。
     *
     * @param config 目标 MCP Server 配置
     * @param name 工具名称
     * @param arguments 工具参数（JSON 对象）
     * @return 工具调用结果文本；连接失败返回错误描述
     */
    override suspend fun callTool(
        config: McpServerConfig,
        name: String,
        arguments: Map<String, Any?>
    ): String = withContext(Dispatchers.IO) {
        var client: Client? = null
        try {
            client = connect(config)
            val result = client.callTool(name, arguments)
            val rendered = renderResult(result.content, result.isError == true)
            // v1 批次17：isError 结果补可诊断日志（此前 renderResult 静默转「工具执行出错」文案，
            // logcat 无记录，真机 RCA 时无法区分服务端错误与链路失败）。
            // guardrail P2-1（CWE-532）：内容为服务端任意文本，可能内嵌含凭证 query 的 URL——
            // 先替换内嵌 URL 为占位符再截断，且仅记首行 + 长度，不回显全文。
            if (result.isError == true) {
                val safe = rendered.replace(Regex("""https?://\S+"""), "<url>")
                Log.w(
                    LOG_TAG,
                    "mcp callTool isError tool=$name url=${sanitizeUrlForLog(config.baseUrl)} " +
                        "len=${rendered.length} firstLine=${safe.lineSequence().firstOrNull()?.take(120)}"
                )
            }
            rendered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // CR-05（CWE-209）：不向 UI 暴露异常内部信息；诊断信息经结构化日志脱敏记录。
            // security: no-retry-on-calltool —— H-1（guardrail 批次16）：callTool 具备服务端
            // 写副作用（文件写入/远程 POST 类工具），响应阶段连接 reset 时无法区分「未执行」
            // 与「已执行但响应丢失」——重试会导致副作用重复执行（绕过 OkHttp 对 POST 的幂等
            // 保护）。故 callTool 禁止走 withRetryOnStaleConnection（该保护仅限只读的
            // listTools/describeTools/diagnose）。
            Log.w(LOG_TAG, "mcp callTool failed tool=$name url=${sanitizeUrlForLog(config.baseUrl)} err=${e::class.simpleName}: ${e.message?.take(160)}")
            "工具调用失败，请检查网络连接或 Server 配置"
        } finally {
            closeQuietly(client)
        }
    }

    /**
     * 建立 MCP Streamable HTTP 连接并返回 [Client]。
     *
     * 读取 API Key 明文 → 拼装鉴权与自定义头 → 构建传输 → 连接。
     *
     * @param config MCP Server 配置
     * @return 已连接的 [Client] 实例（调用方负责 close）
     */
    private suspend fun connect(config: McpServerConfig): Client {
        // 纵深防御（guardrail M1-残 / CWE-113、CWE-93）：连接层独立校验 baseUrl，
        // 不依赖 UI 层过滤。非法 URL（空、非 http(s) 前缀、含 CRLF）直接抛 IllegalArgumentException，
        // 由调用方按降级契约处理，避免向传输层注入坏值。
        val baseUrl = config.baseUrl.trim()
        require(isValidBaseUrl(baseUrl)) {
            "非法 MCP Server baseUrl"
        }
        val apiKey = if (config.apiKeyRef.isNotBlank()) {
            apiKeyRepository.readApiKeyOnce(config.apiKeyRef)
        } else {
            null
        }
        val headers = resolveHeaders(config.headers, apiKey)
        val transport = StreamableHttpClientTransport(httpClient, baseUrl) {
            applyHeaders(this, headers)
        }
        val client = Client(Implementation(CLIENT_NAME, CLIENT_VERSION))
        try {
            client.connect(transport)
        } catch (e: Exception) {
            // guardrail S2（CWE-404）：连接失败时释放已创建的 Client，避免资源泄漏后重抛。
            closeQuietly(client)
            throw e
        }
        return client
    }

    /**
     * 脱敏 URL 供日志（v1 批次15.1 可诊断性，CWE-532）：剥 query/fragment/userinfo 凭证，
     * 仅保留 scheme://host[:port] + 截断 path——与 [LocalMcpToolProvider.sanitizeUrlForLog] 同口径。
     */
    private fun sanitizeUrlForLog(url: String): String {
        val authority = Regex("""^https?://([^/?#]+)""").find(url)?.groupValues?.get(1) ?: return url.take(120)
        val host = authority.substringAfterLast('@')
        val path = Regex("""^https?://[^/?#]+(/[^?#]*)""").find(url)?.groupValues?.get(1).orEmpty()
        return "${url.substringBefore("://")}://$host${path.take(120)}"
    }

    /**
     * 连接异常分类（v1 批次16 US-1602）：映射为 [McpErrorKind] + 用户可读原因。
     * 消息为用户可读中文（已脱敏——不含 Key/内部路径，CWE-209 口径）。
     */
    internal fun classifyError(e: Throwable): Pair<McpErrorKind, String> {
        val msg = e.message.orEmpty()
        return when {
            e is IllegalArgumentException && msg.contains("非法") ->
                McpErrorKind.INVALID_URL to "Base URL 非法（需以 http:// 或 https:// 开头）"
            e is java.net.UnknownServiceException || msg.contains("CLEARTEXT", ignoreCase = true) ->
                McpErrorKind.PLAINTEXT_BLOCKED to
                    "明文 http 被系统拦截（仅 localhost 放行）——USB 连接后执行 adb reverse，或改用 https 端点"
            e is javax.net.ssl.SSLException || msg.contains("SSL", ignoreCase = true) ||
                msg.contains("certificate", ignoreCase = true) ->
                McpErrorKind.TLS to "TLS/证书错误（证书无效或不受信任）"
            e is java.net.SocketTimeoutException || msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) ->
                McpErrorKind.TIMEOUT to "连接超时（服务无响应）"
            e is java.net.ConnectException || msg.contains("Failed to connect", ignoreCase = true) ||
                msg.contains("ECONNREFUSED", ignoreCase = true) ->
                McpErrorKind.CONNECTION_REFUSED to
                    "连接被拒绝（服务未启动 / 端口错误 / USB 场景未执行 adb reverse）"
            msg.contains("401", ignoreCase = false) && (msg.contains("Unauthorized") || msg.contains("HTTP 401")) ->
                McpErrorKind.AUTH to "认证失败（Key 缺失或错误，HTTP 401）"
            msg.contains("403", ignoreCase = false) && (msg.contains("Forbidden") || msg.contains("HTTP 403")) ->
                McpErrorKind.AUTH to "访问被拒（Key 无权限，HTTP 403）"
            e is java.io.IOException && msg.contains("unexpected end of stream", ignoreCase = true) ->
                McpErrorKind.NETWORK to "连接中断（链路重置——USB 重插后常见，已自动重试仍失败）"
            e is java.io.IOException ->
                McpErrorKind.NETWORK to "网络错误（${e::class.simpleName}）"
            else -> McpErrorKind.PROTOCOL to "MCP 协议错误（服务端返回非预期响应）"
        }
    }

    /**
     * 校验 baseUrl 是否合法（连接层白名单，纵深防御）。
     *
     * 要求：非空、以 `http://` 或 `https://` 开头、不含 `\r`/`\n`。
     * CRLF 检查作用于原始值（trim 前），避免尾部 `\r\n` 被 `trim()` 剥离后绕过校验
     * （guardrail R3-1 / CWE-113）。
     *
     * @param baseUrl MCP Server 端点 URL（应为 trim 后的值）
     * @return 是否合法
     */
    internal fun isValidBaseUrl(baseUrl: String): Boolean {
        val hasCrlf = baseUrl.contains('\r') || baseUrl.contains('\n')
        val trimmed = baseUrl.trim()
        val hasScheme = trimmed.startsWith("http://") || trimmed.startsWith("https://")
        return trimmed.isNotEmpty() && hasScheme && !hasCrlf
    }

    /** 将请求头应用到 Ktor 请求构建器。 */
    private fun applyHeaders(builder: HttpRequestBuilder, headers: Map<String, String>) {
        headers.forEach { (k, v) -> builder.header(k, v) }
    }

    /** 关闭 Client，忽略关闭异常（释放资源不阻断流程）。 */
    private suspend fun closeQuietly(client: Client?) {
        try {
            client?.close()
        } catch (_: Exception) {
            // 关闭失败不影响已完成的调用结果（Karpathy：避免非关键路径抛错）
        }
    }

    /**
     * 拼装 MCP 请求头。
     *
     * 规则（对齐 OpenAICompatibleProvider，CR-06 LOW 修复）：
     * - 剔除含 CR/LF 的键值（纵深防御，guardrail M1 / CWE-113、CWE-93），
     *   防止经 StreamableHttpClientTransport 注入 HTTP 首部；UI 层校验为第一道防线，
     *   此处为第二道防线，确保任何调用路径都不会注入 CRLF。
     * - 复制剩余自定义头，保留原样
     * - 若 apiKeyRef 明文非空且自定义头未显式提供 Authorization，则注入 `Bearer <key>`
     * - 头名比较做大小写规范化，避免用户配置小写 `authorization` 时重复注入鉴权头
     *
     * @param customHeaders 用户自定义请求头
     * @param apiKey API Key 明文（可能为 null）
     * @return 合并后的请求头
     */
    internal fun resolveHeaders(customHeaders: Map<String, String>, apiKey: String?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        customHeaders.forEach { (k, v) ->
            if (!k.contains('\r') && !k.contains('\n') && !v.contains('\r') && !v.contains('\n')) {
                result[k] = v
            }
        }
        apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            if (result.keys.none { it.equals(HttpHeaders.Authorization, ignoreCase = true) }) {
                result[HttpHeaders.Authorization] = "Bearer $key"
            }
        }
        return result
    }

    /**
     * 将 MCP 工具调用结果 [ContentBlock] 列表渲染为文本。
     *
     * 仅提取 [TextContent] 文本块，过滤空白后以换行连接；
     * 非文本内容块（如图片/资源引用）忽略。
     *
     * @param content 工具调用返回的内容块
     * @param isError 是否为错误结果
     * @return 渲染文本；错误时前置「工具执行出错」标记
     */
    internal fun renderResult(content: List<ContentBlock>, isError: Boolean): String {
        val text = content.mapNotNull { (it as? TextContent)?.text }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return if (isError) "工具执行出错：$text" else text
    }

    private companion object {
        private const val LOG_TAG = "McpClientManager"
        const val CLIENT_NAME = "Prism"
        const val CLIENT_VERSION = "1.0.0"
    }
}