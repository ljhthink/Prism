package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqReplyDirective] / [QqMediaIntent]（2026-10-26 真机加固回归锁）。
 *
 * 真机缺陷：模型输出 `好呀 [[sticker:开心]]。` 时旧解析器要求"标记后无任何字符"，
 * 导致 ①表情永远发不出去 ②字面标记被当正文发给用户。
 */
class QqReplyDirectiveTest {

    @Test
    fun `末尾表情指令被剥离`() {
        val parsed = QqReplyDirective.parse("好呀，马上到 [[sticker:开心]]")
        assertEquals("好呀，马上到", parsed.text)
        assertEquals("开心", parsed.stickerEmotion)
        assertFalse(parsed.voice)
        assertTrue(parsed.hasDirective)
    }

    @Test
    fun `标记后跟句号也必须生效（真机缺陷回归锁）`() {
        val parsed = QqReplyDirective.parse("好呀，给你一个～ [[sticker:开心]]。")
        assertEquals("好呀，给你一个～", parsed.text)
        assertEquals("开心", parsed.stickerEmotion)
    }

    @Test
    fun `标记后跟换行与感叹号`() {
        val parsed = QqReplyDirective.parse("接住啦！\n[[sticker:调皮]]！\n")
        assertEquals("接住啦！", parsed.text)
        assertEquals("调皮", parsed.stickerEmotion)
    }

    @Test
    fun `全角冒号与中文别名`() {
        assertEquals("收到", QqReplyDirective.parse("收到[[表情：无语]]").text)
        assertEquals("无语", QqReplyDirective.parse("收到[[表情包:无语]]").stickerEmotion)
    }

    @Test
    fun `语音指令被识别（含别名）`() {
        assertTrue(QqReplyDirective.parse("我想你了 [[voice]]").voice)
        assertTrue(QqReplyDirective.parse("我想你了 [[语音]]").voice)
    }

    @Test
    fun `不允许媒体时清除字面标记`() {
        val parsed = QqReplyDirective.parse("你好 [[sticker:开心]]", allowMedia = false)
        assertEquals("你好", parsed.text)
        assertNull(parsed.stickerEmotion)
        assertFalse(parsed.voice)
    }

    @Test
    fun `指令不在末尾时不误吃且清除残留标记`() {
        val parsed = QqReplyDirective.parse("[[sticker:开心]] 后面还有话")
        assertFalse(parsed.text.contains("[["))
        assertFalse(parsed.text.contains("]]"))
        assertTrue(parsed.text.contains("后面还有话"))
    }

    @Test
    fun `只有标记时文本为空以便只发媒体`() {
        val parsed = QqReplyDirective.parse("[[sticker:开心]]")
        assertEquals("", parsed.text)
        assertEquals("开心", parsed.stickerEmotion)
    }

    @Test
    fun `只有语音标记时文本为空`() {
        val parsed = QqReplyDirective.parse("[[voice]]")
        assertEquals("", parsed.text)
        assertTrue(parsed.voice)
    }

    @Test
    fun `普通文本不受影响`() {
        val parsed = QqReplyDirective.parse("今天天气不错")
        assertEquals("今天天气不错", parsed.text)
        assertNull(parsed.stickerEmotion)
        assertFalse(parsed.voice)
        assertFalse(parsed.hasDirective)
    }

    @Test
    fun `未闭合标记也会被清除`() {
        assertEquals("看这里", QqReplyDirective.stripMarkers("看这里[[sticker:开心]"))
    }

    @Test
    fun `媒体关闭时提示为空`() {
        assertEquals("", QqReplyDirective.promptHint(false, false))
        assertTrue(QqReplyDirective.promptHint(true, false).contains("sticker"))
        assertTrue(QqReplyDirective.promptHint(false, true).contains("voice"))
        // 2026-10-26：提示必须明确"对方要求时必加"，否则模型仍然不写
        assertTrue(QqReplyDirective.promptHint(true, true).contains("必须"))
    }
}

/** [QqMediaIntent]：用户明确要表情时必须发（不依赖模型）。 */
class QqMediaIntentTest {

    @Test
    fun `识别要表情的说法`() {
        assertTrue(QqMediaIntent.wantsSticker("发个表情包"))
        assertTrue(QqMediaIntent.wantsSticker("给我发个表情"))
        assertTrue(QqMediaIntent.wantsSticker("来张图"))
        assertFalse(QqMediaIntent.wantsSticker("今天好累"))
    }

    @Test
    fun `识别要语音的说法`() {
        assertTrue(QqMediaIntent.wantsVoice("发条语音"))
        assertTrue(QqMediaIntent.wantsVoice("说句话给我听"))
        assertFalse(QqMediaIntent.wantsVoice("在吗"))
    }

    @Test
    fun `从文案猜情绪`() {
        // "哈哈" 命中"大笑"（顺序敏感：先匹配更具体的词）
        assertEquals("大笑", QqMediaIntent.guessEmotion("哈哈哈笑死"))
        assertEquals("难过", QqMediaIntent.guessEmotion("今天好难过"))
        assertEquals("疑问", QqMediaIntent.guessEmotion("为什么啊"))
        assertNull(QqMediaIntent.guessEmotion("嗯"))
        assertEquals("开心", QqMediaIntent.DEFAULT_EMOTION)
    }
}
