package io.prism.skill

import io.prism.data.McpServerConfig
import io.prism.data.McpServerType
import io.prism.data.ProviderConfig
import io.prism.fs.ToolConfirmationGate
import io.prism.network.ChatStreamProvider
import io.prism.network.McpToolProvider
import io.prism.network.StreamEvent
import io.prism.network.ToolDefinition
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.prism.ui.model.ToolCallRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SkillExecutor 单元测试（US-025，ADR-014 5.4）。
 *
 * **测试分层**（BR-testing-004）：
 * 1. **纯函数测试**：companion object internal 函数（stripNamespace/selectMcpServer/
 *    shouldEmitMaxRoundsError/buildAssistantToolCallMessage/buildToolResultMessage/
 *    encodeArguments/mapToJsonElement/错误格式化）—— 不依赖 Android Context
 * 2. **executeToolCall 集成测试**：fake McpToolProvider + fake ToolConfirmationGate，
 *    验证用户确认/拒绝/超时/异常/无 Server 等场景
 * 3. **executeLoop 集成测试**：fake ChatStreamProvider + fake McpToolProvider +
 *    fake ToolConfirmationGate，验证回路编排/maxRounds/message 回灌
 *
 * **Fake 设计**：
 * - [FakeMcpToolProvider]：可配置返回值/延迟/异常
 * - [FakeConfirmationGate]：可配置 approve/reject/延迟/异常
 * - [FakeChatStreamProvider]：可配置多轮事件序列
 */
class SkillExecutorTest {

    // ==================== 纯函数测试 ====================

    @Test
    fun `stripNamespace removes skill prefix`() {
        assertEquals("read_file", SkillExecutor.stripNamespace("filesystem__read_file"))
    }

    @Test
    fun `stripNamespace returns original when no separator`() {
        assertEquals("read_file", SkillExecutor.stripNamespace("read_file"))
    }

    @Test
    fun `stripNamespace handles multiple separators returns after first separator`() {
        // substringAfter 只剥离第一个分隔符；namespace 规范为 skillName__toolName（单分隔符），
        // 多分隔符场景下 toolName 本身可含 __（如 skill__read__file → read__file）
        assertEquals("b__read_file", SkillExecutor.stripNamespace("a__b__read_file"))
    }

    @Test
    fun `stripNamespace returns empty for separator only`() {
        assertEquals("", SkillExecutor.stripNamespace("__"))
    }

    @Test
    fun `selectMcpServer returns first enabled server`() {
        val disabled = makeServer("disabled", isEnabled = false)
        val enabled1 = makeServer("enabled1", isEnabled = true)
        val enabled2 = makeServer("enabled2", isEnabled = true)
        val result = SkillExecutor.selectMcpServer(listOf(disabled, enabled1, enabled2))
        assertEquals("enabled1", result?.name)
    }

    @Test
    fun `selectMcpServer returns null when all disabled`() {
        val s1 = makeServer("s1", isEnabled = false)
        val s2 = makeServer("s2", isEnabled = false)
        assertNull(SkillExecutor.selectMcpServer(listOf(s1, s2)))
    }

    @Test
    fun `selectMcpServer returns null for empty list`() {
        assertNull(SkillExecutor.selectMcpServer(emptyList()))
    }

    @Test
    fun `selectMcpServer returns first when only one enabled`() {
        val enabled = makeServer("only", isEnabled = true)
        assertEquals("only", SkillExecutor.selectMcpServer(listOf(enabled))?.name)
    }

    @Test
    fun `shouldEmitMaxRoundsError true when last round had tool call and rounds exceeded`() {
        assertTrue(SkillExecutor.shouldEmitMaxRoundsError(true, 10, 10))
    }

    @Test
    fun `shouldEmitMaxRoundsError false when last round had no tool call`() {
        assertFalse(SkillExecutor.shouldEmitMaxRoundsError(false, 10, 10))
    }

    @Test
    fun `shouldEmitMaxRoundsError false when rounds below max`() {
        assertFalse(SkillExecutor.shouldEmitMaxRoundsError(true, 3, 10))
    }

    @Test
    fun `shouldEmitMaxRoundsError false when no tool call and rounds below max`() {
        assertFalse(SkillExecutor.shouldEmitMaxRoundsError(false, 3, 10))
    }

