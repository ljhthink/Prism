package io.prism.qq

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.prism.phonecontrol.bg.ShizukuShell

/**
 * 通过 Termux / ZeroTermux 的 `RUN_COMMAND` 服务托管本机协议端（2026-11-01；US-3128）。
 *
 * ## 它解决什么
 *
 * 用户当前要自己开 ZeroTermux、切容器、敲 `termux-wake-lock` 和 `bash run2.sh`。
 * 本类让 Prism **点一下就完成**这些动作，用户不再接触终端。
 *
 * ## 三条硬前置（缺一即静默失效，缺一不可）
 *
 * 1. 本应用声明 `com.termux.permission.RUN_COMMAND` **且用户手动授予**
 *    （2026-11-01 真机实测：`prot=dangerous`，`pm grant io.prism …` 被 shell uid 2000 拒绝
 *    → **无法由 Shizuku 代授**）；
 * 2. Termux 侧 `~/.termux/termux.properties` 里 `allow-external-apps=true`
 *    （Termux 私有目录 `chmod 700`、`run-as` 被拒 → **读不到也写不了**，只能用探针反推）；
 * 3. targetSdk≥30 需 `<queries><package android:name="com.termux"/></queries>`
 *    （已在 AndroidManifest 声明）。
 *
 * ## 为什么优先经 Shizuku 发 `am startservice`
 *
 * Android 8+ 禁止**后台应用**启动普通服务（`startService` 抛 `IllegalStateException`），
 * 而改用 `startForegroundService` 又要求服务在 5s 内 `startForeground` —— Termux 的
 * `RunCommandService` 并不这么做，会触发 `ForegroundServiceDidNotStartInTimeException`
 * **把 Termux 自己搞崩**。由 shell 身份（Shizuku）发起 `am startservice` 不受后台启动限制，
 * 是唯一既可靠又不误伤 Termux 的路径。
 *
 * ## 安全边界
 *
 * 命令全部由**常量**拼装：脚本路径必须是 `/data/local/tmp/` 下已部署的白名单脚本，
 * 参数只允许 `[A-Za-z0-9_.:/-]`，不接受任何用户输入。
 */
object QqTermuxBridge {

    private const val TAG = "QqTermuxBridge"

    /** Termux / ZeroTermux 的包名（ZeroTermux 的 applicationId 同样是 `com.termux`）。 */
    const val TERMUX_PACKAGE = "com.termux"

    /** Termux 侧 `RUN_COMMAND` 权限名（用户手动授予）。 */
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    // ---- RUN_COMMAND 协议常量（Termux 官方 wiki） ----
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"

    /** Termux 内 bash 的绝对路径（`$PREFIX/bin/bash`）。 */
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"

    /** Termux 的 HOME（`RUN_COMMAND_WORKDIR` 必须是 Termux 可访问目录）。 */
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    /** 会话动作：0=不切换（后台跑完即结束）。 */
    private const val SESSION_ACTION_NONE = "0"

    // ==================== 状态探测 ====================

