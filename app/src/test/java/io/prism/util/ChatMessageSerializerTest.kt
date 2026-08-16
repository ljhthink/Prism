package io.prism.util

import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import org.junit.Assert.assertEquals
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
}
