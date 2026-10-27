package io.prism.qq

import io.prism.qq.channel.QqChannel
import io.prism.qq.channel.QqChannelStatus
import io.prism.qq.channel.QqMessage
import io.prism.qq.channel.QqTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道媒体能力声明（2026-10-27；US-3126 AC-8/AC-9）。
 *
 * 真机实证：协议端不可达会自动降级到无障碍，而无障碍**结构上**看不了图、发不了表情/语音；
 * 旧实现不声明能力，模型会拿到 `[图片]` 占位符后自行发挥或反复自述"发不出图"。
 */
class QqChannelMediaCapabilityTest {

    private class FakeOneBotChannel : QqChannel {
        override val transport = QqTransport.ONEBOT
        override suspend fun status() = QqChannelStatus(QqTransport.ONEBOT, true)
        override suspend fun sendText(contact: String, text: String): String = "已发送"
        override suspend fun recentMessages(contact: String, limit: Int): List<QqMessage> =
            emptyList()
        override suspend fun setSignature(signature: String): String = "已修改"
        override fun incoming(): Flow<QqMessage> = emptyFlow()
    }

    @Test
    fun `协议端通道声明支持媒体`() {
        assertTrue(FakeOneBotChannel().supportsMedia)
    }

    @Test
    fun `无障碍通道声明不支持媒体`() {
        // 用默认实现验证：非 ONEBOT 一律 false（无障碍/未来新增通道默认保守）
        val accessibilityLike = object : QqChannel {
            override val transport = QqTransport.ACCESSIBILITY
            override suspend fun status() = QqChannelStatus(QqTransport.ACCESSIBILITY, true)
            override suspend fun sendText(contact: String, text: String): String = "已发送"
            override suspend fun recentMessages(contact: String, limit: Int): List<QqMessage> =
                emptyList()
            override suspend fun setSignature(signature: String): String = "已修改"
            override fun incoming(): Flow<QqMessage> = emptyFlow()
        }
        assertFalse(accessibilityLike.supportsMedia)
    }

    @Test
    fun `默认实现按 transport 推导，非协议端一律保守为不支持`() {
        // 真值表（默认实现）：ONEBOT=true；ACCESSIBILITY/未来新增通道=false。
        // 无障碍真实实现另有显式 `override val supportsMedia = false`，
        // 与其类注释中的能力边界一致（此处不构造它：依赖 QqLocalToolExecutor 全套依赖）。
        val oneBot = FakeOneBotChannel()
        assertTrue(oneBot.supportsMedia)
        val accessibilityLike = object : QqChannel {
            override val transport = QqTransport.ACCESSIBILITY
            override suspend fun status() = QqChannelStatus(QqTransport.ACCESSIBILITY, true)
            override suspend fun sendText(contact: String, text: String): String = "已发送"
            override suspend fun recentMessages(contact: String, limit: Int): List<QqMessage> =
                emptyList()
            override suspend fun setSignature(signature: String): String = "已修改"
            override fun incoming(): Flow<QqMessage> = emptyFlow()
        }
        assertFalse(accessibilityLike.supportsMedia)
    }
}
