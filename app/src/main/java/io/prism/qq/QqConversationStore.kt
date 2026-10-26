package io.prism.qq

import io.prism.qq.channel.QqMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Prism 侧**会话记录**（2026-10-26 新增；PRD US-3112 修订）。
 *
 * **为什么必须有**：无障碍通道只能读到"最后一条气泡"（`AccessibilityQqChannel` 注释），
 * 协议端历史接口也可能不可用（`get_friend_msg_history` 是 NapCat 扩展）。没有本地会话记录时，
 * 回复永远只有当前一句可参考——这正是用户感知的"接不上话"。
 *
 * 设计对齐 OneBot 生态通行做法：**应用侧按会话持久化 + 按预算裁剪**，而不是绑定协议端条数。
 *
 * 约束：
 * - 仅应用私有目录，明文只在本地；
 * - 每联系人保留最近 [MAX_PER_CONTACT] 条，总条数上限 [MAX_TOTAL]（超出按最旧淘汰）；
 * - 原子写（临时文件 + rename），写失败不抛异常（会话记录是增强能力，不能阻断回复）；
 * - 可一键清空（设置页）。
 */
class QqConversationStore(
    private val rootDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private data class Entry(
        val dir: Boolean, // true=收到，false=发出
        val text: String,
        val at: Long,
        val messageId: String? = null
    )

    private val file = File(rootDir, "qq_conversations.json")

    /** contact → 时间升序条目。 */
    private var store: LinkedHashMap<String, MutableList<Entry>> = read()

    /** 读取最近 [limit] 条（时间升序；无记录返回空列表）。 */
    @Synchronized
    fun recent(contact: String, limit: Int): List<QqMessage> {
        if (limit <= 0) return emptyList()
        val list = store[contact.trim()] ?: return emptyList()
        return list.takeLast(limit).map {
            QqMessage(
                messageId = it.messageId,
                incoming = it.dir,
                text = it.text,
                timestampMs = it.at
            )
        }
    }

    /**
     * 追加一条会话记录（自动去重同 `messageId`）。
     *
     * @return true=实际写入
     */
    @Synchronized
    fun append(contact: String, message: QqMessage): Boolean {
        val key = contact.trim()
        if (key.isEmpty() || message.text.isBlank()) return false
        val list = store.getOrPut(key) { mutableListOf() }
        // 协议端同一条消息可能被事件与轮询各追加一次
        if (!message.messageId.isNullOrBlank() && list.any { it.messageId == message.messageId }) {
            return false
        }
        list += Entry(
            dir = message.incoming,
            text = message.text.take(MAX_TEXT_CHARS),
            at = if (message.timestampMs > 0) message.timestampMs else clock(),
            messageId = message.messageId
        )
        trim(list)
        trimTotal()
        return persist()
    }

    /** 清空全部会话记录（设置页"清空会话记录"）。 */
    @Synchronized
    fun clear(): Boolean {
        store = LinkedHashMap()
        return runCatching { file.delete() }.isSuccess
    }

    /** 当前记录条数（UI 展示用）。 */
    @Synchronized
    fun totalCount(): Int = store.values.sumOf { it.size }

    private fun trim(list: MutableList<Entry>) {
        while (list.size > MAX_PER_CONTACT) list.removeAt(0)
    }

    private fun trimTotal() {
        var total = totalCount()
        if (total <= MAX_TOTAL) return
        // 按每条最旧时间淘汰，直到回到上限（防多联系人把文件撑爆）
        while (total > MAX_TOTAL) {
            val oldestKey = store.entries
                .filter { it.value.isNotEmpty() }
                .minByOrNull { it.value.first().at }?.key ?: return
            val list = store[oldestKey] ?: return
            list.removeAt(0)
            if (list.isEmpty()) store.remove(oldestKey)
            total--
        }
    }

    private fun read(): LinkedHashMap<String, MutableList<Entry>> = runCatching {
        if (!file.isFile) return LinkedHashMap()
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val result = LinkedHashMap<String, MutableList<Entry>>()
        root.forEach { (contact, value) ->
            val entries = (value as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val text = obj["t"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Entry(
                    dir = obj["d"]?.jsonPrimitive?.contentOrNull == "1",
                    text = text,
                    at = obj["a"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    messageId = obj["m"]?.jsonPrimitive?.contentOrNull
                )
            }.toMutableList()
            if (entries.isNotEmpty()) result[contact] = entries
        }
        result
    }.getOrDefault(LinkedHashMap())

    @Synchronized
    private fun persist(): Boolean = runCatching {
        val json = buildString {
            append("{")
            append(store.entries.joinToString(",") { (contact, entries) ->
                "${q(contact)}:[" + entries.joinToString(",") { e ->
                    "{\"d\":\"${if (e.dir) 1 else 0}\",\"t\":${q(e.text)},\"a\":${e.at}" +
                        (e.messageId?.let { ",\"m\":${q(it)}" } ?: "") + "}"
                } + "]"
            })
            append("}")
        }
        val tmp = File(rootDir, "qq_conversations.json.tmp")
        tmp.writeText(json)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
        true
    }.getOrDefault(false)

    private fun q(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "") + "\""

    companion object {
        /** 单联系人保留条数（约等于数十轮对话）。 */
        const val MAX_PER_CONTACT = 200

        /** 全部联系人总条数上限（约 2MB 量级）。 */
        const val MAX_TOTAL = 2_000

        /** 单条正文上限（防超长粘贴撑爆文件）。 */
        const val MAX_TEXT_CHARS = 2_000
    }
}
