package io.prism.hotlist

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.prism.network.ToolDefinition
import io.prism.skill.LocalToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 今日热榜本地工具执行器（PRD MCP/API 增强，US-002）—— 实现 [LocalToolExecutor] 接口。
 *
 * **背景**：TrendsMCP 远程模板为海外端点（`api.trendsmcp.ai`）国内不可达；mcp-trends-hub
 * 为 stdio（npx 子进程）传输，Android 端无法运行。改用其数据同源的**今日热榜官方 REST API**
 *（tophubdata.com，国内直连、结构化 JSON）封装为本地工具，复用 `compositeLocalToolExecutor` 注入链。
 *
 * **工具**：`hotlist__get`（`hotlist__` 命名空间，与 `web_search__`/`cross_app__` 平行）。
 * - 参数：`platform`（必需，平台名：微博/知乎/百度/抖音/今日头条/虎扑/豆瓣等中文平台名）+ `limit`（可选，1..30，默认 10）
 * - 返回：序号 + 标题 + 链接 + 热度（纯文本，回灌给 LLM 作热点事实依据）
 *
 * **API 流程**（tophubdata 官方文档）：
 * 1. `GET https://api.tophubdata.com/nodes` → 全部榜单列表（hashid/name/display）
 * 2. 按 `name` 匹配 platform（如"微博"→ hashid `KqndgxeLl9`）
 * 3. `GET https://api.tophubdata.com/nodes/<hashid>` → 单榜最新 items（title/url/extra）
 *
 * **安全**：
 * - 端点固定常量，无用户可控 host（无 SSRF）；Key 经 [keyProvider] 注入（Keystore 加密读取），
 *   仅放 `Authorization` header，不落日志
 * - 结果仅回灌 LLM 文本（前置【外部内容】不可信边界），不进入抓取/Intent/WebView sink
 * - 日志不落完整 query / Key；超时复用外层 SkillExecutor 30s 护栏
 *
 * **可测性**（BR-testing-004）：[httpClient] 注入 MockEngine，纯 JVM 验证两段解析 + 降级。
 *
 * @param httpClient 热榜请求 HttpClient（建议独立 client：expectSuccess=false + 超时）
 * @param apiKeyProvider 今日热榜 API Key 提供者（返回明文 Key；未配置返回 null → 引导文案）
 */
