package io.prism.skill

import android.util.Log
import io.prism.network.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 反问/澄清本地工具执行器（UXR8 N2 Phase 2，ADR-030）—— `ask_user__ask`。
 *
 * **背景**：LLM 面对需求歧义（实体/版本/标准不明确、缺失信息实质改变答案）时，
 * 与其反复用同义词重试工具直至 maxRounds 硬终止，不如**主动向用户澄清**。
 * Anthropic Claude 的 AskUserQuestion 工具模式：工具"执行"不是后端调用，
 * 而是暂停当前回答流程、弹出结构化提问卡片，用户答复后继续。
 *
 * **工作流**（StopAtTools 语义）：
 * 1. LLM 调用 `ask_user__ask`，参数为 Anthropic 兼容 `questions[]` 数组：
 *    `[{question: "...", options: [{label, description}], multiSelect: bool}, ...]`
 * 2. [execute] 校验参数（白名单，选项来自 LLM 生成需校验，PRD UXR8 非功能 §5）并
 *    返回**特殊标记前缀** `[需要用户回答]` + JSON 载荷；
 * 3. [SkillExecutor.executeLoop] 检测到该前缀 → 发射 [StreamEvent.AskUser] + 中断本轮；
 * 4. UI 展示提问卡片，用户答复作为下一条 user 消息进入下一轮。
 *
 * **安全边界**（PRD UXR8 §5）：问题/选项均为 LLM 生成文本，长度截断防 token 膨胀；
 * 每条问题必须有非空 question；option 数量上限（防 LLM 生成海量选项撑爆 UI）。
 *
 * **可测性**（BR-testing-004）：纯 JVM，无 Android Context 依赖（Log 静态方法
 * 在 isReturnDefaultValues=true 下安全）。
 */
