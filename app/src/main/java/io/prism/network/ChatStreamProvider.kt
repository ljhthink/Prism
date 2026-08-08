package io.prism.network

import io.prism.data.ProviderConfig
import io.prism.ui.model.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * 流式对话 Provider 抽象（US-006，依赖倒置；US-019 扩展 system prompt 注入；M4 扩展 tool_calling）。
 *
 * [io.prism.ui.chat.ConversationViewModel] 依赖本接口而非具体网络实现，
 * 便于测试注入 fake，也便于后续接入其他协议（如 Anthropic Messages API）。
 *
 * **US-019 接口扩展**（ADR-012 5.4 方案 C）：
 * - 新增 [systemPrompt] 可选参数：RAG grounding rules / 角色设定，作为 system 消息前置
 * - 新增 [ragContext] 可选参数：检索到的知识库片段拼接文本，作为 user 消息前置
 *
 * **M4 接口扩展**（ADR-014 5.2）：
 * - 新增 [tools] 可选参数：Skill 声明的工具定义列表，供 LLM 自主决定调用
 * - 新增 [toolChoice] 可选参数：工具选择策略（Auto/Required/Specific/None）
 * - 所有新参数默认 null，既有调用零改动（向后兼容，BR-interface-004 历史过滤器不受影响）
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
     * @param tools 工具定义列表（可选，M4 tool_calling）；null 时 不发送 tools 字段（普通对话）
     * @param toolChoice 工具选择策略（可选，M4）；null 时由 Provider 默认（OpenAI 默认 auto）
     * @return [StreamEvent] 流：增量 [StreamEvent.Delta] / 工具调用事件 → [StreamEvent.Done] / [StreamEvent.Error]
     */
    fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        ragContext: String? = null,
        tools: List<ToolDefinition>? = null,
        toolChoice: ToolChoice? = null
    ): Flow<StreamEvent>
}
