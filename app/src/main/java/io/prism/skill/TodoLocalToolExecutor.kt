package io.prism.skill

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * TODO 任务清单数据项（v1 批次17，ADR-043 D1，Claude Code TodoWrite 同构）。
 *
 * @property content 祈使式任务描述（如「打开淘宝搜索X」）
 * @property activeForm 进行时描述（如「正在淘宝搜索X」），UI 高亮当前步骤
 * @property status 三态：[TodoLocalToolExecutor.STATUS_PENDING] /
 *   [TodoLocalToolExecutor.STATUS_IN_PROGRESS] / [TodoLocalToolExecutor.STATUS_COMPLETED]
 */
data class TodoItem(
    val content: String,
    val activeForm: String,
    val status: String
)

/**
 * TODO 清单状态（会话级内存，ADR-043 D1）。
 *
 * [version] 每次更新递增，UI（TodoCard）按 version 原地更新不新增气泡。
 * 会话切换由 [reset] 清空（TodoListState 不持久化，Claude Code 同款）。
 */
data class TodoListState(
    val items: List<TodoItem> = emptyList(),
    val version: Long = 0L
)

/**
 * TODO 任务规划本地工具执行器（v1 批次17 US-1701，ADR-043 D1）。
 *
 * 单工具 `todo_write` **全量替换**语义（业界共识，Claude Code TodoWrite 同构）：
 * LLM 每次发送完整清单替换旧清单；UI 卡片原地更新；结果以清单快照回灌实现 LLM 持久可见
 *（等价 Roo Code REMINDERS 每轮回灌）。
 *
 * **校验纪律**（Claude Code 三板斧之状态机约束，违规回灌错误提示让 LLM 自纠）：
 * - 清单长度 ≤ [MAX_ITEMS]（防无界列表失控，BabyAGI 反面教训）
 * - 恰好 1 个 in_progress（零个或多个均拒绝）
 * - content 非空
 *
 * **协议**：实现 [LocalToolExecutor]（handles/execute 三方法接口），
 * 由 CompositeLocalToolExecutor 组合注入 SkillExecutor——SkillExecutor 零改动。
 *
 * **状态推送**：[state] StateFlow 直推 UI（ConversationViewModel 暴露 + TodoCard 收集），
 * 不新增 StreamEvent 子类（避免波及 handleStreamEvent 穷尽匹配，Karpathy 简洁优先）。
 *
 * @param clock 时间源（预留可注入测试；当前状态不含时间戳，默认实现无副作用）
 */
