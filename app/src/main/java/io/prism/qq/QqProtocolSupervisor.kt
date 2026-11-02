package io.prism.qq

import android.content.Context
import android.util.Log
import io.prism.phonecontrol.bg.ShizukuShell
import kotlinx.coroutines.CancellationException

/**
 * 协议端**管家**（2026-11-01；US-3128）：把「探测 → 告警 → 自动拉起 → 电源策略核验」串成一条链。
 *
 * ## 它不做的事（必须写清楚，否则会被误当成保活）
 *
 * - **不能阻止 HyperOS 杀进程**：`AutoPowerKill` / `SwipeUpClean` / `OneKeyClean` 无 API、
 *   无文档（[dontkillmyapp](https://dontkillmyapp.com/xiaomi)），任何 App 都拦不住。
 * - **不能修复账号风控**：`serverErrorCode:168`「账号近期存在安全风险」只能用户扫码/按提示恢复。
 *
 * 它能做的是：**把"静默离线"变成"几分钟内被发现 + 自动尝试恢复 + 失败必通知"**。
 *
 * ## 与既有代码的关系
 *
 * - **不新增探测源**（AC-7）：输入永远是 [PrismApplication] 既有的 60s
 *   `QqProtocolHealth.probe()` 结果，本类只消费。
 * - **不碰 OneBot / 无障碍契约**：只在"探测说不可达"时追加一次拉起动作与一次通知。
 * - 通知走 [QqProtocolAlertNotifier]（复用既有渠道，不新建）。
 */
