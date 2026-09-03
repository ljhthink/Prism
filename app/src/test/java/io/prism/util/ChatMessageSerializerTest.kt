package io.prism.util

import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatMessageSerializer 单元测试（UX-001 问题 4，ADR-021，BR-testing-004）。
 *
 * 验证会话历史 JSON 编解码：
 * - 往返一致性（encode → decode 保持字段完整）
 * - thinkingChain / searchResults / sources 等新增字段持久化
 * - 空列表 / 空 JSON 边界
 * - ignoreUnknownKeys 容错
 */
class ChatMessageSerializerTest {

    @Test
    fun `round trip preserves basic fields`() {
        val messages = listOf(
            ChatMessage(id = 1, role = Role.USER, content = "你好", timestamp = 1000L),
            ChatMessage(id = 2, role = Role.ASSISTANT, content = "世界", timestamp = 2000L)
        )
        val json = ChatMessageSerializer.encodeList(messages)
        val decoded = ChatMessageSerializer.decodeList(json)
        assertEquals(messages, decoded)
    }

    @Test
    fun `round trip preserves thinking chain and search results`() {
        val messages = listOf(
            ChatMessage(
                id = 3,
                role = Role.ASSISTANT,
                content = "答案",
                timestamp = 3000L,
                thinkingChain = "推理过程",
                searchResults = listOf(
                    io.prism.ui.model.SearchResult("标题", "https://example.com", "摘要")
                )
            )
        )
        val json = ChatMessageSerializer.encodeList(messages)
        val decoded = ChatMessageSerializer.decodeList(json)
        assertEquals(1, decoded.size)
        assertEquals("推理过程", decoded[0].thinkingChain)
        assertEquals(1, decoded[0].searchResults?.size)
        assertEquals("标题", decoded[0].searchResults?.get(0)?.title)
    }

    @Test
    fun `round trip preserves sources`() {
        val messages = listOf(
            ChatMessage(
                id = 4,
                role = Role.ASSISTANT,
                content = "引用回答",
                timestamp = 4000L,
                sources = listOf(io.prism.ui.model.Citation(1, "文档A", 2, 0.85))
            )
        )
        val json = ChatMessageSerializer.encodeList(messages)
        val decoded = ChatMessageSerializer.decodeList(json)
        assertEquals(1, decoded[0].sources.size)
        assertEquals("文档A", decoded[0].sources[0].documentTitle)
        assertEquals(0.85, decoded[0].sources[0].similarity, 0.0001)
    }

    @Test
    fun `empty list round trips`() {
        val json = ChatMessageSerializer.encodeList(emptyList())
        assertTrue(ChatMessageSerializer.decodeList(json).isEmpty())
    }

    @Test
    fun `decode empty json string returns empty list`() {
        // 空 JSON 数组
        assertTrue(ChatMessageSerializer.decodeList("[]").isEmpty())
    }

    @Test
    fun `ignore unknown keys does not crash on future fields`() {
        // 模拟未来版本新增字段：decode 不应崩溃，已知字段保留
        val json = """[{"id":5,"role":"USER","content":"你好","timestamp":1,"unknownField":123}]"""
        val decoded = ChatMessageSerializer.decodeList(json)
        assertEquals(1, decoded.size)
        assertEquals("你好", decoded[0].content)
    }

    @Test
    fun `transient image message has imageUrl stripped on encode`() {
        // v1 批次13（F1，guardrail TKN-V1B13-GUARDRAIL-001）：瞬态截图图片（手机操控截图 base64）
        // 仅用于当前会话 LLM 请求，持久化时剥离 imageUrl —— 防 400KB+ base64 进会话 JSON 膨胀
        //（真机 ANR 崩溃根因）+ 切纯文本模型后历史请求每轮 400。
        val messages = listOf(
            ChatMessage(
                id = 10,
                role = Role.USER,
                content = "（手机截图，请直接查看屏幕内容）",
                timestamp = 5000L,
                imageUrl = "data:image/jpeg;base64,QUJDREVG",
                transientImage = true
            ),
            ChatMessage(
                id = 11,
                role = Role.USER,
                content = "用户主动发的图片",
                timestamp = 6000L,
                imageUrl = "data:image/jpeg;base64,VVNFUklNQUdF"
            )
        )
        val json = ChatMessageSerializer.encodeList(messages)
        // 瞬态截图图片：base64 被剥离
        assertTrue("瞬态截图图片 base64 不应进入持久化 JSON", !json.contains("QUJDREVG"))
        // 用户主动发图：正常持久化
        assertTrue("用户主动发图应保留 imageUrl", json.contains("VVNFUklNQUdF"))
        // 解码后瞬态消息 imageUrl 为 null（持久化不携带），非瞬态消息保留
        val decoded = ChatMessageSerializer.decodeList(json)
        assertEquals(2, decoded.size)
        assertNull(decoded[0].imageUrl)
        assertEquals("data:image/jpeg;base64,VVNFUklNQUdF", decoded[1].imageUrl)
    }
}