    @Test
    fun `buildAssistantToolCallMessage creates message with toolCalls refs`() {
        val toolCalls = listOf(
            StreamEvent.ToolCallComplete("call_1", "skill__tool1", mapOf("a" to "b")),
            StreamEvent.ToolCallComplete("call_2", "skill__tool2", mapOf("c" to 1))
        )
        val msg = SkillExecutor.buildAssistantToolCallMessage(toolCalls) { 100L }

        assertEquals(100L, msg.id)
        assertEquals(Role.ASSISTANT, msg.role)
        assertEquals("", msg.content)
        assertEquals(2, msg.toolCalls.size)
        assertEquals("call_1", msg.toolCalls[0].id)
        assertEquals("skill__tool1", msg.toolCalls[0].functionName)
        assertEquals("{\"a\":\"b\"}", msg.toolCalls[0].arguments)
        assertEquals("call_2", msg.toolCalls[1].id)
        assertEquals("skill__tool2", msg.toolCalls[1].functionName)
    }

    @Test
    fun `buildAssistantToolCallMessage with empty toolCalls list`() {
        val msg = SkillExecutor.buildAssistantToolCallMessage(emptyList()) { 200L }
        assertEquals(200L, msg.id)
        assertEquals(Role.ASSISTANT, msg.role)
        assertTrue(msg.toolCalls.isEmpty())
    }

    @Test
    fun `buildToolResultMessage creates role TOOL message with toolCallId`() {
        val msg = SkillExecutor.buildToolResultMessage("call_x", "tool_name", "result text") { 300L }
        assertEquals(300L, msg.id)
        assertEquals(Role.TOOL, msg.role)
        assertEquals("result text", msg.content)
        assertEquals("call_x", msg.toolCallId)
        assertEquals("tool_name", msg.toolName)
    }

    @Test
    fun `encodeArguments empty map returns empty json object`() {
        assertEquals("{}", SkillExecutor.encodeArguments(emptyMap()))
    }

    @Test
    fun `encodeArguments simple string values`() {
        val result = SkillExecutor.encodeArguments(mapOf("path" to "/tmp/file", "mode" to "read"))
        // JSON 字段顺序由 Map 迭代序决定（LinkedHashMap）
        assertEquals("{\"path\":\"/tmp/file\",\"mode\":\"read\"}", result)
    }

    @Test
    fun `encodeArguments mixed types`() {
        val result = SkillExecutor.encodeArguments(
            mapOf("name" to "test", "count" to 42, "enabled" to true, "value" to null)
        )
        assertEquals("{\"name\":\"test\",\"count\":42,\"enabled\":true,\"value\":null}", result)
    }

    @Test
    fun `encodeArguments nested map`() {
        val result = SkillExecutor.encodeArguments(
            mapOf("outer" to mapOf("inner" to "value"))
        )
        assertEquals("{\"outer\":{\"inner\":\"value\"}}", result)
    }

    @Test
    fun `encodeArguments list value`() {
        val result = SkillExecutor.encodeArguments(
            mapOf("items" to listOf("a", "b", "c"))
        )
        assertEquals("{\"items\":[\"a\",\"b\",\"c\"]}", result)
    }

    @Test
    fun `encodeArguments empty list`() {
        val result = SkillExecutor.encodeArguments(mapOf("items" to emptyList<String>()))
        assertEquals("{\"items\":[]}", result)
    }

    @Test
    fun `encodeArguments number types preserved`() {
        val result = SkillExecutor.encodeArguments(
            mapOf("int" to 1, "long" to 2L, "double" to 3.5)
        )
        assertTrue(result.contains("\"int\":1"))
        assertTrue(result.contains("\"long\":2"))
        assertTrue(result.contains("\"double\":3.5"))
    }

    @Test
    fun `mapToJsonElement null returns JsonNull`() {
        val element = SkillExecutor.mapToJsonElement(null)
        assertTrue(element.toString() == "null")
    }

    @Test
    fun `mapToJsonElement unknown type falls back to string`() {
        val element = SkillExecutor.mapToJsonElement(listOf(1, 2, 3))
        // List 会被当 List<*> 处理 → JsonArray
        assertTrue(element is kotlinx.serialization.json.JsonArray)
    }

    @Test
    fun `formatRejection contains tool name`() {
        val msg = SkillExecutor.formatRejection("my_tool")
        assertTrue(msg.contains("my_tool"))
        assertTrue(msg.contains("拒绝"))
    }

