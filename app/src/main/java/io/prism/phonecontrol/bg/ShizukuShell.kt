package io.prism.phonecontrol.bg

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * 批次21（SZ-001，ADR-046）：Shizuku shell 命令通道 —— 后台化完整能力的提权层。
 *
 * **能力**：通过 [ShizukuUserService]（shell 身份进程，经 AIDL 绑定）执行受控白名单命令，
 * 绕过 untrusted virtual display 硬限制（ADR-045 R2 取证）：
 * - `am start --display <id> -n <pkg>/<activity>`：shell 持 START_ANY_ACTIVITY，
 *   可将任意 App 启动到虚拟屏；
 * - `input -d <id> tap/swipe/keyevent`：后台手势全量解锁（修复轮八：`-d` 短选项，无 `--display`）；
 * - `am force-stop <pkg>`：任务回收。
 *
 * **安全边界（PRD §4 / ADR-046 风险缓解）**：
 * - 命令**仅由代码拼装**，不接收任何自由文本；包名必须经上层敏感拦截
 *   （[io.prism.phonecontrol.PhoneControlSecurity.isSensitivePackage]）后传入；
 * - 参数类型受限（displayId/坐标为 int，包名/组件来自 PackageManager）；
 * - 用户显式授予 Shizuku 权限（[Shizuku.requestPermission] 流程）。
 *
 * **四态**（[State]）：NOT_INSTALLED（Shizuku App 未装）/ NOT_RUNNING（已装未激活——server
 * 未运行）/ NOT_AUTHORIZED（已激活未授权）/ READY。调用方按态引导。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** Shizuku 服务状态（批次21 真机修复 R3：拆分"未安装"与"已安装未激活"——真机实证
     *  用户装了 Shizuku App 但未激活 server 时，pingBinder 失败被误报为"未安装"，引导失真）。 */
    enum class State { NOT_INSTALLED, NOT_RUNNING, NOT_AUTHORIZED, READY }

    /** Shizuku manager 应用包名（用于区分"未安装"与"已安装未激活"）。 */
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** exec/绑定默认超时（毫秒）。 */
    const val EXEC_TIMEOUT_MS = 5_000L

    /** UserService 版本（实现变更时递增以触发 Shizuku 重启该进程）。
     *  修复轮八：+execWithOutput（AIDL 新方法）→ 升 2。
     *  ADR-065（US-3114）：+隐藏虚拟屏三方法 → 升 3；guardrail r1 C-4：截屏加 maxWidth → 升 4。 */
    private const val USER_SERVICE_VERSION = 4

    private val bindLock = Any()

    /** 应用上下文（PrismApplication.onCreate 注入；PackageManager 探测用）。 */
    @Volatile
    private var appContext: android.content.Context? = null

    /** 已绑定的 shell 进程服务（惰性建立，进程内复用）。 */
    @Volatile
    private var userService: IShizukuUserService? = null

    /** 应用侧注入（[io.prism.PrismApplication.onCreate] 调用；未注入时三态降级为旧行为）。 */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /**
     * 探测 Shizuku 服务与授权状态（**四态**）。
     *
     * pingBinder 失败 → PackageManager 探测 [SHIZUKU_PACKAGE]：
     * 包不存在 = NOT_INSTALLED（未安装）；包存在 = NOT_RUNNING（**已安装未激活**——
     * server 未运行，真机实证场景）；绑定成功但未授权 = NOT_AUTHORIZED；否则 READY。
     *
     * @return 四态；任何异常按 NOT_INSTALLED 处理（不崩溃）
     */
    fun currentState(): State = try {
        if (!Shizuku.pingBinder()) {
            // server 不可达：区分未安装 vs 已安装未激活
            val installed = appContext?.let { ctx ->
                runCatching {
                    ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
                    true
                }.getOrDefault(false)
            } ?: false
            if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
        } else if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            State.READY
        } else {
            State.NOT_AUTHORIZED
        }
    } catch (e: Exception) {
        // Shizuku 未安装时 pingBinder/类加载可能抛（Provider 不存在等）
        State.NOT_INSTALLED
    }

    /** Shizuku 是否就绪（READY 快捷判定）。 */
    fun isReady(): Boolean = currentState() == State.READY

    /**
     * 请求 Shizuku 授权（弹出 Shizuku 的授权对话框；结果异步，经再次 [currentState] 确认）。
     *
     * @param requestCode Activity 侧用于结果回调的请求码
     * @return 是否成功发起请求（服务不可用返回 false）
     */
    fun requestPermission(requestCode: Int): Boolean = try {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(requestCode)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 惰性绑定 [ShizukuUserService]（shell 身份进程），进程内复用。
     *
     * @return 绑定成功的服务接口；超时/未授权/异常返回 null
     */
    private fun ensureUserService(): IShizukuUserService? {
        userService?.let { return it }
        if (currentState() != State.READY) return null
        synchronized(bindLock) {
            userService?.let { return it }
            return try {
                val latch = java.util.concurrent.CountDownLatch(1)
                var bound: IShizukuUserService? = null
                val args = Shizuku.UserServiceArgs(
                    // guardrail R5 P3：类名经 KClass 派生（重构改名自动跟随）；包名硬编码
                    // "io.prism"（applicationId 唯一且 buildConfig 特性未启用，无 BuildConfig 可用）
                    ComponentName("io.prism", ShizukuUserService::class.java.name)
                )
                    .processNameSuffix("shizuku")
                    .version(USER_SERVICE_VERSION)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        bound = IShizukuUserService.Stub.asInterface(service)
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        // shell 进程被回收：清缓存，下次调用重绑
                        synchronized(bindLock) { userService = null }
                    }
                }
                Shizuku.bindUserService(args, connection)
                latch.await(EXEC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                val svc = bound
                if (svc == null) {
                    Log.w(TAG, "bindUserService 超时/失败（${EXEC_TIMEOUT_MS}ms）")
                    return null
                }
                userService = svc
                svc
            } catch (e: Exception) {
                Log.w(TAG, "bindUserService 异常：${e::class.simpleName}")
                null
            }
        }
    }

    /**
     * 以 shell 身份执行命令（白名单方法内部使用；**不对外暴露原始命令入口**）。
     *
     * @param commands 命令与参数（由本类公开方法拼装）
     * @return true=退出码 0；false=失败/超时/异常
     */
    private fun exec(vararg commands: String): Boolean {
        val svc = ensureUserService() ?: return false
        return try {
            val exit = svc.exec(commands)
            if (exit != 0) {
                Log.w(TAG, "exec 失败：${commands.joinToString(" ").take(120)} exit=$exit")
            }
            exit == 0
        } catch (e: Exception) {
            // 远程异常（含 SecurityException：Shizuku 权限被运行中撤销）→ 清缓存重三态
            Log.w(TAG, "exec 异常：${e::class.simpleName}")
            synchronized(bindLock) { userService = null }
            false
        }
    }

    // ==================== 受控命令（SZ-002/003/005） ====================

    /**
     * 以 shell 身份执行命令并捕获输出（修复轮八：供"读设置"类命令用，如启动对账）。
     *
     * @return stdout+stderr 合并输出（trim 后）；失败/超时/异常返回 null
     */
    private fun execOutput(vararg commands: String): String? {
        val svc = ensureUserService() ?: return null
        return try {
            svc.execWithOutput(commands)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "execOutput 异常：${e::class.simpleName}")
            synchronized(bindLock) { userService = null }
            null
        }
    }

    /**
     * 读取系统通知摘要（**仅用于 QQ 新消息轻量探测**，PRD US-3111 AC-4）。
     *
     * 固定命令、不接受任何自由输入（无注入面）；shell 持有 DUMP 权限，可看到全用户通知。
     * 输出可能较大，调用方必须限频（默认 90s）并只做散列比对，不解析具体内容。
     */
    fun notificationSnapshot(): String? = execOutput("dumpsys", "notification")

    /**
     * 读当前 `overlay_display_devices` 设置值（修复轮八启动对账用）。
     *
     * @return 设置值（trim 后）；未设置/读取失败返回 null
     */
    fun getOverlayDisplaySetting(): String? =
        execOutput(*buildSettingsGetCommand())

    /**
     * 拼装 `settings get` 命令数组（纯函数可测；[getOverlayDisplaySetting] 的命令体）。
     */
    fun buildSettingsGetCommand(): Array<String> =
        arrayOf("settings", "get", "global", "overlay_display_devices")

    /**
     * 以 shell 身份把目标 App 启动到指定虚拟屏（绕过 untrusted display 限制）。
     *
     * @param component 启动组件（"pkg/.Activity" 形式，来自 PackageManager 的 launcher intent）
     * @param displayId 虚拟屏 id
     * @return true=启动成功
     */
    fun amStartOnDisplay(component: String, displayId: Int): Boolean =
        exec(*buildAmStartCommand(component, displayId))

    /**
     * 以 shell 身份在指定**用户**下启动应用（QQ 小号助手，ADR-062：MIUI 应用双开运行于
     * XSpace 用户 999，App 自身无跨用户启动能力，shell 的 `am start --user` 可达——M0 实证）。
     *
     * **安全边界**：与 [amStartOnDisplay] 同一白名单范式——component 仅由调用方代码拼装
     * （QQ 主 Activity 常量），userId 为 Int，不接收任何自由文本。
     *
     * @param component 形如 `com.tencent.mobileqq/.activity.SplashActivity`
     * @param userId 目标用户 id（XSpace=999）
     */
    fun amStartForUser(component: String, userId: Int): Boolean =
        exec("am", "start", "--user", userId.toString(), "-n", component)

    /**
     * 以 shell 身份在指定**用户**下把目标 App 启动到指定**虚拟屏**（ADR-062 QQ 小号助手：
     * 小号运行在 XSpace 用户 999，且须在**后台副屏**执行——既有 [amStartForUser]（无 display）
     * 与 [amStartOnDisplay]（无 user）都无法同时跨用户 + 跨屏，App 自身的 `setLaunchDisplayId`
     * 亦只能作用于当前用户）。
     *
     * **forceStopFirst 的必要性**（2026-10-19 真机 PoC）：目标 App 若已在其它 display 上有窗口，
     * `am start --display` 只会把该既有实例拉到其**原 display** 前台（真机实证 "delivered to
     * currently running top-most instance"），不会迁移到目标副屏——那样小号会落到主屏（占用
     * 用户前台，正是本改造要消除的问题）。加 `-S`（force-stop 后重启）可确保以小号在**目标副屏**
     * 顶层重新渲染（真机实证：display 0 运行中的小号被成功迁至副屏）。
     *
     * **安全边界**：与既有白名单范式一致——component 仅由调用方代码拼装，userId / displayId
     * 均为 Int，forceStopFirst 为布尔，不接收任何自由文本。
     *
     * @param component 形如 `com.tencent.mobileqq/.activity.SplashActivity`
     * @param userId 目标用户 id（XSpace=999）
     * @param displayId 目标虚拟屏 displayId
     * @param forceStopFirst 启动前 force-stop 目标 App（确保迁至目标屏，见上）
     */
    fun amStartForUserOnDisplay(
        component: String,
        userId: Int,
        displayId: Int,
        forceStopFirst: Boolean = false
    ): Boolean = exec(*buildAmStartForUserOnDisplayCommand(component, userId, displayId, forceStopFirst))

    /**
     * 以 shell 身份向指定虚拟屏注入触摸（tap/swipe；long_press=慢速 swipe、
     * double_tap=两次快速 tap 由调用方拼参数）。
     *
     * **批次22 修复轮八（2026-09-07，真机取证）**：选项必须用 **`-d`**——`input` 的官方
     * 用法为 `input [<source>] [-d DISPLAY_ID] <command>`（AOSP InputShellCommand onShowUsage），
     * **不存在 `--display` 长选项**；传 `--display` 时 ROM 打印 "Unknown command: --display"
     * 且 **exit=0**（真机 Android 16/MIUI OS3.0.306 实证）→ 注入从未执行但工具报成功。
     * 此前批次21 调研误记为 `--display`（那是 `am start` 的选项，两者不同）。
     */
    fun inputTap(displayId: Int, x: Int, y: Int): Boolean =
        exec(*buildInputTapCommand(displayId, x, y))

    fun inputSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        exec(*buildInputSwipeCommand(displayId, x1, y1, x2, y2, durationMs))

    /**
     * 以 shell 身份向指定虚拟屏发送按键（back/home）。
     *
     * @param keyCode 如 KEYCODE_BACK=4 / KEYCODE_HOME=3
     */
    fun inputKeyevent(displayId: Int, keyCode: Int): Boolean =
        exec(*buildKeyeventCommand(displayId, keyCode))

    /**
     * 批次22 修复轮四（2026-09-07）：以 shell 身份向指定虚拟屏的**焦点窗口**注入文字
     * （`input text` 走按键事件注入，无需 IME；先经 inputTap 聚焦输入框）。
     * 注意：CJK 字符在部分 ROM 上经 VIRTUAL_KEYBOARD 键表可能丢失——调用方应优先走
     * 剪贴板粘贴路径，本方法作为 ASCII/数字文本的兜底。
     *
     * @param text 完整文本（含空格作为单 argv 传入，无 shell 解释面）
     */
    fun inputText(displayId: Int, text: String): Boolean =
        exec(*buildInputTextCommand(displayId, text))

    /**
     * 拼装 `input -d text` 命令数组（纯函数可测；[inputText] 的命令体）。
     * 批次22 修复轮七：对齐 SZ-101 约定（公开 shell 方法一律配 buildXxxCommand 纯函数）。
     * 修复轮八：选项用 `-d`。
     */
    fun buildInputTextCommand(displayId: Int, text: String): Array<String> =
        arrayOf("input", "-d", displayId.toString(), "text", text)

    /**
     * 批次22 修复轮六（2026-09-07）：创建/清除**系统模拟副屏**（trusted display）。
     *
     * 背景：App 自建 untrusted 虚拟屏对第三方窗口只渲染不响应合成输入（真机实证 DeepSeek/
     * 微信 tap/text 均无效；scrcpy #6643 同类平台焦点问题）。而开发者选项「模拟辅助显示
     * 设备」创建的副屏由**系统**创建（trusted），`am start --display` + `input -d`
     * 对其完全有效。shell 持 WRITE_SECURE_SETTINGS 可直接写该设置项自动创建（与开发者选项
     * 同一机制，值格式 "宽x高/dpi"，效果为**主屏上的可见小窗**——可在系统内拖动）。
     *
     * @param spec 如 "720x1560/280"；空串清除
     */
    fun setOverlayDisplay(spec: String): Boolean =
        exec("settings", "put", "global", "overlay_display_devices", spec)

    fun clearOverlayDisplay(): Boolean =
        exec("settings", "put", "global", "overlay_display_devices", "")

    /**
     * 以 shell 身份 force-stop 目标应用（任务回收，比 killBackgroundProcesses 干净）。
     */
    fun forceStop(packageName: String): Boolean =
        exec("am", "force-stop", packageName)

    /**
     * 批次22（SZ-101，ADR-047）：以 shell 身份给目标包授予运行时权限（幂等——已授权时
     * pm grant 返回成功）。语音场景：给**识别引擎 App**（非 Prism）授 RECORD_AUDIO。
     */
    fun pmGrant(packageName: String, permission: String): Boolean =
        exec(*buildPmGrantCommand(packageName, permission))

    /**
     * 拼装 `pm grant` 命令数组（纯函数可测；[pmGrant] 的命令体，SZ-101 验收项）。
     */
    fun buildPmGrantCommand(packageName: String, permission: String): Array<String> =
        arrayOf("pm", "grant", packageName, permission)

    // ==================== 隐藏虚拟屏（ADR-065 / US-3114） ====================

    /** 隐藏虚拟屏创建结果（[createHiddenDisplay] 返回值的解析产物）。 */
    sealed class HiddenDisplayResult {
        /** 创建成功：displayId + 实际生效的 flags（用于诊断哪一级降级命中）。 */
        data class Ok(val displayId: Int, val flags: Int) : HiddenDisplayResult()

        /** 创建失败：可读原因（由 shell 进程回传，含异常类名与消息）。 */
        data class Error(val message: String) : HiddenDisplayResult()
    }

    /**
     * 在 shell 进程内创建隐藏虚拟屏（ADR-065 默认后端；PoC 配方见
     * docs/reports/2026-10-21-qq-hidden-vd-poc.md）。
     *
     * 关键约束（PoC 实证）：必须由创建者进程自持 ImageReader surface、Context 包名必须与
     * calling uid 一致（shell = com.android.shell）；这些均由 [ShizukuUserService] 内部完成。
     *
     * @return 成功含 displayId/flags；失败含可读原因（调用方回落 overlay 并如实报告）
     */
    fun createHiddenDisplay(width: Int, height: Int, density: Int): HiddenDisplayResult {
        val svc = ensureUserService()
            ?: return HiddenDisplayResult.Error("Shizuku 未就绪（shell 进程不可用）")
        val raw = try {
            svc.createHiddenDisplay(width, height, density)
        } catch (e: Exception) {
            synchronized(bindLock) { userService = null }
            Log.w(TAG, "createHiddenDisplay 异常：${e::class.simpleName}")
            return HiddenDisplayResult.Error("Binder 调用失败（${e::class.simpleName}）")
        }
        return parseHiddenDisplayResult(raw)
    }

    /**
     * 解析 [createHiddenDisplay] 的响应（纯函数可测）。
     *
     * 协议：`OK:<displayId>:0x<flagsHex>` / `ERR:<异常类名>:<消息>`。
     * displayId 必须为正数（0=主屏、-1=INVALID；隐藏屏不可能是这些值，出现即视为失败）。
     */
    fun parseHiddenDisplayResult(raw: String?): HiddenDisplayResult {
        if (raw.isNullOrBlank()) return HiddenDisplayResult.Error("空响应")
        if (raw.startsWith("OK:")) {
            val parts = raw.split(':', limit = 3)
            val id = parts.getOrNull(1)?.toIntOrNull()
                ?: return HiddenDisplayResult.Error("响应格式非法：${raw.take(120)}")
            if (id <= 0) return HiddenDisplayResult.Error("非法 displayId=$id")
            val flags = parts.getOrNull(2)?.removePrefix("0x")?.toIntOrNull(16) ?: 0
            return HiddenDisplayResult.Ok(id, flags)
        }
        return HiddenDisplayResult.Error(raw.removePrefix("ERR:").ifBlank { raw })
    }

    /** 释放隐藏虚拟屏（幂等；失败返回 false）。 */
    fun releaseHiddenDisplay(): Boolean {
        val svc = ensureUserService() ?: return false
        return try {
            svc.releaseHiddenDisplay() == "OK"
        } catch (e: Exception) {
            Log.w(TAG, "releaseHiddenDisplay 异常：${e::class.simpleName}")
            synchronized(bindLock) { userService = null }
            false
        }
    }

    /**
     * 取隐藏虚拟屏最新帧 PNG 字节（无帧/失败返回 null）。
     *
     * @param maxWidth 预览最大宽度（0=原始尺寸；Watch 传 360 降低编码/Binder/GC 压力）
     */
    fun captureHiddenFramePng(maxWidth: Int = 0): ByteArray? {
        val svc = ensureUserService() ?: return null
        return try {
            svc.captureHiddenFramePng(maxWidth)
        } catch (e: Exception) {
            Log.w(TAG, "captureHiddenFramePng 异常：${e::class.simpleName}")
            synchronized(bindLock) { userService = null }
            null
        }
    }

    // ==================== 纯函数（可测） ====================

    /**
     * 拼装 `am start --display` 命令数组（纯函数可测；[amStartOnDisplay] 的命令体）。
     */
    fun buildAmStartCommand(component: String, displayId: Int): Array<String> =
        arrayOf("am", "start", "--display", displayId.toString(), "-n", component)

    /**
     * 拼装 `am start --user <uid> --display <id> [-S] -n <component>` 命令数组（纯函数可测；
     * [amStartForUserOnDisplay] 的命令体）。`am start` 使用长短选项均可（与 `input` 的 `-d`
     * 约束不同，本方法按 AOSP am 用法使用 `--user`/`--display` 长选项）。
     */
    fun buildAmStartForUserOnDisplayCommand(
        component: String,
        userId: Int,
        displayId: Int,
        forceStopFirst: Boolean = false
    ): Array<String> = buildList {
        add("am"); add("start")
        add("--user"); add(userId.toString())
        add("--display"); add(displayId.toString())
        if (forceStopFirst) add("-S")
        add("-n"); add(component)
    }.toTypedArray()

    /**
     * 拼装 `input -d tap` 命令数组（纯函数可测）。
     * 修复轮八：选项用 `-d`（`input` 无 `--display` 长选项，"Unknown command" 且 exit=0）。
     */
    fun buildInputTapCommand(displayId: Int, x: Int, y: Int): Array<String> =
        arrayOf("input", "-d", displayId.toString(), "tap", x.toString(), y.toString())

    /**
     * 拼装 `input -d swipe` 命令数组（纯函数可测）。
     */
    fun buildInputSwipeCommand(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long
    ): Array<String> = arrayOf(
        "input", "-d", displayId.toString(), "swipe",
        x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString()
    )

    /**
     * 拼装 `input -d keyevent` 命令数组（纯函数可测）。
     */
    fun buildKeyeventCommand(displayId: Int, keyCode: Int): Array<String> =
        arrayOf("input", "-d", displayId.toString(), "keyevent", keyCode.toString())
}

