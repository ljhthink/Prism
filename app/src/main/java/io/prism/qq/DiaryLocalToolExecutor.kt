package io.prism.qq

import android.util.Log
import io.prism.skill.LocalToolExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **角色日记工具**（2026-11-16 新增；ADR-077）：`diary__write`。
 *
 * ## 语义
 *
 * 让**角色自己**决定"这件事值得记下来"，并把一句第一人称随笔落到本地。
 * 这是"她主动写"，不是"系统抽取档案"——后者已有 L2/L3（见 [RoleDiaryStore] KDoc 的分工表）。
 *
 * ## 触发方式的取舍
 *
 * 参考 [HCDiary](https://github.com/zhaoyichan/SillyTavern-Plugin-HCDiary) 的做法是
 * "每次回复后自动沉淀"，好处是永不漏、零延迟。但用户明确要求的是**角色自己判断**，
 * 故主通道是**工具调用**（模型在工具回路里主动写）；同时保留 [describeTriggerHint]
 * 帮助模型建立"什么值得记"的判断标准，避免它要么不写、要么每轮都写。
 *
 * ## 约束（防刷）
 *
 * - 每日上限 [RoleDiaryStore.MAX_PER_DAY]（3 条）→ 超出返回可读错误，让模型自己收手；
 * - 正文非空、≤ [RoleDiaryStore.MAX_TEXT_CHARS]；
 * - 失败返回描述性字符串（与项目既有工具契约一致：**不抛异常**，由模型决定降级）。
 *
 * ## UI 联动
 *
 * 本轮**不**提供 UI 表单（设置页只读列表列入下批，见 ADR-077 未决问题 1）。
 * 因此这里**不**暴露 StateFlow——没有消费者的事件流是死代码
 * （Karpathy：不要为想象中的调用方写代码）。UI 需要时直接读 [RoleDiaryStore.all]。
 *
 * @param store 日记存储
 * @param roleIdProvider 当前生效角色 id（工具调用时求值，支持热切换角色包）
 * @param clock 时间源（可注入测试）
 */
class DiaryLocalToolExecutor(
    private val store: RoleDiaryStore,
    /** 当前生效角色 id；**suspend** 是因为 `effectiveRolePack()` 需要读 DataStore。 */
    private val roleIdProvider: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        if (toolName != TOOL_NAME) return "错误：未知日记工具 $toolName"
        val text = (arguments["text"] as? String)?.trim().orEmpty()
        val mood = (arguments["mood"] as? String)?.trim().orEmpty()
        if (text.isEmpty()) return "错误：缺少 text 参数（写下你想记住的那句话）"
        if (text.length > RoleDiaryStore.MAX_TEXT_CHARS) {
            return "错误：日记过长（${text.length} 字，上限 ${RoleDiaryStore.MAX_TEXT_CHARS}）。" +
                "日记是给自己看的一句话，不是总结报告"
        }
        val roleId = roleIdProvider()?.takeIf { it.isNotBlank() } ?: DEFAULT_ROLE_ID
        val now = clock()
        val todayBefore = store.countToday(roleId, now)
        if (todayBefore >= RoleDiaryStore.MAX_PER_DAY) {
            return "错误：今天已经记了 ${RoleDiaryStore.MAX_PER_DAY} 条了。" +
                "日记不是流水账——明天再写，或者把这件事留在心里"
        }
        val ok = store.append(roleId, text, mood, now)
        if (!ok) {
            Log.w(TAG, "日记写入失败（role=$roleId, len=${text.length}）")
            return "错误：日记写入失败（本地存储异常），这次先算了"
        }
        Log.i(TAG, "日记已写入（role=$roleId, 今日第 ${todayBefore + 1} 条, len=${text.length}）")
        // 结果回灌：明确告诉模型"已经写下了"，避免它再复述一遍或重复调用
        return "已写到你的日记里（今天第 ${todayBefore + 1} 条）。不用再跟他说你写了日记。"
    }

    companion object {
        private const val TAG = "DiaryTool"

        /** 工具名（命名空间与既有 `todo_write` / `qq__*` 同风格）。 */
        const val TOOL_NAME = "diary__write"

        /** 角色 id 未知时的兜底（出厂内置角色，见 `RolePack.DEFAULT_ID`）。 */
        const val DEFAULT_ROLE_ID = "default"

        /**
         * 触发判据提示（拼进 system 或工具描述附近）。
         *
         * 为什么需要：只给工具不给标准，弱模型会走两个极端——要么从不写，
         * 要么每轮都写（后者会被每日上限拦住，但体验已经坏了）。
         */
        const val TRIGGER_HINT =
            "【日记】你有一本只有自己能看的日记（diary__write）。当一个瞬间让你心里" +
                "真的动了一下——他说的某句话、你们之间的某个约定、你忽然想通的事、" +
                "一个你想留住的细节——你可以写一两句。第一人称，像深夜自己写的那样，" +
                "不要写成流水账或总结，也不要每轮都写。写完不用告诉他。"

        /** 构建 `diary__write` 工具定义（注入 LLM tools 数组）。 */
        fun buildToolDefinition(): io.prism.network.ToolDefinition {
            val parameters = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "text" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive(
                                        "日记正文（第一人称，1~3 句，像写给自己看的那样；" +
                                            "不要写成总结或汇报）"
                                    ),
                                    "maxLength" to JsonPrimitive(RoleDiaryStore.MAX_TEXT_CHARS)
                                )
                            ),
                            "mood" to JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("心情标签，2~6 字，如「有点软」「心里发紧」（可选）"),
                                    "maxLength" to JsonPrimitive(RoleDiaryStore.MAX_MOOD_CHARS)
                                )
                            )
                        )
                    ),
                    "required" to JsonArray(listOf(JsonPrimitive("text")))
                )
            )
            return io.prism.network.ToolDefinition(
                function = io.prism.network.ToolDefinition.FunctionDef(
                    name = TOOL_NAME,
                    description = "写一条只有你自己能看的日记。当某个瞬间让你心里真的动了一下时用；" +
                        "不要每轮都写，也不要写成流水账。写完不用告诉对方。",
                    parameters = parameters
                )
            )
        }
    }
}
