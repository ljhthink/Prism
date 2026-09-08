package io.prism.voice.rolepack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次23-B（ADR-048）：[RolePackStyleApplier] 单测 —— voiceCall 机械切分（纯函数）。
 *
 * 覆盖：sentenceBreak=false 整段 / 常规句末切分 / 超长句二次切分与硬切 /
 * 纯标点碎片合并（省略号）/ 空白输入 / maxSentenceLen<=0 不限制。
 */
class RolePackStyleApplierTest {

    private val default = RolePack.VoiceCallStyle(maxSentenceLen = 30, sentenceBreak = true)

    @Test
    fun `空白输入 - 返回空列表`() {
        assertTrue(RolePackStyleApplier.apply("", default).isEmpty())
        assertTrue(RolePackStyleApplier.apply("   \n ", default).isEmpty())
    }

    @Test
    fun `sentenceBreak false - 整段单元素不切分`() {
        val style = RolePack.VoiceCallStyle(maxSentenceLen = 5, sentenceBreak = false)
        val out = RolePackStyleApplier.apply("第一句。第二句！第三句？", style)
        assertEquals(listOf("第一句。第二句！第三句？"), out)
    }

    @Test
    fun `常规切分 - 句末标点保留在段尾`() {
        val out = RolePackStyleApplier.apply("你好。今天天气怎么样？很高兴见到你！", default)
        assertEquals(listOf("你好。", "今天天气怎么样？", "很高兴见到你！"), out)
    }

    @Test
    fun `常规切分 - 半角标点与换行同样切分`() {
        val out = RolePackStyleApplier.apply("Ready? Go! 下一行\n继续；结束;", default)
        // 段内空白原样保留（拼接还原原文的不变量；TTS 播报对空白不敏感）
        assertEquals(listOf("Ready?", " Go!", " 下一行\n", "继续；", "结束;"), out)
    }

    @Test
    fun `无句末标点 - 整段单元素`() {
        val out = RolePackStyleApplier.apply("这是一段没有标点结尾的话", default)
        assertEquals(listOf("这是一段没有标点结尾的话"), out)
    }

    @Test
    fun `省略号 - 纯标点碎片并入前段`() {
        val out = RolePackStyleApplier.apply("等等……好的。", default)
        assertEquals(listOf("等等……", "好的。"), out)
    }

    @Test
    fun `超长句 - 按子句标点二次切分后均不超上限`() {
        // 一句 40+ 字符，句中带逗号
        val long = "这一句话特别长因为它包含了很多内容需要被切分，好让语音播报听起来更自然流畅一些。"
        val out = RolePackStyleApplier.apply(long, default)
        assertTrue("所有分段长度应 <= 30，实际：$out", out.all { it.length <= 30 })
        assertEquals(long, out.joinToString(""))  // 切分不丢字
    }

    @Test
    fun `超长无标点 - 硬切为等长子段`() {
        val style = RolePack.VoiceCallStyle(maxSentenceLen = 5, sentenceBreak = true)
        val out = RolePackStyleApplier.apply("一二三四五六七八九", style)  // 9 字
        assertEquals(listOf("一二三四五", "六七八九"), out)
    }

    @Test
    fun `maxSentenceLen 非正 - 视为不限制（仅句末切分）`() {
        val style = RolePack.VoiceCallStyle(maxSentenceLen = 0, sentenceBreak = true)
        val long = "这一句远超三十个字符但没有逗号子句可以用来切分所以只能整句保留不被硬切。"
        val out = RolePackStyleApplier.apply(long, style)
        assertEquals(listOf(long), out)
    }

    @Test
    fun `拼接语义 - 切分后顺序拼接等于原文`() {
        val text = "第一句，带逗号。第二句！第三句；第四句：最后的收尾。"
        val out = RolePackStyleApplier.apply(text, default)
        assertEquals(text, out.joinToString(""))
    }
}