    /** Termux 是否已安装（`<queries>` 已声明，否则此处恒为 false）。 */
    fun isTermuxInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 本应用是否已获得 `RUN_COMMAND` 权限（用户手动授予）。 */
    fun hasRunCommandPermission(context: Context): Boolean = runCatching {
        context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 深链到本应用的系统详情页（用户在那里授予「附加权限」）。 */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Shizuku 不可用时的**降级路径**：弹系统确认框请求把 Prism 排除出电池优化。
     *
     * 为什么保留这条路径：Shizuku 未就绪时管家无法代写 doze 白名单（那是 shell 权限），
     * 但用户仍应能让 **Prism 自己**不被冻结——否则连「协议端掉线」的告警都发不出去，
     * 等于把 M2 一起废掉。系统对 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 会弹一次确认框，
     * 用户点「允许」即可，不需要去找隐藏设置。
     *
     * @return true=已发出请求（不代表用户已同意，需下次 `repairPowerPolicy` 回读确认）
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val appContext = context.applicationContext
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return false
        if (pm.isIgnoringBatteryOptimizations(appContext.packageName)) return true
        return runCatching {
            appContext.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.onFailure { e ->
            Log.w(TAG, "请求电池优化豁免失败：${e::class.simpleName}")
        }.getOrDefault(false)
    }

    /** 本应用当前是否已被排除出电池优化（系统真值，非我们的记账）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }.getOrDefault(false)

    /**
     * 端到端可用性判定（**不发起任何命令**）。
     *
     * ## 2026-11-02 修订：Shizuku 不再是核心路径的必需项
     *
     * 真机实证推翻了原设计——Termux 的 `RUN_COMMAND` 权限由**调用方 uid**承担，
     * shell（Shizuku）同样被拒且无法申请该权限。因此命令只能由 Prism 自己发，
     * Shizuku 只剩两个**可选增强**：
     * 1. 把脚本部署到 `/data/local/tmp`（无 Shizuku 时脚本必须已由 adb/用户预先放好）；
     * 2. 读取 `/sdcard` 上的状态/日志文件、写电池白名单。
     *
     * **调用方必须在后台线程调用**（H-3：`SCRIPTS_NOT_DEPLOYED` 那步走 Shizuku
     * `test -f`，冷绑定可能阻塞数秒）。
     *
     * @return 缺什么就返回什么，顺序即用户应先做的一步
     */
    fun readiness(context: Context): Readiness = when {
        !isTermuxInstalled(context) -> Readiness.TERMUX_MISSING
        !hasRunCommandPermission(context) -> Readiness.PERMISSION_MISSING
        // 只有 Shizuku 就绪时才可能"自动部署脚本"；未就绪不代表不可用（脚本可能已预置）
        ShizukuShell.isReady() && !QqProtocolScripts.areDeployed() ->
            Readiness.SCRIPTS_NOT_DEPLOYED
        else -> Readiness.READY
    }

    /** 就绪度（用于设置页给出**一步**指引，而不是一大段话）。 */
    enum class Readiness {
        READY,
        TERMUX_MISSING,
        PERMISSION_MISSING,

        /**
         * Shizuku 未激活。**已不再阻断核心功能**（2026-11-02 修订）：
         * 仅影响「自动部署脚本 / 读取日志与状态 / 修电池白名单」三项增强。
         */
        SHIZUKU_NOT_READY,
        SCRIPTS_NOT_DEPLOYED,

        /**
         * 权限与脚本都就位，但**探针没被 Termux 接受**——
         * 最常见原因就是 Termux 侧 `allow-external-apps=true` 没开
         * （Termux 私有目录 `chmod 700`、`run-as` 被拒，Prism 读不到也写不了，只能用探针反推）。
         *
         * 2026-11-01 验收 AC-1：这个状态此前完全没有出口（探针 `probe()` 全仓无调用方），
         * 导致用户只看到"启动未成功"却不知道差哪一步。
         */
        EXTERNAL_APPS_BLOCKED
    }

    /** 可读说明（已脱敏；不含任何路径凭据）。 */
    fun describeReadiness(readiness: Readiness): String = when (readiness) {
        Readiness.READY -> "协议端管家已就绪"
        Readiness.TERMUX_MISSING ->
            "未检测到 ZeroTermux/Termux。本机模式需要它承载协议端：请先安装一次（仅首次）"
        Readiness.PERMISSION_MISSING ->
            "还差一步：在系统的「Prism → 权限 → 附加权限」里允许「在 Termux 环境中运行命令」"
        Readiness.SHIZUKU_NOT_READY ->
            "Shizuku 未激活（不影响启动协议端，只影响自动部署脚本、读取日志、修电池白名单）"
        Readiness.SCRIPTS_NOT_DEPLOYED ->
            "脚本尚未部署到手机：点「一键修复」会自动部署（需要 Shizuku）"
        Readiness.EXTERNAL_APPS_BLOCKED ->
            "最后一步：打开 ZeroTermux，粘贴并回车执行这行命令（只需一次）：" +
                "echo 'allow-external-apps=true' >> ~/.termux/termux.properties"
    }

    // ==================== 动作 ====================

    /**
     * 启动协议端（`bash /data/local/tmp/run2.sh`）。
     *
     * 脚本自身已内置「单实例 + 快速登录 + 日志镜像到 /sdcard + 不自动重启（防自踢）」，
     * 因此重复调用是**幂等**的（第二个实例检测到既有 NapCat 会自行退出）。
     */
    fun start(context: Context): Boolean = run(context, QqProtocolScripts.RUN_SCRIPT)

    /**
     * 仅当**没有在跑**时才启动（供手动"启动"按钮使用，防自踢）。
     *
     * @param running 判据由调用方（[QqProtocolSupervisor.isProtocolPortListening]）提供
     * @return true=已提交启动；false=本来就在跑或多条件不满足
     */
    fun startIfNotRunning(context: Context, running: Boolean): Boolean =
        if (running) false else start(context)

    /**
     * 强制重启（用户明确要"重启"时用）：先跑停止脚本，再启动。
     *
     * 注意：这会**踢掉当前在线账号**（run2-inner 本身也会 pkill），
     * UI 必须在按钮上写明后果。
     *
     * **返回值只反映"启动是否被提交"**（2026-11-01 审查 N-1）：
     * 原实现 `run(STOP) || run(RUN)` 在「停止成功但启动失败」时返回 true，
     * 会让 UI 显示"已提交重启"而实际协议端已被杀且未拉起——最坏形态（用户以为在线）。
     */
    fun restart(context: Context): Boolean {
        val stopped = run(context, QqProtocolScripts.STOP_SCRIPT)
        if (!stopped) Log.w(TAG, "重启前停止脚本未成功，仍尝试启动")
        return run(context, QqProtocolScripts.RUN_SCRIPT)
    }

    /** 跑一次健康检查（`bash verify.sh`；结论落到 `/sdcard`，由管家读取）。 */
    fun verify(context: Context): Boolean = run(context, QqProtocolScripts.VERIFY_SCRIPT)

    /** 停止协议端（杀容器内 qq/napcat/Xvfb，不动 Termux 本身）。 */
    fun stop(context: Context): Boolean = run(context, QqProtocolScripts.STOP_SCRIPT)

    /** 发一条探针（`echo`），用于反推 Termux 侧 `allow-external-apps` 是否开启。 */
    fun probe(context: Context): Boolean = run(context, QqProtocolScripts.PROBE_SCRIPT)

    /**
     * **真实闭环探针**（2026-11-02，修复"提交成功≠执行成功"的误报）。
     *
     * ## 为什么必须做这一步
     *
     * 真机实测：`startService` 正常返回（无异常），但 Termux 日志是
     * ```
     * E/Termux.RunCommandService: Processing plugin execution error for: Prism
     * ```
     * ——`allow-external-apps=false` 时 Termux **接收但拒绝执行**命令，
     * 而调用方从 API 层面**无法感知**。我先前把"Intent 没抛异常"当作"成功"，
     * 结果 `probe=true` 而协议端根本没起来（与"adb 能力≠App 能力"是同一类错误）。
     *
     * ## 做法
     *
     * 探针脚本把当前 epoch 秒写进 `/sdcard/napcat-probe.txt`；提交后由
     * [ShizukuShell.readTextFile] 回读，**新鲜才算成功**。
     * 不用 Intent 返回值、不用 Termux 的日志——只看副作用是否真的发生。
     *
     * @return true=命令确实被执行（探针文件在 60 秒内被更新）
     */
    suspend fun probeVerified(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        // 先删旧文件，避免把"上次残留"当成这次成功
        val before = readProbeStamp()
        if (!probe(context)) return false
        // 轮询最多 ~6 秒：Termux 唤起 + 脚本执行通常 <2s
        repeat(6) {
            kotlinx.coroutines.delay(1_000L)
            val stamp = readProbeStamp()
            if (stamp != null && stamp != before && (nowMs / 1000 - stamp) <= 60) return true
        }
        Log.w(TAG, "探针文件未更新：Termux 侧可能未开启 allow-external-apps")
        return false
    }

    /** 读探针时间戳（epoch 秒）；不可读/不存在返回 null。 */
    private suspend fun readProbeStamp(): Long? {
        val text = runCatching {
            ShizukuShell.readTextFile(QqProtocolScripts.PROBE_STAMP_FILE)
        }.getOrNull() ?: return null
        return text.trim().toLongOrNull()
    }

    /**
     * 执行一条**已部署的白名单脚本**。
     *
     * ## 为什么必须由 Prism 自己发 Intent（2026-11-02 真机实证纠正）
     *
     * Termux 的 `RunCommandService` 声明了 `android:permission="com.termux.permission.RUN_COMMAND"`，
     * 该权限由**调用方 uid**承担。真机实测：
     * - `adb shell am startservice …`（uid 2000）→ `Error: Requires permission …`；
     * - `pm grant com.android.shell com.termux.permission.RUN_COMMAND` → `has not requested permission`。
     *
     * 即 **Shizuku（shell 身份）根本发不出去这条命令**——我原先"用 Shizuku 代发即可绕过
     * 后台限制"的设计是错的（ADR-070 已据此修订）。唯一合法调用方是 Prism 自己，
     * 且必须由用户手动授予该权限。
     *
     * ## 后台限制
     *
     * Android 8+ 禁止后台应用 `startService`。Prism 在持有前台服务时属
     * foreground-service 状态，可以正常调用；若无前台服务而被拒
     * （`IllegalStateException` / `ForegroundServiceStartNotAllowedException`），
     * 这里会如实记录并返回 false，由上层提示用户"点一下按钮"。
     *
     * @return true=Intent 已交给 Termux（**不代表脚本内部成功**）
     */
    private fun run(context: Context, scriptPath: String): Boolean {
        if (!QqProtocolScripts.isDeployed(scriptPath)) {
            Log.w(TAG, "拒绝执行未部署的脚本：${scriptPath.substringAfterLast('/')}")
            return false
        }
        val appContext = context.applicationContext
        if (!isTermuxInstalled(appContext)) {
            Log.w(TAG, "Termux 未安装，无法提交命令")
            return false
        }
        if (!hasRunCommandPermission(appContext)) {
            Log.w(TAG, "缺少 com.termux.permission.RUN_COMMAND 授权（需用户在系统设置里手动授予）")
            return false
        }
        val intent = android.content.Intent(ACTION_RUN_COMMAND).apply {
            component = android.content.ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf(scriptPath))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_SESSION_ACTION, SESSION_ACTION_NONE)
            putExtra(EXTRA_COMMAND_LABEL, "Prism")
        }
        return runCatching {
            appContext.startService(intent)
            true
        }.onFailure { e ->
            // 后台启动限制 / 权限撤销 / Termux 服务未启用，都要留痕，不能静默
            Log.w(TAG, "提交 RUN_COMMAND 失败：${e::class.simpleName} ${e.message?.take(80)}")
        }.getOrDefault(false)
    }
}

