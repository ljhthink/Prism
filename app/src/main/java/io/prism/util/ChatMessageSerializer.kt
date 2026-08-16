package io.prism.util

import io.prism.ui.model.ChatMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [ChatMessage] 列表 JSON 序列化器（UX-001 问题 4，ADR-021）。
 *
 * 负责 [io.prism.data.Session.messagesJson] 的编码 / 解码：
 * - [encodeList]：消息列表 → JSON 字符串（会话持久化写入）
 * - [decodeList]：JSON 字符串 → 消息列表（会话历史恢复）
 *
 * **容错配置**：
 * - `ignoreUnknownKeys = true`：旧版 JSON 含未知字段时不崩溃，向前兼容（消息模型演进）
 * - `encodeDefaults = true`：显式编码默认值字段，保证新旧版本解码一致
 *
 * 纯函数、无 Android Context 依赖（BR-testing-004 可测性），可在纯 JVM 测试中直接验证。
 */
object ChatMessageSerializer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 将消息列表编码为 JSON 字符串。 */
    fun encodeList(messages: List<ChatMessage>): String = json.encodeToString(messages)

    /** 将 JSON 字符串解码为消息列表；无法解析时由调用方决定降级（如返回空列表）。 */
    fun decodeList(jsonString: String): List<ChatMessage> = json.decodeFromString(jsonString)
}
