package io.prism.crossapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrossAppLocalToolExecutor] 单元测试（M6 Phase B，US-038）。
 *
 * 通过 [FakeCrossAppLauncher] 注入解耦，纯 JVM 验证：
 * - [CrossAppLocalToolExecutor.handles] 命名空间匹配
 * - [CrossAppLocalToolExecutor.execute] 工具分发与参数提取
 * - 模板参数提取（排除已知字段 appId/action）
 * - 缺少必需参数时的降级文案
 * - [CrossAppLocalToolExecutor.buildToolDefinitions] 工具定义生成
 */
class CrossAppLocalToolExecutorTest {

    /** Fake CrossAppLauncher，记录调用参数并返回预设结果。 */
    private class FakeCrossAppLauncher :
        CrossAppLauncher(
            SchemeRegistry.empty(),
            AppAvailabilityChecker { false },
            AppLauncherBridge()
        ) {
        var launchAppCalls = mutableListOf<Triple<String, String?, Map<String, String>>>()
        var shareContentCalls = mutableListOf<String>()
        var pickMediaCalls = mutableListOf<Triple<String, String?, Boolean>>()

        var launchAppResult: String = "已打开微信"
        var shareContentResult: String = "已分享文本"
        var pickMediaResult: String = "已选取照片"

        override suspend fun launchApp(
            appId: String,
            action: String?,
            params: Map<String, String>
        ): String {
            launchAppCalls.add(Triple(appId, action, params))
            return launchAppResult
        }

        override suspend fun shareContent(text: String, chooserTitle: String): String {
            shareContentCalls.add(text)
            return shareContentResult
        }

        override suspend fun pickMedia(
            mediaType: String,
            mimeType: String?,
            allowMultiple: Boolean
        ): String {
            pickMediaCalls.add(Triple(mediaType, mimeType, allowMultiple))
            return pickMediaResult
        }
    }

    private val fakeLauncher = FakeCrossAppLauncher()
    private val executor = CrossAppLocalToolExecutor(fakeLauncher)

    // ==================== handles 测试 ====================

    @Test
    fun `handles returns true for cross_app__open_app`() {
        assertTrue(executor.handles(CrossAppLocalToolExecutor.TOOL_OPEN_APP))
    }

    @Test
    fun `handles returns true for cross_app__share_content`() {
        assertTrue(executor.handles(CrossAppLocalToolExecutor.TOOL_SHARE_CONTENT))
    }

    @Test
    fun `handles returns true for cross_app__pick_media`() {
        assertTrue(executor.handles(CrossAppLocalToolExecutor.TOOL_PICK_MEDIA))
    }

    @Test
    fun `handles returns false for non cross_app tools`() {
        assertFalse(executor.handles("filesystem__read_file"))
        assertFalse(executor.handles("mcp_server__search"))
        assertFalse(executor.handles("open_app"))
        assertFalse(executor.handles(""))
    }

    // ==================== execute open_app 测试 ====================

    @Test
    fun `execute open_app with appId only calls launchApp with default action`() = runBlocking {
        val args = mapOf<String, Any?>("appId" to "wechat")

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_OPEN_APP, args)

