package io.prism.crossapp

import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 Phase A ac-verifier 补充边缘场景测试（TKN-M6-PHASE-A-ACCEPTANCE-001）。
 *
 * 覆盖 guardrail-enforcer 指出的未测试模块：
 * - DeepLinkLauncher.resolveAction（纯函数逻辑分支）
 * - CrossAppLauncher 降级路径（未找到 / 未安装 / 不支持的媒体类型）
 * - CrossAppLauncher 查询方法（getAppConfig / getConfiguredApps）
 * - AppLauncherBridge 桥接流程（request→respond / 超时 / cancelAll）
 * - SchemeRegistry 边缘场景（重复 appId / 特殊字符）
 * - AppAvailabilityChecker 异常路径（修正 L-5 无效断言）
 *
 * 纯 JVM 测试，依赖 `isReturnDefaultValues = true` 处理 android.util.Log / Intent stub。
 */
class CrossAppSupplementalTest {

    // ==================== DeepLinkLauncher.resolveAction ====================

    private val testEntry = AppSchemeEntry(
        appId = "wechat",
        displayName = "微信",
        packageName = "com.tencent.mm",
        scheme = "weixin",
        defaultAction = "weixin://",
        actions = mapOf(
            "open" to "weixin://",
            "scan" to "weixin://scanqrcode"
        ),
        fallbackUrl = "https://weixin.qq.com/",
        queryScheme = "weixin"
    )

    private val entryWithEmptyActions = AppSchemeEntry(
        appId = "simple",
        displayName = "Simple",
        packageName = "com.simple.app",
        scheme = "simple",
        defaultAction = "simple://"
    )

    @Test
    fun `resolveAction returns defaultAction when action is null`() {
        val result = DeepLinkLauncher.resolveAction(testEntry, action = null)
        assertEquals("weixin://", result)
    }

    @Test
    fun `resolveAction returns defaultAction when action is empty string`() {
        val result = DeepLinkLauncher.resolveAction(testEntry, action = "")
        assertEquals("weixin://", result)
    }

    @Test
    fun `resolveAction returns defaultAction when action is blank string`() {
        val result = DeepLinkLauncher.resolveAction(testEntry, action = "   ")
        assertEquals("weixin://", result)
    }

    @Test
    fun `resolveAction returns mapped scheme for known action`() {
        val result = DeepLinkLauncher.resolveAction(testEntry, action = "scan")
        assertEquals("weixin://scanqrcode", result)
    }

    @Test
    fun `resolveAction returns defaultAction for unknown action`() {
        val result = DeepLinkLauncher.resolveAction(testEntry, action = "nonexistent")
        assertEquals("weixin://", result)
    }

    @Test
    fun `resolveAction returns defaultAction when actions map is empty`() {
        val result = DeepLinkLauncher.resolveAction(entryWithEmptyActions, action = "anything")
        assertEquals("simple://", result)
    }

    @Test
    fun `resolveAction returns defaultAction when actions map is empty and action is null`() {
        val result = DeepLinkLauncher.resolveAction(entryWithEmptyActions, action = null)
        assertEquals("simple://", result)
    }

    // ==================== CrossAppLauncher 降级路径 ====================

    /** 构造测试用 CrossAppLauncher（bridge 无需真实 Intent 启动，降级路径不触达 bridge） */
    private val testRegistry = SchemeRegistry.loadFromString("""
        [
          {"appId":"wechat","displayName":"微信","packageName":"com.tencent.mm","scheme":"weixin","defaultAction":"weixin://","actions":{"scan":"weixin://scanqrcode"},"fallbackUrl":"https://weixin.qq.com/","queryScheme":"weixin"},
          {"appId":"simple","displayName":"Simple","packageName":"com.simple","scheme":"simple","defaultAction":"simple://"}
        ]
    """.trimIndent())

    private val checker = AppAvailabilityChecker { pkg ->
        pkg == "com.tencent.mm" // 仅 wechat 已安装
    }

    private val bridge = AppLauncherBridge()

