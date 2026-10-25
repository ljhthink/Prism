package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * [QqSchedulePlanner] 单元测试（ADR-063；PRD US-3108 AC-2 / AC-6）。
 *
 * 纯 JVM（无 Android 依赖）：时钟以毫秒入参、随机源注入种子、时区固定 → 结果可复现。
 */
class QqSchedulePlannerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val windowStart = 20 * 60 // 20:00
    private val windowEnd = 23 * 60   // 23:00

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun todayStartMs(nowMs: Long): Long =
        java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `窗口非法时返回 null`() {
        val now = ms(2026, 10, 18, 12, 0)
        // 2026-10-25：起>止 现在是**合法的跨零点**窗口（ADR-063 修订），不再判非法
        assertNotNull(QqSchedulePlanner.nextTriggerAt(now, 1380, 1200, 0L, Random(1), zone = zone))
        // 起止相同 → 窗口长度为 0，非法
        assertNull(QqSchedulePlanner.nextTriggerAt(now, 1200, 1200, 0L, Random(1), zone = zone))
        // 窗止超过当日分钟数
        assertNull(QqSchedulePlanner.nextTriggerAt(now, 1200, 2000, 0L, Random(1), zone = zone))
    }

    @Test
    fun `触发时刻落在生效区间内且不早于最小间隔`() {
        val now = ms(2026, 10, 18, 21, 0)
        val minGap = 30L * 60_000L
        repeat(50) { i ->
            val t = QqSchedulePlanner.nextTriggerAt(now, windowStart, windowEnd, minGap, Random(i), zone = zone)
            assertNotNull(t)
            val dayStart = todayStartMs(now)
            val lower = maxOf(dayStart + windowStart * 60_000L, now + minGap)
            val end = dayStart + windowEnd * 60_000L
            assertTrue("t=$t lower=$lower end=$end", t!! in lower until end)
        }
    }

    @Test
    fun `当日窗已过则顺延到明日窗内`() {
        val now = ms(2026, 10, 18, 23, 30) // 今日窗（20:00-23:00）已过
        val t = QqSchedulePlanner.nextTriggerAt(now, windowStart, windowEnd, 0L, Random(7), zone = zone)
        assertNotNull(t)
        val tomorrowStart = todayStartMs(now) + 24L * 60 * 60 * 1000 + windowStart * 60_000L
        val tomorrowEnd = todayStartMs(now) + 24L * 60 * 60 * 1000 + windowEnd * 60_000L
        assertTrue("t=$t", t!! in tomorrowStart until tomorrowEnd)
    }

    @Test
    fun `连续调度产生非等间隔（真人感）`() {
        var now = ms(2026, 10, 18, 20, 0)
        val random = Random(20261018)
        val gaps = mutableListOf<Long>()
        repeat(6) {
            val t = QqSchedulePlanner.nextTriggerAt(now, windowStart, windowEnd, 5L * 60_000L, random, zone = zone)
            assertNotNull(t)
            gaps += t!! - now
            now = t
        }
        assertTrue("间隔序列应非等距：$gaps", gaps.distinct().size >= 2)
    }

    @Test
    fun `同种子同参数结果可复现`() {
        val now = ms(2026, 10, 18, 21, 15)
        val a = QqSchedulePlanner.nextTriggerAt(now, windowStart, windowEnd, 0L, Random(42), zone = zone)
        val b = QqSchedulePlanner.nextTriggerAt(now, windowStart, windowEnd, 0L, Random(42), zone = zone)
        assertEquals(a, b)
    }

    @Test
    fun `窗口内校验识别窗口外时刻（N-9）`() {
        val inside = ms(2026, 10, 18, 21, 0)
        val outside = ms(2026, 10, 18, 9, 0)
        assertTrue(QqSchedulePlanner.isWithinWindow(inside, windowStart, windowEnd, zone))
        assertTrue(!QqSchedulePlanner.isWithinWindow(outside, windowStart, windowEnd, zone))
        // 窗口非法 → 一律 false
        assertTrue(!QqSchedulePlanner.isWithinWindow(inside, 1380, 1200, zone))
    }

    @Test
    fun `泊松策略仍落在生效区间内`() {
        val now = ms(2026, 10, 18, 20, 30)
        repeat(50) { i ->
            val t = QqSchedulePlanner.nextTriggerAt(
                now, windowStart, windowEnd, 0L, Random(i),
                strategy = QqSchedulePlanner.Strategy.POISSON, zone = zone
            )
            assertNotNull(t)
            val dayStart = todayStartMs(now)
            assertTrue("t=$t", t!! in dayStart + windowStart * 60_000L until dayStart + windowEnd * 60_000L)
        }
    }

    // ==================== ac-verifier 补充：极端 / 边界用例 ====================

    @Test
    fun `极大最小间隔（配置上限24h）仍可在明日窗内落点`() {
        val now = ms(2026, 10, 18, 21, 0)
        val dayGap = QqConfig.MAX_INTERVAL_MS_CEIL
        val dayStart = todayStartMs(now)
        val tomorrowStart = dayStart + 24L * 60 * 60 * 1000 + windowStart * 60_000L
        val tomorrowEnd = dayStart + 24L * 60 * 60 * 1000 + windowEnd * 60_000L
        repeat(50) { i ->
            val t = QqSchedulePlanner.nextTriggerAt(now, windowStart, windowEnd, dayGap, Random(i), zone = zone)
            assertNotNull(t)
            assertTrue("t=$t 应≥now+24h", t!! >= now + dayGap)
            assertTrue("t=$t", t in tomorrowStart until tomorrowEnd)
        }
    }

    @Test
    fun `最小间隔超过约一天时顺延到更晚的窗口而不是返回 null`() {
        val now = ms(2026, 10, 18, 21, 0)
        // 48h：2026-10-25 修订后搜索 -1..+2 天窗口，约束可满足（原实现只顺延一次 → null）
        val t = QqSchedulePlanner.nextTriggerAt(
            now, windowStart, windowEnd, 2 * 24L * 60 * 60 * 1000, Random(1), zone = zone
        )
        assertNotNull(t)
        assertTrue("t=$t 应≥now+48h", t!! >= now + 2 * 24L * 60 * 60 * 1000)
    }

    // ==================== 2026-10-25 新增：跨零点窗口 ====================

    @Test
    fun `跨零点窗口在深夜落点正确`() {
        val start = 22 * 60 // 22:00
        val end = 2 * 60    // 次日 02:00
        val now = ms(2026, 10, 18, 23, 0)
        repeat(20) { i ->
            val t = QqSchedulePlanner.nextTriggerAt(now, start, end, 0L, Random(i), zone = zone)
            assertNotNull(t)
            // 窗口 = 当日 22:00 → 次日 02:00
            val dayStart = todayStartMs(now)
            assertTrue("t=$t", t!! in now until dayStart + 26L * 60 * 60 * 1000)
            assertTrue(
                "t=$t 应在 22:00-02:00 区间",
                QqSchedulePlanner.isWithinWindow(t, start, end, zone)
            )
        }
    }

    @Test
    fun `跨零点窗口凌晨仍算在窗内（检查昨日开启的那一段）`() {
        val start = 22 * 60
        val end = 2 * 60
        val inside = ms(2026, 10, 19, 1, 30)
        val outside = ms(2026, 10, 19, 3, 0)
        assertTrue(QqSchedulePlanner.isWithinWindow(inside, start, end, zone))
        assertTrue(!QqSchedulePlanner.isWithinWindow(outside, start, end, zone))
    }

    @Test
    fun `跨零点窗口凌晨已过则顺延到当日夜晚`() {
        val start = 22 * 60
        val end = 2 * 60
        val now = ms(2026, 10, 19, 3, 0) // 凌晨段已过
        val t = QqSchedulePlanner.nextTriggerAt(now, start, end, 0L, Random(11), zone = zone)
        assertNotNull(t)
        val dayStart = todayStartMs(now)
        assertTrue("t=$t 应落在当日 22:00 之后", t!! >= dayStart + start * 60_000L)
    }

    // ==================== 2026-10-25 新增：窗口实例锚点（G-r2-02） ====================

    @Test
    fun `跨零点窗口在午夜前后是同一锚点（重试预算不会被午夜清零）`() {
        val start = 22 * 60
        val end = 2 * 60
        val beforeMidnight = ms(2026, 10, 18, 23, 30)
        val afterMidnight = ms(2026, 10, 19, 0, 30)
        val a = QqSchedulePlanner.windowAnchor(beforeMidnight, start, end, zone)
        val b = QqSchedulePlanner.windowAnchor(afterMidnight, start, end, zone)
        assertEquals("跨零点窗口同一实例必须同一锚点", a, b)
        // 锚点应为该实例的 22:00
        assertEquals(ms(2026, 10, 18, 22, 0), a)
    }

    @Test
    fun `普通窗口锚点为窗口起点且次日换新锚点`() {
        val a = QqSchedulePlanner.windowAnchor(ms(2026, 10, 18, 21, 0), windowStart, windowEnd, zone)
        assertEquals(ms(2026, 10, 18, 20, 0), a)
        val b = QqSchedulePlanner.windowAnchor(ms(2026, 10, 19, 21, 0), windowStart, windowEnd, zone)
        assertEquals(ms(2026, 10, 19, 20, 0), b)
        assertTrue("不同日应换锚点", a != b)
    }

    @Test
    fun `窗外时刻锚点稳定指向下一个窗口起点`() {
        // 凌晨 3:00（窗口 20:00-23:00 已过）→ 应指向当日 20:00
        val a = QqSchedulePlanner.windowAnchor(ms(2026, 10, 19, 3, 0), windowStart, windowEnd, zone)
        assertEquals(ms(2026, 10, 19, 20, 0), a)
        // 中午 12:00 → 同样是当日 20:00
        val b = QqSchedulePlanner.windowAnchor(ms(2026, 10, 19, 12, 0), windowStart, windowEnd, zone)
        assertEquals(ms(2026, 10, 19, 20, 0), b)
    }

    @Test
    fun `窗口非法时锚点为 0（G-r3-05）`() {
        assertEquals(0L, QqSchedulePlanner.windowAnchor(ms(2026, 10, 19, 12, 0), 1200, 1200, zone))
        assertEquals(0L, QqSchedulePlanner.windowAnchor(ms(2026, 10, 19, 12, 0), -1, 600, zone))
    }

    @Test
    fun `下一窗口锚点为次日窗口起点（G-r3-03）`() {
        val anchor = QqSchedulePlanner.windowAnchor(ms(2026, 10, 19, 21, 0), windowStart, windowEnd, zone)
        assertEquals(ms(2026, 10, 19, 20, 0), anchor)
        val next = QqSchedulePlanner.nextWindowAnchor(anchor, zone)
        assertEquals(ms(2026, 10, 20, 20, 0), next)
        // 跨零点窗口：锚点 22:00 → 次日晚 22:00
        val crossAnchor = QqSchedulePlanner.windowAnchor(ms(2026, 10, 19, 23, 30), 1320, 120, zone)
        assertEquals(ms(2026, 10, 19, 22, 0), crossAnchor)
        assertEquals(ms(2026, 10, 20, 22, 0), QqSchedulePlanner.nextWindowAnchor(crossAnchor, zone))
    }

    @Test
    fun `下一窗口锚点对 0 或非法输入返回 0`() {
        assertEquals(0L, QqSchedulePlanner.nextWindowAnchor(0L, zone))
        assertEquals(0L, QqSchedulePlanner.nextWindowAnchor(-5L, zone))
    }

    @Test
    fun `负最小间隔按0处理`() {
        val now = ms(2026, 10, 18, 19, 0)
        val t = QqSchedulePlanner.nextTriggerAt(now, windowStart, windowEnd, -12_345L, Random(3), zone = zone)
        assertNotNull(t)
        val dayStart = todayStartMs(now)
        assertTrue("t=$t", t!! in dayStart + windowStart * 60_000L until dayStart + windowEnd * 60_000L)
    }

    @Test
    fun `now恰在窗止时顺延到明日`() {
        val now = ms(2026, 10, 18, 23, 0) // 窗止（不含）边界
        val t = QqSchedulePlanner.nextTriggerAt(now, windowStart, windowEnd, 0L, Random(9), zone = zone)
        assertNotNull(t)
        val dayStart = todayStartMs(now)
        val tomorrowStart = dayStart + 24L * 60 * 60 * 1000 + windowStart * 60_000L
        val tomorrowEnd = dayStart + 24L * 60 * 60 * 1000 + windowEnd * 60_000L
        assertTrue("t=$t", t!! in tomorrowStart until tomorrowEnd)
    }

    @Test
    fun `全天窗口0到1440为合法窗`() {
        val now = ms(2026, 10, 18, 12, 0)
        val t = QqSchedulePlanner.nextTriggerAt(now, 0, QqConfig.MINUTES_PER_DAY, 0L, Random(5), zone = zone)
        assertNotNull(t)
        val dayStart = todayStartMs(now)
        assertTrue("t=$t", t!! in now until dayStart + QqConfig.MINUTES_PER_DAY * 60_000L)
    }

    @Test
    fun `单分钟窗口1439到1440合法且落在窗内`() {
        val now = ms(2026, 10, 18, 0, 0)
        val t = QqSchedulePlanner.nextTriggerAt(now, 1439, 1440, 0L, Random(5), zone = zone)
        assertNotNull(t)
        val dayStart = todayStartMs(now)
        assertTrue("t=$t", t!! in dayStart + 1439 * 60_000L until dayStart + 1440 * 60_000L)
    }

    @Test
    fun `Int极端窗值一律返回null`() {
        val now = ms(2026, 10, 18, 12, 0)
        assertNull(QqSchedulePlanner.nextTriggerAt(now, Int.MAX_VALUE, windowEnd, 0L, Random(1), zone = zone))
        assertNull(QqSchedulePlanner.nextTriggerAt(now, windowStart, Int.MAX_VALUE, 0L, Random(1), zone = zone))
        assertNull(QqSchedulePlanner.nextTriggerAt(now, Int.MAX_VALUE, Int.MAX_VALUE, 0L, Random(1), zone = zone))
        assertNull(QqSchedulePlanner.nextTriggerAt(now, Int.MIN_VALUE, windowEnd, 0L, Random(1), zone = zone))
    }

    @Test
    fun `窗口边界含起不含止`() {
        val start = ms(2026, 10, 18, 20, 0)
        val end = ms(2026, 10, 18, 23, 0)
        assertTrue(QqSchedulePlanner.isWithinWindow(start, windowStart, windowEnd, zone))
        assertTrue(QqSchedulePlanner.isWithinWindow(end - 1, windowStart, windowEnd, zone))
        assertTrue(!QqSchedulePlanner.isWithinWindow(end, windowStart, windowEnd, zone))
        assertTrue(!QqSchedulePlanner.isWithinWindow(start - 1, windowStart, windowEnd, zone))
    }
}
