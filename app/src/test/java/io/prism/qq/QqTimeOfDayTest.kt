package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqTimeOfDay] 单元测试（ADR-063 修订 2026-10-25；PRD US-3115 AC-1）。
 *
 * 覆盖：宽松解析、24:00 上界、越界拒绝、格式化补零、跨零点合法性判定。
 */
class QqTimeOfDayTest {

    @Test
    fun `解析常见写法`() {
        assertEquals(0, QqTimeOfDay.parse("0:00"))
        assertEquals(420, QqTimeOfDay.parse("07:00"))
        assertEquals(420, QqTimeOfDay.parse("7:00"))
        assertEquals(420, QqTimeOfDay.parse("7"))
        assertEquals(425, QqTimeOfDay.parse("7:5"))
        assertEquals(1440, QqTimeOfDay.parse("24:00"))
        assertEquals(1439, QqTimeOfDay.parse("23:59"))
        assertEquals(420, QqTimeOfDay.parse("  7:00  "))
    }

    @Test
    fun `非法输入返回 null`() {
        assertNull(QqTimeOfDay.parse(null))
        assertNull(QqTimeOfDay.parse(""))
        assertNull(QqTimeOfDay.parse("  "))
        assertNull(QqTimeOfDay.parse("7:60"))
        assertNull(QqTimeOfDay.parse("25:00"))
        assertNull(QqTimeOfDay.parse("24:01"))
        assertNull(QqTimeOfDay.parse("-1:00"))
        assertNull(QqTimeOfDay.parse("7:00:00"))
        assertNull(QqTimeOfDay.parse("abc"))
        assertNull(QqTimeOfDay.parse("7点"))
    }

    @Test
    fun `格式化补零并支持 24 点`() {
        assertEquals("00:00", QqTimeOfDay.format(0))
        assertEquals("07:00", QqTimeOfDay.format(420))
        assertEquals("23:59", QqTimeOfDay.format(1439))
        assertEquals("24:00", QqTimeOfDay.format(1440))
        // 越界钳制（防脏值流入 UI）
        assertEquals("00:00", QqTimeOfDay.format(-5))
        assertEquals("24:00", QqTimeOfDay.format(5000))
    }

    @Test
    fun `时段合法性_允许跨零点_拒绝起止相同与越界`() {
        assertTrue(QqTimeOfDay.isValidWindow(420, 1320))
        // 跨零点合法
        assertTrue(QqTimeOfDay.isValidWindow(1320, 120))
        assertFalse(QqTimeOfDay.isValidWindow(420, 420))
        assertFalse(QqTimeOfDay.isValidWindow(-1, 420))
        assertFalse(QqTimeOfDay.isValidWindow(420, 1441))
        assertFalse(QqTimeOfDay.isValidWindow(420, 0))
        assertTrue(QqTimeOfDay.isValidWindow(0, 1440))
    }

    @Test
    fun `跨零点判定与描述文案`() {
        assertTrue(QqTimeOfDay.crossesMidnight(1320, 120))
        assertFalse(QqTimeOfDay.crossesMidnight(420, 1320))
        assertEquals("07:00-22:00", QqTimeOfDay.describe(420, 1320))
        assertTrue(QqTimeOfDay.describe(1320, 120).contains("跨零点"))
    }
}
