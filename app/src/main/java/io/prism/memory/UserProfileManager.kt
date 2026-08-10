package io.prism.memory

import android.util.Log
import io.prism.data.ProfileCategory
import io.prism.data.ProviderConfig
import io.prism.data.UserProfile
import io.prism.data.UserProfileRepository
import io.prism.network.ChatCompletionProvider
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * L3 用户画像管理器（US-034，ADR-015 5.5）—— 显式偏好设定 + 隐式偏好 LLM 抽取 + 画像注入。
 *
 * **三层记忆架构定位**（ADR-015）：
 * - L1 会话内：[SlidingWindowMemoryManager]（滑动窗口 + 摘要压缩）
 * - L2 跨会话：[CrossSessionMemoryManager]（向量化存储 + top-k 检索）
 * - L3 用户画像：**本管理器**，结构化偏好存储（显式 + 隐式）
 *
 * **核心职责**：
 * 1. **显式偏好**（[setExplicitPreference]）：用户通过 UI 设定偏好（如"我喜欢简洁的回复"），
 *    存入 [UserProfileRepository] category=[ProfileCategory.EXPLICIT]。
 * 2. **隐式偏好**（[extractImplicitPreferences]）：对话结束时，LLM 从近期对话抽取偏好
 *    （如"用户常用 Python"），存入 category=[ProfileCategory.IMPLICIT]。
 * 3. **画像注入**（[formatProfilesAsContext]）：将全部画像格式化为 systemPrompt section
 *    （格式："用户偏好：..."），由 [io.prism.ui.chat.ConversationViewModel]（US-035）
 *    注入新会话 systemPrompt 第三段，影响 AI 回复风格。
 *
 * **设计决策**（ADR-015 5.5）：
 * - **复用 [ChatCompletionProvider] 非流式接口**：与 [ConversationSummarizer] 同模式，
 *   一次性完整结果，无需流式增量。[ProviderConfig] 作为参数传入，支持运行时切换 Provider。
 * - **LLM 返回结构化 JSON**：抽取 prompt 指示 LLM 返回 `{"key": "value", ...}` 格式，
 *   本管理器用 [JSONObject] 解析（Android 内置，无需额外依赖）。
 * - **upsert 语义**：[UserProfileRepository.save] 已实现单 key 唯一约束，
 *   同 key 隐式抽取会更新 value + updatedAt，不产生重复记录。
 * - **显式 > 隐式优先级**：显式偏好由用户主动设定，权威性高于隐式抽取。
 *   若同 key 已有 EXPLICIT 记录，隐式抽取**不覆盖**（[extractImplicitPreferences] 跳过）。
 *
 * **错误处理**（BR-error-handling-007）：
 * - [extractImplicitPreferences] 中 LLM 调用失败 → 返回空列表（降级为跳过，不阻断对话）
 * - JSON 解析失败 → 返回空列表
 * - 单个 key-value upsert 失败 → 跳过该 key（不中断整体抽取）
 * - CancellationException 正确重抛
 *
 * **线程安全**：[UserProfileRepository] 内部 ObjectBox 事务保证原子读写（BR-concurrency-001）。
 *
 * US-034 验收标准：
 * - AC-1：显式偏好（用户 UI 设定，category=EXPLICIT）
 * - AC-2：隐式偏好（LLM 抽取，category=IMPLICIT）
 * - AC-3：隐式抽取使用非流式请求 + 结构化 JSON
 * - AC-4：画像注入 systemPrompt（格式："用户偏好：..."）
 * - AC-5：抽取失败降级为跳过，upsert 已有 key
 * - AC-6：单元测试通过
 *
 * @param completionProvider 非流式对话 Provider（依赖倒置，便于测试注入 fake）
 * @param userProfileRepository 用户画像仓库（Phase A 已实现）
 */
