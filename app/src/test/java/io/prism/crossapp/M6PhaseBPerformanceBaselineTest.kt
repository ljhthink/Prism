package io.prism.crossapp

import io.prism.data.McpServerConfig
import io.prism.fs.ToolConfirmationGate
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import io.prism.skill.LocalToolExecutor
import io.prism.skill.SkillExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 Phase B 性能基线测试（TKN-M6-PHASE-B-ACCEPTANCE-001）。
 *
 * Phase B 为纯逻辑层（无 IO/网络/数据库），对 5 个关键函数执行计时测试，
 * 生成初版性能基线（无既有 M6 基线，按 CLAUDE.md 第十一节 4 要求生成）。
 *
 * 迭代次数 1000，统计 p50/p95/p99/avg（纳秒）+ 吞吐（ops/sec）。
 */
class M6PhaseBPerformanceBaselineTest {

    private companion object {
        const val ITERATIONS = 1000
    }

    private data class Stats(
        val p50: Long, val p95: Long, val p99: Long, val avg: Long,
        val throughput: Long // ops/sec
    ) {
        override fun toString(): String =
            "p50=${p50}ns p95=${p95}ns p99=${p99}ns avg=${avg}ns throughput=${throughput}ops/s"
    }

    private fun calcStats(times: LongArray): Stats {
        val sorted = times.sortedArray()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[(sorted.size * 95 / 100)]
        val p99 = sorted[(sorted.size * 99 / 100).coerceAtMost(sorted.size - 1)]
        val avg = sorted.average().toLong()
        val totalNs = sorted.sum()
        val throughput = if (totalNs > 0) 1_000_000_000L / (totalNs / sorted.size) else 0
        return Stats(p50, p95, p99, avg, throughput)
    }

    // ==================== 测试辅助 ====================

    private val approveGate = ToolConfirmationGate { _, _ -> true }

    private class FakeMcpProvider : McpToolProvider {
        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()
        override suspend fun callTool(
            config: McpServerConfig, name: String, arguments: Map<String, Any?>
        ): String = "MCP result"
    }

    private val mcpServer = McpServerConfig(
        name = "test", baseUrl = "http://localhost", isEnabled = true
    )

    private fun toolCall(name: String, args: Map<String, Any?> = emptyMap()) =
        StreamEvent.ToolCallComplete(toolCallId = "call_1", toolName = name, arguments = args)

    private fun makeLauncher(): CrossAppLauncher = CrossAppLauncher(
        SchemeRegistry.empty(),
        AppAvailabilityChecker { false },
        AppLauncherBridge()
    )

    private class FastFakeLauncher : CrossAppLauncher(
        SchemeRegistry.empty(), AppAvailabilityChecker { false }, AppLauncherBridge()
    ) {
        override suspend fun launchApp(appId: String, action: String?, params: Map<String, String>) = "已打开"
        override suspend fun shareContent(text: String, chooserTitle: String) = "已分享"
        override suspend fun pickMedia(mediaType: String, mimeType: String?, allowMultiple: Boolean) = "已选取"
    }

    // ==================== 性能基线测试 ====================

