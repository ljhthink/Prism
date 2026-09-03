package io.prism.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * US-1506（v1 批次15 B1）WebViewFetchRenderer 协议层测试（Robolectric）。
 *
 * **范围说明（如实标注）**：Robolectric 的 ShadowWebView 不会真实执行网络加载、
 * 也不会自动触发 onPageFinished / evaluateJavascript 回调，因此「加载真实页面 →
 * JS 渲染 → outerHTML 提取 → Readability 提纯」的端到端渲染链路无法在 JVM 单测中
 * 覆盖，列为**真机补测项**。本文件覆盖可稳定验证的协议层：
 * - 非 https URL → 不渲染直接返回 null（SSRF 防御纵深红线）
 * - evaluateJavascript 结果 JSON 解码（"null"/空/畸形 → null）
 * - 页面永不完成加载 → 总超时触发返回 null（生命周期 destroy 不挂死）
 *
 * 降级触发链（开关/状态码/空壳判定）见 [WebViewFetchFallbackTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WebViewFetchRendererTest {

    /**
     * UnconfinedTestDispatcher 作为 Main：避免依赖 Robolectric 主 Looper 泵（PAUSED 模式下
     * withContext(Dispatchers.Main) 会永久挂起）。withTimeout 计时器位于外层 runBlocking
     * 事件循环（真实时钟），不受该调度器虚拟时钟影响，超时路径可真实触发。
     */
    private val testMain = UnconfinedTestDispatcher()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(testMain)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ==================== SSRF 防御纵深：仅 https ====================

    @Test
    fun `render rejects non-https url without rendering`() = runBlocking {
        val renderer = WebViewFetchRenderer(context, timeoutMs = 1_000)
        // 非 https（http/file/javascript）一律拒绝：进入渲染前即返回 null
        assertNull("http URL 应被拒绝", renderer.render("http://example.com/"))
        assertNull("file scheme 应被拒绝", renderer.render("file:///etc/passwd"))
        assertNull("javascript scheme 应被拒绝", renderer.render("javascript:alert(1)"))
        assertTrue("https 校验函数与行为一致", renderer.isRenderableUrl("https://example.com/"))
    }

    // ==================== evaluateJavascript 结果解码 ====================

    @Test
    fun `decodeEvalResult decodes json html and rejects null or malformed`() {
        val renderer = WebViewFetchRenderer(context, timeoutMs = 1_000)
        val html = "<html><body><p>渲染正文</p></body></html>"
        val encoded = Json.encodeToString(String.serializer(), html)

        assertEquals("JSON 字符串应解码为原 HTML", html, renderer.decodeEvalResult(encoded))
        assertNull("null 字面量应返回 null", renderer.decodeEvalResult("null"))
        assertNull("null 输入应返回 null", renderer.decodeEvalResult(null))
        assertNull("空输入应返回 null", renderer.decodeEvalResult(""))
        assertNull("畸形 JSON 应返回 null", renderer.decodeEvalResult("not-json"))
        assertNull("解码后空白应返回 null", renderer.decodeEvalResult(Json.encodeToString(String.serializer(), "  ")))
    }

    // ==================== M-1（guardrail TKN-V1B15）：主框架导航/终态 URL 公网校验 ====================

    @Test
    fun `isFinalUrlAllowed blocks non-public https targets`() {
        val renderer = WebViewFetchRenderer(context, timeoutMs = 1_000)
        // 拦截：非 https scheme
        org.junit.Assert.assertFalse("http 应拒绝", renderer.isFinalUrlAllowed("http://example.com/"))
        org.junit.Assert.assertFalse("null 应拒绝", renderer.isFinalUrlAllowed(null))
        org.junit.Assert.assertFalse("空应拒绝", renderer.isFinalUrlAllowed(""))
        // 拦截：localhost / 回环（network_security_config localhost 明文放行域，攻击面核心）
        org.junit.Assert.assertFalse("127.0.0.1 应拒绝", renderer.isFinalUrlAllowed("https://127.0.0.1:11434/"))
        org.junit.Assert.assertFalse("localhost 应拒绝", renderer.isFinalUrlAllowed("https://localhost/api"))
        org.junit.Assert.assertFalse("子域 localhost 应拒绝", renderer.isFinalUrlAllowed("https://api.localhost/v1"))
        org.junit.Assert.assertFalse("IPv6 回环应拒绝", renderer.isFinalUrlAllowed("https://[::1]:8080/"))
        org.junit.Assert.assertFalse("userinfo 回环绕过应拒绝", renderer.isFinalUrlAllowed("https://user:pass@127.0.0.1/"))
        // 拦截：私网/链路本地/CGNAT IPv4 字面量
        org.junit.Assert.assertFalse("10.x 应拒绝", renderer.isFinalUrlAllowed("https://10.0.0.1/"))
        org.junit.Assert.assertFalse("192.168.x 应拒绝", renderer.isFinalUrlAllowed("https://192.168.1.1/"))
        org.junit.Assert.assertFalse("172.16.x 应拒绝", renderer.isFinalUrlAllowed("https://172.16.0.1/"))
        org.junit.Assert.assertFalse("172.31.x 应拒绝", renderer.isFinalUrlAllowed("https://172.31.255.1/"))
        org.junit.Assert.assertFalse("169.254 元数据应拒绝", renderer.isFinalUrlAllowed("https://169.254.169.254/latest/meta-data"))
        org.junit.Assert.assertFalse("100.64 CGNAT 应拒绝", renderer.isFinalUrlAllowed("https://100.64.0.1/"))
        // 放行：公网 https
        assertTrue("公网域名应放行", renderer.isFinalUrlAllowed("https://example.com/page?q=1"))
        assertTrue("公网 IPv4 应放行", renderer.isFinalUrlAllowed("https://8.8.8.8/"))
        assertTrue("172.32 非私网应放行", renderer.isFinalUrlAllowed("https://172.32.0.1/"))
        assertTrue("公网 IPv6 字面量应放行", renderer.isFinalUrlAllowed("https://[2606:4700::1111]/"))
        assertTrue("带端口公网应放行", renderer.isFinalUrlAllowed("https://example.com:8443/"))
        // 非法输入 fail-closed
        org.junit.Assert.assertFalse("未闭合 IPv6 括号应拒绝", renderer.isFinalUrlAllowed("https://[::1:8080/"))
    }

    // ==================== 超时生命周期 ====================

    @Test
    fun `render returns null on total timeout when page never finishes loading`() {
        // ShadowWebView 不会触发 onPageFinished → 唯一出路是总超时：
        // 验证超时路径返回 null 且 finally 中 destroy 不挂死
        val renderer = WebViewFetchRenderer(context, timeoutMs = 300)
        val start = System.currentTimeMillis()
        val result = runBlocking { renderer.render("https://example.com/never-finishes") }
        val elapsed = System.currentTimeMillis() - start

        assertNull("超时应返回 null（调用方回退原文案）", result)
        assertTrue("应在超时上限附近返回而非挂死（实际 ${elapsed}ms）", elapsed < 10_000)
    }
}