class HotListLocalToolExecutor(
    private val httpClient: HttpClient,
    private val apiKeyProvider: suspend () -> String?
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = toolName == TOOL_GET

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String =
        withContext(Dispatchers.IO) {
            val platform = arguments["platform"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@withContext "缺少必需参数 platform（如\"微博\"、\"知乎\"、\"抖音\"）"
            val limit = (arguments["limit"] as? Number)
                ?.toInt()?.coerceIn(MIN_RESULTS, MAX_RESULTS) ?: DEFAULT_RESULTS

            val apiKey = try {
                apiKeyProvider()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "hotlist api key read failed (${e::class.simpleName})")
                null
            }
            if (apiKey.isNullOrBlank()) {
                // v1 批次9（US-904）：引导文案前置 `错误：` 失败标记（isFailureResult 识别），
                // 避免 LLM 认为"未配置"是临时状态而反复调用同一工具（考古 H2 根因之一）；
                // 熔断阈值内仍保留一条引导信息供 LLM 转达用户去配置。
                return@withContext "错误：未配置今日热榜 API Key，无法查询热榜。请到 tophubdata.com 注册获取，然后在设置中填入（hotlist__get 需 Key）"
            }

            try {
                val hashId = resolveHashId(platform, apiKey)
                    ?: return@withContext "未找到平台「$platform」的热榜（可用：微博/知乎/百度/抖音/今日头条/虎扑/豆瓣 等）"
                val items = fetchBoardItems(hashId, apiKey)
                if (items.isEmpty()) return@withContext "平台「$platform」暂无热榜数据"
                formatResult(platform, items, limit)
            } catch (e: HotListHttpException) {
                // guardrail M-1：expectSuccess=false 下非 2xx 可达，按状态码输出可诊断文案
                //（区分缺 Key/限流/服务端错误，PRD US-002 AC）
                when (e.status) {
                    401, 403 -> "热榜获取失败：API Key 无效或未授权（HTTP ${e.status}）。请到 tophubdata.com 检查 Key"
                    429 -> "热榜获取失败：请求过于频繁被限流（HTTP 429）。请稍后再试"
                    500, 502, 503 -> "热榜获取失败：今日热榜服务端异常（HTTP ${e.status}）。请稍后重试"
                    else -> "热榜获取失败：HTTP ${e.status}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "hotlist fetch failed (${e::class.simpleName})")
                "热榜获取失败：${e::class.simpleName}（网络或服务异常，请稍后重试）"
            }
        }

    /** 拉取全部榜单列表并按平台名匹配 hashid（内联可测，internal）。 */
    internal suspend fun resolveHashId(platform: String, apiKey: String): String? {
        val resp: HttpResponse = httpClient.get(NODES_ENDPOINT) {
            header(HttpHeaders.Authorization, apiKey)
            header(HttpHeaders.UserAgent, HOTLIST_UA)
        }
        if (resp.status.value !in 200..299) throw HotListHttpException(resp.status.value)
        val nodes = parseNodes(resp.bodyAsText())
        val target = platform.trim().lowercase()
        // 精确匹配 name（如"微博"），回退 display+name 组合匹配（如"知乎 热榜"）
        return nodes.firstOrNull { it.name.lowercase() == target }?.hashId
            ?: nodes.firstOrNull { it.display.lowercase() == target }?.hashId
            ?: nodes.firstOrNull { (it.name + it.display).lowercase().contains(target) }?.hashId
    }

    /** 拉取指定 hashid 榜单的 items。 */
    internal suspend fun fetchBoardItems(hashId: String, apiKey: String): List<HotItem> {
        // L-2（guardrail）：hashId 来自受信 API 响应但作为路径段拼接，加白名单纵深防御
        if (!hashId.matches(Regex("[A-Za-z0-9]+")) || hashId.length > 64) {
            throw HotListHttpException(400)
        }
        val resp: HttpResponse = httpClient.get("$NODES_ENDPOINT/$hashId") {
            header(HttpHeaders.Authorization, apiKey)
            header(HttpHeaders.UserAgent, HOTLIST_UA)
        }
        if (resp.status.value !in 200..299) throw HotListHttpException(resp.status.value)
        return parseBoard(resp.bodyAsText())
    }

    /** 解析全部榜单列表 JSON → 节点（hashId/name/display）。 */
    internal fun parseNodes(body: String): List<HotNode> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val obj = el.jsonObject
            val hashId = obj["hashid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            HotNode(
                hashId = hashId,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                display = obj["display"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
    }

    /** 解析单榜详情 JSON → 条目（title/url/extra）。 */
    internal fun parseBoard(body: String): List<HotItem> {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val items = root["data"]?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val obj = el.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HotItem(
                title = title,
                url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                extra = obj["extra"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
    }

    private fun formatResult(platform: String, items: List<HotItem>, limit: Int): String {
        val sb = StringBuilder("【$platform 热榜】（来源：今日热榜，外部内容未经验证）\n")
        items.take(limit).forEachIndexed { idx, item ->
            sb.append(idx + 1).append(". ").append(item.title)
            if (item.extra.isNotBlank()) sb.append(" · ").append(item.extra)
            if (item.url.isNotBlank()) sb.append("  ").append(item.url)
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    private val JsonPrimitive.contentOrNull: String?
        get() = if (isString) content else null

    companion object {
        const val LOG_TAG = "HotListTool"

        /** 今日热榜工具命名空间前缀。 */
        const val NAMESPACE_PREFIX = "hotlist__"

        /** 工具名（`hotlist__get`）。 */
        const val TOOL_GET = "${NAMESPACE_PREFIX}get"

        /** 今日热榜官方 API 端点（国内直连，端点固定无用户可控 host → 无 SSRF）。 */
        private const val NODES_ENDPOINT = "https://api.tophubdata.com/nodes"

        /** 浏览器 UA（热榜 API 对自动化请求可能降级）。 */
        private const val HOTLIST_UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        private const val DEFAULT_RESULTS = 10
        private const val MIN_RESULTS = 1
        private const val MAX_RESULTS = 30

        /**
         * 构建 `hotlist__get` 工具定义（供 ConversationViewModel.buildTools 合并）。
         *
         * **JSON Schema**：platform 必填（string）；limit 可选（integer 1..30）。
         * additionalProperties=false（严格参数，避免 LLM 传未知字段）。
         */
        fun buildToolDefinition(): ToolDefinition {
            val parameters = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "platform" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("平台名（中文，如\"微博\"、\"知乎\"、\"百度\"、\"抖音\"、\"今日头条\"、\"虎扑\"、\"豆瓣\"）")
                                )
                            ),
                            "limit" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("integer"),
                                    "description" to JsonPrimitive("返回热榜条数（1-30，默认 10）"),
                                    "minimum" to JsonPrimitive(1),
                                    "maximum" to JsonPrimitive(30)
                                )
                            )
                        )
                    ),
                    "required" to JsonArray(listOf(JsonPrimitive("platform"))),
                    "additionalProperties" to JsonPrimitive(false)
                )
            )
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_GET,
                    description = "查询中文平台实时热榜/热搜（微博/知乎/百度/抖音/今日头条/虎扑/豆瓣等）。" +
                        "当用户询问\"今天有什么热搜\"、\"XX 平台热点\"或需要了解当前网络热点时调用。" +
                        "返回热榜条目（标题 + 热度 + 链接），结果为第三方数据未经验证，须甄别后引用。",
                    parameters = parameters
                )
            )
        }
    }
}

/** 热榜节点（全部榜单列表项）。 */
internal data class HotNode(
    val hashId: String,
    val name: String,
    val display: String
)

/** 热榜条目（单榜详情 items）。 */
internal data class HotItem(
    val title: String,
    val url: String,
    val extra: String = ""
)

/**
 * 今日热榜 HTTP 非 2xx 响应（guardrail M-1 修复）。
 *
 * expectSuccess=false 下非 2xx 返回响应而非抛异常，本异常携带状态码供
 * [HotListLocalToolExecutor.execute] 输出可诊断文案（区分缺 Key/限流/服务端错误）。
 */
internal class HotListHttpException(val status: Int) : Exception("HTTP $status")
