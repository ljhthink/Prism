package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **装配回归**（2026-11-15；guardrail R-01/R-02 阻断项的防线）。
 *
 * ## 为什么需要"源码级"测试
 *
 * 上一轮我把 `primeDownload` 和 `prefetchMediaInBackground` 写好了，
 * 但两处**装配**的文本替换静默失败：
 * - `QqMediaLocalBridge(this)` 没传 `primeDownload` → 生产从未调用过 `get_image`；
 * - `prefetchMediaInBackground()` 定义了却零调用点 → 预取是死代码。
 *
 * 单测全绿，功能却完全没接通（真机"一片空白"）。单元测试**测不出"没接线"**，
 * 所以这里直接对生产源码做装配断言——廉价，且能立刻抓住同类事故。
 */
class QqMediaWiringTest {

    private fun appSource(): String {
        // 测试工作目录是 app/，源码在 src/main/java/...
        val candidates = listOf(
            File("src/main/java/io/prism/PrismApplication.kt"),
            File("app/src/main/java/io/prism/PrismApplication.kt")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 PrismApplication.kt（工作目录=${File("").absolutePath}）")
        return file.readText()
    }

    private fun bridgeSource(): String {
        val candidates = listOf(
            File("src/main/java/io/prism/qq/QqMediaLocalBridge.kt"),
            File("app/src/main/java/io/prism/qq/QqMediaLocalBridge.kt")
        )
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `生产装配必须注入 primeDownload`() {
        val src = appSource()
        assertTrue(
            "QqMediaLocalBridge 构造必须传 primeDownload —— 否则 get_image 永不调用，" +
                "图片永远等不到落盘（这正是第三次『一片空白』的根因之一）",
            src.contains("primeDownload =")
        )
        assertTrue(
            "prime 必须真的调用 OneBot get_image",
            src.contains("\"get_image\"")
        )
    }

    @Test
    fun `必须存在预取调用点`() {
        val src = appSource()
        // 定义 + 至少一个调用点（定义行含 "fun prefetchMediaInBackground"）
        val occurrences = Regex("prefetchMediaInBackground").findAll(src).count()
        assertTrue(
            "prefetchMediaInBackground 必须既有定义又有调用点（实际出现 $occurrences 次，" +
                "只有 1 次说明是死代码）",
            occurrences >= 2
        )
    }

    @Test
    fun `桥接层必须串行化以防串图`() {
        val src = bridgeSource()
        assertTrue(
            "fetchBytes 必须持锁串行 —— 脚本产物是全局固定路径，" +
                "并发会读到别人的图（内容错配，比取不到更严重）",
            src.contains("fetchLock.withLock")
        )
    }

    @Test
    fun `取图预算不得超过 replyMutex 可承受范围`() {
        val src = bridgeSource()
        val m = Regex("""TIMEOUT_MS:\s*Long\s*=\s*([0-9_]+)L""").find(src)
            ?: error("找不到 TIMEOUT_MS")
        val ms = m.groupValues[1].replace("_", "").toLong()
        assertTrue(
            "TIMEOUT_MS=$ms 过长：fetch 发生在 autoReplyLocked 内（持 replyMutex），" +
                "过久会堵住后续所有回复",
            ms <= 20_000L
        )
    }
}
