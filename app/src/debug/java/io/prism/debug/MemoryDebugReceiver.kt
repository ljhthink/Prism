package io.prism.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.prism.PrismApplication
import io.prism.data.MemoryRecord

/**
 * L2 跨会话记忆的真机验收工具（**仅 debug 构建**，见 `src/debug/AndroidManifest.xml`）。
 *
 * 存在理由（guardrail R4-Q6）：用户反馈「L2 跨会话没有记录，无法查看 UI」。
 * 真实产生的条件是三件事同时成立（会话结束 + 重要轮次 + 嵌入模型就绪），
 * 在验收环境里很难稳定复现；但 L2 的**列表 UI** 必须先能看、能点、能删。
 *
 * 本接收器只做一件事：往 `memoryRepository` 塞一条真实结构的数据，让 UI 有东西可看。
 * **不修改任何产品逻辑**，不新增 release 代码路径，不改数据结构——用既有 `save()`。
 *
 * 用法：
 * ```
 * adb shell am broadcast -a io.prism.DEBUG_MEMORY --es action seed -p io.prism
 * adb shell am broadcast -a io.prism.DEBUG_MEMORY --es action clear -p io.prism
 * ```
 * 结果输出到 logcat（tag `MemoryDebug`）。
 */
class MemoryDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PrismApplication
        if (app == null) {
            Log.e(TAG, "RESULT: applicationContext is not PrismApplication")
            return
        }
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_SEED
        val pending = goAsync()
        Thread({
            try {
                val repo = app.memoryRepository
                when (action) {
                    ACTION_SEED -> {
                        // 嵌入向量用零向量占位：本条只为"看 UI"，不参与检索排序。
                        // 维度取 384（与既有 embedder 输出维度一致，避免下游校验形状不匹配）。
                        val now = System.currentTimeMillis()
                        val samples = listOf(
                            "产品复盘的结论偏好：先给结论再给证据" to now - 3L * 86_400_000L,
                            "写作时不用感叹号，尽量短句" to now - 5L * 86_400_000L,
                            "周四晚上通常不在线，别在那时找我" to now - 7L * 86_400_000L
                        )
                        samples.forEachIndexed { index, (content, ts) ->
                            repo.save(
                                MemoryRecord(
                                    sessionId = "acceptance-seed",
                                    content = content,
                                    embedding = FloatArray(384),
                                    timestamp = ts,
                                    turnCount = index + 1
                                )
                            )
                        }
                        Log.i(TAG, "RESULT[seed] saved=${samples.size} total=${repo.memoryRecords.value.size}")
                    }

                    ACTION_CLEAR -> {
                        val before = repo.memoryRecords.value.size
                        repo.memoryRecords.value.forEach { repo.deleteById(it.id) }
                        Log.i(TAG, "RESULT[clear] before=$before after=${repo.memoryRecords.value.size}")
                    }

                    ACTION_STATUS -> {
                        val list = repo.memoryRecords.value
                        Log.i(TAG, "RESULT[status] count=${list.size} contents=${list.map { it.content.take(16) }}")
                    }

                    else -> Log.e(TAG, "RESULT: unknown action=$action")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "RESULT: failed ${t::class.simpleName}: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }, "memory-debug").start()
    }

    private companion object {
        const val TAG = "MemoryDebug"
        const val EXTRA_ACTION = "action"
        const val ACTION_SEED = "seed"
        const val ACTION_CLEAR = "clear"
        const val ACTION_STATUS = "status"
    }
}
