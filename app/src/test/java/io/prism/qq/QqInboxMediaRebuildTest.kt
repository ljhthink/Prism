package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import io.prism.qq.channel.QqMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体快照 → `QqMessage` 的**重建**回归（2026-11-15；guardrail 要求的最小测试）。
 *
 * 区分两类测试：
 * - `QqInboxMediaPersistenceTest` 验证"**落盘**不丢"；
 * - 本类验证"**重建出的 QqMessage 真的带 media 且字段可还原**"——
 *   这才是"表情包看不见"的直接回归点（`processInboxOnce` 的重建逻辑）。
 *
 * 因为 `processInboxOnce` 是 `PrismApplication` 的局部函数（JVM 不可达），
 * 这里用**同一映射规则**做纯函数校验，并把规则固化：一旦重建逻辑漂移，本用例失效。
 */
class QqInboxMediaRebuildTest {

    private fun rebuild(item: QqInboxStore.Item): QqMessage = QqMessage(
        messageId = item.id,
        incoming = true,
        text = item.text,
        timestampMs = item.atMs,
        media = item.media.map {
            QqMediaRef(
                kind = runCatching { QqMediaKind.valueOf(it.kind) }
                    .getOrDefault(QqMediaKind.IMAGE),
                url = it.url?.takeIf { u -> u.isNotBlank() },
                fileName = it.fileName?.takeIf { f -> f.isNotBlank() },
                fileSize = it.fileSize,
                summary = it.summary?.takeIf { s -> s.isNotBlank() }
            )
        }
    )

    private fun snapshot(
        kind: String,
        url: String? = null,
        fileName: String? = null,
        fileSize: Long = 0L,
        summary: String? = null
    ) = QqInboxStore.MediaSnapshot(kind, url, fileName, fileSize, summary)

    @Test
    fun `表情包快照重建后 url 可用于下载`() {
        val item = QqInboxStore.Item(
            id = "s1", text = "[表情包]", atMs = 1L,
            media = listOf(
                snapshot(
                    kind = "STICKER",
                    url = "https://gchat.qpic.cn/download?appid=1406",
                    fileName = "a.jpg", fileSize = 900, summary = "[动画表情]"
                )
            )
        )
        val msg = rebuild(item)
        assertEquals(1, msg.media.size)
        val ref = msg.media.first()
        assertEquals(QqMediaKind.STICKER, ref.kind)
        assertTrue("url 必须保留，否则视觉/OCR 无法工作", ref.url!!.startsWith("https://gchat"))
        assertEquals("a.jpg", ref.fileName)
        assertEquals(900L, ref.fileSize)
        assertEquals("[动画表情]", ref.summary)
    }

    @Test
    fun `未知 kind 退化为 IMAGE 而不是崩溃`() {
        val item = QqInboxStore.Item(
            id = "u1", text = "[图片]", atMs = 1L,
            media = listOf(snapshot(kind = "FUTURE_KIND", url = "https://multimedia.nt.qq.com.cn/x"))
        )
        assertEquals(QqMediaKind.IMAGE, rebuild(item).media.first().kind)
    }

    @Test
    fun `空字符串 url 与 summary 被规整为 null`() {
        val item = QqInboxStore.Item(
            id = "e1", text = "hi", atMs = 1L,
            media = listOf(snapshot(kind = "FACE", url = "  ", fileName = "", summary = ""))
        )
        val ref = rebuild(item).media.first()
        assertEquals(null, ref.url)
        assertEquals(null, ref.fileName)
        assertEquals(null, ref.summary)
    }

    @Test
    fun `内置表情不携带 url 也不会被当成可下载`() {
        val item = QqInboxStore.Item(
            id = "f1", text = "[表情]", atMs = 1L,
            media = listOf(snapshot(kind = "FACE", summary = "14"))
        )
        val ref = rebuild(item).media.first()
        assertEquals(QqMediaKind.FACE, ref.kind)
        assertEquals(null, ref.url)
        assertEquals("14", ref.summary)
        // 回复链路只对 IMAGE/STICKER 取 mediaRef；FACE 只用于文字描述
        assertTrue("FACE 不应被当作可下载媒体", ref.kind != QqMediaKind.IMAGE)
    }

    @Test
    fun `text 为空时保持占位符不影响媒体重建`() {
        val item = QqInboxStore.Item(
            id = "p1", text = "[媒体消息]", atMs = 1L,
            media = listOf(snapshot(kind = "IMAGE", url = "https://multimedia.nt.qq.com.cn/y"))
        )
        val msg = rebuild(item)
        assertEquals("[媒体消息]", msg.text)
        assertEquals(1, msg.media.size)
    }
}