class UserProfileManager(
    private val completionProvider: ChatCompletionProvider,
    private val userProfileRepository: UserProfileRepository
) {

    /**
     * 设置显式偏好（US-034 AC-1）。
     *
     * 用户通过 UI 设定偏好（如 key="tone", value="简洁"），存入 [UserProfileRepository]
     * category=[ProfileCategory.EXPLICIT]。upsert 语义：同 key 存在则更新。
     *
     * @param key 偏好键（如 "tone"、"language"），非空
     * @param value 偏好值（如 "简洁"、"中文"），非空
     * @return 保存后的 id
     * @throws IllegalArgumentException 当 key 或 value 为空时
     */
    fun setExplicitPreference(key: String, value: String): Long {
        require(key.isNotBlank()) { "偏好 key 不能为空" }
        require(value.isNotBlank()) { "偏好 value 不能为空" }

        val profile = UserProfile(
            key = key,
            value = value,
            category = ProfileCategory.EXPLICIT.name,
            updatedAt = System.currentTimeMillis()
        )
        return userProfileRepository.save(profile)
    }

    /**
     * 从近期对话抽取隐式偏好（US-034 AC-2 + AC-3 + AC-5）。
     *
     * **流程**：
     * 1. 过滤消息（[filterKeyMessages]）：仅保留 user+assistant 且 content 非空
     * 2. 构建抽取 prompt（[buildExtractionPrompt]）
     * 3. 调用 [ChatCompletionProvider.chatCompletion]（非流式）
     * 4. 解析 JSON 响应（[parsePreferencesJson]）
     * 5. 对每个 key-value：若同 key 无 EXPLICIT 记录，则 upsert 为 IMPLICIT
     *
     * **显式 > 隐式优先级**：若同 key 已有 EXPLICIT 记录，跳过（不覆盖用户主动设定）。
     *
     * **降级策略**（AC-5）：
     * - LLM 调用失败 → 返回空列表（不阻断对话）
     * - JSON 解析失败 → 返回空列表
     * - 单个 key upsert 失败 → 跳过该 key
     * - CancellationException 正确重抛
     *
     * @param messages 近期对话消息列表（建议滑动窗口内的消息，避免超长）
     * @param config 目标 Provider 配置（支持运行时切换 Provider）
     * @return 实际保存的隐式偏好 key-value 列表（可能因失败/无抽取/显式覆盖而为空）
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛
     */
    suspend fun extractImplicitPreferences(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): List<Pair<String, String>> {
        val keyMessages = filterKeyMessages(messages)
        if (keyMessages.isEmpty()) return emptyList()

        // 1. 调用 LLM 抽取偏好（非流式）
        val jsonResponse = try {
            completionProvider.chatCompletion(
                config = config,
                messages = keyMessages,
                systemPrompt = buildExtractionPrompt()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // BR-error-handling-004：LLM 调用失败记录日志（不含敏感信息），降级为空结果
            Log.w(TAG, "extractImplicitPreferences: LLM 调用失败（${e.javaClass.simpleName}）")
            return emptyList()
        }

        // 2. 解析 JSON 响应
        val preferences = parsePreferencesJson(jsonResponse)
        if (preferences.isEmpty()) return emptyList()

        // 3. upsert 每个偏好（跳过已有 EXPLICIT 的 key）
        val saved = mutableListOf<Pair<String, String>>()
        for ((key, value) in preferences) {
            try {
                if (shouldSkipImplicitUpsert(key)) {
                    Log.i(TAG, "extractImplicitPreferences: 跳过 key='$key'（已有 EXPLICIT 记录）")
                    continue
                }
                val profile = UserProfile(
                    key = key,
                    value = value,
                    category = ProfileCategory.IMPLICIT.name,
                    updatedAt = System.currentTimeMillis()
                )
                userProfileRepository.save(profile)
                saved.add(key to value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // BR-error-handling-004：单个 key upsert 失败跳过，不中断整体抽取
                Log.w(TAG, "extractImplicitPreferences: upsert key='$key' 失败（${e.javaClass.simpleName}）")
            }
        }
        return saved
    }

    /**
     * 格式化全部画像为 systemPrompt section（US-034 AC-4）。
     *
     * **格式**：
     * ```
     * 用户偏好：
     * - language: 中文（显式）
     * - tech_stack: Python（隐式）
     * - tone: 简洁（显式）
     * ```
     *
     * 该 section 由调用方（ConversationViewModel，US-035）注入新会话 systemPrompt 第三段
     * （ADR-015 决策 4：base → RAG → L1 摘要 → L2 跨会话 → **L3 画像** → Skill）。
     *
     * @return 格式化文本；无画像返回 null（表示无偏好需注入）
     */
    fun formatProfilesAsContext(): String? {
        val profiles = userProfileRepository.getAll()
        if (profiles.isEmpty()) return null

        val formatted = profiles.joinToString("\n") { profile ->
            val categoryLabel = if (profile.category == ProfileCategory.EXPLICIT.name) "显式" else "隐式"
            "- ${profile.key}: ${profile.value}（$categoryLabel）"
        }
        return "$PROFILE_CONTEXT_PREFIX$formatted"
    }

    /**
     * 获取全部用户画像（便捷查询，供 UI 使用）。
     */
    fun getAllProfiles(): List<UserProfile> = userProfileRepository.getAll()

    /**
     * 按类别获取用户画像（便捷查询，供 UI 使用）。
     */
    fun getProfilesByCategory(category: ProfileCategory): List<UserProfile> =
        userProfileRepository.getByCategory(category)

    /**
     * 删除指定 key 的画像（供 UI 使用）。
     */
    fun deleteProfile(key: String): Boolean = userProfileRepository.delete(key)

    /**
     * 删除全部画像（供 UI"一键清除"使用）。
     */
    fun deleteAllProfiles(): Long = userProfileRepository.deleteAll()

    /**
     * 过滤关键消息：仅保留 user+assistant 且 content 非空（AC-2 前置处理）。
     *
     * 与 [CrossSessionMemoryManager.filterKeyMessages] 同模式：跳过 Role.TOOL 和空 content。
     *
     * 纯函数，可测。
     */
    internal fun filterKeyMessages(messages: List<ChatMessage>): List<ChatMessage> =
        messages.filter { msg ->
            (msg.role == Role.USER || msg.role == Role.ASSISTANT) && msg.content.isNotBlank()
        }

    /**
     * 判断是否应跳过隐式 upsert（显式 > 隐式优先级，US-034 AC-5）。
     *
     * 若同 key 已有 EXPLICIT 记录，隐式抽取不覆盖（用户主动设定权威性高于 LLM 推断）。
     *
     * 纯函数，可测。
     */
    internal fun shouldSkipImplicitUpsert(key: String): Boolean {
        val existing = userProfileRepository.get(key) ?: return false
        return existing.category == ProfileCategory.EXPLICIT.name
    }

    /**
     * 构建偏好抽取 prompt（纯函数，可测）。
     *
     * **prompt 设计**（参考 mem0 隐式记忆抽取最佳实践）：
     * - 明确角色（用户偏好抽取助手）
     * - 明确抽取规则（仅抽取稳定偏好，不抽取一次性需求）
     * - 明确输出格式（纯 JSON，无 markdown 包装）
     * - 提供示例（few-shot，引导 LLM 返回正确格式）
     *
     * @return 抽取 prompt 文本
     */
    internal fun buildExtractionPrompt(): String = EXTRACTION_PROMPT_TEMPLATE

    /**
     * 解析 LLM 返回的 JSON 偏好响应（纯函数，可测）。
     *
     * **解析流程**：
     * 1. 输入 null/空 → 返回空列表
     * 2. 剥离 markdown 代码块包装（```json ... ```）
     * 3. [Json.decodeFromString] 解析为 [JsonObject]，遍历 entries 提取 key-value
     * 4. 过滤空 key 或空 value
     * 5. 解析失败 → 返回空列表（降级）
     *
     * **使用 kotlinx.serialization**（非 org.json）：kotlinx.serialization 在 JVM 单元测试
     * 环境可用（org.json 在 JVM 测试环境为 Android stub，仅 Robolectric 可用）。
     *
     * @param jsonResponse LLM 返回的文本（预期为 JSON）
     * @return 解析出的 key-value 列表；解析失败返回空列表
     */
    internal fun parsePreferencesJson(jsonResponse: String?): List<Pair<String, String>> {
        if (jsonResponse.isNullOrBlank()) return emptyList()

        // 剥离 markdown 代码块包装（LLM 可能返回 ```json\n{...}\n```）
        val cleaned = stripMarkdownCodeBlock(jsonResponse).trim()
        if (cleaned.isEmpty()) return emptyList()

        return try {
            val json = Json { ignoreUnknownKeys = true }
            val jsonObject = json.decodeFromString<JsonObject>(cleaned)
            val result = mutableListOf<Pair<String, String>>()
            for ((key, element) in jsonObject) {
                // 仅处理字符串值（JsonPrimitive），跳过嵌套对象/数组
                val value = (element as? JsonPrimitive)?.contentOrNull
                if (key.isNotBlank() && !value.isNullOrBlank()) {
                    result.add(key to value)
                }
            }
            result
        } catch (e: Exception) {
            // JSON 解析失败降级为空列表（不阻断对话）
            Log.w(TAG, "parsePreferencesJson: JSON 解析失败（${e.javaClass.simpleName}）")
            emptyList()
        }
    }

    /**
     * 剥离 markdown 代码块包装（纯函数，可测）。
     *
     * LLM 可能返回：
     * - ```json\n{"key": "value"}\n```
     * - ```\n{"key": "value"}\n```
     * - 裸 JSON：`{"key": "value"}`
     *
     * 本方法提取首个 `{` 到最后一个 `}` 之间的内容。
     *
     * @param text LLM 返回的原始文本
     * @return 剥离后的 JSON 文本；无 JSON 结构返回原文本 trim 后的结果
     */
    internal fun stripMarkdownCodeBlock(text: String): String {
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        return if (firstBrace >= 0 && lastBrace > firstBrace) {
            text.substring(firstBrace, lastBrace + 1)
        } else {
            text.trim()
        }
    }

    companion object {
        /** 日志 Tag（BR-error-handling-004：catch 块日志归类）。 */
        private const val TAG = "UserProfileManager"

        /** 用户画像注入 systemPrompt 的前缀（US-034 AC-4）。 */
        internal const val PROFILE_CONTEXT_PREFIX = "用户偏好：\n"

        /**
         * 偏好抽取 prompt 模板（参考 mem0 隐式记忆抽取最佳实践）。
         *
         * 设计要点：
         * - 明确角色（用户偏好抽取助手）
         * - 明确抽取规则（稳定偏好，非一次性需求）
         * - 明确输出格式（纯 JSON，无 markdown 包装）
         * - few-shot 示例引导正确格式
         */
        internal val EXTRACTION_PROMPT_TEMPLATE = """
你是用户偏好抽取助手。请从以下对话中抽取用户的隐式偏好，返回 JSON 格式。

抽取规则：
1. 仅抽取可从对话推断的稳定偏好（如常用语言、技术栈、回复风格偏好、专业领域）
2. 不要抽取一次性需求（如"帮我写个函数"不是偏好）
3. 不要抽取对话主题本身（如"用户问了关于数据库的问题"不是偏好）
4. 每个偏好用 key-value 表示，key 用英文 snake_case，value 用中文
5. 返回纯 JSON，不要 markdown 代码块包装，不要解释说明

常见偏好 key：
- language：用户常用编程语言
- tech_stack：技术栈
- tone：回复风格偏好（如"简洁"、"详细"）
- expertise：专业领域（如"初级"、"资深"）
- language_pref：自然语言偏好（如"中文"、"英文"）

示例输出：
{"language": "Python", "tone": "简洁", "expertise": "资深"}

如果没有可抽取的稳定偏好，返回空 JSON：{}
        """.trimIndent()
    }
}
