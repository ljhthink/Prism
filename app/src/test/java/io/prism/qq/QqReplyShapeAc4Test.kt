package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AC-4（US-3139）：形状池 7 → 9，删「再顺口问一件具体的小事」，新增两条**明确不许提问**的形状。
 *
 * 为什么需要本文件：既有 `QqReplyShapeAndBubblesTest` 只断言"池非空 + 抽样必属池内元素"，
 * 对"池里有几条""那条许可证式提示是否被删掉"没有任何守护。
 * 本次修复的**因果链**是：那条形状提示 = 真机上"每轮都问点什么"的许可证 →
 * 用户抱怨"提问句过多"。删了却没断言，下一轮改动可以静默加回来。
 */
class QqReplyShapeAc4Test {

    private fun shapeSource(): String {
        val candidates = listOf(
            File("src/main/java/io/prism/qq/QqReplyShape.kt"),
            File("app/src/main/java/io/prism/qq/QqReplyShape.kt")
        )
        return (candidates.firstOrNull { it.isFile }
            ?: error("找不到 QqReplyShape.kt")).readText()
    }

    /** 取出 SHAPES 列表里的字符串字面量（按出现顺序）。 */
    private fun shapes(): List<String> {
        val src = shapeSource()
        val start = src.indexOf("private val SHAPES = listOf(")
        assertTrue("必须存在 SHAPES 列表", start >= 0)
        val body = src.substring(start, src.indexOf(")", start))
        return Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `形状池恰好 9 条`() {
        assertEquals("AC-4 要求 7 → 9", 9, QqReplyShape.size)
        assertEquals("源码字面量数量必须与暴露的 size 一致", 9, shapes().size)
    }

    @Test
    fun `「再顺口问一件具体的小事」必须已被删除`() {
        for (s in shapes()) {
            assertFalse("许可证式提问提示不得回归：$s", s.contains("顺口问"))
        }
        // KDoc 里保留该措辞是**合法**的（记录"删了什么、为什么删"）；
        // 不允许的是它重新出现在 SHAPES 字面量里（上面的循环已钉死）。
        val shapesBody = shapeSource().substringAfter("private val SHAPES = listOf(").substringBefore(")")
        assertFalse("SHAPES 列表不得再含该措辞", shapesBody.contains("再顺口问一件具体的小事"))
    }

    @Test
    fun `必须有两处明确的禁止提问提示`() {
        val list = shapes()
        assertTrue(
            "必须有「一个问号都不要有」一类形状，实际=${list.size} 条",
            list.any { it.contains("问号都不要有") }
        )
        assertTrue(
            "必须有「短到只够半句」一类形状（可以不问、可以只说半句）",
            list.any { it.contains("半句") }
        )
    }

    @Test
    fun `至少一条形状明确要求不要用问句收尾`() {
        assertTrue(
            "必须保留一条「不要用问句收尾」的形状",
            shapes().any { it.contains("不要用问句收尾") }
        )
    }

    @Test
    fun `形状文本不得重复（9 条必须是 9 个不同提示）`() {
        val list = shapes()
        assertEquals("不得有重复形状", list.size, list.toSet().size)
    }
}
