package io.prism.phonecontrol.bg

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Log
import android.view.Display

/**
 * 批次20（BG-001，ADR-045）：手机操控后台化的虚拟屏管理器。
 *
 * **职责**：
 * - [ensureDisplay]：以主屏同分辨率/DPI 创建虚拟屏（幂等，复用同一 displayId），
 *   ImageReader 作为 Surface 提供帧——**免 MediaProjection 授权**（对比无障碍 takeScreenshot
 *   需 API 34+ 才支持 displayId 参数），虚拟屏画面即目标 App 画面，OCR 坐标零换算。
 * - [launchApp]：`ActivityOptions.setLaunchDisplayId` 把目标 App 启动到虚拟屏（用户主屏
 *   完全留给用户自己使用）。记录启动过的包名，[releaseDisplay] 时统一回收。
 * - [latestFrame]：取虚拟屏最新帧（Bitmap，虚拟屏坐标空间）。
 * - [releaseDisplay]：销毁虚拟屏 + 释放 ImageReader + killBackgroundProcesses 回收包名。
 *
 * **权限模型**（调研报告第 7 节核验）：
 * - 创建 VirtualDisplay（OWN_CONTENT_ONLY|PUBLIC）无需特殊权限；
 * - 后台启动 Activity 到虚拟屏受 Android 10+ 限制，豁免条件为 `SYSTEM_ALERT_WINDOW`
 *   （悬浮窗）——由 [canLaunchBackground] 判定，未授权时调用方返回引导文案；
 * - 坐标触摸注入到虚拟屏需 shell 级权限（`input -d`），纯 App 不可行 → 后台模式
 *   坐标手势由上层（PhoneControlLocalToolExecutor）返回固定错误文案。
 *
 * **线程安全**：ensure/release 全程持 [lock]；displayId/latestFrame 走 @Volatile + 短锁。
 * ImageReader 监听跑在专用 [HandlerThread]（保持队列流动，防 Surface 队列填满后停帧）。
 */
object VirtualDisplayManager {

    private const val TAG = "VirtualDisplayMgr"
    private const val DISPLAY_NAME = "prism-bg-display"

    /** launch_app 后 settle 等待（毫秒），与前台模式 LAUNCH_SETTLE_MS 语义一致。 */
    const val LAUNCH_SETTLE_MS = 700L

    /** ImageReader 最大缓冲帧数（保持队列流动的最小集）。 */
    private const val MAX_IMAGES = 2

    /** 批次22 修复轮六：系统模拟副屏规格——主屏可见小窗形态，尺寸刻意紧凑。
     *  批次22 修复轮七：宽/高/dpi 单一来源化（OVERLAY_SPEC 由三者拼装；isOverlaySizeMatch 校验共用）。 */
    private const val OVERLAY_WIDTH = 720
    private const val OVERLAY_HEIGHT = 1560
    private const val OVERLAY_DENSITY = 280
    private const val OVERLAY_SPEC = "${OVERLAY_WIDTH}x${OVERLAY_HEIGHT}/$OVERLAY_DENSITY"

    /** 模拟副屏等待轮数（×250ms ≈ 5s；MIUI 创建可达秒级，4s 曾贴线超时）。 */
    private const val OVERLAY_WAIT_ROUNDS = 20

    private val lock = Any()

    /**
     * 批次22 修复轮六：当前执行面是否为**系统模拟副屏（trusted）**——true 时截图走
     * a11y takeScreenshotForDisplay（无 ImageReader），合成输入可用。
     */
    @Volatile
    var isOverlayMode: Boolean = false
        private set

    @Volatile
    private var overlayDisplayObj: Display? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var listenerThread: HandlerThread? = null

    @Volatile
    private var displayId: Int = Display.INVALID_DISPLAY

    /** width/height/dpi（ensureDisplay 时记录，供测试与诊断）。 */
    @Volatile
    var displayWidth: Int = 0
        private set

    @Volatile
    var displayHeight: Int = 0
        private set

    @Volatile
    var displayDensity: Int = 0
        private set