class TodoLocalToolExecutor(
    private val clock: () -> Long = System::currentTimeMillis
) : LocalToolExecutor {

    private val _state = MutableStateFlow(TodoListState())

    /** 当前清单状态（UI 收集渲染 TodoCard）。 */
    val state: StateFlow<TodoListState> = _state.asStateFlow()

    /** 会话切换时清空（v1 批次17：ConversationViewModel 在新会话/加载历史时调用）。 */
    fun reset() {
        _state.value = TodoListState()
    }

    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        val items = parseItems(arguments)
        val error = validate(items)
        if (error != null) {
            // guardrail P3-6（CWE-532）：日志只记条数，不回显 LLM 原文（防日志伪造/膨胀）
            Log.w(TAG, "todo_write rejected items=${items.size}")
            return "错误：任务清单未更新——$error。请修正后重新调用 todo_write（发送完整清单替换旧清单）。"
        }
        _state.value = TodoListState(items = items, version = _state.value.version + 1)
        return buildResultText(items)
    }

    companion object {
        private const val TAG = "TodoTool"

        /** 工具名（本地工具，无命名空间前缀——与 web_search__search 平行的顶层能力）。 */
        const val TOOL_NAME = "todo_write"

        /** 清单长度上限（防无界列表失控）。 */
        const val MAX_ITEMS = 8

        const val STATUS_PENDING = "pending"
        const val STATUS_IN_PROGRESS = "in_progress"
        const val STATUS_COMPLETED = "completed"

        /** LLM 工具描述（compact，控制每轮 schema 体积；使用纪律内嵌——Claude Code 描述三板斧）。 */
        const val TOOL_DESCRIPTION =
            "创建或更新你的任务清单（全量替换，发送完整清单）。作为工作计划展示给用户：" +
                "收到多步任务先建清单；开始一项前先置为 in_progress；完成后立即标记 completed（禁止批量补记）；" +
                "未完全达成不得标 completed；发现新任务随时追加。"

        /** 解析 LLM 传入的 todos 参数（防御式：非 List/元素非 Map 逐项跳过）。 */
        internal fun parseItems(arguments: Map<String, Any?>): List<TodoItem> {
            val raw = arguments[KEY_TODOS] as? List<*> ?: return emptyList()
            return raw.mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                val content = (map["content"] as? String)?.trim().orEmpty()
                val activeForm = (map["activeForm"] as? String)?.trim().orEmpty()
                val status = (map["status"] as? String)?.trim().orEmpty()
                if (content.isEmpty()) return@mapNotNull null
                TodoItem(
                    content = content,
                    activeForm = activeForm.ifEmpty { content },
                    status = status
                )
            }
        }

        /**
         * 校验清单（纯函数）：返回 null 表示合法，否则返回可诊断错误原因。
         *
         * @param items 待校验清单
         */
        internal fun validate(items: List<TodoItem>): String? {
            if (items.isEmpty()) return null // 空清单 = 清空状态，合法
            if (items.size > MAX_ITEMS) return "清单最多 $MAX_ITEMS 项（当前 ${items.size} 项）"
            val inProgress = items.count { it.status == STATUS_IN_PROGRESS }
            if (inProgress != 1) return "必须恰好 1 个 in_progress（当前 $inProgress 个）"
            val invalidStatus = items.firstOrNull { it.status !in VALID_STATUSES }
            if (invalidStatus != null) {
                return "非法 status「${invalidStatus.status}」（仅支持 pending/in_progress/completed）"
            }
            return null
        }

        /**
         * 渲染清单快照（回灌给 LLM 的持久可见文本，等价 Roo Code REMINDERS）。
         *
         * 格式：`1.[x] 已完成  2.[→] 正在…  3.[ ] 待办`（completed→[x]，in_progress→[→]，pending→[ ]）。
         *
         * @param items 清单项
         */
        internal fun renderSnapshot(items: List<TodoItem>): String = items.mapIndexed { index, item ->
            val mark = when (item.status) {
                STATUS_COMPLETED -> "[x]"
                STATUS_IN_PROGRESS -> "[→]"
                else -> "[ ]"
            }
            "${index + 1}.$mark ${item.content}"
        }.joinToString("\n")

        private fun buildResultText(items: List<TodoItem>): String {
            val done = items.count { it.status == STATUS_COMPLETED }
            val current = items.firstOrNull { it.status == STATUS_IN_PROGRESS }
            val header = "任务清单已更新（$done/${items.size} 完成）" +
                (current?.let { "，当前：${it.activeForm}" } ?: "")
            return "$header\n${renderSnapshot(items)}\n" +
                "（以上为当前清单快照，继续执行时以此为准；全部完成后向用户汇报结果）"
        }

        /**
         * 构建 `todo_write` 工具定义（US-1702，注入 LLM tools 数组）。
         *
         * schema 遵循 JSON Schema 合法形态（`type:array + items:object`，BR-tool-schema），
         * 防严格端点 400；maxItems=[MAX_ITEMS]。
         */
        fun buildToolDefinition(): io.prism.network.ToolDefinition {
            val parameters = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "todos" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("array"),
                                    "description" to JsonPrimitive("完整任务清单（全量替换旧清单，按执行顺序排列）"),
                                    "maxItems" to JsonPrimitive(MAX_ITEMS),
                                    "items" to JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("object"),
                                            "required" to JsonArray(
                                                listOf(JsonPrimitive("content"), JsonPrimitive("status"))
                                            ),
                                            "properties" to JsonObject(
                                                mapOf(
                                                    "content" to JsonObject(
                                                        mapOf(
                                                            "type" to JsonPrimitive("string"),
                                                            "description" to JsonPrimitive("祈使式任务描述，如「打开淘宝搜索X」")
                                                        )
                                                    ),
                                                    "activeForm" to JsonObject(
                                                        mapOf(
                                                            "type" to JsonPrimitive("string"),
                                                            "description" to JsonPrimitive("进行时描述，如「正在淘宝搜索X」（执行中高亮展示）")
                                                        )
                                                    ),
                                                    "status" to JsonObject(
                                                        mapOf(
                                                            "type" to JsonPrimitive("string"),
                                                            "enum" to JsonArray(
                                                                listOf(
                                                                    JsonPrimitive(STATUS_PENDING),
                                                                    JsonPrimitive(STATUS_IN_PROGRESS),
                                                                    JsonPrimitive(STATUS_COMPLETED)
                                                                )
                                                            )
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    ),
                    "required" to JsonArray(listOf(JsonPrimitive("todos")))
                )
            )
            return io.prism.network.ToolDefinition(
                function = io.prism.network.ToolDefinition.FunctionDef(
                    name = TOOL_NAME,
                    description = TOOL_DESCRIPTION,
                    parameters = parameters
                )
            )
        }
    }
}

private val VALID_STATUSES = setOf(
    TodoLocalToolExecutor.STATUS_PENDING,
    TodoLocalToolExecutor.STATUS_IN_PROGRESS,
    TodoLocalToolExecutor.STATUS_COMPLETED
)

private const val KEY_TODOS = "todos"
