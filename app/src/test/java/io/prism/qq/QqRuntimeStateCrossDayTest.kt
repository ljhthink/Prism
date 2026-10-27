package io.prism.qq

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 跨天计数自愈（2026-10-27 RC-4/RC-5 回归锁）。
 *
 * 真机证据：`fired_date=2026-10-27, fired_count=1, scheduled_count=4, max_per_day=3`
 * → `last_outcome=SKIP:THROTTLE 已达定时主动消息当日上限（3 次）`。
 * `scheduled(4) > fired(1)` 只可能来自昨天：旧 `recordReplied` 只改日期不重置定时数。
 */
class QqRuntimeStateCrossDayTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private var store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>? = null

    private fun repo(name: String = "t"): QqRuntimeStateRepository {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) {
            folder.newFile("$name.preferences_pb")
        }
        store = ds
        return QqRuntimeStateRepository(ds)
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** 跨天后**先发生被动回复**，不得让昨天的定时数继续生效。 */
    @Test
    fun `被动回复跨天后定时计数必须清零`() = runBlocking {
        val r = repo()
        val day1 = "2026-10-26"
        val day2 = "2026-10-27"
        repeat(4) { r.recordFired(1000L + it, day1) }
        assertEquals(4, r.get().scheduledFiredCount)

        // 跨天后的被动回复：旧实现在这里只改日期，定时数留在 4 → 当天定时全被拒
        r.recordReplied(2000L, day2)
        val state = r.get()
        assertEquals(day2, state.firedDate)
        assertEquals(1, state.firedCount)
        assertEquals("定时数与总数必须同时清零", 0, state.scheduledFiredCount)
    }

    @Test
    fun `未确认被动回复跨天同样清零`() = runBlocking {
        val r = repo("u")
        repeat(3) { r.recordFired(1000L + it, "2026-10-26") }
        r.recordUnconfirmedReplied(2000L, "2026-10-27")
        val s = r.get()
        assertEquals(1, s.firedCount)
        assertEquals(0, s.scheduledFiredCount)
    }

    @Test
    fun `同一天内被动回复不影响定时计数`() = runBlocking {
        val r = repo("v")
        val day = "2026-10-27"
        r.recordFired(1L, day)
        r.recordReplied(2L, day)
        r.recordReplied(3L, day)
        val s = r.get()
        assertEquals(3, s.firedCount)
        assertEquals("被动回复不占用定时主动消息配额", 1, s.scheduledFiredCount)
    }

    /** 脏数据自愈：升级后当天即可用，不必等次日。 */
    @Test
    fun `定时数大于总数时按总数收敛（复现真机脏数据 4 比 1）`() = runBlocking {
        val r = repo("w")
        val day = "2026-10-27"
        // 直接构造真机那组脏数据：fired=1 / scheduled=4（旧版本跨天只重置总数导致）
        store!!.edit { prefs ->
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("qq_fired_date")] = day
            prefs[androidx.datastore.preferences.core.intPreferencesKey("qq_fired_count")] = 1
            prefs[androidx.datastore.preferences.core.intPreferencesKey("qq_scheduled_count")] = 4
        }
        val s = r.get()
        assertEquals("总数保持真实值", 1, s.firedCount)
        assertEquals("定时数必须收敛到总数以内", 1, s.scheduledFiredCount)
        // 收敛后：maxPerDay=3 时定时消息应当恢复可用
        val cfg = QqConfig(maxPerDay = 3, dailyCap = 200, minIntervalMs = 60_000L)
        assertEquals(
            QqThrottle.Decision.Allow,
            QqThrottle.decide(cfg, s, 2000L + 10 * 60_000L)
        )
    }

    @Test
    fun `脏数据不影响正常收敛后的不变量`() = runBlocking {
        val r = repo("y")
        val day = "2026-10-27"
        store!!.edit { prefs ->
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("qq_fired_date")] = day
            prefs[androidx.datastore.preferences.core.intPreferencesKey("qq_fired_count")] = 0
            prefs[androidx.datastore.preferences.core.intPreferencesKey("qq_scheduled_count")] = 9
        }
        val s = r.get()
        assertEquals(0, s.scheduledFiredCount)
    }

    @Test
    fun `跨天后定时消息不再被误判为当日超限`() = runBlocking {
        val r = repo("x")
        val day1 = "2026-10-26"
        val day2 = "2026-10-27"
        val cfg = QqConfig(maxPerDay = 3, dailyCap = 200, minIntervalMs = 60_000L)
        repeat(3) { r.recordFired(1000L + it, day1) }
        r.recordReplied(2000L, day2) // 跨天后先被动回复
        val state = r.get()
        val decision = QqThrottle.decide(cfg, state, 2000L + 10 * 60_000L)
        assertEquals(
            "跨天后必须允许定时主动消息",
            QqThrottle.Decision.Allow,
            decision
        )
    }
}
