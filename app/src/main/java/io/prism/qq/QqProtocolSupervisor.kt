package io.prism.qq

import android.content.Context
import android.util.Log
import io.prism.phonecontrol.bg.ShizukuShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

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
    private val repository: QqRuntimeStateRepository,
    /**
     * 协议端 HTTP 是否可达（2026-11-02 新增；guardrail R-4）。
     *
     * 判「是否在跑」只信状态文件有个致命前提：**设备上必须是新脚本**。
     * 旧脚本（80 行）探活恒写 `onebot3000=0`，于是 Prism 会一直判"没起来"并反复 `pkill` 重启——
     * 正是本轮要修的自踢循环。加一个独立的 HTTP 探针作为**兜底证据**：
     * 只要协议端能回 `get_status`，就说明它在跑，绝不再重启。
     *
     * 传 null 表示不具备该能力（JVM 单测/未装配），行为退化为旧口径。
     */
    private val httpProbe: (suspend () -> Boolean)? = null,
    /**
     * 账号在线态探测（2026-11-15 新增；ADR-073）。
     *
     * 一键修复必须能区分「进程没跑」与「进程在跑但账号离线（需扫码）」——
     * 后者点多少次修复都不会好，只能扫码。旧报告把两者混成"本来就在运行"。
     */
    private val healthProbe: (suspend () -> QqProtocolHealth.Status)? = null
) {
    private val appContext = context.applicationContext

    /** 同一进程内防止 60s 循环与手动触发并发拉起（拉起本身会争抢 Termux 会话）。 */
    private val launchLock = Any()

    /**
     * 拉起动作的**协程互斥**（2026-11-14；guardrail R-04）。
     *
     * `launchLock` 是 `synchronized`（不可挂起），只能保护"提交"这一步；
     * 而 `restartAndWait` 需要在 STOP 与 RUN 之间 `delay`，必须用挂起锁。
     */
    private val launchMutex = kotlinx.coroutines.sync.Mutex()

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
                Log.i(
                    TAG,
                    "协议端失联告警：${action.kind}（since=${action.downSinceMs}" +
                        (if (action.reminder) "，重提醒" else "") + "）"
                )
                QqProtocolAlertNotifier.notifyDown(
                    appContext,
                    kind = action.kind,
                    detail = action.detail,
                    loopback = loopback,
                    reminder = action.reminder
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
            // 2026-11-02（F-2 修正；guardrail R-09）：**只有真正在线**才清零重启预算。
            // 旧版条件写在这里是"非 UNREACHABLE 即清零"，而 QQ_OFFLINE 也是非 UNREACHABLE——
            // 账号离线时反复清零会让"1 小时最多 3 次"的上限彻底失效，变成无限重启。
            if (state == QqProtocolAlertPolicy.PROBE_ONLINE) {
                runCatching {
                    if (repository.get().protocolRestartAttempts > 0) {
                        repository.clearProtocolRestart()
                        Log.i(TAG, "协议端已恢复在线，重启预算清零")
                    }
                }
            }
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
        // 2026-11-18：**Termux 不活着时，状态文件不可信**（刚被 force-stop 时它仍新鲜，
        // 见 repairAll 内同款不变量说明）。否则自愈会因"状态文件说在跑"直接 GiveUp，
        // 而这正是本次连续 20 小时未自愈的形态之一。
        val termuxState = QqTermuxBridge.termuxProcessState()
        val alreadyRunning = if (termuxState == QqTermuxBridge.TermuxState.ALIVE) {
            isProtocolPortListening()
        } else if (termuxState == QqTermuxBridge.TermuxState.DEAD) {
            false
        } else {
            // UNKNOWN：查不出来时保守——沿用状态文件判定，避免把查询故障当成"已死"而狂重启。
            isProtocolPortListening()
        }
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
                //
                // 2026-11-18（真机 RCA）：**自愈前必须先解除 Termux 的 force-stop**。
                // HyperOS「锁屏后清理内存」会 `force-stop com.termux`（真机日志
                // `ProcessSceneCleaner: LockScreenClean: force-stop pkgName=com.termux`）。
                // force-stop 之后 RUN_COMMAND 的 `startService` 被 AMS **静默丢弃**：
                // 不抛异常、不执行。监督器于是每分钟记一条"状态文件已陈旧，判定协议端已停止"，
                // 却永远拉不起链——本次真机连续 20 小时未自愈、端口 3000 一直超时即此。
                //
                // 先留一条"Termux 当时是否活着"的日志，否则下次 RCA 又只能靠猜。
                val termuxAlive = QqTermuxBridge.isTermuxProcessAlive()
                if (!termuxAlive) {
                    Log.w(TAG, "Termux 进程不存在（疑似被系统 force-stop），尝试解除停止状态后自愈")
                    QqTermuxBridge.ensureUnstoppedAwait()
                } else if (alreadyRunning) {
                    // Termux 活着且状态文件新鲜 → 真的在跑，不重复拉起（防自踢）。
                    // Termux 不活着时 alreadyRunning 已在上面被强制判为 false
                    // （刚 force-stop 的状态文件仍"新鲜"，不可信）。
                    Log.i(TAG, "Termux 存活且状态文件新鲜，判定协议端仍在运行，不重复拉起（防自踢）")
                }
                val deploy = deployScriptsDetailed()
                val deployed = deploy.ok
                val submitted: Boolean = if (!deployed) {
                    false
                } else {
                    // 2026-11-14（guardrail R-03）：**自动路径同样必须让新脚本生效**。
                    // 旧实现只 `start()`，而运行中的 bash 不会回读脚本、新实例又被 flock 静默拒绝
                    // ⇒ 无人值守场景（用户不点「一键修复」）新逻辑永远到不了设备。
                    //
                    // guardrail R-04：`restartAndWait` 是 suspend（要等 Termux 侧写停止回执），
                    // 因此**不能**放在 synchronized 内，用协程 Mutex 串行化。
                    // 它内部已按 [QqTermuxBridge.StopOutcome] 三态记录日志；这里只关心"是否提交了启动"。
                    launchMutex.withLock {
                        if (deploy.changed) {
                            Log.i(TAG, "脚本内容已变更，重启整条链以让新逻辑生效")
                            QqTermuxBridge.restartAndWait(appContext)
                            true
                        } else {
                            QqTermuxBridge.start(appContext)
                        }
                    }
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
                    // 2026-11-15（guardrail R-04）：**只在最后一次尝试失败时才高优提醒**。
                    // 旧写 `attempts >= 1` 恒真（Allow 分支返回的已是自增后的值）→ 每次 Allow
                    // 失败都发一条 reminder 高优通知，而真正需要打扰的 GiveUp 分支反而完全静默。
                    // 现在：前 N-1 次用普通通知（不打扰），最后一次用重提醒。
                    if (decision.state.attempts >= QqProtocolRestartPolicy.MAX_ATTEMPTS) {
                        runCatching {
                            QqProtocolAlertNotifier.notifyRestartFailed(
                                appContext,
                                reason = why,
                                shizukuReady = ShizukuShell.isReady()
                            )
                        }.onFailure {
                            Log.w(TAG, "拉起失败通知发送失败（${it::class.simpleName}）")
                        }
                    }
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
        // 2026-11-02（ADR-071）：扫码态/启动中**绝不重启**。
        // 等待扫码时 3000 本就不监听，旧判据会把它当"没起来"→ pkill QQ → 用户正在扫的二维码作废，
        // 并且每次重启都要求重新扫码，直接把用户拖进"越修越掉"的循环。
        // 2026-11-14（guardrail R-05）：状态读取必须走**新鲜度校验**。
        // 直接用 `parseState(readStatusFile())` 会把 11-03 那种陈旧文件里的
        // `state=need_qr` 当成当前状态，从而永久拒绝重启/重启判断失真。
        val state = currentFreshState()
        val portUp = parseOneBot3000(text) > 0
        if (state == STATE_NEED_QR) {
            Log.i(TAG, "协议端状态=need_qr，判定正在等待扫码，不重复拉起")
            return true
        }
        if (state == STATE_STARTING) {
            // 2026-11-14 真机实证（重要）：`starting` 不能**无限期**当"在跑"。
            // 旧脚本探活恒 0 时会永远写 `starting`，于是 Prism 判"已在启动"→ 拒绝重启 →
            // 新脚本永远部署不上（本轮现场就撞到：deploy 成功但 alreadyRunning=true 未重启）。
            // 现在按 `since=` 判定：启动宽限期内才算"在跑"，超时即视为卡死、允许拉起。
            val stuckMs = startingStuckMs(text)
            if (stuckMs in 0..STARTING_GRACE_MS) {
                Log.i(TAG, "协议端状态=starting 且仅 ${stuckMs / 1000}s，判定仍在启动")
                return true
            }
            Log.i(TAG, "协议端状态=starting 已 ${stuckMs / 1000}s（超 ${STARTING_GRACE_MS / 1000}s），判定卡死")
        }
        if (portUp) return true
        // 状态文件说"没监听"，但可能跑的是恒写 0 的旧脚本 → 用 HTTP 探针复核（guardrail R-4）。
        // 探针只在"文件说没跑"时执行，不新增周期性网络探测源（AC-8 不变）。
        val probe = httpProbe ?: return false
        val reachable = runCatching { probe() }.getOrDefault(false)
        if (reachable) {
            Log.i(TAG, "状态文件显示 3000 未监听，但协议端 HTTP 可达（疑似旧脚本探活口径），判为在跑")
        }
        return reachable
    }

    /**
     * `state=starting` 已持续的毫秒数；解析不到返回 -1（视为未知，调用方按"未超时"保守处理）。
     *
     * 状态行形如 `ts=… tick=… qq=3 onebot3000=0 state=starting since=1794661850 …`。
     * 旧脚本没有 `since=` → 返回 -1，由调用方退化成"不知道多久了"，避免误杀正在启动的链路。
     */
    private fun startingStuckMs(statusText: String): Long {
        val since = Regex("""\bsince=(\d{9,13})""").find(statusText)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return -1L
        val sinceMs = if (since > 100_000_000_000L) since else since * 1000L
        return (System.currentTimeMillis() - sinceMs).coerceAtLeast(0L)
    }

    /**
     * 当前状态机取值，**仅在状态文件新鲜时**返回；陈旧/不可读返回空串。
     *
     * 2026-11-14（guardrail R-05）：把"新鲜度"与"状态解析"绑成一个入口，
     * 避免调用方各自 `parseState(readStatusFile())` 绕过 fresh 判定
     * （真机 11-03 的陈旧文件里还留着 `state=need_qr`，会让自动重启永久让路）。
     */
    private suspend fun currentFreshState(): String {
        val text = readStatusFile() ?: return ""
        val ts = parseStatusTimestampSec(text)
        if (ts <= 0L) return ""
        if (System.currentTimeMillis() - ts * 1000L > STATUS_FRESH_MS) return ""
        return parseState(text)
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
    /**
     * 脚本部署的**结果**（2026-11-14 新增；ADR-072 F3）。
     *
     * 区分"部署成功"与"**内容确实变了**"：前者只说明文件在位，后者才说明
     * "设备上正在跑的逻辑需要被替换"。真机 11-02/11-03 的两次事故都源于只做了前者——
     * 新脚本写进了 `/data/local/tmp`，但正在跑的 `run2-inner.sh` 早已把 `while` 循环
     * 解析进内存，绝不会回读文件，且新实例会被 `flock -n` 静默拒绝（脚本第 36~44 行），
     * 而 `QqTermuxBridge.start()` 又把"第二实例自行退出"当幂等成功
     * ⇒ **部署永远不会生效**。
     */
    data class DeployResult(val ok: Boolean, val changed: Boolean)

    /**
     * 把 assets 里的生产脚本部署到 `/data/local/tmp`（幂等，但**会更新内容**）。
     *
     * @return 见 [DeployResult]；`changed=true` 时调用方**必须**重启协议端进程
     */
    suspend fun deployScriptsDetailed(): DeployResult {
        if (!ShizukuShell.isReady()) return DeployResult(ok = false, changed = false)
        val marker = readScriptMarker()
        val contents = mutableMapOf<String, String>()
        var changed = false
        for (name in QqProtocolScripts.ASSET_SCRIPTS) {
            val text = readAsset(name) ?: run {
                Log.w(TAG, "assets 缺少脚本 $name")
                return DeployResult(ok = false, changed = changed)
            }
            contents[name] = text
            val path = QqProtocolScripts.deployedPath(name)
            val hash = sha256(text)
            val upToDate = marker[name] == hash && ShizukuShell.fileExists(path)
            if (upToDate) continue
            if (!ShizukuShell.writeShellFile(path, text)) {
                Log.w(TAG, "部署失败：$name")
                return DeployResult(ok = false, changed = changed)
            }
            changed = true
            Log.i(TAG, "脚本已部署/更新：$name（sha=${hash.take(8)}）")
        }
        // 两个 Prism 侧生成的脚本：**也走标记比对**（审查 M-5）。
        // 原来"存在即跳过"意味着将来改 STOP_SCRIPT_BODY 永远到不了手机——
        // 与 H3 修的是同一类问题，只是对象不同。
        if (deployGenerated(
                MARKER_KEY_STOP, QqProtocolScripts.STOP_SCRIPT,
                QqProtocolScripts.STOP_SCRIPT_BODY, marker, contents
            )
        ) changed = true
        if (deployGenerated(
                MARKER_KEY_PROBE, QqProtocolScripts.PROBE_SCRIPT,
                QqProtocolScripts.PROBE_SCRIPT_BODY, marker, contents
            )
        ) changed = true
        val allPresent = QqProtocolScripts.ASSET_SCRIPTS.all {
            ShizukuShell.fileExists(QqProtocolScripts.deployedPath(it))
        }
        if (allPresent) {
            writeScriptMarker(contents.mapValues { (_, v) -> sha256(v) })
        }
        // guardrail A-01（2026-11-14 验收）：**不能**用 `hadMarker` 门控 `changed`。
        // 标记文件写失败/被清时 changed 会恒为 false ⇒ 脚本部署了却永远不重启 ⇒ 静默不生效，
        // 与 F3 要修的失败模式一模一样。宁可首次部署多一次"停→起"（此时通常没有在跑的链，
        // 代价仅为几秒），也不能让"部署不生效"再次成为静默故障。
        return DeployResult(ok = allPresent, changed = changed)
    }

    /** 兼容旧调用点：只关心"是否都在位"。 */
    suspend fun deployScripts(): Boolean = deployScriptsDetailed().ok

    /**
     * 部署一个 Prism 侧生成的脚本（内容固定但**会随版本变化**）。
     *
     * 与 assets 脚本同一套标记机制：hash 不一致才重写。
     *
     * @return true=本次**实际写入**（即内容有变化）
     */
    private fun deployGenerated(
        markerKey: String,
        path: String,
        body: String,
        marker: Map<String, String>,
        contents: MutableMap<String, String>
    ): Boolean {
        val hash = sha256(body)
        contents[markerKey] = body
        if (marker[markerKey] == hash && ShizukuShell.fileExists(path)) return false
        if (!ShizukuShell.writeShellFile(path, body)) {
            Log.w(TAG, "部署生成脚本失败：${path.substringAfterLast('/')}")
            return false
        }
        return true
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
    private suspend fun deployScriptsQuietly(): DeployResult = try {
        deployScriptsDetailed()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "部署异常：${e::class.simpleName}")
        DeployResult(ok = false, changed = false)
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
        // 2026-11-18（guardrail M1）：**一键修复必须先解除 force-stop**。
        //
        // 原实现直接走到 [QqTermuxBridge.probeVerified]——而 force-stop 后探针的
        // `startService` 同样被静默丢弃，probe 必然 false，于是：
        // - `started` 恒为 false；
        // - readiness 被归类为 `EXTERNAL_APPS_BLOCKED`，UI 让用户去开
        //   `allow-external-apps`——**方向完全错误，永远修不好**；
        // - `restartForDeploy` / `restartAndWait` 因 `probed=false` 永不可达。
        //
        // 放在 power/deploy 之后、probe 之前：先恢复 Termux 的接收能力，再探针。
        val termuxAlive = QqTermuxBridge.isTermuxProcessAlive()
        if (!termuxAlive) {
            Log.w(TAG, "一键修复：Termux 进程不存在（疑似被系统 force-stop），先解除 stopped")
            QqTermuxBridge.ensureUnstoppedAwait()
        }
        val deploy = deployScriptsQuietly()
        val deployed = deploy.ok
        // 先用**闭环探针**验证「命令真的被执行」——不是只看 Intent 有没有抛异常。
        // 真机教训：allow-external-apps=false 时 startService 正常返回、Termux 内部拒绝执行。
        val probed = if (deployed) QqTermuxBridge.probeVerified(appContext) else false
        // D-1（验收必修）：手动路径同样要判"是否已经在跑"
        //
        // 2026-11-18（真机 E2E 现场发现）：**Termux 刚被 force-stop 时，状态文件仍是新鲜的**。
        // 脚本每 10 秒写一次，进程被杀后文件保留最后时间戳，`STATUS_FRESH_MS=30s` 内
        // 它看起来完全正常（真机实测：force-stop 后 14 秒触发修复，读到
        // `ts=… age=14s → 判定 3000 仍在监听`，但此时 proot/Xvfb/qq 全部为 0）。
        // 后果：修复"正确地"解除了 stopped，却因 alreadyRunning=true 跳过启动 → 仍然没起。
        //
        // 不变量：**Termux 进程不存在时，它内部跑的 run2 链必然也已死**——
        // 此时状态文件无论多新鲜都不可信。这比调小新鲜度窗口更准确
        // （窗口调小会误伤正常刷新，而这条不变量是因果推断）。
        val alreadyRunning = if (termuxAlive) {
            isProtocolPortListening()
        } else {
            Log.i(TAG, "Termux 曾被 force-stop，判定 run2 链已死（忽略状态文件新鲜度）")
            false
        }
        // 2026-11-14（ADR-072 F3）：**脚本内容变了就必须重启**，否则新逻辑永不生效
        // （运行中的 bash 已把 while 循环读进内存，新实例又被 flock 静默拒绝）。
        //
        // 只拦 `need_qr`（用户可能正在扫二维码）。**不再拦 `starting`**：
        // 现场实测——旧脚本探活恒 0 会永远写 `starting`，若它也拦重启，
        // 「部署新脚本」与「让新脚本生效」互为死锁（deployed=true 却不重启）。
        val state = currentFreshState()
        val restartForDeploy = deployed && deploy.changed && alreadyRunning &&
            state != STATE_NEED_QR
        val started: Boolean = when {
            !deployed || !probed -> false
            restartForDeploy -> {
                Log.i(TAG, "脚本内容已变更，重启协议端以让新逻辑生效（state=$state）")
                // restartAndWait 返回三态（CONFIRMED/STILL_RUNNING/UNKNOWN），内部已记录日志；
                // 无论哪一态都已提交启动，故按"已提交"计（真正的在线判定由 60s 探测负责）。
                QqTermuxBridge.restartAndWait(appContext)
                true
            }
            !alreadyRunning -> QqTermuxBridge.start(appContext)
            else -> false
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
        // 2026-11-15（ADR-073）：**必须把账号在线态一起报出来**。
        // "协议端进程在跑" ≠ "账号能收发"：3000 在监听但账号已离线（需扫码）时，
        // 旧报告让 UI 显示"本来就在运行"，用户点了修复却什么都没发生。
        val health = runCatching { healthProbe?.invoke() }.getOrNull()
        val accountState = health?.state?.name.orEmpty()
        val needsQr = health?.state == QqProtocolHealth.State.QQ_OFFLINE ||
            currentFreshState() == STATE_NEED_QR
        return RepairReport(
            power = power,
            scriptsDeployed = deployed,
            probeAccepted = probed,
            started = started,
            alreadyRunning = alreadyRunning,
            readiness = readiness,
            accountState = accountState,
            needsQrLogin = needsQr
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
        val readiness: QqTermuxBridge.Readiness,
        /**
         * 修复后的**账号在线态**（2026-11-15 新增；ADR-073）。
         *
         * 真机反馈「一键修复好像有问题」的根因：旧报告只有 `alreadyRunning`，
         * 而"协议端进程在跑"与"账号能收发"是两件事。3000 在监听但账号已离线
         * （需扫码）时，UI 固定显示"协议端本来就在运行（未重启，避免账号被踢）"，
         * 把真正要做的动作（扫码）完全掩盖了。现在把账号态一起返回并驱动文案。
         */
        val accountState: String = "",
        /** 需要用户**扫码**（账号离线/登录态失效）。 */
        val needsQrLogin: Boolean = false
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
         * （形如 `<字母数字>.<大写字母>`，故正则不能只认 `[A-Za-z0-9]`，否则会把 token
         * 截断成"看起来对、实际鉴权必失败"的地址）。
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

        /**
         * 从状态文件解析 `state=...`（纯函数，可单测）。
         *
         * 2026-11-02（ADR-071）新增：`run2-inner.sh` 写 `starting|need_qr|online|qq_dead`。
         * 旧版脚本没有该字段 → 返回空串（调用方按"未知"处理，行为与旧版一致）。
         */
        fun parseState(statusText: String): String {
            val m = Regex("""\bstate=([a-z_]+)""").find(statusText) ?: return ""
            return m.groupValues[1]
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
         * `state=starting` 的宽限期（2026-11-14 真机实证）。
         *
         * 与脚本内的「启动超时 180s」对齐：超过该时长仍处 `starting` 即视为卡死，
         * 允许自动拉起/部署重启。否则旧脚本探活恒 0 造成的"永远 starting"
         * 会锁死整个自愈链（现场实测：脚本部署成功却因 `alreadyRunning=true` 不重启）。
         */
        const val STARTING_GRACE_MS = 180_000L

        /** 状态机取值（与 `run2-inner.sh` 约定一致；ADR-071）。 */
        const val STATE_STARTING = "starting"
        const val STATE_NEED_QR = "need_qr"
        const val STATE_ONLINE = "online"
        const val STATE_QQ_DEAD = "qq_dead"

        /**
         * 「协议端是否在跑」的**判据顺序**（纯函数，可单测；guardrail R-4）。
         *
         * 顺序即语义，不可调换：
         * 1. `starting` / `need_qr` → 在跑（扫码期 3000 本就不监听，重启会毁掉扫码窗口）；
         * 2. `onebot3000 > 0` → 在跑；
         * 3. HTTP 可达 → 在跑（兜底旧脚本恒写 0 的情况）；
         * 4. 其余 → 停止。
         */
        fun isRunningDecision(onebot3000: Int, state: String, httpReachable: Boolean): Boolean {
            if (state == STATE_STARTING || state == STATE_NEED_QR) return true
            if (onebot3000 > 0) return true
            return httpReachable
        }

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
