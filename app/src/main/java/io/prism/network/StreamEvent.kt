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
 *
 * **问题 8a 设计**（ADR-020）：[ReasoningDelta] 表示深度思考模式（thinking）的推理过程增量
 * （DeepSeek `delta.reasoning_content`），与最终答案 [Delta]（`delta.content`）语义区分。
 * UI 可据此将思考过程与最终答案分开展示。
 */
sealed class StreamEvent {
    /** 增量 token 内容（可能为空白，需自行 trim 判断）。 */
    data class Delta(val content: String) : StreamEvent()

    /**
     * 深度思考推理过程增量（问题 8a，ADR-020）。
     *
     * 深度思考模式下（`thinking` 参数开启），DeepSeek 流式响应先输出 `delta.reasoning_content`
     * （思考过程），后输出 `delta.content`（最终答案）。本事件携带思考过程增量片段，
     * 由调用方按需展示（如独立样式 / 折叠区域），避免与最终答案混淆。
     */
    data class ReasoningDelta(val content: String) : StreamEvent()

    /** 流式结束（`[DONE]` 或正常完成）。 */
    data object Done : StreamEvent()

    /** 错误（端点不可达 / 超时 / 401 / 流中断 / 解析失败）。 */
    data class Error(
        val message: String,
        /**
         * v1 US-301（方案 B 云端视觉旁路）：是否为「模型不支持图片（视觉）」信号。
         *
         * 由 [OpenAICompatibleProvider.mapHttpError] 在「含图 + 400 + 错误详情含视觉不支持
         * 关键词」时置 true。调用方（ConversationViewModel）据此触发云端视觉旁路（视觉
         * Provider 生成描述 → 注入文本模型）或 OCR 兜底，而非仅展示错误提示。
         *
         * **向后兼容**：默认 false，现有 `when` 穷尽匹配与既有测试不受影响。
         */
        val visionUnsupported: Boolean = false,
        /**
         * v1 批次11（E，D11）：服务端 429 限流响应头 `Retry-After` 建议的等待秒数。
         *
         * 由 [OpenAICompatibleProvider] 在 429 时从响应头解析（可为 null）；调用方
         * [io.prism.skill.SkillExecutor] 限流退避时优先采纳该值（行业标准，优于固定 3s×2^n）。
         * 默认 null 向后兼容（无 Retry-After 时走既有指数退避）。
         */
        val retryAfterSeconds: Long? = null
    ) : StreamEvent()

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

    /**
     * LLM 主动反问/澄清（UXR8 N2 Phase 2，ADR-030）。
     *
     * `ask_user__ask` 本地工具被执行后由 [io.prism.skill.SkillExecutor.executeLoop]
     * 检测结果标记前缀并发射本事件，同时中断当前工具回路（StopAtTools 语义）。
     * 调用方（UI 层）收到后展示结构化提问卡片，用户答复作为下一条 user 消息进入下一轮。
     *
     * @property questions 澄清问题列表（来自 LLM 生成，长度已截断校验）
     */
    data class AskUser(
        val questions: List<io.prism.skill.AskUserQuestion>
    ) : StreamEvent()
}