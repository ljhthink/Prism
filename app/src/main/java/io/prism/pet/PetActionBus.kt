package io.prism.pet

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 批次24（ADR-051 D8）：桌宠**跨层事件总线** —— 应用级广播，供悬浮窗 Service 订阅。
 *
 * **为什么必须有它**（本项目既有架构的硬约束，考古报告 §五已逐条证实）：
 * 全仓**无事件总线**（`SharedFlow` 之外的 EventBus/LiveData/Channel 零命中），
 * 且聊天状态全部落在 `ConversationViewModel`（`isTyping` / `messages` / `streamingIds`）
 * 与 `VoiceSessionController`（`phase`）这些**ViewModel 作用域**的对象上——
 * 而桌宠悬浮窗是**前台 Service**，拿不到任何 ViewModel。
 * 直接让 Service 去"找" ViewModel 会引入生命周期耦合与内存泄漏；
 * 因此需要一条 Service 可达的单向广播通道。
 *
 * **方向是单向的**（生产者 → 桌宠），桌宠**不反向修改**任何会话状态，
 * 因此不存在回声/环路风险。
 *
 * **背压策略**：`extraBufferCapacity = 8` + [BufferOverflow.DROP_OLDEST]。
 * 桌宠是**尽力而为**的装饰性消费者：它卡顿时宁可丢掉过期的口型事件，
 * 也绝不能反压会话主链路（`tryEmit` 永不挂起、永不失败）。
 *
 * **无消费者时零开销**：没有订阅者时 [emit] 只是往 buffer 里放，随后被丢弃。
 */
object PetActionBus {

    /** 桌宠可见/可交互状态的外部事件。 */
    sealed interface Event {

        /**
         * 语音播报起止（驱动口型）。
         *
         * @property active 是否正在播报
         * @property durationMs 预计播报时长（毫秒）；[active]=false 时无意义。
         *   **来源是估算值而非真实音频时长**：项目现有 TTS 引擎（`SystemTtsEngine`）未覆写
         *   `onRangeStart`，且 `VoiceSessionController.volume` 在 SPEAKING 期为死值（考古取证），
         *   因此无法做真正的音量/音素级同步，只能按文本长度估算匀速口型——
         *   这与上游 `mouth-sync.ts` 的做法一致（它同样只拿到 `durationMs`）。
         */
        data class Speaking(val active: Boolean, val durationMs: Long = 0L) : Event

        /**
         * 展示对话气泡。
         *
         * @property text 气泡正文（**完整正文，未裁剪**）。裁剪（80 字 + 省略号）由
         *   **消费方** [io.prism.pet.overlay.PetOverlayService] 在展示前完成——
         *   这里刻意保持"生产者只提供事实、消费者决定呈现"的分工
         *   （guardrail L-6：早期 KDoc 曾写成"已由生产方裁剪"，与实现不符）。
         */
        data class Bubble(val text: String) : Event

        /** 播放动作别名（供会话侧主动触发，例如未来做"情绪驱动形象"）。 */
        data class PlayAction(val alias: String) : Event

        /** 形象变更后通知刷新。 */
        data class AppearanceChanged(val appearanceId: String?) : Event
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 订阅入口（悬浮窗 Service 消费）。 */
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /**
     * 广播事件（非挂起，永不失败）。
     *
     * 刻意用 `tryEmit` 而非 `emit`：调用点分散在语音与聊天热路径上，
     * 任何"为了桌宠而挂起主链路"的可能性都必须被结构性排除。
     */
    fun emit(event: Event) {
        _events.tryEmit(event)
    }
}
