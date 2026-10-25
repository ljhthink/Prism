package io.prism.qq

import io.prism.security.FakePreferenceDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqRuntimeStateRepository] 幂等 ID 集合持久化测试（guardrail M-01，D-03）。
 *
 * 覆盖：A→B→A 判重集合、超过上限淘汰最旧、空白 ID 清键语义。
 */
class QqRuntimeStateRepositoryTest {

    @Test
    fun `A-B-重复A 的 ID 集合持久化后可判重`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())

        repo.setLastRepliedMessageId("A")
        repo.setLastRepliedMessageId("B")

        val state = repo.get()
        assertEquals("最后一条应为 B", "B", state.lastRepliedMessageId)
        assertEquals(listOf("A", "B"), state.repliedMessageIds)
        // 重复 A 必须命中去重集合
        assertTrue(
            QqReplyDedup.isDuplicate(
                messageId = "A",
                lastMessageId = state.lastRepliedMessageId,
                hash = 0, lastHash = 0, lastRepliedAtMs = 0, nowMs = 0,
                repliedMessageIds = state.repliedMessageIds
            )
        )
    }

    @Test
    fun `超过上限时淘汰最旧 ID`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        val total = QqRuntimeState.MAX_REPLIED_IDS + 1
        repeat(total) { i -> repo.setLastRepliedMessageId("id-$i") }

        val ids = repo.get().repliedMessageIds
        assertEquals("集合应钳制在上限", QqRuntimeState.MAX_REPLIED_IDS, ids.size)
        assertTrue("最旧的 id-0 应被淘汰", "id-0" !in ids)
        assertEquals("最新的应保留", "id-${total - 1}", ids.last())
    }

    @Test
    fun `空白 ID 清除最后一条但保留集合（语义对称）`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        repo.setLastRepliedMessageId("A")

        repo.setLastRepliedMessageId("  ")

        val state = repo.get()
        assertEquals("最后一条应被清除", "", state.lastRepliedMessageId)
        assertEquals("集合不应被空白值污染", listOf("A"), state.repliedMessageIds)
    }

    // ==================== 未确认送达的记账（guardrail G-r3-01 / G-r4-02） ====================

    @Test
    fun `主动路径未确认记账推进冷却与计数但不清失败连击`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        val today = "2026-10-25"
        val now = 1_000_000L
        // 先造 2 次失败（未达阈值 3，不熔断）
        repo.recordFailure(QqThrottle.FAILURE_STREAK_THRESHOLD)
        repo.recordFailure(QqThrottle.FAILURE_STREAK_THRESHOLD)
        assertEquals(2, repo.get().failureStreak)

        repo.recordUnconfirmedFired(now, today)

        val s = repo.get()
        assertEquals("冷却基准必须前进（否则可在最小间隔内连发）", now, s.lastFiredAt)
        assertEquals("当日总数 +1（dailyCap 生效）", 1, s.firedCount)
        assertEquals("当日定时数 +1（maxPerDay 生效）", 1, s.scheduledFiredCount)
        assertEquals("日期键必须写入", today, s.firedDate)
        assertEquals("失败连击**不得**被清零（否则假阴性会洗白熔断）", 2, s.failureStreak)
        assertEquals("熔断态不得被改变", false, s.circuitOpen)
    }

    @Test
    fun `主动路径未确认后 maxPerDay=1 时下一轮必须被拒（配额生效）`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        val now = 1_000_000L
        val today = QqThrottle.todayKey(now, java.time.ZoneId.systemDefault())
        repo.recordUnconfirmedFired(now, today)

        val cfg = QqConfig(maxPerDay = 1, minIntervalMs = 60_000L, dailyCap = 100)
        val decision = QqThrottle.decide(cfg, repo.get(), now + 120_000L)
        assertTrue(
            "未确认已消耗当日定时配额 → 必须 Deny：$decision",
            decision is QqThrottle.Decision.Deny
        )
        assertTrue(
            "拒绝原因应为当日上限：${(decision as QqThrottle.Decision.Deny).reason}",
            decision.reason.contains("上限")
        )
    }

    @Test
    fun `主动路径未确认后冷却期内仍被拒绝（minInterval 生效）`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        val now = 2_000_000L
        val today = QqThrottle.todayKey(now, java.time.ZoneId.systemDefault())
        repo.recordUnconfirmedFired(now, today)

        val cfg = QqConfig(maxPerDay = 5, minIntervalMs = 30 * 60_000L, dailyCap = 100)
        val decision = QqThrottle.decide(cfg, repo.get(), now + 60_000L)
        assertTrue("未达最小间隔必须 Deny", decision is QqThrottle.Decision.Deny)
        assertTrue(
            (decision as QqThrottle.Decision.Deny).reason.contains("最小间隔")
        )
    }

    // ==================== 被动路径未确认：不占主动消息配额（guardrail G-r5-01） ====================

    @Test
    fun `被动未确认不消耗 maxPerDay 配额（G-r5-01）`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        val now = 3_000_000L
        val today = QqThrottle.todayKey(now, java.time.ZoneId.systemDefault())
        // 先造 1 次失败，验证 streak 不受影响
        repo.recordFailure(QqThrottle.FAILURE_STREAK_THRESHOLD)

        repo.recordUnconfirmedReplied(now, today)

        val s = repo.get()
        assertEquals("最小间隔基准必须前进", now, s.lastFiredAt)
        assertEquals("当日总数 +1（dailyCap 生效）", 1, s.firedCount)
        assertEquals(
            "被动回复**不得**占用定时主动消息配额（M-06 不变量）",
            0, s.scheduledFiredCount
        )
        assertEquals(1, s.failureStreak)
        assertEquals(false, s.circuitOpen)
    }

    @Test
    fun `被动未确认后 maxPerDay=1 的定时消息仍允许（G-r5-01 核心）`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        val now = 4_000_000L
        val today = QqThrottle.todayKey(now, java.time.ZoneId.systemDefault())
        repo.recordUnconfirmedReplied(now, today)

        // 冷却已过（minInterval=1 分钟，取 now+5 分钟）
        val cfg = QqConfig(maxPerDay = 1, minIntervalMs = 60_000L, dailyCap = 100)
        val decision = QqThrottle.decide(cfg, repo.get(), now + 5 * 60_000L)
        assertTrue(
            "被动未确认不得让定时主动消息被判『已达上限』：$decision",
            decision is QqThrottle.Decision.Allow
        )
    }

    @Test
    fun `被动未确认后同一消息下一轮被判重（BR-testing-013）`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        val now = 5_000_000L
        val today = QqThrottle.todayKey(now, java.time.ZoneId.systemDefault())
        val hash = "你好".hashCode().toString()
        repo.recordUnconfirmedReplied(now, today)
        repo.setLastReplied(hash, now)
        repo.setLastRepliedMessageId("msg-1")

        val s = repo.get()
        assertTrue(
            "未确认也必须标记已回复，否则会重复回复同一条（US-3111 AC-3）",
            QqReplyDedup.isDuplicate(
                messageId = "msg-1",
                lastMessageId = s.lastRepliedMessageId,
                hash = "你好".hashCode(),
                lastHash = s.lastRepliedMessageHash.toIntOrNull() ?: 0,
                lastRepliedAtMs = s.lastRepliedAtMs,
                nowMs = now + 60_000L,
                repliedMessageIds = s.repliedMessageIds
            )
        )
    }

    @Test
    fun `重试预算按窗口锚点持久化且跨重启不重置`() = runBlocking {
        // 同一个 DataStore 由两个仓库实例共享 → 模拟"服务重启后重新读状态"
        val store = FakePreferenceDataStore()
        val anchor = 1_700_000_000_000L
        val first = QqRuntimeStateRepository(store)
        repeat(3) { first.recordRetry(anchor) }

        val afterRestart = QqRuntimeStateRepository(store)
        val state = afterRestart.get()
        assertEquals("锚点必须持久化", anchor, state.retryWindowAnchor)
        assertEquals("计数必须持久化（跨重启不重置）", 3, state.retryCount)
        // 换窗口锚点 → 计数归零
        assertEquals(1, afterRestart.recordRetry(anchor + 86_400_000L))
        assertEquals(1, afterRestart.get().retryCount)
    }

    @Test
    fun `resetRetry 清空预算（成功后从满额开始）`() = runBlocking {
        val repo = QqRuntimeStateRepository(FakePreferenceDataStore())
        repo.recordRetry(123L)
        repo.recordRetry(123L)
        repo.resetRetry()
        val s = repo.get()
        assertEquals(0, s.retryCount)
        assertEquals(0L, s.retryWindowAnchor)
    }
}
