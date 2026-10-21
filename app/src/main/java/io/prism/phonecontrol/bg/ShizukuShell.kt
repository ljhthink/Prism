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
     *  修复轮八：+execWithOutput（AIDL 新方法）→ 升 2。 */
    private const val USER_SERVICE_VERSION = 2

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
}
