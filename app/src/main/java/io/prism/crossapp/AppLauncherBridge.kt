package io.prism.crossapp

import android.content.Intent
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Activity 上下文桥接器（M6，ADR-016）。
 *
 * 仿 [io.prism.fs.UiConfirmationGate] 的桥接模式（SharedFlow + CompletableDeferred），
 * 将 ActivityResult 异步回调桥接为 suspend 函数，供 [CrossAppLocalToolExecutor] 在
 * SkillExecutor 协程中调用。
 *
 * **线程模型**（与 UiConfirmationGate 一致）：
 * - 工具处理器在 SkillExecutor 协程中调用 [requestIntent]，经 CompletableDeferred 挂起
 * - UI 在 Compose 层收集 [requests] 流，调用 `launcher.launch(intent)`，回调中调用 [respond]
 * - 挂起协程与 UI 无共享可变状态，无竞态
 *
 * **超时兜底**（ADR-016 R2 缓解，BR-concurrency-003，BR-concurrency-005）：
 * [requestIntent] 经 [withTimeoutOrNull] 等待，超时（默认 25s，**短于** [io.prism.skill.SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS] 的 30s）
 * 返回失败描述，避免 UI 宿主缺失或用户切后台时永久挂起。内层超时短于外层保证 bridge 先超时返回语义化文案 + 清理 pending。
 *
 * **协程取消安全**（BR-error-handling-007）：
 * [requestIntent] 内部 withTimeoutOrNull 不抛 CancellationException（超时返回 null），
 * 但外部协程取消仍会正常传播（CompletableDeferred 被清理）。
 */
class AppLauncherBridge {

    /**
     * 一次待处理的 Intent 请求。
     *
     * @property id 请求 id（由 [requestIntent] 分配，UI 回调时传回 [respond]）
     * @property intent 待启动的 Intent（UI 层 `launcher.launch(intent)`）
     */
    data class PendingIntentRequest(
        val id: Long,
        val intent: Intent
    )

    private val _requests = MutableSharedFlow<PendingIntentRequest>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** 待处理请求流（UI 收集并触发 launcher）。 */
    val requests: SharedFlow<PendingIntentRequest> = _requests.asSharedFlow()

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val seq = AtomicLong(0L)

    /**
     * 请求启动 Intent 并等待 ActivityResult。
     *
     * **流程**：
     * 1. 分配 id + 创建 CompletableDeferred
     * 2. emit [PendingIntentRequest] 到 [requests] 流（UI 收集后 launcher.launch）
     * 3. [withTimeoutOrNull] 等待 [respond] 回调
     * 4. 超时返回失败描述；成功返回结果文本
     *
     * @param intent 待启动的 Intent
     * @param timeoutMs 超时毫秒（默认 25s，**短于** [io.prism.skill.SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS] 的 30s，
     *   保证 bridge 先超时返回语义化文案 + 清理 pending，BR-concurrency-005）
     * @return 结果文本（成功/失败描述，回灌给 LLM）
     */
    suspend fun requestIntent(
        intent: Intent,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String {
        val id = seq.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        _requests.emit(PendingIntentRequest(id, intent))
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)
        return result ?: run {
            Log.w(TAG, "intent request timeout: $intent ($timeoutMs ms)")
            "跨 App 调用超时（${timeoutMs}ms），未收到结果"
        }
    }

    /**
     * 响应 Intent 请求（UI 层在 ActivityResult 回调中调用）。
     *
     * @param id 请求 id（来自 [PendingIntentRequest.id]）
     * @param result 结果文本（成功/失败描述）
     */
    fun respond(id: Long, result: String) {
        pending.remove(id)?.complete(result)
    }

    /**
     * 清理所有待处理请求（Activity 销毁时调用，避免泄漏，ADR-016 R2 缓解）。
     *
     * 将所有未完成的 deferred 完成"已取消"消息，避免协程永久挂起。
     */
    fun cancelAll() {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            entry.value.complete("跨 App 调用已取消（Activity 销毁）")
        }
    }

    internal companion object {
        private const val TAG = "AppLauncherBridge"

        /**
         * 默认超时（25s）。
         *
         * **M6 Phase C M-1 修复**（guardrail-enforcer TKN-M6-PHASEC-GUARDRAIL-001 M-1 中危发现）：
         * 原值 30s 与 [io.prism.skill.SkillExecutor.DEFAULT_TOOL_TIMEOUT_MS]（30s）相同，
         * 当两者同时开始计时，SkillExecutor 的外层 [kotlinx.coroutines.withTimeout] 会先超时
         * （[kotlinx.coroutines.withTimeoutOrNull] 仅捕获自身作用域的 TimeoutCancellationException，
         * 外部取消会正常传播），抛出 [kotlinx.coroutines.TimeoutCancellationException]，导致：
         *
         * 1. 本 bridge 的 `pending.remove(id)` 在外部取消路径中不执行（残留）
         * 2. 本 bridge 的语义化超时文案 `"跨 App 调用超时"` 永远不会返回（被通用文案覆盖）
         *
         * **正确修复方向**（BR-concurrency-005）：内层超时必须**短于**外层超时。
         * 调整为 25s（短于 SkillExecutor 30s），保证 bridge 的 [withTimeoutOrNull] 先超时，
         * 返回语义化超时文案 + 主动执行 `pending.remove(id)` 清理。SkillExecutor 的外层
         * [kotlinx.coroutines.withTimeout] 30s 作为不可达兜底（仅当 bridge 自身 bug 时触发）。
         *
         * **历史教训**：第一次修复（35s）方向错误——35s > 30s 使 bridge 超时更晚而非更早，
         * guardrail-enforcer 在 Phase C 审查中发现并要求回退修复。
         */
        const val DEFAULT_TIMEOUT_MS = 25_000L
    }
}
