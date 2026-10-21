package io.prism.qq

import io.prism.qq.channel.QqMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDateTime

/**
 * 定时主动消息的**文案生成器**（ADR-063；PRD US-3108 AC-3——"由 LLM 结合 RolePack
 * 人设与生活情境生成"）。
 *
 * **最小实现**：OpenAI 兼容端点**非流式**一次性调用（`/chat/completions`，`stream=false`），
 * system = RolePack persona（无则通用模板）+ 情境指令；user = 当前时间与生活情境提示。
 *
 * **可测性**：HTTP 执行以 [httpPost] 函数注入（生产用 `HttpURLConnection`）；
 * 请求构建与响应解析为 internal 纯函数。
 *
 * **降级**：无激活 Provider / 无 key / 生成失败 → 返回 null（调用方按"未生成"诚实跳过，
 * 不发占位文案——宁可这次不触发，也不发不像人的消息）。
 */
class QqProactiveComposer(
    /**
     * HTTP POST 执行（url、请求头、JSON 体 → 响应体文本；失败抛异常）。
     * 生产：HttpURLConnection（IO 调度器上执行）；测试：注入 fake。
     */
    private val httpPost: suspend (url: String, headers: Map<String, String>, bodyJson: String) -> String
) {

    /**
     * 生成一条主动消息。
     *
     * @param baseUrl Provider baseUrl（如 `https://api.deepseek.com/v1`）
     * @param apiKey 明文 key
     * @param model 模型名（ProviderConfig.models 首个）
     * @param personaSystemPrompt RolePack `persona.systemPrompt`（可为 null/空 → 通用模板）
     * @param companionAddress 用户对角色的称呼（可选，仅用于 prompt 情境）
     * @param now 当前时间（注入可测）
     * @return 生成的消息文本；失败返回 null
     */
    suspend fun compose(
        baseUrl: String,
        apiKey: String,
        model: String,
        personaSystemPrompt: String?,
        companionAddress: String?,
        now: LocalDateTime = LocalDateTime.now()
    ): String? {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        val hour = now.hour
        val period = when (hour) {
            in 6..10 -> "早晨"
            in 11..13 -> "中午"
            in 14..17 -> "下午"
            in 18..22 -> "晚上"
            else -> "深夜"
        }
        val persona = personaSystemPrompt?.takeIf { it.isNotBlank() }
            ?.let { "$it\n\n【本次任务】" }
            ?: "你是一个像真人一样和用户聊天的 AI 伙伴${companionAddress?.let { "（用户叫你「$it」）" } ?: ""}。\n\n【本次任务】"
        val system = persona +
            "你现在想主动给用户发一条消息（就像真人偶尔想起对方时发微信/QQ那样）。" +
            "要求：像真人口吻（口语、可以有语气词/表情），20-60 字，贴合当前时段（$period " +
            "${hour}:${"%02d".format(now.minute)}）与生活情境；不要引号、不要自称 AI、不要解释你在做什么，" +
            "只输出消息正文本身。"
        val body = buildRequestJson(model, system, now.toString())
        return postWithRetry(body, baseUrl, apiKey)
    }

    /**
     * 生成一条**回应**消息（US-3109 双向对话）：针对小号刚收到的消息 [incoming] 生成一条像真人
     * 的即时回复。
     *
     * @param incoming 对方（用户主号）发来的消息正文
     * @return 生成的回复文本；失败返回 null（调用方按"未生成"诚实跳过）
     */
    suspend fun composeReply(
        baseUrl: String,
        apiKey: String,
        model: String,
        personaSystemPrompt: String?,
        companionAddress: String?,
        incoming: String,
        /** 最近对话（由旧到新，**不含**当前待回消息；US-3112 AC-1）。 */
        history: List<QqMessage> = emptyList(),
        /** L2 跨会话记忆 + L3 用户画像的合并 section（US-3112 AC-3；null/空=不注入）。 */
        memorySection: String? = null,
        now: LocalDateTime = LocalDateTime.now()
    ): String? {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        val persona = personaSystemPrompt?.takeIf { it.isNotBlank() }
            ?.let { "$it\n\n【本次任务】" }
            ?: "你是一个像真人一样和用户聊天的 AI 伙伴${companionAddress?.let { "（用户叫你「$it」）" } ?: ""}。\n\n【本次任务】"
        val memory = memorySection?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "\n\n【关于用户的相关记忆（供你自然运用，不要直接复述）】\n$it" }
            ?: ""
        val system = persona + memory +
            "\n\n对方刚给你发了一条消息，请**像真人那样自然、即时地回一句**。" +
            "要求：口语、简短（不超过 60 字）、承接对方话题，可以有语气词/表情；" +
            "不要引号、不要自称 AI、不要解释你在做什么，只输出回复正文本身。"
        val userContext = buildReplyUserContext(history, incoming)
        val body = buildRequestJson(model, system, userContext, reply = true)
        return postWithRetry(body, baseUrl, apiKey)
    }

    /**
     * 非流式请求 + 单次重试（DEF-108：真机联调实测端点偶发瞬态失败）。
     * 成功返回内容（截断至 [MAX_LEN]）；失败返回 null（附脱敏诊断，不落业务数据）。
     */
    private suspend fun postWithRetry(body: String, baseUrl: String, apiKey: String): String? {
        // ADR-064 H-06：QQ 文案生成同样不得把正文/Key 发往远端明文 http
        if (!io.prism.network.PrivateNetwork.isCleartextAllowed(baseUrl)) {
            android.util.Log.w("QqProactiveComposer", "拒绝远端明文 http 模型端点（QQ 文案生成）")
            return null
        }
        repeat(2) { attempt ->
            try {
                val resp = httpPost(
                    "${baseUrl.trimEnd('/')}/chat/completions",
                    mapOf(
                        "Content-Type" to "application/json",
                        "Authorization" to "Bearer $apiKey"
                    ),
                    body
                )
                parseContent(resp)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(MAX_LEN) }
                android.util.Log.w(
                    "QqProactiveComposer",
                    "attempt=$attempt 解析失败（响应长度=${resp.length}，内容已脱敏）"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("QqProactiveComposer", "attempt=$attempt HTTP 失败：${e::class.simpleName} ${e.message?.take(120)}")
            }
            if (attempt == 0) kotlinx.coroutines.delay(2_000)
        }
        return null
    }

    companion object {
        /** 生成消息长度上限（防跑飞）。 */
        const val MAX_LEN = 200

        /**
         * 进入 prompt 的历史条数上限（US-3112；与配置域 6~30 一致，guardrail D-02）。
         *
         * 此前为 20，导致用户配置 21~30 时静默失效——AC-1 要求「可配置」必须真实生效。
         */
        const val MAX_HISTORY_IN_PROMPT = 30

        /** 单条历史消息进入 prompt 的字符上限（防单条长文撑爆上下文）。 */
        const val MAX_CHARS_PER_MESSAGE = 120

        /**
     * 构建最近对话 + 当前消息的 user 内容（internal 纯函数可测；US-3112 AC-1）。
     *
     * 截断策略：最多 [MAX_HISTORY_IN_PROMPT] 条、单条 [MAX_CHARS_PER_MESSAGE] 字——
     * 防止长聊天把 prompt 撑爆（上下文窗口与 token 成本双重约束）。
     */
    internal fun buildReplyUserContext(history: List<QqMessage>, incoming: String): String {
        val lines = history.takeLast(MAX_HISTORY_IN_PROMPT).map { message ->
            val who = if (message.incoming) "对方" else "我"
            "$who：${message.text.trim().take(MAX_CHARS_PER_MESSAGE)}"
        }
        val historyBlock = if (lines.isEmpty()) {
            ""
        } else {
            "最近对话（由旧到新）：\n" + lines.joinToString("\n") + "\n\n"
        }
        return historyBlock + "对方刚发来：「${incoming.trim().take(MAX_CHARS_PER_MESSAGE)}」"
    }

    /**
     * 构建非流式 chat completions 请求体（internal 纯函数可测）。
     *
     * @param reply true=回复场景（[userContext] 已是完整用户内容）；false=主动消息场景
     */
    internal fun buildRequestJson(
        model: String,
        system: String,
        userContext: String,
        reply: Boolean = false
    ): String =
        buildJsonObject {
            put("model", model)
            put("stream", false)
            put("max_tokens", 120)
            put("temperature", 0.9)
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        },
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                if (reply) userContext else "现在时间：$userContext。请生成这条主动消息。"
                            )
                        }
                    )
                )
            )
        }.toString()

        /** 解析 OpenAI 兼容响应中的 `choices[0].message.content`（internal 纯函数可测）。 */
        internal fun parseContent(body: String): String? = try {
            val root = Json.parseToJsonElement(body).jsonObject
            val first = (root["choices"] ?: return null).jsonArray.firstOrNull() ?: return null
            val message = first.jsonObject["message"] ?: return null
            message.jsonObject["content"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }
}