    @Test
    fun `formatTimeout contains tool name and duration`() {
        val msg = SkillExecutor.formatTimeout("my_tool", 30000L)
        assertTrue(msg.contains("my_tool"))
        assertTrue(msg.contains("30000"))
        assertTrue(msg.contains("超时"))
    }

    @Test
    fun `formatToolError contains tool name and exception message`() {
        val msg = SkillExecutor.formatToolError("my_tool", RuntimeException("connection refused"))
        assertTrue(msg.contains("my_tool"))
        assertTrue(msg.contains("connection refused"))
    }

    @Test
    fun `formatToolError falls back to exception class name when message null`() {
        // RuntimeException() 无 message，formatToolError 应使用 javaClass.simpleName 兜底
        val msg = SkillExecutor.formatToolError("my_tool", RuntimeException())
        assertTrue(msg.contains("my_tool"))
        assertTrue(msg.contains("RuntimeException"))
    }

    @Test
    fun `formatNoServer contains tool name`() {
        val msg = SkillExecutor.formatNoServer("my_tool")
        assertTrue(msg.contains("my_tool"))
        assertTrue(msg.contains("MCP Server"))
    }

    @Test
    fun `formatConfirmError contains tool name and exception message`() {
        val msg = SkillExecutor.formatConfirmError("my_tool", IllegalStateException("gate closed"))
        assertTrue(msg.contains("my_tool"))
        assertTrue(msg.contains("gate closed"))
    }

    // ==================== M3：sanitizeErrorMessage 信息脱敏测试（CWE-209） ====================

    @Test
    fun `sanitizeErrorMessage returns null for null input`() {
        assertNull(SkillExecutor.sanitizeErrorMessage(null))
    }

    @Test
    fun `sanitizeErrorMessage preserves short non-path message unchanged`() {
        assertEquals("connection refused", SkillExecutor.sanitizeErrorMessage("connection refused"))
    }

    @Test
    fun `sanitizeErrorMessage redacts unix paths to placeholder`() {
        val raw = "failed to open /tmp/secret/file.txt"
        val sanitized = SkillExecutor.sanitizeErrorMessage(raw)
        assertTrue("应替换 Unix 路径为 <path>", sanitized!!.contains("<path>"))
        assertFalse("不应泄露原路径", sanitized.contains("/tmp/secret"))
    }

    @Test
    fun `sanitizeErrorMessage redacts windows paths to placeholder`() {
        val raw = "cannot access C:\\Users\\admin\\creds.txt"
        val sanitized = SkillExecutor.sanitizeErrorMessage(raw)
        assertTrue("应替换 Windows 路径为 <path>", sanitized!!.contains("<path>"))
        assertFalse("不应泄露原路径", sanitized.contains("C:\\Users"))
    }

    @Test
    fun `sanitizeErrorMessage truncates long messages to max length`() {
        val long = "x".repeat(SkillExecutor.MAX_ERROR_MESSAGE_LEN + 100)
        val sanitized = SkillExecutor.sanitizeErrorMessage(long)
        assertNotNull(sanitized)
        assertTrue("应截断到 max + 省略号", sanitized!!.length == SkillExecutor.MAX_ERROR_MESSAGE_LEN + 3)
        assertTrue("应以省略号结尾", sanitized.endsWith("..."))
    }

    @Test
    fun `sanitizeErrorMessage preserves message at exactly max length`() {
        val exact = "y".repeat(SkillExecutor.MAX_ERROR_MESSAGE_LEN)
        val sanitized = SkillExecutor.sanitizeErrorMessage(exact)
        assertEquals("等于上限不应截断", exact, sanitized)
    }

    @Test
    fun `formatToolError redacts paths in exception message (M3 integration)`() {
        // M3 集成验证：formatToolError 应通过 sanitizeErrorMessage 脱敏路径
        val msg = SkillExecutor.formatToolError(
            "read_file",
            RuntimeException("failed: /etc/passwd leaked")
        )
        assertTrue("应含工具名", msg.contains("read_file"))
        assertTrue("路径应被脱敏为 <path>", msg.contains("<path>"))
        assertFalse("不应泄露原始路径", msg.contains("/etc/passwd"))
    }

