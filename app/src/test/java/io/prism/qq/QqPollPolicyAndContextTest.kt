package io.prism.qq

import io.prism.qq.channel.QqMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QqPollPolicy] 与屏幕态驱动的轮询决策（2026-10-26）。 */
class QqPollPolicyTest {

    @Test
    fun `亮屏时无论探针结果都放行`() {
        listOf(null, true, false).forEach { probe ->
            val d = QqPollPolicy.decide(screenInteractive = true, probe = probe)
            assertTrue("probe=$probe", d.poll)
            assertEquals(QqPollPolicy.SCREEN_ON_POLL_MS, d.nextDelayMs)
        }
    }

    @Test
    fun `熄屏探针命中才放行`() {
        assertTrue(QqPollPolicy.decide(false, true).poll)
        assertTrue(!QqPollPolicy.decide(false, false).poll)
    }

    @Test
    fun `熄屏探针未知时不放行（交给兜底）`() {
        val d = QqPollPolicy.decide(false, null)
        assertTrue(!d.poll)
        assertEquals(QqPollPolicy.SCREEN_OFF_POLL_MS, d.nextDelayMs)
        assertTrue(d.reason.contains("未知"))
    }
}

/** [QqContextBuilder] 字符预算裁剪（2026-10-26；US-3112 修订）。 */
class QqContextBuilderTest {

    private fun message(text: String, incoming: Boolean = true) =
        QqMessage(messageId = null, incoming = incoming, text = text, timestampMs = 0L)

    @Test
    fun `预算充足时原样保留`() {
        val list = listOf(message("a"), message("b"), message("c"))
        assertEquals(list, QqContextBuilder.trimByBudget(list, 10_000))
    }

    @Test
    fun `预算不足时从最旧截断且保留最新一条`() {
        val list = (1..10).map { message("第 $it 条消息内容") }
        val trimmed = QqContextBuilder.trimByBudget(list, 40)
        assertTrue(trimmed.isNotEmpty())
        assertEquals(list.last(), trimmed.last())
        val cost = trimmed.sumOf { QqContextBuilder.estimateCost(it.text) }
        assertTrue("cost=$cost", cost <= 40 || trimmed.size == 1)
    }

    @Test
    fun `单条超预算仍保留该条`() {
        val huge = message("x".repeat(500))
        val trimmed = QqContextBuilder.trimByBudget(listOf(huge), 100)
        assertEquals(listOf(huge), trimmed)
    }

    @Test
    fun `空历史返回空`() {
        assertTrue(QqContextBuilder.trimByBudget(emptyList(), 1000).isEmpty())
    }

    @Test
    fun `预算 0 表示不限制`() {
        val list = (1..20).map { message("m$it") }
        assertEquals(list, QqContextBuilder.trimByBudget(list, 0))
    }

    @Test
    fun `render 区分方向`() {
        val text = QqContextBuilder.render(
            listOf(message("你好"), message("在呢", incoming = false))
        )
        assertTrue(text.contains("对方：你好"))
        assertTrue(text.contains("我：在呢"))
    }
}
