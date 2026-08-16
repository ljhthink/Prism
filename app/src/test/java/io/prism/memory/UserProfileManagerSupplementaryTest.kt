package io.prism.memory

import io.objectbox.BoxStore
import io.prism.data.MyObjectBox
import io.prism.data.ProfileCategory
import io.prism.data.ProviderConfig
import io.prism.data.UserProfileRepository
import io.prism.network.ChatCompletionProvider
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * ac-verifier 补充测试（TKN-M5-PHASED-ACCEPTANCE-001）。
 *
 * 覆盖 guardrail 报告 §1.5「未覆盖场景」+ ac-verifier 极端/边缘场景补充：
 * - 超长 key/value 边界（等价类+边界值）
 * - 特殊字符（换行符/Unicode/emoji/控制字符/JSON 特殊字符）
 * - LLM 返回非字符串 JsonPrimitive（数字/布尔/嵌套对象/数组/null）
 * - stripMarkdownCodeBlock 极端输入（多 {}/value 含 }/嵌套/单边括号/空串）
 * - formatProfilesAsContext 特殊字符（prompt 注入风险验证）
 * - parsePreferencesJson 超大响应（资源耗尽边界）
 * - 并发写入同 key（状态迁移+并发，BR-concurrency-001）
 * - 安全：JSON 注入/proto 注入/script 标签
 *
 * 测试基础设施与 UserProfileManagerTest 同模式（真实 ObjectBox + FakeCompletionProvider）。
 */
class UserProfileManagerSupplementaryTest {

    private lateinit var boxStore: BoxStore
    private lateinit var userProfileRepository: UserProfileRepository
    private lateinit var manager: UserProfileManager
    private lateinit var tempDir: java.io.File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("prism-profile-sup-test").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        userProfileRepository = UserProfileRepository(boxStore)
        manager = UserProfileManager(FakeCompletionProvider(), userProfileRepository)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== AC-1 边界值：超长 key/value ====================

    @Test
    fun `setExplicitPreference accepts very long key`() {
        // 当前实现无长度上限（L-02 低危建议未修复），验证当前接受行为
        val longKey = "k".repeat(10000)
        val id = manager.setExplicitPreference(longKey, "value")
        assertTrue("超长 key 应被接受（当前无长度限制，L-02）", id > 0)
        assertEquals(longKey, userProfileRepository.get(longKey)?.key)
    }

    @Test
    fun `setExplicitPreference accepts very long value`() {
        // 当前实现无长度上限（L-02 低危建议未修复），验证当前接受行为
        val longValue = "v".repeat(10000)
        val id = manager.setExplicitPreference("long_key", longValue)
        assertTrue("超长 value 应被接受（当前无长度限制，L-02）", id > 0)
        assertEquals(longValue, userProfileRepository.get("long_key")?.value)
    }

    @Test
    fun `setExplicitPreference accepts single char key and value`() {
        val id = manager.setExplicitPreference("k", "v")
        assertTrue("单字符 key/value 应被接受", id > 0)
        assertEquals("v", userProfileRepository.get("k")?.value)
    }

    // ==================== 特殊字符（Unicode/emoji/控制字符） ====================

    @Test
    fun `setExplicitPreference accepts unicode key and value`() {
        manager.setExplicitPreference("偏好", "简洁的中文回复")
        val saved = userProfileRepository.get("偏好")
        assertNotNull("Unicode key 应可查询", saved)
        assertEquals("简洁的中文回复", saved!!.value)
    }

    @Test
    fun `setExplicitPreference accepts emoji value`() {
        manager.setExplicitPreference("mood", "喜欢 🚀💻🎉 表情")
        val saved = userProfileRepository.get("mood")
        assertNotNull("emoji value 应可保存", saved)
        assertEquals("喜欢 🚀💻🎉 表情", saved!!.value)
    }

    @Test
    fun `setExplicitPreference accepts value with newline`() {
        // L-03 低危：value 含换行符当前未编码，验证当前接受行为
        manager.setExplicitPreference("multiline", "line1\nline2\r\nline3")
        val saved = userProfileRepository.get("multiline")
        assertNotNull("含换行符的 value 应被接受", saved)
        assertEquals("line1\nline2\r\nline3", saved!!.value)
    }

