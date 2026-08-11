package io.prism.crossapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrossAppLauncher.resolveTemplates] 模板替换安全测试（M6 Phase B，BR-security-006）。
 *
 * 验证 URL 编码防护：
 * - 模板占位符 `{key}` 被正确替换
 * - 替换值经 URLEncoder.encode 编码，防止 URI 注入
 * - 特殊字符（& / = / # / ? / :）被编码，无法注入额外参数或切换 scheme
 */
class CrossAppLauncherTemplateTest {

    /** 创建测试用 CrossAppLauncher（不实际启动 Intent，仅测试 resolveTemplates）。 */
    private val launcher = CrossAppLauncher(
        SchemeRegistry.empty(),
        AppAvailabilityChecker { false },
        AppLauncherBridge()
    )

    @Test
    fun `resolveTemplates returns scheme unchanged when params empty`() {
        val scheme = "weixin://scanqrcode"
        val result = launcher.resolveTemplates(scheme, emptyMap())
        assertEquals(scheme, result)
    }

    @Test
    fun `resolveTemplates replaces single placeholder`() {
        val scheme = "taobao://item?id={itemId}"
        val result = launcher.resolveTemplates(scheme, mapOf("itemId" to "123456"))
        assertEquals("taobao://item?id=123456", result)
    }

    @Test
    fun `resolveTemplates replaces multiple placeholders`() {
        val scheme = "baidumap://map/direction?origin={origin}&destination={dest}&mode=driving"
        val result = launcher.resolveTemplates(
            scheme,
            mapOf("origin" to "北京", "dest" to "上海")
        )
        // 中文经 URLEncoder.encode 编码为 UTF-8 百分比编码
        assertTrue("origin should be encoded", result.contains("origin=%E5%8C%97%E4%BA%AC"))
        assertTrue("dest should be encoded", result.contains("destination=%E4%B8%8A%E6%B5%B7"))
    }

    // ==================== BR-security-006: URL 编码防护测试 ====================

    @Test
    fun `resolveTemplates encodes ampersand to prevent parameter injection`() {
        // 攻击场景：itemId 含 & 试图注入额外参数
        // 原始：taobao://item?id={itemId}
        // 攻击：itemId = "123&redirect=evil://"
        // 未编码：taobao://item?id=123&redirect=evil://（注入了 redirect 参数）
        // 编码后：taobao://item?id=123%26redirect%3Devil%3A%2F%2F（& 被编码，安全）
        val scheme = "taobao://item?id={itemId}"
        val result = launcher.resolveTemplates(scheme, mapOf("itemId" to "123&redirect=evil"))
        assertEquals("taobao://item?id=123%26redirect%3Devil", result)
    }

    @Test
    fun `resolveTemplates encodes hash to prevent fragment injection`() {
        val scheme = "taobao://item?id={itemId}"
        val result = launcher.resolveTemplates(scheme, mapOf("itemId" to "123#fragment"))
        assertEquals("taobao://item?id=123%23fragment", result)
    }

    @Test
    fun `resolveTemplates encodes question mark to prevent query injection`() {
        val scheme = "weixin://{path}"
        val result = launcher.resolveTemplates(scheme, mapOf("path" to "scan?extra=bad"))
        // ? 被编码为 %3F，无法启动新的 query string
        assertEquals("weixin://scan%3Fextra%3Dbad", result)
    }

    @Test
    fun `resolveTemplates encodes colon slash to prevent scheme injection`() {
        // 攻击场景：试图注入 evil:// scheme
        val scheme = "taobao://item?id={itemId}"
        val result = launcher.resolveTemplates(scheme, mapOf("itemId" to "evil://malicious"))
        assertEquals("taobao://item?id=evil%3A%2F%2Fmalicious", result)
    }

    @Test
    fun `resolveTemplates encodes equals sign`() {
        val scheme = "taobao://item?id={itemId}"
        val result = launcher.resolveTemplates(scheme, mapOf("itemId" to "key=value"))
        assertEquals("taobao://item?id=key%3Dvalue", result)
    }

    @Test
    fun `resolveTemplates leaves scheme without placeholders unchanged`() {
        val scheme = "weixin://scanqrcode"
        val result = launcher.resolveTemplates(scheme, mapOf("unused" to "value"))
        assertEquals(scheme, result)
    }

    @Test
    fun `resolveTemplates handles spaces in values`() {
        val scheme = "baidumap://map/direction?destination={dest}"
        val result = launcher.resolveTemplates(scheme, mapOf("dest" to "北京 天安门"))
        // 空格编码为 +
        assertTrue("space should be encoded", result.contains("+") || result.contains("%20"))
    }

    @Test
    fun `resolveTemplates handles empty string value`() {
        val scheme = "taobao://item?id={itemId}"
        val result = launcher.resolveTemplates(scheme, mapOf("itemId" to ""))
        assertEquals("taobao://item?id=", result)
    }

    @Test
    fun `resolveTemplates does not replace unknown placeholders`() {
        val scheme = "taobao://item?id={itemId}&user={userId}"
        val result = launcher.resolveTemplates(scheme, mapOf("itemId" to "123"))
        // userId 未提供，保持原样
        assertEquals("taobao://item?id=123&user={userId}", result)
    }
}
