package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import io.prism.qq.channel.QqMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 收件箱**媒体持久化**回归（2026-11-15；PRD US-3132）。
 *
 * 真机反馈「非内置表情包看不见了」的根因：收件箱只持久化 `text`，
 * 重建 `QqMessage` 时 `media` 为空 → `mediaRef == null` → 视觉/OCR 整条失效，
 * 只能回一句"对方发来了表情包"。本用例锁死"media 跨落盘不丢"。
 */
class QqInboxMediaPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L

    private fun stickerMsg(id: String = "st1") = QqMessage(
        messageId = id,
        incoming = true,
        text = "[表情包]",
        timestampMs = t0,
        media = listOf(
            QqMediaRef(
                kind = QqMediaKind.STICKER,
                url = "https://gchat.qpic.cn/download?appid=1406&fileid=abc",
                fileName = "ABC123.jpg",
                fileSize = 10508,
                summary = "[动画表情]"
            )
        )
    )

    @Test
    fun `表情包媒体跨落盘不丢`() {
        val dir = tmp.newFolder()
        val store = QqInboxStore(dir)
        assertTrue(store.enqueue(stickerMsg()))

        // 模拟进程重启：从磁盘读回
        val reopened = QqInboxStore(dir)
        val item = reopened.due(t0 + 1000).first()
        assertEquals(1, item.media.size)
        val m = item.media.first()
        assertEquals("STICKER", m.kind)
        assertTrue("URL 必须保留（否则无法下载给视觉模型）", m.url!!.startsWith("https://gchat"))
        assertEquals("ABC123.jpg", m.fileName)
        assertEquals(10508L, m.fileSize)
        assertEquals("[动画表情]", m.summary)
    }

    @Test
    fun `内置表情只需 kind 与 summary`() {
        val dir = tmp.newFolder()
        val store = QqInboxStore(dir)
        store.enqueue(
            QqMessage(
                messageId = "f1", incoming = true, text = "[表情]",
                timestampMs = t0,
                media = listOf(QqMediaRef(kind = QqMediaKind.FACE, summary = "14"))
            )
        )
        val m = QqInboxStore(dir).due(t0 + 1000).first().media.first()
        assertEquals("FACE", m.kind)
        assertEquals("14", m.summary)
    }

    @Test
    fun `无媒体的消息媒体列表为空而不是崩溃`() {
        val dir = tmp.newFolder()
        QqInboxStore(dir).enqueue(
            QqMessage(messageId = "t1", incoming = true, text = "在吗", timestampMs = t0)
        )
        assertTrue(QqInboxStore(dir).due(t0 + 1000).first().media.isEmpty())
    }

    @Test
    fun `旧版无 m 字段的数据仍可读`() {
        val dir = tmp.newFolder()
        // 模拟旧格式（没有 "m" 数组）
        java.io.File(dir, QqInboxStore.FILE_NAME).writeText(
            """{"lastSeenAt":$t0,"items":[{"i":"old","t":"旧消息","a":$t0,"n":0,"x":0,"e":""}]}"""
        )
        val store = QqInboxStore(dir)
        val item = store.due(t0 + 1000).first()
        assertEquals("old", item.id)
        assertTrue("旧数据无 m → 空列表（向后兼容）", item.media.isEmpty())
    }

    @Test
    fun `多条媒体全部保留`() {
        val dir = tmp.newFolder()
        QqInboxStore(dir).enqueue(
            QqMessage(
                messageId = "multi", incoming = true, text = "",
                timestampMs = t0,
                media = listOf(
                    QqMediaRef(kind = QqMediaKind.FACE, summary = "14"),
                    QqMediaRef(
                        kind = QqMediaKind.IMAGE,
                        url = "https://multimedia.nt.qq.com.cn/download?x=1",
                        fileSize = 2048
                    )
                )
            )
        )
        val media = QqInboxStore(dir).due(t0 + 1000).first().media
        assertEquals(2, media.size)
        assertEquals("FACE", media[0].kind)
        assertEquals("IMAGE", media[1].kind)
    }
}