/**
 * 协议端脚本的**部署约定**（2026-11-01；US-3128）。
 *
 * 脚本的**唯一事实来源**是仓库 `tools/napcat-on-phone/`；构建期同步到
 * `app/src/main/assets/napcat/`，运行期由 [QqProtocolSupervisor] 经 Shizuku 写入 [DEPLOY_DIR]。
 *
 * 为什么部署到 `/data/local/tmp`：Termux（另一个 uid）必须能**按绝对路径执行**脚本，
 * 而 `/data/data/io.prism` 对 Termux 不可读；`/data/local/tmp` 是 shell 可写、
 * Termux 可读的标准路径（2026-11-01 实测 `drwxrwx--x shell:shell`，既有脚本就在那里）。
 */
object QqProtocolScripts {

    /** 部署目录（shell 可写、Termux 可读）。 */
    const val DEPLOY_DIR = "/data/local/tmp"

    /** 从 assets 同步的三个脚本（顺序即部署顺序；`run2-inner.sh` 由 `run2.sh` 调用）。 */
    val ASSET_SCRIPTS = listOf("run2.sh", "run2-inner.sh", "verify.sh")

    const val RUN_SCRIPT = "$DEPLOY_DIR/run2.sh"
    const val VERIFY_SCRIPT = "$DEPLOY_DIR/verify.sh"