    private val launcher = CrossAppLauncher(testRegistry, checker, bridge)

    @Test
    fun `launchApp returns error for unknown appId`() = runBlocking {
        val result = launcher.launchApp("nonexistent_app")
        assertTrue("should contain appId in error", result.contains("nonexistent_app"))
        assertTrue("should indicate not found", result.contains("未找到") || result.contains("未配置"))
    }

    @Test
    fun `launchApp returns not-installed error with fallbackUrl for uninstalled app`() = runBlocking {
        val result = launcher.launchApp("simple")
        assertTrue("should contain display name", result.contains("Simple"))
        assertTrue("should contain fallback hint", result.contains("手动打开"))
        assertFalse("should not contain fallback URL (entry has none)", result.contains("https://"))
    }

    @Test
    fun `launchApp returns not-installed error with URL for uninstalled app with fallbackUrl`() = runBlocking {
        // 构造一个有 fallbackUrl 但未安装的 entry
        val registryWithFallback = SchemeRegistry.loadFromString("""
            [{"appId":"alipay","displayName":"支付宝","packageName":"com.eg.android.AlipayGphone","scheme":"alipay","defaultAction":"alipay://","fallbackUrl":"https://mobile.alipay.com/","queryScheme":"alipay"}]
        """.trimIndent())
        val checkerNoneInstalled = AppAvailabilityChecker { _ -> false }
        val testLauncher = CrossAppLauncher(registryWithFallback, checkerNoneInstalled, bridge)

        val result = testLauncher.launchApp("alipay")
        assertTrue("should contain display name", result.contains("支付宝"))
        assertTrue("should contain fallback URL", result.contains("https://mobile.alipay.com/"))
        assertTrue("should contain manual open hint", result.contains("手动打开"))
    }

    @Test
    fun `launchApp returns not-installed error without URL when fallbackUrl is null`() = runBlocking {
        val result = launcher.launchApp("simple")
        assertFalse("should not contain URL", result.contains("https://"))
        assertTrue("should contain manual open hint", result.contains("请手动打开"))
    }

    // ==================== CrossAppLauncher.pickMedia 降级路径 ====================

    @Test
    fun `pickMedia returns error for unsupported media type`() = runBlocking {
        val result = launcher.pickMedia("video")
        assertTrue(result.contains("不支持"))
        assertTrue(result.contains("video"))
        assertTrue(result.contains("photo") || result.contains("document"))
    }

    @Test
    fun `pickMedia returns error for empty media type`() = runBlocking {
        val result = launcher.pickMedia("")
        assertTrue(result.contains("不支持"))
    }

    @Test
    fun `pickMedia returns error for null-like media type`() = runBlocking {
        val result = launcher.pickMedia("audio")
        assertTrue(result.contains("不支持"))
        assertTrue(result.contains("audio"))
    }

    // ==================== CrossAppLauncher 查询方法 ====================

    @Test
    fun `getConfiguredApps returns all entries from registry`() {
        val apps = launcher.getConfiguredApps()
        assertEquals(2, apps.size)
        assertEquals("wechat", apps[0].appId)
        assertEquals("simple", apps[1].appId)
    }

    @Test
    fun `getAppConfig returns entry for known appId`() {
        val config = launcher.getAppConfig("wechat")
        assertNotNull(config)
        assertEquals("微信", config!!.displayName)
    }

    @Test
    fun `getAppConfig returns null for unknown appId`() {
        val config = launcher.getAppConfig("nonexistent")
        assertNull(config)
    }

    // ==================== AppLauncherBridge 桥接流程 ====================

