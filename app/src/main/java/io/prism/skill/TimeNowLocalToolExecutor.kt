package io.prism.skill

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * **当前时间工具**（2026-11-22 新增；ADR-080）：`time__now`。
 *
 * ## 为什么需要（真机证据）
 *
 * 聊天链路每轮都会在 user 首行注入 `【现在】…`，但模型**没有能力自己算差值**：
 * 「上周三我们说的那件事」「隔了多久」「现在到十二点半还有几分钟」这类问题它只能猜，
 * 而猜错的代价是"活人感崩塌"（会说出与真实时间矛盾的话）。
 * 给一个只读、零网络的本地时间工具，比让模型心算可靠得多。
 *
 * ## 为什么不放在主聊天默认工具集
 *
 * 主聊天（ConversationViewModel.buildTools）的工具清单被大量既有测试断言，
 * 且那里的时间由消息时间戳天然承载。本工具**只注入 QQ 自动回复的工具回路**
 * （见 `PrismApplication.composeReplyWithTools`），零副作用、零网络、零权限。
 *
 * ## 契约
 *
 * 与项目既有本地工具一致：**不抛异常**，失败返回可读字符串。
 */
class TimeNowLocalToolExecutor(
    private val clock: () -> Long = System::currentTimeMillis
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = toolName == TOOL_NAME

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        if (toolName != TOOL_NAME) return "错误：未知时间工具 $toolName"
        return describe(clock())
    }

    companion object {
        /** 工具名（与 `diary__write` / `todo_write` 同风格）。 */
        const val TOOL_NAME = "time__now"

        private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /** 时段（与 [io.prism.qq.QqProactiveComposer] 的 `formatNow` 口径一致，便于模型对齐）。 */
        fun periodOf(hour: Int): String = when (hour) {
            in 5..8 -> "清晨"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..22 -> "晚上"
            else -> "深夜"
        }

        /** 毫秒 → 可读时间描述（纯函数，便于单测）。 */
        fun describe(nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
            val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
            val week = WEEK[(now.dayOfWeek.value - 1).coerceIn(0, 6)]
            return "现在：${now.format(FORMAT)}（$week·${periodOf(now.hour)}），" +
                "距离今天 0 点已过 ${now.hour} 小时 ${now.minute} 分钟。"
        }

        /** 构建 `time__now` 工具定义（注入 LLM tools 数组）。 */
        fun buildToolDefinition(): io.prism.network.ToolDefinition {
            val parameters = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "type" to kotlinx.serialization.json.JsonPrimitive("object"),
                    "properties" to kotlinx.serialization.json.JsonObject(emptyMap())
                )
            )
            return io.prism.network.ToolDefinition(
                function = io.prism.network.ToolDefinition.FunctionDef(
                    name = TOOL_NAME,
                    description = "获取当前的日期、星期、几点和时段。想知道现在几点、" +
                        "要算隔了多久、要判断是不是深夜时用它——不要凭印象猜时间。",
                    parameters = parameters
                )
            )
        }
    }
}
