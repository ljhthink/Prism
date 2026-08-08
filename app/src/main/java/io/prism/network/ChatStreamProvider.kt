package io.prism.network

import io.prism.data.ProviderConfig
import io.prism.ui.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * 流式对话 Provider 抽象（US-006，依赖倒置；US-019 扩展 system prompt 注入）。
 *
 * [io.prism.ui.chat.ConversationViewModel] 依赖本接口而非具体网络实现，
 * 便于测试注入 fake，也便于后续接入其他协议（如 Anthropic Messages API）。
 *
 * **US-019 接口扩展**（ADR-012 5.4 方案 C）：
 * - 新增 [systemPrompt] 可选参数：RAG grounding rules / 角色设定，作为 system 消息前置
 * - 新增 [ragContext] 可选参数：检索到的知识库片段拼接文本，作为 user 消息前置
 * - 两参数默认 null，既有调用零改动（向后兼容）
 *
 * @see OpenAICompatibleProvider
 */
interface ChatStreamProvider {
    /**
     * 发起流式对话请求。
     *
     * @param config 目标 Provider 配置（baseUrl / apiKeyRef / headers / models）
     * @param messages 对话历史
     * @param systemPrompt system 消息内容（可选，RAG grounding rules）；null 时不注入 system 消息
     * @param ragContext RAG context 文本（可选，知识库片段拼接）；null 时不注入 context user 消息
     * @return [StreamEvent] 流：增量 [StreamEvent.Delta] → [StreamEvent.Done] / [StreamEvent.Error]
     */
    fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        ragContext: String? = null
    ): Flow<StreamEvent>
}
