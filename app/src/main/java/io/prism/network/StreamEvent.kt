package io.prism.network

/**
 * OpenAI 兼容 chat/completions 流式响应的解码结果（ADR-004 4.2 / ADR-014 5.1）。
 *
 * 流式 SSE 被解码为一串 [StreamEvent]，由 [Flow] 暴露给调用方：
 * - [Delta]：单个增量 token（`choices[0].delta.content`）
 * - [Done]：收到 `[DONE]` 终止信号
 * - [Error]：网络 / 协议 / 鉴权错误
 * - [ToolCallStart]：检测到新 tool_call 开始（M4，第一个 delta 携带工具名时发射）
 * - [ToolCallDelta]：tool_call arguments 增量片段（M4，可选，UI 实时展示参数构建）
 * - [ToolCallComplete]：tool_call 完整可执行（M4，finish_reason=tool_calls 时发射，arguments 已解析）
 *
 * 密封类保证调用方用 `when` 穷尽分支，避免遗漏（Karpathy Guidelines）。
 *
 * **M4 设计**（ADR-014 5.1）：[ToolCallStart] / [ToolCallDelta] / [ToolCallComplete]
 * 命名与语义不绑定任何 Provider 协议（Provider 中立），[ToolCallComplete.arguments]
 * 已解析为 [Map]，调用方无需处理 JSON string。现有 [Delta] / [Done] / [Error] 语义不变，向后兼容。
 */
sealed class StreamEvent {
    /** 增量 token 内容（可能为空白，需自行 trim 判断）。 */
    data class Delta(val content: String) : StreamEvent()

    /** 流式结束（`[DONE]` 或正常完成）。 */
    data object Done : StreamEvent()

    /** 错误（端点不可达 / 超时 / 401 / 流中断 / 解析失败）。 */
    data class Error(val message: String) : StreamEvent()

    /**
     * 检测到新 tool_call 开始（M4，ADR-014 5.1）。
     *
     * 第一个 delta 携带工具名时发射，UI 可立即展示"正在调用工具: {toolName}"。
     *
     * @property toolCallId 工具调用 id（OpenAI `call_xxx`，用于关联 arguments 与 result）
     * @property toolName 工具名（命名空间隔离后的 `skillName__toolName`）
     * @property index 并行 tool_call 的索引（OpenAI delta 按 index 区分）
     */
    data class ToolCallStart(
        val toolCallId: String,
        val toolName: String,
        val index: Int
    ) : StreamEvent()

    /**
     * tool_call arguments 增量片段（M4，ADR-014 5.1）。
     *
     * 可选事件，调用方可忽略只关注 [ToolCallComplete]。UI 可用于实时展示参数构建过程。
     *
     * @property toolCallId 工具调用 id
     * @property argumentsFragment arguments JSON string 的增量片段
     */
    data class ToolCallDelta(
        val toolCallId: String,
        val argumentsFragment: String
    ) : StreamEvent()

    /**
     * tool_call 完整可执行（M4，ADR-014 5.1）。
     *
     * `finish_reason == "tool_calls"` 时发射，[arguments] 已解析为 [Map]。
     * 调用方收到此事件后触发工具执行回路（用户确认 → 执行 → 结果回灌）。
     *
     * @property toolCallId 工具调用 id
     * @property toolName 工具名
     * @property arguments 已解析的参数 Map（JSON 已 parse）
     */
    data class ToolCallComplete(
        val toolCallId: String,
        val toolName: String,
        val arguments: Map<String, Any?>
    ) : StreamEvent()
}