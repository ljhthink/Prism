package io.prism.qq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * 开机后**恢复协议端通道**（2026-11-01；US-3128）。
 *
 * ## 与 PRD US-3108 AC-5 的关系（必须先看这条，否则会以为是违规）
 *
 * US-3108 AC-5 说的是「**定时任务**默认不因开机/重启自动恢复」——约束的是"到点主动发消息"，
 * 理由是重启后自动发消息会让用户莫名其妙收到未预期的消息。
 *
 * 本接收器恢复的是**通道本身**（把协议端拉起来），不恢复任何定时计划
 * （`QqSchedulePlanner` 的计划时刻仍由既有逻辑决定）。二者语义不同：
 * 通道是"能收发"的前提，定时是"要不要主动说"的决定。
 *
 * ## 为什么需要它
 *
 * 2026-11-01 复核发现手机上**没有任何开机自启手段**（未装 Termux:Boot），
 * 手机重启后协议端不会自己回来；而 HyperOS 重启/省电重置并不罕见。
 *
 * ## 安全约束
 *
 * - 只在**总开关开启 + 通道为 ONEBOT + 地址为回环**（本机模式）时才动作；
 * - 只做"启动"，不做"部署"（部署需要 Shizuku 在开机早期往往还没就绪）；
 * - 全程 `runCatching`，绝不因开机恢复失败而影响系统启动。
 */
class QqBootRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (context.applicationContext !is io.prism.PrismApplication) {
            Log.i(TAG, "非本应用上下文，跳过开机恢复")
            return
        }
        val app = context.applicationContext as io.prism.PrismApplication
        // 2026-11-01 审查 M2：**不取 goAsync()**。
        //   ① 广播接收器拿 PendingResult 后有 ~10s 预算；而这里要做 3 次 Shizuku
        //      `test -f` + 1 次 `am startservice`，Shizuku 冷绑定时单次可达 5s → 超时 ANR；
        //   ② `appScope` 若已取消，`launch` 既不执行 body 也不抛异常，
        //      `pending.finish()` 将**永远不被调用**（常规泄漏路径，不是极端情况）。
        // 开机恢复本身不需要"广播返回前完成"——交给进程级 scope 异步做即可。
        app.appScope.launch {
            try {
                restoreProtocol(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "开机恢复失败：${e::class.simpleName}")
            }
        }
    }

    private suspend fun restoreProtocol(app: io.prism.PrismApplication) {
        val cfg = app.qqConfigRepository.get()
        if (!cfg.enabled) {
            Log.i(TAG, "总开关关闭，不做开机恢复")
            return
        }
        if (cfg.transport != io.prism.qq.channel.QqTransport.ONEBOT) {
            Log.i(TAG, "通道不是协议端，不做开机恢复")
            return
        }
        if (!io.prism.qq.onebot.OneBotEndpoint.isLoopback(cfg.oneBotBaseUrl)) {
            Log.i(TAG, "非本机模式（协议端在别处），不做开机恢复")
            return
        }
        // 2026-11-01 审查 M-1：BOOT_COMPLETED 时 Shizuku 往往还没就绪，
        // 原来直接 return 等于开机恢复**大概率不生效**。这里改成**有限次轮询等待**
        // （最多 ~2 分钟，每 5 秒一次），期间用户解锁屏幕后 Shizuku 通常会被拉起。
        val readiness = waitForReady(app)
        if (readiness != QqTermuxBridge.Readiness.READY) {
            Log.i(TAG, "开机恢复放弃：${QqTermuxBridge.describeReadiness(readiness)}")
            return
        }
        val ok = QqTermuxBridge.start(app)
        if (ok) {
            app.qqRuntimeStateRepository.clearProtocolRestart()
            Log.i(TAG, "开机已提交协议端拉起")
        } else {
            Log.w(TAG, "开机拉起失败（RUN_COMMAND 未被接受）")
        }
    }

    /** 等待 Shizuku 就绪（最多 [WAIT_ATTEMPTS] 次 × [WAIT_INTERVAL_MS]）。 */
    private suspend fun waitForReady(
        app: io.prism.PrismApplication
    ): QqTermuxBridge.Readiness {
        var readiness = QqTermuxBridge.readiness(app)
        var attempt = 0
        while (readiness != QqTermuxBridge.Readiness.READY && attempt < WAIT_ATTEMPTS) {
            // 只会因"Shizuku 未就绪"而等；其它缺失（未装 Termux / 未授权）等多久都没用
            if (readiness != QqTermuxBridge.Readiness.SHIZUKU_NOT_READY) return readiness
            attempt++
            kotlinx.coroutines.delay(WAIT_INTERVAL_MS)
            readiness = QqTermuxBridge.readiness(app)
        }
        return readiness
    }

    companion object {
        private const val TAG = "QqBootRestartReceiver"

        /** 等待 Shizuku 就绪的轮询间隔。 */
        private const val WAIT_INTERVAL_MS = 5_000L

        /** 最多等待 24 次 ≈ 2 分钟（开机后用户解锁/Shizuku 自启的常见时间窗）。 */
        private const val WAIT_ATTEMPTS = 24
    }
}
