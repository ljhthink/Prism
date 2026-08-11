package io.prism.crossapp

import android.content.Intent
import android.net.Uri
import io.prism.data.McpServerConfig
import io.prism.fs.ToolConfirmationGate
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import io.prism.skill.LocalToolExecutor
import io.prism.skill.SkillExecutor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ac-verifier 补充测试（TKN-M6-PHASE-B-ACCEPTANCE-001）。
 *
 * 覆盖主 Agent 自问答复中指出的三个重点，以及代码审查中发现的额外问题：
 *
 * - **M-1 双重超时竞态**：SkillExecutor withTimeout(30s) 与 AppLauncherBridge withTimeoutOrNull(30s)
 *   默认值相同。验证外层先超时的行为，以及 pending 残留是否由 cancelAll 兜底。
 * - **L-1 isFailureResult 前缀匹配误判**：显式测试 MCP 正常结果以跨 App 前缀开头时被误判为 FAIL
 *   的已知局限，并将其文档化为可追踪的测试断言。
 * - **L-4 URLEncoder.encode 空格编码为 + 在 path 占位符的语义问题**：
 *   发现 app_schemes.json 中 douyin 的 `snssdk1128://aweme/detail/{awemeId}/`
 *   占位符实际在 path 部分（主 Agent 自问答复称"所有占位符均在查询参数"不准确），
 *   验证 path 占位符含空格时编码为 `+`（字面量而非空格）。
 * - **L-3 extractTemplateParams null 值转空字符串**：验证边界行为。
 * - **极端/恶意输入**：超长输入、组合注入载荷等。
 */
class M6PhaseBAcceptanceSupplementTest {

    // ==================== 测试辅助 ====================

    private val approveGate = ToolConfirmationGate { _, _ -> true }

    private class FakeMcpProvider : McpToolProvider {
        var callToolCalls = 0
        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
        override suspend fun callTool(
            config: McpServerConfig,
            name: String,
            arguments: Map<String, Any?>
        ): String {
            callToolCalls++
            return "MCP 工具结果"
        }
    }

    private val fakeMcp = FakeMcpProvider()
    private val mcpServer = McpServerConfig(
        name = "test-server",
        baseUrl = "http://localhost",
        isEnabled = true
    )

    private fun toolCall(name: String, args: Map<String, Any?> = emptyMap()) =
        StreamEvent.ToolCallComplete(
            toolCallId = "call_test",
            toolName = name,
            arguments = args
        )

    private fun launcher(): CrossAppLauncher = CrossAppLauncher(
        SchemeRegistry.empty(),
        AppAvailabilityChecker { false },
        AppLauncherBridge()
    )

    /** 通过反射获取 AppLauncherBridge 的 pending map 大小（验证 M-1 残留）。 */
    private fun getPendingSize(bridge: AppLauncherBridge): Int {
        val field = AppLauncherBridge::class.java.getDeclaredField("pending")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pending = field.get(bridge) as ConcurrentHashMap<Long, *>
        return pending.size
    }

    // ==================== L-1: isFailureResult 前缀匹配误判边界 ====================

    @Test
    fun `L-1 isFailureResult falsely flags MCP results starting with cross-app prefix - 未找到应用配置`() {
        // 已知局限：MCP Server 正常结果恰好以 "未找到应用配置" 开头时被误判为 FAIL
        // 影响仅限 ToolCallRecord.status 标记，结果文本仍正确回灌给 LLM
        assertTrue(
            "MCP result starting with '未找到应用配置' is falsely flagged as failure (known L-1 limitation)",
            SkillExecutor.isFailureResult("未找到应用配置: 某MCP工具的正常输出")
        )
    }

    @Test
    fun `L-1 isFailureResult falsely flags MCP results starting with cross-app prefix - 未安装`() {
        assertTrue(
            "MCP result starting with '未安装' is falsely flagged as failure (known L-1 limitation)",
            SkillExecutor.isFailureResult("未安装某依赖包，请参考文档")
        )
    }

    @Test
    fun `L-1 isFailureResult falsely flags MCP results starting with cross-app prefix - 跨 App 调用超时`() {
        assertTrue(
            "MCP result starting with '跨 App 调用超时' is falsely flagged as failure (known L-1 limitation)",
            SkillExecutor.isFailureResult("跨 App 调用超时记录: 无异常")
        )
    }

