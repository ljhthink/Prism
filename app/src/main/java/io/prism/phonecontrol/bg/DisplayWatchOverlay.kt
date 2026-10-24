package io.prism.phonecontrol.bg

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView

/**
 * ADR-065（US-3114 AC-3）：Watch Mode —— 可选悬浮窗，实时（≤2fps）显示执行面画面。
 *
 * 帧来源统一走 [VirtualDisplayManager.latestFrame]：
 * - 隐藏后端：shell 进程自持 ImageReader → Binder 回传 PNG → 位图（预览降采样到 360 宽）；
 * - legacy 自建后端：本地 ImageReader 帧缓存；
 * - legacy overlay（trusted）后端无 ImageReader → 无帧（窗口保持空白背景，不虚构画面）。
 *
 * **关闭 Watch 只移除悬浮窗**，不触碰执行面（不中断执行；符合 AC-3「关闭后恢复完全不可见」）。
 * 悬浮窗不可触摸/不可聚焦，不干扰用户正常操作。
 *
 * **线程约束（guardrail r1 Q-05）**：[start] / [stop] 必须由主线程调用；`imageView` 仅主线程
 * 访问（[stop] 在非主线程调用时会 post 到主线程）。`running` 为跨线程只读标志。
 *
 * **Bitmap 生命周期（guardrail r1 Q-03）**：换帧与关闭都**不主动 recycle**旧帧，交由 GC 回收——
 * 避免 RenderThread 仍引用旧帧时抛 "trying to use a recycled bitmap"（预览帧已降采样，
 * 2fps 下的分配压力可接受）。
 *
 * **退避（guardrail r1 Q-04）**：Shizuku 未就绪/无帧时指数退避（0.5s→5s），避免每帧阻塞
 * 绑定超时与无意义的 Binder/编码开销。
 */
object DisplayWatchOverlay {

    private const val TAG = "DisplayWatchOverlay"

    /** 帧刷新间隔（500ms = 2fps，AC-3 上限）。 */
    private const val FRAME_INTERVAL_MS = 500L

    /** 连续无帧时的最大退避间隔。 */
    private const val MAX_FRAME_INTERVAL_MS = 5_000L

    /** 悬浮窗宽度（dp）；高度按执行面宽高比换算。 */
    private const val WIDTH_DP = 132

    /** Watch 预览帧最大宽度（降低 shell 端编码 / Binder / App 解码与 GC 压力）。 */
    private const val PREVIEW_MAX_WIDTH = 360

    /** 主线程 Handler（懒加载；所有窗口状态变更都在主线程）。 */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var frameThread: Thread? = null

    /** 仅主线程访问。 */
    private var imageView: ImageView? = null

    /** 是否正在显示。 */
    val isRunning: Boolean
        get() = running

    /**
     * 开启 Watch 悬浮窗（**必须主线程**）。
     *
     * @return "已开启"/"已在运行" 或可读错误文案（权限缺失/线程错误/窗口创建失败）
     */
    fun start(context: Context): String {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return "错误：Watch 必须在主线程开启"
        }
        if (running) return "Watch 已在运行"
        if (!android.provider.Settings.canDrawOverlays(context)) {
            return "错误：Watch 需要「显示在其他应用上层」（悬浮窗）权限"
        }
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return "错误：WindowManager 不可用"
        val density = app.resources.displayMetrics.density
        val widthPx = (WIDTH_DP * density).toInt().coerceAtLeast(1)
        val heightPx = (widthPx * RemoteShellBackend.HEIGHT / RemoteShellBackend.WIDTH).coerceAtLeast(1)
        val view = ImageView(app).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xCC000000.toInt())
        }
        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (8 * density).toInt()
            // 窗口标题仅用于诊断（dumpsys window 可定位 Watch 悬浮窗）
            title = "PrismWatch"
        }
        return try {
            wm.addView(view, params)
            imageView = view
            running = true
            frameThread = Thread({
                var interval = FRAME_INTERVAL_MS
                var emptyRounds = 0
                while (running) {
                    // guardrail r2 R-01：legacy 本地自建屏的帧不依赖 Shizuku（不能因
                    // Shizuku 未就绪而吞掉本地 ImageReader 帧）；只有隐藏后端才需就绪判定。
                    val canCapture = !VirtualDisplayManager.isHiddenMode || ShizukuShell.isReady()
                    val frame = if (canCapture) {
                        runCatching { VirtualDisplayManager.latestFrame(PREVIEW_MAX_WIDTH) }.getOrNull()
                    } else {
                        null
                    }
                    if (frame != null) {
                        emptyRounds = 0
                        interval = FRAME_INTERVAL_MS
                        mainHandler.post {
                            if (running) view.setImageBitmap(frame)
                            // 不 recycle：旧帧可能仍被 RenderThread 引用（Q-03）
                        }
                    } else {
                        emptyRounds = (emptyRounds + 1).coerceAtMost(4)
                        interval = (FRAME_INTERVAL_MS * (1L shl emptyRounds)).coerceAtMost(MAX_FRAME_INTERVAL_MS)
                    }
                    try {
                        Thread.sleep(interval)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "prism-watch").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Watch 已开启（≤2fps，预览宽 $PREVIEW_MAX_WIDTH）")
            "Watch 已开启（≤2fps）"
        } catch (e: Exception) {
            running = false
            imageView = null
            runCatching { wm.removeView(view) }
            Log.w(TAG, "Watch 悬浮窗创建失败：${e::class.simpleName}")
            "错误：Watch 悬浮窗创建失败（${e::class.simpleName}）"
        }
    }

    /**
     * 关闭 Watch（只移除悬浮窗，不触碰执行面）。
     * 非主线程调用时自动 post 到主线程执行（如 [VirtualDisplayManager.releaseDisplay] 的后台路径）。
     */
    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopOnMain() }
            return
        }
        stopOnMain()
    }

    /** 仅主线程执行。 */
    private fun stopOnMain() {
        if (!running && imageView == null) return
        running = false
        frameThread?.interrupt()
        frameThread = null
        val view = imageView
        imageView = null
        if (view != null) {
            val app = view.context.applicationContext
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            runCatching { wm?.removeView(view) }
            // 先摘引用再让 GC 回收（不主动 recycle，Q-03）
            view.setImageDrawable(null)
        }
        Log.i(TAG, "Watch 已关闭")
    }
}