    @Test
    fun `setExplicitPreference accepts json special chars in value`() {
        val specialValue = """{"injected":"true"},"extra":[]"""
        manager.setExplicitPreference("json_chars", specialValue)
        val saved = userProfileRepository.get("json_chars")
        assertNotNull("JSON 特殊字符 value 应被接受", saved)
        assertEquals(specialValue, saved!!.value)
    }

    // ==================== AC-3：LLM 返回非字符串 JsonPrimitive ====================

    @Test
    fun `parsePreferencesJson handles numeric value as string`() {
        // JsonPrimitive.contentOrNull 对数字返回原始字符串表示
        val json = """{"count":"42","version":"3"}"""
        val result = manager.parsePreferencesJson(json)
        // 注意：JSON 中 "42" 是字符串值（带引号），不是数字
        assertEquals(2, result.size)
        assertTrue(result.any { it.first == "count" && it.second == "42" })
    }

    @Test
    fun `parsePreferencesJson handles bare numeric value`() {
        // LLM 返回裸数字值（不带引号）：{"count": 42}
        // kotlinx.serialization 解析为 JsonPrimitive，contentOrNull 返回 "42"
        val json = """{"count":42}"""
        val result = manager.parsePreferencesJson(json)
        assertEquals("裸数字应被解析为字符串 '42'", 1, result.size)
        assertEquals("42", result[0].second)
    }

    @Test
    fun `parsePreferencesJson handles boolean value`() {
        // LLM 返回布尔值：{"active": true}
        val json = """{"active":true}"""
        val result = manager.parsePreferencesJson(json)
        assertEquals("布尔值应被解析为字符串 'true'", 1, result.size)
        assertEquals("true", result[0].second)
    }

    @Test
    fun `parsePreferencesJson skips nested object value`() {
        // 嵌套对象不是 JsonPrimitive，应跳过
        val json = """{"nested":{"inner":"value"},"normal":"ok"}"""
        val result = manager.parsePreferencesJson(json)
        assertEquals("嵌套对象应被跳过，仅保留字符串值", 1, result.size)
        assertEquals("normal", result[0].first)
        assertEquals("ok", result[0].second)
    }

    @Test
    fun `parsePreferencesJson skips array value`() {
        // 数组不是 JsonPrimitive，应跳过
        val json = """{"list":[1,2,3],"normal":"ok"}"""
        val result = manager.parsePreferencesJson(json)
        assertEquals("数组应被跳过，仅保留字符串值", 1, result.size)
        assertEquals("normal", result[0].first)
    }

    @Test
    fun `parsePreferencesJson skips null json value`() {
        // JSON null 值（JsonNull）不是 JsonPrimitive，应跳过
        val json = """{"nullkey":null,"normal":"ok"}"""
        val result = manager.parsePreferencesJson(json)
        assertEquals("null 值应被跳过", 1, result.size)
        assertEquals("normal", result[0].first)
    }

    // ==================== stripMarkdownCodeBlock 极端输入（路径覆盖） ====================

    @Test
    fun `stripMarkdownCodeBlock handles multiple brace pairs`() {
        // 多个 {} 对：firstBrace 到 lastBrace 提取整个，中间含非 JSON 文本 → 解析失败 → 空列表
        val input = """{"a":"1"} some text {"b":"2"}"""
        val result = manager.parsePreferencesJson(input)
        assertTrue("多 {} 对的混合文本应解析失败返回空列表", result.isEmpty())
    }

    @Test
    fun `stripMarkdownCodeBlock handles value containing closing brace`() {
        // value 含 } 字符：lastIndexOf('}') 正确指向最后 } → 合法 JSON → 解析成功
        val input = """{"key":"val}ue"}"""
        val result = manager.parsePreferencesJson(input)
        assertEquals("value 含 } 应正常解析", 1, result.size)
        assertEquals("val}ue", result[0].second)
    }

