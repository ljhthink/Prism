package io.prism.fs

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UI 确认门禁 —— 经 [MutableSharedFlow] 暴露确认请求，UI 弹「AI 请求执行工具」对话框，
 * 用户「允许 / 拒绝」后响应；[confirm] 挂起直至收到响应（ADR-006 5.4）。
 *
 * **线程模型**：工具处理器在 Server 的 handler 协程（默认 Dispatchers.Default）中调用
 * [confirm]，此处经 [CompletableDeferred] 挂起；UI 在 UI 线程收集 [requests] 并向
 * [respond] 返回结果，往返安全。挂起的协程与 UI 无共享可变状态，无竞态。
 *
 * **并发**：同一时刻可挂起多个确认请求，各自以自增 id 独立响应；UI 侧按到达顺序逐个展示。
 *
 * **超时兜底（BR-concurrency-003）**：[confirm] 经 [withTimeoutOrNull] 等待，超时（默认 30s）
 * 按拒绝处理返回 false，避免 UI 宿主缺失或用户不响应时 `await()` 永久挂起。
 * [MutableSharedFlow] 使用 [BufferOverflow.DROP_OLDEST]：宿主未收集时丢弃最早请求，配合超时
 * 兜底保证 `confirm` 永不永久阻塞。
 *
 * **2026-11-22（guardrail M-3）**：超时/取消路径必须
 * 1. `finally` 里移除 `pending[id]`（否则长会话无界增长）；
 * 2. 通过 [expired] 事件通知 UI **把对话框撤掉**——否则用户对着一张"已失效"的卡片点「允许」，
 *    动作不会执行也没有任何反馈，与用户报的"一直说检测到敏感操作"是同一类体验缺陷。
 */
class UiConfirmationGate : ToolConfirmationGate {

    /** 一次待确认请求。 */
    data class PendingConfirm(
        val id: Long,
        val toolName: String,
        val arguments: Map<String, Any?>
    )

    private val _requests = MutableSharedFlow<PendingConfirm>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** 待确认请求流（UI 收集并展示对话框）。 */
    val requests: SharedFlow<PendingConfirm> = _requests.asSharedFlow()

    /**
     * 已失效请求流（超时/被取消）——UI 收到后应**撤掉对应对话框**并给一句已失效提示。
     * 2026-11-22（guardrail M-3）：只清 pending 不通知 UI，会留下可点但无效的卡片。
     */
    private val _expired = MutableSharedFlow<Long>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val expired: SharedFlow<Long> = _expired.asSharedFlow()

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val seq = AtomicLong(0L)

    override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean =
        confirm(toolName, arguments, CONFIRM_TIMEOUT_MILLIS)

    /**
     * 带自定义超时的确认（2026-11-22）：手机操控的**高危动作**（购买/发送/删除）常发生在
     * 用户切到别的 App 之后，需要独立的等待窗口。
     *
     * @param timeoutMs 等待用户响应的上限（毫秒）；超时按拒绝处理
     */
    suspend fun confirm(
        toolName: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long
    ): Boolean {
        val id = seq.incrementAndGet()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        try {
            _requests.emit(PendingConfirm(id, toolName, arguments))
            // 超时按拒绝处理（拒绝方向安全，AD：confirm=false → isError，不执行文件操作）
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            // guardrail M-3：无论正常响应 / 超时 / 协程取消，都要摘除并告知 UI 撤卡片。
            val wasPending = pending.remove(id) != null
            if (wasPending && !deferred.isCompleted) {
                _expired.tryEmit(id)
            }
        }
    }

    /**
     * 响应当前确认请求。
     *
     * @param id 请求 id（来自 [PendingConfirm.id]）
     * @param allow true 允许 / false 拒绝
     * @return true=该请求仍在等待（已生效）；false=已超时/已失效（UI 应提示"已失效，未执行"）
     */
    fun respond(id: Long, allow: Boolean): Boolean =
        pending.remove(id)?.complete(allow) ?: false

    internal companion object {
        const val CONFIRM_TIMEOUT_MILLIS = 30_000L
    }
}
