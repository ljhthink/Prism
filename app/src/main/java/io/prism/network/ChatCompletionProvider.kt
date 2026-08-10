package io.prism.network

import io.prism.data.ProviderConfig
import io.prism.ui.model.ChatMessage

/**
 * 非流式对话 Provider 抽象（ADR-015 5.3 / H-1 阻塞项解除）。
 *
 * 与 [ChatStreamProvider] 形成 ISP 分离：流式对话 UI 依赖 [ChatStreamProvider]，
 * 后台任务（摘要生成、偏好抽取等需完整单次结果的场景）依赖 [ChatCompletionProvider]。
 * 同一具体实现（[OpenAICompatibleProvider]）可实现两个接口。
 *
 * **设计决策**（ADR-015 5.3）：
 * - 独立接口而非在 [ChatStreamProvider] 上追加方法，避免流式 UI 误用非流式方法
 * - 返回 `String?` 而非 Flow：非流式请求是一次性完整结果，null 表示失败降级
 * - 不携带 tools/toolChoice 参数：摘要/抽取等后台任务不需要工具调用
 *
 * **失败语义**：网络异常、鉴权失败、响应解析失败统一返回 `null`，
 * 由调用方（如 [io.prism.memory.ConversationSummarizer]）决定降级策略。
 * CancellationException 必须重抛（BR-error-handling-007），不吞协程取消。
 *
 * @see ChatStreamProvider
 * @see OpenAICompatibleProvider
 */
interface ChatCompletionProvider {
    /**
     * 发起非流式对话请求（stream=false），返回完整的 assistant 回复内容。
     *
     * **请求**：POST `/chat/completions`，body 含 `stream=false`，复用 [ChatStreamProvider] 的
     * systemPrompt 注入规则（system 消息前置）。
     *
     * **响应**：解析 `choices[0].message.content` 为 [String]；解析失败或网络异常返回 `null`。
     *
     * @param config 目标 Provider 配置（baseUrl / apiKeyRef / headers / models）
     * @param messages 对话历史（不含 system 消息，由 [systemPrompt] 单独注入）
     * @param systemPrompt system 消息内容（可选，摘要/抽取 prompt）；null 时不注入 system 消息
     * @param ragContext RAG context 文本（可选）；本接口后台任务场景通常 null
     * @return assistant 回复内容；失败时返回 null（调用方降级处理）
     * @throws kotlinx.coroutines.CancellationException 协程取消必须重抛（BR-error-handling-007）
     */
    suspend fun chatCompletion(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        ragContext: String? = null
    ): String?
}