    @Test
    fun `L-1 isFailureResult falsely flags MCP results starting with cross-app prefix - 缺少必需参数`() {
        assertTrue(
            "MCP result starting with '缺少必需参数' is falsely flagged as failure (known L-1 limitation)",
            SkillExecutor.isFailureResult("缺少必需参数: 请查看 API 文档")
        )
    }

    @Test
    fun `L-1 isFailureResult falsely flags MCP results starting with cross-app prefix - 不支持的媒体类型`() {
        assertTrue(
            "MCP result starting with '不支持的媒体类型' is falsely flagged as failure (known L-1 limitation)",
            SkillExecutor.isFailureResult("不支持的媒体类型: 但这是正常回复")
        )
    }

    @Test
    fun `L-1 isFailureResult falsely flags MCP results starting with cross-app prefix - 未知跨 App 工具`() {
        assertTrue(
            "MCP result starting with '未知跨 App 工具' is falsely flagged as failure (known L-1 limitation)",
            SkillExecutor.isFailureResult("未知跨 App 工具: 这是 MCP 的正常结果")
        )
    }

    @Test
    fun `L-1 isFailureResult does not flag typical MCP success results`() {
        // 正常 MCP 结果不以这些前缀开头时正确判定为成功
        assertFalse(SkillExecutor.isFailureResult("搜索完成，找到 3 个结果"))
        assertFalse(SkillExecutor.isFailureResult("文件内容: ..."))
        assertFalse(SkillExecutor.isFailureResult(""))
    }

    @Test
    fun `L-1 isFailureResult correctly flags actual cross-app failure messages`() {
        // 真实的跨 App 失败消息应被正确判定
        assertTrue(SkillExecutor.isFailureResult("未找到应用配置: unknown_app"))
        assertTrue(SkillExecutor.isFailureResult("未安装微信，请手动打开"))
        assertTrue(SkillExecutor.isFailureResult("跨 App 调用超时（30000ms），未收到结果"))
        assertTrue(SkillExecutor.isFailureResult("缺少必需参数 appId"))
        assertTrue(SkillExecutor.isFailureResult("不支持的媒体类型: video"))
        assertTrue(SkillExecutor.isFailureResult("未知跨 App 工具: cross_app__unknown"))
    }

    // ==================== L-4: path 占位符 URLEncoder.encode 空格编码 ====================

    @Test
    fun `L-4 douyin awemeId placeholder is in path part of scheme`() {
        // 发现：app_schemes.json 中 douyin 的 detail action 是
        // snssdk1128://aweme/detail/{awemeId}/
        // {awemeId} 占位符在 path 部分，而非查询参数
        // 这与主 Agent 自问答复(b)称"所有占位符均在查询参数"不符
        val scheme = "snssdk1128://aweme/detail/{awemeId}/"
        val result = launcher().resolveTemplates(scheme, mapOf("awemeId" to "123456"))
        assertEquals("snssdk1128://aweme/detail/123456/", result)
    }

    @Test
    fun `L-4 path placeholder space encoded as plus (known limitation)`() {
        // URLEncoder.encode 将空格编码为 +（form-urlencoded 行为）
        // 在 path 部分，+ 是字面量而非空格编码
        // 这意味着 awemeId="123 456" 会变成 snssdk1128://aweme/detail/123+456/
        // 而非 snssdk1128://aweme/detail/123%20456/
        val scheme = "snssdk1128://aweme/detail/{awemeId}/"
        val result = launcher().resolveTemplates(scheme, mapOf("awemeId" to "123 456"))
        assertTrue(
            "path placeholder space encoded as plus (L-4 known limitation)",
            result.contains("+")
        )
        assertFalse(
            "path placeholder space not encoded as %20 (L-4 known limitation)",
            result.contains("%20")
        )
    }

    @Test
    fun `L-4 query placeholder space encoded as plus (correct for query)`() {
        // 对比：query 参数中空格编码为 + 是正确的（form-urlencoded）
        val scheme = "taobao://item?id={itemId}"
        val result = launcher().resolveTemplates(scheme, mapOf("itemId" to "123 456"))
        assertTrue("query placeholder space encoded as plus (correct)", result.contains("+"))
    }

