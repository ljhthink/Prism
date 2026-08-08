package io.prism.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Provider 中立的工具定义（M4，ADR-014 5.2）。
 *
 * 采用 OpenAI 嵌套结构（`type + function`），因当前仅 [OpenAICompatibleProvider]；
 * 未来 Anthropic 适配时在 Provider 内部转换为 `input_schema` 扁平结构。
 *
 * **字段语义**：
 * - [type] 工具类型，OpenAI 固定 `"function"`
 * - [function] 函数定义（名称、描述、参数 JSON Schema）
 *
 * @see ToolChoice
 */
@Serializable
data class ToolDefinition(
    val type: String = TYPE_FUNCTION,
    val function: FunctionDef
) {
    /** OpenAI tool 类型固定为 function（Chat Completions 流式仅支持 function）。 */
    companion object {
        const val TYPE_FUNCTION = "function"
    }

    /**
     * 函数定义。
     *
     * @property name 工具名（命名空间隔离后的 `skillName__toolName`）
     * @property description 工具描述（供 LLM 决策调用）
     * @property parameters JSON Schema 描述参数结构（[JsonElement] 因 Schema 结构灵活）
     * @property strict 是否启用 strict mode（OpenAI strict mode 保证 arguments 严格遵循 Schema，
     *   需 `additionalProperties: false` + 所有字段在 `required` 中，且与 `parallel_tool_calls` 不兼容）
     */
    @Serializable
    data class FunctionDef(
        val name: String,
        val description: String,
        val parameters: JsonElement,
        val strict: Boolean? = null
    )
}

/**
 * Provider 中立的工具选择策略（M4，ADR-014 5.2）。
 *
 * 用 sealed class 表达穷尽分支（仿 [io.prism.rag.RagTarget] 模式）。
 * 各 Provider 在内部转换为协议特定格式（OpenAI `tool_choice` 字段）。
 */
sealed class ToolChoice {
    /** LLM 自主决定是否调用工具（OpenAI `"auto"`，默认）。 */
    data object Auto : ToolChoice()

    /** 强制调用工具（OpenAI `"required"`）。 */
    data object Required : ToolChoice()

    /** 指定调用某工具（OpenAI `{"type":"function","function":{"name":"..."}}`）。 */
    data class Specific(val name: String) : ToolChoice()

    /** 禁止调用工具（OpenAI `"none"`）。 */
    data object None : ToolChoice()
}