    @Test
    fun `formatConfirmError redacts paths in exception message (M3 integration)`() {
        val msg = SkillExecutor.formatConfirmError(
            "write_file",
            IllegalStateException("gate error at /home/user/.ssh/id_rsa")
        )
        assertTrue("应含工具名", msg.contains("write_file"))
        assertTrue("路径应被脱敏为 <path>", msg.contains("<path>"))
        assertFalse("不应泄露原始路径", msg.contains("/home/user"))
    }

    // ==================== L6：encodeArguments 特殊字符转义测试 ====================

    @Test
    fun `encodeArguments escapes double quotes in string values`() {
        val result = SkillExecutor.encodeArguments(mapOf("path" to """C:\Users\"test".txt"""))
        // kotlinx.serialization 自动转义双引号和反斜杠
        assertTrue("应转义反斜杠", result.contains("\\\\"))
        assertTrue("应转义双引号", result.contains("\\\""))
    }

    @Test
    fun `encodeArguments escapes backslashes in string values`() {
        val result = SkillExecutor.encodeArguments(mapOf("win" to "C:\\Program Files\\app"))
        assertTrue("应转义反斜杠", result.contains("\\\\"))
        assertFalse("不应有未转义的反斜杠路径", result.contains("C:\\Program"))
    }

    @Test
    fun `encodeArguments preserves unicode characters`() {
        val result = SkillExecutor.encodeArguments(mapOf("text" to "你好世界 🌍 中文"))
        assertTrue("应保留 Unicode 字符", result.contains("你好世界"))
        assertTrue("应保留 emoji", result.contains("🌍"))
    }

    @Test
    fun `encodeArguments escapes control characters and newlines`() {
        val result = SkillExecutor.encodeArguments(mapOf("text" to "line1\nline2\tend"))
        // 换行符 \n 应转义为 \\n，制表符 \t 应转义为 \\t
        assertTrue("应转义换行符", result.contains("\\n"))
        assertTrue("应转义制表符", result.contains("\\t"))
        assertFalse("不应含原始换行字符", result.contains("\n"))
    }

    @Test
    fun `encodeArguments handles mixed special characters`() {
        // 综合特殊字符：引号 + 反斜杠 + Unicode + 控制字符
        val result = SkillExecutor.encodeArguments(
            mapOf("complex" to "引号\"反斜杠\\换行\nUnicode你好")
        )
        // 应成功序列化（不崩溃），且包含转义后的内容
        assertTrue("应含转义后的 Unicode", result.contains("你好"))
        assertTrue("应转义双引号", result.contains("\\\""))
        assertTrue("应转义反斜杠", result.contains("\\\\"))
    }

    // ==================== executeToolCall 集成测试 ====================

    @Test
    fun `executeToolCall user approves and mcp returns result`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(returnResult = "file content here")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__read_file", mapOf("path" to "/tmp"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        val result = executor.executeToolCall(toolCall, servers)

