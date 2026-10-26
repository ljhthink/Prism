package io.prism.qq

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道能力驱动的**被动回复端到端判定**（ADR-064 修订；guardrail G-01 回归锁）。
 *
 * 背景：真机上「协议端未配置 + 无障碍可用」时自动回复永久静默。根因是两处都按
 * `cfg.transport` 硬判：`replyLoop` 跳过轮询（已修）+ `autoReplyOnce` 返回"等待协议端事件"
 * （guardrail 复审发现仍残留）。本测试锁定**后半段的判据**：只有"当前实际生效的通道"
 * 才是协议端时，才允许以"等待事件"为由跳过轮询。
 *
 * 说明：`autoReplyOnce` 是 `PrismApplication` 内的局部函数，JVM 侧不可直接构造；
 * 因此把它的**判据**抽成 [shouldSkipForEventDriven]，并断言与真实实现同一规则。
 */
class QqAutoReplyDispatchTest {

    @Test
    fun `协议端未配置但无障碍可用时不得跳过轮询（G-01 核心回归）`() {
        val resolution = QqChannelResolver.resolve(
            preferred = io.prism.qq.channel.QqTransport.ONEBOT,
            oneBotConnected = false,
            accessibilityAvailable = true
        )
        assertEquals(QqChannelResolver.Mode.ACCESSIBILITY, resolution.mode)
        assertFalse(
            "实际生效通道是无障碍时，必须允许读会话并回复",
            QqAutoReplyDispatch.shouldSkipForEventDriven(resolution)
        )
    }

    @Test
    fun `协议端已连接时轮询路径让位给事件`() {
        val resolution = QqChannelResolver.resolve(
            preferred = io.prism.qq.channel.QqTransport.ONEBOT,
            oneBotConnected = true,
            accessibilityAvailable = true
        )
        assertTrue(QqAutoReplyDispatch.shouldSkipForEventDriven(resolution))
    }

    @Test
    fun `两条通道都不可用时跳过（不空转、不建屏）`() {
        val resolution = QqChannelResolver.resolve(
            preferred = io.prism.qq.channel.QqTransport.ONEBOT,
            oneBotConnected = false,
            accessibilityAvailable = false
        )
        assertTrue(QqAutoReplyDispatch.shouldSkipForEventDriven(resolution))
    }

    @Test
    fun `首选无障碍且可用时不依赖协议端`() {
        val resolution = QqChannelResolver.resolve(
            preferred = io.prism.qq.channel.QqTransport.ACCESSIBILITY,
            oneBotConnected = false,
            accessibilityAvailable = true
        )
        assertFalse(QqAutoReplyDispatch.shouldSkipForEventDriven(resolution))
    }

    @Test
    fun `事件回调必须保留 override 文案与空 override 的语义差异`() = runBlocking {
        // 事件路径总是携带消息（override != null）；轮询路径为 null。
        // 这里用纯函数表达该契约，防止后续重构把两条路径混为一谈。
        assertTrue(QqAutoReplyDispatch.isEventPayload(QqChannelResolver.Mode.ONEBOT))
        assertFalse(QqAutoReplyDispatch.isEventPayload(QqChannelResolver.Mode.ACCESSIBILITY))
    }
}