/**
 * 批次21（SZ-001，ADR-046）：Shizuku UserService 实现——由 Shizuku 在 **shell 身份进程**中
 * 实例化（构造参数 context 由 Shizuku 注入），[exec] 在该进程内以 shell 权限执行命令。
 */
class ShizukuUserService(private val context: android.content.Context?) : IShizukuUserService.Stub() {

    /** 与 ShizukuShell.EXEC_TIMEOUT_MS 一致的等待上限（UserService 进程内独立常量）。 */
    private val EXEC_TIMEOUT_MS = 5_000L

    override fun exec(commands: Array<out String>?): Int {
        if (commands.isNullOrEmpty()) return -1
        return try {
            val process = Runtime.getRuntime().exec(commands)
            val finished = process.waitFor(EXEC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (finished) process.exitValue() else {
                process.destroy()
                -1
            }
        } catch (e: Exception) {
            android.util.Log.w("ShizukuUserService", "exec 异常：${e::class.simpleName}")
            -1
        }
    }

    /**
     * 批次22 修复轮八：exec + 捕获 stdout/stderr（utf-8 合并）。设置读取类命令用；
     * 超时/异常返回 null（调用方按"读不到"处理）。
     */
    override fun execWithOutput(commands: Array<out String>?): String? {
        if (commands.isNullOrEmpty()) return null
        val process = try {
            // redirectErrorStream 合并 stderr——避免分别读两流时缓冲区填满导致的管道死锁
            ProcessBuilder(*commands).redirectErrorStream(true).start()
        } catch (e: Exception) {
            android.util.Log.w("ShizukuUserService", "execWithOutput 启动失败：${e::class.simpleName}")
            return null
        }
        // guardrail Q-1（修复轮八）：readText 在 waitFor 之前执行会绕过超时契约（命令挂起
        // 且不关流时永久阻塞）——读流放独立线程，主路径 waitFor(5s) 强制限时，超时
        // destroyForcibly 让读线程随流 EOF 退出
        val result = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val reader = Thread {
            result.set(runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            }.getOrNull())
        }
        reader.start()
        return try {
            val finished = process.waitFor(EXEC_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            reader.join(1_000L)  // 输出收尾兜底（正常退出后流已 EOF，几乎立即结束）
            if (process.exitValue() == 0) result.get() else null
        } catch (e: Exception) {
            android.util.Log.w("ShizukuUserService", "execWithOutput 异常：${e::class.simpleName}")
            runCatching { process.destroyForcibly() }
            null
        }
    }

    // ==================== 隐藏虚拟屏（ADR-065 / US-3114） ====================
    // Derived from opencyvis/opencyvis-phone (Apache-2.0)：
    // 特权进程建屏范式 + flag 逐级降级（见 NOTICE 与 ADR-065）。未引入其产品壳。

    private val hiddenLock = Any()

    @Volatile
    private var hiddenDisplay: android.hardware.display.VirtualDisplay? = null

    @Volatile
    private var hiddenReader: android.media.ImageReader? = null

    @Volatile
    private var hiddenThread: android.os.HandlerThread? = null

    /** 最新帧缓存（监听线程写、Binder 线程读；换帧与取帧同 [hiddenLock]）。 */
    @Volatile
    private var hiddenFrame: android.graphics.Bitmap? = null

    override fun createHiddenDisplay(width: Int, height: Int, density: Int): String {
        // guardrail r1 Q-11：AIDL 是跨进程契约，必须有上界（当前调用方是常量，但契约面
        // 不允许 100000x100000 让 shell 进程 OOM）。Long 乘法防溢出。
        if (width <= 0 || height <= 0 || density <= 0 ||
            width > HIDDEN_MAX_DIMENSION || height > HIDDEN_MAX_DIMENSION ||
            width.toLong() * height.toLong() > HIDDEN_MAX_PIXELS
        ) {
            return "ERR:IllegalArgumentException:尺寸/DPI 非法（$width x $height @ $density）"
        }
        synchronized(hiddenLock) {
            hiddenDisplay?.let {
                return "OK:${it.display?.displayId ?: -1}:0x0"
            }
            var reader: android.media.ImageReader? = null
            var thread: android.os.HandlerThread? = null
            var vd: android.hardware.display.VirtualDisplay? = null
            var committed = false
            return try {
                // PoC 实证：Context 包名必须与 calling uid 一致（shell=com.android.shell），
                // 系统 Context 的包名 android（uid 1000）必抛 SecurityException。
                val shellCtx = hiddenShellContext()
                    ?: return "ERR:SecurityException:无法取得 com.android.shell 上下文"
                val dm = shellCtx.getSystemService(android.content.Context.DISPLAY_SERVICE)
                    as android.hardware.display.DisplayManager
                reader = android.media.ImageReader.newInstance(
                    width, height, android.graphics.PixelFormat.RGBA_8888, HIDDEN_MAX_IMAGES
                )
                thread = android.os.HandlerThread("prism-hidden-vd").apply { start() }
                reader.setOnImageAvailableListener(hiddenDrainListener, android.os.Handler(thread.looper))
                var createdFlags = 0
                var lastError: Throwable? = null
                // OpenCyvis flag 阶梯：完整组合优先，逐级剥离 TRUSTED/扩展位（CHANGELOG 2.0.0）
                for (flags in HiddenDisplayFlags.LADDER) {
                    try {
                        vd = dm.createVirtualDisplay(
                            HIDDEN_DISPLAY_NAME, width, height, density, reader.surface, flags
                        )
                        if (vd != null) {
                            createdFlags = flags
                            break
                        }
                    } catch (t: Throwable) {
                        lastError = t
                    }
                }
                val created = vd
                if (created == null) {
                    val err = lastError
                    return "ERR:${err?.javaClass?.name ?: "Unknown"}:${err?.message ?: "createVirtualDisplay 返回 null"}"
                }
                val id = created.display?.displayId ?: -1
                if (id <= 0) {
                    return "ERR:IllegalStateException:非法 displayId=$id"
                }
                // 全部成功后才提交状态（成对不变量：display/reader/thread 同时生效）
                hiddenDisplay = created
                hiddenReader = reader
                hiddenThread = thread
                committed = true
                reader = null
                thread = null
                vd = null
                android.util.Log.i(
                    "ShizukuUserService",
                    "隐藏虚拟屏已创建 displayId=$id ${width}x$height@$density flags=0x${Integer.toHexString(createdFlags)}"
                )
                "OK:$id:0x${Integer.toHexString(createdFlags)}"
            } catch (t: Throwable) {
                "ERR:${t.javaClass.name}:${t.message ?: ""}"
            } finally {
                // guardrail r1 Q-01：未提交（含"已创建但后续失败"）统一清理并复位，
                // 保证下一次 create 不会命中已死 displayId。
                if (!committed) {
                    runCatching { reader?.setOnImageAvailableListener(null, null) }
                    thread?.let { t ->
                        runCatching { t.quitSafely() }
                        runCatching { t.join(2_000L) }
                    }
                    runCatching { vd?.release() }
                    runCatching { reader?.close() }
                    hiddenDisplay = null
                    hiddenReader = null
                    hiddenThread = null
                }
            }
        }
    }

    override fun releaseHiddenDisplay(): String {
        // guardrail r1 Q-01：先在锁内**摘除并复位**字段，再做拆卸——即使拆卸抛异常，
        // 状态也不会残留为"已就绪"，杜绝复用已死 displayId。
        val display: android.hardware.display.VirtualDisplay?
        val reader: android.media.ImageReader?
        val thread: android.os.HandlerThread?
        val frame: android.graphics.Bitmap?
        synchronized(hiddenLock) {
            if (hiddenDisplay == null && hiddenReader == null) return "OK"
            display = hiddenDisplay
            reader = hiddenReader
            thread = hiddenThread
            frame = hiddenFrame
            hiddenDisplay = null
            hiddenReader = null
            hiddenThread = null
            hiddenFrame = null
        }
        return try {
            // 拆卸顺序同 VirtualDisplayManager（防 native use-after-close SIGSEGV）：
            // ①摘监听 → ②停线程 join → ③release display / close reader → ④回收帧缓存
            runCatching { reader?.setOnImageAvailableListener(null, null) }
            thread?.let { t ->
                runCatching { t.quitSafely() }
                runCatching { t.join(2_000L) }
            }
            runCatching { display?.release() }
            runCatching { reader?.close() }
            frame?.recycle()
            android.util.Log.i("ShizukuUserService", "隐藏虚拟屏已释放")
            "OK"
        } catch (t: Throwable) {
            "ERR:${t.javaClass.name}:${t.message ?: ""}"
        }
    }

    override fun captureHiddenFramePng(maxWidth: Int): ByteArray? {
        val source = synchronized(hiddenLock) {
            hiddenFrame?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        } ?: return null
        var scaled: android.graphics.Bitmap? = null
        return try {
            // guardrail r1 Q-04：Watch 预览降采样（默认 360 宽），降低 shell 端 PNG 编码、
            // Binder 传输与 App 端解码/GC 压力；截图（maxWidth=0）保持原始分辨率。
            val target = if (maxWidth > 0 && source.width > maxWidth) {
                val h = (source.height.toLong() * maxWidth / source.width).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(source, maxWidth, h, true).also { scaled = it }
            } else {
                source
            }
            java.io.ByteArrayOutputStream().use { out ->
                target.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray().takeIf { it.size <= HIDDEN_MAX_FRAME_BYTES }
            }
        } catch (t: Throwable) {
            android.util.Log.w("ShizukuUserService", "帧编码失败：${t.javaClass.simpleName}")
            null
        } finally {
            scaled?.recycle()
            source.recycle()
        }
    }

    /**
     * 取与 calling uid 匹配的 Context（PoC 实证的唯一可用形态）。
     *
     * 策略按成本递增：Shizuku 注入的 Context（其进程即 shell uid）→ 进程内已有
     * ActivityThread 的系统 Context → 自行 `systemMain()`（需 Looper，PoC 同路）。
     */
    private fun hiddenShellContext(): android.content.Context? {
        // 隐藏 API 豁免（ActivityThread 反射用；失败不致命——注入 Context 路径不需要）。
        // guardrail r1 Q-12：豁免收窄到 ActivityThread（不做进程级 "L" 全量豁免）。
        runCatching {
            val vmClass = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmClass.getMethod("getRuntime").invoke(null)
            vmClass.getMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime, arrayOf("Landroid/app/ActivityThread;"))
        }
        runCatching {
            context?.createPackageContext(HIDDEN_SHELL_PACKAGE, 0)?.let { return it }
        }
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }.invoke(null)
            if (current != null) {
                val sys = at.getDeclaredMethod("getSystemContext")
                    .apply { isAccessible = true }.invoke(current) as? android.content.Context
                sys?.createPackageContext(HIDDEN_SHELL_PACKAGE, 0)?.let { return it }
            }
        }
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
            android.util.Log.w(
                "ShizukuUserService",
                "隐藏屏 Context：注入 Context 与 currentActivityThread 均不可用，尝试 systemMain（高风险兼容路径）"
            )
            val thread = at.getDeclaredMethod("systemMain").apply { isAccessible = true }.invoke(null)
            val sys = at.getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }.invoke(thread) as? android.content.Context
            sys?.createPackageContext(HIDDEN_SHELL_PACKAGE, 0)?.let { return it }
        }
        return null
    }

    /**
     * ImageReader 帧监听：保持 Surface 队列流动 + 缓存最新帧供 [captureHiddenFramePng] 取用。
     * 拆卸期（hiddenReader==null）丢弃残帧并回收，避免写已释放对象。
     */
    private val hiddenDrainListener =
        android.media.ImageReader.OnImageAvailableListener { reader ->
            try {
                val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
                val bitmap = try {
                    hiddenImageToBitmap(image)
                } finally {
                    image.close()
                }
                if (bitmap != null) {
                    var old: android.graphics.Bitmap? = null
                    var dropped = false
                    synchronized(hiddenLock) {
                        if (hiddenReader == null) {
                            dropped = true
                        } else {
                            old = hiddenFrame
                            hiddenFrame = bitmap
                        }
                    }
                    if (dropped) bitmap.recycle() else old?.recycle()
                }
            } catch (_: Throwable) {
                // 拆卸竞态/编码异常：静默丢弃（不可让监听线程崩溃）
            }
        }

    /** Image → Bitmap（处理 rowStride 行尾 padding，同 VirtualDisplayManager）。 */
    private fun hiddenImageToBitmap(image: android.media.Image): android.graphics.Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        if (pixelStride != 4) return null
        return if (rowStride == w * 4) {
            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } else {
            val strideWidth = rowStride / pixelStride
            val padded = android.graphics.Bitmap.createBitmap(strideWidth, h, android.graphics.Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val cropped = android.graphics.Bitmap.createBitmap(padded, 0, 0, w, h)
            padded.recycle()
            cropped
        }
    }

    private companion object {
        /** PoC 实证：shell uid 对应的包上下文（packageName must match calling uid）。 */
        const val HIDDEN_SHELL_PACKAGE = "com.android.shell"
        const val HIDDEN_MAX_IMAGES = 2
        /** Binder 单次事务上限 1MB；超过即诚实返回无帧（正常 UI 帧远小于此）。 */
        const val HIDDEN_MAX_FRAME_BYTES = 900_000
        /** 建屏尺寸上界（guardrail r1 Q-11：AIDL 契约面防 shell 进程 OOM）。 */
        const val HIDDEN_MAX_DIMENSION = 4096
        const val HIDDEN_MAX_PIXELS = 4_000_000L
    }
}