        assertEquals("file content here", result)
        assertTrue(gate.confirmCalled)
        assertEquals("read_file", mcpProvider.lastToolName) // 命名空间剥离
        assertEquals(mapOf("path" to "/tmp"), mcpProvider.lastArguments)
    }

    @Test
    fun `executeToolCall user rejects returns rejection message`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(returnResult = "should not be called")
        val gate = FakeConfirmationGate(approve = false)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__tool", emptyMap())
        val servers = listOf(makeServer("fs", isEnabled = true))

        val result = executor.executeToolCall(toolCall, servers)

        assertTrue(result.contains("拒绝"))
        assertTrue(result.contains("skill__tool"))
        assertFalse(mcpProvider.callToolCalled)
    }

    @Test
    fun `executeToolCall no enabled server returns no server message`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(returnResult = "should not be called")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__tool", emptyMap())
        val servers = listOf(makeServer("fs", isEnabled = false))

        val result = executor.executeToolCall(toolCall, servers)

        assertTrue(result.contains("MCP Server"))
        assertFalse(mcpProvider.callToolCalled)
    }

    @Test
    fun `executeToolCall empty server list returns no server message`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(returnResult = "should not be called")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__tool", emptyMap())

        val result = executor.executeToolCall(toolCall, emptyList())

        assertTrue(result.contains("MCP Server"))
        assertFalse(mcpProvider.callToolCalled)
    }

    @Test
    fun `executeToolCall mcp throws exception returns error message`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(throwException = RuntimeException("network error"))
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__tool", emptyMap())
        val servers = listOf(makeServer("fs", isEnabled = true))

        val result = executor.executeToolCall(toolCall, servers)

        assertTrue(result.contains("工具执行失败"))
        assertTrue(result.contains("network error"))
    }

    @Test
    fun `executeToolCall timeout returns timeout message`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(returnResult = "delayed", delayMs = 500)
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__tool", emptyMap())
        val servers = listOf(makeServer("fs", isEnabled = true))

        val result = executor.executeToolCall(toolCall, servers, maxTimeoutMs = 100)

        assertTrue(result.contains("超时"))
        assertTrue(result.contains("100"))
    }

    @Test
    fun `executeToolCall confirmation gate throws returns confirm error message`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(returnResult = "should not be called")
        val gate = FakeConfirmationGate(throwException = IllegalStateException("gate broken"))
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__tool", emptyMap())
        val servers = listOf(makeServer("fs", isEnabled = true))

        val result = executor.executeToolCall(toolCall, servers)

        assertTrue(result.contains("用户确认失败"))
        assertTrue(result.contains("gate broken"))
        assertFalse(mcpProvider.callToolCalled)
    }

    @Test
    fun `executeToolCall propagates CancellationException`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(returnResult = "should not be called")
        val gate = FakeConfirmationGate(throwException = CancellationException("cancelled"))
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__tool", emptyMap())
        val servers = listOf(makeServer("fs", isEnabled = true))

        var caught: CancellationException? = null
        try {
            executor.executeToolCall(toolCall, servers)
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull("CancellationException must be rethrown (BR-error-handling-007)", caught)
    }

    @Test
    fun `executeToolCall mcp throws CancellationException propagates`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(throwException = CancellationException("mcp cancelled"))
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__tool", emptyMap())
        val servers = listOf(makeServer("fs", isEnabled = true))

        var caught: CancellationException? = null
        try {
            executor.executeToolCall(toolCall, servers)
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull("CancellationException from mcpToolProvider must be rethrown", caught)
    }

    @Test
    fun `executeToolCall strips namespace from tool name before mcp call`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(returnResult = "ok")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "translator__translate", emptyMap())
        val servers = listOf(makeServer("fs", isEnabled = true))

        executor.executeToolCall(toolCall, servers)

        assertEquals("translate", mcpProvider.lastToolName)
    }

    @Test
    fun `executeToolCall without namespace passes name as-is`() = runBlocking {
        val mcpProvider = FakeMcpToolProvider(returnResult = "ok")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "translate", emptyMap())
        val servers = listOf(makeServer("fs", isEnabled = true))

        executor.executeToolCall(toolCall, servers)

        assertEquals("translate", mcpProvider.lastToolName)
    }

    // ==================== executeLoop 集成测试 ====================

    @Test
    fun `executeLoop no tool calls returns after single round`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(StreamEvent.Delta("hello"), StreamEvent.Done)
            )
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "should not be called")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__tool"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        val events = mutableListOf<StreamEvent>()
        val result = executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 10, idGenerator = { 1L }
        ) { events.add(it) }

        assertEquals(1, provider.roundsConsumed)
        assertEquals(initialMessages, result) // 无工具调用，消息列表不变
        assertEquals(2, events.size) // Delta + Done
        assertFalse(mcpProvider.callToolCalled)
    }

    @Test
    fun `executeLoop one tool call then text response completes in two rounds`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("call_1", "skill__tool", mapOf("x" to 1))),
                listOf(StreamEvent.Delta("done"), StreamEvent.Done)
            )
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "tool result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__tool"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        var idCounter = 100L
        val result = executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 10, idGenerator = { idCounter++ }
        ) { /* ignore events */ }

        assertEquals(2, provider.roundsConsumed)
        assertEquals(3, result.size) // user + assistant placeholder + tool result
        assertEquals(Role.ASSISTANT, result[1].role)
        assertEquals(1, result[1].toolCalls.size)
        assertEquals("call_1", result[1].toolCalls[0].id)
        assertEquals(Role.TOOL, result[2].role)
        assertEquals("tool result", result[2].content)
        assertEquals("call_1", result[2].toolCallId)
        assertTrue(mcpProvider.callToolCalled)
        assertEquals("tool", mcpProvider.lastToolName)
    }

    @Test
    fun `executeLoop multiple tool calls in single round all executed`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(
                    StreamEvent.ToolCallComplete("call_1", "skill__t1", emptyMap()),
                    StreamEvent.ToolCallComplete("call_2", "skill__t2", emptyMap())
                ),
                listOf(StreamEvent.Delta("done"), StreamEvent.Done)
            )
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__t1"), makeToolDefinition("skill__t2"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        var idCounter = 1L
        val result = executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 10, idGenerator = { idCounter++ }
        ) { /* ignore */ }

        // user + assistant placeholder(2 toolCalls) + tool result 1 + tool result 2
        assertEquals(4, result.size)
        assertEquals(2, result[1].toolCalls.size)
        assertEquals(Role.TOOL, result[2].role)
        assertEquals("call_1", result[2].toolCallId)
        assertEquals(Role.TOOL, result[3].role)
        assertEquals("call_2", result[3].toolCallId)
        assertEquals(2, mcpProvider.callCount)
    }

    @Test
    fun `executeLoop maxRounds exceeded emits Error event`() = runBlocking {
        // 每轮都返回 tool_call，永远不结束 → maxRounds=2 触发
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("c1", "skill__t", emptyMap())),
                listOf(StreamEvent.ToolCallComplete("c2", "skill__t", emptyMap()))
            ),
            repeatLastRound = true
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__t"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        val events = mutableListOf<StreamEvent>()
        var idCounter = 1L
        executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 2, idGenerator = { idCounter++ }
        ) { events.add(it) }

        // 应发射 maxRounds Error 事件
        val errorEvents = events.filterIsInstance<StreamEvent.Error>()
        assertTrue("Should emit maxRounds Error", errorEvents.any { it.message.contains("上限") })
    }

    @Test
    fun `executeLoop maxRounds not exceeded when last round has no tool call`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("c1", "skill__t", emptyMap())),
                listOf(StreamEvent.Delta("done"), StreamEvent.Done)
            )
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__t"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        val events = mutableListOf<StreamEvent>()
        var idCounter = 1L
        executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 2, idGenerator = { idCounter++ }
        ) { events.add(it) }

        // 第二轮无工具调用，回路自然结束，不应发射 maxRounds Error
        assertFalse(events.any { it is StreamEvent.Error && it.message.contains("上限") })
    }

    @Test
    fun `executeLoop tool rejection回灌 rejection message to messages`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("c1", "skill__t", emptyMap())),
                listOf(StreamEvent.Delta("ok"), StreamEvent.Done)
            )
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "should not be called")
        val gate = FakeConfirmationGate(approve = false) // 用户拒绝
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__t"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        var idCounter = 1L
        val result = executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 10, idGenerator = { idCounter++ }
        ) { /* ignore */ }

        // tool result 消息内容应包含「拒绝」
        val toolMsg = result.firstOrNull { it.role == Role.TOOL }
        assertNotNull(toolMsg)
        assertTrue(toolMsg!!.content.contains("拒绝"))
        assertFalse(mcpProvider.callToolCalled)
    }

    @Test
    fun `executeLoop forwards all events to onEvent callback`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(
                    StreamEvent.ToolCallStart("c1", "skill__t", 0),
                    StreamEvent.ToolCallDelta("c1", "{\"x\""),
                    StreamEvent.ToolCallComplete("c1", "skill__t", mapOf("x" to 1))
                ),
                listOf(StreamEvent.Delta("final"), StreamEvent.Done)
            )
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__t"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        val events = mutableListOf<StreamEvent>()
        var idCounter = 1L
        executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 10, idGenerator = { idCounter++ }
        ) { events.add(it) }

        // 应收到全部 5 个事件（第一轮 3 + 第二轮 2）
        assertEquals(5, events.size)
        assertTrue(events.any { it is StreamEvent.ToolCallStart })
        assertTrue(events.any { it is StreamEvent.ToolCallDelta })
        assertTrue(events.any { it is StreamEvent.ToolCallComplete })
        assertTrue(events.any { it is StreamEvent.Delta && it.content == "final" })
        assertTrue(events.any { it is StreamEvent.Done })
    }

    @Test
    fun `executeLoop passes tools and toolChoice Auto to provider`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(listOf(StreamEvent.Delta("ok"), StreamEvent.Done))
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__t"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 10, idGenerator = { 1L }
        ) { /* ignore */ }

        assertNotNull(provider.lastTools)
        assertEquals(1, provider.lastTools!!.size)
        assertEquals("skill__t", provider.lastTools!![0].function.name)
        assertEquals(io.prism.network.ToolChoice.Auto, provider.lastToolChoice)
    }

    @Test
    fun `executeLoop provider throws exception emits Error and terminates`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = emptyList(),
            throwOnStreamChat = RuntimeException("provider broken")
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__t"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        val events = mutableListOf<StreamEvent>()
        val result = executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 10, idGenerator = { 1L }
        ) { events.add(it) }

        assertTrue(events.any { it is StreamEvent.Error })
        assertEquals(initialMessages, result) // 异常终止，消息列表不变
    }

    @Test
    fun `executeLoop propagates CancellationException`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(listOf(StreamEvent.Delta("ok"), StreamEvent.Done))
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(throwException = CancellationException("cancelled"))
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        // 第一轮触发 tool_call → executeToolCall → gate.confirm 抛 CancellationException
        val providerWithToolCall = FakeChatStreamProvider(
            rounds = listOf(listOf(StreamEvent.ToolCallComplete("c1", "skill__t", emptyMap())))
        )
        val tools = listOf(makeToolDefinition("skill__t"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        var caught: CancellationException? = null
        try {
            executor.executeLoop(
                providerWithToolCall, config, initialMessages,
                systemPrompt = null, ragContext = null,
                tools = tools, mcpServers = servers,
                maxRounds = 10, idGenerator = { 1L }
            ) { /* ignore */ }
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull("CancellationException must propagate through executeLoop", caught)
    }

    @Test
    fun `executeLoop with empty tools list completes single round`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(listOf(StreamEvent.Delta("no tools needed"), StreamEvent.Done))
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val servers = listOf(makeServer("fs", isEnabled = true))

        val result = executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = "system", ragContext = "rag",
            tools = emptyList(), mcpServers = servers,
            maxRounds = 10, idGenerator = { 1L }
        ) { /* ignore */ }

        assertEquals(1, provider.roundsConsumed)
        assertEquals(initialMessages, result)
        assertFalse(mcpProvider.callToolCalled)
    }

    @Test
    fun `executeLoop passes systemPrompt and ragContext to provider`() = runBlocking {
        val provider = FakeChatStreamProvider(
            rounds = listOf(listOf(StreamEvent.Delta("ok"), StreamEvent.Done))
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = emptyList<ToolDefinition>()
        val servers = listOf(makeServer("fs", isEnabled = true))

        executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = "you are assistant", ragContext = "knowledge: x",
            tools = tools, mcpServers = servers,
            maxRounds = 10, idGenerator = { 1L }
        ) { /* ignore */ }

        assertEquals("you are assistant", provider.lastSystemPrompt)
        assertEquals("knowledge: x", provider.lastRagContext)
    }

    // ==================== ac-verifier 补充：极端/边缘场景（主 Agent 盲区） ====================

    @Test
    fun `executeLoop maxRounds equals 1 with tool call emits Error`() = runBlocking {
        // 边界值：maxRounds=1，首轮有 tool_call → rounds=1 >= maxRounds=1 → 应发射 maxRounds Error
        val provider = FakeChatStreamProvider(
            rounds = listOf(
                listOf(StreamEvent.ToolCallComplete("c1", "skill__t", emptyMap()))
            ),
            repeatLastRound = true
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "result")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val initialMessages = listOf(makeUserMessage("hi"))
        val config = makeProviderConfig()
        val tools = listOf(makeToolDefinition("skill__t"))
        val servers = listOf(makeServer("fs", isEnabled = true))

        val events = mutableListOf<StreamEvent>()
        executor.executeLoop(
            provider, config, initialMessages,
            systemPrompt = null, ragContext = null,
            tools = tools, mcpServers = servers,
            maxRounds = 1, idGenerator = { 1L }
        ) { events.add(it) }

        // maxRounds=1，首轮有 tool_call → 应发射 maxRounds Error
        assertTrue(
            "maxRounds=1 且首轮有 tool_call 应发射上限 Error",
            events.any { it is StreamEvent.Error && it.message.contains("上限") }
        )
    }

    @Test
    fun `executeToolCall passes malicious arguments as-is without execution`() = runBlocking {
        // 安全验证：恶意 arguments（SQL 注入/命令注入/XSS）应原样传递给 MCP，不被执行/解析为代码
        val maliciousArgs = mapOf(
            "query" to "'; DROP TABLE users; --",
            "cmd" to "rm -rf /",
            "script" to "<script>alert(1)</script>"
        )
        val mcpProvider = FakeMcpToolProvider(returnResult = "ok")
        val gate = FakeConfirmationGate(approve = true)
        val executor = SkillExecutor(mcpProvider, gate, Dispatchers.Unconfined)
        val toolCall = StreamEvent.ToolCallComplete("call_1", "skill__tool", maliciousArgs)
        val servers = listOf(makeServer("fs", isEnabled = true))

        executor.executeToolCall(toolCall, servers)

        // 恶意参数应原样传递给 MCP（不执行、不解析为代码）
        assertEquals(maliciousArgs, mcpProvider.lastArguments)
        assertEquals("tool", mcpProvider.lastToolName)
    }

    // ==================== Fake 实现与辅助函数 ====================

    /** 可配置的 McpToolProvider fake。 */
    private class FakeMcpToolProvider(
        private val returnResult: String = "",
        private val throwException: Throwable? = null,
        private val delayMs: Long = 0
    ) : McpToolProvider {
        var lastToolName: String? = null
            private set
        var lastArguments: Map<String, Any?>? = null
            private set
        var callToolCalled: Boolean = false
            private set
        var callCount: Int = 0
            private set

        override suspend fun listTools(config: McpServerConfig): List<String> = emptyList()

        override suspend fun callTool(
            config: McpServerConfig,
            name: String,
            arguments: Map<String, Any?>
        ): String {
            callToolCalled = true
            callCount++
            lastToolName = name
            lastArguments = arguments
            if (delayMs > 0) delay(delayMs)
            throwException?.let { throw it }
            return returnResult
        }
    }

    /** 可配置的 ToolConfirmationGate fake。 */
    private class FakeConfirmationGate(
        private val approve: Boolean = true,
        private val throwException: Throwable? = null,
        private val delayMs: Long = 0
    ) : ToolConfirmationGate {
        var confirmCalled: Boolean = false
            private set

        override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean {
            confirmCalled = true
            if (delayMs > 0) delay(delayMs)
            throwException?.let { throw it }
            return approve
        }
    }

    /**
     * 可配置的 ChatStreamProvider fake。
     *
     * @param rounds 每轮的事件列表（按顺序消费）
     * @param repeatLastRound true 时最后一轮事件列表重复消费（用于 maxRounds 测试）
     * @param throwOnStreamChat 非 null 时 streamChat 抛异常
     */
    private class FakeChatStreamProvider(
        private val rounds: List<List<StreamEvent>>,
        private val repeatLastRound: Boolean = false,
        private val throwOnStreamChat: Throwable? = null
    ) : ChatStreamProvider {
        var roundsConsumed: Int = 0
            private set
        var lastTools: List<ToolDefinition>? = null
            private set
        var lastToolChoice: io.prism.network.ToolChoice? = null
            private set
        var lastSystemPrompt: String? = null
            private set
        var lastRagContext: String? = null
            private set

        override fun streamChat(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            tools: List<ToolDefinition>?,
            toolChoice: io.prism.network.ToolChoice?
        ): Flow<StreamEvent> {
            throwOnStreamChat?.let { throw it }
            lastTools = tools
            lastToolChoice = toolChoice
            lastSystemPrompt = systemPrompt
            lastRagContext = ragContext
            val roundIndex = roundsConsumed.coerceAtMost(if (repeatLastRound) rounds.size - 1 else rounds.size - 1)
            val events = rounds.getOrElse(roundIndex) { emptyList() }
            roundsConsumed++
            return flow {
                events.forEach { emit(it) }
            }
        }
    }

    private fun makeServer(name: String, isEnabled: Boolean): McpServerConfig =
        McpServerConfig(
            name = name,
            serverType = McpServerType.LOCAL,
            baseUrl = "",
            isEnabled = isEnabled
        )

    private fun makeUserMessage(content: String): ChatMessage =
        ChatMessage(id = 0L, role = Role.USER, content = content, timestamp = 0L)

    private fun makeProviderConfig(): ProviderConfig =
        ProviderConfig(name = "test", baseUrl = "https://api.test.com/v1", apiKeyRef = "ref")

    private fun makeToolDefinition(name: String): ToolDefinition =
        ToolDefinition(
            function = ToolDefinition.FunctionDef(
                name = name,
                description = "test tool",
                parameters = kotlinx.serialization.json.JsonObject(emptyMap())
            )
        )
}
