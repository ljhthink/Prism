package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QqReplyDirective] 表情/语音指令解析（2026-10-26；US-3122）。 */
class QqReplyDirectiveTest {

    @Test
    fun `末尾表情指令被剥离`() {
        val parsed = QqReplyDirective.parse("好呀，马上到 [[sticker:开心]]")
        assertEquals("好呀，马上到", parsed.text)
        assertEquals("开心", parsed.stickerEmotion)
        assertFalse(parsed.voice)
    }

    @Test
    fun `全角冒号也支持`() {
        assertEquals("收到", QqReplyDirective.parse("收到[[sticker：无语]]").text)
    }

    @Test
    fun `语音指令被识别`() {
        val parsed = QqReplyDirective.parse("我想你了 [[voice]]")
        assertEquals("我想你了", parsed.text)
        assertTrue(parsed.voice)
    }

    @Test
    fun `不允许媒体时原样返回`() {
        val raw = "你好 [[sticker:开心]]"
        val parsed = QqReplyDirective.parse(raw, allowMedia = false)
        assertEquals(raw, parsed.text)
        assertEquals(null, parsed.stickerEmotion)
        assertFalse(parsed.voice)
    }

    @Test
    fun `指令不在末尾时不剥离`() {
        val raw = "[[sticker:开心]] 后面还有话"
        val parsed = QqReplyDirective.parse(raw)
        assertEquals(raw, parsed.text)
    }

    @Test
    fun `只有指令时回退原文避免空消息`() {
        val parsed = QqReplyDirective.parse("[[voice]]")
        assertEquals("[[voice]]", parsed.text)
        assertTrue(parsed.voice)
    }

    @Test
    fun `普通文本不受影响`() {
        val parsed = QqReplyDirective.parse("今天天气不错")
        assertEquals("今天天气不错", parsed.text)
        assertEquals(null, parsed.stickerEmotion)
    }

    @Test
    fun `媒体关闭时提示为空`() {
        assertEquals("", QqReplyDirective.promptHint(false, false))
        assertTrue(QqReplyDirective.promptHint(true, false).contains("sticker"))
        assertTrue(QqReplyDirective.promptHint(false, true).contains("voice"))
    }
}