    @Test
    fun `baseline - handles() O(1) set lookup`() {
        val executor = CrossAppLocalToolExecutor(makeLauncher())
        // 预热
        repeat(100) { executor.handles("cross_app__open_app") }

        val times = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val start = System.nanoTime()
            executor.handles("cross_app__open_app")
            times[i] = System.nanoTime() - start
        }
        val stats = calcStats(times)
        println("[M6-Perf] handles(): $stats")
        assertTrue("handles p99 < 500ns", stats.p99 < 500)
    }

    @Test
    fun `baseline - resolveTemplates single placeholder + URLEncoder`() {
        val launcher = makeLauncher()
        val scheme = "taobao://item?id={itemId}"
        val params = mapOf("itemId" to "123456")
        // 预热
        repeat(100) { launcher.resolveTemplates(scheme, params) }

        val times = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val start = System.nanoTime()
            launcher.resolveTemplates(scheme, params)
            times[i] = System.nanoTime() - start
        }
        val stats = calcStats(times)
        println("[M6-Perf] resolveTemplates(1 param): $stats")
        assertTrue("resolveTemplates p99 < 50μs (baseline gate)", stats.p99 < 50_000)
    }

    @Test
    fun `baseline - resolveTemplates multi placeholder + URLEncoder`() {
        val launcher = makeLauncher()
        val scheme = "baidumap://map/direction?origin={origin}&destination={dest}&mode=driving"
        val params = mapOf("origin" to "北京天安门", "dest" to "上海浦东")
        repeat(100) { launcher.resolveTemplates(scheme, params) }

        val times = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val start = System.nanoTime()
            launcher.resolveTemplates(scheme, params)
            times[i] = System.nanoTime() - start
        }
        val stats = calcStats(times)
        println("[M6-Perf] resolveTemplates(2 params + Chinese): $stats")
        assertTrue("resolveTemplates multi p99 < 200μs (baseline gate)", stats.p99 < 200_000)
    }

    @Test
    fun `baseline - isFailureResult prefix matching`() {
        val results = listOf(
            "已打开微信", "已分享文本", "已选取照片",
            "未找到应用配置: xxx", "未安装抖音", "跨 App 调用超时（30000ms）",
            "缺少必需参数 appId", "用户拒绝执行工具: x", "工具执行超时（30000ms）: y"
        )
        repeat(100) { results.forEach { SkillExecutor.isFailureResult(it) } }

        val times = LongArray(ITERATIONS)
        repeat(ITERATIONS) { i ->
            val result = results[i % results.size]
            val start = System.nanoTime()
            SkillExecutor.isFailureResult(result)
            times[i] = System.nanoTime() - start
        }
        val stats = calcStats(times)
        println("[M6-Perf] isFailureResult(): $stats")
        assertTrue("isFailureResult p99 < 5μs (baseline gate)", stats.p99 < 5_000)
    }

    @Test
    fun `baseline - executeToolCall local tool branch (full path with withTimeout)`() {
        val fakeLauncher = FastFakeLauncher()
        val localExecutor = CrossAppLocalToolExecutor(fakeLauncher)
        val executor = SkillExecutor(FakeMcpProvider(), approveGate, localToolExecutor = localExecutor)
        val args = mapOf<String, Any?>("appId" to "wechat")
        val servers = listOf(mcpServer)

        // 预热（含 runBlocking 协程开销）
        runBlocking { repeat(10) { executor.executeToolCall(toolCall("cross_app__open_app", args), servers) } }

        val times = LongArray(ITERATIONS)
        runBlocking {
            repeat(ITERATIONS) { i ->
                val start = System.nanoTime()
                executor.executeToolCall(toolCall("cross_app__open_app", args), servers)
                times[i] = System.nanoTime() - start
            }
        }
        val stats = calcStats(times)
        println("[M6-Perf] executeToolCall(local branch): $stats")
        // 含 withContext + withTimeout + when 分发 + 参数提取 + FakeLauncher 调用
        assertTrue("executeToolCall local p99 < 2ms (baseline gate)", stats.p99 < 2_000_000)
    }

    @Test
    fun `baseline - execute open_app dispatch + param extraction (no coroutines)`() {
        val fakeLauncher = FastFakeLauncher()
        val executor = CrossAppLocalToolExecutor(fakeLauncher)
        val args = mapOf<String, Any?>("appId" to "taobao", "action" to "item", "itemId" to "123456")
        runBlocking { repeat(100) { executor.execute("cross_app__open_app", args) } }

        val times = LongArray(ITERATIONS)
        runBlocking {
            repeat(ITERATIONS) { i ->
                val start = System.nanoTime()
                executor.execute("cross_app__open_app", args)
                times[i] = System.nanoTime() - start
            }
        }
        val stats = calcStats(times)
        println("[M6-Perf] execute(open_app, 3 args): $stats")
        assertTrue("execute open_app p99 < 50μs", stats.p99 < 50_000)
    }
}
