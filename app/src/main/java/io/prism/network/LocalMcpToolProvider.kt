package io.prism.network

import android.util.Log
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
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
            // UXR11 U3（ADR-033）：fetchWithRedirects 手动跟随 3xx 重定向（每次重定向目标
            // 重新过 isPublicHttpUrl SSRF 校验），并携带完整浏览器请求头降低反爬拦截率。
            val response: io.ktor.client.statement.HttpResponse =
                fetchWithRedirects(client, rawUrl, FETCH_HTTP_HEADERS, 0)
            // Content-Length 预检（防超大响应全量读入，guardrail L-3/M-1）
            val contentLength = response.contentLength()
            if (contentLength != null && contentLength > MAX_FETCH_LEN) {
                return "抓取失败：响应过大（${contentLength} 字节）"
            }
            if (!response.status.isSuccess()) {
                // R3（UXR10，ADR-032）：反爬场景可诊断文案。网络调研（webfetch-mcp / 官方
                // server-fetch 等 MCP 实现）确认：Fetch 被 Cloudflare/Paywall 等反爬系统
                // 拦截（403）或被目标站限流（429）是**常态**，并非 URL 错误。此前统一返回
                // "抓取失败：HTTP xxx" 会让 LLM 误以为 URL 写错而**反复重试同一 URL**，
                // 放大请求频率 → 叠加 LLM 端点（如 kimi RPM=3）限流。改为按状态码给出
                // 可诊断文案并**显式标注勿重试**，引导 LLM 换来源或降级。
                return when (response.status.value) {
                    403 -> "抓取失败：目标站点拒绝访问（403，可能反爬或需登录）。请勿反复重试同一 URL，改用其他来源或基于已有信息回答"
                    404 -> "抓取失败：目标页面不存在（404）。请勿反复重试，改用其他来源"
                    429 -> "抓取失败：目标站点限流（429）。请稍后再试或改用其他来源，勿连续抓取"
                    else -> "抓取失败：HTTP ${response.status.value}。请勿反复重试同一 URL，可改用其他来源"
                }
            }
            // Q-LOW-5 修复（guardrail TKN-UXR9-GUARDRAIL-002）：Content-Length 缺失/分块
            // 传输时 `bodyAsText()` 会全量读入（资源耗尽边界）。改用 channel 限读——
            // `readRemaining(MAX_FETCH_READ_CAP+1)` 最多读该上限字节即返回（不等待 EOF），
            // 读满即判定为病态超大响应并拒绝；正常响应（< MAX_FETCH_READ_CAP）照常读取，
            // 由下方 `text.take(maxLength)` 做常规截断（maxLength 语义不变）。
            val body = try {
                val bytes = response.bodyAsChannel()
                    .readRemaining(MAX_FETCH_READ_CAP.toLong() + 1)
                    .readByteArray()
                if (bytes.size > MAX_FETCH_READ_CAP) {
                    return "抓取失败：响应过大"
                }
                bytes.toString(Charsets.UTF_8)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "fetch body read failed: ${e::class.simpleName}")
                return "抓取失败：网络错误或目标不可访问"
            }
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
     * UXR11 U3（ADR-033）：Fetch 请求 + 手动跟随 3xx 重定向（SSRF 安全）。
     *
     * **背景**：很多网页（短链、http→https 升级、CMS 跳转）返回 3xx。fetchHttpClient 出于
     * SSRF 纵深防御**不安装 HttpRedirect 插件**（Q-LOW-3 不变量，重定向到内网地址的绕过
     * 不可达），此前 3xx 直接落入"抓取失败：HTTP 3xx"（LLM 误以为 URL 错而反复重试）。
     * 本函数手动跟随重定向，**每次重定向目标都重新过 [isPublicHttpUrl] SSRF 校验**
     * （fail-closed：目标非公网/解析失败则返回原始 3xx，不跟随）。
     *
     * - 请求头：完整浏览器典型头（[FETCH_HTTP_HEADERS]），降低 Cloudflare/Paywall 等
     *   反爬系统对"裸 UA 请求"的拦截率（网络调研 webfetch-mcp / readerfi：浏览器头是
     *   把 403 变 200 的最廉价手段；**不设 Accept-Encoding**，交由 OkHttp 透明 gzip 解压）。
     * - 重定向上限 [FETCH_REDIRECT_MAX]（3），防重定向环。
     * - 相对 Location：用 [java.net.URI.resolve] 与当前 URL 解析拼接。
     * - 跟随前取消 3xx 响应（丢弃 body 释放连接，Ktor HttpResponse 懒式 body）。
     * - CancellationException 重抛（BR-error-handling-007）。
     */
    private suspend fun fetchWithRedirects(
        client: io.ktor.client.HttpClient,
        url: String,
        headers: Map<String, String>,
        hop: Int
    ): io.ktor.client.statement.HttpResponse {
        val response = client.get(url) {
            headers.forEach { (k, v) -> header(k, v) }
        }
        if (hop >= FETCH_REDIRECT_MAX) return response
        val status = response.status.value
        if (status !in 300..399) return response
        val location = response.headers[io.ktor.http.HttpHeaders.Location] ?: return response
        val next = try {
            java.net.URI(url).resolve(location).toString()
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            null
        } ?: return response
        // 重定向目标必须重新过 SSRF 校验（fail-closed：非公网/解析失败则不跟随）
        if (!isPublicHttpUrl(next)) return response
        // 丢弃 3xx body 释放连接（懒式 HttpResponse 未消费会挂起连接）。经 bodyAsChannel 取消
        // 读取通道，避免 3xx body 残留占用连接；失败不阻断重定向（响应随连接回收释放）。
        try {
            response.bodyAsChannel().cancel(null)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            // 取消失败可忽略：HttpResponse 在调用链结束时由 engine 回收
        }
        return fetchWithRedirects(client, next, headers, hop + 1)
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
        // UXR9 Bug5 修复：改用正则提取 host（避免 `java.net.URI(url)` 对含中文/非 ASCII
        // 路径的 URL 抛 URISyntaxException 而误拒——如 `https://baike.baidu.com/item/昔涟`）。
        // S-1（guardrail TKN-UXR9-GUARDRAIL-001，CWE-918 SSRF fail-open 修复）：
        // 必须先剥离 userinfo（`scheme://user:pass@host`），否则 `http://evil.com:80@127.0.0.1/`
        // 会被截出 host=evil.com（公网放行）而 OkHttp 实际解析 userinfo 后发往 127.0.0.1。
        // L-4：IPv6 字面量 `[::1]:8080` 需先去方括号再校验（避免 `substringBefore(':')` 截出 `[`）。
        // 中文域名（IDN）主机经 IDN.toASCII 归一化后再解析。
        val authority = Regex("""^https?://([^/?#]+)""").find(url)?.groupValues?.get(1) ?: return false
        // Q-LOW-2 修复（guardrail TKN-UXR9-GUARDRAIL-002）：先剥离 userinfo 再判 IPv6 字面量。
        // 此前顺序（先判 `[` 后剥 userinfo）对 `user:pass@[::1]:8080` 会残留左方括号 `[::1`
        // → IDN 归一化失败 → fail-closed 拒绝（安全但主机提取不精确）。统一改为：
        // 1) substringAfterLast('@') 剥离 userinfo；2) 若剩余以 `[` 开头按 IPv6 字面量处理。
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) {
            // IPv6 字面量：`[::1]:8080` → `::1`
            val end = hostPort.indexOf(']')
            if (end < 0) return false // 未闭合的 `[` → 非法，拒绝（fail-closed）
            hostPort.substring(1, end)
        } else {
            // 剥离端口：`host:port` → `host`
            hostPort.substringBefore(':')
        }.trim().lowercase()
        if (host.isEmpty()) return false
        val asciiHost = try {
            java.net.IDN.toASCII(host)
        } catch (e: Exception) {
            return false // 非法主机名（无法 IDN 归一化）→ 拒绝（fail-closed）
        }
        // 主机名到 IP 解析（域名 / IP 字面量均处理）
        val addresses = runCatching { java.net.InetAddress.getAllByName(asciiHost) }.getOrNull()
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

        /**
         * Fetch 病态超大响应读上限（字节，Q-LOW-5）。
         *
         * 仅用于 Content-Length 缺失/分块传输时的**读取**硬上限（防恶意响应全量读入内存）。
         * 正常响应（< 1MB）照常读取并由 [MAX_FETCH_LEN] 截断；超过该上限才判定为病态拒绝。
         * 与 Content-Length 预检（[MAX_FETCH_LEN] 阈值）为两层独立防御。
         */
        const val MAX_FETCH_READ_CAP = 1_000_000

        /** Fetch 请求 User-Agent（部分站点对无 UA 请求降级/拒绝）。 */
        private const val FETCH_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /**
         * UXR11 U3（ADR-033）：Fetch 请求浏览器典型请求头集合（降低反爬拦截率）。
         *
         * 反爬系统（Cloudflare/Paywall）常按请求头指纹识别自动化请求（缺 Accept /
         * Accept-Language / Sec-Fetch-* 等即判 bot）。网络调研（webfetch-mcp / readerfi
         * 60-line MCP）：补全浏览器头是把 403 变 200 的最廉价手段。
         *
         * **刻意不设 Accept-Encoding**：Ktor OkHttp 在未手动设置该头时透明 gzip 解压，
         * 手动声明 `gzip, br` 会关掉 OkHttp 自动解压 → 响应乱码（与 UXR11 U4 的乱码同族风险）。
         */
        private val FETCH_HTTP_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to FETCH_USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1"
        )

        /**
         * UXR11 U3（ADR-033）：Fetch 手动重定向最大跳数（防重定向环）。
         */
        private const val FETCH_REDIRECT_MAX = 3

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
