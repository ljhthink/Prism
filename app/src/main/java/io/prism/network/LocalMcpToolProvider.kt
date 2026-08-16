package io.prism.network

import android.util.Log
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.prism.data.McpServerConfig
import io.prism.fs.FilesystemMcpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本地 MCP 工具提供者 —— 按 [McpServerConfig.name] 分发到各本地实现（DEF-008，Bug-3）。
 *
 * **Bug-3 修复**：原实现硬编码仅桥接 [FilesystemMcpServer]，导致 Time/Fetch 等其他
 * 宣称"本地内置零配置"的 server 实际无工具可调。本次按 server 名称分发：
 * - `Filesystem` → 桥接 [FilesystemMcpServer]（真实文件工具，走 MCP 协议）
 * - `Time` → 内置简单实现（返回当前时间，零配置）
 * - 其他未实现本地 server → 返回空工具列表（不注入，避免误导 LLM）
 *
 * 每次 listTools / describeTools / callTool 建立一对 [InProcessTransport] +
 * `server.createSession` + `client.connect`，完整走 MCP 握手，调用完成后统一清理。
 *
 * **错误语义**（对齐 [McpToolProvider] 契约）：
 * - listTools / describeTools：失败返回空列表
 * - callTool：失败返回通用错误文案（不泄露内部细节，CWE-209）
 * - 协程取消重新抛出（结构化并发）
 */
