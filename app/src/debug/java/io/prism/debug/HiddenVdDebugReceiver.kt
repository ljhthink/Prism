package io.prism.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.prism.phonecontrol.bg.ShizukuShell
import io.prism.phonecontrol.bg.VirtualDisplayManager

/**
 * ADR-065（US-3114）真机验收调试接收器 —— **仅 debug 构建**（见 `src/debug/AndroidManifest.xml`）。
 *
 * 目的：在不依赖 LLM 会话的前提下，驱动生产代码路径并采集真机证据：
 * - `create`：`VirtualDisplayManager.ensureDisplay`（默认隐藏后端；失败自动回落 legacy）
 * - `launch`：`launchApp` / `launchAppForUser`（extras: `pkg`、`user`）
 * - `frame`：`latestFrame`（验证 Binder 回传 PNG 可解码）
 * - `takeover`：`takeOverToMainScreen`
 * - `release`：`releaseDisplay`
 * - `status`：只读状态
 *
 * 用法：
 * `adb shell am broadcast -a io.prism.DEBUG_HIDDEN_VD --es action create -p io.prism`
 * 结果输出到 logcat（tag `HiddenVdDebug`）。
 */
class HiddenVdDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 本类仅存在于 debug 源集（src/debug），release 构建不包含该组件。
        val app = context.applicationContext
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_STATUS
        // WindowManager.addView / removeView 必须在有 Looper 的线程（onReceive 即主线程）执行
        if (action == ACTION_WATCH_ON || action == ACTION_WATCH_OFF) {
            val result = if (action == ACTION_WATCH_ON) {
                io.prism.phonecontrol.bg.DisplayWatchOverlay.start(app)
            } else {
                io.prism.phonecontrol.bg.DisplayWatchOverlay.stop()
                "watchRunning=${io.prism.phonecontrol.bg.DisplayWatchOverlay.isRunning}"
            }
            Log.i(TAG, "RESULT[$action] $result")
            return
        }
        val pending = goAsync()
        Thread({
            try {
                val result = when (action) {
                    ACTION_CREATE -> {
                        val id = VirtualDisplayManager.ensureDisplay(app)
                        "ensureDisplay=$id hidden=${VirtualDisplayManager.isHiddenMode} " +
                            "overlay=${VirtualDisplayManager.isOverlayMode} " +
                            "isCreated=${VirtualDisplayManager.isCreated} " +
                            "size=${VirtualDisplayManager.frameSize()} " +
                            "note=${VirtualDisplayManager.lastBackendNote}"
                    }
                    ACTION_LAUNCH -> {
                        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                        if (pkg == null) {
                            logError("launch 缺少 --es pkg")
                            "launch=缺少 --es pkg"
                        } else {
                            val user = intent.getIntExtra(EXTRA_USER, 0)
                            if (user > 0) {
                                VirtualDisplayManager.launchAppForUser(app, pkg, user)
                            } else {
                                VirtualDisplayManager.launchApp(app, pkg)
                            }
                        }
                    }
                    ACTION_FRAME -> {
                        frameDescription(0)
                    }
                    ACTION_FRAME_PREVIEW -> frameDescription(360)
                    // 2026-10-25 帧健康：连续取帧并输出帧龄/尺寸，用于证明"冻结/无画面"
                    ACTION_FRAME_PROBE -> frameProbe()
                    ACTION_DISPLAY_STATE -> {
                        val dm = app.getSystemService(android.content.Context.DISPLAY_SERVICE)
                            as android.hardware.display.DisplayManager
                        val id = VirtualDisplayManager.currentDisplayId
                        val state = runCatching { dm.getDisplay(id)?.state }.getOrNull()
                        val info = runCatching { ShizukuShell.hiddenFrameInfo() }.getOrNull()
                        "displayId=$id state=$state hiddenFrame=$info"
                    }
                    ACTION_TAKEOVER -> VirtualDisplayManager.takeOverToMainScreen(app)
                    ACTION_LEGACY -> {
                        // 强制走 legacy 后端（等价于「隐藏后端不可用」回落路径），验证 AC-5。
                        // guardrail r1 Q-10：仅在本次调用内生效，finally 还原用户偏好。
                        val original = VirtualDisplayManager.hiddenDisplayEnabledProvider
                        VirtualDisplayManager.hiddenDisplayEnabledProvider = { false }
                        try {
                            val id = VirtualDisplayManager.ensureDisplay(app)
                            "legacy ensureDisplay=$id hidden=${VirtualDisplayManager.isHiddenMode} " +
                                "overlay=${VirtualDisplayManager.isOverlayMode} " +
                                "note=${VirtualDisplayManager.lastBackendNote}"
                        } finally {
                            VirtualDisplayManager.hiddenDisplayEnabledProvider = original
                        }
                    }
                    ACTION_RELEASE -> "release=${VirtualDisplayManager.releaseDisplay(app)}"
                    ACTION_STATUS -> "created=${VirtualDisplayManager.isCreated} " +
                        "displayId=${VirtualDisplayManager.currentDisplayId} " +
                        "hidden=${VirtualDisplayManager.isHiddenMode} " +
                        "overlay=${VirtualDisplayManager.isOverlayMode} " +
                        "note=${VirtualDisplayManager.lastBackendNote}"
                    else -> "unknown action=$action"
                }
                Log.i(TAG, "RESULT[$action] $result")
            } catch (t: Throwable) {
                Log.e(TAG, "ERROR[$action] ${t.javaClass.name}: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }, "hidden-vd-debug").start()
    }

    private fun logError(message: String) {
        Log.e(TAG, message)
    }

    /** 取帧并返回尺寸描述（0=原图；360=Watch 预览宽）。 */
    private fun frameDescription(maxWidth: Int): String {
        val frame = VirtualDisplayManager.latestFrameWithMeta(maxWidth)
            ?: return "frame=null (maxWidth=$maxWidth)"
        return try {
            "frame=${frame.width}x${frame.height} ageMs=${frame.ageMs()} " +
                "fresh=${VirtualDisplayManager.isFrameFresh(frame.ageMs())} (maxWidth=$maxWidth)"
        } finally {
            frame.bitmap.recycle()
        }
    }

    /**
     * 连续 5 次取帧（间隔 1s），输出每次的帧龄/尺寸/新鲜度。
     *
     * 判据：熄屏后帧龄应持续增长且 `fresh=false`；亮屏且有画面时帧龄应保持较小。
     */
    private fun frameProbe(): String {
        val sb = StringBuilder("probe:")
        repeat(5) { i ->
            val frame = runCatching { VirtualDisplayManager.latestFrameWithMeta() }.getOrNull()
            if (frame == null) {
                sb.append(" [").append(i).append("]=null")
            } else {
                val age = frame.ageMs()
                sb.append(" [").append(i).append("]=")
                    .append(frame.width).append('x').append(frame.height)
                    .append("/age=").append(age)
                    .append("/fresh=").append(VirtualDisplayManager.isFrameFresh(age))
                frame.bitmap.recycle()
            }
            if (i < 4) runCatching { Thread.sleep(1_000L) }
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "HiddenVdDebug"
        const val EXTRA_ACTION = "action"
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_USER = "user"
        const val ACTION_CREATE = "create"
        const val ACTION_LAUNCH = "launch"
        const val ACTION_FRAME = "frame"
        const val ACTION_FRAME_PREVIEW = "frame_preview"
        const val ACTION_FRAME_PROBE = "frame_probe"
        const val ACTION_DISPLAY_STATE = "display_state"
        const val ACTION_TAKEOVER = "takeover"
        const val ACTION_LEGACY = "legacy"
        const val ACTION_RELEASE = "release"
        const val ACTION_WATCH_ON = "watch_on"
        const val ACTION_WATCH_OFF = "watch_off"
        const val ACTION_STATUS = "status"
    }
}
