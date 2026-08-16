package io.prism.ui.chat

import io.prism.network.KnowledgeBaseLocalToolExecutor
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.prism.ui.model.ToolCallRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ac-verifier 补充（TKN-UXR7R2-ACCEPTANCE-001）：UXR7-R2 引用池（工具参数反向映射）极端场景验证。
 *
 * 覆盖主 Agent 测试（ConversationViewModelUxR6Test）未覆盖的盲区：
 * 1. AC-3.2 JSON 容错：arguments 为 JSON 数组 / 原始值 / documentTitle 为 null / 非字符串
 * 2. AC-3.3 successfulKbReadToolCallIds 对 toolCallId=null 的 TOOL 消息处理
 * 3. AC-3.4 多轮工具回路中 search + get_document_content 混合去重
 */
class ConversationViewModelUxr7SupplementTest {

    @Test
    fun `parseToolCallDocumentTitle returns null for json array arguments`() {
        // arguments 为 JSON 数组（非法 object）→ parseToJsonElement 成功但 .jsonObject 抛异常
        // → 被 catch 捕获返回 null（不崩溃）
        assertNull(ConversationViewModel.parseToolCallDocumentTitle("[1,2,3]"))
        assertNull(ConversationViewModel.parseToolCallDocumentTitle("[]"))
    }

    @Test
    fun `parseToolCallDocumentTitle returns null for json primitive arguments`() {
        // arguments 为 JSON 原始值（字符串/数字）→ .jsonObject 抛异常 → null
        assertNull(ConversationViewModel.parseToolCallDocumentTitle("\"just a string\""))
        assertNull(ConversationViewModel.parseToolCallDocumentTitle("123"))
        assertNull(ConversationViewModel.parseToolCallDocumentTitle("null"))
    }

