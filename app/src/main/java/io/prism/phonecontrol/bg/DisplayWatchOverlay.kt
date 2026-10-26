package io.prism.phonecontrol.bg

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * ADR-065（US-3114 AC-3）：Watch Mode —— 可选悬浮窗，实时（≤2fps）显示执行面画面。
 *
 * 帧来源统一走 [VirtualDisplayManager.latestFrameWithMeta]（2026-10-25 帧健康整改）：
 * - 隐藏后端：shell 进程自持 ImageReader → Binder 回传 JPEG → 位图（预览降采样到 360 宽）；
 * - legacy 自建后端：本地 ImageReader 帧缓存（带采集时刻）；
 * - legacy overlay（trusted）后端无 ImageReader → 无帧（窗口保持空白背景，不虚构画面）。
 *
 * **2026-10-25 新增**：
 * 1. **帧龄提示**：帧龄超阈值**且环境可疑**（设备不可交互／执行面 display 已 OFF）时，
 *    在窗口底部显示「画面可能已冻结（Ns 前）」。屏幕亮着时的旧帧属静态画面正常表现，
 *    **不提示**（否则正常页面会一直挂着告警，反而误导——ac-verifier D-02）；
 * 2. **真实长宽比**：首帧到达后按真实帧尺寸重设窗口高度（旋转/重建后自适应），不再用常量规格；
 * 3. 帧停滞超过阈值时**只提示**，不在这里重建执行面（重建由截图/工具链路统一负责，
 *    避免 Watch 开关本身触发目标应用重启）。
 *
 * **关闭 Watch 只移除悬浮窗**，不触碰执行面（不中断执行；符合 AC-3「关闭后恢复完全不可见」）。
 * 悬浮窗不可触摸/不可聚焦，不干扰用户正常操作。
 *
 * **线程约束（guardrail r1 Q-05）**：[start] / [stop] 必须由主线程调用；视图仅主线程访问
 * （[stop] 在非主线程调用时会 post 到主线程）。`running` 为跨线程只读标志。
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

    /** 设备是否可交互（D-02：静态画面 vs 真冻结的判据之一）。 */
    private fun isDeviceInteractive(): Boolean = runCatching {
        val ctx = VirtualDisplayManager.appContextForWatch
            ?: return@runCatching true
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    }.getOrDefault(true)

    /** 执行面 display 是否可用（读不到时按"可用"处理，宁可不多提示）。 */
    private fun isDisplayOn(): Boolean = runCatching {
        val ctx = VirtualDisplayManager.appContextForWatch ?: return@runCatching true
        VirtualDisplayManager.isDisplayOn(ctx)
    }.getOrDefault(true)

    /** 悬浮窗宽度（dp）；高度按**真实帧**宽高比换算。 */
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
    private var container: FrameLayout? = null
    private var imageView: ImageView? = null
    private var hintView: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null

    /** 已应用的真实宽高比（避免每帧重设窗口参数）。 */
    private var appliedAspect: Float = 0f

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
        val metrics = app.resources.displayMetrics
        val widthPx = (WIDTH_DP * density).toInt().coerceAtLeast(1)
        // 初始按规格比例；首帧到达后按真实尺寸修正
        val initialHeight = (widthPx * RemoteShellBackend.HEIGHT / RemoteShellBackend.WIDTH)
            .coerceAtLeast(1)
        val image = ImageView(app).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xCC000000.toInt())
        }
        val hint = TextView(app).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 9f
            setBackgroundColor(0x99000000.toInt())
            visibility = TextView.GONE
            setPadding(6, 2, 6, 2)
        }
        val root = FrameLayout(app).apply {
            addView(
                image,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                hint,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            )
        }
        val params = WindowManager.LayoutParams(
            widthPx,
            initialHeight.coerceAtMost((metrics.heightPixels * 0.8f).toInt()),
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
            wm.addView(root, params)
            container = root
            imageView = image
            hintView = hint
            windowParams = params
            appliedAspect = 0f
            running = true
            frameThread = Thread({ frameLoop() }, "prism-watch").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "Watch 已开启（≤2fps，预览宽 $PREVIEW_MAX_WIDTH）")
            "Watch 已开启（≤2fps）"
        } catch (e: Exception) {
            running = false
            container = null
            imageView = null
            hintView = null
            windowParams = null
            runCatching { wm.removeView(root) }
            Log.w(TAG, "Watch 悬浮窗创建失败：${e::class.simpleName}")
            "错误：Watch 悬浮窗创建失败（${e::class.simpleName}）"
        }
    }

    /** 帧循环（后台线程；所有视图操作 post 到主线程）。 */
    private fun frameLoop() {
        var interval = FRAME_INTERVAL_MS
        var emptyRounds = 0
        var lastLogAtMs = 0L
        while (running) {
            // guardrail r2 R-01：legacy 本地自建屏的帧不依赖 Shizuku（不能因
            // Shizuku 未就绪而吞掉本地 ImageReader 帧）；只有隐藏后端才需就绪判定。
            val canCapture = !VirtualDisplayManager.isHiddenMode || ShizukuShell.isReady()
            val frame = if (canCapture) {
                runCatching { VirtualDisplayManager.latestFrameWithMeta(PREVIEW_MAX_WIDTH) }.getOrNull()
            } else {
                null
            }
            if (frame != null) {
                emptyRounds = 0
                interval = FRAME_INTERVAL_MS
                val bitmap = frame.bitmap
                val age = frame.ageMs()
                // ac-verifier D-02：屏幕亮着时的"旧帧"只是静态画面，不该报"已冻结"。
                // 只有设备不可交互或执行面 display 已 OFF 才算可疑。
                val frozen = FrameFreshness.shouldHintStale(
                    ageMs = age,
                    interactive = isDeviceInteractive(),
                    displayOn = isDisplayOn()
                )
                val aspect = if (frame.width > 0) frame.height.toFloat() / frame.width else 0f
                val now = System.currentTimeMillis()
                if (frozen && now - lastLogAtMs >= 60_000L) {
                    lastLogAtMs = now
                    Log.i(TAG, "Watch 帧龄偏大（${age}ms）：画面可能已冻结")
                }
                mainHandler.post {
                    if (!running) return@post
                    imageView?.setImageBitmap(bitmap)
                    // 真实长宽比（±2% 容差内不重设，避免抖动）
                    if (aspect > 0.1f && kotlin.math.abs(aspect - appliedAspect) / aspect > 0.02f) {
                        applyAspect(aspect)
                    }
                    hintView?.let { tv ->
                        if (frozen) {
                            tv.visibility = TextView.VISIBLE
                            tv.text = "画面可能已冻结（${age / 1000}s 前）"
                        } else {
                            tv.visibility = TextView.GONE
                        }
                    }
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
    }

    /** 按真实帧宽高比重设窗口高度（仅主线程）。 */
    private fun applyAspect(aspect: Float) {
        val wm = container?.context?.applicationContext
            ?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val params = windowParams ?: return
        val view = container ?: return
        val screenH = view.context.resources.displayMetrics.heightPixels
        val newHeight = (params.width * aspect).toInt().coerceIn(1, (screenH * 0.8f).toInt())
        if (newHeight == params.height) {
            appliedAspect = aspect
            return
        }
        params.height = newHeight
        runCatching { wm.updateViewLayout(view, params) }
            .onSuccess { appliedAspect = aspect }
            .onFailure { Log.w(TAG, "Watch 尺寸更新失败：${it::class.simpleName}") }
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
        if (!running && container == null) return
        running = false
        frameThread?.interrupt()
        frameThread = null
        val view = container
        container = null
        imageView = null
        hintView = null
        windowParams = null
        appliedAspect = 0f
        if (view != null) {
            val app = view.context.applicationContext
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            runCatching { wm?.removeView(view) }
            // 先摘引用再让 GC 回收（不主动 recycle，Q-03）
            view.removeAllViews()
        }
        Log.i(TAG, "Watch 已关闭")
    }
}
