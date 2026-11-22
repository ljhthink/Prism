package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import io.prism.qq.channel.QqMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 合并窗口的**行为级**装配证明（ac-verifier，2026-11-22；US-3139 AC-9）。
 *
 * ## 为什么必须有这个测试
 *
 * guardrail R2 §1 B-01「残留（不阻断，交 ac-verifier）」明确写道：
 * 「R1 要求的"装配级证明两条 200ms 内连发 → 一次 claim 拿到 2 条"目前只由**源码扫描**断言覆盖
 * （`QqNotifyWiringTest`），而非行为断言」。而真机日志 `（收件箱）批=2 条` 需真实对端连发。
 *
 * 本测试把 `PrismApplication.processInboxOnce` 的合并循环（`while (waited < MAX_LOOP_WAIT_MS)`
 * → `due()` → `waitMs()` → `delay()` → `claimDue()`）**按生产代码原样**跑在真实
 * [QqInboxStore] 上（临时目录、真实时钟、真实 200ms 间隔），因此：
 * - 它是**行为断言**，不是源码扫描；
 * - 它证明的是"合并窗口 + 原子取件"这一对组合，而非单测里的纯函数单点。
 *
 * 代价：一次真实 1.2s 级等待（`QUIET_MS`），故整类只跑一条主用例。
 */
class QqInboxMergeBehaviorTest {

    private fun msg(id: String, text: String, atMs: Long, media: List<QqMediaRef> = emptyList()) =
        QqMessage(messageId = id, incoming = true, text = text, timestampMs = atMs, media = media)

    @Test
    fun `两条 200ms 内连发必须在同一次 claim 中取到 2 条`() = runBlocking {
        val dir = Files.createTempDirectory("qq-inbox-merge").toFile()
        try {
            val store = QqInboxStore(dir)
            val t0 = System.currentTimeMillis()
            // 第一条（文本）先到
            assertTrue(store.enqueue(msg("sim-a", "还没呢，还在忙", t0)))
            // 200ms 后第二条（表情包）到 —— 真机 22:23:39 / 22:23:42 的形态
            val producer = launch {
                delay(200L)
                store.enqueue(
                    msg(
                        id = "sim-b",
                        text = "[表情包]",
                        atMs = System.currentTimeMillis(),
                        media = listOf(QqMediaRef(kind = QqMediaKind.STICKER, summary = "猫猫"))
                    )
                )
            }

            // ↓↓↓ 与 PrismApplication.processInboxOnce 的合并循环逐行对应 ↓↓↓
            var waited = 0L
            var loops = 0
            while (waited < QqInboxCoalescer.MAX_LOOP_WAIT_MS) {
                val nowMs = System.currentTimeMillis()
                val queued = store.due(nowMs)
                if (queued.isEmpty()) break
                val waitMs = QqInboxCoalescer.waitMs(nowMs, queued.map { it.atMs })
                if (waitMs <= 0L) break
                delay(waitMs)
                waited += waitMs
                loops++
            }
            producer.join()

            val claimed = store.claimDue(System.currentTimeMillis(), limit = QqInboxStore.BURST_LIMIT)
            assertEquals(
                "两条消息必须在同一次 claim 里（等价于真机日志「（收件箱）批=2 条」）",
                2,
                claimed.size
            )
            assertEquals(listOf("sim-a", "sim-b"), claimed.map { it.id })
            assertTrue("必须真的进入过合并等待", loops >= 1)
            assertTrue("总等待不得超过硬上限", waited <= QqInboxCoalescer.MAX_LOOP_WAIT_MS)
            // 取件后收件箱必须清空（不悬空）
            assertTrue(store.due(System.currentTimeMillis()).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `纯表情批在 10s 窗口内应被折叠判定命中`() {
        // 折叠判定的输入取自 claim 结果；此处直接对齐装配处的取参方式
        val now = System.currentTimeMillis()
        val messages = listOf(
            msg("sim-c", "[表情包]", now, listOf(QqMediaRef(kind = QqMediaKind.STICKER))),
            msg("sim-d", "[表情]", now, listOf(QqMediaRef(kind = QqMediaKind.FACE)))
        )
        val folded = QqMediaFollow.shouldFold(
            texts = messages.map { it.text },
            mediaKinds = messages.flatMap { m -> m.media.map { it.kind.name } },
            nowMs = now,
            lastRepliedAtMs = now - 3_200L
        )
        assertTrue("纯表情跟随必须折叠", folded)
        assertTrue(QqMediaFollow.logLine(now, now - 3_200L, folded = true).contains("纯表情跟随"))
    }

    @Test
    fun `含图片的批不得被折叠判定命中`() {
        val now = System.currentTimeMillis()
        val messages = listOf(
            msg("sim-e", "[图片]", now, listOf(QqMediaRef(kind = QqMediaKind.IMAGE))),
            msg("sim-f", "[表情包]", now, listOf(QqMediaRef(kind = QqMediaKind.STICKER)))
        )
        assertFalse(
            QqMediaFollow.shouldFold(
                texts = messages.map { it.text },
                mediaKinds = messages.flatMap { m -> m.media.map { it.kind.name } },
                nowMs = now,
                lastRepliedAtMs = now - 1_000L
            )
        )
    }
}
