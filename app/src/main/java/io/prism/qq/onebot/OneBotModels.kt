package io.prism.qq.onebot

import io.prism.qq.channel.QqMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OneBot 11 报文模型与**纯解析函数**（ADR-064）。
 *
 * **为什么拆成纯函数**：JSON 形状随实现（NapCat / SnowLuma）与版本漂移，
 * 解析是唯一需要密集单测的部分；客户端只负责传输与状态机。
 *
 * 参考：[OneBot 11 规范](https://github.com/botuniverse/onebot-11)
 */
object OneBotModels {

    /** 响应体（`status`/`retcode`/`data`/`message`/`echo`）。 */
    data class Response(
        val ok: Boolean,
        val retcode: Int,
        val data: JsonElement?,
        val message: String?
    )

    /** 解析 HTTP/WS 的响应 JSON；非法 JSON 返回 [Response.ok]=false。 */
    fun parseResponse(body: String): Response = try {
        val root = Json.parseToJsonElement(body).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        val retcode = root["retcode"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1
        Response(
            ok = status == "ok" && retcode == 0,
            retcode = retcode,
            data = root["data"],
            message = root["message"]?.jsonPrimitive?.contentOrNull
                ?: root["wording"]?.jsonPrimitive?.contentOrNull
        )
    } catch (_: Exception) {
        Response(ok = false, retcode = -1, data = null, message = "响应不是合法 JSON")
    }

    /** 构建 `send_private_msg` 请求体。 */
    fun buildSendPrivateMsg(userId: Long, text: String): String = buildJsonObject {
        put("user_id", userId)
        put("message", text)
    }.toString()

    /**
     * 构建 `send_private_msg` 的**语音**请求体（PRD US-3113）。
     *
     * 消息段格式（OneBot 11 `record`）：`{"type":"record","data":{"file":"base64://..."}}`。
     * 协议端负责把 WAV 转成 QQ 的 SILK——Prism 不自行实现 SILK 编码。
     *
     * @param wavBase64Url 形如 `base64://<...>` 的音频负载（见 [io.prism.qq.QqVoiceCodec]）
     */
    fun buildSendPrivateVoiceMsg(userId: Long, wavBase64Url: String): String = buildJsonObject {
        put("user_id", userId)
        putJsonArray("message") {
            addJsonObject {
                put("type", "record")
                putJsonObject("data") {
                    put("file", wavBase64Url)
                }
            }
        }
    }.toString()

    /** 构建 `get_friend_msg_history` 请求体（NapCat 扩展；缺失时调用方按失败降级）。 */
    fun buildGetFriendMsgHistory(userId: Long, count: Int): String = buildJsonObject {
        put("user_id", userId)
        put("count", count)
    }.toString()

    /** 构建 `set_self_longnick` 请求体。 */
    fun buildSetLongNick(signature: String): String = buildJsonObject {
        put("longNick", signature)
    }.toString()

    /**
     * 从私聊消息事件中提取 [QqMessage]。
     *
     * 仅接受 `post_type=message`、`message_type=private` 且 `self_id` 与配置一致的事件；
     * 若给出 [targetUserId]（非 0），发送者必须与之匹配（避免回复陌生人）。
     *
     * @return 匹配的事件消息；不匹配/字段缺失返回 null
     */
    fun parsePrivateMessage(
        event: JsonObject,
        expectedSelfId: Long,
        targetUserId: Long
    ): QqMessage? {
        if (event["post_type"]?.jsonPrimitive?.contentOrNull != "message") return null
        if (event["message_type"]?.jsonPrimitive?.contentOrNull != "private") return null
        val selfId = event["self_id"]?.jsonPrimitive?.longOrNull ?: return null
        // guardrail M-10：self_id 必须已知且匹配，否则事件方向不可判定 → fail-closed
        if (expectedSelfId <= 0 || selfId != expectedSelfId) return null
        val senderId = event["user_id"]?.jsonPrimitive?.longOrNull
            ?: event["sender"]?.jsonObject?.get("user_id")?.jsonPrimitive?.longOrNull
            ?: return null
        if (targetUserId > 0 && senderId != targetUserId) return null
        val messageId = event["message_id"]?.let { elementToId(it) }
        // 媒体消息不进入回复链路：message 数组无 text 段时**不得**回退 raw_message
        //（真实事件必带 `[CQ:image,...]`，回退会把 CQ 码当正文回复，guardrail M-01）。
        val messageElement = event["message"]
        val text = extractText(messageElement)
            ?: if (messageElement == null) {
                event["raw_message"]?.jsonPrimitive?.contentOrNull?.takeIf { !it.contains("[CQ:") }
            } else {
                null
            }
            ?: ""
        if (text.isBlank()) return null
        val timeSec = event["time"]?.jsonPrimitive?.longOrNull ?: 0L
        return QqMessage(
            messageId = messageId,
            incoming = senderId != selfId,
            text = text,
            timestampMs = if (timeSec > 0) timeSec * 1000 else 0L
        )
    }

    /**
     * 解析 `get_friend_msg_history` 的 `data`。
     *
     * 兼容两种形状：`data.messages[]`（NapCat）与 `data[]`（部分实现）。
     * 仅保留文本消息；时间升序由调用方决定（此处保持原始顺序）。
     */
    fun parseHistory(data: JsonElement?, selfId: Long): List<QqMessage> {
        val array: JsonArray = when {
            data is JsonObject -> data["messages"] as? JsonArray ?: return emptyList()
            data is JsonArray -> data
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val message = element as? JsonObject ?: return@mapNotNull null
            val senderId = message["sender"]?.jsonObject?.get("user_id")?.jsonPrimitive?.longOrNull
                ?: message["user_id"]?.jsonPrimitive?.longOrNull
                ?: return@mapNotNull null
            val messageElement = message["message"]
            val text = extractText(messageElement)
                ?: if (messageElement == null) {
                    message["raw_message"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { !it.contains("[CQ:") }
                } else {
                    null
                }
                ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            val timeSec = message["time"]?.jsonPrimitive?.longOrNull ?: 0L
            QqMessage(
                messageId = message["message_id"]?.let { elementToId(it) },
                incoming = senderId != selfId,
                text = text,
                timestampMs = if (timeSec > 0) timeSec * 1000 else 0L
            )
        }
    }

    /**
     * 从 `message` 字段（字符串或消息段数组）提取纯文本。
     *
     * **媒体消息必须返回 null**（不进入回复链路）：数组中没有 text 段时返回 null；
     * 纯字符串若含 CQ 码（`[CQ:image,...]`）同样返回 null——否则会把 CQ 码当正文回复。
     */
    fun extractText(message: JsonElement?): String? = when (message) {
        is JsonPrimitive -> message.contentOrNull?.takeIf { !it.contains("[CQ:") }
        is JsonArray -> message.mapNotNull { segment ->
            val obj = segment as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
            obj["data"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("").ifBlank { null }
        else -> null
    }

    /**
     * 规范化 `message_id`（US-3111 AC-3 幂等键）。
     *
     * 只保留 `[0-9A-Za-z_-]` 并截断 64 字符，保证「解析侧」与「持久化侧」使用同一规则——
     * 否则超长 ID 在重启后永远匹配不上，幂等失效（guardrail L-01）。
     */
    fun normalizeMessageId(raw: String?): String? = raw
        ?.trim()
        // 只保留 ASCII `[0-9A-Za-z_-]`（与注释一致；Unicode 数字/汉字一律丢弃）
        ?.filter {
            it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '_' || it == '-'
        }
        ?.take(64)
        ?.takeIf { it.isNotEmpty() }

    /** `message_id` 可能是数字或字符串，统一转字符串并规范化（幂等键）。 */
    private fun elementToId(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> normalizeMessageId(element.contentOrNull)
        else -> null
    }

    /**
     * 从 `get_friend_list` 的 `data` 中按备注名/昵称解析 QQ 号。
     *
     * @return 命中的 user_id；未命中返回 null
     */
    fun resolveUserIdByContact(data: JsonElement?, contact: String): Long? {
        val target = contact.trim()
        if (target.isEmpty()) return null
        val array = data as? JsonArray ?: return null
        // 第一优先：备注名精确匹配（唯一确定）
        array.forEach { element ->
            val friend = element as? JsonObject ?: return@forEach
            val remark = friend["remark"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (remark == target) {
                return friend["user_id"]?.jsonPrimitive?.longOrNull
            }
        }
        // 第二优先：昵称匹配，**必须唯一**（guardrail M-16：避免命中同名他人）
        val nicknameMatches = array.mapNotNull { element ->
            val friend = element as? JsonObject ?: return@mapNotNull null
            val nickname = friend["nickname"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (nickname == target) friend["user_id"]?.jsonPrimitive?.longOrNull else null
        }.distinct()
        nicknameMatches.singleOrNull()?.let { return it }
        // 第三优先：纯数字 contact 必须命中好友列表里的 user_id（guardrail R-01/M-07：
        // 保留「显式 QQ 号」能力，但仍以好友关系为前置，杜绝向任意号码发消息）
        val numeric = target.toLongOrNull() ?: return null
        array.forEach { element ->
            val friend = element as? JsonObject ?: return@forEach
            if (friend["user_id"]?.jsonPrimitive?.longOrNull == numeric) return numeric
        }
        return null
    }

    /**
     * 判断地址是否为本机/私网（ADR-064 鉴权边界）。
     *
     * **严格 IP 字面量判定**（guardrail H-01）：只接受 `localhost`、`127.0.0.1`、`::1`
     * 与**四段十进制 IPv4 字面量**；任何域名一律拒绝——否则 `10.0.0.5.evil.example`
     * 会被前缀匹配误判为私网，把 `Authorization: Bearer <token>` 发往公网。
     *
     * scheme 仅允许 `http/https/ws/wss`；URI 解析失败 fail-closed。
     */
    fun isPrivateEndpoint(rawUrl: String): Boolean =
        io.prism.network.PrivateNetwork.isPrivateEndpoint(rawUrl)
}
