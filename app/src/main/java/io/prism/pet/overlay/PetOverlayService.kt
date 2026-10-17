package io.prism.pet.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import io.prism.MainActivity
import io.prism.PrismApplication
import io.prism.pet.PetActionBus
import io.prism.pet.PetManager
import io.prism.pet.model.PetConfig
import io.prism.pet.model.PetIdlePolicy
import io.prism.pet.model.PetWindowGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import kotlin.random.Random

/**
 * 批次24（ADR-051 D5）：桌宠悬浮窗前台服务。
 *
 * **形态**：`WindowManager` + `TYPE_APPLICATION_OVERLAY` 的自绘窗口，内含
 * 一个承载 Live2D/图像渲染的 `WebView` 与一个对话气泡 `TextView`。
 *
 * **与 ADR-041 的边界**（重要，写在这里以免后人误读）：本服务**不是**"隐形后台常驻"。
 * 它只由用户在设置页**显式**打开；窗口本身在桌面上**可见**；通知即"如何收起"的回执；
 * 关闭开关立即 `stopSelf()`；**不申请开机自启**。ADR-041 否决的是"AI 任务在后台长期驻留、
 * 用户看不到也关不掉"的形态，与本服务不同类。
 *
 * **合规**：`foregroundServiceType = specialUse`（Android 14 `FOREGROUND_SERVICE_TYPE_NONE`
 * 会抛 `InvalidForegroundServiceTypeException`），Manifest 已声明 `FOREGROUND_SERVICE_SPECIAL_USE`
 * 权限与用途 `<property>`。`START_NOT_STICKY`：被系统回收后不自动重启。
 *
 * **失败必可见**（PRD US-2413 AC-1）：任何一步失败都会经 [PetManager.onServiceFailed]
 * 回报可读原因，设置页据此展示；**绝不静默失败成"开关是开的但桌面上什么都没有"**。
 */
class PetOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var manager: PetManager

    private var root: FrameLayout? = null
    private var webView: WebView? = null
    private var bubble: TextView? = null
    private var menu: android.widget.LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var busJob: Job? = null
    private var idleJob: Runnable? = null
    private var bubbleHide: Runnable? = null
    private var menuHide: Runnable? = null

    /** 是否已收到渲染器 `ready` 回执（未 ready 前的失败才算失败，之后的异常降级不打断）。 */
    private var rendererReady = false

    /** 就绪看门狗（guardrail H-1）。 */
    private var readyWatchdog: Runnable? = null

    /**
     * 渲染器就绪门控（BUG-20260913-001）：页面脚本执行完（收到 `boot`）之前，
     * 一切对 `PetRenderer.*` 的下发都必须压住——否则会以
     * `ReferenceError: PetRenderer is not defined` 静默失败且永不重试。
     */
    private val rendererGate = PetRendererGate()

    /** 手势处理器（guardrail L-1：配置变化时需同步其 `area`）。 */
    private var touchHandler: TouchHandler? = null

    /** 当前生效配置快照（用于待机定时与几何计算）。 */
    private var config: PetConfig = PetConfig.DEFAULT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        manager = (application as PrismApplication).petManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                if (!startForegroundCompat()) return START_NOT_STICKY
                // 走 showOverlay 而不是直接 reloadAppearance：服务可能**尚未**建立窗口
                // （例如配置变更时 `startService` 把已停止的服务重新拉起，或窗口此前创建失败）。
                // 直接 reload 会走到 `webView == null` 的空操作分支，留下一个
                // "前台服务在跑 + 通知在 + 桌面上什么都没有"的僵死态。
                showOverlay()
                return START_NOT_STICKY
            }
            else -> {
                if (!startForegroundCompat()) return START_NOT_STICKY
                showOverlay()
                return START_NOT_STICKY
            }
        }
    }

    /**
     * Android 14 的 `startForeground` 必须声明类型，且类型必须与 Manifest 一致。
     * 放在最前面：`startForegroundService` 要求 5 秒内调用，任何前置检查失败都不能延后它。
     *
     * **必须捕获异常**（guardrail L-3）：Android 12+ 在后台启动前台服务受限，
     * 存在"`startService` 建起服务、`startForeground` 却被拒"的竞态窗口，
     * 此时会抛 `ForegroundServiceStartNotAllowedException`。
     * 不捕获的直接后果是**用户点一下开关就闪退**；捕获后走 [failAndStop] 降级，
     * 变成"开关回弹 + 可读原因"。
     *
     * @return 是否成功进入前台（false 时调用方必须立即停止后续步骤）
     */
    private fun startForegroundCompat(): Boolean = try {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "pet foreground start rejected (${e::class.simpleName})")
        manager.onServiceFailed("系统拒绝了桌宠前台服务（后台启动受限），请在前台再次打开")
        stopSelf()
        false
    }

    // ==================== 窗口 ====================

    private fun showOverlay() {
        if (root != null) {
            // 窗口已在，收到的是"配置/形象变了"的刷新请求：必须走 reloadConfigAndAppearance
            // 而不是只 reloadAppearance —— 后者不会重读 config，导致"待机开关/减少动效/
            // 低功耗/不透明度"这些设置改了却不生效（窗口一直用启动那一刻的快照）。
            reloadConfigAndAppearance()
            return
        }
        // 权限在启动前由设置页检查，但用户可能在服务运行期间去系统设置里撤销——
        // 所以这里必须再查一次，否则 addView 会抛 BadTokenException（崩溃）。
        if (!Settings.canDrawOverlays(this)) {
            failAndStop("未授予悬浮窗权限")
            return
        }
        runCatching {
            buildOverlay()
        }.onFailure { e ->
            failAndStop("悬浮窗创建失败（${e::class.simpleName}）")
            return
        }
        manager.onServiceStarted()
        startBusCollection()
        reloadConfigAndAppearance()
        // guardrail TKN-PET-BUG-001-GUARDRAIL 的 D-1：**boot 也要有超时兜底**。
        // 若 `pet-renderer.js` 自身抛错（或资产被篡改），`boot` 永不到达 ⇒ 门控永久压住
        // 下发、`armReadyWatchdog` 又永远不会布防（它只在门控放行后才调）⇒
        // 又回到"开关开着、桌面空着、且没有任何原因"的静默态。
        armBootWatchdog()
    }

    /**
     * 页面脚本就绪看门狗（guardrail D-1 修复）。
     *
     * 与 [armReadyWatchdog] 是**两段式**：本函数管"脚本有没有跑起来"（秒级），
     * 后者管"模型有没有加载完"（十秒级）。二者串联覆盖整条链，
     * 任一环断掉都会有可读原因，不会静默。
     */
    private fun armBootWatchdog() {
        readyWatchdog?.let { main.removeCallbacks(it) }
        val job = Runnable {
            if (rendererGate.isBooted) return@Runnable
            Log.w(TAG, "renderer never booted within ${BOOT_TIMEOUT_MS}ms")
            manager.onServiceFailed("渲染器未能启动（页面脚本未执行），请重试或重装应用")
        }
        readyWatchdog = job
        main.postDelayed(job, BOOT_TIMEOUT_MS)
    }

    private fun failAndStop(reason: String) {
        Log.w(TAG, "overlay aborted: $reason")
        manager.onServiceFailed(reason)
        stopSelf()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildOverlay() {
        val area = availableArea()
        val frame = PetWindowGeometry.resolve(
            areaWidth = area.width(),
            areaHeight = area.height(),
            contentWidth = (area.width() * BASE_WIDTH_RATIO).toInt(),
            contentHeight = (area.width() * BASE_WIDTH_RATIO * BASE_ASPECT).toInt(),
            posX = config.posX,
            posY = config.posY,
            minWidth = dp(MIN_TOUCH_SIZE_DP),
            minHeight = dp(MIN_TOUCH_SIZE_DP)
        )

        // 根布局**按需**拦截触摸（guardrail R3-B 的 N-1）：
        // 早期实现无条件 `return true`，于是子 View **永远收不到触摸** ——
        // 长按能弹出菜单，但"打开聊天 / 收起桌宠"两个按钮点了没反应
        // （触点被下面的 handleTap 吞掉）。而这类缺陷**所有单测都抓不到**：
        // 没有一个用例覆盖"菜单按钮是否可达"。
        // 现在：菜单**可见时不拦截**（让按钮拿到触摸），隐藏时拦截（根布局做拖动/点击判定）。
        val container = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
                menu?.visibility != View.VISIBLE
        }

        val web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isClickable = false
            isFocusable = false
            // 菜单可见时根布局不再拦截触摸，此时落在 WebView 上的触点必须被**吞掉**
            // 而不是穿透成页面内点击（页面不处理任何点击，但吞掉可避免与菜单手势混淆）。
            setOnTouchListener { _, _ -> true }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.mediaPlaybackRequiresUserGesture = false
            // 安全：只经虚拟源取资源，禁止 file/content 访问与跨源读取
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            // 页面完全由我们自己的资产构成，不需要弹窗/多窗口/地理位置/数据库——
            // 一律关掉，缩小攻击面（用户资产只作为**数据**被 XHR 读取，永不作为网页加载）。
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            settings.databaseEnabled = false
            settings.setNeedInitialFocus(false)
            // **刻意不设 `blockNetworkLoads = true`**（guardrail R3-B 的 N-3）。
            //
            // 早期把它作为"纵深防御"加上，但审查指出：本页面是经 `shouldInterceptRequest`
            // 提供的 **https 虚拟源**，而 `blockNetworkLoads` 与"顶层 https 导航是否会在
            // 进入拦截回调之前就被网络栈拒掉"的相互作用**无法在任何本地环境中验证**。
            // 风险不对称：猜错的代价是**整个悬浮窗白屏（功能完全不可用）**，
            // 而它挡住的场景（页面被替换后外发数据）已由其它层覆盖 ——
            // `allowFileAccess/allowContentAccess/allowUniversalAccessFromFileURLs` 全关、
            // `shouldOverrideUrlLoading` 拦截一切导航、且页面是我们自己的静态资产。
            // 故撤回该设置；真机验证过"虚拟源下的 fetch 与导航确实不受影响"后可以再加回。
            webViewClient = PetWebViewClient()
            addJavascriptInterface(HostBridge(), "PetHost")
        }

        val bubbleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xCC1F1F26.toInt())
            }
            maxLines = 4
            visibility = View.GONE
        }

        container.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        container.addView(
            bubbleView,
            FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.TOP
                topMargin = dp(2)
            }
        )

        // 长按菜单（PRD US-2408 AC-3/AC-4）：**在同一个悬浮窗内**用普通 View 拼，
        // 而不是用 AlertDialog —— Service 没有 Activity 的 window token，
        // 直接 showDialog 会抛 WindowManager$BadTokenException。
        val menuView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xE61F1F26.toInt())
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        menuView.addView(menuButton("打开聊天") { openChat() })
        menuView.addView(menuButton("收起桌宠") { stopSelf() })
        container.addView(
            menuView,
            FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(4)
            }
        )

        val lp = WindowManager.LayoutParams(
            frame.width,
            frame.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // 让 x/y 以"整屏"为坐标系，配合 availableArea() 的 insets 钳制，
                // 保证窗口既不被系统栏压住、也不会跑到屏幕外
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = area.left + frame.x
            y = area.top + frame.y
            alpha = config.opacity
        }

        container.setOnTouchListener(TouchHandler().also { touchHandler = it; it.area = area })
        windowManager.addView(container, lp)

        root = container
        webView = web
        bubble = bubbleView
        menu = menuView
        params = lp

        web.loadUrl(ASSET_URL)
    }

    /** 屏幕可用区域（扣除系统栏与刘海）。 */
    private fun availableArea(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return Rect(
                insets.left,
                insets.top,
                metrics.bounds.width() - insets.right,
                metrics.bounds.height() - insets.bottom
            )
        }
        @Suppress("DEPRECATION")
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /** 拖动 / 点击 / 长按手势。位移超过系统 slop 判为拖动，否则按按压时长区分点击与长按。 */
    private inner class TouchHandler : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@PetOverlayService).scaledTouchSlop
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private var downRawX = 0f
        private var downRawY = 0f
        private var originX = 0
        private var originY = 0
        private var downAt = 0L
        private var dragging = false

        /**
         * 当前可用区域（**可变**）。
         *
         * guardrail L-1：早期实现把它作为构造参数一次性捕获，旋转/分屏后 `area` 变陈旧，
         * 而 `lp.width` 已被新几何覆写 → `coerceIn(area.left, area.right - lp.width)`
         * 在 `min > max` 时抛 `IllegalArgumentException`（拖动即崩溃）。
         * 改为可变字段并在 [applyWindowConfig] 中同步。
         */
        var area: Rect = Rect()

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    originX = lp.x
                    originY = lp.y
                    downAt = android.os.SystemClock.uptimeMillis()
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        hideMenu()
                        lp.x = clampOffset(originX + dx.toInt(), area.left, area.right - lp.width)
                        lp.y = clampOffset(originY + dy.toInt(), area.top, area.bottom - lp.height)
                        runCatching { windowManager.updateViewLayout(v, lp) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 任何一次交互都重置待机计时（PRD US-2406 AC-2：不会刚摸完就自己动起来）
                    rescheduleIdleAfterInteraction()
                    if (dragging) {
                        persistPosition(lp, area)
                    } else if (android.os.SystemClock.uptimeMillis() - downAt >= longPressTimeout) {
                        toggleMenu()
                    } else {
                        handleTap(event.x / v.width.coerceAtLeast(1), event.y / v.height.coerceAtLeast(1))
                    }
                    return true
                }
            }
            return false
        }
    }

    /**
     * 偏移量钳制（guardrail L-1 修复）。
     *
     * **不能用 `coerceIn(min, max)`**：当窗口比可用区域还大时 `max < min`，
     * `coerceIn` 会直接抛 `IllegalArgumentException`。这里退化为"贴住 min 边"，
     * 与 [PetWindowGeometry.resolve] 的"窗口尺寸先被钳到屏幕尺寸"形成双保险
     * （尺寸与位置分别独立安全，不依赖另一方先正确）。
     */
    private fun clampOffset(value: Int, min: Int, max: Int): Int =
        if (max <= min) min else value.coerceIn(min, max)

    /**
     * 配置变化（旋转 / 分屏 / 折叠）→ 重算窗口几何（guardrail L-1）。
     *
     * **Service 确实会收到 `onConfigurationChanged`**（系统对运行中的 Service 广播配置变化），
     * 无需在 Manifest 声明 `android:configChanges`。不覆写的后果是：窗口停在旧坐标系、
     * 手势区域陈旧，用户在旋转后既看不到正确位置也拖不动。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWindowConfig()
    }

    /**
     * 交互后重置待机计时（PRD US-2406 AC-2）。
     *
     * **为什么是"重新排程"而不是"清零一个计数器"**：[PetIdlePolicy] 的语义就是
     * "每次交互后重新采样一个 [12s, 30s] 的间隔"——重新排程即等价于重置，
     * 且不需要额外维护"上次交互时刻"这个字段（少一个可失同步的状态）。
     */
    private fun rescheduleIdleAfterInteraction() {
        scope.launch {
            config = runCatching { (application as PrismApplication).petConfigRepository.get() }
                .getOrDefault(config)
                .clamped()
            scheduleIdle()
        }
    }

    // ==================== 长按菜单 ====================

    private fun menuButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener {
                hideMenu()
                onClick()
            }
        }

    private fun toggleMenu() {
        val view = menu ?: return
        if (view.visibility == View.VISIBLE) {
            hideMenu()
        } else {
            view.visibility = View.VISIBLE
            menuHide?.let { main.removeCallbacks(it) }
            val hide = Runnable { view.visibility = View.GONE }
            menuHide = hide
            main.postDelayed(hide, MENU_HOLD_MS)
        }
    }

    private fun hideMenu() {
        menuHide?.let { main.removeCallbacks(it) }
        menuHide = null
        menu?.visibility = View.GONE
    }

    /**
     * 打开聊天页（PRD US-2408 AC-4）。
     *
     * **不清空会话**：用 `FLAG_ACTIVITY_SINGLE_TOP` 复用既有 MainActivity 实例，
     * 只把它带到前台；MainActivity 是单 Activity + NavHost 架构，重新创建实例会丢失当前页面状态。
     * 需 `FLAG_ACTIVITY_NEW_TASK` 是因为从 Service 启动 Activity。
     */
    private fun openChat() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }.onFailure { Log.w(TAG, "open chat from pet failed: ${it::class.simpleName}") }
    }

    private fun persistPosition(lp: WindowManager.LayoutParams, area: Rect) {
        val nx = PetWindowGeometry.normalize(area.width(), lp.width, lp.x - area.left)
        val ny = PetWindowGeometry.normalize(area.height(), lp.height, lp.y - area.top)
        scope.launch { manager.updatePosition(nx, ny) }
    }

    private fun handleTap(nx: Float, ny: Float) {
        val web = webView ?: return
        eval(web, "PetRenderer.tapAt(${nx.toDouble()},${ny.toDouble()})")
    }

    // ==================== 渲染 ====================

    private fun reloadConfigAndAppearance() {
        scope.launch {
            config = runCatching { (application as PrismApplication).petConfigRepository.get() }
                .getOrDefault(PetConfig.DEFAULT)
                .clamped()
            applyWindowConfig()
            reloadAppearance()
            scheduleIdle()
        }
    }

    private fun applyWindowConfig() {
        val lp = params ?: return
        val container = root ?: return
        val area = availableArea()
        val frame = PetWindowGeometry.resolve(
            areaWidth = area.width(),
            areaHeight = area.height(),
            contentWidth = (area.width() * BASE_WIDTH_RATIO).toInt(),
            contentHeight = (area.width() * BASE_WIDTH_RATIO * BASE_ASPECT).toInt(),
            posX = config.posX,
            posY = config.posY,
            minWidth = dp(MIN_TOUCH_SIZE_DP),
            minHeight = dp(MIN_TOUCH_SIZE_DP)
        )
        lp.width = frame.width
        lp.height = frame.height
        lp.x = area.left + frame.x
        lp.y = area.top + frame.y
        lp.alpha = config.opacity
        touchHandler?.area = area
        runCatching { windowManager.updateViewLayout(container, lp) }
        syncOptionsToRenderer()
    }

    private fun optionsJson(): String = JSONObject()
        .put("reduceMotion", config.reduceMotion)
        .put("lowPower", config.lowPowerMode)
        .put("scale", config.scale.toDouble())
        .toString()

    private fun reloadAppearance() {
        // BUG-20260913-001：渲染器未就绪（页面脚本还没跑完）时**不能**下发——
        // 早期实现在 loadUrl 之后立刻下发，JS 抛 ReferenceError 且无重试，
        // 描述符从未送达 ⇒ 模型从未加载 ⇒ 看门狗超时误报"形象不受支持"。
        if (!rendererGate.canSendNow()) {
            rendererGate.markSyncPending()
            Log.i(TAG, "renderer not booted yet; descriptor sync deferred")
            return
        }
        scope.launch {
            val appearance = manager.effectiveAppearance()
            val descriptor = manager.buildDescriptor(appearance, config)
            rendererReady = false
            eval(webView, "PetRenderer.setOptions(${JSONObject.quote(optionsJson())})")
            eval(webView, "PetRenderer.load(${JSONObject.quote(descriptor)})")
            armReadyWatchdog(appearance?.name)
        }
    }

    /**
     * 把"选项"同步给渲染器（同样受就绪门控约束）。
     *
     * 选项是**幂等全量快照**，故压住后无需排队：boot 时的一次全量补发即覆盖。
     */
    private fun syncOptionsToRenderer() {
        if (!rendererGate.canSendNow()) {
            rendererGate.markSyncPending()
            return
        }
        eval(webView, "PetRenderer.setOptions(${JSONObject.quote(optionsJson())})")
    }

    /**
     * 渲染就绪看门狗（guardrail H-1 修复；ADR-051 风险表"三保险"之②）。
     *
     * **为什么必须有**：WebView 在悬浮窗里"加载了但什么都没渲染出来"是本功能最主要
     * 的失败形态（透明背景失败、moc3 版本不兼容、纹理解码失败都表现为它）。
     * 没有看门狗时，这种失败**完全没有信号**：`onServiceStarted()` 已经执行，
     * 设置页显示"显示中"、通知也在，而桌面上什么都没有 —— 用户只能看到"开关是开的但没用"。
     *
     * 触发后**不停服务**（用户可能只是换了个不兼容的形象，停掉整个桌宠是过度反应），
     * 而是把可读原因写进 [PetManager.lastError]，让设置页能说出来。
     */
    private fun armReadyWatchdog(appearanceName: String?) {
        readyWatchdog?.let { main.removeCallbacks(it) }
        val job = Runnable {
            if (rendererReady) return@Runnable
            Log.w(TAG, "pet renderer not ready within ${READY_TIMEOUT_MS}ms; surfacing failure")
            manager.onServiceFailed("形象加载超时（${appearanceName ?: "未选形象"}），可能不受支持")
        }
        readyWatchdog = job
        main.postDelayed(job, READY_TIMEOUT_MS)
    }

    /**
     * 执行 JS 并把**失败留痕**（BUG-20260913-001 的可观测性修复）。
     *
     * 早期实现是 `evaluateJavascript(script, null)`：回调传 `null` 意味着
     * **JS 侧异常完全不可见** —— `runCatching` 只能捕到 Kotlin 侧异常，捕不到
     * `Uncaught ReferenceError`。于是"下发了 3 次、3 次都抛错"这件事在 Kotlin 日志里
     * 一片空白，只剩 8 秒后一句"加载超时"，把排查引向了完全错误的方向。
     *
     * 现在：回调拿到非 `null` 结果即视为 JS 抛错（正常返回值均为对象或 null），
     * 记 WARN 并带上脚本前 40 字符（**不含用户内容**：脚本内容是动作别名/路径，
     * 且这里只截取调用形态前缀，便于区分是哪个调用点）。
     */
    private fun eval(web: WebView?, script: String) {
        if (web == null) return
        val label = script.substringBefore('(').take(40)
        runCatching {
            web.evaluateJavascript(script) { result ->
                // evaluateJavascript 的 result 是 JS 返回值的 JSON 形式；
                // 抛异常时 Chromium 传回 "null"。我们的所有调用都是有返回值的
                // （返回 JSON 字符串），故 "null" 即代表那次调用失败了。
                if (result == null || result == "null") {
                    Log.w(TAG, "renderer call returned null (likely JS error): $label")
                }
            }
        }.onFailure { Log.w(TAG, "eval dispatch failed (${it::class.simpleName}): $label") }
    }

    // ==================== 事件 ====================

    private fun startBusCollection() {
        busJob?.cancel()
        busJob = scope.launch {
            // 用 collect 而非 collectLatest：事件处理块里没有挂起点，
            // collectLatest 的"取消上一次"语义在此只会引入误读（且对 Speaking 这类
            // 高频事件，取消语义会让人误以为存在有意的合并行为——实际没有）。
            PetActionBus.events.collect { event ->
                when (event) {
                    is PetActionBus.Event.Speaking -> {
                        // 口型**不**受「减少动效」影响：它是对"正在说话"的直接响应，
                        // 关掉会让形象在说话时嘴一动不动，比动效本身更出戏。
                        // 减少动效只停"没人理时的自发动作"（待机 + 呼吸），见 PRD US-2406 AC-4。
                        eval(
                            webView,
                            "PetRenderer.setMouth(${event.active}, ${event.durationMs})"
                        )
                    }
                    is PetActionBus.Event.Bubble -> showBubble(event.text)
                    is PetActionBus.Event.AppearanceChanged -> reloadAppearance()
                    is PetActionBus.Event.PlayAction ->
                        eval(webView, "PetRenderer.play(${JSONObject.quote(event.alias)})")
                }
            }
        }
    }

    private fun showBubble(text: String) {
        if (!config.bubbleEnabled) return
        val view = bubble ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val shown = if (trimmed.length > PetManager.MAX_BUBBLE_CHARS) {
            trimmed.take(PetManager.MAX_BUBBLE_CHARS) + "…"
        } else {
            trimmed
        }
        view.text = shown
        view.visibility = View.VISIBLE
        bubbleHide?.let { main.removeCallbacks(it) }
        val hide = Runnable { view.visibility = View.GONE }
        bubbleHide = hide
        main.postDelayed(hide, BUBBLE_HOLD_MS)
    }

    /** 待机动作：策略是纯函数（[PetIdlePolicy]），这里只负责定时投递。 */
    private fun scheduleIdle() {
        idleJob?.let { main.removeCallbacks(it) }
        val delay = PetIdlePolicy.nextDelayMs(config, Random.Default) ?: return
        val job = Runnable {
            val aliases = manager.let { it.appearances.value }
                .firstOrNull { it.id == config.appearanceId }
                ?.let { appearance ->
                    io.prism.pet.model.PetActionCatalog.idleAliases(appearance)
                }
                .orEmpty()
            if (aliases.isNotEmpty()) {
                eval(webView, "PetRenderer.play(${JSONObject.quote(aliases.random())})")
            }
            scheduleIdle()
        }
        idleJob = job
        main.postDelayed(job, delay)
    }

    // ==================== 渲染器桥 ====================

    /**
     * JS → Kotlin 回调入口。
     *
     * **安全**：只暴露一个方法，且**只接受固定结构的事件对象**（不接受任何"方法名"参数）——
     * 页面内容全部由我们自己的资产构成（`assets/pet/`），用户资产只作为数据被加载，
     * 不参与脚本执行。即便如此仍按最小暴露面设计：桥对象不可枚举、无反射入口。
     */
    private inner class HostBridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            main.post { onRendererEvent(json) }
        }
    }

    private fun onRendererEvent(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "boot" -> {
                // 页面脚本执行完毕（`window.PetRenderer` 此刻才真正存在）。
                // BUG-20260913-001：此前所有下发都被 [rendererGate] 压住了，必须在此补发一次全量同步。
                Log.d(TAG, "pet renderer booted")
                if (rendererGate.onRendererBooted()) {
                    Log.i(TAG, "renderer booted; flushing deferred descriptor sync")
                    reloadConfigAndAppearance()
                }
            }
            "ready" -> {
                rendererReady = true
                readyWatchdog?.let { main.removeCallbacks(it) }
                readyWatchdog = null
                // **状态必须复原**（guardrail R3-A 的 N-1）：看门狗是**猜测**，不是判决。
                // 慢设备上首次"WebView 冷启动 + moc3 解码 + 9MB 纹理上传"超过 8s 时，
                // 看门狗会先把状态置成 FAILED；此时模型其实**渲染成功了**。
                // 不复原的话设置页会**永久**显示"启动失败 · 形象加载超时"，
                // 而且 `onServiceStopped` 在 FAILED 下不复位 —— 用户只能重建服务才能摆脱。
                // 真值以渲染器回执为准：回执到了就是成功。
                manager.onServiceStarted()
                // 诊断载荷**必须落日志**（guardrail H-2）：这是真机上判断
                // "moc3 版本是否被 Core 接受""纹理尺寸是否正常""命中区是否解析出来"的
                // **唯一设备侧证据**。不记它，真机失败就只能看到"没报错但没画面"。
                // 仅记数值与条数——不含资产内容、不含路径。
                Log.i(
                    TAG,
                    "pet renderer ready kind=${obj.optString("kind")} " +
                        "core=${obj.optString("coreVersion")} " +
                        "coreMocSupport=${obj.optString("coreMocSupport")} " +
                        "w=${obj.optInt("width")} h=${obj.optInt("height")} " +
                        "hitAreas=${obj.optJSONArray("hitAreas")?.length() ?: 0}"
                )
            }
            "diag" -> {
                // 模型自身的 moc 版本（`csmGetMocVersion(moc, size)` 的真实返回值）。
                // **与 `coreMocSupport` 是两件事**（guardrail R3-A 的 N-2）：
                // 前者答"这个模型是第几版"，后者答"这套 Core 最高能吃到第几版"。
                // 二者一比较就能直接判定兼容性：`mocVersion > coreMocSupport` ⇒ 必然加载失败。
                // 早期实现只记了后者却把它写成 `moc=`，等于用"Core 支持到 5"冒充
                // "昔涟这个模型是 5" —— 真机上最关键的那一问仍然答不了。
                Log.i(
                    TAG,
                    "pet model moc version=${obj.optString("mocVersion")}"
                )
            }
            "action" -> {
                val alias = obj.optString("alias")
                if (obj.optBoolean("fallback")) {
                    // 回退是**正常路径**（昔涟模型 18/23 条动作本就只有表情），用 info 而非 warn。
                    // 必须带上 alias 与 kind：只记 reason 时真机日志只能看出"有动作回退了"，
                    // 无法回答"是哪个别名、回退成了什么"——而这正是判断"包配置错"还是
                    // "动作确实没播"的唯一依据（BR-observability-003）。
                    // `kind=none` 表示连回退表情都没有（该别名本就无 fallbackExpression）。
                    Log.i(
                        TAG,
                        "pet action fell back: alias=$alias applied=${obj.optString("kind")}" +
                            " target=${obj.optString("target")} reason=${obj.optString("reason")}"
                    )
                } else {
                    // 成功路径此前**零日志**：真机无法区分"动作播了"与"压根没触发"。
                    // 这条同时是"待机动作是否真的在跑"的唯一证据（BR-observability-002）。
                    Log.i(
                        TAG,
                        "pet action played: alias=$alias kind=${obj.optString("kind")}" +
                            " target=${obj.optString("target")} index=${obj.optInt("index")}"
                    )
                }
            }
            "hit" -> {
                // 命中区点击是"完整桌宠功能"的核心交互，但此前**零日志** ——
                // 真机排障时无法区分"没点中"与"点中了但动作没播"。
                // 只记条数与别名（别名来自 pet.json，是本功能的配置数据，不含用户内容）。
                Log.i(
                    TAG,
                    "pet hit areas=${obj.optJSONArray("areas")?.length() ?: 0} alias=${obj.optString("alias")}"
                )
            }
            "error" -> {
                val code = obj.optString("code")
                manager.onServiceFailed(rendererFailureText(code))
                Log.w(TAG, "pet renderer error code=$code")
            }
        }
    }

    private fun rendererFailureText(code: String): String = when (code) {
        "pixi_missing", "runtime_missing" -> "渲染组件缺失，请重装应用"
        "model_load_failed" -> "形象文件无法加载（可能已损坏，建议重新导入）"
        "image_load_failed" -> "形象图片无法加载（建议重新导入）"
        "no_model", "no_image" -> "形象缺少必要文件"
        "bad_kind" -> "形象类型不受支持"
        else -> "形象渲染失败"
    }

    // ==================== WebView 资源路由 ====================

    private inner class PetWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url ?: return null
            // 纵深防御（guardrail L-2）：只认虚拟源的 **https**。
            // 只判 host 会让 `file://pet.prism.local/...` 这类别的 scheme 也走进本分支；
            // 虽然当前 WebSettings 已关掉 file 访问，但把判据写全比依赖别处的开关更可靠。
            if (url.scheme != "https" || url.host != VIRTUAL_HOST) return null
            val path = url.encodedPath ?: return null

            return runCatching { serve(path) }
                .onFailure { Log.w(TAG, "asset serve failed: ${it::class.simpleName}") }
                .getOrNull()
        }

        /**
         * 把导航限制在虚拟源内（guardrail L-2）。
         *
         * 页面是我们自己的资产，**不应发生任何导航**；任何跳转都意味着资产被替换或出现注入。
         * 一律拦截并留痕（不静默）。
         */
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val url = request?.url ?: return true
            if (url.scheme == "https" && url.host == VIRTUAL_HOST) return false
            Log.w(TAG, "pet webview navigation blocked (${url.scheme})")
            return true
        }

        private fun serve(path: String): WebResourceResponse? {
            PetAssetRoutes.toAssetPath(path)?.let { assetPath ->
                val stream: InputStream = assets.open("pet/$assetPath")
                return WebResourceResponse(PetAssetRoutes.mimeOf(assetPath), null, stream)
            }
            val petsDir = File(filesDir, PetManager.PETS_DIR)
            val file = PetAssetRoutes.resolvePetFile(petsDir, path) ?: return null
            return WebResourceResponse(
                PetAssetRoutes.mimeOf(file.name),
                null,
                file.inputStream().buffered()
            )
        }
    }

    // ==================== 生命周期 ====================

    override fun onDestroy() {
        Log.i(TAG, "pet overlay service destroyed")
        idleJob?.let { main.removeCallbacks(it) }
        bubbleHide?.let { main.removeCallbacks(it) }
        readyWatchdog?.let { main.removeCallbacks(it) }
        menuHide?.let { main.removeCallbacks(it) }
        idleJob = null
        bubbleHide = null
        readyWatchdog = null
        menuHide = null
        busJob?.cancel()
        scope.cancel()
        webView?.let { web ->
            runCatching { web.loadUrl("about:blank") }
            runCatching { web.removeJavascriptInterface("PetHost") }
            runCatching { web.destroy() }
        }
        root?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        root = null
        webView = null
        bubble = null
        menu = null
        params = null
        touchHandler = null
        manager.onServiceStopped()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val collapse = PendingIntent.getService(
            this,
            REQUEST_COLLAPSE,
            Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("桌宠显示中")
            .setContentText("在设置 → 桌宠 可调整或收起")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "收起桌宠", collapse)
            .build()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "PetOverlayService"

        /** 通知 action：收起。 */
        const val ACTION_STOP = "io.prism.pet.ACTION_STOP"

        /** 通知/设置页 action：重新加载形象。 */
        const val ACTION_REFRESH = "io.prism.pet.ACTION_REFRESH"

        /** 虚拟源 host（渲染页面与形象资产都由此源提供，不使用 file://）。 */
        const val VIRTUAL_HOST = "pet.prism.local"

        private const val ASSET_URL = "https://$VIRTUAL_HOST/assets/pet/index.html"

        private const val CHANNEL_ID = "prism_pet_overlay"
        private const val CHANNEL_NAME = "桌宠"
        private const val NOTIFICATION_ID = 2101
        private const val REQUEST_OPEN = 2102
        private const val REQUEST_COLLAPSE = 2103

        /** 窗口宽度占可用宽度的比例（角色类形象窄高，取屏宽 42% 观感合适）。 */
        private const val BASE_WIDTH_RATIO = 0.42f

        /** 窗口宽高比（宽 : 高 = 1 : BASE_ASPECT）。渲染层会在窗口内等比适配，故此处只求"够用"。 */
        private const val BASE_ASPECT = 1.35f

        /** 最小可点尺寸（dp）——小于它拖动会变得难以命中。 */
        private const val MIN_TOUCH_SIZE_DP = 56

        /** 气泡停留时长（PRD US-2408 AC-1）。 */
        private const val BUBBLE_HOLD_MS = 6000L

        /**
         * 渲染就绪超时（毫秒）。
         *
         * **8s → 15s（BUG-20260913-001 修复时上调）**：原值 8s 是在"下发时序还是错的、
         * 计时起点也不对"的前提下拍的。修好握手后，计时**才第一次真正从"渲染器收到 load"开始**，
         * 而它要覆盖的是"WebView 冷启动 + 解析 moc3 v5 + 上传 9MB 纹理"整条链——
         * 低端机 + 首次加载很可能超过 8s。宁可等久一点，也不要误报"形象不受支持"
         * （误报会把用户引向"换个形象试试"的错误方向）。
         */
        private const val READY_TIMEOUT_MS = 15_000L

        /**
         * 页面脚本就绪超时（毫秒）。取 5s：只是"HTML 解析 + 4 个脚本执行"，
         * 正常在 1s 内完成；超过 5s 基本可判定脚本链路断了（guardrail D-1）。
         */
        private const val BOOT_TIMEOUT_MS = 5_000L

        /** 菜单自动收起时长。 */
        private const val MENU_HOLD_MS = 5_000L

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        /**
         * 启动桌宠悬浮窗。
         *
         * **必须返回是否成功**（guardrail D-4）：早期实现把
         * `ForegroundServiceStartNotAllowedException` 只写进 logcat，
         * 调用方（`PetManager`/`PrismApplication`）**捕不到也看不到** ⇒
         * 恢复被系统拒绝时呈现"持久化开关是开的 + 界面显示已停止 + 没有任何原因"。
         * 现在由调用方据此调用 [PetManager.onServiceFailed] 给出可读反馈。
         *
         * @return true 表示请求已发出（不代表服务一定起得来，那由 `boot`/`ready` 事件确认）
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, PetOverlayService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    Log.w(TAG, "pet foreground service start rejected (Android 12+ restriction)")
                } else {
                    Log.w(TAG, "pet overlay service start failed (${e::class.simpleName})")
                }
                false
            }
        }

        /** 停止桌宠悬浮窗。 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PetOverlayService::class.java)) }
                .onFailure { Log.w(TAG, "pet overlay stop failed (${it::class.simpleName})") }
        }

        /** 通知运行中的窗口刷新形象/配置（未运行时由调用方保证不调）。 */
        fun refresh(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, PetOverlayService::class.java).setAction(ACTION_REFRESH)
                )
            }.onFailure {
                // guardrail M-2：不得静默吞。这里是"已有窗口时改设置"的唯一通道，
                // 失败意味着用户改了大小/不透明度却看不到任何变化——必须留痕。
                Log.w(TAG, "pet overlay refresh failed (${it::class.simpleName})")
            }
        }
    }
}
