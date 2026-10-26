package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QqProactiveComposer] 纯函数单元测试（ADR-063；US-3108 AC-3）。
 *
 * HTTP 注入为 fake → 全 JVM 可测；覆盖请求构建、响应解析、降级路径。
 */
class QqProactiveComposerTest {

    private class FakeHttp(val response: String?, val error: Exception? = null) {
        var lastUrl: String? = null
        var lastBody: String? = null
        suspend fun post(url: String, headers: Map<String, String>, bodyJson: String): String {
            lastUrl = url
            lastBody = bodyJson
            error?.let { throw it }
            return response ?: error("empty")
        }
    }

    @Test
    fun `请求体含模型与系统提示且非流式`() {
        val body = QqProactiveComposer.buildRequestJson("deepseek-chat", "你是测试人设", "2026-10-19T20:00")
        assertTrue(body.contains("\"model\":\"deepseek-chat\""))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("你是测试人设"))
        assertTrue(body.contains("现在时间"))
    }

    @Test
    fun `解析标准 OpenAI 响应`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"今天加班到好晚，你吃了吗"}}]}"""
        assertEquals("今天加班到好晚，你吃了吗", QqProactiveComposer.parseContent(body))
    }

    @Test
    fun `解析非法响应返回 null 不抛异常`() {
        assertNull(QqProactiveComposer.parseContent("not json"))
        assertNull(QqProactiveComposer.parseContent("""{"error":"rate limited"}"""))
        assertNull(QqProactiveComposer.parseContent("""{"choices":[]}"""))
    }

    @Test
    fun `正常路径返回生成的消息`() {
        val fake = FakeHttp("""{"choices":[{"message":{"content":"在忙吗？刚看到个好笑的事"}}]}""")
        val composer = QqProactiveComposer { u, h, b -> fake.post(u, h, b) }
        val msg = kotlinx.coroutines.runBlocking {
            composer.compose(
                baseUrl = "https://api.example.com/v1",
                apiKey = "sk-test",
                model = "test-model",
                personaSystemPrompt = "你是昔涟",
                companionAddress = null,
                now = java.time.LocalDateTime.of(2026, 10, 19, 20, 30)
            )
        }
        assertEquals("在忙吗？刚看到个好笑的事", msg)
        assertTrue(fake.lastUrl!!.endsWith("/chat/completions"))
        assertTrue(fake.lastBody!!.contains("你是昔涟"))
        assertTrue(fake.lastBody!!.contains("Bearer") == false) // key 在头不在体
    }

    @Test
    fun `HTTP 异常时返回 null 不抛出`() {
        val fake = FakeHttp(response = null, error = java.net.SocketTimeoutException("timeout"))
        val composer = QqProactiveComposer { u, h, b -> fake.post(u, h, b) }
        val msg = kotlinx.coroutines.runBlocking {
            composer.compose("https://api.example.com/v1", "k", "m", null, null)
        }
        assertNull(msg)
    }

    @Test
    fun `composeReply 将收到消息带入请求体并返回回复（US-3109）`() {
        val fake = FakeHttp("""{"choices":[{"message":{"content":"在的，刚在忙"}}]}""")
        val composer = QqProactiveComposer { u, h, b -> fake.post(u, h, b) }
        val reply = kotlinx.coroutines.runBlocking {
            composer.composeReply(
                baseUrl = "https://api.example.com/v1",
                apiKey = "sk-test",
                model = "m",
                personaSystemPrompt = "你是昔涟",
                companionAddress = null,
                incoming = "在吗？"
            )
        }
        assertEquals("在的，刚在忙", reply)
        assertTrue("请求体必须带入收到的消息", fake.lastBody!!.contains("在吗？"))
        assertTrue(fake.lastBody!!.contains("你是昔涟"))
    }

    @Test
    fun `composeReply 参数缺失返回 null（不发请求）`() {
        var called = false
        val composer = QqProactiveComposer { _, _, _ -> called = true; "" }
        val reply = kotlinx.coroutines.runBlocking {
            composer.composeReply("", "k", "m", null, null, "hi")
        }
        assertNull(reply)
        assertTrue(!called)
    }

    // ==================== US-3112：上下文与记忆注入 ====================

    @Test
    fun `回复上下文包含最近对话与当前消息（由旧到新）`() {
        val context = QqProactiveComposer.buildReplyUserContext(
            history = listOf(
                io.prism.qq.channel.QqMessage(null, true, "你今天几点下班", 0),
                io.prism.qq.channel.QqMessage(null, false, "大概七点", 0)
            ),
            incoming = "那要不要一起吃饭"
        )
        assertTrue(context.contains("最近对话（由旧到新）"))
        assertTrue(context.contains("对方：你今天几点下班"))
        assertTrue(context.contains("我：大概七点"))
        assertTrue(context.contains("对方刚发来：「那要不要一起吃饭」"))
        // 顺序：历史在前，当前消息在后
        assertTrue(context.indexOf("你今天几点下班") < context.indexOf("那要不要一起吃饭"))
    }

    @Test
    fun `回复上下文对超长单条与超量历史做截断`() {
        val longText = "啊".repeat(500)
        val many = (1..40).map {
            io.prism.qq.channel.QqMessage(null, it % 2 == 0, longText, 0)
        }
        val context = QqProactiveComposer.buildReplyUserContext(many, "收到")
        val lineCount = context.lines().count { it.startsWith("对方：") || it.startsWith("我：") }
        assertEquals(QqProactiveComposer.MAX_HISTORY_IN_PROMPT, lineCount)
        assertTrue(
            "单条必须截断到上限",
            context.contains("啊".repeat(QqProactiveComposer.MAX_CHARS_PER_MESSAGE)) &&
                !context.contains("啊".repeat(QqProactiveComposer.MAX_CHARS_PER_MESSAGE + 1))
        )
    }

    @Test
    fun `记忆 section 注入 system 提示`() {
        val fake = FakeHttp("""{"choices":[{"message":{"content":"好呀"}}]}""")
        val composer = QqProactiveComposer { u, h, b -> fake.post(u, h, b) }
        kotlinx.coroutines.runBlocking {
            composer.composeReply(
                baseUrl = "https://api.example.com/v1",
                apiKey = "k",
                model = "m",
                personaSystemPrompt = "你是昔涟",
                companionAddress = null,
                incoming = "在吗",
                memorySection = "用户喜欢在晚上聊天"
            )
        }
        assertTrue(fake.lastBody!!.contains("用户喜欢在晚上聊天"))
        assertTrue(fake.lastBody!!.contains("关于用户的相关记忆"))
    }

    @Test
    fun `远端明文 http 端点被拒且不发请求（H-06）`() {
        var called = false
        val composer = QqProactiveComposer { _, _, _ -> called = true; "{}" }
        val result = kotlinx.coroutines.runBlocking {
            composer.composeReply(
                baseUrl = "http://api.example.com/v1",
                apiKey = "sk-test",
                model = "m",
                personaSystemPrompt = null,
                companionAddress = null,
                incoming = "在吗"
            )
        }
        assertNull(result)
        assertTrue("不得发起明文请求", !called)
    }

    @Test
    fun `空历史时只带当前消息`() {
        val context = QqProactiveComposer.buildReplyUserContext(emptyList(), "在吗")
        assertEquals("对方刚发来：「在吗」", context)
    }
}