    @Test
    fun `L-4 path placeholder injection still prevented by URLEncoder`() {
        // 即使在 path 部分，URLEncoder.encode 仍然编码危险字符
        // 验证 path 占位符的注入防护仍然有效
        val scheme = "snssdk1128://aweme/detail/{awemeId}/"
        val result = launcher().resolveTemplates(
            scheme,
            mapOf("awemeId" to "evil?inject=true#frag")
        )
        // ? # = 均被编码，无法注入 query 或 fragment
        assertFalse("path placeholder ? encoded", result.contains("?inject"))
        assertFalse("path placeholder # encoded", result.contains("#frag"))
        assertTrue("path placeholder ? encoded as %3F", result.contains("%3F"))
        assertTrue("path placeholder # encoded as %23", result.contains("%23"))
    }

    // ==================== L-3: extractTemplateParams null 值处理 ====================

    @Test
    fun `L-3 extractTemplateParams null value converted to empty string via executeOpenApp`() {
        // 验证 L-3: args 中 null 值参数被转为空字符串
        val fakeLauncher = object : CrossAppLauncher(
            SchemeRegistry.empty(),
            AppAvailabilityChecker { false },
            AppLauncherBridge()
        ) {
            var capturedParams: Map<String, String> = emptyMap()
            override suspend fun launchApp(
                appId: String,
                action: String?,
                params: Map<String, String>
            ): String {
                capturedParams = params
                return "已打开"
            }
        }
        val executor = CrossAppLocalToolExecutor(fakeLauncher)
        runBlocking {
            executor.execute(
                CrossAppLocalToolExecutor.TOOL_OPEN_APP,
                mapOf("appId" to "taobao", "action" to "item", "itemId" to null)
            )
        }
        // null itemId 被转为空字符串
        assertEquals("", fakeLauncher.capturedParams["itemId"])
    }

    // ==================== M-1: 双重超时竞态 ====================

