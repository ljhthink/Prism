package io.prism.qq.channel

import io.prism.qq.QqLocalToolExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 无障碍通道（ADR-064 兜底；原 ADR-062 主路线）。
 *
 * **能力边界**（如实降级，不做假承诺）：
 * - 发送/签名：复用 [QqLocalToolExecutor] 的后台副屏 UI 链路（闸门/节流仍在执行器内）；
 * - 历史上下文：只能读到**最后一条**消息气泡（无 message_id），故 [recentMessages] 至多 1 条；
 * - 事件：无法事件驱动（跨用户通知监听不可行，见跟进调研 §1.3），[incoming] 返回空流，
 *   由 Service 侧以「轻量探测 + 命中才建屏」轮询兜底；
 * - 语音：不支持（Android 无系统级虚拟麦克风，无法注入预生成音频）。
 *
 * 该通道存在的意义是：**协议端未配置/离线时不至于功能全失**。
 */
class AccessibilityQqChannel(
    private val executor: QqLocalToolExecutor,
    /** 无障碍服务是否可用（生产：服务单例存在；测试可注入）。 */
    private val availability: () -> Boolean = {
        io.prism.phonecontrol.PhoneControlAccessibilityService.instance != null
    }
) : QqChannel {

    override val transport: QqTransport = QqTransport.ACCESSIBILITY

    override suspend fun status(): QqChannelStatus {
        val available = runCatching { availability() }.getOrDefault(false)
        return QqChannelStatus(
            transport = QqTransport.ACCESSIBILITY,
            connected = available,
            selfId = null,
            detail = if (available) {
                "已启用（无障碍后台链路；仅能读取最后一条消息，可能显示副屏小窗）"
            } else {
                "无障碍服务未开启（请在系统设置中授权 Prism）"
            }
        )
    }

    override suspend fun sendText(contact: String, text: String): String =
        executor.sendProactive(contact, text)

    override suspend fun recentMessages(contact: String, limit: Int): List<QqMessage> {
        if (limit <= 0) return emptyList()
        val last = executor.lastMessageWith(contact) ?: return emptyList()
        return listOf(
            QqMessage(
                messageId = null,
                incoming = last.incoming,
                text = last.text,
                timestampMs = 0L
            )
        )
    }

    override suspend fun setSignature(signature: String): String =
        executor.setSignatureUnconfirmed(signature)

    override fun incoming(): Flow<QqMessage> = emptyFlow()
}
