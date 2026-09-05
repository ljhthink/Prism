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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * UserProfileManager 单元测试（US-034 AC-1 ~ AC-6）。
 *
 * 测试覆盖：
 * - AC-1：显式偏好设定（setExplicitPreference）
 * - AC-2：隐式偏好抽取（extractImplicitPreferences）
 * - AC-3：非流式请求 + 结构化 JSON（parsePreferencesJson）
 * - AC-4：画像注入 systemPrompt（formatProfilesAsContext）
 * - AC-5：抽取失败降级 / upsert / 显式>隐式优先级
 * - AC-6：纯函数（filterKeyMessages / shouldSkipImplicitUpsert / stripMarkdownCodeBlock）
 *
 * 测试基础设施：
 * - FakeCompletionProvider：可控的非流式 LLM 响应（含失败/取消模拟）
 * - 真实 ObjectBox BoxStore（temp 目录）：与 UserProfileRepositoryTest 同模式
 */
class UserProfileManagerTest {

    private lateinit var boxStore: BoxStore
    private lateinit var userProfileRepository: UserProfileRepository
    private lateinit var manager: UserProfileManager
    private lateinit var tempDir: java.io.File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("prism-profile-test").toFile()
        boxStore = MyObjectBox.builder().directory(tempDir).build()
        userProfileRepository = UserProfileRepository(boxStore)
        manager = UserProfileManager(FakeCompletionProvider(), userProfileRepository)
    }

    @After
    fun tearDown() {
        boxStore.close()
        tempDir.deleteRecursively()
    }

    // ==================== AC-1：显式偏好设定 ====================

    @Test
    fun setExplicitPreference_saves_with_explicit_category() {
        val id = manager.setExplicitPreference("tone", "简洁")
        assertTrue("应返回有效 id", id > 0)

        val saved = userProfileRepository.get("tone")
        assertNotNull("应能查询到记录", saved)
        assertEquals("value 应为'简洁'", "简洁", saved!!.value)
        assertEquals("category 应为 EXPLICIT", ProfileCategory.EXPLICIT.name, saved.category)
    }

    @Test
    fun setExplicitPreference_upserts_existing_key() {
        manager.setExplicitPreference("language", "中文")
        manager.setExplicitPreference("language", "English")

        val profiles = userProfileRepository.getAll()
        assertEquals("应只有 1 条记录（upsert）", 1, profiles.size)
        assertEquals("value 应更新为 English", "English", profiles[0].value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setExplicitPreference_throws_on_blank_key() {
        manager.setExplicitPreference("", "value")
    }

    @Test(expected = IllegalArgumentException::class)
    fun setExplicitPreference_throws_on_blank_value() {
        manager.setExplicitPreference("key", "")
    }

    @Test
    fun setExplicitPreference_updates_timestamp() {
        manager.setExplicitPreference("tone", "简洁")
        val firstTimestamp = userProfileRepository.get("tone")!!.updatedAt

        Thread.sleep(10) // 确保时间戳不同
        manager.setExplicitPreference("tone", "详细")
        val secondTimestamp = userProfileRepository.get("tone")!!.updatedAt

        assertTrue("updatedAt 应刷新", secondTimestamp > firstTimestamp)
    }

    // ==================== AC-2：隐式偏好抽取 ====================

    @Test
    fun extractImplicitPreferences_returns_empty_for_empty_messages() = runBlocking {
        val provider = FakeCompletionProvider(returnValue = """{"language":"Python"}""")
        manager = UserProfileManager(provider, userProfileRepository)

        val result = manager.extractImplicitPreferences(emptyList(), testConfig())
        assertTrue("空消息应返回空列表", result.isEmpty())
        assertEquals("LLM 不应被调用", 0, provider.callCount)
    }

    @Test
    fun extractImplicitPreferences_returns_empty_for_only_tool_messages() = runBlocking {
        val provider = FakeCompletionProvider(returnValue = """{"language":"Python"}""")
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.TOOL, "tool result", 1000L)
        )
        val result = manager.extractImplicitPreferences(messages, testConfig())
        assertTrue("仅 TOOL 消息应返回空列表", result.isEmpty())
        assertEquals("LLM 不应被调用（过滤后为空）", 0, provider.callCount)
    }

    @Test
    fun extractImplicitPreferences_saves_implicit_preferences() = runBlocking {
        val provider = FakeCompletionProvider(returnValue = """{"language":"Python","tone":"简洁"}""")
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "帮我用 Python 写个脚本", 1000L),
            ChatMessage(2, Role.ASSISTANT, "好的，简洁地回复...", 2000L)
        )
        val result = manager.extractImplicitPreferences(messages, testConfig())

        assertEquals("应保存 2 个隐式偏好", 2, result.size)
        assertEquals("仓库应有 2 条记录", 2L, userProfileRepository.count())

        val profiles = userProfileRepository.getByCategory(ProfileCategory.IMPLICIT)
        assertEquals("应有 2 条 IMPLICIT 记录", 2, profiles.size)
    }

    @Test
    fun extractImplicitPreferences_calls_llm_with_extraction_prompt() = runBlocking {
        val provider = FakeCompletionProvider(returnValue = """{}""")
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "问题", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答", 2000L)
        )
        manager.extractImplicitPreferences(messages, testConfig())

        assertEquals("LLM 应被调用 1 次", 1, provider.callCount)
        assertNotNull("systemPrompt 应为抽取 prompt", provider.lastSystemPrompt)
        assertTrue("prompt 应包含'偏好抽取'", provider.lastSystemPrompt!!.contains("偏好抽取"))
    }

    // ==================== AC-3：非流式请求 + 结构化 JSON ====================

    @Test
    fun parsePreferencesJson_parses_valid_json() {
        val json = """{"language":"Python","tone":"简洁"}"""
        val result = manager.parsePreferencesJson(json)

        assertEquals("应解析 2 个 key-value", 2, result.size)
        assertTrue("应包含 language", result.any { it.first == "language" && it.second == "Python" })
        assertTrue("应包含 tone", result.any { it.first == "tone" && it.second == "简洁" })
    }

    @Test
    fun parsePreferencesJson_returns_empty_for_null() {
        val result = manager.parsePreferencesJson(null)
        assertTrue("null 应返回空列表", result.isEmpty())
    }

    @Test
    fun parsePreferencesJson_returns_empty_for_blank() {
        val result = manager.parsePreferencesJson("   ")
        assertTrue("空白应返回空列表", result.isEmpty())
    }

    @Test
    fun parsePreferencesJson_returns_empty_for_invalid_json() {
        val result = manager.parsePreferencesJson("not a json")
        assertTrue("非法 JSON 应返回空列表", result.isEmpty())
    }

    @Test
    fun parsePreferencesJson_strips_markdown_code_block() {
        val json = "```json\n{\"language\":\"Python\"}\n```"
        val result = manager.parsePreferencesJson(json)
        assertEquals("应剥离 markdown 代码块后解析", 1, result.size)
        assertEquals("language", result[0].first)
        assertEquals("Python", result[0].second)
    }

    @Test
    fun parsePreferencesJson_strips_bare_code_block() {
        val json = "```\n{\"tone\":\"简洁\"}\n```"
        val result = manager.parsePreferencesJson(json)
        assertEquals("应剥离无语言标记的代码块", 1, result.size)
    }

    @Test
    fun parsePreferencesJson_filters_blank_value() {
        val json = """{"language":"Python","empty_key":""}"""
        val result = manager.parsePreferencesJson(json)
        assertEquals("应过滤空 value", 1, result.size)
        assertEquals("language", result[0].first)
    }

    @Test
    fun parsePreferencesJson_handles_empty_json_object() {
        val result = manager.parsePreferencesJson("{}")
        assertTrue("空 JSON 对象应返回空列表", result.isEmpty())
    }

    @Test
    fun parsePreferencesJson_handles_json_with_explanation() {
        // LLM 可能在 JSON 前后加解释文本
        val json = "根据对话，用户偏好如下：\n{\"language\":\"Python\"}\n以上是抽取结果。"
        val result = manager.parsePreferencesJson(json)
        assertEquals("应从混合文本中提取 JSON", 1, result.size)
    }

    // ==================== AC-4：画像注入 systemPrompt ====================

    @Test
    fun formatProfilesAsContext_returns_null_when_no_profiles() {
        val result = manager.formatProfilesAsContext()
        assertNull("无画像应返回 null", result)
    }

    @Test
    fun formatProfilesAsContext_returns_formatted_text_with_prefix() {
        // O1：显式偏好注入自然语言原句（不含 key: 前缀）
        manager.setExplicitPreference("language", "请用中文回答")
        manager.setExplicitPreference("tone", "我喜欢简洁的回复")

        val result = manager.formatProfilesAsContext()
        assertNotNull("应返回格式化文本", result)
        // v1 批次19：L3 section 记忆化改造——前缀改为「关于用户的长期记忆」+ 引用指令
        assertTrue("应以记忆化前缀开头", result!!.startsWith("关于用户的长期记忆"))
        assertTrue("前缀应含身份类问题引用指令", result.contains("不要声称没有记忆"))
        assertTrue("显式偏好应注入原句", result.contains("请用中文回答（显式）"))
        assertTrue("显式偏好应注入原句", result.contains("我喜欢简洁的回复（显式）"))
    }

    @Test
    fun formatProfilesAsContext_includes_category_label() {
        manager.setExplicitPreference("language", "中文")

        val provider = FakeCompletionProvider(returnValue = """{"tech_stack":"Python"}""")
        manager = UserProfileManager(provider, userProfileRepository)
        runBlocking {
            val messages = listOf(
                ChatMessage(1, Role.USER, "用 Python 写", 1000L),
                ChatMessage(2, Role.ASSISTANT, "好的", 2000L)
            )
            manager.extractImplicitPreferences(messages, testConfig())
        }

        val result = manager.formatProfilesAsContext()
        assertNotNull(result)
        assertTrue("应包含显式标签", result!!.contains("（显式）"))
        assertTrue("应包含隐式标签", result.contains("（隐式）"))
        // O1：隐式偏好 value 为 LLM 抽取短语，保留 key: value 结构提供语义
        assertTrue("隐式偏好应保留 key: value 结构", result.contains("tech_stack: Python（隐式）"))
    }

    // ==================== AC-5：抽取失败降级 + upsert + 优先级 ====================

    @Test
    fun extractImplicitPreferences_returns_empty_on_llm_failure() = runBlocking {
        val provider = FakeCompletionProvider(throwOnCall = true)
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "问题", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答", 2000L)
        )
        val result = manager.extractImplicitPreferences(messages, testConfig())

        assertTrue("LLM 失败应返回空列表", result.isEmpty())
        assertEquals("仓库不应有记录", 0L, userProfileRepository.count())
    }

    @Test
    fun extractImplicitPreferences_returns_empty_on_null_response() = runBlocking {
        val provider = FakeCompletionProvider(returnValue = null)
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "问题", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答", 2000L)
        )
        val result = manager.extractImplicitPreferences(messages, testConfig())
        assertTrue("null 响应应返回空列表", result.isEmpty())
    }

    @Test
    fun extractImplicitPreferences_returns_empty_on_invalid_json() = runBlocking {
        val provider = FakeCompletionProvider(returnValue = "not a json")
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "问题", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答", 2000L)
        )
        val result = manager.extractImplicitPreferences(messages, testConfig())
        assertTrue("非法 JSON 应返回空列表", result.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun extractImplicitPreferences_rethrows_cancellation_exception(): Unit = runBlocking {
        val provider = FakeCompletionProvider(throwCancellation = true)
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "问题", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答", 2000L)
        )
        manager.extractImplicitPreferences(messages, testConfig())
    }

    @Test
    fun extractImplicitPreferences_does_not_overwrite_explicit_preference() = runBlocking {
        // 先设置显式偏好
        manager.setExplicitPreference("language", "中文")

        // 隐式抽取同 key 不同 value
        val provider = FakeCompletionProvider(returnValue = """{"language":"Python"}""")
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "用 Python", 1000L),
            ChatMessage(2, Role.ASSISTANT, "好的", 2000L)
        )
        val result = manager.extractImplicitPreferences(messages, testConfig())

        assertTrue("隐式抽取 language 应被跳过（显式已存在）", result.isEmpty())

        val profile = userProfileRepository.get("language")
        assertNotNull(profile)
        assertEquals("显式值不应被覆盖", "中文", profile!!.value)
        assertEquals("category 应保持 EXPLICIT", ProfileCategory.EXPLICIT.name, profile.category)
    }

    @Test
    fun extractImplicitPreferences_upserts_existing_implicit() = runBlocking {
        // 第一次抽取
        var provider = FakeCompletionProvider(returnValue = """{"language":"Python"}""")
        manager = UserProfileManager(provider, userProfileRepository)
        val messages = listOf(
            ChatMessage(1, Role.USER, "用 Python", 1000L),
            ChatMessage(2, Role.ASSISTANT, "好的", 2000L)
        )
        manager.extractImplicitPreferences(messages, testConfig())
        assertEquals("第一次应保存 1 条", 1L, userProfileRepository.count())

        // 第二次抽取同 key 不同 value
        provider = FakeCompletionProvider(returnValue = """{"language":"Go"}""")
        manager = UserProfileManager(provider, userProfileRepository)
        manager.extractImplicitPreferences(messages, testConfig())

        assertEquals("第二次应 upsert（仍 1 条）", 1L, userProfileRepository.count())
        val profile = userProfileRepository.get("language")
        assertEquals("value 应更新为 Go", "Go", profile!!.value)
        assertEquals("category 应保持 IMPLICIT", ProfileCategory.IMPLICIT.name, profile.category)
    }

    @Test
    fun extractImplicitPreferences_handles_partial_failure_gracefully() = runBlocking {
        // 正常 JSON，但某个 key 的 value 为空（应被过滤）
        val provider = FakeCompletionProvider(
            returnValue = """{"language":"Python","empty":"","tone":"简洁"}"""
        )
        manager = UserProfileManager(provider, userProfileRepository)

        val messages = listOf(
            ChatMessage(1, Role.USER, "问题", 1000L),
            ChatMessage(2, Role.ASSISTANT, "回答", 2000L)
        )
        val result = manager.extractImplicitPreferences(messages, testConfig())

        assertEquals("应保存 2 个偏好（空 value 被过滤）", 2, result.size)
        assertEquals("仓库应有 2 条记录", 2L, userProfileRepository.count())
    }

    // ==================== AC-6：纯函数测试 ====================

    @Test
    fun filterKeyMessages_keeps_user_and_assistant() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "user msg", 1000L),
            ChatMessage(2, Role.ASSISTANT, "assistant msg", 2000L),
            ChatMessage(3, Role.TOOL, "tool msg", 3000L)
        )
        val result = manager.filterKeyMessages(messages)
        assertEquals("应保留 USER + ASSISTANT", 2, result.size)
    }

    @Test
    fun filterKeyMessages_skips_blank_content() {
        val messages = listOf(
            ChatMessage(1, Role.USER, "", 1000L),
            ChatMessage(2, Role.ASSISTANT, "  ", 2000L),
            ChatMessage(3, Role.USER, "valid", 3000L)
        )
        val result = manager.filterKeyMessages(messages)
        assertEquals("应过滤空 content", 1, result.size)
        assertEquals("valid", result[0].content)
    }

    @Test
    fun filterKeyMessages_handles_empty_list() {
        val result = manager.filterKeyMessages(emptyList())
        assertTrue("空列表应返回空", result.isEmpty())
    }

    @Test
    fun shouldSkipImplicitUpsert_returns_false_for_nonexistent_key() {
        assertFalse("不存在的 key 应返回 false", manager.shouldSkipImplicitUpsert("nonexistent"))
    }

    @Test
    fun shouldSkipImplicitUpsert_returns_true_for_explicit_key() {
        manager.setExplicitPreference("language", "中文")
        assertTrue("已有 EXPLICIT 的 key 应返回 true", manager.shouldSkipImplicitUpsert("language"))
    }

    @Test
    fun shouldSkipImplicitUpsert_returns_false_for_implicit_key() {
        runBlocking {
            val provider = FakeCompletionProvider(returnValue = """{"language":"Python"}""")
            manager = UserProfileManager(provider, userProfileRepository)
            val messages = listOf(
                ChatMessage(1, Role.USER, "用 Python", 1000L),
                ChatMessage(2, Role.ASSISTANT, "好的", 2000L)
            )
            manager.extractImplicitPreferences(messages, testConfig())
        }
        assertFalse("已有 IMPLICIT 的 key 应返回 false（可被覆盖）", manager.shouldSkipImplicitUpsert("language"))
    }

    @Test
    fun buildExtractionPrompt_contains_key_instructions() {
        val prompt = manager.buildExtractionPrompt()
        assertTrue("应包含角色说明", prompt.contains("偏好抽取助手"))
        assertTrue("应包含 JSON 格式要求", prompt.contains("JSON"))
        assertTrue("应包含 snake_case 要求", prompt.contains("snake_case"))
        assertTrue("应包含示例", prompt.contains("示例"))
    }

    @Test
    fun stripMarkdownCodeBlock_extracts_json_from_code_block() {
        val input = "```json\n{\"key\":\"value\"}\n```"
        val result = manager.stripMarkdownCodeBlock(input)
        assertEquals("{\"key\":\"value\"}", result)
    }

    @Test
    fun stripMarkdownCodeBlock_extracts_json_from_bare_code_block() {
        val input = "```\n{\"key\":\"value\"}\n```"
        val result = manager.stripMarkdownCodeBlock(input)
        assertEquals("{\"key\":\"value\"}", result)
    }

    @Test
    fun stripMarkdownCodeBlock_returns_raw_for_no_braces() {
        val input = "no json here"
        val result = manager.stripMarkdownCodeBlock(input)
        assertEquals("no json here", result)
    }

    @Test
    fun stripMarkdownCodeBlock_extracts_from_mixed_text() {
        val input = "解释文本\n{\"key\":\"value\"}\n结尾文本"
        val result = manager.stripMarkdownCodeBlock(input)
        assertEquals("{\"key\":\"value\"}", result)
    }

    // ==================== 便捷查询方法 ====================

    @Test
    fun getAllProfiles_returns_all_sorted_by_updatedAt_desc() {
        manager.setExplicitPreference("a", "1")
        Thread.sleep(10)
        manager.setExplicitPreference("b", "2")
        Thread.sleep(10)
        manager.setExplicitPreference("c", "3")

        val profiles = manager.getAllProfiles()
        assertEquals("应返回 3 条", 3, profiles.size)
        assertEquals("最近更新的在前", "c", profiles[0].key)
    }

    @Test
    fun getProfilesByCategory_filters_correctly() {
        manager.setExplicitPreference("explicit_key", "value")

        runBlocking {
            val provider = FakeCompletionProvider(returnValue = """{"implicit_key":"value"}""")
            manager = UserProfileManager(provider, userProfileRepository)
            val messages = listOf(
                ChatMessage(1, Role.USER, "问题", 1000L),
                ChatMessage(2, Role.ASSISTANT, "回答", 2000L)
            )
            manager.extractImplicitPreferences(messages, testConfig())
        }

        // 需要重新设置 explicit（manager 重建后 repository 共享，但 explicit 是之前设的）
        manager.setExplicitPreference("explicit_key", "value")

        val explicit = manager.getProfilesByCategory(ProfileCategory.EXPLICIT)
        val implicit = manager.getProfilesByCategory(ProfileCategory.IMPLICIT)
        assertEquals("应 1 条 EXPLICIT", 1, explicit.size)
        assertEquals("应 1 条 IMPLICIT", 1, implicit.size)
    }

    @Test
    fun deleteProfile_removes_by_key() {
        manager.setExplicitPreference("tone", "简洁")
        assertTrue("删除应返回 true", manager.deleteProfile("tone"))
        assertNull("删除后查询应返回 null", userProfileRepository.get("tone"))
        assertFalse("再次删除应返回 false", manager.deleteProfile("tone"))
    }

    @Test
    fun deleteAllProfiles_clears_all() {
        manager.setExplicitPreference("a", "1")
        manager.setExplicitPreference("b", "2")
        assertEquals("应删除 2 条", 2L, manager.deleteAllProfiles())
        assertEquals("仓库应为空", 0L, userProfileRepository.count())
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

    /**
     * Fake ChatCompletionProvider 用于测试（复用 ConversationSummarizerTest 模式）。
     *
     * @param returnValue chatCompletion 返回的固定值（null 模拟失败）
     * @param throwOnCall 是否在调用时抛普通异常
     * @param throwCancellation 是否在调用时抛 CancellationException
     */
    private class FakeCompletionProvider(
        private val returnValue: String? = null,
        private val throwOnCall: Boolean = false,
        private val throwCancellation: Boolean = false
    ) : ChatCompletionProvider {

        var lastConfig: ProviderConfig? = null
            private set
        var lastMessages: List<ChatMessage>? = null
            private set
        var lastSystemPrompt: String? = null
            private set
        var lastRagContext: String? = null
            private set
        var callCount: Int = 0
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
            lastConfig = config
            lastMessages = messages
            lastSystemPrompt = systemPrompt
            lastRagContext = ragContext
            return when {
                throwCancellation -> throw CancellationException("test cancellation")
                throwOnCall -> throw RuntimeException("test network error")
                else -> returnValue
            }
        }
    }
}
