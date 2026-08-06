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

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val seq = AtomicLong(0L)

    override suspend fun confirm(toolName: String, arguments: Map<String, Any?>): Boolean {
        val id = seq.incrementAndGet()
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = deferred
        _requests.emit(PendingConfirm(id, toolName, arguments))
        // 超时按拒绝处理（拒绝方向安全，AD：confirm=false → isError，不执行文件操作）
        return withTimeoutOrNull(CONFIRM_TIMEOUT_MILLIS) { deferred.await() } ?: false
    }

    /**
     * 响应当前确认请求。
     *
     * @param id 请求 id（来自 [PendingConfirm.id]）
     * @param allow true 允许 / false 拒绝
     */
    fun respond(id: Long, allow: Boolean) {
        pending.remove(id)?.complete(allow)
    }

    internal companion object {
        const val CONFIRM_TIMEOUT_MILLIS = 30_000L
    }
}