    /** 停止脚本（Prism 生成，不来自 assets）。 */
    const val STOP_SCRIPT = "$DEPLOY_DIR/prism-stop.sh"

    /** 探针脚本（Prism 生成；内容只有一行 `echo prism-probe`）。 */
    const val PROBE_SCRIPT = "$DEPLOY_DIR/prism-probe.sh"

    /**
     * 部署版本标记（一行 `<脚本名>:<sha256>`），用于判断"手机上的脚本是否已过期"。
     *
     * **为什么需要**：原实现"文件已存在就跳过"，导致仓库里修好的脚本**永远到不了手机**
     * （2026-11-01 审查 H3）——升级 App 后行为不更新，排查时也看不出线上跑的是哪一版。
     */
    const val MARKER_FILE = "$DEPLOY_DIR/prism-scripts.sha"

    /** `verify.sh` 写的机器可读结论文件（供管家判据闭环，避免只靠人眼）。 */
    const val VERIFY_RESULT_FILE = "/sdcard/napcat-verify.txt"

    /** 探针回执文件（写 epoch 秒；证明「命令真的被执行了」而不只是「Intent 被接收」）。 */
    const val PROBE_STAMP_FILE = "/sdcard/napcat-probe.txt"

    /** 停止脚本内容：杀容器内进程，**不动 Termux 本身**（避免殃及用户其它会话）。 */
    const val STOP_SCRIPT_BODY = """#!/data/data/com.termux/files/usr/bin/bash
pkill -f 'NapCat' 2>/dev/null
pkill -f 'qq --no-sandbox' 2>/dev/null
pkill -f 'Xvfb' 2>/dev/null
echo stopped
"""