        assertEquals("已打开微信", result)
        assertEquals(1, fakeLauncher.launchAppCalls.size)
        val (appId, action, params) = fakeLauncher.launchAppCalls[0]
        assertEquals("wechat", appId)
        assertEquals(null, action)
        assertTrue("params should be empty", params.isEmpty())
    }

    @Test
    fun `execute open_app with action calls launchApp with action`() = runBlocking {
        val args = mapOf<String, Any?>(
            "appId" to "wechat",
            "action" to "scan"
        )

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_OPEN_APP, args)

        assertEquals("已打开微信", result)
        val (appId, action, params) = fakeLauncher.launchAppCalls[0]
        assertEquals("wechat", appId)
        assertEquals("scan", action)
        assertTrue("params should be empty", params.isEmpty())
    }

    @Test
    fun `execute open_app with template params extracts them correctly`() = runBlocking {
        val args = mapOf<String, Any?>(
            "appId" to "taobao",
            "action" to "item",
            "itemId" to "123456",
            "referrer" to "search"
        )

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_OPEN_APP, args)

        assertEquals("已打开微信", result) // fake returns fixed result
        val (appId, action, params) = fakeLauncher.launchAppCalls[0]
        assertEquals("taobao", appId)
        assertEquals("item", action)
        assertEquals(2, params.size)
        assertEquals("123456", params["itemId"])
        assertEquals("search", params["referrer"])
    }

    @Test
    fun `execute open_app without appId returns error message`() = runBlocking {
        val args = mapOf<String, Any?>("action" to "scan")

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_OPEN_APP, args)

        assertEquals("缺少必需参数 appId", result)
        assertEquals(0, fakeLauncher.launchAppCalls.size)
    }

    @Test
    fun `execute open_app with null appId returns error message`() = runBlocking {
        val args = mapOf<String, Any?>("appId" to null)

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_OPEN_APP, args)

        assertEquals("缺少必需参数 appId", result)
    }

    @Test
    fun `execute open_app with numeric appId converts to string`() = runBlocking {
        val args = mapOf<String, Any?>("appId" to 12345)

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_OPEN_APP, args)

        assertEquals("已打开微信", result)
        assertEquals("12345", fakeLauncher.launchAppCalls[0].first)
    }

    // ==================== execute share_content 测试 ====================

    @Test
    fun `execute share_content with content calls shareContent`() = runBlocking {
        val args = mapOf<String, Any?>("content" to "Hello World")

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_SHARE_CONTENT, args)

        assertEquals("已分享文本", result)
        assertEquals(1, fakeLauncher.shareContentCalls.size)
        assertEquals("Hello World", fakeLauncher.shareContentCalls[0])
    }

    @Test
    fun `execute share_content without content returns error message`() = runBlocking {
        val args = mapOf<String, Any?>("appId" to "wechat")

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_SHARE_CONTENT, args)

        assertEquals("缺少必需参数 content", result)
        assertEquals(0, fakeLauncher.shareContentCalls.size)
    }

    // ==================== execute pick_media 测试 ====================

    @Test
    fun `execute pick_media with photo type calls pickMedia`() = runBlocking {
        val args = mapOf<String, Any?>("mediaType" to "photo")

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_PICK_MEDIA, args)

        assertEquals("已选取照片", result)
        assertEquals(1, fakeLauncher.pickMediaCalls.size)
        val (mediaType, mimeType, allowMultiple) = fakeLauncher.pickMediaCalls[0]
        assertEquals("photo", mediaType)
        assertEquals(null, mimeType)
        assertEquals(false, allowMultiple)
    }

    @Test
    fun `execute pick_media with document type and all params`() = runBlocking {
        val args = mapOf<String, Any?>(
            "mediaType" to "document",
            "mimeType" to "application/pdf",
            "allowMultiple" to true
        )

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_PICK_MEDIA, args)

        assertEquals("已选取照片", result)
        val (mediaType, mimeType, allowMultiple) = fakeLauncher.pickMediaCalls[0]
        assertEquals("document", mediaType)
        assertEquals("application/pdf", mimeType)
        assertEquals(true, allowMultiple)
    }

    @Test
    fun `execute pick_media without mediaType returns error message`() = runBlocking {
        val args = mapOf<String, Any?>("mimeType" to "image/png")

        val result = executor.execute(CrossAppLocalToolExecutor.TOOL_PICK_MEDIA, args)

        assertEquals("缺少必需参数 mediaType", result)
        assertEquals(0, fakeLauncher.pickMediaCalls.size)
    }

    // ==================== execute 未知工具测试 ====================

    @Test
    fun `execute unknown tool returns error message`() = runBlocking {
        val result = executor.execute("cross_app__unknown_tool", emptyMap())

        assertEquals("未知跨 App 工具: cross_app__unknown_tool", result)
    }

    // ==================== 错误传播测试 ====================

    @Test
    fun `execute propagates launcher error messages`() = runBlocking {
        fakeLauncher.launchAppResult = "未安装抖音，请手动打开"

        val result = executor.execute(
            CrossAppLocalToolExecutor.TOOL_OPEN_APP,
            mapOf("appId" to "douyin")
        )

        assertEquals("未安装抖音，请手动打开", result)
    }

    @Test
    fun `execute propagates launcher timeout messages`() = runBlocking {
        fakeLauncher.launchAppResult = "跨 App 调用超时（30000ms），未收到结果"

        val result = executor.execute(
            CrossAppLocalToolExecutor.TOOL_OPEN_APP,
            mapOf("appId" to "wechat")
        )

        assertEquals("跨 App 调用超时（30000ms），未收到结果", result)
    }

    // ==================== buildToolDefinitions 测试 ====================

    @Test
    fun `buildToolDefinitions returns 3 tool definitions`() {
        val tools = CrossAppLocalToolExecutor.buildToolDefinitions(fakeLauncher)

        assertEquals(3, tools.size)
        assertEquals(CrossAppLocalToolExecutor.TOOL_OPEN_APP, tools[0].function.name)
        assertEquals(CrossAppLocalToolExecutor.TOOL_SHARE_CONTENT, tools[1].function.name)
        assertEquals(CrossAppLocalToolExecutor.TOOL_PICK_MEDIA, tools[2].function.name)
    }

    @Test
    fun `buildToolDefinitions open_app has appId in required`() {
        val tools = CrossAppLocalToolExecutor.buildToolDefinitions(fakeLauncher)
        val openAppParams = tools[0].function.parameters
        val required = openAppParams
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("required")

        assertNotNull(required)
        assertTrue(required.toString().contains("appId"))
    }

    @Test
    fun `buildToolDefinitions share_content has content in required`() {
        val tools = CrossAppLocalToolExecutor.buildToolDefinitions(fakeLauncher)
        val shareParams = tools[1].function.parameters
        val required = shareParams
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("required")

        assertNotNull(required)
        assertTrue(required.toString().contains("content"))
    }

    @Test
    fun `buildToolDefinitions pick_media has mediaType in required`() {
        val tools = CrossAppLocalToolExecutor.buildToolDefinitions(fakeLauncher)
        val pickParams = tools[2].function.parameters
        val required = pickParams
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("required")

        assertNotNull(required)
        assertTrue(required.toString().contains("mediaType"))
    }

    @Test
    fun `buildToolDefinitions open_app description contains app list marker`() {
        val tools = CrossAppLocalToolExecutor.buildToolDefinitions(fakeLauncher)
        val description = tools[0].function.description

        // FakeCrossAppLauncher uses SchemeRegistry.empty() so getConfiguredApps() returns empty
        // Description should contain "可用 App：" marker followed by empty list notice
        assertTrue("description should contain app list marker", description.contains("可用 App："))
        assertTrue("description should mention no apps available", description.contains("无可用 App 配置"))
    }

    @Test
    fun `buildToolDefinitions open_app enum contains all 7 appIds`() {
        val tools = CrossAppLocalToolExecutor.buildToolDefinitions(fakeLauncher)
        val params = tools[0].function.parameters
        val paramsObj = params as? kotlinx.serialization.json.JsonObject
        val properties = paramsObj?.get("properties") as? kotlinx.serialization.json.JsonObject
        val appIdProp = properties?.get("appId") as? kotlinx.serialization.json.JsonObject
        val enum = appIdProp?.get("enum") as? kotlinx.serialization.json.JsonArray

        assertNotNull(enum)
        assertEquals(7, enum?.size)
        val enumValues = enum?.map { it.toString().trim('"') }
        assertTrue("enum should contain wechat", enumValues?.contains("wechat") == true)
        assertTrue("enum should contain alipay", enumValues?.contains("alipay") == true)
        assertTrue("enum should contain baidu_map", enumValues?.contains("baidu_map") == true)
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }
}
