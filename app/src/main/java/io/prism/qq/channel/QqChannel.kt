package io.prism.qq.channel

import kotlinx.coroutines.flow.Flow

/**
 * QQ 传输通道抽象（ADR-064；PRD US-3110 / US-3111 / US-3112 / US-3113）。
 *
 * **为什么需要双轨**：协议端（OneBot 11）在上下文、语音、事件驱动上是结构性优势，
 * 但需要用户自建外部进程且离线即不可用；无障碍通道（本机 UI 自动化）零外部依赖，
 * 但只能读最后一条气泡、会建可见副屏。二者能力互补，故抽象为可切换的同一接口。
 *
 * **默认与降级**：`transport=ONEBOT` 为默认主通道；未配置/未连接/鉴权失败时由上层
 * 降级到 [QqTransport.ACCESSIBILITY]，并在 UI 如实展示当前生效通道与原因。
 *
 * **契约**（与项目既有工具执行器一致）：所有方法返回**可读结果字符串**，失败不抛异常
 * （`CancellationException` 除外）；调用方无需区分异常与业务失败。
 */
enum class QqTransport {
    /** OneBot 11 协议端（默认）。 */
    ONEBOT,

    /** 无障碍 UI 自动化（兜底）。 */
    ACCESSIBILITY
}

/** 通道状态（设置页直接展示；`detail` 必须可读且不含凭据）。 */
data class QqChannelStatus(
    val transport: QqTransport,
    val connected: Boolean,
    /** 协议端小号 QQ 号；无障碍通道为 null。 */
    val selfId: Long? = null,
    /** 可读状态/诊断（如「已连接」「鉴权失败」「地址不可达」）。 */
    val detail: String = ""
)

/**
 * 一条 QQ 消息（协议端与无障碍通道的统一视图）。
 *
 * @param messageId 协议端消息 ID（幂等键）；无障碍通道为 null
 * @param incoming true=对方发来，false=自己发出
 * @param text 文本正文（媒体消息为空串）
 * @param timestampMs 消息时间（毫秒；未知时为 0）
 */
data class QqMessage(
    val messageId: String?,
    val incoming: Boolean,
    val text: String,
    val timestampMs: Long
)

interface QqChannel {

    val transport: QqTransport

    /** 探测当前连接状态（不得抛异常）。 */
    suspend fun status(): QqChannelStatus

    /** 发送文本；返回可读结果（成功/失败原因）。 */
    suspend fun sendText(contact: String, text: String): String

    /**
     * 读取与 [contact] 的最近 [limit] 条消息（时间升序）。
     *
     * 通道不支持时返回空列表（调用方按降级路径处理）。
     */
    suspend fun recentMessages(contact: String, limit: Int): List<QqMessage>

    /** 修改个性签名；返回可读结果。 */
    suspend fun setSignature(signature: String): String

    /**
     * 发送语音（PRD US-3113）。
     *
     * 默认实现返回可读「不支持」——无障碍通道无法注入预生成音频（Android 无系统虚拟麦克风），
     * 故只有协议端覆盖本方法。
     *
     * @param pcm 单声道 PCM 浮点样本（[-1,1]）
     * @param sampleRate 采样率（Hz）
     */
    suspend fun sendVoice(contact: String, pcm: FloatArray, sampleRate: Int): String =
        "错误：当前通道不支持语音消息（无障碍通道无法注入预生成音频）"

    /**
     * 新消息事件流（仅协议端实现；无障碍通道返回空流——其轮询由 Service 侧承载）。
     */
    fun incoming(): Flow<QqMessage>
}