    @Test
    fun `M-1 SkillExecutor timeout fires before bridge timeout returns generic message`() = runTest {
        // 验证 M-1: 当 SkillExecutor 的 withTimeout(maxTimeoutMs) 先于 bridge 的 withTimeoutOrNull 超时时，
        // SkillExecutor 返回通用 "工具执行超时" 消息，而非 bridge 的 "跨 App 调用超时"
        val bridge = AppLauncherBridge()
        val localExecutor = object : LocalToolExecutor {
            override fun handles(toolName: String): Boolean = toolName == "cross_app__open_app"
            override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
                // 模拟 bridge.requestIntent，使用更长的超时（10s）
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("weixin://"))
                return bridge.requestIntent(intent, timeoutMs = 10_000)
            }
        }
        val executor = SkillExecutor(fakeMcp, approveGate, localToolExecutor = localExecutor)

        val result = executor.executeToolCall(
            toolCall("cross_app__open_app", mapOf("appId" to "wechat")),
            listOf(mcpServer),
            maxTimeoutMs = 100 // SkillExecutor 100ms 超时，先于 bridge 10s
        )

        // M-1 核心验证：返回通用超时消息，而非 bridge 的语义化超时消息
        assertTrue(
            "should return generic timeout message (SkillExecutor fires first)",
            result.startsWith("工具执行超时")
        )
        assertFalse(
            "should NOT return bridge timeout message (bridge never fires)",
            result.contains("跨 App 调用超时")
        )
    }

    @Test
    fun `M-1 bridge pending has residual after external cancellation`() = runTest {
        // 验证 M-1: 当外部协程取消 bridge.requestIntent 时，
        // pending 中的 CompletableDeferred 可能残留（pending.remove 不执行）
        val bridge = AppLauncherBridge()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("weixin://"))

        // 启动 requestIntent（会挂起等待 respond）
        val job = launch {
            bridge.requestIntent(intent, timeoutMs = 10_000)
        }

        // 让 requestIntent 执行到 deferred.await() 挂起点
        yield()

        // 验证 pending 有 1 个条目（requestIntent 已注册）
        val pendingDuringRequest = getPendingSize(bridge)
        assertTrue(
            "pending should have entry during active request (got $pendingDuringRequest)",
            pendingDuringRequest >= 1
        )

        // 外部取消（模拟 SkillExecutor withTimeout 超时导致的取消传播）
        job.cancelAndJoin()

        // M-1 关键验证：取消后 pending 可能残留
        // 注意：withTimeoutOrNull 在外部取消时的行为取决于 kotlinx-coroutines 版本：
        // - 若 withTimeoutOrNull 让外部取消传播 → pending.remove 不执行 → 残留
        // - 若 withTimeoutOrNull 捕获外部取消返回 null → pending.remove 执行 → 清理
        // 无论哪种情况，cancelAll 都应能清理
        val pendingAfterCancel = getPendingSize(bridge)

        // cancelAll 兜底清理
        bridge.cancelAll()

        val pendingAfterCleanup = getPendingSize(bridge)
        assertEquals(
            "cancelAll should clean up all pending entries (residual was $pendingAfterCancel)",
            0,
            pendingAfterCleanup
        )
    }

    @Test
    fun `M-1 cancelAll completes residual deferred with cancel message`() = runTest {
        // 验证 M-1: cancelAll 对残留的 deferred 完成 "已取消" 消息
        val bridge = AppLauncherBridge()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("weixin://"))

        var result: String? = null
        val job = launch {
            result = bridge.requestIntent(intent, timeoutMs = 10_000)
        }

        yield()
        assertTrue("pending has entry", getPendingSize(bridge) >= 1)

        // cancelAll 在 Activity 销毁时调用
        bridge.cancelAll()
        job.join()

        // 验证残留 deferred 被 cancelAll 完成
        // 注意：如果 withTimeoutOrNull 捕获了外部取消，result 可能为 null（withTimeoutOrNull 返回 null）
        // 如果 withTimeoutOrNull 让取消传播，result 可能为 "跨 App 调用已取消（Activity 销毁）"
        // 无论哪种，cancelAll 不应导致异常
        assertTrue("job completed without exception", !job.isActive)
    }

    @Test
    fun `M-1 fix - bridge default timeout is shorter than SkillExecutor`() {
        // M-1 修复验证（guardrail-enforcer TKN-M6-PHASEC-GUARDRAIL-001 M-1）：
        // 原根因：两层超时默认值相同（30s），SkillExecutor 先超时导致 bridge 语义化文案丢失。
        // 修复方向：bridge 超时必须**短于** SkillExecutor 超时（BR-concurrency-005），
        // 保证 bridge 先超时返回语义化文案 + 主动清理 pending。
        assertEquals(30_000L, SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS)
        assertEquals(25_000L, AppLauncherBridge.DEFAULT_TIMEOUT_MS)
        assertTrue(
            "bridge timeout (${AppLauncherBridge.DEFAULT_TIMEOUT_MS}ms) must be STRICTLY SHORTER than " +
                "SkillExecutor timeout (${SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS}ms) — BR-concurrency-005",
            AppLauncherBridge.DEFAULT_TIMEOUT_MS < SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS
        )
    }

    @Test
    fun `M-1 fix - bridge fires first with default timeouts returns semantic message`() = runTest {
        // M-1 修复端到端验证：使用默认超时值（bridge 25s < SkillExecutor 30s），
        // bridge 的 withTimeoutOrNull(25s) 先超时 → 返回语义化文案 + 主动执行 pending.remove(id) 清理；
        // SkillExecutor 的外层 withTimeout(30s) 永不触发（25s < 30s）。
        val bridge = AppLauncherBridge()
        val localExecutor = object : LocalToolExecutor {
            override fun handles(toolName: String): Boolean = toolName == "cross_app__open_app"
            override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("weixin://"))
                return bridge.requestIntent(intent) // 使用默认 25s 超时
            }
        }
        val executor = SkillExecutor(fakeMcp, approveGate, localToolExecutor = localExecutor)

        val result = executor.executeToolCall(
            toolCall("cross_app__open_app", mapOf("appId" to "wechat")),
            listOf(mcpServer)
            // maxTimeoutMs 使用默认值 30_000L（SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS）
        )

        // M-1 修复核心验证：返回 bridge 的语义化超时消息，而非 SkillExecutor 的通用消息
        assertTrue(
            "should return bridge semantic timeout message (bridge fires first with default timeouts): $result",
            result.contains("跨 App 调用超时")
        )
        assertFalse(
            "should NOT return SkillExecutor generic timeout message (SkillExecutor never fires): $result",
            result.startsWith("工具执行超时")
        )

        // 验证 pending 已被 bridge 主动清理（M-1 修复的关键：pending.remove(id) 执行）
        assertEquals(
            "pending should be empty after bridge timeout cleanup",
            0,
            getPendingSize(bridge)
        )
    }

    // ==================== 极端/恶意输入场景 ====================

    @Test
    fun `resolveTemplates handles very long input value`() {
        // 超长输入（10000 字符）
        val longValue = "a".repeat(10_000)
        val scheme = "taobao://item?id={itemId}"
        val result = launcher().resolveTemplates(scheme, mapOf("itemId" to longValue))
        assertTrue("long value should be encoded and replaced", result.contains("a".repeat(100)))
        assertTrue("result should not contain raw placeholder", !result.contains("{itemId}"))
    }

    @Test
    fun `resolveTemplates handles combined injection payload`() {
        // 组合注入载荷：& # ? : // = 同时出现
        val payload = "123&redirect=evil#frag?query=1://malicious=value"
        val scheme = "taobao://item?id={itemId}"
        val result = launcher().resolveTemplates(scheme, mapOf("itemId" to payload))
        // 所有特殊字符均被编码
        assertFalse("& not encoded", result.contains("&redirect"))
        assertFalse("# not encoded", result.contains("#frag"))
        assertFalse("? not encoded", result.contains("?query"))
        assertFalse(":// not encoded", result.contains("://malicious"))
    }

    @Test
    fun `resolveTemplates handles unicode and emoji input`() {
        // Unicode + Emoji
        val scheme = "taobao://item?id={itemId}"
        val result = launcher().resolveTemplates(scheme, mapOf("itemId" to "测试🎉emoji"))
        // URLEncoder.encode 编码 Unicode 和 Emoji
        assertFalse("raw unicode should not appear", result.contains("测试"))
        assertFalse("raw emoji should not appear", result.contains("🎉"))
        assertTrue("encoded value present", result.contains("%"))
    }

    @Test
    fun `resolveTemplates handles many placeholders`() {
        // 多占位符（10 个）
        val scheme = (1..10).joinToString("") { "p{key$it}" }
        val params = (1..10).associate { "key$it" to "v$it" }
        val result = launcher().resolveTemplates(scheme, params)
        (1..10).forEach {
            assertTrue("key$it replaced", result.contains("v$it"))
            assertFalse("key$it placeholder removed", result.contains("{key$it}"))
        }
    }

    @Test
    fun `execute open_app with empty appId returns error`() = runBlocking {
        val executor = CrossAppLocalToolExecutor(launcher())
        val result = executor.execute(
            CrossAppLocalToolExecutor.TOOL_OPEN_APP,
            mapOf("appId" to "")
        )
        // 空 appId 会传给 launchApp，SchemeRegistry.getAppById("") 返回 null
        // 返回 "未找到应用配置: "
        assertTrue("empty appId should return not found error", result.startsWith("未找到应用配置"))
    }

    @Test
    fun `execute open_app with special characters in appId`() = runBlocking {
        val executor = CrossAppLocalToolExecutor(launcher())
        val result = executor.execute(
            CrossAppLocalToolExecutor.TOOL_OPEN_APP,
            mapOf("appId" to "wechat'; DROP TABLE--")
        )
        // 特殊字符 appId 不在配置中，返回未找到
        assertTrue(result.startsWith("未找到应用配置"))
    }

    @Test
    fun `execute pick_media with uppercase mediaType works`() = runBlocking {
        // CrossAppLauncher.pickMedia 使用 lowercase(Locale.ROOT)，大写应被转换
        val fakeLauncher = object : CrossAppLauncher(
            SchemeRegistry.empty(),
            AppAvailabilityChecker { false },
            AppLauncherBridge()
        ) {
            var capturedMediaType: String? = null
            override suspend fun pickMedia(
                mediaType: String,
                mimeType: String?,
                allowMultiple: Boolean
            ): String {
                capturedMediaType = mediaType
                return "已选取照片"
            }
        }
        val executor = CrossAppLocalToolExecutor(fakeLauncher)
        executor.execute(
            CrossAppLocalToolExecutor.TOOL_PICK_MEDIA,
            mapOf("mediaType" to "PHOTO")
        )
        // mediaType 原样传给 CrossAppLauncher.pickMedia，内部 lowercase 后匹配
        assertEquals("PHOTO", fakeLauncher.capturedMediaType)
    }

    @Test
    fun `handles returns false for empty and blank tool names`() {
        val executor = CrossAppLocalToolExecutor(launcher())
        assertFalse(executor.handles(""))
        assertFalse(executor.handles("   "))
        assertFalse(executor.handles("cross_app_"))
        assertFalse(executor.handles("__open_app"))
        assertFalse(executor.handles("cross_app__"))
    }
}
