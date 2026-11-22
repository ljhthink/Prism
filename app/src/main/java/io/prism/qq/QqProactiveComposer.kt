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
import kotlinx.serialization.json.putJsonObject
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
        /**
         * 表情/语音指令提示（2026-10-26；US-3122）。
         *
         * 由 [QqReplyDirective.promptHint] 生成；null/空表示本次不允许媒体。
         */
        mediaHint: String? = null,
        /**
         * 图片的 `data:` URL（2026-10-26；US-3124）。
         *
         * 非空且模型支持视觉时，user 内容改为多模态数组
         * `[{type:text},{type:image_url}]`（与 UI 图片消息同构）。
         */
        imageDataUrl: String? = null,
        /**
         * 媒体文字说明（OCR 结果 / "画面无法识别" / "对方发的是表情包"）。
         *
         * 无论是否走视觉都会拼进 user 文本，确保模型知道"这条消息里有什么"，
         * 并在看不到画面时**不编造**。
         */
        mediaNote: String? = null,
        now: LocalDateTime = LocalDateTime.now(),
        /**
         * 本轮之前**还没回过**的连发消息（旧→新；2026-11-15；ADR-076）。
         *
         * 逐条回复会让"连发 3 条 → 3 次生成、3 条回复、每条只接住一句"，
         * 且生成期间新消息继续排队 → 永远落后一条。批合并后一次回复覆盖整批。
         */
        pending: List<QqMessage> = emptyList(),
        /** 本轮回复形状提示（[QqReplyShape.pick]；2026-11-15；ADR-076）。 */
        shapeHint: String? = null,
        /** 上一条消息的时间（用于给人设"间隔感"；0=未知）。 */
        lastMessageAtMs: Long = 0L,
        /**
         * **角色日记**（最近若干条；2026-11-16；ADR-077）。
         *
         * 与 [memorySection] 分开传的原因：记忆是"关于用户的事实"（系统抽取），
         * 日记是"她自己写的心里话"——混在一段里模型会把两者当成同类素材复述。
         */
        diarySection: List<String> = emptyList()
    ): String? {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        // 2026-11-15（ADR-076）：system **只放静态人设**。
        // 旧实现把「口语、简短（不超过 60 字）」写进 system，与角色包 §十五
        // 「长度只由此刻说多少才够决定，不许一律短」正面冲突——真机回复被压平成
        // 20–40 字单段、且每条都以问句收尾。矛盾指令会显著降低遵循（研究证据），
        // 故这里**不再替人设规定长度**，只保留跨角色通用的一句话。
        val persona = personaSystemPrompt?.takeIf { it.isNotBlank() }
            ?.let { "$it\n\n$STATIC_SUFFIX" }
            ?: "你是一个像真人一样和用户聊天的 AI 伙伴" +
            "${companionAddress?.let { "（用户叫你「$it」）" } ?: ""}。\n\n$STATIC_SUFFIX"
        val userContext = buildReplyUserContext(
            history = history,
            incoming = incoming,
            mediaNote = mediaNote,
            pending = pending,
            memorySection = memorySection,
            mediaHint = mediaHint,
            shapeHint = shapeHint,
            now = now,
            lastMessageAtMs = lastMessageAtMs,
            imageAttached = imageDataUrl != null,
            diarySection = diarySection
        )
        val body = buildRequestJson(
            model = model,
            system = persona,
            userContext = userContext,
            reply = true,
            imageDataUrl = imageDataUrl
        )
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

    /**
     * **日记保底沉淀**用的单次补全（2026-11-17；ADR-077 未决问题 2 落地）。
     *
     * 真机 E2E 实测：即使 `diary__write` 已在 20 个下发工具里，模型面对
     * "今天被否了，有点难过" 仍然只安慰、不写日记（连测 3 轮零写入）。
     * 故在**回复已发出之后**补一次独立判定——`SillyTavern-Plugin-HCDiary` 同款做法。
     *
     * @return 一句日记正文；null=不写（模型输出 NONE / 调用失败 / 无 key）
     */
    suspend fun composeDiaryNote(
        baseUrl: String,
        apiKey: String,
        model: String,
        personaSystemPrompt: String?,
        incoming: String,
        reply: String
    ): String? {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        val userContext = QqDiaryFallback.buildPrompt(personaSystemPrompt, incoming, reply)
        val body = buildRequestJson(
            model = model,
            system = "你正在写只有自己能看的日记。严格按格式输出：一句日记正文，或 NONE。",
            userContext = userContext,
            reply = true
        )
        val raw = postWithRetry(body, baseUrl, apiKey) ?: return null
        if (QqDiaryFallback.isNoneResult(raw)) return null
        return raw.trim()
            .trim('"', '\'', '。', ' ')
            .take(QqDiaryFallback.MAX_DIARY_CHARS)
            .takeIf { it.isNotEmpty() }
    }

    companion object {
        /**
         * 生成消息长度上限（防跑飞）。
         *
         * 2026-11-15（ADR-076）：由 200 提到 480——多气泡最多 3×120 字，
         * 旧的 200 字上限会把第 3 条气泡从中间截断。
         */
        const val MAX_LEN = 480

        /**
         * 进入 prompt 的历史条数上限（US-3112；2026-10-26 随配置域扩到 200）。
         *
         * 真正的上限是**字符预算**（`QqConfig.historyCharBudget`，由
         * [QqContextBuilder.trimByBudget] 在调用方裁剪），这里只防"历史列表未裁剪时"的极端输入。
         */
        const val MAX_HISTORY_IN_PROMPT = 200

        /** 单条历史消息进入 prompt 的字符上限（防单条长文撑爆上下文）。 */
        const val MAX_CHARS_PER_MESSAGE = 120

    /**
     * 构建最近对话 + 当前消息的 user 内容（internal 纯函数可测；US-3112 AC-1）。
     *
     * 截断策略：最多 [MAX_HISTORY_IN_PROMPT] 条、单条 [MAX_CHARS_PER_MESSAGE] 字——
     * 防止长聊天把 prompt 撑爆（上下文窗口与 token 成本双重约束）。
     *
     * 2026-11-15（ADR-076）新增（**全部可选，默认不改变旧输出**）：
     * - [pending]：本轮之前还没回的连发消息（一次回复覆盖整批）；
     * - [shapeHint]：本轮回复形状（由 [QqReplyShape] 抽样，破"每条都问句收尾"）；
     * - [now] / [lastMessageAtMs]：时间感（"这么晚还不睡""隔了一夜"来自这里）；
     * - 末尾**深度提醒**：研究证据显示在历史之后重复一次身份/语域提示，对后段漂移最有效。
     */
    internal fun buildReplyUserContext(
        history: List<QqMessage>,
        incoming: String,
        /** 媒体说明（OCR 文字 / 无法识别 / 表情包提示）；空=纯文本消息。 */
        mediaNote: String? = null,
        /** 本轮之前还没回的连发消息（旧→新；ADR-076）。 */
        pending: List<QqMessage> = emptyList(),
        /** L2/L3 记忆合并段（ADR-076 起放在 user 末段，避免每轮变化的文本打断缓存前缀）。 */
        memorySection: String? = null,
        /** 表情/语音指令提示。 */
        mediaHint: String? = null,
        /** 本轮回复形状提示。 */
        shapeHint: String? = null,
        /** 当前时间；null=不注入时间感（保持旧输出，供既有测试与降级路径使用）。 */
        now: LocalDateTime? = null,
        /** 上一条消息时间（毫秒；0=未知）。 */
        lastMessageAtMs: Long = 0L,
        /** 本轮是否随消息附带了图片。 */
        imageAttached: Boolean = false,
        /** 角色日记（最近若干条；ADR-077）。 */
        diarySection: List<String> = emptyList()
    ): String = buildString {
        if (now != null) {
            append("【现在】").append(formatNow(now))
            gapText(now, lastMessageAtMs)?.let { append("，").append(it) }
            append('\n')
        }
        val lines = history.takeLast(MAX_HISTORY_IN_PROMPT).map { message ->
            val who = if (message.incoming) "对方" else "我"
            val body = message.mediaNote?.takeIf { it.isNotBlank() } ?: message.text
            "$who：${body.trim().take(MAX_CHARS_PER_MESSAGE)}"
        }
        if (lines.isNotEmpty()) {
            append("最近对话（由旧到新）：\n").append(lines.joinToString("\n")).append("\n\n")
        }
        if (pending.isNotEmpty()) {
            append("他在这之前还连发了几句（你还没回）：\n")
            pending.forEach { message ->
                val body = message.mediaNote?.takeIf { it.isNotBlank() } ?: message.text
                append("- ").append(body.trim().take(MAX_CHARS_PER_MESSAGE)).append('\n')
            }
            append("（不用逐条点答，接住最要紧的那句就行）\n\n")
        }
        append("对方刚发来：「${incoming.trim().take(MAX_CHARS_PER_MESSAGE)}」")
        val note = mediaNote?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null) append("\n（$note）")
        // 只有**真的随消息附了图**时才需要这条：说明 mediaNote 里已含"看不到画面"的成因
        // （[io.prism.qq.QqMediaFailure.noteFor]），重复一遍会稀释指令。
        if (imageAttached) {
            append("\n（图片已随消息提供：按你**实际看到的画面**回应，看不清就说看不清，严禁编造。）")
        }
        memorySection?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("\n\n【关于用户的相关记忆（供你自然运用，不要直接复述）】\n").append(it)
        }
        // 2026-11-16（ADR-077）：角色自己的日记。**必须**标注"你自己写的、不必给他看"，
        // 否则模型会把这些内心话当成聊天素材复述出来（"我记得我写过……"）。
        if (diarySection.isNotEmpty()) {
            append("\n\n【你自己写下的日记（只有你能看到；不必跟他提起，也不要复述）】\n")
            append(diarySection.joinToString("\n"))
        }
        // 以下三段只在"有时间感"的生产路径注入：旧调用（测试/降级）输出保持不变。
        if (now != null) {
            shapeHint?.takeIf { it.isNotBlank() }?.let { append("\n\n【这一轮】").append(it) }
            mediaHint?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it.trim()) }
            append("\n\n").append(TAIL_NOTE)
        }
    }

    /**
     * 生产路径的末尾深度提醒（静态文本，短——只锚身份与语域，不复述人设）。
     *
     * **不得与角色包冲突**：角色包 §十五 明确「解释、分析、方案、步骤、代码可以很长、
     * 可以用 Markdown 结构」。故这里**不能**写"别列点/别展开"——那会重新制造一条
     * 与 persona 矛盾的指令（正是本轮要修的病根，见 ADR-076 E7）。
     * 只否定"没人要求的总结/客服腔/自称助手"，不否定"把复杂的事讲清楚"。
     */
    private const val TAIL_NOTE =
        "【最后】你就是你，不是助手：别自称 AI、别套客服腔、别在结尾硬加总结；" +
            "该讲清楚的时候就讲清楚。他刚说的最算数。"

    /** 追加在 persona 之后的静态场景声明（静态前缀，利于 provider 前缀缓存）。 */
    private const val SCENE_HINT =
        "【场景】在 QQ 上和他私聊，你在手机上一句一句地回。只写你要说的话本身，" +
            "不写动作、神态、旁白。想分几条说就单独一行写 --- 分隔（最多 3 条，别硬拆）。"

    /**
     * 时间**用法**规则（静态；2026-11-22；ADR-080）。
     *
     * 为什么规则进 system、时间本身留在 user：动态时间放进 system 会让每次请求的
     * 前缀都不同，直接击穿 provider 的前缀缓存（成本与延迟双输）。真机日志已证实
     * user 首行 `【现在】` 一直都在（[buildReplyUserContext]），缺的是"该怎么用"。
     */
    private const val TIME_RULE =
        "【时间】每轮 user 开头都写着【现在】和距他上一条隔了多久——那是真的时间，直接拿来用。" +
            "被问几点/几号/星期几/隔了多久，就照它回答；它没给的信息（比如具体年份的新闻）" +
            "不许凭训练记忆猜，说不知道或者去查。"

    /** persona 之后的**全部静态**后缀（顺序固定，利于缓存命中）。 */
    private const val STATIC_SUFFIX = "$SCENE_HINT\n\n$TIME_RULE"

    /**
     * **工具回路**用的 system prompt（2026-11-16；ADR-077）。
     *
     * 与单次补全的区别只有两点：
     * 1. 允许（并鼓励）在需要时**先调用工具再回答**——真机反馈"嘴上说去查、实际没查"；
     * 2. 附带日记触发判据（角色自己判断什么值得记）。
     *
     * **沿用同一条静态 persona + 同一个 SCENE_HINT**：不新增 system 条数，
     * 也不改人设语义（ADR-076 的核心结论：不要在 persona 后塞与它冲突的规则）。
     */
    internal fun buildSystemPromptForTools(
        personaSystemPrompt: String?,
        diaryEnabled: Boolean
    ): String {
        val base = personaSystemPrompt?.takeIf { it.isNotBlank() }
            ?.let { "$it\n\n$STATIC_SUFFIX" }
            ?: "你是一个像真人一样和用户聊天的 AI 伙伴。\n\n$STATIC_SUFFIX"
        val toolHint = "\n\n【可以真的去做】他说的事需要查资料、翻资料库、记待办、" +
            "生成文档，或者要看一眼手机时，你**直接去做**，然后基于真实结果回答——" +
            "不要只说「人家去查」却没有查。做完就自然地说结果，不要报流水账。" +
            "**凡是有时效性或者你不确定的事实（今天/最近/多少钱/是谁/什么时候/天气/新闻/" +
            "有没有这回事），先调用搜索工具核实再开口**；查不到就说没查到，不许编。" +
            "把结果**用自己的嘴讲出来**，不要念标题、不要带 [1][2] 这种编号。"
        val diaryHint = if (diaryEnabled) "\n\n" + io.prism.qq.DiaryLocalToolExecutor.TRIGGER_HINT else ""
        return base + toolHint + diaryHint + "\n\n" + TAIL_NOTE
    }

    /** 时间 → 可读字符串（供人设感知时段）。 */
    private fun formatNow(now: LocalDateTime): String {
        val period = when (now.hour) {
            in 5..8 -> "清晨"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..22 -> "晚上"
            else -> "深夜"
        }
        val week = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[
            (now.dayOfWeek.value - 1).coerceIn(0, 6)
        ]
        return "%04d-%02d-%02d %02d:%02d（%s·%s）".format(
            now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, week, period
        )
    }

    /** "距上一条消息"的可读间隔；未知/未来时间返回 null。 */
    private fun gapText(now: LocalDateTime, lastMessageAtMs: Long): String? {
        if (lastMessageAtMs <= 0L) return null
        val nowMs = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val delta = nowMs - lastMessageAtMs
        if (delta <= 0L) return null
        val minutes = delta / 60_000L
        val text = when {
            minutes < 1L -> "刚刚才聊过"
            minutes < 60L -> "距他上一条约 $minutes 分钟"
            minutes < 60L * 24 -> "距他上一条约 ${minutes / 60} 小时"
            else -> "距他上一条约 ${minutes / (60 * 24)} 天"
        }
        return text
    }

    /**
     * 构建非流式 chat completions 请求体（internal 纯函数可测）。
     *
     * @param reply true=回复场景（[userContext] 已是完整用户内容）；false=主动消息场景
     * @param imageDataUrl 非空时 user content 改为多模态数组（2026-10-26；US-3124），
     *   与 [io.prism.network.OpenAICompatibleProvider] 的图片消息同构：
     *   `[{"type":"text","text":...},{"type":"image_url","image_url":{"url":...}}]`
     */
    internal fun buildRequestJson(
        model: String,
        system: String,
        userContext: String,
        reply: Boolean = false,
        imageDataUrl: String? = null
    ): String =
        buildJsonObject {
            put("model", model)
            put("stream", false)
            put("max_tokens", if (reply) 400 else 120)
            put("temperature", 0.9)
            val userText = if (reply) userContext else "现在时间：$userContext。请生成这条主动消息。"
            val image = imageDataUrl?.takeIf { it.isNotBlank() }
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
                            if (image == null) {
                                put("content", userText)
                            } else {
                                put(
                                    "content",
                                    JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", userText)
                                            },
                                            buildJsonObject {
                                                put("type", "image_url")
                                                putJsonObject("image_url") { put("url", image) }
                                            }
                                        )
                                    )
                                )
                            }
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