    /**
     * 探针脚本内容：**写回执文件**，而不是仅 `echo`。
     *
     * 2026-11-02 真机：`allow-external-apps=false` 时 Termux 收下命令但拒绝执行，
     * 调用方 API 层面无异常 → 只看"提交是否成功"会误报。写文件后由 Prism 回读，
     * 才能证明"命令真的跑起来了"。
     */
    const val PROBE_SCRIPT_BODY = """#!/data/data/com.termux/files/usr/bin/bash
date +%s > /sdcard/napcat-probe.txt 2>/dev/null
echo prism-probe
"""

    /** 全部允许执行的脚本绝对路径（白名单；顺序无关）。标记文件不是脚本，不在此列。 */
    fun allowedPaths(): Set<String> =
        (ASSET_SCRIPTS.map { "$DEPLOY_DIR/$it" } + STOP_SCRIPT + PROBE_SCRIPT).toSet()

    /** 该路径是否属于白名单。 */
    fun isDeployed(path: String): Boolean = path in allowedPaths()

    /** 部署路径（assets 名 → 绝对路径）。 */
    fun deployedPath(name: String): String = "$DEPLOY_DIR/$name"

    /**
     * 三个生产脚本是否都已在手机上（**需 Shizuku**：`/data/local/tmp` 对普通应用
     * 只有 `--x` 没有 `r`，`File.exists()` 在这里恒为 false，必须由 shell 判定）。
     */
    fun areDeployed(): Boolean {
        if (!ShizukuShell.isReady()) return false
        return ASSET_SCRIPTS.all { ShizukuShell.fileExists(deployedPath(it)) }
    }
}
