package io.prism.qq.channel

import android.util.Log
import io.prism.qq.QqConfig
import io.prism.qq.onebot.OneBotApi
import io.prism.qq.onebot.OneBotClient
import io.prism.qq.onebot.OneBotModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * OneBot 11 协议端通道（ADR-064；PRD US-3110 / US-3111 / US-3112）。
 *
 * **联系人解析**（guardrail B-01 / R-01 修复）：协议 API 需要 `user_id`（QQ 号），用户配置的是
 * 小号视角的**备注名**。解析顺序：① 与 `cfg.targetContact` 完全一致时使用显式配置的目标 QQ 号；
 * ② `get_friend_list` 按备注名/昵称精确匹配；③ 纯数字 contact **仅当该 QQ 号在好友列表中**才生效。
 * **不存在**「数字直接返回」的捷径——否则提示注入可向任意 QQ 号发消息。
 * 缓存**按 contact 分别缓存**——不同联系人绝不共享同一解析结果（防误投递）。
 *
 * **三级降级**（US-3112 AC-2）：历史读取失败时回落到进程内事件环形缓冲，再失败则返回空
 * （调用方只用当前一条）。
 *
 * **失败语义**：全部返回可读字符串，不抛异常（除 `CancellationException`）；
 * 解析失败时**不盲目发送**（避免误发给陌生人）。
 */