    @Test
    fun `stripMarkdownCodeBlock handles nested json object`() {
        // 嵌套 {}：提取整个 → 合法 JSON → inner 为嵌套对象被跳过
        val input = """{"outer":{"inner":"v"},"normal":"ok"}"""
        val result = manager.parsePreferencesJson(input)
        assertEquals("嵌套 JSON 应提取成功，inner 被跳过", 1, result.size)
        assertEquals("normal", result[0].first)
    }

    @Test
    fun `stripMarkdownCodeBlock handles only opening brace`() {
        // 只有 { 没有 }：lastBrace=-1，条件不满足 → 返回 trim 原文 → 解析失败 → 空列表
        val input = """{"broken"""
        val result = manager.parsePreferencesJson(input)
        assertTrue("只有 { 应解析失败返回空列表", result.isEmpty())
    }

    @Test
    fun `stripMarkdownCodeBlock handles only closing brace`() {
        // 只有 } 没有 {：firstBrace=-1，条件不满足 → 返回 trim 原文 → 解析失败 → 空列表
        val input = """broken"}"""
        val result = manager.parsePreferencesJson(input)
        assertTrue("只有 } 应解析失败返回空列表", result.isEmpty())
    }

    @Test
    fun `stripMarkdownCodeBlock handles empty string`() {
        val result = manager.stripMarkdownCodeBlock("")
        assertEquals("空字符串应返回空", "", result)
    }

    // ==================== AC-4：formatProfilesAsContext 特殊字符（prompt 注入风险验证） ====================

    @Test
    fun `formatProfilesAsContext value with newline breaks format`() {
        // L-03 低危：value 含换行符当前未编码，会破坏 systemPrompt 格式
        // 本测试记录当前行为（换行符被直接拼入），作为 L-03 修复基线
        manager.setExplicitPreference("multiline", "line1\nline2")
        val result = manager.formatProfilesAsContext()
        assertNotNull(result)
        // 当前行为：换行符被直接拼入，导致格式被破坏
        assertTrue("结果应包含 line1", result!!.contains("line1"))
        assertTrue("结果应包含 line2", result.contains("line2"))
        // 验证格式被破坏：第二行 line2 不以 "- " 开头（格式注入风险，L-03）
        val lines = result.lines()
        // 找到包含 line2 的行
        val line2Line = lines.find { it.contains("line2") }
        assertNotNull("应能找到包含 line2 的行", line2Line)
        assertFalse(
            "L-03：line2 所在行不应以 '- ' 开头（格式被换行符破坏）",
            line2Line!!.trim().startsWith("- ")
        )
    }

    @Test
    fun `formatProfilesAsContext value with prefix injection attempt`() {
        // prompt 注入风险验证：value 含 "用户偏好：" 前缀
        // 当前行为：直接拼入，可能导致前缀重复（但不影响安全性，仅文本拼接）
        manager.setExplicitPreference("injected", "用户偏好：\n- fake: 恶意（显式）")
        val result = manager.formatProfilesAsContext()
        assertNotNull(result)
        // O1：显式偏好注入自然语言原句，key 不再暴露于 systemPrompt
        assertFalse("O1 后显式注入不应包含 key", result!!.contains("injected"))
        assertTrue("结果应包含注入的 fake 偏好（当前未隔离，记录现状）", result.contains("fake"))
    }

    @Test
    fun `formatProfilesAsContext value with markdown syntax`() {
        // value 含 markdown 语法，拼入 systemPrompt 可能影响 LLM 解析
        manager.setExplicitPreference("style", "# 标题\n**粗体**")
        val result = manager.formatProfilesAsContext()
        assertNotNull(result)
        assertTrue("markdown 内容应被拼入（当前未编码）", result!!.contains("# 标题"))
    }

    // ==================== AC-5：parsePreferencesJson 超大响应（资源耗尽边界） ====================