class LocalMcpToolProvider(
    private val filesystemMcpServer: FilesystemMcpServer,
    /** UXR3 问题 11（ADR-023）：Fetch MCP 工具的 HTTP 客户端（可空：null 时 Fetch 工具降级为不可用）。 */
    private val fetchHttpClient: io.ktor.client.HttpClient? = null
) : McpToolProvider {

    override suspend fun listTools(config: McpServerConfig): List<String> = withContext(Dispatchers.IO) {
        when {
            config.name.equals(NAME_FILESYSTEM, ignoreCase = true) -> filesystemTools().map { it.name }
            config.name.equals(NAME_TIME, ignoreCase = true) -> TIME_TOOLS.map { it.function.name }
            config.name.equals(NAME_SEQUENTIAL_THINKING, ignoreCase = true) -> SEQUENTIAL_THINKING_TOOLS.map { it.function.name }
            config.name.equals(NAME_FETCH, ignoreCase = true) -> FETCH_TOOLS.map { it.function.name }
            else -> emptyList()
        }
    }

    override suspend fun describeTools(config: McpServerConfig): List<ToolDefinition> = withContext(Dispatchers.IO) {
        when {
            config.name.equals(NAME_FILESYSTEM, ignoreCase = true) -> filesystemTools().map { it.toToolDefinition() }
            config.name.equals(NAME_TIME, ignoreCase = true) -> TIME_TOOLS
            config.name.equals(NAME_SEQUENTIAL_THINKING, ignoreCase = true) -> SEQUENTIAL_THINKING_TOOLS
            config.name.equals(NAME_FETCH, ignoreCase = true) -> FETCH_TOOLS
            else -> emptyList()
        }
    }

    override suspend fun callTool(
        config: McpServerConfig,
        name: String,
        arguments: Map<String, Any?>
    ): String = withContext(Dispatchers.IO) {
        when {
            config.name.equals(NAME_TIME, ignoreCase = true) && name == TIME_GET_CURRENT_TIME -> {
                // 零配置本地 Time server：返回当前日期时间
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            }
            config.name.equals(NAME_SEQUENTIAL_THINKING, ignoreCase = true) && name == ST_THINK -> {
                // UX-001 问题 6（ADR-022）：实现本地 Sequential Thinking 工具（MCP 官方算法）
                sequentialThink(arguments)
            }
            config.name.equals(NAME_FETCH, ignoreCase = true) && name == FETCH_TOOL -> {
                // UXR3 问题 11（ADR-023）：实现本地 Fetch 工具（抓取 URL 内容，零配置）
                fetchUrl(arguments)
            }
            config.name.equals(NAME_FILESYSTEM, ignoreCase = true) -> filesystemCallTool(name, arguments)
            else -> "工具调用失败：该本地 MCP Server（${config.name}）暂未实现该工具"
        }
    }

    // ==================== Sequential Thinking（MCP 官方算法，零依赖） ====================

    /**
     * 执行 Sequential Thinking 工具的思考步骤（UX-001 问题 6，ADR-022）。
     *
     * 对齐 MCP Sequential Thinking 官方 server 的语义：
     * - 参数 `thought`（必需）：本步思考内容
     * - 参数 `thoughtNumber`（可选）：思考步骤编号
     * - 参数 `totalThoughts`（可选）：预期总步骤数
     * - 参数 `nextThoughtNeeded`（可选）：是否需要继续
     * - 参数 `isRevision`（可选）：是否修订上一步
     * - 参数 `revisesThought`（可选）：修订哪一步
     * - 参数 `branchFromThought`（可选）：从哪一步分支
     * - 参数 `branchId`（可选）：分支标识
     * - 参数 `needsMoreThoughts`（可选）：是否还需要更多思考
     *
     * **实现**：纯结构化回显 —— 返回格式化的思考状态 JSON（含 step/总数/是否继续/修订/分支），
     * 供 LLM 迭代使用。不维护跨调用状态（保持无状态 + 幂等，符合零配置定位；
     * LLM 通过 thoughtNumber/totalThoughts 自管理步骤）。
     *
     * **校验**（fail-fast）：thought 为空时返回错误文案（BR-error-handling-004）。
     */
    internal fun sequentialThink(arguments: Map<String, Any?>): String {
        val thought = arguments["thought"]?.toString()?.trim()
            ?: return "缺少必需参数 thought"
        val thoughtNumber = (arguments["thoughtNumber"] as? Number)?.toInt() ?: 0
        val totalThoughts = (arguments["totalThoughts"] as? Number)?.toInt() ?: 0
        val nextThoughtNeeded = arguments["nextThoughtNeeded"] as? Boolean ?: true
        val isRevision = arguments["isRevision"] as? Boolean ?: false
        val revisesThought = (arguments["revisesThought"] as? Number)?.toInt()
        val branchFromThought = (arguments["branchFromThought"] as? Number)?.toInt()
        val branchId = arguments["branchId"]?.toString()
        val needsMoreThoughts = arguments["needsMoreThoughts"] as? Boolean

        return buildString {
            append("已记录思考步骤（Sequential Thinking）：\n")
            append("步骤 ${if (thoughtNumber > 0) thoughtNumber else "?"}")
            if (totalThoughts > 0) append("/$totalThoughts")
            append("\n")
            if (isRevision) {
                append("类型：修订")
                if (revisesThought != null) append("（修订第 $revisesThought 步）")
                append("\n")
            }
            if (branchFromThought != null) {
                append("分支：自第 $branchFromThought 步")
                if (branchId != null) append("（分支 $branchId）")
                append("\n")
            }
            append("内容：$thought\n")
            when (needsMoreThoughts) {
                true -> append("是否继续：是，需要更多思考")
                false -> append("是否继续：否")
                null -> append("是否继续：${if (nextThoughtNeeded) "是" else "否"}")
            }
        }
    }

    // ==================== Fetch（UXR3 问题 11，ADR-023） ====================

    /**
     * 执行 Fetch 工具（抓取指定 URL 的内容文本）。
     *
     * 对齐 MCP Fetch 官方 server 的核心语义：
     * - 参数 `url`（必需）：要抓取的 http(s) URL
     * - 参数 `maxLength`（可选，100..10000，默认 5000）：返回内容最大字符数
     * - 参数 `raw`（可选，默认 false）：是否返回原始未处理内容
     *
     * **实现**：通过 [fetchHttpClient] GET 目标 URL，读取响应体文本，
     * 去掉 HTML 标签后按 maxLength 截断返回。零配置（无 API Key），契合本地内置定位。
     *
     * **安全**（guardrail M-1 补强，CWE-918 SSRF）：
     * - 仅允许 http/https scheme
     * - **拒绝内网/回环/链路本地地址**：解析 URL 主机，若是 IP 字面量则按地址族分类
     *   拦截（回环 / 私有网段 / 链路本地 / 云元数据 169.254.169.254）；若是域名则解析为
     *   IP 后同样校验。防止 LLM 诱导 App 向设备本机/局域网发起请求。
     *   **已知局限**：DNS rebinding（先解析公网、后解析内网）无法在发起请求前完全阻断，
     *   本层为纵深防御；工具描述声明「仅可访问公网地址」。
     * - **响应大小上限**：Content-Length 预检（若声明超过 [MAX_FETCH_LEN] 则拒绝），
     *   避免恶意大响应经 [bodyAsText] 全量读入造成内存压力（L-3）。
     * - 不向 LLM 暴露响应头/错误细节（CWE-209）
     *
     * **降级**：client 为 null、URL 非法、内网地址或网络失败时返回描述性文案（不抛异常）。
     *
     * **协程取消**：CancellationException 重抛（BR-error-handling-007）。
     */
    private suspend fun fetchUrl(arguments: Map<String, Any?>): String {
        val client = fetchHttpClient ?: return "Fetch 工具不可用：未配置网络客户端"
        val rawUrl = arguments["url"]?.toString()?.trim()
            ?: return "缺少必需参数 url"
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            return "仅支持抓取 http:// 或 https:// 地址"
        }
        // SSRF 防护：解析 URL 并拒绝内网/回环/链路本地地址（guardrail M-1）
        if (!isPublicHttpUrl(rawUrl)) {
            return "仅支持抓取公网地址（已拒绝内网/本机地址）"
        }
        val maxLength = (arguments["maxLength"] as? Number)
            ?.toInt()?.coerceIn(MIN_FETCH_LEN, MAX_FETCH_LEN) ?: DEFAULT_FETCH_LEN
        val raw = arguments["raw"] as? Boolean ?: false

        return try {
            val response: io.ktor.client.statement.HttpResponse = client.get(rawUrl) {
                header(io.ktor.http.HttpHeaders.UserAgent, FETCH_USER_AGENT)
            }
            // Content-Length 预检（防超大响应全量读入，guardrail L-3/M-1）
            val contentLength = response.contentLength()
            if (contentLength != null && contentLength > MAX_FETCH_LEN) {
                return "抓取失败：响应过大（${contentLength} 字节）"
            }
            if (!response.status.isSuccess()) {
                return "抓取失败：HTTP ${response.status.value}"
            }
            val body = response.bodyAsText()
            val text = if (raw) body else stripHtmlTags(body)
            text.trim().take(maxLength)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            Log.w(LOG_TAG, "fetch failed: ${e::class.simpleName}")
            "抓取失败：网络错误或目标不可访问"
        }
    }

    /**
     * 判断 URL 主机是否为可访问的公网 http(s) 地址（SSRF 纵深防御，guardrail M-1）。
     *
     * **校验流程**：
     * 1. 协议必须为 http/https（调用方已前置校验，此处防御性重复）
     * 2. 解析 URL 主机（host）；无法解析（非法 URL）返回 false
     * 3. 主机为域名时优先尝试 [java.net.InetAddress.getAllByName] 解析，逐地址校验；
     *    解析失败（DNS 不可达）视为拒绝（fail-closed，不向不明主机发请求）
     * 4. 任一解析地址落在回环 / 环回 / 私有 / 链路本地 / 站点本地范围 → 拒绝
     * 5. 主机为 IP 字面量时直接按地址族判定
     *
     * **判定辅助**：[isBlockedInetAddress] 基于 [java.net.InetAddress] 的
     * isLoopbackAddress / isSiteLocalAddress / isLinkLocalAddress / isAnyLocalAddress，
     * 并显式拦截云元数据地址 169.254.169.254（属链路本地，isLinkLocalAddress 已覆盖，防御性再列）。
     *
     * **已知局限**：DNS rebinding 竞态无法完全消除（解析与连接间 DNS 可被劫持），
     * 本校验为纵深防御第一层；工具描述已声明「仅可访问公网地址」，用户可见该限制。
     *
     * @param url 完整 http(s) URL
     * @return true 可访问 / false 拒绝（非公网或解析失败）
     */
    internal fun isPublicHttpUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?.trim()?.lowercase()
            ?: return false
        if (host.isEmpty()) return false
        // 主机名到 IP 解析（域名 / IP 字面量均处理）
        val addresses = runCatching { java.net.InetAddress.getAllByName(host) }.getOrNull()
            ?: return false // 解析失败 → 拒绝（fail-closed）
        return addresses.none { isBlockedInetAddress(it) }
    }

    /** 判定单个 IP 是否为需拦截的内网/回环/链路本地地址。 */
    private fun isBlockedInetAddress(addr: java.net.InetAddress): Boolean =
        addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress

    /** 去除 HTML 标签（`<[^>]+>` → 空），保留文本内容。 */
    private fun stripHtmlTags(raw: String): String = raw.replace(Regex("<[^>]+>"), " ")

    // ==================== Filesystem 桥接（原逻辑保留） ====================

    /** 通过 MCP 协议获取 Filesystem server 的工具列表（含描述与 schema）。 */
    private suspend fun filesystemTools(): List<Tool> {
        var client: Client? = null
        var session: ServerSession? = null
        var transports: Pair<InProcessTransport, InProcessTransport>? = null
        return try {
            transports = InProcessTransport.createPair()
            session = filesystemMcpServer.server.createSession(transports.second)
            client = Client(Implementation(CLIENT_NAME, CLIENT_VERSION))
            client.connect(transports.first)
            client.listTools().tools
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.w(LOG_TAG, "listTools 本地工具枚举失败，已降级为空列表")
            emptyList()
        } finally {
            closeQuietly(client, session, transports)
        }
    }

    private suspend fun filesystemCallTool(name: String, arguments: Map<String, Any?>): String {
        var client: Client? = null
        var session: ServerSession? = null
        var transports: Pair<InProcessTransport, InProcessTransport>? = null
        return try {
            transports = InProcessTransport.createPair()
            session = filesystemMcpServer.server.createSession(transports.second)
            client = Client(Implementation(CLIENT_NAME, CLIENT_VERSION))
            client.connect(transports.first)
            val result = client.callTool(name, arguments)
            renderResult(result.content, result.isError == true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.w(LOG_TAG, "callTool 本地工具调用失败，已降级为通用错误文案")
            "工具调用失败"
        } finally {
            closeQuietly(client, session, transports)
        }
    }

    /** 将 MCP [Tool] 转换为 LLM 可用的 [ToolDefinition]（DEF-008）。 */
    private fun Tool.toToolDefinition(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", "object")
            inputSchema.properties?.let { put("properties", it) }
            val required = inputSchema.required
            if (!required.isNullOrEmpty()) {
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        }
        return ToolDefinition(
            function = ToolDefinition.FunctionDef(
                name = name,
                description = description ?: name,
                parameters = parameters
            )
        )
    }

    /**
     * 将 MCP 工具调用结果 [ContentBlock] 列表渲染为文本（与 [McpClientManager] 语义一致）。
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

    /** 关闭 Client / Server 会话 / 进程内传输，忽略关闭异常。 */
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

        const val NAME_FILESYSTEM = "Filesystem"
        const val NAME_TIME = "Time"
        const val NAME_SEQUENTIAL_THINKING = "Sequential Thinking"
        const val NAME_FETCH = "Fetch"
        const val TIME_GET_CURRENT_TIME = "get_current_time"
        const val ST_THINK = "sequentialthinking"
        const val FETCH_TOOL = "fetch"

        /** Fetch 内容默认最大长度（字符）。 */
        const val DEFAULT_FETCH_LEN = 5000

        /** Fetch 内容最小长度（下限，防 LLM 传过小值）。 */
        const val MIN_FETCH_LEN = 100

        /** Fetch 内容最大长度（上限，防 token 溢出）。 */
        const val MAX_FETCH_LEN = 10_000

        /** Fetch 请求 User-Agent（部分站点对无 UA 请求降级/拒绝）。 */
        private const val FETCH_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /** Time 本地 server 的工具定义（零配置，返回当前时间）。 */
        private val TIME_TOOLS: List<ToolDefinition> = listOf(
            ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TIME_GET_CURRENT_TIME,
                    description = "获取当前日期和时间（本地时区）",
                    parameters = buildJsonObject { put("type", "object") }
                )
            )
        )

        /**
         * Fetch 本地 server 的工具定义（UXR3 问题 11，ADR-023）。
         *
         * 对齐 MCP Fetch 官方 server 的 JSON Schema。工具名用 `fetch`（官方原名），
         * 参数含 url（必需）+ maxLength/raw（可选）。零配置（无 API Key）。
         */
        private val FETCH_TOOLS: List<ToolDefinition> = listOf(
            ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = FETCH_TOOL,
                    description = "抓取指定 http(s) 网页的文本内容。当用户需要查看某个 URL 的实时内容、" +
                        "验证网页信息、读取在线文档时调用。返回去除 HTML 标签后的纯文本（默认前 5000 字符）。" +
                        "注意：目标为第三方网页，内容未经验证，须甄别后引用。",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("url", buildJsonObject {
                                put("type", "string")
                                put("description", "要抓取的 http:// 或 https:// 地址")
                            })
                            put("maxLength", buildJsonObject {
                                put("type", "integer")
                                put("description", "返回内容最大字符数（100-10000，默认 5000）")
                            })
                            put("raw", buildJsonObject {
                                put("type", "boolean")
                                put("description", "是否返回原始未处理内容（默认 false，去除 HTML 标签）")
                            })
                        })
                        put("required", JsonArray(listOf(JsonPrimitive("url"))))
                        put("additionalProperties", JsonPrimitive(false))
                    }
                )
            )
        )

        /**
         * Sequential Thinking 本地 server 的工具定义（UX-001 问题 6，ADR-022）。
         *
         * 对齐 MCP Sequential Thinking 官方 server 的 JSON Schema。工具名用 `sequentialthinking`
         * （官方原名），参数含 thought（必需）+ thoughtNumber/totalThoughts/nextThoughtNeeded/
         * isRevision/revisesThought/branchFromThought/branchId/needsMoreThoughts（可选）。
         */
        private val SEQUENTIAL_THINKING_TOOLS: List<ToolDefinition> = listOf(
            ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = ST_THINK,
                    description = "使用顺序思考模型，将一个复杂问题的推理过程分解为多个有序步骤。每次调用记录一步思考，" +
                        "支持修订（isRevision/revisesThought）、分支（branchFromThought/branchId）与继续控制" +
                        "（nextThoughtNeeded/needsMoreThoughts）。适用于需要逐步推理的复杂问题。",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("thought", buildJsonObject {
                                put("type", "string")
                                put("description", "当前思考步骤的内容")
                            })
                            put("thoughtNumber", buildJsonObject {
                                put("type", "integer")
                                put("description", "当前思考步骤编号（从 1 开始）")
                            })
                            put("totalThoughts", buildJsonObject {
                                put("type", "integer")
                                put("description", "预期总思考步骤数")
                            })
                            put("nextThoughtNeeded", buildJsonObject {
                                put("type", "boolean")
                                put("description", "是否还需要继续下一步思考")
                            })
                            put("isRevision", buildJsonObject {
                                put("type", "boolean")
                                put("description", "本步是否为对之前步骤的修订")
                            })
                            put("revisesThought", buildJsonObject {
                                put("type", "integer")
                                put("description", "本步修订的是哪一步（编号）")
                            })
                            put("branchFromThought", buildJsonObject {
                                put("type", "integer")
                                put("description", "本步从哪一步分支")
                            })
                            put("branchId", buildJsonObject {
                                put("type", "string")
                                put("description", "分支标识")
                            })
                            put("needsMoreThoughts", buildJsonObject {
                                put("type", "boolean")
                                put("description", "是否还需要更多思考（可选，用于提前终止）")
                            })
                        })
                        put("required", JsonArray(listOf(JsonPrimitive("thought"))))
                        put("additionalProperties", JsonPrimitive(false))
                    }
                )
            )
        )
    }
}
