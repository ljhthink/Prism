package io.prism.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.prism.PrismApplication
import io.prism.data.ProviderConfig
import io.prism.data.ProviderConfigRepository
import io.prism.network.ChatStreamProvider
import io.prism.network.StreamEvent
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 聊天界面 ViewModel —— 管理消息列表、打字状态与流式回复。
 *
 * 响应式状态：用 [MutableStateFlow] 暴露 [messages] 与 [isTyping]，
 * Compose 通过 `collectAsState()` 订阅渲染（ADR-002 4.2）。
 *
 * **US-006**：构造注入 [ProviderConfigRepository] + [ChatStreamProvider]，
 * [sendMessage] 由 Mock 替换为真实 SSE 流式请求（ADR-004 4.5）：
 * - 追加用户消息 → 追加空 AI 占位消息 → [isTyping]=true
 * - 订阅 [provider.streamChat]，[StreamEvent.Delta] 增量追加到 AI 消息
 * - [StreamEvent.Done] / [StreamEvent.Error] 后 [isTyping]=false
 * - 无激活 Provider 时追加提示消息而非崩溃
 *
 * [activeProvider] 由仓库 Flow 暴露，替代 ConversationScreen 内 cast 反模式。
 */
class ConversationViewModel(
    private val providerRepository: ProviderConfigRepository,
    private val provider: ChatStreamProvider
) : ViewModel() {

    private var nextId = 0L

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** 消息列表（只读 StateFlow） */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    /** AI 是否正在回复（打字指示 + 「正在调用 MCP 检索知识库…」状态） */
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    /** 当前激活 Provider（订阅仓库，供顶栏副标题展示）。 */
    val activeProvider: StateFlow<ProviderConfig?> = providerRepository.activeProviderFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), providerRepository.activeProviderFlow.value)

    /** 全部已配置 Provider（订阅仓库，供切换选择器展示）。 */
    val providers: StateFlow<List<ProviderConfig>> = providerRepository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), providerRepository.providers.value)

    /** 激活指定 Provider（经仓库单激活事务，US-007）。 */
    fun setActiveProvider(id: Long) {
        providerRepository.setActive(id)
    }

    /**
     * 发送一条消息。
     *
     * 追加用户消息 → 追加空 AI 占位消息 → 进入打字态 → 订阅流式请求实时更新 AI 消息内容。
     * 空白输入（trim 后为空）忽略。
     *
     * @param text 用户输入文本
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val now = System.currentTimeMillis()
        _messages.value += ChatMessage(nextId++, Role.USER, trimmed, now)

        viewModelScope.launch {
            _isTyping.value = true
            val aiId = nextId++
            _messages.value += ChatMessage(aiId, Role.ASSISTANT, "", now)

            val active = providerRepository.activeProviderFlow.value
            val stream = if (active == null) {
                kotlinx.coroutines.flow.flow {
                    emit(StreamEvent.Error("尚未配置激活的 Provider，请在「设置」中添加并激活"))
                }
            } else {
                // 请求历史排除所有空 content 的 AI 消息：既排除刚追加的空占位，
                // 也排除上一轮因服务端零增量（仅 [DONE]）结束而残留的空消息，
                // 避免空 content 消息被严格 API 拒绝（CR-02，guardrail 发现 1）
                val history = _messages.value.filterNot { it.role == Role.ASSISTANT && it.content.isEmpty() }
                provider.streamChat(active, history)
            }

            stream.collect { event ->
                when (event) {
                    is StreamEvent.Delta -> appendDelta(aiId, event.content)
                    StreamEvent.Done -> _isTyping.value = false
                    is StreamEvent.Error -> {
                        appendDelta(aiId, "\n\n⚠️ ${event.message}")
                        _isTyping.value = false
                    }
                }
            }
        }
    }

    /** 将增量 token 追加到指定 AI 消息末尾（不可变 StateFlow 更新）。 */
    private fun appendDelta(aiId: Long, delta: String) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == aiId) msg.copy(content = msg.content + delta) else msg
        }
    }

    companion object {
        /** 供 [androidx.lifecycle.viewmodel.compose.viewModel] initializer 使用的工厂。 */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PrismApplication
                ConversationViewModel(
                    providerRepository = app.providerConfigRepository,
                    provider = app.openAICompatibleProvider
                )
            }
        }
    }
}