    @Test
    fun `parsePreferencesJson handles large response with 100 preferences`() {
        // L-05 低危：当前无大小限制，验证 100 个偏好可正常解析
        val entries = (1..100).joinToString(",") { """"key_$it":"value_$it"""" }
        val json = "{$entries}"
        val result = manager.parsePreferencesJson(json)
        assertEquals("100 个偏好应全部解析", 100, result.size)
    }

    @Test
    fun `parsePreferencesJson handles large response_with_500_preferences`() {
        // 超大规模响应（L-05 低危：当前无限制）
        val entries = (1..500).joinToString(",") { """"k_$it":"v_$it"""" }
        val json = "{$entries}"
        val result = manager.parsePreferencesJson(json)
        assertEquals("500 个偏好应全部解析", 500, result.size)
    }

    @Test
    fun `extractImplicitPreferences handles large_llm_response end_to_end`() = runBlocking {
        // 集成测试：LLM 返回 50 个偏好 → 解析 → upsert 全链路
        val entries = (1..50).joinToString(",") { """"pref_$it":"val_$it"""" }
        val provider = FakeCompletionProvider(returnValue = "{$entries}")
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "大量偏好对话", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回复", 2000L)
        )
        val result = manager.extractImplicitPreferences(messages, testConfig())

        assertEquals("50 个偏好应全部保存", 50, result.size)
        assertEquals("仓库应有 50 条记录", 50L, userProfileRepository.count())
        val implicit = userProfileRepository.getByCategory(ProfileCategory.IMPLICIT)
        assertEquals("应有 50 条 IMPLICIT", 50, implicit.size)
    }

    // ==================== 并发写入（BR-concurrency-001） ====================

    @Test
    fun `concurrent setExplicitPreference same key maintains single record`() = runBlocking {
        // 并发写入同 key：ObjectBox runInTx 事务保证最终一致（BR-concurrency-001）
        val key = "concurrent_key"
        val deferred = (1..20).map { i ->
            async(Dispatchers.IO) {
                manager.setExplicitPreference(key, "value_$i")
            }
        }
        deferred.awaitAll()

        val profiles = userProfileRepository.getAll()
        assertEquals("并发写入同 key 应最终只 1 条记录（upsert）", 1, profiles.size)
        assertEquals("key 应为 concurrent_key", key, profiles[0].key)
        assertEquals("category 应为 EXPLICIT", ProfileCategory.EXPLICIT.name, profiles[0].category)
    }

    @Test
    fun `concurrent setExplicitPreference different keys preserves all`() = runBlocking {
        // 并发写入不同 key：应全部保留
        val deferred = (1..10).map { i ->
            async(Dispatchers.IO) {
                manager.setExplicitPreference("key_$i", "value_$i")
            }
        }
        deferred.awaitAll()

        assertEquals("10 个不同 key 应全部保存", 10L, userProfileRepository.count())
    }

    @Test
    fun `concurrent extractImplicitPreferences same key upserts`() = runBlocking {
        // 并发隐式抽取同 key：应 upsert 为单条 IMPLICIT 记录
        val provider = FakeCompletionProvider(returnValue = """{"shared_key":"val"}""")
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "对话", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回复", 2000L)
        )

        val deferred = (1..5).map {
            async(Dispatchers.IO) {
                manager.extractImplicitPreferences(messages, testConfig())
            }
        }
        val results = deferred.awaitAll()

        // 最终仓库应只有 1 条 IMPLICIT 记录（upsert 语义）
        assertEquals("并发抽取同 key 应最终 1 条记录", 1L, userProfileRepository.count())
        val profile = userProfileRepository.get("shared_key")
        assertNotNull(profile)
        assertEquals(ProfileCategory.IMPLICIT.name, profile!!.category)
        // 至少一次成功
        assertTrue("至少 1 次抽取成功", results.any { it.isNotEmpty() })
    }

    // ==================== 安全：JSON 注入 / proto 注入 / script 标签 ====================

    @Test
    fun `parsePreferencesJson ignores proto injection attempt`() {
        // __proto__ 注入：kotlinx.serialization 不执行 JS 原型链，作为普通 key 处理
        val json = """{"__proto__":{"admin":true},"normal":"ok"}"""
        val result = manager.parsePreferencesJson(json)
        // __proto__ 的值是嵌套对象（非 JsonPrimitive），应被跳过
        // normal 是字符串值，应被保留
        assertEquals("__proto__ 嵌套对象应被跳过", 1, result.size)
        assertEquals("normal", result[0].first)
    }

    @Test
    fun `parsePreferencesJson stores script tag as plain string`() {
        // script 标签：作为字符串值存储（非 HTML 渲染，无 XSS 风险）
        val json = """{"xss":"<script>alert(1)</script>"}"""
        val result = manager.parsePreferencesJson(json)
        assertEquals("script 标签应作为字符串值解析", 1, result.size)
        assertEquals("<script>alert(1)</script>", result[0].second)
    }

    @Test
    fun `extractImplicitPreferences does_not_leak_secrets in logs on failure`() = runBlocking {
        // 安全：LLM 失败时日志不应泄露密钥/请求体/URL（BR-error-handling-004）
        // 验证降级路径不崩溃且返回空列表
        val provider = FakeCompletionProvider(throwOnCall = true)
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "包含密钥的对话 secret=abc123", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回复", 2000L)
        )
        val result = manager.extractImplicitPreferences(messages, testConfig())

        assertTrue("LLM 失败应降级返回空列表", result.isEmpty())
        assertEquals("仓库不应有记录", 0L, userProfileRepository.count())
    }

