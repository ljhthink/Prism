package io.prism.network

import io.prism.data.ProviderConfig
import io.prism.ui.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * 流式对话 Provider 抽象（US-006，依赖倒置）。
 *
 * [ConversationViewModel] 依赖本接口而非具体网络实现，
 * 便于测试注入 fake，也便于后续接入其他协议（如 Anthropic Messages API）。
 *
 * @see OpenAICompatibleProvider
 */
interface ChatStreamProvider {
    /**
     * 发起流式对话请求。
     *
     * @param config 目标 Provider 配置（baseUrl / apiKeyRef / headers / models）
     * @param messages 对话历史
     * @return [StreamEvent] 流：增量 [StreamEvent.Delta] → [StreamEvent.Done] / [StreamEvent.Error]
     */
    fun streamChat(config: ProviderConfig, messages: List<ChatMessage>): Flow<StreamEvent>
}