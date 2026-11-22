package io.prism.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * [TimeNowLocalToolExecutor] 单测（2026-11-22；ADR-080）。
 *
 * 时间工具的价值是"可断言"——所以这里直接把时区钉死，避免 CI 因本地时区/夏令时漂移。
 */
class TimeNowLocalToolExecutorTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `只处理自己的工具名`() {
        val exec = TimeNowLocalToolExecutor()
        assertTrue(exec.handles(TimeNowLocalToolExecutor.TOOL_NAME))
        assertFalse(exec.handles("diary__write"))
        assertFalse(exec.handles(""))
    }

    @Test
    fun `描述含日期星期时段`() {
        // 2026-11-22 22:35 +08:00
        val ms = java.time.OffsetDateTime.of(2026, 11, 22, 22, 35, 0, 0, java.time.ZoneOffset.ofHours(8))
            .toInstant().toEpochMilli()
        val text = TimeNowLocalToolExecutor.describe(ms, zone)
        assertTrue(text.contains("2026-11-22 22:35:00"))
        assertTrue(text.contains("周日"))
        assertTrue(text.contains("晚上"))
    }

    @Test
    fun `时段边界`() {
        assertEquals("深夜", TimeNowLocalToolExecutor.periodOf(2))
        assertEquals("清晨", TimeNowLocalToolExecutor.periodOf(6))
        assertEquals("上午", TimeNowLocalToolExecutor.periodOf(10))
        assertEquals("中午", TimeNowLocalToolExecutor.periodOf(12))
        assertEquals("下午", TimeNowLocalToolExecutor.periodOf(16))
        assertEquals("晚上", TimeNowLocalToolExecutor.periodOf(21))
        assertEquals("深夜", TimeNowLocalToolExecutor.periodOf(23))
    }

    @Test
    fun `工具定义无必填参数且名字正确`() {
        val def = TimeNowLocalToolExecutor.buildToolDefinition()
        assertEquals("time__now", def.function.name)
        assertTrue(def.function.description.contains("当前的日期"))
    }

    @Test
    fun `未知工具名返回可读错误而不是抛异常`() = kotlinx.coroutines.runBlocking {
        val exec = TimeNowLocalToolExecutor { 0L }
        val out = exec.execute("other__tool", emptyMap())
        assertTrue(out.startsWith("错误"))
    }
}
