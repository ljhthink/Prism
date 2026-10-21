package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [QqThrottle] 单元测试（ADR-063；PRD US-3105 AC-1/AC-3、US-3108 AC-4）。
 *
 * 纯 JVM（无 Android 依赖）：时钟与时区入参固定 → 结果确定可复现。
 */
class QqThrottleTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val cfg = QqConfig(
        enabled = true,
        riskAccepted = true,
        scheduleEnabled = true,
        minIntervalMs = 30L * 60_000L,
        dailyCap = 20,
        maxPerDay = 3
    )

    @Test
    fun `熔断中一律拒绝`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(circuitOpen = true)
        assertTrue(QqThrottle.decide(cfg, st, now, zone) is QqThrottle.Decision.Deny)
    }

    @Test
    fun `未达最小间隔则拒绝`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(lastFiredAt = now - 10 * 60_000L, firedDate = "2026-10-18")
        assertTrue(QqThrottle.decide(cfg, st, now, zone) is QqThrottle.Decision.Deny)
    }

    @Test
    fun `达到最小间隔后允许`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(lastFiredAt = now - 40 * 60_000L, firedDate = "2026-10-18")
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decide(cfg, st, now, zone))
    }

    @Test
    fun `当日总数达上限则拒绝`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(
            lastFiredAt = now - 60 * 60_000L,
            firedDate = "2026-10-18",
            firedCount = 20,
            scheduledFiredCount = 0
        )
        assertTrue(QqThrottle.decide(cfg, st, now, zone) is QqThrottle.Decision.Deny)
    }

    @Test
    fun `当日定时数达上限则拒绝`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(
            lastFiredAt = now - 60 * 60_000L,
            firedDate = "2026-10-18",
            firedCount = 5,
            scheduledFiredCount = 3
        )
        assertTrue(QqThrottle.decide(cfg, st, now, zone) is QqThrottle.Decision.Deny)
    }

    @Test
    fun `跨日后计数清零不触发上限`() {
        val now = at(2026, 10, 19, 21, 0) // 次日
        val st = QqRuntimeState(
            lastFiredAt = now - 60 * 60_000L,
            firedDate = "2026-10-18", // 昨日
            firedCount = 20,
            scheduledFiredCount = 3
        )
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decide(cfg, st, now, zone))
    }

    @Test
    fun `日期键格式为 yyyy-MM-dd`() {
        assertEquals("2026-10-18", QqThrottle.todayKey(at(2026, 10, 18, 21, 0), zone))
    }

    // ==================== ac-verifier 补充：边界 / 阈值 / 顺序用例 ====================

    @Test
    fun `从未发送lastFiredAt为0不触发最小间隔拒绝`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState() // lastFiredAt=0、firedDate=""、计数 0
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decide(cfg, st, now, zone))
    }

    @Test
    fun `恰好达到最小间隔边界即允许`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(lastFiredAt = now - cfg.minIntervalMs, firedDate = "2026-10-18")
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decide(cfg, st, now, zone))
    }

    @Test
    fun `差1毫秒未达最小间隔即拒绝`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(lastFiredAt = now - cfg.minIntervalMs + 1, firedDate = "2026-10-18")
        assertTrue(QqThrottle.decide(cfg, st, now, zone) is QqThrottle.Decision.Deny)
    }

    @Test
    fun `当日总数恰达上限即拒绝`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(
            lastFiredAt = now - 60 * 60_000L,
            firedDate = "2026-10-18",
            firedCount = cfg.dailyCap,
            scheduledFiredCount = 0
        )
        assertTrue(QqThrottle.decide(cfg, st, now, zone) is QqThrottle.Decision.Deny)
    }

    @Test
    fun `当日总数差1不触发上限`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(
            lastFiredAt = now - 60 * 60_000L,
            firedDate = "2026-10-18",
            firedCount = cfg.dailyCap - 1,
            scheduledFiredCount = 0
        )
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decide(cfg, st, now, zone))
    }

    @Test
    fun `当日定时数差1不触发上限`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(
            lastFiredAt = now - 60 * 60_000L,
            firedDate = "2026-10-18",
            firedCount = 1,
            scheduledFiredCount = cfg.maxPerDay - 1
        )
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decide(cfg, st, now, zone))
    }

    @Test
    fun `熔断优先于其他判定（决策顺序断言）`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(
            lastFiredAt = now - 10 * 60_000L,
            firedDate = "2026-10-18",
            firedCount = 999,
            scheduledFiredCount = 999,
            failureStreak = 3,
            circuitOpen = true
        )
        val d = QqThrottle.decide(cfg, st, now, zone)
        assertTrue(d is QqThrottle.Decision.Deny)
        assertTrue("应命中熔断分支", (d as QqThrottle.Decision.Deny).reason.contains("熔断"))
    }

    @Test
    fun `空日期键视为跨日不触发计数上限`() {
        val now = at(2026, 10, 18, 21, 0)
        val st = QqRuntimeState(
            lastFiredAt = now - 60 * 60_000L,
            firedDate = "",
            firedCount = 999,
            scheduledFiredCount = 999
        )
        assertEquals(QqThrottle.Decision.Allow, QqThrottle.decide(cfg, st, now, zone))
    }
}
