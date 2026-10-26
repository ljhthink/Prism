package io.prism.qq

import io.prism.qq.channel.QqTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqChannelResolver] 单元测试（ADR-064 修订 2026-10-25；PRD US-3109 AC-1）。
 *
 * 核心回归点：**首选协议端但未连接时，必须真的降级到无障碍**——这正是真机上
 * "自动回复永不触发"的根因（旧实现按配置硬判，直接跳过轮询）。
 */
class QqChannelResolverTest {

    @Test
    fun `首选协议端且已连接时使用协议端事件`() {
        val r = QqChannelResolver.resolve(QqTransport.ONEBOT, oneBotConnected = true, accessibilityAvailable = true)
        assertEquals(QqChannelResolver.Mode.ONEBOT, r.mode)
        assertTrue(QqChannelResolver.isEventDriven(r.mode))
    }

    @Test
    fun `首选协议端未连接且无障碍可用时降级到无障碍（核心回归）`() {
        val r = QqChannelResolver.resolve(
            QqTransport.ONEBOT, oneBotConnected = false, accessibilityAvailable = true
        )
        assertEquals(QqChannelResolver.Mode.ACCESSIBILITY, r.mode)
        assertFalse(QqChannelResolver.isEventDriven(r.mode))
        assertTrue(r.reason.contains("降级"))
    }

    @Test
    fun `首选协议端未连接且无障碍不可用时为 NONE`() {
        val r = QqChannelResolver.resolve(
            QqTransport.ONEBOT, oneBotConnected = false, accessibilityAvailable = false
        )
        assertEquals(QqChannelResolver.Mode.NONE, r.mode)
        assertFalse(QqChannelResolver.isEventDriven(r.mode))
    }

    @Test
    fun `首选无障碍可用时不使用协议端（即便协议端在线）`() {
        val r = QqChannelResolver.resolve(
            QqTransport.ACCESSIBILITY, oneBotConnected = true, accessibilityAvailable = true
        )
        assertEquals(QqChannelResolver.Mode.ACCESSIBILITY, r.mode)
    }

    @Test
    fun `首选无障碍不可用时改用协议端`() {
        val r = QqChannelResolver.resolve(
            QqTransport.ACCESSIBILITY, oneBotConnected = true, accessibilityAvailable = false
        )
        assertEquals(QqChannelResolver.Mode.ONEBOT, r.mode)
        assertTrue(QqChannelResolver.isEventDriven(r.mode))
    }

    @Test
    fun `两条通道都不可用时为 NONE`() {
        val r = QqChannelResolver.resolve(
            QqTransport.ACCESSIBILITY, oneBotConnected = false, accessibilityAvailable = false
        )
        assertEquals(QqChannelResolver.Mode.NONE, r.mode)
    }
}
