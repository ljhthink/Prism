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
import net.dankito.readability4j.Readability4J

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
    private val fetchHttpClient: io.ktor.client.HttpClient? = null,
    /**
     * US-1506（v1 批次15 B1）：WebView 渲染抓取第三级降级开关读取器。
     *
     * 消费 DataStore key `settings_webview_fetch_enabled`（Boolean，默认 false，由设置仓库提供，
     * 设置页 UI 由并行实现方负责）。默认 `{ false }` = 降级链关闭，行为与现状完全一致（向后兼容）。
     */
    private val webviewFetchEnabledProvider: suspend () -> Boolean = { false },
    /**
     * US-1506：WebView 渲染器（null = 降级链不可用，行为同开关关闭）。
     * 生产接线：[io.prism.PrismApplication.localMcpToolProvider]（webviewFetchEnabledProvider +
     * renderer 已注入，开关默认 false）。
     */
    private val webviewFetchRenderer: WebViewHtmlRenderer? = null
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
        // v1 批次5/6 修复（Issue 1，真机证据 UnknownServiceException）：Android 9+（targetSdk>28）
        // 默认拦截公网**明文 http**（`network_security_config` 仅放行 localhost/127.0.0.1），
        // 此前公网 http 请求一步步走到 OkHttp 即抛 `UnknownServiceException: CLEARTEXT...`，
        // 被当成"反爬失败"，LLM 反复重试。公网站点绝大多数支持 https，先把 http 升级为 https
        // 尝试（同主机、SSRF 复检），绕开明文拦截而不放宽全局明文（ADR-004 安全边界不变）。
        val upgraded = if (rawUrl.startsWith("http://")) {
            "https://" + rawUrl.removePrefix("http://")
        } else {
            rawUrl
        }
        if (!isPublicHttpUrl(upgraded)) {
            return "仅支持抓取公网地址（已拒绝内网/本机地址）"
        }
        val maxLength = (arguments["maxLength"] as? Number)
            ?.toInt()?.coerceIn(MIN_FETCH_LEN, MAX_FETCH_LEN) ?: DEFAULT_FETCH_LEN
        val raw = arguments["raw"] as? Boolean ?: false
        // PRD MCP/API 增强（US-003）：Jina Reader 增强——直抓被 JS 渲染/反爬拦截时，
        // 用 r.jina.ai/<url> 转出干净 Markdown（免 Key、开箱即用，20 RPM）。
        // 目标 URL 已过 isPublicHttpUrl SSRF 校验；r.jina.ai 端点固定常量。
        // guardrail M-2：Jina 失败（非 2xx/网络/超量）降级到普通 Fetch 直抓，不直接返回失败。
        val useJinaReader = arguments["useJinaReader"] as? Boolean ?: false
        if (useJinaReader) {
            val jinaResult = fetchViaJinaReader(client, upgraded, maxLength, raw)
            if (jinaResult != null) return withUntrustedBoundary(jinaResult)
            // 降级：继续走下方直抓路径（fetchWithRedirects + SSRF + 内容纯度判定）
        }

        return try {
            // UXR11 U3（ADR-033）：fetchWithRedirects 手动跟随 3xx 重定向（每次重定向目标
            // 重新过 isPublicHttpUrl SSRF 校验），并携带完整浏览器请求头降低反爬拦截率。
            val response: io.ktor.client.statement.HttpResponse =
                fetchWithRedirects(client, upgraded, FETCH_HTTP_HEADERS, 0)
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
                val status = response.status.value
                // US-1506（v1 批次15 B1）：403/503（反爬/人机挑战页）且设置开关开启时，
                // 先尝试 WebView 渲染第三级降级；失败仍返回原可诊断文案（向后兼容）。
                // 其余状态不触发：404 内容确实不存在、429 限流（渲染也无意义且放大请求）。
                if (status == 403 || status == 503) {
                    tryWebviewFetch(upgraded, maxLength)?.let { return it }
                }
                return when (status) {
                    403 -> "抓取失败：目标站点拒绝访问（403，可能反爬或需登录）。请勿反复重试同一 URL，改用其他来源或基于已有信息回答"
                    404 -> "抓取失败：目标页面不存在（404）。请勿反复重试，改用其他来源"
                    429 -> "抓取失败：目标站点限流（429）。请稍后再试或改用其他来源，勿连续抓取"
                    503 -> "抓取失败：目标站点返回人机验证/挑战页（503，如 Cloudflare「Just a moment」）。该页为验证或动态渲染页，当前抓取方式不可达，请改用其他来源"
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
            val text = if (raw) body else extractReadableText(body)
            // v1 真机反馈（Issue 2）：反爬系统有时返回 200 但内容为挑战页/动态渲染空壳/登录墙，
            // 直接回灌 LLM 会得到无意义脚本或误导性"页面为空"。做内容纯度判定，命中即降级提示。
            // v1 批次9（US-903）：纯标签剥离后判空壳的，再尝试**本地 HTML 主干提纯**（提取
            // article/main/标题/段落主干文本），提纯后仍有正文才判定有效；二者皆空才返回失败。
            // 这样对"正文在 HTML 里但 script/nav 干扰"的静态页，可避免误判空壳（此前 JS 渲染
            // 与静态页混为一谈，静态页被误杀 → 用户感知"Fetch 几乎不可用"）。
            if (!raw && isAntiBotOrEmpty(text)) {
                // US-1506（v1 批次15 B1）：200 空壳（JS 动态渲染/登录墙）且设置开关开启时，
                // 先尝试 WebView 渲染第三级降级；失败仍返回原可诊断文案（向后兼容）。
                tryWebviewFetch(upgraded, maxLength)?.let { return it }
                return "抓取失败：页面无有效正文（可能是 JS 动态渲染、登录墙或人机验证页）。请改用其他来源或基于已有信息回答"
            }
            // L-2（guardrail TKN-V1B15-GUARDRAIL-001）：成功回灌统一前置【外部内容】不可信边界
            //（与搜索路径 formatSearchResult 同语义，prompt injection 纵深防御）
            text.trim().take(maxLength).let { withUntrustedBoundary(it) }
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            // ALM-001（v1 批次6 Issue 1）：记录**脱敏 URL**（host+path 即可定位）与异常类型，
            // 便于区分"明文拦截/超时/DNS/握手/解析"各层失败；此前只记 simpleName，
            // 真机无法判断到底是反爬还是明文拦截。
            Log.w(LOG_TAG, "fetch failed url=${sanitizeUrlForLog(upgraded)} err=${e::class.simpleName}: ${e.message?.take(160)}")
            "抓取失败：网络错误或目标不可访问"
        }
    }

    /**
     * PRD MCP/API 增强（US-003）：Jina Reader 抓取（`https://r.jina.ai/<url>` → 干净 Markdown）。
     *
     * **背景**：内置 Fetch 直抓无法处理 JS 动态渲染/Cloudflare 反爬（返回挑战壳/空壳）。
     * Jina Reader 免 Key 开箱即用（20 RPM），URL 前缀 `r.jina.ai/<url>` 服务端渲染后返回
     * LLM 友好的 Markdown，对标 Firecrawl 的 scrape 核心用途。
     *
     * **安全**：
     * - 目标 URL 已在调用前过 [isPublicHttpUrl] SSRF 校验（仅公网可达）
     * - `r.jina.ai` 端点固定常量，无用户可控 host
     * - 返回体限读 [MAX_FETCH_READ_CAP]，日志经 [sanitizeUrlForLog]（不落完整 query/userinfo）
     *
     * @param client Fetch HttpClient（expectSuccess=false + 15s 超时，可复用）
     * @param targetUrl 已校验的公网 URL
     * @param maxLength 返回内容最大字符数
     * @param raw 是否返回原始内容（Jina 默认已 Markdown 化，raw=true 时仅跳过 strip 逻辑占位）
     */
    private suspend fun fetchViaJinaReader(
        client: io.ktor.client.HttpClient,
        targetUrl: String,
        maxLength: Int,
        raw: Boolean
    ): String? {
        // L-4（guardrail TKN-V1B9-GUARDRAIL-001）：URLEncoder 是 form 编码（空格→'+'），
        // 拼进 URL 路径会失真；替换为 RFC 3986 的 '%20'，保证含空格目标 URL 经 Jina 正确转码。
        val jinaUrl = JINA_READER_ENDPOINT +
            java.net.URLEncoder.encode(targetUrl, "UTF-8").replace("+", "%20")
        return try {
            val response = client.get(jinaUrl) {
                // 浏览器 UA（Jina 对裸 UA 可能降级）；Referer 模拟"从网页点进"来源
                header(io.ktor.http.HttpHeaders.UserAgent, FETCH_HTTP_HEADERS[io.ktor.http.HttpHeaders.UserAgent] ?: DEFAULT_UA)
                header(io.ktor.http.HttpHeaders.Referrer, JINA_REFERER)
            }
            val contentLength = response.contentLength()
            if (contentLength != null && contentLength > MAX_FETCH_READ_CAP) {
                return null // 降级直抓
            }
            if (!response.status.isSuccess()) {
                Log.w(LOG_TAG, "jina reader http ${response.status.value} for ${sanitizeUrlForLog(targetUrl)}, fallback to direct fetch")
                return null // 降级直抓
            }
            val body = try {
                val bytes = response.bodyAsChannel()
                    .readRemaining(MAX_FETCH_READ_CAP.toLong() + 1)
                    .readByteArray()
                if (bytes.size > MAX_FETCH_READ_CAP) {
                    return null // 降级直抓
                }
                bytes.toString(Charsets.UTF_8)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "jina reader body read failed: ${e::class.simpleName}")
                return null // 降级直抓
            }
            val text = if (raw) body else stripHtmlTags(body)
            text.trim().take(maxLength)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            // v1 批次9（US-903，B1 根因）：真机实测 r.jina.ai 国内不可达（ConnectTimeout/
            // ConnectException 连接海外 IP 失败），此日志需明确标注"国内不可达"以区分反爬，
            // 避免误导（此前按"fetch failed"处理，用户误以为反爬问题）。
            Log.w(
                LOG_TAG,
                "jina reader fetch failed url=${sanitizeUrlForLog(targetUrl)} err=${e::class.simpleName} " +
                    "(r.jina.ai 国内不可达, fallback to direct fetch + local extraction)"
            )
            null // 降级直抓（fetchUrl 内再走本地 HTML 提纯）
        }
    }

    /**
     * US-1506（v1 批次15 B1）：WebView 渲染抓取第三级降级。
     *
     * **触发条件**（由 [fetchUrl] 在调用前判定）：直抓返回 403/503 诊断文案，或 200 内容
     * 经 [isAntiBotOrEmpty] 判空壳，且设置开关（`settings_webview_fetch_enabled`，默认
     * false）已开启。其余失败（404/429/网络错误）不触发。
     *
     * **安全红线**：
     * - 仅 https 公网 URL：入参 `url` 已在 [fetchUrl] 开头过 [isPublicHttpUrl] SSRF 校验；
     *   此处防御性要求 https 前缀（[WebViewFetchRenderer.render] 内部亦二次校验）
     * - 渲染结果仅取 HTML 文本提纯，不 eval 注入；cookie/存储内容不落日志
     * - 日志 URL 经 [sanitizeUrlForLog] 脱敏（CWE-532）
     *
     * **降级语义**：开关关闭 / 渲染失败 / 提纯为空 / 渲染结果仍是挑战壳 → 返回 null，
     * 调用方回退原可诊断文案（行为向后兼容，开关默认 false 时零影响）。
     *
     * @param url 已过 isPublicHttpUrl 校验的 https 公网 URL
     * @param maxLength 返回内容最大字符数（与直抓路径同一 clamp 值）
     * @return 提纯后的正文；null = 放弃降级，调用方返回原诊断文案
     */
    private suspend fun tryWebviewFetch(url: String, maxLength: Int): String? {
        if (!webviewFetchEnabledProvider()) return null
        if (!url.startsWith("https://")) return null // SSRF 红线：WebView 仅 https 公网
        val renderer = webviewFetchRenderer ?: return null
        Log.i(LOG_TAG, "webview fetch fallback attempt url=${sanitizeUrlForLog(url)}")
        val html = try {
            renderer.render(url)
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            Log.w(LOG_TAG, "webview render crashed url=${sanitizeUrlForLog(url)} err=${e::class.simpleName}")
            null
        } ?: return null
        // 渲染后 HTML 走同一提纯链（Readability4J 主路径 + 正则降级），非空才回灌
        val text = extractReadableText(html)
        if (isAntiBotOrEmpty(text)) {
            // 渲染后仍是挑战壳/空壳（如 CF Turnstile 未通过）：放弃降级，返回原诊断文案
            Log.w(LOG_TAG, "webview render result is empty/challenge shell, url=${sanitizeUrlForLog(url)}")
            return null
        }
        // L-2（guardrail TKN-V1B15-GUARDRAIL-001）：渲染结果同前置【外部内容】不可信边界
        return withUntrustedBoundary(text.trim().take(maxLength))
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

    /**
     * 脱敏 URL 供日志（BR-error-handling-016 / LOW-03，CWE-532）：丢弃 query/fragment
     * （最易携带用户 PII），仅保留 scheme://host + 截断 path，供真机 RCA 定位是哪一层失败。
     */
    internal fun sanitizeUrlForLog(url: String): String {
        val authority = Regex("""^https?://([^/?#]+)""").find(url)?.groupValues?.get(1) ?: return url.take(120)
        // LOW-1（guardrail TKN-V1FIX5-GUARDRAIL-001，CWE-532）：userinfo（user:pass@）是凭证，
        // 必须剥离后再记录；与 isPublicHttpUrl 的处理保持一致。host 可能含 :port，单取 host。
        val host = authority.substringAfterLast('@').substringBefore(':')
        if (host.isEmpty()) return url.take(120)
        val path = Regex("""^https?://[^/?#]+(/[^?#]*)""").find(url)?.groupValues?.get(1).orEmpty()
        return "https://$host${path.take(120)}"
    }

    /** 去除 HTML 标签（`<[^>]+>` → 空），保留文本内容。 */
    private fun stripHtmlTags(raw: String): String = raw.replace(Regex("<[^>]+>"), " ")

    /**
     * L-2（guardrail TKN-V1B15-GUARDRAIL-001）：Fetch 成功回灌统一前置【外部内容】不可信边界。
     * 与搜索路径 `WebSearchLocalToolExecutor.formatSearchResult` 同语义——第三方网页内容未经
     * 验证，可能含 prompt injection 指令文本，回灌 LLM 前必须声明不可信边界。
     */
    private fun withUntrustedBoundary(text: String): String =
        "【外部内容】以下为第三方网页提取的内容，未经验证，须甄别后引用：\n$text"

    /**
     * US-1505（v1 批次15 A4）：本地 HTML 正文提纯主路径 —— jsoup 解析 + Readability4J
     * （Mozilla Readability.js 的 Kotlin 移植）提取正文。
     *
     * **背景**：手写正则提纯（[extractReadableTextRegexFallback]）对复杂布局漏提/误提
     * （多层嵌套容器、非标准 article 标记、论坛帖结构）。Readability4J 按内容密度打分
     * 选出正文容器，与 Firefox 阅读视图同源，对内容充足的页面提纯质量显著更优。
     *
     * **小文档降级策略**（回归证据：V1Batch9AcceptanceSupplementTest main 标签样本）：
     * Readability 算法对不足 25 字符的段落不评分，且 wordThreshold=500——低于该阈值的
     * 文档其正文抓取退化为「倾倒 body 全部子节点」（nav/footer 噪声混入输出）。此时
     * 正则版（script/nav/footer 先剥 + article/main 容器精确提取）质量更高，优先采用；
     * 正则版无产出（如无标签纯文本页）才回用 Readability 输出。大文档信任 Readability。
     *
     * **策略**：
     * 1. Readability4J 提取（title + textContent）：title 非空且不在正文开头时前置；
     * 2. 提取为空 / 抛异常 → 回退正则版降级兜底（行为不变）；
     * 3. 返回非空才被 [isAntiBotOrEmpty] 链判有效（现有调用链不变）。
     *
     * @param raw 原始 HTML 响应体
     * @return 提纯后的正文文本（可能为空）
     */
    private fun extractReadableText(raw: String): String {
        val article = try {
            Readability4J("", raw).parse()
        } catch (e: Exception) {
            // 提纯库异常（畸形 HTML / 超元素上限等）属罕见路径，降级正则版不中断抓取
            Log.w(LOG_TAG, "readability extract failed, fallback to regex: ${e::class.simpleName}")
            return extractReadableTextRegexFallback(raw)
        }
        val title = article.title?.trim().orEmpty()
        val content = article.textContent?.trim().orEmpty()
        val best = if (content.length < READABILITY_WORD_THRESHOLD) {
            // 小文档：正则版优先（精确剔除噪声）；正则无产出才回用 Readability 输出
            val regexContent = extractReadableTextRegexFallback(raw)
            if (regexContent.isNotEmpty()) regexContent else content
        } else {
            content
        }
        return when {
            best.isEmpty() -> ""
            title.isNotEmpty() && !best.startsWith(title) -> "$title\n$best"
            else -> best
        }
    }

    /**
     * v1 批次9（US-903）：本地 HTML 主干文本提纯（正则版）——US-1505 后降级为
     * Readability4J 失败/空结果时的兜底路径（行为与原实现完全一致）。
     *
     * **策略**（借鉴调研结论：Jina Reader 本地等价物 = 取 `<article>/<main>/<h1-h6>/<p>`
     * 主干，Defuddle/Readability 思路的手写轻量版，零新增依赖）：
     * 1. 若存在 `<article>` 或 `<main>` 容器，优先提取其内部全部块级文本；
     * 2. 否则取全部 `<h1-h6>` 与 `<p>` 文本；
     * 3. 剔除 script/style/nav/header/footer/iframe 等非正文节点；
     * 4. 段落间保留换行（供 LLM 阅读），空白归一化。
     *
     * 无法解析出有效文本时返回空串（调用方按空壳处理，不误报成功）。
     *
     * @param raw 原始 HTML 响应体
     * @return 提纯后的正文文本（可能为空）
     */
    internal fun extractReadableTextRegexFallback(raw: String): String {
        val cleaned = raw
            .replace(Regex("(?is)<script[^>]*>[\\s\\S]*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>[\\s\\S]*?</style>"), " ")
            .replace(Regex("(?is)<nav[^>]*>[\\s\\S]*?</nav>"), " ")
            .replace(Regex("(?is)<header[^>]*>[\\s\\S]*?</header>"), " ")
            .replace(Regex("(?is)<footer[^>]*>[\\s\\S]*?</footer>"), " ")
            .replace(Regex("(?is)<iframe[^>]*>[\\s\\S]*?</iframe>"), " ")
            .replace(Regex("(?is)<form[^>]*>[\\s\\S]*?</form>"), " ")
        // 优先取正文主干容器（article > main > body 内标题+段落）
        val container = Regex("(?is)<article[^>]*>([\\s\\S]*?)</article>").find(cleaned)
            ?.groupValues?.get(1)
            ?: Regex("(?is)<main[^>]*>([\\s\\S]*?)</main>").find(cleaned)?.groupValues?.get(1)
            ?: cleaned
        // 提取标题与段落文本（保留段落换行）
        val sb = StringBuilder()
        for (m in Regex("(?is)<h[1-6][^>]*>([\\s\\S]*?)</h[1-6]>").findAll(container)) {
            val t = cleanBlock(m.groupValues[1])
            if (t.isNotEmpty()) sb.append(t).append("\n")
        }
        for (m in Regex("(?is)<p[^>]*>([\\s\\S]*?)</p>").findAll(container)) {
            val t = cleanBlock(m.groupValues[1])
            if (t.isNotEmpty()) sb.append(t).append("\n")
        }
        val text = sb.toString().trim()
        // 标题+段落为空 → 回退裸标签剥离（尽力而为，至少返回可见文本）
        return if (text.isEmpty()) stripHtmlTags(cleaned).trim() else text
    }

    /** 清理单个文本块：去标签 + 实体解码 + 空白归一化。 */
    private fun cleanBlock(raw: String): String =
        stripHtmlTags(raw)
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

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

    // internal（US-1506）：FETCH_USER_AGENT 供 WebViewFetchRenderer 复用（UA 单一事实来源）
    internal companion object {
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

        /** PRD MCP/API 增强（US-003）：Jina Reader URL 前缀端点（免 Key，固定常量无 SSRF）。 */
        private const val JINA_READER_ENDPOINT = "https://r.jina.ai/"

        /** Jina Reader 请求 Referer（模拟"从网页点进"来源，降反爬拦截）。 */
        private const val JINA_REFERER = "https://jina.ai/"

        /** 兜底浏览器 UA（FETCH_HTTP_HEADERS 缺失时）。 */
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        /**
         * 判定抓取到的正文是否为「无有效内容」：空白，或命中人机验证/登录墙/动态渲染壳特征。
         * 供 200 响应的内容纯度判定（v1 Issue 2），避免把无意义挑战页/空壳回灌 LLM。
         *
         * 分级判定（guardrail TKN-V1FIX-GUARDRAIL-001 发现 A 收敛）：
         * - 强特征（Cloudflare 等典型验证门户标记）独立命中即判空壳；
         * - 弱特征（中文泛词如"人机验证/异常流量"）需配合「正文极短」（近似纯挑战壳）才判定，
         *   避免把含"验证码/安全验证"等词的**正常长文**（登录教程/安全科普）误伤。
         */
        private fun isAntiBotOrEmpty(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return true
            val lower = t.lowercase()
            if (STRONG_CHALLENGE_MARKERS.any { it in lower }) return true
            if (t.length <= MAX_CHALLENGE_TEXT_LEN && WEAK_CHALLENGE_MARKERS.any { it in lower }) return true
            return false
        }

        /** 强特征：验证门户（Cloudflare 等）典型标记（小写匹配），独立命中即判定空壳。 */
        private val STRONG_CHALLENGE_MARKERS = listOf(
            "just a moment", "cf-browser-verification", "checking your browser",
            "verify you are human", "attention required", "cf-challenge",
            "access denied by security policy", "unusual traffic from your network"
        )

        /** 弱特征：需配合正文极短才判定的中文验证外壳标记。 */
        private val WEAK_CHALLENGE_MARKERS = listOf("人机验证", "滑动验证", "异常流量")

        /** 判定弱特征时允许的最大正文长度（字符）：超过即视为正常长文，不误判。 */
        private const val MAX_CHALLENGE_TEXT_LEN = 80

        /**
         * US-1505：Readability 提纯结果的可信长度下限（字符）。
         *
         * 与 Mozilla Readability 的 wordThreshold=500 对齐：低于该长度的文档，
         * Readability 抓取退化为「倾倒 body」路径（噪声混入），此时改用正则版精确提取。
         */
        private const val READABILITY_WORD_THRESHOLD = 500

        /** Fetch 请求 User-Agent（部分站点对无 UA 请求降级/拒绝）。v1 升级至 2026 主流移动 Chrome/126。 */
        internal const val FETCH_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * Fetch 请求浏览器典型请求头集合（降低反爬拦截率）。
         *
         * 反爬系统（Cloudflare/Paywall）常按请求头指纹识别自动化请求（缺 Accept /
         * Accept-Language / Sec-Fetch-* 等即判 bot）。网络调研（tech-selection-researcher，
         * 2026-08-19）：请求头层是与目标 SSR 站点反爬交战最廉价的入口。
         *
         * v1 真机反馈（Issue 2）优化：
         * 1. 补 `Sec-CH-UA` 系列（Client Hints）—— Cloudflare L3 层的核心判定依据；
         *    版本号与 [FETCH_USER_AGENT] 的 Chrome/126 **必须一致**，否则反而实锤 bot。
         * 2. 保持**刻意不设 Accept-Encoding**：Ktor OkHttp 在未手动设置该头时透明 gzip 解压，
         *    手动声明 `gzip, br` 会关掉 OkHttp 自动解压 → 响应乱码（与 UXR11 U4 同族风险）。
         */
        private val FETCH_HTTP_HEADERS: Map<String, String> = mapOf(
            "User-Agent" to FETCH_USER_AGENT,
            "sec-ch-ua" to "\"Chromium\";v=\"126\", \"Not/A)Brand\";v=\"8\", \"Google Chrome\";v=\"126\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-ch-ua-full-version-list" to "\"Chromium\";v=\"126.0.6478.122\", \"Not/A)Brand\";v=\"8\", \"Google Chrome\";v=\"126.0.6478.122\"",
            "sec-ch-ua-platform-version" to "\"13.0.0\"",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            // v1 真机二次修复（Issue 1）：部分站点做 Referer 校验，无 Referer 视为外链/机器人。
            // 用户流程通常是"搜索 → 点进结果页"，用 Bing 作 Referer 最贴近正常点击来源，
            // 可降低 403 概率。刻意不伪造为文本类 URL（站源 Referer 逻辑复杂易反噬）。
            "Referer" to "https://cn.bing.com/"
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
                        "若目标页为 JS 动态渲染/被反爬拦截（直抓返回空或验证页），可设 useJinaReader=true 走" +
                        "Jina Reader 渲染后转 Markdown。注意：目标为第三方网页，内容未经验证，须甄别后引用。",
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
                            put("useJinaReader", buildJsonObject {
                                put("type", "boolean")
                                put("description", "是否用 Jina Reader（r.jina.ai）渲染后转 Markdown（默认 false；直抓失败/动态页时设 true）")
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