    /** 已启动过的包名集合（release 时统一 killBackgroundProcesses 回收）。 */
    private val launchedPackages = LinkedHashSet<String>()

    /** 当前 displayId；未创建返回 [Display.INVALID_DISPLAY]。 */
    val currentDisplayId: Int
        get() = displayId

    /**
     * 虚拟屏是否已创建。批次22 修复轮六：**overlay-aware**——模拟副屏（trusted）路线无
     * ImageReader（virtualDisplay=null），漏判会导致 launch 必败 + release 永不清 overlay
     * 设置（guardrail B1）。
     */
    val isCreated: Boolean
        get() = displayId != Display.INVALID_DISPLAY && (isOverlayMode || virtualDisplay != null)

    /**
     * 后台模式是否被当前系统支持：需要 API 30+（getWindowsOnAllDisplays 跨屏无障碍）。
     */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 后台启动 Activity 豁免判定：用户授予悬浮窗（SYSTEM_ALERT_WINDOW）权限
     * （Android 官方《后台启动 activity 的限制》豁免条件）。
     */
    fun canLaunchBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 幂等创建后台执行面（已存在则直接返回当前 displayId）。
     *
     * 批次22 修复轮六（2026-09-07）：**优先系统模拟副屏（trusted）路线**——App 自建
     * untrusted 虚拟屏对第三方窗口"只渲染不响应合成输入"（tap/text 均无效，真机实证
     * DeepSeek/微信；scrcpy #6643 同类平台焦点问题）。改为 shell `settings put
     * overlay_display_devices` 创建**系统 trusted 副屏**（与开发者选项「模拟辅助显示设备」
     * 同一机制，主屏上的可见小窗形态），`am start --display`/`input -d` 对其有效。
     * Shizuku 未就绪/创建失败时回退自建虚拟屏（ImageReader 截图路径）。
     *
     * @return displayId；创建失败返回 [Display.INVALID_DISPLAY]
     */
    fun ensureDisplay(context: Context): Int {
        if (!isSupported()) return Display.INVALID_DISPLAY
        synchronized(lock) {
            if (isCreated) return displayId
            // ---- 路线 1：系统模拟副屏（trusted，需 Shizuku）----
            // guardrail Q1：beforeIds 快照必须在 setOverlayDisplay **之前**——exec 返回与
            // display 添加存在毫秒级竞态（真机实证创建仅 ~51ms），快照晚了会把新屏 id
            // 漏进基线集 → 差集永不命中 → 超时拆屏（本 Bug 原始形态）在窄窗口下复活
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val beforeIds = dm.getDisplays().map { it.displayId }.toHashSet()
            if (ShizukuShell.isReady() && ShizukuShell.setOverlayDisplay(OVERLAY_SPEC)) {
                // 批次22 修复轮七（2026-09-07）：**displayId 差集法检测**——替代原
                // name.contains("Overlay") 谓词。根因：overlay 屏名来自系统本地化资源
                // （AOSP display_manager_overlay_display_name），zh-CN 为「叠加视图 #1」，
                // 原谓词永不命中 → ~4.1s 空转超时误判 → 清设置误拆刚建好的 trusted 屏 →
                // 回退自建 untrusted 屏（touch NONE）全部合成输入失效（真机取证：
                // docs/reports/2026-09-07-bg-overlay-display-race-debug.md）。社区同构实现：
                // settings put 后按新增 displayId 差集等待（gist 1999AZZAR/virtual_display.sh）。
                var overlay: Display? = null
                var rounds = 0
                while (rounds < OVERLAY_WAIT_ROUNDS && overlay == null) {
                    overlay = dm.getDisplays().firstOrNull {
                        it.displayId != Display.DEFAULT_DISPLAY && it.displayId !in beforeIds &&
                            isOverlaySizeMatch(it.mode.physicalWidth, it.mode.physicalHeight)
                    }
                    if (overlay == null) {
                        try {
                            Thread.sleep(250)
                        } catch (e: InterruptedException) {
                            // guardrail Q2：响应中断（调用方取消）——恢复中断位后按未创建早退，
                            // 不清设置不回退（由上层 CancellationException 路径收口）
                            Thread.currentThread().interrupt()
                            return Display.INVALID_DISPLAY
                        }
                    }
                    rounds++
                }
                if (overlay == null) {
                    // 宽松重检（修复轮七 D1 防御）：严格尺寸未命中但已有新增 display 时**绝不
                    // 拆除**——按实际尺寸接受（防 mode 上报偏差重演"拆掉刚建好的屏"）。
                    // guardrail Q3：叠加 FLAG_PRESENTATION 位过滤（overlay 屏实证带该 flag），
                    // 防止把用户 5s 窗口内自开的投屏/外接屏误认为本应用执行面
                    overlay = dm.getDisplays().firstOrNull {
                        it.displayId != Display.DEFAULT_DISPLAY && it.displayId !in beforeIds &&
                            it.flags and Display.FLAG_PRESENTATION != 0
                    }
                    overlay?.let {
                        Log.w(TAG, "模拟副屏尺寸与规格不符（${it.mode.physicalWidth}x${it.mode.physicalHeight}），按实际尺寸接受")
                    }
                }
                val display = overlay
                if (display != null) {
                    val mode = display.mode
                    isOverlayMode = true
                    overlayDisplayObj = display
                    virtualDisplay = null
                    imageReader = null
                    listenerThread = null
                    displayId = display.displayId
                    displayWidth = mode.physicalWidth
                    displayHeight = mode.physicalHeight
                    // Display.Mode 不暴露 density——直接用 OVERLAY_DENSITY
                    displayDensity = OVERLAY_DENSITY
                    Log.i(TAG, "系统模拟副屏已就绪 displayId=$displayId ${displayWidth}x$displayHeight@$displayDensity（trusted，输入可用）")
                    return displayId
                }
                Log.w(TAG, "模拟副屏创建超时，回退自建虚拟屏")
                ShizukuShell.clearOverlayDisplay()
            }
            // ---- 路线 2：自建虚拟屏（ImageReader 截图；untrusted 窗口可能不响应合成输入）----
            return try {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val metrics = context.resources.displayMetrics
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val dpi = metrics.densityDpi
                // guardrail P2-2：局部引用提升，失败/异常路径 finally 内可回收（防 reader/thread 泄漏）
                var reader: ImageReader? = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, MAX_IMAGES)
                var thread: HandlerThread? = HandlerThread("prism-bg-display").apply { start() }
                try {
                    reader?.setOnImageAvailableListener(drainListener, Handler(thread!!.looper))
                    val vd = dm.createVirtualDisplay(
                        DISPLAY_NAME,
                        w,
                        h,
                        dpi,
                        reader!!.surface,
                        // OWN_CONTENT_ONLY：独立内容（不镜像主屏）；PUBLIC：允许承载其他 App 的 Activity
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    ) ?: run {
                        Log.w(TAG, "ensureDisplay: createVirtualDisplay 返回 null")
                        return Display.INVALID_DISPLAY
                    }
                    isOverlayMode = false
                    overlayDisplayObj = null
                    imageReader = reader
                    virtualDisplay = vd
                    listenerThread = thread
                    displayId = vd.display.displayId
                    displayWidth = w
                    displayHeight = h
                    displayDensity = dpi
                    // 已移交字段 → 局部引用置空（finally 跳过回收）
                    reader = null
                    thread = null
                    Log.i(TAG, "虚拟屏已创建 displayId=$displayId ${w}x$h@$dpi")
                    displayId
                } finally {
                    // 未移交的临时对象在此回收（创建失败/异常路径统一收口）。
                    // 修复轮九：与 releaseDisplay 同序同不变量——先摘监听/停线程（join 等
                    // 在途回调退出），再 close reader（drainListener 锁段后有"已释放"闸）
                    runCatching { reader?.setOnImageAvailableListener(null, null) }
                    thread?.quitSafely()
                    thread?.join(2_000L)
                    runCatching { reader?.close() }
                }
            } catch (e: Exception) {
                virtualDisplay = null
                imageReader = null
                listenerThread = null
                displayId = Display.INVALID_DISPLAY
                displayWidth = 0
                displayHeight = 0
                displayDensity = 0
                Log.w(TAG, "ensureDisplay 失败：${e::class.simpleName}")
                Display.INVALID_DISPLAY
            }
        }
    }

    /**
     * 启动对账（修复轮八，2026-09-07）：清理**进程死亡残留**的模拟副屏设置。
     *
     * 背景：`overlay_display_devices` 是全局系统设置，进程被杀（MIUI 回收/用户划掉）时
     * [releaseDisplay] 的空闲释放来不及执行 → "叠加视图"小窗永久滞留且用户无从关闭
     * （真机取证：18:12 会话后进程 18:14:29 死亡，120s 空闲释放排定在 18:15:54 未执行）。
     * 在应用启动/无障碍连接时调用：**仅当设置值精确等于本应用规格** [OVERLAY_SPEC] 且
     * 当前未持有虚拟屏时清除——不碰用户自开的模拟副屏配置（值不同）。
     *
     * guardrail Q-2（修复轮八）：清除判定必须在 [lock] 内**复检 isCreated**——否则与
     * 并发的 ensureDisplay（持锁"先写设置→等 display 出现→才置 isCreated"）形成
     * check-then-act 竞态，会拆掉刚建好的会话执行面；双入口（Application/a11y 连接）
     * 经 [reconcileAttempted] 去重，Shizuku 未就绪时不消费标记允许下次重试。
     */
    private val reconcileAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    fun reconcileStaleOverlay() {
        if (!ShizukuShell.isReady()) return
        if (!reconcileAttempted.compareAndSet(false, true)) return
        val current = ShizukuShell.getOverlayDisplaySetting() ?: run {
            reconcileAttempted.set(false)  // 读取失败：允许下个入口重试
            return
        }
        if (!isOurOverlaySpec(current)) return
        synchronized(lock) {
            if (isCreated) return  // 锁内复检：会话正在重建（竞态窗口内设置已是我们的规格）
            runCatching { ShizukuShell.clearOverlayDisplay() }
            Log.i(TAG, "启动对账：检测到进程死亡残留的模拟副屏设置（$current），已清除")
        }
    }

    /**
     * 判断设置值是否为本应用的模拟副屏规格（纯函数可测；[reconcileStaleOverlay] 判据）。
     */
    fun isOurOverlaySpec(value: String): Boolean = value.trim() == OVERLAY_SPEC

    /**
     * 在虚拟屏上启动目标应用（调用方已完成包名纠正与敏感拦截）。
     *
     * **批次20 真机修复 R2（RCA 问题1）**：Android 10+ 的 untrusted virtual display
     * **不允许承载第三方 App 的 Activity**（`VIRTUAL_DISPLAY_FLAG_TRUSTED` 为 @SystemApi，
     * 普通/shell 应用不可加——scrcpy #5997 与 jqssun/android-display-mirror 的 Shizuku 对照
     * 表实证）。真机 MIUI 实测 `startActivity(setLaunchDisplayId)` 抛 SecurityException。
     * 处理：catch SecurityException 单独分支返回系统限制说明；异常消息完整记录
     * （此前只打类名，丢失定位信息）。官方预检 API `isActivityStartAllowedOnDisplay`
     * 为 @hide 不可编译，故以异常捕获为准。
     * **完整后台化需要 Shizuku（shell 身份 trusted display）或 Android 14+ VirtualDeviceManager
     * 路线——作为后续批次决策项**。
     *
     * @return 成功/失败描述文案（含诊断；由上层决定是否 settle）
     */
    fun launchApp(context: Context, packageName: String): String {
        if (!isCreated) return "错误：后台虚拟屏未创建"
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "错误：未找到应用 $packageName 的启动入口"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 批次21（SZ-002，ADR-046）：shell 身份 `am start --display` 绕过 untrusted display
        // 启动限制与 BAL（后台/息屏可启动）。
        // 批次22 修复轮三（2026-09-06）：**废除迁移法（moveTaskToDisplay）**——真机实证：
        // 任务级迁移返回成功但第三方窗口无法挂载 untrusted 显示面（拼多多与 DeepSeek 均
        // 0 窗口，非 App 风控而是平台硬限制），迁移法整体为死路。改为**直启 + 窗口验证 +
        // 诚实降级**：am start 后轮询虚拟屏顶层窗口，未渲染即明确告知（不再让 LLM 盲试）。
        if (ShizukuShell.isReady()) {
            val component = intent.component?.flattenToString()
                ?: return "错误：未找到应用 $packageName 的启动组件"
            return try {
                val started = ShizukuShell.amStartOnDisplay(component, displayId)
                if (!started) {
                    Log.w(TAG, "shell am start --display 失败（$packageName）")
                    return "错误：shell 启动失败（am start 非零退出）。请确认 Shizuku 状态后重试，" +
                        "或显式传 mode=\"foreground\" 在主屏继续"
                }
                // 轮询窗口就绪（最多 6s；虚拟屏顶层窗口包名 == 目标包名才算渲染成功）
                var rendered = false
                var topPkg: String? = null
                repeat(12) {
                    Thread.sleep(500)
                    topPkg = io.prism.phonecontrol.PhoneControlAccessibilityService
                        .instance?.backgroundWindowPackage(displayId)
                    if (topPkg == packageName) {
                        rendered = true
                        return@repeat
                    }
                }
                if (rendered) {
                    synchronized(launchedPackages) { launchedPackages.add(packageName) }
                    Log.i(TAG, "已在虚拟屏渲染 $packageName（displayId=$displayId）")
                    "已在后台虚拟屏启动应用 $packageName（窗口已就绪）。" +
                        "注意：主屏上出现的「叠加视图」小窗是后台执行面（用户可见），任务结束后约 2 分钟自动关闭，" +
                        "也可在通知栏「Prism 正在操控手机」通知上点「关闭后台虚拟屏」立即关闭——可向用户说明"
                } else {
                    Log.w(TAG, "直启后虚拟屏无目标窗口（actual=$topPkg）→ 诚实判定拒绝渲染")
                    "错误：应用 $packageName 未能渲染到后台虚拟屏（当前虚拟屏顶层=${topPkg ?: "无窗口"}）。" +
                        "该 App 大概率拒绝在非物理屏运行（启动期检测），重试后台启动无效——" +
                        "请改用 mode=foreground（会短暂占用主屏，先告知用户）或 take_over"
                }
            } catch (e: Exception) {
                Log.w(TAG, "后台直启失败：${e::class.simpleName}: ${e.message}")
                "错误：后台启动应用失败（${e::class.simpleName}: ${e.message}）。请重试，或显式传 mode=\"foreground\" 在主屏继续"
            }
        }
        // 无 Shizuku：纯 App 路线（untrusted display 对第三方 App 恒被拒，保留原实现与诚实文案）
        return try {
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)
            context.startActivity(intent, options.toBundle())
            synchronized(launchedPackages) { launchedPackages.add(packageName) }
            Log.i(TAG, "已启动到虚拟屏 $packageName（displayId=$displayId）")
            "已在后台虚拟屏启动应用 $packageName"
        } catch (e: SecurityException) {
            Log.w(TAG, "launchApp(后台) SecurityException: ${e.message}")
            "错误：当前系统限制普通应用将其它应用启动到自建虚拟屏（untrusted display，${e.message}）。" +
                "完整后台化请安装并激活 Shizuku（https://shizuku.rikka.app/download/）后重试；" +
                "或对该次调用显式传 mode=\"foreground\" 在主屏继续执行任务"
        } catch (e: Exception) {
            // 后台启动被系统拒绝（MIUI 后台弹出权限等），完整记录异常消息便于定位
            Log.w(TAG, "launchApp(后台) 失败：${e::class.java.name}: ${e.message}")
            "错误：后台启动应用被系统拒绝（${e::class.java.simpleName}: ${e.message}）。" +
                "请对该次调用显式传 mode=\"foreground\" 在主屏继续执行任务"
        }
    }

    /**
     * 取虚拟屏最新帧（虚拟屏坐标空间 Bitmap）。
     *
     * 批次22 修复轮四（2026-09-07）：**帧缓存模式**——此前 latestFrame 依赖 acquireLatestImage
     * 即时取帧，但 drainListener 每帧 acquire→close 立即丢弃 → 静态画面（App 渲染后无新帧）
     * 时永远取不到帧（真机实证：后台截图恒"无画面"，OCR/坐标定位全废）。改为：listener 每帧
     * 转换后写入缓存，latestFrame 返回缓存快照（调用方 recycle 副本，缓存自持）。
     *
     * @return 帧位图副本（调用方负责 recycle）；未创建/从未渲染/异常返回 null
     */
    fun latestFrame(): Bitmap? {
        if (imageReader == null) return null
        return synchronized(lock) {
            try {
                frameCache?.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                Log.w(TAG, "latestFrame 失败：${e::class.simpleName}")
                null
            }
        }
    }

    /** 最新帧缓存（drainListener 写入；@Volatile 跨线程可见——listener 线程写、IO 线程读）。 */
    @Volatile
    private var frameCache: Bitmap? = null

    /** Image → Bitmap（处理 rowStride 行尾 padding）。 */
    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        @Suppress("DEPRECATION")
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        if (pixelStride != 4) return null
        return if (rowStride == w * 4) {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } else {
            val strideWidth = rowStride / pixelStride
            val padded = Bitmap.createBitmap(strideWidth, h, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
            padded.recycle()
            cropped
        }
    }

    /** 虚拟屏尺寸 (宽, 高)；未创建返回 null。 */
    fun frameSize(): Pair<Int, Int>? =
        if (isCreated) displayWidth to displayHeight else null

    /**
     * 释放虚拟屏并回收启动过的应用（killBackgroundProcesses——普通权限可用的最接近
     * force-stop 语义；目标在虚拟屏后台常驻会占内存，任务结束后应回收）。
     *
     * **批次22 修复轮九（2026-09-07，真机 SIGSEGV 取证）**：拆卸顺序必须——
     * **①先摘 ImageReader 监听 → ②停 HandlerThread 并 join 等待在途回调退出 →
     * ③再 release 虚拟屏 / close reader → ④最后回收 frameCache**。
     * 原顺序 `virtualDisplay.release() + imageReader.close()` 与 drainListener 线程
     * 在途的 `acquireLatestImage()/imageToBitmap` 形成 native use-after-close 竞态 →
     * SIGSEGV（Fatal signal 11 in "prism-bg-displa" 线程）→ 整进程死亡 → 无障碍服务
     * 掉线（"重连中"）。
     *
     * @return 是否成功释放（未创建时返回 true——幂等）
     */
    fun releaseDisplay(context: Context): Boolean {
        synchronized(lock) {
            if (!isCreated) return true
            return try {
                // ①摘掉监听（阻止新回调入队）
                runCatching { imageReader?.setOnImageAvailableListener(null, null) }
                // ②停线程并等待在途回调执行完毕（quitSafely 会跑完已 post 的消息再退出）。
                //   **join 超时后仍 close 的安全前提（结构不变量）**：drainListener 内所有
                //   reader 访问都在其进锁之前完成，锁段仅有 frameCache 交换 + 释放闸——
                //   超时场景在途回调只会卡在本锁（安全），锁段后严禁新增 reader 访问。
                //   正常回调 <10ms，2s join 仅作有界兜底（超时白等为已知成本，后台线程可接受）
                listenerThread?.quitSafely()
                listenerThread?.join(2_000L)
                // ③线程已退（或超时兜底）→ 释放显示面与 reader
                runCatching { virtualDisplay?.release() }
                runCatching { imageReader?.close() }
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                synchronized(launchedPackages) {
                    for (pkg in launchedPackages) {
                        // 批次21（SZ-005，ADR-046）：Shizuku 就绪时用 am force-stop（干净回收），
                        // 否则回退 killBackgroundProcesses（普通权限可用的最接近语义）
                        if (ShizukuShell.isReady()) {
                            runCatching { ShizukuShell.forceStop(pkg) }
                        } else {
                            runCatching { activityManager.killBackgroundProcesses(pkg) }
                        }
                    }
                    launchedPackages.clear()
                }
                Log.i(TAG, "虚拟屏已释放（曾启动包：已回收后台进程）")
                virtualDisplay = null
                imageReader = null
                listenerThread = null
                // ④线程已死，frameCache 无写者 → 此时回收安全
                frameCache?.recycle()
                frameCache = null
                // 批次22 修复轮六：模拟副屏路线 → 清除系统设置（回收可见小窗）
                if (isOverlayMode) {
                    runCatching { ShizukuShell.clearOverlayDisplay() }
                    overlayDisplayObj = null
                    isOverlayMode = false
                }
                displayId = Display.INVALID_DISPLAY
                displayWidth = 0
                displayHeight = 0
                displayDensity = 0
                true
            } catch (e: Exception) {
                Log.w(TAG, "releaseDisplay 失败：${e::class.simpleName}")
                false
            }
        }
    }

    /** 清空已启动包名记录（不杀进程；如任务完成后想保留应用常驻虚拟屏时用）。 */
    fun clearLaunchedPackages() {
        synchronized(launchedPackages) { launchedPackages.clear() }
    }

    /**
     * ImageReader 帧监听：双重职责——①保持 Surface 队列流动（虚拟屏持续渲染但无人消费时
     * 队列满会导致目标 App 停止提交帧）；②批次22 修复轮四起，转换最新帧写入 [frameCache]
     * （此前 acquire→close 直接丢弃，静态画面下 latestFrame 永远取不到帧）。转换失败保留旧缓存。
     */
    private val drainListener = ImageReader.OnImageAvailableListener { reader ->
        try {
            val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
            val bitmap = try {
                imageToBitmap(image)
            } finally {
                image.close()
            }
            if (bitmap != null) {
                // 换帧与 latestFrame 的 copy 同锁（防"copy 进行中旧帧被 recycle"竞态）。
                // guardrail P2-1/P3-1（修复轮九）：锁段加"已释放"闸——releaseDisplay 的
                // join(2s) 超时后仍会 close reader（安全性前提：回调内所有 reader 访问都在
                // 进锁之前完成，锁段后禁止新增 reader 访问）；超时场景下在途回调会卡在本锁
                // 直到释放完成，闸保证其丢弃残帧（不写已回收的 frameCache、不泄漏 bitmap）
                var old: Bitmap? = null
                var dropped = false
                synchronized(lock) {
                    if (imageReader == null) {
                        dropped = true  // 拆卸中/已拆卸：旧屏残帧，直接回收
                    } else {
                        old = frameCache
                        frameCache = bitmap
                    }
                }
                if (dropped) {
                    bitmap.recycle()
                    return@OnImageAvailableListener
                }
                old?.recycle()
            }
        } catch (_: Exception) {
        }
    }

    // ==================== 纯逻辑可测部分 ====================

    /**
     * overlay 副屏尺寸校验（纯函数可测；修复轮七差集法检测的防误报闸）。
     *
     * displayId 差集命中"新增 display"后，校验尺寸与 [OVERLAY_SPEC] 一致——防止把用户
     * 自开的投屏/模拟副屏误认为本应用的执行面。
     */
    fun isOverlaySizeMatch(width: Int, height: Int): Boolean =
        width == OVERLAY_WIDTH && height == OVERLAY_HEIGHT

    /**
     * 判断工具调用的 mode 参数是否合法（纯函数可测）。
     *
     * @param mode 工具参数（null 视为 foreground）
     * @return 归一化后的模式；非法值返回 null
     */
    fun normalizeMode(mode: String?): String? = when (mode?.trim()?.lowercase()) {
        null, "", "foreground", "前台" -> MODE_FOREGROUND
        "background", "后台" -> MODE_BACKGROUND
        else -> null
    }

    const val MODE_FOREGROUND = "foreground"
    const val MODE_BACKGROUND = "background"
}