    @Test
    fun `parseToolCallDocumentTitle returns null for non-string documentTitle`() {
        // documentTitle 为对象 → jsonPrimitive 抛异常 → 被 catch 捕获返回 null
        assertNull(ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": {"a":1}}"""))
        // documentTitle 为数组 → jsonPrimitive 抛异常 → null
        assertNull(ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": [1,2]}"""))
    }

    @Test
    fun `parseToolCallDocumentTitle maps explicit null and literal null to null`() {
        // DEF-001 修复（TKN-UXR7R2-ACCEPTANCE-002）：JsonNull.content 返回字符串 "null"，
        // documentTitle 显式 null 或字面量 "null"（含空白包裹）均视为缺失返回 null，
        // 杜绝假引用"null"污染引用池（对齐 ConversationViewModelUxR6Test 修复后断言）。
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": null}"""))
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": "null"}"""))
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": "  null  "}"""))
    }

    @Test
    fun `parseToolCallDocumentTitle accepts numeric documentTitle as content`() {
        // documentTitle 为数字（LLM 偶发）→ jsonPrimitive.content 返回 "123"（容错）
        assertEquals("123", ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": 123}"""))
    }

    @Test
    fun `parseToolCallDocumentTitle trims whitespace-only title to null`() {
        assertEquals(null, ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": "   "}"""))
        // 带首尾空格的标题 → trim
        assertEquals("设计规范.md", ConversationViewModel.parseToolCallDocumentTitle("""{"documentTitle": "  设计规范.md  "}"""))
    }

    @Test
    fun `successfulKbReadToolCallIds skips tool message with null toolCallId`() {
        // toolCallId=null 的 TOOL 消息（协议异常/缺失）→ mapNotNull 跳过，不崩溃
        val messages = listOf(
            ChatMessage(
                id = 1, role = Role.TOOL,
                content = "【知识库文档：设计规范.md】\n内容\n【END】",
                timestamp = 0, toolCallId = null, toolName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT
            )
        )
        assertTrue(ConversationViewModel.successfulKbReadToolCallIds(messages).isEmpty())
    }

    @Test
    fun `successfulKbReadToolCallIds handles mixed success failure across rounds`() {
        // 多轮：2 成功 1 失败 1 非 kb 工具 → 仅成功 2 个入集合
        val messages = listOf(
            ChatMessage(id = 1, role = Role.TOOL, content = "【知识库文档：文档A.md】\n内容\n【END】",
                timestamp = 0, toolCallId = "ok_1", toolName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT),
            ChatMessage(id = 2, role = Role.TOOL, content = "知识库中未找到文档「文档B.md」",
                timestamp = 0, toolCallId = "fail_1", toolName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT),
            ChatMessage(id = 3, role = Role.TOOL, content = "【知识库文档：文档C.md】\n内容\n【END】",
                timestamp = 0, toolCallId = "ok_2", toolName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT),
            ChatMessage(id = 4, role = Role.TOOL, content = "【知识库内容，来源为已上传的个人资料】\n[来源1] 文件=文档D.txt\n【END 知识库内容】",
                timestamp = 0, toolCallId = "search_1", toolName = KnowledgeBaseLocalToolExecutor.TOOL_SEARCH)
        )
        assertEquals(setOf("ok_1", "ok_2"), ConversationViewModel.successfulKbReadToolCallIds(messages))
    }

    @Test
    fun `parseKnowledgeBaseCitationsFromToolCalls merges with text citations for dedup`() {
        // AC-3.4：文本解析（TOOL 返回）与参数反向映射（assistant toolCalls）结果
        // 在 syncToolMessages 中按 documentTitle 去重合并——此处验证两个纯函数对
        // 同一文档的解析结果可被外层 distinctBy 去重。
        val textCitations = ConversationViewModel.parseKnowledgeBaseCitations(
            "【知识库文档：设计规范.md】\n内容\n【END】"
        )
        val argCitations = ConversationViewModel.parseKnowledgeBaseCitationsFromToolCalls(
            listOf(
                ToolCallRef(
                    id = "call_1",
                    functionName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT,
                    arguments = """{"documentTitle": "设计规范.md"}"""
                )
            )
        )
        // 两来源同文档 → 合并去重后仅 1 条
        val merged = (textCitations + argCitations).distinctBy { it.documentTitle }
        assertEquals("同文档两来源应去重为 1 条", 1, merged.size)
        assertEquals("设计规范.md", merged[0].documentTitle)
    }

    @Test
    fun `parseKnowledgeBaseCitationsFromToolCalls ignores search tool calls`() {
        // 白名单：仅 get_document_content 工具的 documentTitle 被提取，search 工具参数不解析
        val toolCalls = listOf(
            ToolCallRef(
                id = "s1",
                functionName = KnowledgeBaseLocalToolExecutor.TOOL_SEARCH,
                arguments = """{"query": "昔涟", "topK": 3}"""
            ),
            ToolCallRef(
                id = "g1",
                functionName = KnowledgeBaseLocalToolExecutor.TOOL_GET_DOCUMENT_CONTENT,
                arguments = """{"documentTitle": "昔涟介绍.md"}"""
            )
        )
        val citations = ConversationViewModel.parseKnowledgeBaseCitationsFromToolCalls(toolCalls)
        assertEquals("仅 get_document_content 应解析", 1, citations.size)
        assertEquals("昔涟介绍.md", citations[0].documentTitle)
    }

    @Test
    fun `parseKnowledgeBaseCitations dedups get_document_content across search marker`() {
        // 同文档同时以 search [来源N] 与 get_document_content 【知识库文档：】 出现 → 去重
        val content = """
            【知识库内容，来源为已上传的个人资料】
            [来源1] 文件=设计规范.md 相似度=0.8
            【END 知识库内容】
            【知识库文档：设计规范.md】
            content B
            【END】
        """.trimIndent()
        val citations = ConversationViewModel.parseKnowledgeBaseCitations(content)
        assertEquals("同文档两格式应去重为 1 条", 1, citations.size)
        assertEquals("设计规范.md", citations[0].documentTitle)
    }
}
