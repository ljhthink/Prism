package io.prism.network

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * WebView 渲染抓取抽象（US-1506，v1 批次15 B1）。
 *
 * [LocalMcpToolProvider] 依赖此接口而非具体实现：单测可用替身（无需 Android Context /
 * Robolectric）验证降级触发链；生产由 [WebViewFetchRenderer] 提供。
 */
interface WebViewHtmlRenderer {

    /**
     * 渲染指定页面并返回渲染后的 HTML。
     *
     * @return 渲染后 HTML；超时 / 加载错误 / 结果为空 / 非 https URL → null
     *（调用方回退返回原可诊断文案，行为向后兼容）
     */
    suspend fun render(url: String): String?
}

/**
 * US-1506（v1 批次15 B1）：offscreen WebView 渲染抓取器 —— Fetch 第三级降级实现。
 *
 * **用途**：直抓被 Cloudflare 等反爬拦截（403/503）或返回 JS 动态渲染空壳时，
 * 用真实 WebView 执行页面 JS 后取 DOM，供 Readability4J 提纯正文。定位为
 * 「尽力而为第三级」——WebView 指纹仍可能被 CF 识别/Turnstile 需交互，失败即放弃。
 *
 * **生命周期与线程**：
 * - WebView 必须在主线程创建/操作（[withContext]（Dispatchers.Main））；
 * - 每次 [render] 创建独立 offscreen WebView（不 attach 任何可见视图），用应用 context；
 * - 严格生命周期：finally 中 stopLoading + destroy，防泄漏；
 * - 总超时 [timeoutMs]（页面加载 + JS 执行）覆盖全程，超时返回 null。
 *
 * **安全红线**：
 * - 仅加载 https URL（SSRF 防御纵深；完整 isPublicHttpUrl 公网校验由调用方
 *   [LocalMcpToolProvider.tryWebviewFetch] 在触发前完成，契约见其 KDoc）；
 * - JS 注入脚本为固定常量字符串（取 outerHTML），无用户可控内容，不存在 eval 注入；
 * - 自定义 UA 与 Fetch 直抓一致（[LocalMcpToolProvider.FETCH_USER_AGENT] 单一事实来源）；
 * - cookie 仅用于渲染（CF clearance 等站点状态），**不落日志**；
 * - 渲染进程崩溃（onRenderProcessGone）按进程隔离处理：标记失败并自行销毁，
 *   不影响主进程（PRD 验收要求）。
 *
 * @param appContext 应用 context（offscreen WebView 无需 Activity 宿主）
 * @param timeoutMs 渲染总超时（毫秒）
 */
