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
 * - `encodeDefaults = false`（v1 批次17）：默认值字段不写入，防 variants/activeVariantIndex
 *   等新增字段逐条膨胀会话 JSON；反序列化缺失字段应用默认值，语义不变
 *
 * 纯函数、无 Android Context 依赖（BR-testing-004 可测性），可在纯 JVM 测试中直接验证。
 */
object ChatMessageSerializer {

    private val json = Json {
        ignoreUnknownKeys = true
        // v1 批次17（US-1707，guardrail 防膨胀）：改为 encodeDefaults=false ——
        // 默认值字段（variants=空 / activeVariantIndex=0 / transientImage=false /
        // isSystemNotice=false / sources=空 等）不再逐条写入会话 JSON。
        // 反序列化时缺失字段应用默认值，语义不变（roundtrip 单测覆盖）。
        encodeDefaults = false
    }

    /**
     * 将消息列表编码为 JSON 字符串。
     *
     * **v1 批次13（F1，guardrail TKN-V1B13-GUARDRAIL-001）**：持久化前剥离瞬态截图图片的
     * base64 —— [ChatMessage.transientImage] 为 true 的消息仅用于当前会话 LLM 请求（image_url
     * 注入），不落会话历史。根因：手机操控截图 400KB+ base64 进会话 JSON 后，UI 恢复渲染
     * 时主线程解码/布局被阻塞 >5s → 真机 ANR 崩溃 + 历史界面卡顿；且切纯文本模型后历史
     * 请求每轮携带 image_url 触发 400。用户主动发图（transientImage=false）不受影响。
     */
    fun encodeList(messages: List<ChatMessage>): String {
        val stripped = messages.map { msg ->
            if (msg.transientImage) msg.copy(imageUrl = null) else msg
        }
        return json.encodeToString(stripped)
    }

    /** 将 JSON 字符串解码为消息列表；无法解析时由调用方决定降级（如返回空列表）。 */
    fun decodeList(jsonString: String): List<ChatMessage> = json.decodeFromString(jsonString)
}