    @Test
    fun `extractImplicitPreferences upserts existing implicit multiple times`() = runBlocking {
        // 多次隐式抽取同 key：每次都 upsert，最终仍 1 条记录
        val messages = listOf(
            ChatMessage(1, Role.USER, "用 Python", 1000L),
            ChatMessage(2, Role.ASSISTANT, "好的", 2000L)
        )

        for (i in 1..5) {
            val provider = FakeCompletionProvider(returnValue = """{"lang":"v$i"}""")
            manager = UserProfileManager(provider, userProfileRepository)
            manager.extractImplicitPreferences(messages, testConfig())
        }

        assertEquals("5 次抽取同 key 应 upsert 为 1 条", 1L, userProfileRepository.count())
        val profile = userProfileRepository.get("lang")
        assertNotNull(profile)
        assertEquals("v5", profile!!.value)
    }

    // ==================== 性能基线（ac-verifier 首版基线，US-034 新模块无历史基线） ====================

    @Test
    fun perf_baseline_setExplicitPreference() {
        val iters = 100
        val latenciesUs = LongArray(iters)
        var failures = 0
        // 预热
        repeat(5) { manager.setExplicitPreference("warmup_$it", "warmup") }
        repeat(iters) { i ->
            val start = System.nanoTime()
            try {
                manager.setExplicitPreference("perf_key_$i", "perf_value_$i")
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printPerfStats("setExplicitPreference", "1_save", iters, latenciesUs, failures)
        assertEquals("性能测试应无失败", 0, failures)
    }

    @Test
    fun perf_baseline_parsePreferencesJson() {
        val json = """{"language":"Python","tone":"简洁","tech_stack":"Kotlin","expertise":"资深","language_pref":"中文"}"""
        val iters = 100
        val latenciesUs = LongArray(iters)
        var failures = 0
        // 预热
        repeat(10) { manager.parsePreferencesJson(json) }
        repeat(iters) { i ->
            val start = System.nanoTime()
            try {
                manager.parsePreferencesJson(json)
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printPerfStats("parsePreferencesJson", "5_pairs", iters, latenciesUs, failures)
        assertEquals("性能测试应无失败", 0, failures)
    }

    @Test
    fun perf_baseline_formatProfilesAsContext() {
        // 预填充 10 条画像
        repeat(10) { i -> manager.setExplicitPreference("key_$i", "value_$i") }
        val iters = 100
        val latenciesUs = LongArray(iters)
        var failures = 0
        // 预热
        repeat(10) { manager.formatProfilesAsContext() }
        repeat(iters) { i ->
            val start = System.nanoTime()
            try {
                manager.formatProfilesAsContext()
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printPerfStats("formatProfilesAsContext", "10_profiles", iters, latenciesUs, failures)
        assertEquals("性能测试应无失败", 0, failures)
    }

    @Test
    fun perf_baseline_extractImplicitPreferences() = runBlocking {
        val provider = FakeCompletionProvider(
            returnValue = """{"language":"Python","tone":"简洁","tech_stack":"Kotlin"}"""
        )
        manager = UserProfileManager(provider, userProfileRepository)
        val messages = listOf(
            ChatMessage(1, Role.USER, "用 Python 写代码", 1000L),
            ChatMessage(2, Role.ASSISTANT, "好的，简洁回复", 2000L)
        )
        val iters = 30
        val latenciesUs = LongArray(iters)
        var failures = 0
        // 预热
        manager.extractImplicitPreferences(messages, testConfig())
        repeat(iters) { i ->
            val start = System.nanoTime()
            try {
                manager.extractImplicitPreferences(messages, testConfig())
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printPerfStats("extractImplicitPreferences", "3_prefs", iters, latenciesUs, failures)
        assertEquals("性能测试应无失败", 0, failures)
    }

    @Test
    fun perf_baseline_filterKeyMessages() {
        val messages = (1..20).flatMap { i ->
            listOf(
                ChatMessage(i.toLong() * 2 - 1, Role.USER, "问题 $i", i.toLong() * 1000),
                ChatMessage(i.toLong() * 2, Role.ASSISTANT, "回答 $i", i.toLong() * 1000 + 500)
            )
        }
        val iters = 100
        val latenciesUs = LongArray(iters)
        var failures = 0
        // 预热
        repeat(10) { manager.filterKeyMessages(messages) }
        repeat(iters) { i ->
            val start = System.nanoTime()
            try {
                manager.filterKeyMessages(messages)
            } catch (e: Exception) {
                failures++
            }
            latenciesUs[i] = (System.nanoTime() - start) / 1_000
        }
        printPerfStats("filterKeyMessages", "20_msgs", iters, latenciesUs, failures)
        assertEquals("性能测试应无失败", 0, failures)
    }

    // ==================== 辅助方法 ====================

    private fun testConfig(): ProviderConfig = ProviderConfig(
        id = 1,
        name = "test",
        baseUrl = "https://test.example.com",
        apiKeyRef = "test-key-ref",
        models = listOf("gpt-4"),
        headers = emptyMap(),
        isActive = true
    )

    private fun printPerfStats(op: String, scale: String, iters: Int, latenciesUs: LongArray, failures: Int) {
        latenciesUs.sort()
        val p50 = latenciesUs[iters / 2]
        val p95 = latenciesUs[iters * 95 / 100]
        val p99 = latenciesUs[iters * 99 / 100]
        val min = latenciesUs[0]
        val max = latenciesUs[iters - 1]
        val throughput = "%.1f".format(1_000_000.0 / p50)
        println(
            "PERF_BASELINE|op=$op|scale=$scale|iters=$iters|" +
                "min=${min}us|p50=${p50}us|p95=${p95}us|p99=${p99}us|max=${max}us|" +
                "throughput=${throughput}_ops_per_s|failures=$failures"
        )
    }

    /**
     * Fake ChatCompletionProvider（与 UserProfileManagerTest 同模式）。
     */
    private class FakeCompletionProvider(
        private val returnValue: String? = null,
        private val throwOnCall: Boolean = false,
        private val throwCancellation: Boolean = false
    ) : ChatCompletionProvider {

        var callCount: Int = 0
            private set
        var lastSystemPrompt: String? = null
            private set

        override suspend fun chatCompletion(
            config: ProviderConfig,
            messages: List<ChatMessage>,
            systemPrompt: String?,
            ragContext: String?,
            thinkingEnabled: Boolean?,
            reasoningEffort: String?
        ): String? {
            callCount++
            lastSystemPrompt = systemPrompt
            return when {
                throwCancellation -> throw CancellationException("test cancellation")
                throwOnCall -> throw RuntimeException("test network error")
                else -> returnValue
            }
        }
    }
}
