package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqConfig] 配置闸门与钳制单元测试（PRD US-3101 AC-1/AC-2；ac-verifier 补充。
 * 此前 `schedulingActive` 三闸同开语义与 `clamped()` 边界无任何自动化覆盖）。
 *
 * 纯 JVM（无 Android 依赖）。
 */
class QqConfigTest {

    @Test
    fun `默认配置全关`() {
        val c = QqConfig.DEFAULT
        assertFalse(c.enabled)
        assertFalse(c.riskAccepted)
        assertFalse(c.scheduleEnabled)
        assertFalse(c.schedulingActive)
    }

    @Test
    fun `定时链路激活为四闸同开（三闸决策表8组合 + 目标联系人）`() {
        val combos = listOf(
            Triple(false, false, false),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
            Triple(true, true, false),
            Triple(true, false, true),
            Triple(false, true, true),
            Triple(true, true, true)
        )
        for ((enabled, risk, schedule) in combos) {
            val c = QqConfig(
                enabled = enabled,
                riskAccepted = risk,
                scheduleEnabled = schedule,
                targetContact = "小主"
            )
            val expected = enabled && risk && schedule
            assertEquals("enabled=$enabled risk=$risk schedule=$schedule", expected, c.schedulingActive)
        }
    }

    @Test
    fun `钳制越界最小间隔到值域`() {
        assertEquals(QqConfig.MIN_INTERVAL_MS_FLOOR, QqConfig(minIntervalMs = 0L).clamped().minIntervalMs)
        assertEquals(QqConfig.MIN_INTERVAL_MS_FLOOR, QqConfig(minIntervalMs = -1L).clamped().minIntervalMs)
        assertEquals(QqConfig.MAX_INTERVAL_MS_CEIL, QqConfig(minIntervalMs = Long.MAX_VALUE).clamped().minIntervalMs)
    }

    @Test
    fun `钳制越界每日上限与定时上限`() {
        assertEquals(QqConfig.MIN_DAILY_CAP, QqConfig(dailyCap = 0).clamped().dailyCap)
        assertEquals(QqConfig.MAX_DAILY_CAP, QqConfig(dailyCap = Int.MAX_VALUE).clamped().dailyCap)
        assertEquals(QqConfig.MIN_PER_DAY, QqConfig(maxPerDay = 0).clamped().maxPerDay)
        assertEquals(QqConfig.MAX_PER_DAY_CEIL, QqConfig(maxPerDay = Int.MAX_VALUE).clamped().maxPerDay)
    }

    @Test
    fun `钳制越界时间窗到当日分钟域`() {
        val c = QqConfig(windowStartMinute = -5, windowEndMinute = 5000).clamped()
        assertEquals(0, c.windowStartMinute)
        assertEquals(QqConfig.MINUTES_PER_DAY, c.windowEndMinute)
        val c2 = QqConfig(windowStartMinute = 5000, windowEndMinute = 0).clamped()
        assertEquals(QqConfig.MINUTES_PER_DAY - 1, c2.windowStartMinute)
        assertEquals(1, c2.windowEndMinute)
    }

    @Test
    fun `四闸同开时激活为真（含目标联系人，US-3108 AC-3）`() {
        assertTrue(
            QqConfig(
                enabled = true,
                riskAccepted = true,
                scheduleEnabled = true,
                targetContact = "小主"
            ).schedulingActive
        )
    }

    @Test
    fun `缺任一闸（含未配置目标联系人）时激活为假`() {
        assertTrue(
            !QqConfig(enabled = true, riskAccepted = true, scheduleEnabled = true).schedulingActive
        ) // targetContact 空白 → 不激活
        assertTrue(
            !QqConfig(enabled = false, riskAccepted = true, scheduleEnabled = true, targetContact = "小主").schedulingActive
        )
        assertTrue(
            !QqConfig(enabled = true, riskAccepted = false, scheduleEnabled = true, targetContact = "小主").schedulingActive
        )
        assertTrue(
            !QqConfig(enabled = true, riskAccepted = true, scheduleEnabled = false, targetContact = "小主").schedulingActive
        )
    }

    @Test
    fun `双向对话默认关闭且激活为四闸同开（US-3109）`() {
        assertFalse("默认必须关闭双向对话", QqConfig.DEFAULT.autoReplyEnabled)
        assertFalse(QqConfig.DEFAULT.autoReplyActive)
        for (ar in listOf(false, true)) {
            val c = QqConfig(enabled = true, riskAccepted = true, autoReplyEnabled = ar, targetContact = "小主")
            assertEquals("autoReply=$ar", ar, c.autoReplyActive)
        }
        assertTrue(
            !QqConfig(enabled = false, riskAccepted = true, autoReplyEnabled = true, targetContact = "小主").autoReplyActive
        )
        assertTrue(
            !QqConfig(enabled = true, riskAccepted = false, autoReplyEnabled = true, targetContact = "小主").autoReplyActive
        )
        assertTrue(
            !QqConfig(enabled = true, riskAccepted = true, autoReplyEnabled = true).autoReplyActive // 目标联系人空白
        )
    }

    @Test
    fun `协议端为默认主通道且历史条数默认 12（ADR-064）`() {
        assertEquals(
            io.prism.qq.channel.QqTransport.ONEBOT,
            QqConfig.DEFAULT.transport
        )
        assertEquals(QqConfig.DEFAULT_HISTORY_LIMIT, QqConfig.DEFAULT.historyLimit)
        assertEquals(12, QqConfig.DEFAULT_HISTORY_LIMIT)
        assertTrue(QqConfig.DEFAULT.memoryInjectionEnabled)
        assertEquals("", QqConfig.DEFAULT.oneBotToken)
    }

    @Test
    fun `历史条数与 QQ 号钳制到值域`() {
        assertEquals(
            QqConfig.MIN_HISTORY_LIMIT,
            QqConfig(historyLimit = 0).clamped().historyLimit
        )
        assertEquals(
            QqConfig.MAX_HISTORY_LIMIT,
            QqConfig(historyLimit = Int.MAX_VALUE).clamped().historyLimit
        )
        assertEquals(0L, QqConfig(oneBotSelfId = -1L).clamped().oneBotSelfId)
        assertEquals(0L, QqConfig(oneBotTargetUserId = -1L).clamped().oneBotTargetUserId)
    }

    @Test
    fun `autoReplyActive 全决策表（16 组合穷举，US-3109 AC-5 补盲）`() {
        // 主 Agent 既有用例仅对 autoReply 取 true/false 两值 + 单轴枚举；
        // 本用例对四闸做 2^4=16 穷举，锁定"四闸同开"契约（缺任一即不激活）。
        for (enabled in listOf(false, true)) {
            for (risk in listOf(false, true)) {
                for (ar in listOf(false, true)) {
                    for (contact in listOf("", "小主")) {
                        val c = QqConfig(
                            enabled = enabled,
                            riskAccepted = risk,
                            autoReplyEnabled = ar,
                            targetContact = contact
                        )
                        val expected = enabled && risk && ar && contact.isNotBlank()
                        assertEquals(
                            "enabled=$enabled risk=$risk autoReply=$ar contact='$contact'",
                            expected, c.autoReplyActive
                        )
                    }
                }
            }
        }
    }
}