class OneBotQqChannel(
    /** 注入 [OneBotApi] 以便 JVM 测试用假实现（guardrail L-07）。 */
    private val client: OneBotApi,
    private val configProvider: suspend () -> QqConfig
) : QqChannel {

    override val transport: QqTransport = QqTransport.ONEBOT

    private val resolveLock = Mutex()

    /** contact → QQ 号（逐联系人缓存；guardrail B-01）。 */
    private val targetCache = ConcurrentHashMap<String, Long>()

    /** 进程内最近事件环形缓冲（第三级降级的中间层）。 */
    private val eventBuffer = ArrayDeque<QqMessage>()
    private val bufferLock = Any()

    /** 事件丢弃日志限频（D-04：避免高频事件刷屏；不记正文）。 */
    @Volatile
    private var lastDropLogAtMs: Long = 0L

    fun invalidateTarget() {
        targetCache.clear()
    }

    override suspend fun status(): QqChannelStatus {
        val cfg = configProvider()
        val state = client.state.value
        return when (state) {
            is OneBotClient.State.Connected -> QqChannelStatus(
                transport = QqTransport.ONEBOT,
                connected = true,
                selfId = if (cfg.oneBotSelfId > 0) cfg.oneBotSelfId else state.selfId,
                detail = "已连接（OneBot 11）"
            )
            is OneBotClient.State.Connecting -> QqChannelStatus(
                QqTransport.ONEBOT, false, cfg.oneBotSelfId.takeIf { it > 0 }, "连接中…"
            )
            is OneBotClient.State.Failed -> QqChannelStatus(
                QqTransport.ONEBOT, false, cfg.oneBotSelfId.takeIf { it > 0 }, state.reason
            )
            OneBotClient.State.Disconnected -> QqChannelStatus(
                QqTransport.ONEBOT, false, cfg.oneBotSelfId.takeIf { it > 0 }, "未连接"
            )
        }
    }

    override suspend fun sendText(contact: String, text: String): String {
        val userId = resolveTargetUserId(contact)
            ?: return "错误：协议端未解析到「${mask(contact)}」的 QQ 号。" +
                "请在小号好友列表中确认备注名，或在设置中配置目标 QQ 号"
        val response = client.call(
            "send_private_msg",
            OneBotModels.buildSendPrivateMsg(userId, text)
        )
        return if (response.ok) {
            "已发送消息给「${mask(contact)}」（协议端确认）"
        } else {
            "错误：协议端发送失败（${response.message ?: "未知原因"}，retcode=${response.retcode}）"
        }
    }

    override suspend fun sendVoice(contact: String, pcm: FloatArray, sampleRate: Int): String {
        if (pcm.isEmpty()) return "错误：语音内容为空（合成未产出音频）"
        val userId = resolveTargetUserId(contact)
            ?: return "错误：协议端未解析到「${mask(contact)}」的 QQ 号。" +
                "请在小号好友列表中确认备注名，或在设置中配置目标 QQ 号"
        val payload = io.prism.qq.QqVoiceCodec.base64WavUrl(pcm, sampleRate)
        if (payload.length <= "base64://".length) return "错误：语音编码失败（音频为空）"
        val response = client.call(
            "send_private_msg",
            OneBotModels.buildSendPrivateVoiceMsg(userId, payload)
        )
        return when {
            response.ok -> "已发送语音给「${mask(contact)}」（协议端确认）"
            // retcode≥0 = 协议端**已响应并明确拒绝**（如转码失败/权限不足）→ 调用方可将此视为确定失败
            response.retcode >= 0 -> {
                val reason = (response.message ?: "未知原因").take(MAX_ERROR_REASON_CHARS)
                "错误：协议端语音发送失败（$reason，retcode=${response.retcode}）"
            }
            // retcode=-1 = 传输异常/超时/未配置：消息**可能已送达**，不得自动改发文本（防双发）
            else -> {
                val reason = (response.message ?: "未知原因").take(MAX_ERROR_REASON_CHARS)
                "⚠️ 语音发送未确认（$reason）；为避免重复发送，请勿自动重试，可改用 qq__send_message"
            }
        }
    }

    override suspend fun recentMessages(contact: String, limit: Int): List<QqMessage> {
        if (limit <= 0) return emptyList()
        val userId = resolveTargetUserId(contact)
        if (userId != null) {
            val response = client.call(
                "get_friend_msg_history",
                OneBotModels.buildGetFriendMsgHistory(userId, limit.coerceIn(1, MAX_HISTORY_LIMIT))
            )
            if (response.ok) {
                val selfId = effectiveSelfId()
                val parsed = OneBotModels.parseHistory(response.data, selfId)
                if (parsed.isNotEmpty()) return parsed
            } else {
                Log.w(TAG, "历史消息读取失败：${response.message ?: "retcode=${response.retcode}"}")
            }
        }
        // 第二级：进程内事件环形缓冲（D-07：降级必须可观测）
        val buffered = synchronized(bufferLock) { eventBuffer.toList().takeLast(limit) }
        if (buffered.isNotEmpty()) {
            Log.i(TAG, "历史降级到事件环形缓冲（source=buffer，条数=${buffered.size}）")
            return buffered
        }
        // 第三级：空列表（调用方只用当前一条）
        Log.i(TAG, "历史读取降级到仅当前一条（source=none）")
        return emptyList()
    }

    override suspend fun setSignature(signature: String): String {
        val response = client.call(
            "set_self_longnick",
            OneBotModels.buildSetLongNick(signature)
        )
        return if (response.ok) {
            "已修改小号个性签名（协议端确认）"
        } else {
            "错误：协议端修改签名失败（${response.message ?: "未知原因"}，retcode=${response.retcode}）"
        }
    }

    override fun incoming(): Flow<QqMessage> = client.events.mapNotNull { event ->
        val cfg = configProvider()
        // guardrail M-15：双向对话未激活时不解析、不缓冲、不打好友列表请求
        if (!cfg.autoReplyActive) return@mapNotNull null
        // 2026-10-25：心跳/生命周期等**元事件**是正常流量，不是"被丢弃的事件"——
        // 若与真正的过滤失败共用同一条日志，真机 RCA 会被每 60s 一条的噪音误导。
        val postType = event["post_type"]?.jsonPrimitive?.contentOrNull
        if (postType != null && postType != "message") return@mapNotNull null
        val target = resolveTargetUserId(cfg.targetContact.trim())
        if (target == null || target <= 0L) {
            logDropRateLimited("目标 QQ 号未解析（请先连接并配置目标联系人）")
            return@mapNotNull null
        }
        val parsed = OneBotModels.parsePrivateMessage(event, effectiveSelfId(), target)
        if (parsed == null) {
            // D-04：非私聊/self_id 不匹配/非目标发送者均在此丢弃——必须可观测（限频，不记正文）
            logDropRateLimited("非目标发送者或非私聊事件")
            return@mapNotNull null
        }
        // guardrail M-12：单条超过上限不进入环形缓冲（防 30 条长文常驻内存）
        if (parsed.text.length <= MAX_BUFFERED_TEXT_CHARS) {
            synchronized(bufferLock) {
                eventBuffer.addLast(parsed)
                while (eventBuffer.size > MAX_EVENT_BUFFER) eventBuffer.removeFirst()
            }
        }
        parsed
    }

    /** D-04：丢弃事件日志限频（60s），不含任何正文/昵称。 */
    private fun logDropRateLimited(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastDropLogAtMs >= DROP_LOG_INTERVAL_MS) {
            lastDropLogAtMs = now
            Log.i(TAG, "事件已丢弃（$reason）")
        }
    }

    /** 事件过滤用的 self_id：优先连接态（可能由 get_login_info 自动获取），其次配置值。 */
    private suspend fun effectiveSelfId(): Long {
        val connected = client.connectedSelfId
        return if (connected > 0) connected else configProvider().oneBotSelfId
    }

    /**
     * 逐联系人解析 QQ 号（guardrail B-01）。
     *
     * **R-01 修复（guardrail r5）**：纯数字 contact **不再直接返回**——否则提示注入可让 LLM
     * 传入任意 QQ 号绕过好友关系校验。数字与其它写法一样，必须命中「配置目标」或好友列表。
     *
     * @return 解析成功返回 user_id；失败返回 null（调用方拒绝发送）
     */
    private suspend fun resolveTargetUserId(contact: String): Long? {
        val trimmed = contact.trim()
        if (trimmed.isEmpty()) return null
        targetCache[trimmed]?.takeIf { it > 0 }?.let { return it }
        return resolveLock.withLock {
            targetCache[trimmed]?.takeIf { it > 0 } ?: run {
                val cfg = configProvider()
                val resolved = doResolve(cfg, trimmed)
                if (resolved > 0) targetCache[trimmed] = resolved
                resolved.takeIf { it > 0 }
            }
        }
    }

    private suspend fun doResolve(cfg: QqConfig, contact: String): Long {
        // 显式目标 QQ 号**只在 contact 与配置联系人一致时**生效（防把他人消息发给目标）
        if (cfg.oneBotTargetUserId > 0 && contact == cfg.targetContact.trim()) {
            return cfg.oneBotTargetUserId
        }
        val response = client.call("get_friend_list", "{}")
        if (!response.ok) {
            Log.w(TAG, "好友列表读取失败：${response.message ?: "retcode=${response.retcode}"}")
            return 0L
        }
        return OneBotModels.resolveUserIdByContact(response.data, contact) ?: 0L
    }

    /** 联系人在日志/UI 中脱敏（guardrail L-02）。 */
    private fun mask(contact: String): String =
        if (contact.length <= 1) "***" else "${contact.first()}***"

    companion object {
        private const val TAG = "OneBotQqChannel"

        /** 历史条数硬上限（防跑飞；PRD 可配 6~30，此处为绝对上界）。 */
        const val MAX_HISTORY_LIMIT = 50

        /** 进程内事件环形缓冲容量（第二级降级）。 */
        private const val MAX_EVENT_BUFFER = 30

        /** 进入环形缓冲的单条文本上限（防长文常驻内存；guardrail M-12）。 */
        private const val MAX_BUFFERED_TEXT_CHARS = 500

        /** 协议端错误原因展示上限（防超长响应刷屏/撑爆 UI；guardrail 低危）。 */
        private const val MAX_ERROR_REASON_CHARS = 120

        /** 事件丢弃日志限频（D-04）。 */
        private const val DROP_LOG_INTERVAL_MS = 60_000L
    }
}
