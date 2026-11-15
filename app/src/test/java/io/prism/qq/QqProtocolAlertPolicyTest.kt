package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqProtocolAlertPolicy] 边界测试（M2；US-3128；AC-1~AC-8）。
 *
 * ## 为什么阈值在测试里写**字面量**而不是引用常量
 *
 * 交接文档 B4 陷阱 4 记录过一次真实事故：断言两侧都引用同一个常量时，
 * 把常量从 3 改成 1 测试**依然全绿**——等于没测。
 * 因此本文件所有阈值（10 分钟 / 3 次 / 30 分钟）一律写成字面量，
 * 并用 [QqProtocolAlertPolicyTest] 开头的「契约锁定」用例把常量与字面量钉在一起：
 * 改常量必须同时改这里，否则立刻红。
 */
class QqProtocolAlertPolicyTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    /** 契约锁定：常量一旦被改动，本文件其它用例的"字面量"语义立刻失效，必须先在这里暴露。 */
    @Test
    fun `契约锁定 阈值常量与产品约定一致`() {
        assertEquals("AC-1 稳定在线门槛必须是 10 分钟", 600_000L, QqProtocolAlertPolicy.STABLE_ONLINE_MS)
        assertEquals("AC-2 不可达确认次数必须是 3", 3, QqProtocolAlertPolicy.CONFIRM_UNREACHABLE)
        assertEquals("账号离线确认次数必须是 2（确定态，比不可达快一档）", 2, QqProtocolAlertPolicy.CONFIRM_QQ_OFFLINE)
        assertEquals("AC-3 节流窗口必须是 30 分钟", 1_800_000L, QqProtocolAlertPolicy.NOTIFY_THROTTLE_MS)
    }

    private fun online(now: Long, prev: QqProtocolAlertPolicy.State, enabled: Boolean = true) =
        QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_ONLINE,
            nowMs = now,
            enabled = enabled,
            loopback = true,
            prev = prev
        )

    private fun unreachable(
        now: Long,
        prev: QqProtocolAlertPolicy.State,
        loopback: Boolean = true,
        enabled: Boolean = true
    ) = QqProtocolAlertPolicy.decide(
        probe = QqProtocolAlertPolicy.PROBE_UNREACHABLE,
        nowMs = now,
        enabled = enabled,
        loopback = loopback,
        prev = prev
    )

    private fun qqOffline(now: Long, prev: QqProtocolAlertPolicy.State) =
        QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_QQ_OFFLINE,
            nowMs = now,
            enabled = true,
            loopback = true,
            prev = prev
        )

    /** 推进到「已稳定在线 10 分钟且已武装」的状态（字面量 10 分钟）。 */
    private fun armedAt(start: Long): QqProtocolAlertPolicy.State {
        val s1 = online(start, QqProtocolAlertPolicy.State()).state
        return online(start + 10 * minute, s1).state
    }

    // ==================== AC-1：稳定在线门槛 ====================

    @Test
    fun `AC1 在线 9 分 59 秒尚未武装`() {
        val s = online(t0, QqProtocolAlertPolicy.State()).state
        val s2 = online(t0 + 9 * minute + 59_000L, s).state
        assertEquals("差 1 秒不应武装", 0L, s2.armedAtMs)
        assertTrue(!s2.armed)
    }

    @Test
    fun `AC1 恰好满 10 分钟即武装`() {
        val s = online(t0, QqProtocolAlertPolicy.State()).state
        val s2 = online(t0 + 10 * minute, s).state
        assertEquals("武装时刻应为 onlineSince + 10min", t0 + 10 * minute, s2.armedAtMs)
        assertTrue(s2.armed)
    }

    @Test
    fun `AC1 未武装前的短时连续失败不告警`() {
        var s = online(t0, QqProtocolAlertPolicy.State()).state
        var action: QqProtocolAlertPolicy.Action = QqProtocolAlertPolicy.Action.None
        repeat(5) { i ->
            val d = unreachable(t0 + (i + 1) * minute, s)
            s = d.state
            action = d.action
        }
        assertEquals("从未稳定在线过且只失败 5 次 → 不告警", QqProtocolAlertPolicy.Action.None, action)
    }

    // ==================== 2026-11-02：从未连上过 ====================

    @Test
    fun `从未连上过 连续 29 次不告警 第 30 次告警`() {
        var s = QqProtocolAlertPolicy.State()
        var action: QqProtocolAlertPolicy.Action = QqProtocolAlertPolicy.Action.None
        repeat(29) { i ->
            val d = unreachable(t0 + i * minute, s)
            s = d.state
            action = d.action
        }
        assertEquals("29 次不应告警", QqProtocolAlertPolicy.Action.None, action)
        val d = unreachable(t0 + 30 * minute, s)
        assertTrue(
            "连续 30 次从未连上必须告警（否则配置失败会永远沉默）",
            d.action is QqProtocolAlertPolicy.Action.NotifyDown
        )
        assertEquals(
            QqProtocolAlertPolicy.Kind.PROTOCOL_NEVER_STARTED,
            (d.action as QqProtocolAlertPolicy.Action.NotifyDown).kind
        )
    }

    /**
     * 2026-11-15（ADR-073）：兜底判据**只在抖动时**启用；稳态持续不可达仍走原 30 次阈值。
     * 这条锁死"不得因为加了兜底而把'等半小时'的语义改成'等 15 分钟'"。
     */
    @Test
    fun `稳态不可达仍按 30 次阈值而不是兜底阈值`() {
        var s = QqProtocolAlertPolicy.State()
        var notifiedAt = -1
        repeat(30) { i ->
            val d = unreachable(t0 + i * minute, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown && notifiedAt < 0) notifiedAt = i
        }
        assertEquals("稳态不可达必须等到第 30 次（t0+29min）", 29, notifiedAt)
    }

    @Test
    fun `从未连上过的告警只发一次`() {
        var s = QqProtocolAlertPolicy.State()
        var notifyCount = 0
        repeat(120) { i ->
            val d = unreachable(t0 + i * minute, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notifyCount++
        }
        assertEquals("长时间不可达也只能通知 1 次，不得刷屏", 1, notifyCount)
    }

    /**
     * 2026-11-15（ADR-073）**语义修订**：原用例断言"未武装时账号离线永 NOT 告警"，
     * 真机证明这条会变成沉默盲区——快速登录"成功"后 12 秒即离线、反复 3 次，
     * 用户只看到"有时回有时不回"却收不到任何提示。现在改为：
     * 未到兜底阈值（15 分钟）不打扰，超过即告警（且按 4h 节流只响一次）。
     */
    @Test
    fun `账号离线在未武装时超过兜底阈值才告警`() {
        var s = QqProtocolAlertPolicy.State()
        var notifiedAtMinute = -1
        repeat(60) { i ->
            val d = qqOffline(t0 + i * minute, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown && notifiedAtMinute < 0) {
                notifiedAtMinute = i
            }
        }
        assertTrue("未武装 + 账号长期离线必须告警（否则用户永远不知道要扫码）", notifiedAtMinute >= 0)
        assertTrue(
            "必须等够兜底阈值（15 分钟）再报，逐条抖动不得打扰。实际第 $notifiedAtMinute 分钟",
            notifiedAtMinute >= (QqProtocolAlertPolicy.DOWN_SINCE_FALLBACK_MS / minute).toInt()
        )
    }

    @Test
    fun `曾经连上过之后长期不可达走掉线分支而不是从未连上`() {
        var s = armedAt(t0)
        var kind: QqProtocolAlertPolicy.Kind? = null
        repeat(3) { i ->
            val d = unreachable(t0 + 11 * minute + i * minute, s)
            s = d.state
            (d.action as? QqProtocolAlertPolicy.Action.NotifyDown)?.let { kind = it.kind }
        }
        assertEquals(QqProtocolAlertPolicy.Kind.LOCAL_PROTOCOL_NOT_RUNNING, kind)
    }

    @Test
    fun `AC1 重启后武装状态从持久化恢复`() {
        val armed = armedAt(t0)
        val down = unreachable(t0 + 11 * minute, armed).state
        // "重启"：状态由持久化带入（armedAtMs 仍在），继续不可达必须能走到告警
        var s = down
        var notified = false
        repeat(3) { i ->
            val d = unreachable(t0 + 12 * minute + i * minute, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified = true
        }
        assertTrue("武装状态必须跨重启保留（否则 10-31 那种场景永远静默）", notified)
    }

    // ==================== AC-2：连续 3 次才告警 ====================

    @Test
    fun `AC2 两次 UNREACHABLE 不告警`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        val d1 = unreachable(t, s); s = d1.state
        val d2 = unreachable(t + minute, s); s = d2.state
        assertEquals(QqProtocolAlertPolicy.Action.None, d1.action)
        assertEquals(QqProtocolAlertPolicy.Action.None, d2.action)
        assertEquals(2, s.unreachableStreak)
    }

    @Test
    fun `AC2 第三次 UNREACHABLE 告警`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        var action: QqProtocolAlertPolicy.Action = QqProtocolAlertPolicy.Action.None
        repeat(3) { i ->
            val d = unreachable(t + i * minute, s)
            s = d.state
            action = d.action
        }
        assertTrue("第三次应告警", action is QqProtocolAlertPolicy.Action.NotifyDown)
        assertEquals(
            QqProtocolAlertPolicy.Kind.LOCAL_PROTOCOL_NOT_RUNNING,
            (action as QqProtocolAlertPolicy.Action.NotifyDown).kind
        )
    }

    @Test
    fun `AC2 中断恢复会重置连续计数`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        s = unreachable(t, s).state
        s = unreachable(t + minute, s).state
        assertEquals(2, s.unreachableStreak)
        s = online(t + 2 * minute, s).state
        assertEquals("恢复在线应清零", 0, s.unreachableStreak)
        val d = unreachable(t + 3 * minute, s)
        assertEquals(QqProtocolAlertPolicy.Action.None, d.action)
    }

    // ==================== AC-3：同一轮只通知一次 + 30 分钟节流 ====================

    @Test
    fun `AC3 同一轮失联只通知一次`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        var notifyCount = 0
        repeat(10) { i ->
            val d = unreachable(t + i * minute, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notifyCount++
        }
        assertEquals("同一轮 10 次探测只能通知 1 次", 1, notifyCount)
    }

    @Test
    fun `AC3 恢复后再次失联且超过 30 分钟可再通知`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        var notifyCount = 0
        repeat(3) { i ->
            val d = unreachable(t + i * minute, s); s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notifyCount++
        }
        s = online(t + 5 * minute, s).state
        s = online(t + 5 * minute + 10 * minute, s).state
        val t2 = t + 30 * minute + 20 * minute
        repeat(3) { i ->
            val d = unreachable(t2 + i * minute, s); s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notifyCount++
        }
        assertEquals("跨轮且超节流窗口应可再次通知", 2, notifyCount)
    }

    @Test
    fun `AC3 29 分钟内换故障类型也不重复通知`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        repeat(3) { i ->
            s = unreachable(t + i * minute, s).state
        }
        assertTrue(s.downNotified)
        val d = qqOffline(t + 29 * minute, s)
        assertEquals("节流窗口内不得再发", QqProtocolAlertPolicy.Action.None, d.action)
    }

    // ==================== AC-4：恢复通知 ====================

    @Test
    fun `AC4 未通知过掉线时恢复不通知`() {
        val s = online(t0, QqProtocolAlertPolicy.State()).state
        assertEquals(QqProtocolAlertPolicy.Action.None, online(t0 + minute, s).action)
    }

    @Test
    fun `AC3 升级为账号离线时突破节流（M3 修复）`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        // 先因不可达通知
        repeat(3) { i ->
            s = unreachable(t + i * minute, s).state
        }
        assertTrue(s.downNotified)
        assertEquals(
            QqProtocolAlertPolicy.Kind.LOCAL_PROTOCOL_NOT_RUNNING.name,
            s.downKind
        )
        val downSinceBefore = s.downSinceMs
        // 5 分钟后变成账号离线（需扫码）——这是"信息更具体、动作更明确"的升级
        var d2: QqProtocolAlertPolicy.Decision? = null
        repeat(2) { i ->
            d2 = qqOffline(t + 5 * minute + i * minute, s)
            s = d2!!.state
        }
        val action = d2!!.action
        assertTrue(
            "账号离线是升级型故障，必须突破 30 分钟节流（否则用户在需要扫码时收不到指引）",
            action is QqProtocolAlertPolicy.Action.NotifyDown
        )
        assertEquals(
            QqProtocolAlertPolicy.Kind.QQ_ACCOUNT_OFFLINE,
            (action as QqProtocolAlertPolicy.Action.NotifyDown).kind
        )
        assertEquals(
            "换 kind 不得重置失联起点（否则恢复通知少报总时长）",
            downSinceBefore,
            s.downSinceMs
        )
    }

    @Test
    fun `AC3 非升级方向的跨类型切换仍受节流`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        // 先因账号离线通知
        repeat(2) { i ->
            s = qqOffline(t + i * minute, s).state
        }
        assertEquals(QqProtocolAlertPolicy.Kind.QQ_ACCOUNT_OFFLINE.name, s.downKind)
        // 5 分钟后变成不可达：不是升级方向，应被节流压住
        var action: QqProtocolAlertPolicy.Action = QqProtocolAlertPolicy.Action.None
        repeat(3) { i ->
            val d = unreachable(t + 5 * minute + i * minute, s)
            s = d.state
            action = d.action
        }
        assertEquals(QqProtocolAlertPolicy.Action.None, action)
    }

    @Test
    fun `AC4 通知过掉线后恢复发一条并带上持续时长`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        repeat(3) { i ->
            s = unreachable(t + i * minute, s).state
        }
        val downSince = s.downSinceMs
        val recoverAt = t + 10 * minute
        val d = online(recoverAt, s)
        val action = d.action
        assertTrue(action is QqProtocolAlertPolicy.Action.NotifyRecovered)
        assertEquals(
            QqProtocolAlertPolicy.Kind.LOCAL_PROTOCOL_NOT_RUNNING.name,
            (action as QqProtocolAlertPolicy.Action.NotifyRecovered).downKind
        )
        assertEquals(recoverAt - downSince, action.downDurationMs)
        assertTrue("恢复后应清掉 downNotified", !d.state.downNotified)
    }

    // ==================== AC-5/AC-6：可执行文案 + 本机/非本机归因 ====================

    @Test
    fun `AC6 回环不可达归因为本机协议端未运行`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        var kind: QqProtocolAlertPolicy.Kind? = null
        repeat(3) { i ->
            val d = unreachable(t + i * minute, s, loopback = true)
            s = d.state
            (d.action as? QqProtocolAlertPolicy.Action.NotifyDown)?.let { kind = it.kind }
        }
        assertEquals(QqProtocolAlertPolicy.Kind.LOCAL_PROTOCOL_NOT_RUNNING, kind)
    }

    @Test
    fun `AC6 非回环不可达归因为网络不可达`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        var kind: QqProtocolAlertPolicy.Kind? = null
        repeat(3) { i ->
            val d = unreachable(t + i * minute, s, loopback = false)
            s = d.state
            (d.action as? QqProtocolAlertPolicy.Action.NotifyDown)?.let { kind = it.kind }
        }
        assertEquals(QqProtocolAlertPolicy.Kind.PROTOCOL_UNREACHABLE, kind)
    }

    @Test
    fun `账号离线单独一种文案且两次即确认`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        var kind: QqProtocolAlertPolicy.Kind? = null
        repeat(2) { i ->
            val d = qqOffline(t + i * minute, s)
            s = d.state
            (d.action as? QqProtocolAlertPolicy.Action.NotifyDown)?.let { kind = it.kind }
        }
        assertEquals("两次 QQ_OFFLINE 即应告警（确定态）", QqProtocolAlertPolicy.Kind.QQ_ACCOUNT_OFFLINE, kind)
    }

    @Test
    fun `单次 QQ_OFFLINE 不告警`() {
        val armed = armedAt(t0)
        val d = qqOffline(t0 + 11 * minute, armed)
        assertEquals(QqProtocolAlertPolicy.Action.None, d.action)
    }

    // ==================== AC-8：总开关关闭不告警 ====================

    @Test
    fun `AC8 总开关关闭时即使已武装且连续失败也不告警`() {
        var s = armedAt(t0)
        var action: QqProtocolAlertPolicy.Action = QqProtocolAlertPolicy.Action.None
        repeat(10) { i ->
            val d = unreachable(t0 + 11 * minute + i * minute, s, enabled = false)
            s = d.state
            action = d.action
        }
        assertEquals(QqProtocolAlertPolicy.Action.None, action)
        assertEquals("关闭时应清空状态", QqProtocolAlertPolicy.State(), s)
    }

    @Test
    fun `AC8 关闭再打开不会补发旧通知`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        repeat(3) { i ->
            s = unreachable(t + i * minute, s).state
        }
        assertTrue(s.downNotified)
        s = QqProtocolAlertPolicy.decide(
            probe = QqProtocolAlertPolicy.PROBE_UNREACHABLE,
            nowMs = t + 5 * minute,
            enabled = false,
            loopback = true,
            prev = s
        ).state
        assertTrue(!s.downNotified)
        assertEquals(
            QqProtocolAlertPolicy.Action.None,
            unreachable(t + 6 * minute, s).action
        )
    }

    // ==================== 验收补充：时钟回拨 ====================

    @Test
    fun `验收 时钟回拨后仍能重新武装并告警`() {
        var s = armedAt(t0)
        // 系统时间被调回 1 小时
        val back = t0 - 60 * minute
        s = online(back, s).state
        assertTrue("武装状态是历史事实，不得因回拨丢失", s.armed)
        // 回拨后连续不可达：节流差值变负，必须仍能告警（否则长期静默）
        var notified = false
        repeat(3) { i ->
            val d = unreachable(back + (i + 1) * minute, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) notified = true
        }
        assertTrue("时钟回拨不得导致告警永久静默", notified)
    }

    @Test
    fun `验收 恢复通知不消耗掉线告警的节流额度`() {
        var s = armedAt(t0)
        val t = t0 + 11 * minute
        repeat(3) { i ->
            s = unreachable(t + i * minute, s).state
        }
        // 恢复一次
        s = online(t + 4 * minute, s).state
        assertTrue(!s.downNotified)
        // 5 分钟后（远小于 30 分钟）再次掉线：恢复通知不应把节流锚点推到"现在"
        repeat(3) { i ->
            val d = unreachable(t + 9 * minute + i * minute, s)
            s = d.state
            if (d.action is QqProtocolAlertPolicy.Action.NotifyDown) {
                // 命中了，说明恢复没有吃掉节流额度
                return
            }
        }
        // 未命中也可接受（节流语义保守），但不能是"因为恢复而静默"——
        // 这里断言节流锚点仍是第一次掉线通知的时刻
        assertEquals(
            "恢复通知不得改写 lastNotifyAtMs",
            t + 2 * minute,
            s.lastNotifyAtMs
        )
    }

    // ==================== 防御性 ====================

    @Test
    fun `未知探测字符串不改状态也不告警`() {
        val armed = armedAt(t0)
        val d = QqProtocolAlertPolicy.decide(
            probe = "WHATEVER",
            nowMs = t0 + 20 * minute,
            enabled = true,
            loopback = true,
            prev = armed
        )
        assertEquals(armed, d.state)
        assertEquals(QqProtocolAlertPolicy.Action.None, d.action)
    }
}