open class WebViewFetchRenderer(
    private val appContext: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : WebViewHtmlRenderer {

    /** 提取渲染后 DOM 的固定 JS（无用户可控内容，不存在注入面）。 */
    private companion object {
        const val LOG_TAG = "WebViewFetchRenderer"
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val EXTRACT_DOM_JS = "document.documentElement.outerHTML"
    }

    override suspend fun render(url: String): String? {
        // SSRF 防御纵深：仅 https（完整公网校验是调用方契约，见类 KDoc）
        if (!isRenderableUrl(url)) return null
        return try {
            withTimeout(timeoutMs) {
                // WebView 必须主线程创建/操作（Android 硬性约束）
                withContext(Dispatchers.Main) { renderOnMainThread(url) }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(LOG_TAG, "webview render timeout after ${timeoutMs}ms")
            null
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            Log.w(LOG_TAG, "webview render failed err=${e::class.simpleName}")
            null
        }
    }

    /** 渲染 URL 校验（internal 供单测）：WebView 仅加载 https。 */
    internal fun isRenderableUrl(url: String): Boolean = url.startsWith("https://")

    /**
     * 主框架 URL 终态/导航校验（internal 供单测，M-1 修复）。
     *
     * **M-1（guardrail TKN-V1B15-GUARDRAIL-001，CWE-918 变体）**：页内 JS `location`/meta
     * refresh/服务端 302 可把 WebView 带到非公网地址（如 `http://127.0.0.1:11434`，命中
     * network_security_config 的 localhost 明文放行），内网响应体会被 outerHTML 提取回灌。
     * 与直抓路径「逐跳 isPublicHttpUrl」对齐：
     * - [shouldOverrideUrlLoading] 拦截页内导航（JS/链接类）；
     * - [onPageFinished] 对终态 URL 复验（服务端 302 不触发前者，由本兜底）。
     *
     * **主线程安全**：仅字符串级校验，**不做 DNS 解析**（onPageFinished 在主线程，
     * InetAddress 解析会触发 NetworkOnMainThreadException）；「公网 DNS 名解析到内网」
     * （rebinding）为既有已知局限，与直抓路径同口径（见 LocalMcpToolProvider KDoc）。
     */
    internal fun isFinalUrlAllowed(url: String?): Boolean {
        if (url.isNullOrEmpty() || !url.startsWith("https://")) return false
        val authority = Regex("""^https://([^/?#]+)""").find(url)?.groupValues?.get(1) ?: return false
        val hostPort = authority.substringAfterLast('@') // 剥 userinfo（防 user:pass@127.0.0.1 绕过）
        val host: String
        if (hostPort.startsWith("[")) {
            // IPv6 字面量：`[::1]:8080` → `::1`
            val end = hostPort.indexOf(']')
            if (end < 0) return false // 未闭合 `[` → 非法，拒绝（fail-closed）
            host = hostPort.substring(1, end)
            return host != "::1" // 仅拦回环（公网 v6 字面量放行）
        }
        host = hostPort.substringBefore(':').trim().lowercase()
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) return false
        val octets = host.split('.')
        val a = octets.firstOrNull()?.toIntOrNull() ?: return true // 域名（非 IP 字面量）→ 放行
        if (octets.size != 4 || octets.any { it.toIntOrNull() == null }) return true
        val b = octets[1].toIntOrNull()
        // 内网/回环/链路本地/CGNAT/未指定 IPv4 字面量拦截
        return !(a == 0 || a == 127 || a == 10 || (a == 192 && b == 168) ||
            (a == 172 && b != null && b in 16..31) || (a == 169 && b == 254) ||
            (a == 100 && b != null && b in 64..127))
    }

    private suspend fun renderOnMainThread(url: String): String? {
        var webView: WebView? = null
        try {
            val pageLoaded = CompletableDeferred<Boolean>()
            val wv = WebView(appContext)
            webView = wv
            configureSettings(wv.settings)
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // M-1：页内导航（JS location/meta refresh/链接）指向非公网 https → 拦截
                    if (request.isForMainFrame && !isFinalUrlAllowed(request.url.toString())) {
                        Log.w(LOG_TAG, "webview blocked non-public main frame navigation")
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    // 挑战页可能多次触发（JS 跳转）；complete 幂等，以首次为准。
                    // M-1：服务端 302 不触发 shouldOverrideUrlLoading —— 以终态 URL 复验兜底
                    //（非公网 https → 判定加载失败，放弃渲染，杜绝内网响应体外泄）
                    val allowed = isFinalUrlAllowed(view.url)
                    if (!allowed) Log.w(LOG_TAG, "webview final url not public https, abort render")
                    pageLoaded.complete(allowed)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    // 仅主框架错误判定加载失败（子资源 404 等不影响渲染）
                    if (request.isForMainFrame) pageLoaded.complete(false)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    // 渲染进程崩溃（进程隔离，不影响主进程）：标记失败；返回 true 表示
                    // 由本类处置（finally 中 destroy），阻止框架默认处理
                    pageLoaded.complete(false)
                    return true
                }
            }
            // 空 WebChromeClient：屏蔽 JS alert/confirm 等默认弹窗（offscreen 无对话框宿主）
            wv.webChromeClient = object : WebChromeClient() {}
            // 渲染需要 cookie（CF clearance 等站点状态）；内容不落日志（安全红线）
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.loadUrl(url)
            if (!pageLoaded.await()) return null
            val evalResult = CompletableDeferred<String?>()
            wv.evaluateJavascript(EXTRACT_DOM_JS) { json -> evalResult.complete(json) }
            return decodeEvalResult(evalResult.await())
        } finally {
            // 严格生命周期：取消/超时/失败/成功路径统一销毁，防 WebView 泄漏
            webView?.let { wv ->
                try { wv.stopLoading() } catch (_: Exception) { }
                try { wv.destroy() } catch (_: Exception) { }
            }
        }
    }

    private fun configureSettings(settings: WebSettings) {
        @SuppressLint("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true // 渲染抓取必需 JS（默认关闭状态下本渲染器不会生效）
        settings.domStorageEnabled = true
        settings.userAgentString = LocalMcpToolProvider.FETCH_USER_AGENT // 与直抓 UA 一致
        // SSRF/本地资源加固：禁 file/content scheme 访问，禁 JS 自动开窗
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
    }

    /**
     * 解码 [WebView.evaluateJavascript] 回调结果（JSON 编码字符串）为 HTML 文本。
     * "null" 字面量（WebView 已销毁/页面为空）与解码失败 → null。
     */
    internal fun decodeEvalResult(json: String?): String? {
        if (json.isNullOrEmpty() || json == "null") return null
        return try {
            val html = Json.decodeFromString(String.serializer(), json)
            if (html.isBlank()) null else html
        } catch (e: Exception) {
            Log.w(LOG_TAG, "eval result decode failed: ${e::class.simpleName}")
            null
        }
    }
}
