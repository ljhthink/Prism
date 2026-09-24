package io.prism.voice.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LVC-1（ADR-053 决策 6）：ZipVoice 文本规范化单测（PRD AC2）。
 *
 * 契约：只做"去除无音素符号 + 停顿符号转逗号 + 压缩空白/连续逗号"，
 * **不做全角→半角转换、不改写数字**（依据：官方示例混用半角/全角标点均可工作）。
 */
class LocalCloneTextNormalizerTest {

    @Test
    fun `无音素符号被删除`() {
        assertEquals(
            "以爱为始的故事，也一定以爱为终",
            LocalCloneTextNormalizer.normalize("以爱为始的故事，也一定以爱为终♪")
        )
        assertEquals("名为昔涟的少女", LocalCloneTextNormalizer.normalize("名为「昔涟」的少女"))
        assertEquals(
            "愿开拓，共赴爱的约定",
            LocalCloneTextNormalizer.normalize("愿【开拓】，共赴『爱』的约定♫")
        )
    }

    @Test
    fun `停顿符号归一为逗号并压缩连续逗号`() {
        assertEquals("别眨眼，锵锵，送你一朵花", LocalCloneTextNormalizer.normalize("别眨眼…锵锵——送你一朵花"))
        assertEquals("等等，好吗", LocalCloneTextNormalizer.normalize("等等~~~ 好吗"))
        // 停顿符号与已有逗号相邻时压缩为一个
        assertEquals("你好，世界", LocalCloneTextNormalizer.normalize("你好，…世界"))
    }

    @Test
    fun `全角标点与数字字母原样保留（不做全角转半角）`() {
        assertEquals("下一页是空白呢，那我们一起写下吧？", LocalCloneTextNormalizer.normalize("下一页是空白呢，那我们一起写下吧？"))
        assertEquals("1987年 PhiLia093 你好。", LocalCloneTextNormalizer.normalize("1987年 PhiLia093 你好。"))
    }

    @Test
    fun `空白压缩与首尾裁剪`() {
        assertEquals("你好 世界", LocalCloneTextNormalizer.normalize("  你好   世界  "))
        // 全角空格与半角空白一并压缩为单个半角空格
        assertEquals("你好 世界", LocalCloneTextNormalizer.normalize("你好\u3000世界"))
        // CJK 标点两侧空白被剥离（不触碰半角逗号，英文文本不受影响）
        assertEquals("你好，世界", LocalCloneTextNormalizer.normalize("你好 ， 世界"))
        assertEquals("hello, world", LocalCloneTextNormalizer.normalize("hello, world"))
    }

    @Test
    fun `空输入与纯符号输入返回空串`() {
        assertEquals("", LocalCloneTextNormalizer.normalize(""))
        assertEquals("", LocalCloneTextNormalizer.normalize("   "))
        assertEquals("", LocalCloneTextNormalizer.normalize("♪「」【】"))
        // 仅剩标点（首尾逗号被裁掉）→ 空串，调用方据此判失败而不是把空串送进模型
        assertEquals("", LocalCloneTextNormalizer.normalize("…——～"))
    }

    @Test
    fun `幂等性 - 规范化两次结果一致`() {
        val once = LocalCloneTextNormalizer.normalize("看好咯，别眨眼…锵锵——送你一朵盛开的花♪")
        assertEquals(once, LocalCloneTextNormalizer.normalize(once))
    }
}
