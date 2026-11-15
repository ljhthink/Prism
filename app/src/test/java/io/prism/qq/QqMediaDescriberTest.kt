package io.prism.qq

import io.prism.qq.channel.QqMediaKind
import io.prism.qq.channel.QqMediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入站媒体描述单测（2026-11-15；PRD US-3131）。
 *
 * 真机反馈「LLM 看不到用户发的表情」根因：`face`（QQ 内置表情）在回复链路里被整条丢弃，
 * 模型只拿到 `[表情]` 占位符。本用例锁死"每种媒体都必须被描述出来"。
 */
class QqMediaDescriberTest {

    private fun ref(kind: QqMediaKind, summary: String? = null, size: Long = 0L) =
        QqMediaRef(kind = kind, summary = summary, fileSize = size)

    @Test
    fun `无媒体返回 null`() {
        assertNull(QqMediaDescriber.describe(emptyList()))
    }

    @Test
    fun `内置表情翻译成情绪而不是空白占位`() {
        // 14 = 开心（目录里的稳定映射）
        val text = QqMediaDescriber.describe(listOf(ref(QqMediaKind.FACE, "14")))
        assertEquals("对方发来 QQ 表情：开心", text)
    }

    @Test
    fun `未知 face id 也要说明而不是丢弃`() {
        val text = QqMediaDescriber.describe(listOf(ref(QqMediaKind.FACE, "9999")))
        assertTrue("必须包含 id 以便排查", text!!.contains("9999"))
        assertTrue(text.contains("QQ 表情"))
    }

    @Test
    fun `face 无 id 时仍给出一句可读描述`() {
        val text = QqMediaDescriber.describe(listOf(ref(QqMediaKind.FACE)))
        assertEquals("对方发来一个 QQ 表情", text)
    }

    @Test
    fun `表情包保留协议端摘要`() {
        val text = QqMediaDescriber.describe(
            listOf(ref(QqMediaKind.STICKER, "[动画表情]", 10508))
        )
        assertTrue(text!!.contains("表情包"))
        assertTrue("摘要不能丢（下载失败时是唯一线索）", text.contains("[动画表情]"))
        assertTrue(text.contains("KB"))
    }

    @Test
    fun `图片描述不把表情摘要误当图片说明`() {
        val text = QqMediaDescriber.describe(listOf(ref(QqMediaKind.IMAGE, "海边照片", 2048)))
        assertTrue(text!!.contains("图片"))
        assertTrue(text.contains("海边照片"))
    }

    @Test
    fun `语音与视频都有可读描述`() {
        assertEquals("对方发来一条语音", QqMediaDescriber.describe(listOf(ref(QqMediaKind.VOICE))))
        assertEquals("对方发来一段视频", QqMediaDescriber.describe(listOf(ref(QqMediaKind.VIDEO))))
    }

    @Test
    fun `多条媒体全部出现`() {
        val text = QqMediaDescriber.describe(
            listOf(
                ref(QqMediaKind.FACE, "14"),
                ref(QqMediaKind.IMAGE, "照片"),
                ref(QqMediaKind.STICKER, "[动画表情]")
            )
        )
        val lines = text!!.split("\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("开心"))
        assertTrue(lines[1].contains("图片"))
        assertTrue(lines[2].contains("表情包"))
    }

    @Test
    fun `情绪反查覆盖目录里的全部内置表情`() {
        QqStickerCatalog.allFaceIds().forEach { id ->
            val emotion = QqMediaDescriber.emotionOfFace(id)
            assertTrue("faceId=$id 必须有情绪标签", !emotion.isNullOrBlank())
        }
    }
}
