package io.prism.voice.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * LVC-1（ADR-053 决策 5）：参考音频选择单测（PRD AC3）。
 *
 * 契约：按"目标文本与参考文本的模型可见字数差最小"确定性选取；平局取清单序首个；
 * 参考集为空 / 全部参考文本规范化后为空 → null（调用方据此判失败）。
 */
class LocalReferenceSelectorTest {

    private fun ref(id: String, text: String) = LocalReference(id = id, audio = File("$id.wav"), text = text)

    @Test
    fun `选字数最接近的参考`() {
        // 规范可见长度：short=3，mid=11，long=35
        val refs = listOf(
            ref("short", "你好呀"),
            ref("mid", "你好，今天过得怎么样呢"),
            ref("long", "能在梦里听见朦胧的神谕，还在它的指引下前行，人家也觉得很神奇呢，真的哦")
        )
        // 目标 8 字：|3-8|=5、|11-8|=3 → mid
        assertEquals("mid", LocalReferenceSelector.select("今天心情很不错呢", refs)?.id)
        // 目标 2 字：|3-2|=1 → short
        assertEquals("short", LocalReferenceSelector.select("好的", refs)?.id)
        // 目标 31 字：|35-31|=4 → long
        assertEquals(
            "long",
            LocalReferenceSelector.select("能在梦里听见朦胧的神谕，还在它的指引下前行，人家也觉得很神奇呢", refs)?.id
        )
        // 目标 7 字：|3-7|=4 与 |11-7|=4 平局 → 清单序首个（short）
        assertEquals("short", LocalReferenceSelector.select("今天心情不错吧", refs)?.id)
    }

    @Test
    fun `平局以清单顺序为准 - 逆序时换成先出现者`() {
        val refs = listOf(ref("a", "abcd"), ref("b", "efgh"), ref("c", "abcdefghij"))
        // 目标 4 字：a/b 差值同为 0 → 清单中先出现者胜出
        assertEquals("a", LocalReferenceSelector.select("wxyz", refs)?.id)
        assertEquals("b", LocalReferenceSelector.select("wxyz", refs.reversed())?.id)
    }

    @Test
    fun `同一输入多次选择结果一致（确定性）`() {
        val refs = listOf(ref("a", "abcd"), ref("b", "efgh"))
        val first = LocalReferenceSelector.select("wxyz", refs)?.id
        repeat(20) {
            assertEquals(first, LocalReferenceSelector.select("wxyz", refs)?.id)
        }
    }

    @Test
    fun `字数比较使用规范化后的文本`() {
        // 「♪「」」等无音素符号应被规范化剔除后再比长度：ref-a 有效长度 4，ref-b 有效长度 10
        val refs = listOf(ref("a", "你好世界♪「」"), ref("b", "你好世界，今天真不错"))
        assertEquals("a", LocalReferenceSelector.select("一二三四", refs)?.id)
    }

    @Test
    fun `空参考集或全部无有效文本返回 null`() {
        assertNull(LocalReferenceSelector.select("你好", emptyList()))
        assertNull(LocalReferenceSelector.select("你好", listOf(ref("x", "♪「」"), ref("y", "   "))))
    }
}
