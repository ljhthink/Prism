package io.prism.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 聊天界面 ViewModel —— 管理消息列表与打字状态。
 *
 * 响应式状态：用 [MutableStateFlow] 暴露 [messages] 与 [isTyping]，
 * Compose 通过 `collectAsState()` 订阅渲染（ADR-002 4.2）。
 *
 * **当前范围**：[sendMessage] 追加用户消息 → 进入打字态 [isTyping] →
 * 短暂延迟后追加占位 AI 回复（含引用来源 [ChatMessage.source]），仅更新 UI 不接网络
 * （US-005 AC-4）。US-006 在此处接入真实流式请求。
 *
 * US-005 AC-3：ConversationViewModel 用 StateFlow 暴露消息列表。
 */
class ConversationViewModel : ViewModel() {

    private var nextId = 0L

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** 消息列表（只读 StateFlow） */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    /** AI 是否正在回复（打字指示 + 「正在调用 MCP 检索知识库…」状态） */
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    /**
     * 发送一条消息。
     *
     * 追加用户消息 → 进入打字态 → 短延迟后追加占位 AI 回复（Mock，US-006 前）。
     * 空白输入（trim 后为空）忽略。
     *
     * @param text 用户输入文本
     */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val now = System.currentTimeMillis()
        val newMessages = _messages.value.toMutableList()
        newMessages += ChatMessage(nextId++, Role.USER, trimmed, now)
        _messages.value = newMessages

        viewModelScope.launch {
            _isTyping.value = true
            delay(1400)
            val reply = _messages.value.toMutableList()
            val replyTime = System.currentTimeMillis()
            reply += ChatMessage(
                id = nextId++,
                role = Role.ASSISTANT,
                content = "已基于你的「工作」知识库检索到相关内容。核心结论来自 2 篇文档，需要我展开要点吗？",
                timestamp = replyTime,
                source = "Q3规划.pdf · p.12"
            )
            _messages.value = reply
            _isTyping.value = false
        }
    }
}