class QqProtocolSupervisor(
    context: Context,
    private val repository: QqRuntimeStateRepository
) {
    private val appContext = context.applicationContext

    /** 同一进程内防止 60s 循环与手动触发并发拉起（拉起本身会争抢 Termux 会话）。 */
    private val launchLock = Any()

    /**
     * 处理一次探测结果：推进告警策略、（必要时）通知、并在本机模式不可达时尝试自动拉起。
     *
     * @param state 探测结果（`ONLINE` / `QQ_OFFLINE` / `UNREACHABLE`）
     * @param detail 探测给出的可读说明（已脱敏）
     * @param nowMs 探测时刻
     * @param enabled QQ 小号助手总开关
     * @param loopback 协议端地址是否回环（本机模式）
     * @return 本次决策产生的动作（便于日志与真机排障；不参与 UI）
     */
    suspend fun onProbe(
        state: String,
        detail: String,
        nowMs: Long,
        enabled: Boolean,
        loopback: Boolean
    ): Outcome {
        // ---- 1. 告警策略（纯函数，原子读写） ----
        val decision = repository.advanceProtocolAlert(
            probe = state,
            nowMs = nowMs,
            enabled = enabled,
            loopback = loopback,
            detail = detail
        )

        // ---- 2. 通知（副作用） ----
        when (val action = decision.action) {
            is QqProtocolAlertPolicy.Action.NotifyDown -> {
                Log.i(TAG, "协议端失联告警：${action.kind}（since=${action.downSinceMs}）")
                QqProtocolAlertNotifier.notifyDown(
                    appContext,
                    kind = action.kind,
                    detail = action.detail,
                    loopback = loopback
                )
            }
            is QqProtocolAlertPolicy.Action.NotifyRecovered -> {
                Log.i(TAG, "协议端已恢复：${action.downKind} 持续 ${action.downDurationMs}ms")
                QqProtocolAlertNotifier.notifyRecovered(
                    appContext,
                    downKind = action.downKind,
                    downDurationMs = action.downDurationMs
                )
            }
            QqProtocolAlertPolicy.Action.None -> Unit
        }

        // ---- 3. 自动拉起（仅本机模式 + 不可达 + 总开关开） ----
        if (state != QqProtocolAlertPolicy.PROBE_UNREACHABLE || !enabled || !loopback) {
            return Outcome(decision.action, restart = RestartOutcome.NOT_ATTEMPTED)
        }
        val restart = maybeAutoRestart(nowMs)
        return Outcome(decision.action, restart)
    }

    /** 一次 `onProbe` 的完整结果（日志/测试用）。 */
    data class Outcome(
        val alert: QqProtocolAlertPolicy.Action,
        val restart: RestartOutcome
    )

    /** 自动拉起结果。 */
    sealed interface RestartOutcome {
        data object NOT_ATTEMPTED : RestartOutcome
        data class Waiting(val waitMs: Long) : RestartOutcome
        data object GaveUp : RestartOutcome
        data class Submitted(val reason: String) : RestartOutcome
        data class Failed(val reason: String) : RestartOutcome
    }

    /**
     * 按 [QqProtocolRestartPolicy] 决定是否拉起，并提交命令。
     *
     * **自踢防护（2026-11-01 审查 H5 修复）**：`alreadyRunning` **必须真的判**——
     * 原实现硬编码 false，等于让 `QqProtocolRestartPolicy` 的防自踢契约在生产路径永不生效，
     * ADR-069 踩过的"自动重启互踢账号"形态会原样复现。
     * 判据：状态文件里 `onebot3000` > 0（协议端自己每 10 秒写一次，代理进程存活）。
     */
    private suspend fun maybeAutoRestart(nowMs: Long): RestartOutcome {
        val state = repository.get()
        val alreadyRunning = isProtocolPortListening()
        val decision = QqProtocolRestartPolicy.decide(
            nowMs = nowMs,
            state = QqProtocolRestartPolicy.State(
                windowStartMs = state.protocolRestartWindowStartMs,
                attempts = state.protocolRestartAttempts,
                lastRestartAtMs = state.protocolLastRestartAtMs
            ),
            alreadyRunning = alreadyRunning,
            enabled = true
        )
        if (alreadyRunning) {
            Log.i(TAG, "状态文件显示 3000 已在监听，判定协议端仍在运行，不重复拉起（防自踢）")
        }
        return when (decision) {
            is QqProtocolRestartPolicy.Decision.Wait -> RestartOutcome.Waiting(decision.waitMs)
            QqProtocolRestartPolicy.Decision.GiveUp -> RestartOutcome.GaveUp

            is QqProtocolRestartPolicy.Decision.Allow -> {
                // 2026-11-01 验收 D-1（必修）：**失败也必须记账**。
                // 原实现只有成功才 `recordProtocolRestart`，于是「部署失败 / RUN_COMMAND 被拒」
                // （正是 allow-external-apps 未开时的常见状态）会让 attempts 恒为 0 →
                // 每分钟无限重试、永不给 GiveUp、也永远不告警，还伴生每分钟一次 Shizuku 调用。
                // 现在：无论提交成功与否，都先落账；用尽 3 次后策略自然转为 GiveUp，交给告警。
                val deployed = deployScripts()
                val submitted = if (!deployed) {
                    false
                } else {
                    // 提交动作本身不是 suspend（只是 startService），可以放在锁内保证互斥
                    synchronized(launchLock) { QqTermuxBridge.start(appContext) }
                }
                repository.recordProtocolRestart(
                    windowStartMs = decision.state.windowStartMs,
                    attempts = decision.state.attempts,
                    lastRestartAtMs = decision.state.lastRestartAtMs
                )
                if (submitted) {
                    Log.i(TAG, "已提交协议端拉起：${decision.reason}")
                    RestartOutcome.Submitted(decision.reason)
                } else {
                    val why = if (!deployed) {
                        "脚本部署失败（需要 Shizuku + /data/local/tmp 可写）"
                    } else {
                        "RUN_COMMAND 提交失败（检查 RUN_COMMAND 授权与 allow-external-apps）"
                    }
                    Log.w(TAG, "拉起失败（已计入重试预算）：$why")
                    RestartOutcome.Failed(why)
                }
            }
        }
    }

    /**
     * 状态文件里 `onebot3000 > 0` **且新鲜**（ts 在 [STATUS_FRESH_MS] 内）才算"仍在运行"。
     *
     * **2026-11-01 审查 C1（Critical）**：原实现只看 `onebot3000>0`，
     * 而进程被 SIGKILL 时脚本没有机会清空状态文件——文件会永久停在 `onebot3000=1`，
     * 于是 `alreadyRunning=true` → 重启策略直接 `GiveUp`，**自动拉起从第一次探测起永不执行**，
     * 恰好是本功能要覆盖的场景。
     *
     * 修法：`run2-inner.sh` 每行状态写 `ts=<epoch 秒>`；这里要求 `now - ts <= 30s`
     * （脚本每 10 秒刷新一次，留 3 倍余量）。**文件无 ts**（旧版脚本）一律视为陈旧。
     */
    suspend fun isProtocolPortListening(): Boolean {
        val text = readStatusFile() ?: return false
        val ts = parseStatusTimestampSec(text)
        if (ts <= 0L) return false
        val ageMs = System.currentTimeMillis() - ts * 1000L
        if (ageMs > STATUS_FRESH_MS) {
            Log.i(TAG, "状态文件已陈旧（${ageMs / 1000}s 未更新），判定协议端已停止")
            return false
        }
        return parseOneBot3000(text) > 0
    }

    /**
     * 把 assets 里的生产脚本部署到 `/data/local/tmp`（幂等，但**会更新内容**）。
     *
     * **为什么不能"已存在就跳过"**（2026-11-01 审查 H3）：那样仓库里修好的脚本
     * **永远到不了手机**，与"tools/ 是唯一事实来源"直接矛盾——升级 App 后行为不更新，
     * 且排查时会对着仓库看不出线上跑的是哪一版。
     *
     * 修法：写一份 SHA-256 标记文件（`prism-scripts.sha`，一行 `name:sha256`），
     * 每次部署先读标记比对，不一致才重写。**标记不存在**（老版本/首次）→ 视为需重写。
     *
     * @return true=三个脚本都已在位
     */
    suspend fun deployScripts(): Boolean {
        if (!ShizukuShell.isReady()) return false
        val marker = readScriptMarker()
        val contents = mutableMapOf<String, String>()
        for (name in QqProtocolScripts.ASSET_SCRIPTS) {
            val text = readAsset(name) ?: run {
                Log.w(TAG, "assets 缺少脚本 $name")
                return false
            }
            contents[name] = text
            val path = QqProtocolScripts.deployedPath(name)
            val hash = sha256(text)
            val upToDate = marker[name] == hash && ShizukuShell.fileExists(path)
            if (upToDate) continue
            if (!ShizukuShell.writeShellFile(path, text)) {
                Log.w(TAG, "部署失败：$name")
                return false
            }
            Log.i(TAG, "脚本已部署/更新：$name（sha=${hash.take(8)}）")
        }
        // 两个 Prism 侧生成的脚本：**也走标记比对**（审查 M-5）。
        // 原来"存在即跳过"意味着将来改 STOP_SCRIPT_BODY 永远到不了手机——
        // 与 H3 修的是同一类问题，只是对象不同。
        deployGenerated(MARKER_KEY_STOP, QqProtocolScripts.STOP_SCRIPT, QqProtocolScripts.STOP_SCRIPT_BODY, marker, contents)
        deployGenerated(MARKER_KEY_PROBE, QqProtocolScripts.PROBE_SCRIPT, QqProtocolScripts.PROBE_SCRIPT_BODY, marker, contents)
        val allPresent = QqProtocolScripts.ASSET_SCRIPTS.all {
            ShizukuShell.fileExists(QqProtocolScripts.deployedPath(it))
        }
        if (allPresent) {
            writeScriptMarker(contents.mapValues { (_, v) -> sha256(v) })
        }
        return allPresent
    }

    /**
     * 部署一个 Prism 侧生成的脚本（内容固定但**会随版本变化**）。
     *
     * 与 assets 脚本同一套标记机制：hash 不一致才重写。
     */
    private fun deployGenerated(
        markerKey: String,
        path: String,
        body: String,
        marker: Map<String, String>,
        contents: MutableMap<String, String>
    ) {
        val hash = sha256(body)
        contents[markerKey] = body
        if (marker[markerKey] == hash && ShizukuShell.fileExists(path)) return
        if (!ShizukuShell.writeShellFile(path, body)) {
            Log.w(TAG, "部署生成脚本失败：${path.substringAfterLast('/')}")
        }
    }

    private fun readAsset(name: String): String? = runCatching {
        appContext.assets.open("napcat/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    /** 读部署版本标记（`name:sha256` 每行一条）；不存在/读失败返回空表（视为需重写）。 */
    private fun readScriptMarker(): Map<String, String> {
        val raw = ShizukuShell.readFileTail(QqProtocolScripts.MARKER_FILE, 32) ?: return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val name = line.substring(0, idx).trim()
                val hash = line.substring(idx + 1).trim()
                if (name.isEmpty() || hash.length < 16) null else name to hash
            }
            .toMap()
    }

    private fun writeScriptMarker(hashes: Map<String, String>) {
        val body = hashes.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n"
        ShizukuShell.writeShellFile(QqProtocolScripts.MARKER_FILE, body)
    }

    /** 内容摘要（只用于"要不要重新部署"，非安全用途）。 */
    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 部署但**不吞掉取消**（M7：CancellationException 必须继续抛，否则协程取消语义被破坏）。 */
    private suspend fun deployScriptsQuietly(): Boolean = try {
        deployScripts()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "部署异常：${e::class.simpleName}")
        false
    }

    /**
     * 一键修复：电源策略（白名单 + 待机桶）→ 部署脚本 → **探针验证** → 拉起协议端 → 清空重启记账。
     *
     * 2026-11-01 验收 AC-1：探针 `probe()` 此前全仓无调用方，等于"allow-external-apps
     * 没开"这一最常见的失败原因没有任何出口（用户只看到"启动未成功"）。
     * 现在探针**纳入修复流程**，失败时 readiness 会给出 [QqTermuxBridge.Readiness.EXTERNAL_APPS_BLOCKED]。
     *
     * @return 每一步的结果（UI 逐行展示，失败不静默）
     */
    suspend fun repairAll(): RepairReport {
        val power = repairPowerPolicy()
        val deployed = deployScriptsQuietly()
        // 先用**闭环探针**验证「命令真的被执行」——不是只看 Intent 有没有抛异常。
        // 真机教训：allow-external-apps=false 时 startService 正常返回、Termux 内部拒绝执行。
        val probed = if (deployed) QqTermuxBridge.probeVerified(appContext) else false
        // D-1（验收必修）：手动路径同样要判"是否已经在跑"
        val alreadyRunning = isProtocolPortListening()
        val started = if (deployed && probed && !alreadyRunning) {
            QqTermuxBridge.start(appContext)
        } else {
            false
        }
        if (started) repository.clearProtocolRestart()
        val readiness = QqTermuxBridge.readiness(appContext).let { base ->
            // 探针被拒而其余条件都满足 → 直指 allow-external-apps（AC-1 第二步）
            if (deployed && !probed && (base == QqTermuxBridge.Readiness.READY ||
                    base == QqTermuxBridge.Readiness.SCRIPTS_NOT_DEPLOYED)
            ) {
                QqTermuxBridge.Readiness.EXTERNAL_APPS_BLOCKED
            } else {
                base
            }
        }
        return RepairReport(
            power = power,
            scriptsDeployed = deployed,
            probeAccepted = probed,
            started = started,
            alreadyRunning = alreadyRunning,
            readiness = readiness
        )
    }

    /** 一键修复的结果（UI 直接消费；每项都有可读文案）。 */
    data class RepairReport(
        val power: PowerRepairResult,
        val scriptsDeployed: Boolean,
        /** 探针命令是否被 Termux 接受（false ≈ `allow-external-apps` 未开）。 */
        val probeAccepted: Boolean = false,
        val started: Boolean,
        /** 修复时协议端本来就在跑（此时**不重启**，避免自踢）。 */
        val alreadyRunning: Boolean = false,
        val readiness: QqTermuxBridge.Readiness
    )

    /** 电源策略修复结果。 */
    data class PowerRepairResult(
        val attempted: Boolean,
        val whitelisted: Boolean,
        val bucket: Int?,
        val readFailed: Boolean,
        /** Prism 自身是否在白名单（不在则告警可能发不出去）。 */
        val prismWhitelisted: Boolean = false,
        /** 协议端（Termux）是否在白名单（不在则会被冻结）。 */
        val termuxWhitelisted: Boolean = false,
        /**
         * Termux 的 MIUI **自启动（可被唤醒）** 状态：`allow` / `ignore` / …；null=非 MIUI/不适用。
         *
         * 2026-11-02 真机根因：该项被拦时，Prism 发的 `startService` 会被 HyperOS
         * `WakePathChecker` **静默丢弃**（不抛异常），表现为"一键修复成功但协议端没起来"。
         */
        val autoStartMode: String? = null
    ) {
        /** 供 UI 直接显示的一行（**如实区分已生效/未生效/无法确认**）。 */
        fun describe(): String {
            val base = QqPowerPolicy.describe(whitelisted, bucket, readFailed)
            val autoStart = QqPowerPolicy.describeAutoStart(autoStartMode)
            val suffix = buildString {
                if (!readFailed && (!prismWhitelisted || !termuxWhitelisted)) {
                    val who = buildList {
                        if (!termuxWhitelisted) add("协议端")
                        if (!prismWhitelisted) add("Prism 自身")
                    }.joinToString("、")
                    append("（仍缺：").append(who).append("）")
                }
                if (autoStart != null) {
                    if (isNotEmpty()) append("；")
                    append(autoStart)
                }
            }
            return if (suffix.isEmpty()) base else "$base；$suffix"
        }
    }

    /**
     * 修复并**回读校验**电源策略（AC-2：未生效必须显示"未生效"，不能显示"已设置"）。
     *
     * 步骤：
     * 1. 读白名单 → 缺则加 → 再读校验；
     * 2. 读待机桶 → 不是 EXEMPTED 且白名单已生效时，补一次 `am set-standby-bucket`；
     * 3. 两步都**以回读结果为准**。
     */
    suspend fun repairPowerPolicy(): PowerRepairResult {
        if (!ShizukuShell.isReady()) {
            return PowerRepairResult(attempted = false, whitelisted = false, bucket = null, readFailed = true)
        }
        val firstDump = runCatching { ShizukuShell.deviceIdleWhitelistDump() }.getOrNull()
        if (firstDump == null) {
            return PowerRepairResult(attempted = true, whitelisted = false, bucket = null, readFailed = true)
        }
        var whitelistedSelf = QqPowerPolicy.isWhitelisted(firstDump, appContext.packageName)
        var whitelistedTermux = QqPowerPolicy.isWhitelisted(firstDump, QqPowerPolicy.TERMUX_PACKAGE)

        if (!whitelistedSelf) {
            runCatching { ShizukuShell.addDeviceIdleWhitelist(appContext.packageName) }
        }
        if (!whitelistedTermux) {
            runCatching { ShizukuShell.addDeviceIdleWhitelist(QqPowerPolicy.TERMUX_PACKAGE) }
        }

        // 回读（**判据永远是回读，不是命令退出码**）
        val dump = runCatching { ShizukuShell.deviceIdleWhitelistDump() }.getOrNull()
        if (dump != null) {
            whitelistedSelf = QqPowerPolicy.isWhitelisted(dump, appContext.packageName)
            whitelistedTermux = QqPowerPolicy.isWhitelisted(dump, QqPowerPolicy.TERMUX_PACKAGE)
        }
        var bucket = runCatching { ShizukuShell.standbyBucketOf(QqPowerPolicy.TERMUX_PACKAGE) }.getOrNull()
        // M4 修复：KDoc 说了要补写待机桶，原实现只读不写。这里按 needsRepair 真正补一次并回读。
        if (bucket != null && QqPowerPolicy.needsRepair(whitelistedTermux, bucket)) {
            runCatching { ShizukuShell.setStandbyBucket(QqPowerPolicy.TERMUX_PACKAGE, QqPowerPolicy.BUCKET_ACTIVE) }
            bucket = runCatching { ShizukuShell.standbyBucketOf(QqPowerPolicy.TERMUX_PACKAGE) }.getOrNull()
            Log.i(TAG, "待机桶不达标，已补写并回读：bucket=$bucket")
        }
        Log.i(
            TAG,
            "电源策略回读：prism=${if (whitelistedSelf) "在白名单" else "不在"} " +
                "termux=${if (whitelistedTermux) "在白名单" else "不在"} bucket=$bucket"
        )
        // 2026-11-02（真机根因）：MIUI/HyperOS 默认拦截「Termux 被其它应用唤醒」，
        // 于是 Prism 的 startService 被框架层静默丢弃。这里修 + 回读校验。
        val autoStartMode = repairTermuxAutoStart()
        return PowerRepairResult(
            attempted = true,
            // 2026-11-01 审查 M-3：**必须两项都在白名单**。
            // Termux 不在 → 协议端会被冻结；Prism 不在 → **告警本身发不出去**，
            // 等于把 M2 一起废掉。只回传 Termux 会让 UI 显示"已生效"而实际仍会静默。
            whitelisted = whitelistedTermux && whitelistedSelf,
            bucket = bucket,
            readFailed = dump == null,
            prismWhitelisted = whitelistedSelf,
            termuxWhitelisted = whitelistedTermux,
            autoStartMode = autoStartMode
        )
    }

    /**
     * 修复并回读 Termux 的 MIUI **自启动（可被唤醒）** op。
     *
     * @return 回读到的模式；非 MIUI 机型/读不到返回 null（不当作失败）
     */
    private suspend fun repairTermuxAutoStart(): String? {
        val before = runCatching {
            ShizukuShell.appOpsDump(QqPowerPolicy.TERMUX_PACKAGE)
        }.getOrNull()
        val modeBefore = QqPowerPolicy.parseMiuiAutoStartMode(before) ?: return null
        if (!QqPowerPolicy.needsAutoStartRepair(modeBefore)) return modeBefore

        runCatching {
            ShizukuShell.setAppOp(
                QqPowerPolicy.TERMUX_PACKAGE,
                QqPowerPolicy.MIUI_AUTO_START_OP,
                "allow"
            )
        }
        val after = runCatching {
            ShizukuShell.appOpsDump(QqPowerPolicy.TERMUX_PACKAGE)
        }.getOrNull()
        val modeAfter = QqPowerPolicy.parseMiuiAutoStartMode(after)
        Log.i(TAG, "Termux 自启动（可被唤醒）：$modeBefore → ${modeAfter ?: "读不到"}")
        return modeAfter
    }

    /** 读取协议端脚本写的状态文件（`/sdcard/napcat-status.txt`）；不可读返回 null。 */
    suspend fun readStatusFile(): String? = try {
        ShizukuShell.readTextFile(STATUS_FILE)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "读状态文件失败：${e::class.simpleName}")
        null
    }

    /**
     * 状态文件内容 → **可直接展示**的文本（L6：脱敏）。
     *
     * 状态文件里含 `run2-inner.sh` grep 出的关键日志行，可能带 QQ 号/昵称；
     * 设置页会把这段文本渲染到屏幕上，因此这里把长数字与昵称片段打码。
     */
    suspend fun readStatusForDisplay(): String? = readStatusFile()?.let { redactForDisplay(it) }

    /**
     * 读取 NapCat 日志尾部（用于设置页"查看日志"/诊断）；不可读返回 null。
     *
     * **H2 修复**：裁剪必须由 **shell 侧 `tail -n`** 完成，不能整文件 `cat` 回来——
     * 日志会持续增长，越过 Binder 事务上限后 `execWithOutput` 恒返回 null，
     * 表现为「扫码地址突然解析不到」的静默失效。
     */
    suspend fun readLogTail(maxLines: Int = 120): String? = try {
        ShizukuShell.readFileTail(LOG_FILE, maxLines)?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "读日志失败：${e::class.simpleName}")
        null
    }

    /** 读取 `verify.sh` 写的机器可读结论（`OK online=1 ws=1` 等）；不可读返回 null。 */
    suspend fun readVerifyResult(): String? = try {
        ShizukuShell.readTextFile(QqProtocolScripts.VERIFY_RESULT_FILE)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * 从 NapCat 日志里解析 WebUI 扫码地址（`WebUi User Panel Url: http://127.0.0.1:6099/webui?token=…`）。
     *
     * 这样用户**不用自己去翻 token**——10-31 事故后交接文档 Part A 的 A2 步骤里，
     * 用户正是卡在"去日志里找 token"这一步。
     *
     * **2026-11-01 审查 H-2**：WebUI 地址只在 NapCat 启动时打印**一次**，
     * 日志持续增长后会滑出尾部窗口，只读日志尾 400 行必然失效。
     * 因此优先读脚本单独维护的 [WEBUI_FILE]（`run2-inner.sh` 每 20 秒 `grep|tail -1` 落一份），
     * 读不到再退回扫日志尾部（兼容旧版脚本）。
     */
    suspend fun resolveWebUiUrl(): String? {
        val dedicated = try {
            ShizukuShell.readTextFile(WEBUI_FILE)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        dedicated?.let { line -> parseWebUiUrl(line)?.let { return it } }
        val log = readLogTail(maxLines = 400) ?: return null
        return parseWebUiUrl(log)
    }

    companion object {
        private const val TAG = "QqProtocolSupervisor"

        /** 协议端脚本写的状态文件（与 tools/napcat-on-phone/run2.sh 约定一致）。 */
        const val STATUS_FILE = "/sdcard/napcat-status.txt"

        /** 协议端日志镜像（与 run2.sh 约定一致）。 */
        const val LOG_FILE = "/sdcard/napcat.log"

        /** 单独的 WebUI 扫码地址文件（`run2-inner.sh` 每 20 秒刷新；审查 H-2）。 */
        const val WEBUI_FILE = "/sdcard/napcat-webui.txt"

        /** 标记文件里 Prism 生成脚本的键（不是 assets 里的文件名）。 */
        private const val MARKER_KEY_STOP = "prism-stop.sh"
        private const val MARKER_KEY_PROBE = "prism-probe.sh"

        /**
         * WebUI 地址识别（只匹配回环，避免把外网地址当扫码入口）。
         *
         * **H1 修复**：token 字符集必须认同 `._~+/=` 与 `-`——真机默认 token 就带点
         * （`tools/napcat-on-phone/verify.sh` 历史注释里的 `QQgiGKiNyi29k5.T`），
         * 原来只认 `[A-Za-z0-9]` 会把 token 截断成"看起来对、实际鉴权必失败"的地址。
         */
        private val WEBUI_REGEX = Regex(
            """https?://(?:127\.0\.0\.1|localhost|\[::1]):\d+/webui\?token=[A-Za-z0-9._~+/=-]+"""
        )

        /**
         * 从日志文本里取**最后一条** WebUI 地址（纯函数，可单测）。
         *
         * 取最后一条而不是第一条：NapCat 重启后 token 会变，日志里会有多条历史地址。
         */
        fun parseWebUiUrl(logText: String): String? =
            WEBUI_REGEX.findAll(logText).lastOrNull()?.value

        /**
         * 从状态文件解析 `onebot3000=N`（纯函数，可单测）。
         *
         * 状态文件格式（`run2-inner.sh` 每 10 秒重写一次）：
         * ```
         * tick=798 qq=3 onebot3000=0
         * （其后是若干行 grep 出的关键日志）
         * ```
         * @return 3000 端口监听数；解析失败返回 0（保守：视为未运行）
         */
        fun parseOneBot3000(statusText: String): Int {
            val match = Regex("""onebot3000=(\d+)""").find(statusText) ?: return 0
            return match.groupValues[1].toIntOrNull() ?: 0
        }

        /** 展示前的脱敏：5 位以上数字（QQ 号/UIN）打码。纯函数，可单测。 */
        fun redactForDisplay(text: String): String =
            Regex("""\d{5,}""").replace(text) { m ->
                val v = m.value
                v.take(2) + "*".repeat(v.length - 2)
            }

        /** 一次判定是否"确实在跑"（供设置页与重启策略共用同一口径）。 */
        fun isRunningStatus(statusText: String?): Boolean =
            statusText != null && parseOneBot3000(statusText) > 0

        /** 状态新鲜度上限：脚本每 10 秒刷新一次，留 3 倍余量（审查 C1）。 */
        const val STATUS_FRESH_MS = 30_000L

        /**
         * 解析状态行里的 `ts=<epoch 秒>`（纯函数，可单测）。
         *
         * **为什么必须有时间戳**（审查 C1，Critical）：进程被 SIGKILL 时脚本没有任何
         * 机会清空状态文件，文件会永久停在 `onebot3000=1`；只看这个值会导致
         * `alreadyRunning=true` → 重启策略直接放弃 → **自动拉起永不执行**。
         *
         * @return epoch 秒；缺失/非法返回 0（视为陈旧——宁可多拉一次也不静默不拉）
         */
        fun parseStatusTimestampSec(statusText: String): Long {
            val m = Regex("""\bts=(\d{9,13})""").find(statusText) ?: return 0L
            val raw = m.groupValues[1].toLongOrNull() ?: return 0L
            // 防御：13 位是毫秒，10 位是秒；统一成秒
            return if (raw > 100_000_000_000L) raw / 1000L else raw
        }

        /**
         * 状态是否「新鲜且在跑」（纯函数，可单测）。
         *
         * @param nowMs 当前时刻
         */
        fun isStatusFreshAndRunning(statusText: String?, nowMs: Long): Boolean {
            if (statusText == null) return false
            val ts = parseStatusTimestampSec(statusText)
            if (ts <= 0L) return false
            if (nowMs - ts * 1000L > STATUS_FRESH_MS) return false
            return parseOneBot3000(statusText) > 0
        }
    }
}