class AskUserLocalToolExecutor : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = toolName == TOOL_ASK

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        // 容错解析：LLM 可能传 "questions" 数组；缺失/非法 → 返回错误文案回灌（非崩溃）
        val questions = parseQuestions(arguments) ?: return "缺少必需参数 questions（数组，每项含 question 与可选 options/multiSelect）"
        if (questions.isEmpty()) return "questions 不能为空"
        if (questions.size > MAX_QUESTIONS) {
            return "问题数量过多（${questions.size} > 上限 $MAX_QUESTIONS），请合并为更少问题"
        }
        val payload = Json.encodeToString(AskUserPayload.serializer(), AskUserPayload(questions))
        Log.i(TAG, "ask_user payload: ${payload.take(LOG_PAYLOAD_MAX_LEN)}")
        return "$RESULT_MARKER$payload"
    }

    /**
     * 从 LLM 工具参数解析问题列表（纯函数，可测）。
     *
     * 参数结构（Anthropic AskUserQuestion 兼容）：
     * `{"questions": [{"question": "...", "options": [{"label": "...", "description": "..."}], "multiSelect": false}]}`
     *
     * 校验：question 非空；options 每项 label 非空；multiSelect 缺省 false。
     * 无法解析为合法结构时返回 null（调用方降级为错误文案）。
     */
    internal fun parseQuestions(arguments: Map<String, Any?>): List<AskUserQuestion>? {
        val raw = arguments["questions"] ?: return null
        val list = raw as? List<*> ?: return null
        val out = mutableListOf<AskUserQuestion>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val questionText = (map["question"] as? String)?.trim().orEmpty()
            if (questionText.isEmpty()) continue
            val options = (map["options"] as? List<*>)
                ?.mapNotNull { opt ->
                    val om = opt as? Map<*, *> ?: return@mapNotNull null
                    val label = (om["label"] as? String)?.trim().orEmpty()
                    if (label.isEmpty()) return@mapNotNull null
                    val desc = (om["description"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    AskUserOption(label.take(OPTION_LABEL_MAX_LEN), desc?.take(OPTION_DESC_MAX_LEN))
                }
                ?.take(MAX_OPTIONS_PER_QUESTION)
                ?: emptyList()
            val multiSelect = (map["multiSelect"] as? Boolean) ?: false
            out.add(
                AskUserQuestion(
                    question = questionText.take(QUESTION_MAX_LEN),
                    options = options,
                    multiSelect = multiSelect
                )
            )
        }
        return out
    }

    /** 构建 `ask_user__ask` 工具定义（供 [buildTools] 合并；纯函数可测）。 */
    companion object {
        /** 反问工具命名空间前缀。 */
        const val NAMESPACE_PREFIX = "ask_user__"

        /** 反问工具名（`ask_user__ask`）。 */
        const val TOOL_ASK = "${NAMESPACE_PREFIX}ask"

        /**
         * 结果标记前缀。SkillExecutor 检测到该前缀 → 发射 [StreamEvent.AskUser] 并中断本轮。
         * 前缀后紧跟 JSON 载荷（[AskUserPayload] 序列化）。
         */
        const val RESULT_MARKER = "【需要用户回答】"

        /** 单条问题最大长度（防 token 膨胀）。 */
        internal const val QUESTION_MAX_LEN = 200

        /** 单条选项 label 最大长度。 */
        internal const val OPTION_LABEL_MAX_LEN = 60

        /** 单条选项 description 最大长度。 */
        internal const val OPTION_DESC_MAX_LEN = 120

        /** 单次调用问题数量上限（防 LLM 一次性生成海量问题）。 */
        internal const val MAX_QUESTIONS = 3

        /** 每个问题的选项数量上限（防选项撑爆 UI）。 */
        internal const val MAX_OPTIONS_PER_QUESTION = 8

        /** 日志载荷截断长度（BR-error-handling-016/CWE-532）。 */
        private const val LOG_PAYLOAD_MAX_LEN = 120

        private const val TAG = "AskUserTool"

        /** 供 ConversationViewModel 构建工具定义时调用。 */
        fun buildToolDefinition(): ToolDefinition {
            val optionsSchema = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "label" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("选项短标签（用户点选后显示）")
                                )
                            ),
                            "description" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("选项补充说明（可选，帮助用户判断）")
                                )
                            )
                        )
                    ),
                    "required" to JsonArray(listOf(JsonPrimitive("label"))),
                    "additionalProperties" to JsonPrimitive(false)
                )
            )
            val questionSchema = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "question" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("向用户澄清的问题（一次一问，具体明确）")
                                )
                            ),
                            "options" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("array"),
                                    "description" to JsonPrimitive("建议选项列表（可选；不提供时用户自由输入）"),
                                    "items" to optionsSchema
                                )
                            ),
                            "multiSelect" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("boolean"),
                                    "description" to JsonPrimitive("是否允许多选（默认 false）")
                                )
                            )
                        )
                    ),
                    "required" to JsonArray(listOf(JsonPrimitive("question"))),
                    "additionalProperties" to JsonPrimitive(false)
                )
            )
            return ToolDefinition(
                function = ToolDefinition.FunctionDef(
                    name = TOOL_ASK,
                    description = "当用户需求存在实体/版本/标准等歧义、且缺失信息会实质改变答案时，" +
                        "用本工具以提问卡片形式向用户澄清（一次一问，给出建议选项）。" +
                        "不要用反复同义词重试搜索代替澄清。问题需具体明确，选项需可判断。",
                    parameters = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "properties" to JsonObject(
                                mapOf(
                                    "questions" to JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("array"),
                                            "description" to JsonPrimitive("澄清问题列表（1-3 个）"),
                                            "items" to questionSchema
                                        )
                                    )
                                )
                            ),
                            "required" to JsonArray(listOf(JsonPrimitive("questions"))),
                            "additionalProperties" to JsonPrimitive(false)
                        )
                    )
                )
            )
        }
    }
}

/** 反问问题（纯数据，可序列化，供 UI 展示）。 */
@kotlinx.serialization.Serializable
data class AskUserQuestion(
    val question: String,
    val options: List<AskUserOption> = emptyList(),
    val multiSelect: Boolean = false
)

/** 反问选项（纯数据，可序列化）。 */
@kotlinx.serialization.Serializable
data class AskUserOption(
    val label: String,
    val description: String? = null
)

/** 反问工具执行结果载荷（execute 返回标记前缀 + 本载荷 JSON）。 */
@kotlinx.serialization.Serializable
internal data class AskUserPayload(
    val questions: List<AskUserQuestion>
)