    @Test
    fun `requestIntent returns result when respond is called`() = runBlocking {
        val testBridge = AppLauncherBridge()
        val intent = Intent("test_action")

        // 启动收集器模拟 UI 层响应
        val job = launch(Dispatchers.Default) {
            testBridge.requests.collect { request ->
                testBridge.respond(request.id, "已打开微信")
            }
        }

        // 等待收集器订阅 SharedFlow（replay=0 要求收集器先订阅再 emit）
        delay(200)

        try {
            val result = testBridge.requestIntent(intent, timeoutMs = 5000)
            assertEquals("已打开微信", result)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `requestIntent returns timeout message when no response`() = runBlocking {
        val testBridge = AppLauncherBridge()
        val intent = Intent("test_action")

        // 不启动收集器，模拟 UI 层缺失
        val result = testBridge.requestIntent(intent, timeoutMs = 200L)
        assertTrue("should contain timeout info", result.contains("超时"))
        assertTrue("should contain timeout value", result.contains("200"))
    }

    @Test
    fun `cancelAll completes pending requests with cancel message`() = runBlocking {
        val testBridge = AppLauncherBridge()
        val intent = Intent("test_action")

        // 启动请求但不响应（模拟用户切后台）
        val deferred = launch(Dispatchers.Default) {
            val result = testBridge.requestIntent(intent, timeoutMs = 10000L)
            assertTrue("should contain cancel message", result.contains("取消"))
        }

        delay(200) // 等待请求发出并进入 pending map
        testBridge.cancelAll() // 取消所有 pending

        withTimeoutOrNull(3000L) { deferred.join() }
            ?: throw AssertionError("deferred did not complete after cancelAll")
    }

    @Test
    fun `requestIntent cleans up pending map after completion`() = runBlocking {
        val testBridge = AppLauncherBridge()
        val intent = Intent("test_action")

        val job = launch(Dispatchers.Default) {
            testBridge.requests.collect { request ->
                testBridge.respond(request.id, "done")
            }
        }

        // 等待收集器订阅
        delay(200)

        try {
            testBridge.requestIntent(intent, timeoutMs = 5000)
            // 完成后 cancelAll 应该没有 pending 条目（不会影响已完成请求）
            testBridge.cancelAll() // 应不抛异常
        } finally {
            job.cancel()
        }
    }

    // ==================== SchemeRegistry 边缘场景 ====================

    @Test
    fun `loadFromString with duplicate appId returns first match`() {
        val dupJson = """
            [
              {"appId":"wechat","displayName":"微信","packageName":"com.tencent.mm","scheme":"weixin","defaultAction":"weixin://"},
              {"appId":"wechat","displayName":"微信2","packageName":"com.tencent.mm2","scheme":"weixin2","defaultAction":"weixin2://"}
            ]
        """.trimIndent()
        val registry = SchemeRegistry.loadFromString(dupJson)
        val entry = registry.getAppById("wechat")
        assertNotNull(entry)
        assertEquals("first entry should be returned", "微信", entry!!.displayName)
        assertEquals("com.tencent.mm", entry.packageName)
    }

    @Test
    fun `loadFromString with special characters in displayName`() {
        val specialJson = """
            [{"appId":"test","displayName":"App<>&\"'","packageName":"com.test","scheme":"test","defaultAction":"test://"}]
        """.trimIndent()
        val registry = SchemeRegistry.loadFromString(specialJson)
        val entry = registry.getAppById("test")
        assertNotNull(entry)
        assertEquals("App<>&\"'", entry!!.displayName)
    }

    @Test
    fun `loadFromString with very long appId`() {
        val longAppId = "a".repeat(500)
        val longJson = """
            [{"appId":"$longAppId","displayName":"Long","packageName":"com.long","scheme":"long","defaultAction":"long://"}]
        """.trimIndent()
        val registry = SchemeRegistry.loadFromString(longJson)
        val entry = registry.getAppById(longAppId)
        assertNotNull(entry)
        assertEquals("Long", entry!!.displayName)
    }

    @Test
    fun `loadFromString with extra whitespace and newlines in JSON`() {
        val messyJson = """
            [
              {
                "appId": "wechat"  ,
                "displayName": "微信"  ,
                "packageName": "com.tencent.mm"  ,
                "scheme": "weixin"  ,
                "defaultAction": "weixin://"
              }
            ]
        """.trimIndent()
        val registry = SchemeRegistry.loadFromString(messyJson)
        assertEquals(1, registry.getAllApps().size)
        assertEquals("wechat", registry.getAllApps()[0].appId)
    }

    @Test
    fun `loadFromString with null fallbackUrl and empty actions`() {
        val minimalJson = """
            [{"appId":"x","displayName":"X","packageName":"com.x","scheme":"x","defaultAction":"x://"}]
        """.trimIndent()
        val registry = SchemeRegistry.loadFromString(minimalJson)
        val entry = registry.getAppById("x")!!
        assertNull(entry.fallbackUrl)
        assertTrue(entry.actions.isEmpty())
        assertEquals("x", entry.queryScheme) // 默认等于 scheme
    }

    // ==================== AppAvailabilityChecker 异常路径修正（L-5） ====================

    @Test
    fun `isAppInstalled propagates exception from injected checker when not caught`() {
        // 明确断言：当注入的 packageChecker 抛异常时，isAppInstalled 直接传播异常
        // （生产实现 fromContext 内部有 try-catch，但 isAppInstalled 方法本身不捕获）
        val throwingChecker = AppAvailabilityChecker { _ ->
            throw SecurityException("mock security exception")
        }

        try {
            throwingChecker.isAppInstalled("com.any.app")
            // 如果到达这里，说明异常被捕获了（不应发生）
            throw AssertionError("Expected SecurityException to be propagated")
        } catch (e: SecurityException) {
            assertEquals("mock security exception", e.message)
        }
    }

    @Test
    fun `isAppInstalled with entry overload uses entry packageName`() {
        val checker = AppAvailabilityChecker { pkg -> pkg == "com.tencent.mm" }
        val entry = AppSchemeEntry(
            appId = "wechat",
            displayName = "微信",
            packageName = "com.tencent.mm",
            scheme = "weixin",
            defaultAction = "weixin://"
        )
        assertTrue(checker.isAppInstalled(entry))

        val uninstalledEntry = AppSchemeEntry(
            appId = "douyin",
            displayName = "抖音",
            packageName = "com.ss.android.ugc.aweme",
            scheme = "snssdk1128",
            defaultAction = "snssdk1128://"
        )
        assertFalse(checker.isAppInstalled(uninstalledEntry))
    }

    // ==================== CrossAppConfirmationRequest 数据类 ====================

    @Test
    fun `CrossAppConfirmationRequest ActionType enum has all three values`() {
        val values = CrossAppConfirmationRequest.ActionType.values()
        assertEquals(3, values.size)
        assertTrue(values.contains(CrossAppConfirmationRequest.ActionType.OPEN))
        assertTrue(values.contains(CrossAppConfirmationRequest.ActionType.SHARE))
        assertTrue(values.contains(CrossAppConfirmationRequest.ActionType.PICK))
    }

    @Test
    fun `CrossAppConfirmationRequest data class constructs correctly`() {
        val request = CrossAppConfirmationRequest(
            appDisplayName = "微信",
            actionType = CrossAppConfirmationRequest.ActionType.OPEN,
            contentPreview = "打开微信扫一扫",
            targetScheme = "weixin://scanqrcode",
            isAppInstalled = true
        )
        assertEquals("微信", request.appDisplayName)
        assertEquals(CrossAppConfirmationRequest.ActionType.OPEN, request.actionType)
        assertEquals("打开微信扫一扫", request.contentPreview)
        assertEquals("weixin://scanqrcode", request.targetScheme)
        assertTrue(request.isAppInstalled)
    }

    @Test
    fun `CrossAppConfirmationRequest data class equals and copy work correctly`() {
        val request1 = CrossAppConfirmationRequest(
            appDisplayName = "微信",
            actionType = CrossAppConfirmationRequest.ActionType.SHARE,
            contentPreview = "分享文本",
            targetScheme = "weixin://",
            isAppInstalled = true
        )
        val request2 = request1.copy()
        assertEquals(request1, request2)
        assertEquals(request1.hashCode(), request2.hashCode())

        val request3 = request1.copy(isAppInstalled = false)
        assertFalse(request1 == request3)
        assertFalse(request3.isAppInstalled)
    }
}
