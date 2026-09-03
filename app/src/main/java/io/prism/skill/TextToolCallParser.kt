package io.prism.skill

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 文本型工具调用解析器（v1 批次12，A/D13）。
 *
 * **背景**：glm-4.6v-flash 等模型不产生 OpenAI 原生 `tool_calls`（结构化函数调用未被该模型/
 * 端点支持或被理解），而是把工具调用写成**文本型 `<tool_call>` XML 块**（常包裹在 HTML 代码围栏内）。
 * 应用此前只认流式原生 `ToolCallComplete` → 文本块被当正文渲染、工具从不执行（真机"连工具都无法使用"）。
 *
 * 本解析器从模型文本输出中提取这些块，转换为可执行的工具调用（name + 参数 Map），使此类模型
 * 也能驱动工具回路。**模型无关兜底**：不依赖 OpenAI tool 协议（glm 端点可能不认 tool role），
 * 执行结果以【工具执行结果】user 消息回灌。
 *
 * **支持格式**（容忍空白/换行/属性变体）：
 * ```
 * <tool_call>phone_control__launch_app
 * <arg_key>package</arg_key>
 * <arg_value>com.xunmeng.pinduoduo</arg_value>
 * </tool_call>
 * ```
 *
 * 参数值解析：优先 JSON（number/boolean/object/array），否则字符串。
 *
 * **可测性**：全部为纯函数，JVM 直测。
 */
object TextToolCallParser {

    /** 解析出的文本工具调用。 */
    data class TextToolCall(val name: String, val arguments: Map<String, Any?>)

    /**
     * 从文本中解析全部文本工具调用（纯函数可测）。
     *
     * @param text 模型输出文本
     * @return 解析出的工具调用列表（顺序保持）；无则空列表
     */
    fun parse(text: String): List<TextToolCall> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<TextToolCall>()
        for (match in TOOL_CALL_BLOCK.findAll(text)) {
            val block = match.value
            val name = extractName(block) ?: continue
            val args = extractArguments(block)
            result.add(TextToolCall(name, args))
        }
        return result
    }

    /**
     * 从文本中剥离文本工具调用块（含 HTML 代码围栏包裹）——用于 UI 渲染与历史注入时
     * 不显示原始 `<tool_call>` XML（glm 常包裹在 ```html 围栏内）。纯函数可测。
     *
     * @param text 原始文本
     * @return 剥离后的文本（去首尾空白）
     */
    fun stripTextToolCalls(text: String): String {
        if (text.isBlank()) return text
        var result = text
        result = FENCED_TOOL_BLOCK.replace(result, "")
        result = TOOL_CALL_BLOCK.replace(result, "")
        return result.trim()
    }

    /** 从工具块中提取工具名（`<tool_call>` 后到空白/`<` 前的 token）。 */
    private fun extractName(block: String): String? {
        val m = NAME_REGEX.find(block) ?: return null
        val name = m.groupValues[1].trim()
        return name.takeIf { it.isNotBlank() && VALID_NAME_REGEX.matches(it) }
    }

    /** 提取 `<arg_key>k</arg_key>` + `<arg_value>v</arg_value>` 参数对（按出现顺序配对）。 */
    private fun extractArguments(block: String): Map<String, Any?> {
        val args = LinkedHashMap<String, Any?>()
        val keys = KEY_REGEX.findAll(block).map { it.groupValues[1].trim() }.toList()
        val values = VALUE_REGEX.findAll(block).map { it.groupValues[1].trim() }.toList()
        val n = minOf(keys.size, values.size)
        for (i in 0 until n) {
            val k = keys[i]
            if (k.isEmpty() || args.containsKey(k)) continue
            args[k] = parseValue(values[i])
        }
        return args
    }

    /** 参数值解析：优先 JSON（number/boolean/object/array），否则字符串。 */
    private fun parseValue(raw: String): Any? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            jsonToAny(ARGS_JSON.parseToJsonElement(trimmed))
        } catch (_: Exception) {
            trimmed // 非 JSON（普通文本/含中文），原样作为字符串
        }
    }

    /** JSON → Kotlin Map/List/基本类型（供 executeToolCall / encodeArguments 使用）。 */
    private fun jsonToAny(json: JsonElement): Any? = when (json) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            json.isString -> json.content
            json.content == "true" -> true
            json.content == "false" -> false
            json.content == "null" -> null
            else -> json.content.toLongOrNull() ?: json.content.toDoubleOrNull() ?: json.content
        }
        is JsonObject -> json.mapValues { jsonToAny(it.value) }
        is JsonArray -> json.map { jsonToAny(it) }
    }

    /** 完整工具块（容忍属性与空白；非贪婪到 `</tool_call>`）。 */
    private val TOOL_CALL_BLOCK = Regex(
        """<tool_call[^>]*>[\s\S]*?</tool_call>""",
        RegexOption.IGNORE_CASE
    )

    /** HTML 代码围栏包裹的工具块（```lang\n<tool_call>...</tool_call>\n```）。 */
    private val FENCED_TOOL_BLOCK = Regex(
        """```[^\n]*\n\s*<tool_call[^>]*>[\s\S]*?</tool_call>\s*\n\s*```""",
        RegexOption.IGNORE_CASE
    )

    /** 工具名：`<tool_call>` 后到空白/`<` 前的 token。 */
    private val NAME_REGEX = Regex(
        """<tool_call[^>]*>\s*([^\s<]+)""",
        RegexOption.IGNORE_CASE
    )

    /** 合法工具名（与工具命名空间规范一致：字母/数字/下划线）。 */
    private val VALID_NAME_REGEX = Regex("""[A-Za-z0-9_]+""")

    /** `<arg_key>...</arg_key>`。 */
    private val KEY_REGEX = Regex(
        """<arg_key[^>]*>\s*([\s\S]*?)\s*</arg_key>""",
        RegexOption.IGNORE_CASE
    )

    /** `<arg_value>...</arg_value>`。 */
    private val VALUE_REGEX = Regex(
        """<arg_value[^>]*>\s*([\s\S]*?)\s*</arg_value>""",
        RegexOption.IGNORE_CASE
    )

    /** 参数 JSON 解析实例（容错未知字段）。 */
    private val ARGS_JSON = Json { ignoreUnknownKeys = true }
}
