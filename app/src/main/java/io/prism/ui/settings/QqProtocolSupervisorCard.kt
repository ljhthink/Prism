package io.prism.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.prism.qq.QqProtocolSupervisor
import io.prism.qq.QqTermuxBridge
import kotlinx.coroutines.launch
import io.prism.ui.components.PrismActionsRow
import io.prism.ui.components.PrismCardBox
import io.prism.ui.components.PrismKv
import io.prism.ui.components.PrismMini
import io.prism.ui.components.PrismNoteLine
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismType
import io.prism.ui.theme.PrismWarning

/**
 * 「协议端管家」设置区（2026-11-01；US-3128）。
 *
 * ## 为什么要有它（用户原话）
 *
 * > 现在的配置非常麻烦，又新引入了 ZeroTermux，但大多数用户并不会使用该软件，
 * > 是否可以把对应功能直接接入 Prism 中降低配置门槛。
 *
 * 它做两件事：
 * 1. **把不可验证的手工步骤变成可验证的判据**——白名单到底生效没有，回读给用户看；
 * 2. **把要敲的命令变成按钮**——一键修复 / 启动 / 停止 / 扫码登录。
 *
 * ## 诚实的边界（必须显示，而不是只写在文档里）
 *
 * - 白名单只**降低**被 HyperOS 清理的概率，**不保证进程不死**；
 * - 首次仍有 2 个一次性手动步骤（授予 Termux 命令权限、Termux 内开 `allow-external-apps`），
 *   已实测任何技术手段都绕不开（`pm grant` 被系统拒绝、Termux 私有目录不可读）。
 */
@Composable
fun QqProtocolSupervisorSection(
    supervisor: QqProtocolSupervisor?,
    onOpenTermuxPermission: () -> Unit,
    onOpenWebUi: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 状态行只放**人能读的结论**（设计稿「已就绪 · QQ 在线」）；协议端状态文件原文是
    // `ts=… tick=… state=online` 这类机器串，直接铺在卡片上既不可读也与设计稿不符，
    // 因此仅在**非正常态**时以 note-line 作为诊断原文展示（ADR-079 D3：细节只在异常时出现）。
    var statusRaw by remember { mutableStateOf("点「刷新」查看协议端状态") }
    var powerText by remember { mutableStateOf("未核验 · 点「一键修复」校验") }
    var readiness by remember { mutableStateOf<QqTermuxBridge.Readiness?>(null) }

    /** 账号在线态文案（2026-11-15；ADR-073）：与"协议端是否在跑"分开显示，避免混为一谈。 */
    var accountText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        val app = supervisor
        if (app == null) {
            statusRaw = "App 尚未初始化完成，请稍后重试"
            return
        }
        scope.launch {
            // 2026-11-01 审查 H-3（含第三轮补漏）：
            // `readiness()` → `areDeployed()` → `fileExists()`×3 → `exec()` → 冷绑定
            // `latch.await(5s)`（ShizukuShell）**全部在调用线程同步阻塞**；
            // `rememberCoroutineScope()` 是 Main，只要有一处在 Main 调用就会卡 UI / ANR。
            // 因此**整个探测过程**（含 readiness）都放进 IO，主线程只接收最终结果。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val r = QqTermuxBridge.readiness(context)
                val text = try {
                    app.readStatusForDisplay()
                        ?: "读不到协议端状态文件（未启动或 Shizuku 未就绪）"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "读取状态失败：${e::class.simpleName}"
                }
                r to text
            }.let { (r, text) ->
                readiness = r
                statusRaw = text
            }
        }
    }

    /** 是否具备操作条件（readiness 未知时**不让点**，避免点了没反应）。 */
    val canOperate = supervisor != null &&
        readiness != null &&
        readiness != QqTermuxBridge.Readiness.TERMUX_MISSING

    // 首次进入自动探测一次（用户不必先点"刷新"才知道当前状态）
    androidx.compose.runtime.LaunchedEffect(supervisor) { refresh() }

    /** 告警链路是否可用（无通知权限时 M2 告警发不出去，必须显式告知）。 */
    val notificationsEnabled = androidx.compose.runtime.remember {
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    // 设计稿 `settingsQq()` 的「协议端管家」：`card`（状态 / 省电策略）+ 7 个小按钮 + 一行 note。
    // 视觉层从 Material 文本/按钮换成 Prism 的 `PrismCardBox` / `PrismKv` / `PrismMini`，
    // **全部动作与 IO 线程语义保持不变**（探测/修复/启动/重启/停止/扫码/刷新）。
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = PrismSize.s2)) {
        PrismCardBox(title = "协议端管家") {
            // **maxLines = 3**（guardrail 2026-11-21 B1）：这三格可能是唯一可执行结论
            //（「账号已离线：请点扫码登录」/「命令没能交给 Termux：见最后一步」/ describeReadiness
            // 的整条命令），单行会让用户看不到该做什么。
            PrismKv(
                key = "状态",
                value = protocolStateLabel(statusRaw),
                tone = protocolStateTone(statusRaw),
                maxLines = 3
            )
            PrismKv(
                key = "省电策略",
                value = powerText,
                tone = if (powerText.contains("未")) PrismWarning else null,
                maxLines = 3
            )
            // guardrail R-03：账号态与"协议端是否在跑"分开显示（进程在跑 ≠ 账号能收发）
            if (accountText.isNotBlank()) {
                PrismKv(
                    key = "账号",
                    value = accountText,
                    tone = if (accountText.contains("离线") || accountText.contains("不可达")) PrismDanger else null,
                    maxLines = 3
                )
            }
            // 2026-11-15（ADR-075 / US-3134）：媒体失败诊断就地可见（不只藏在 logcat）
            val mediaFailure = (context.applicationContext as? io.prism.PrismApplication)
                ?.lastMediaFailureKind
            if (mediaFailure != null) {
                val severe = mediaFailure == io.prism.qq.QqMediaResult.Kind.RKEY_EXPIRED ||
                    mediaFailure == io.prism.qq.QqMediaResult.Kind.NETWORK
                PrismNoteLine(
                    io.prism.qq.QqMediaFailure.diagnosticFor(mediaFailure) +
                        if (mediaFailure == io.prism.qq.QqMediaResult.Kind.RKEY_EXPIRED) {
                            "（已尝试从协议端本地文件回退；仍失败请点「一键修复」重装脚本）"
                        } else ""
                )
                if (severe) {
                    // 严重态用真彩色强调（PrismNoteLine 是 faint 色，不足以提示）
                    Text(
                        text = "媒体链路异常 · 需要处理",
                        color = PrismDanger,
                        fontSize = PrismType.micro,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            if (!notificationsEnabled) {
                Text(
                    text = "未授予通知权限 · 协议端掉线时无法通知你（去系统设置允许通知）",
                    color = PrismDanger,
                    fontSize = PrismType.micro,
                    lineHeight = PrismType.microLine,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            readiness?.let {
                PrismNoteLine(QqTermuxBridge.describeReadiness(it))
            }
            // 诊断原文**只在非正常态**出现：正常时页面保持设计稿的一行状态，
            // 出问题时用户仍能看到 `state=…` 这类判据（不牺牲可排查性）。
            if (protocolStateLabel(statusRaw) != "已就绪 · QQ 在线") {
                PrismNoteLine("状态原文：$statusRaw")
            }
            // Shizuku 不可用时：至少把 Prism 自己排除出电池优化（否则连告警都发不出去）
            if (readiness == QqTermuxBridge.Readiness.SHIZUKU_NOT_READY) {
                PrismActionsRow {
                    PrismMini(
                        text = "电量豁免",
                        onClick = {
                            QqTermuxBridge.requestIgnoreBatteryOptimizations(context)
                            statusRaw = "已请求系统豁免；请在弹出的确认框点「允许」"
                        }
                    )
                }
            }
            // 7 个动作分两行（设计稿是 flex-wrap；Compose 的 Row 不换行，分两行等价且不挤压）
            PrismActionsRow {
                PrismMini(
                    text = "一键修复",
                    enabled = !busy && canOperate,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                // H-3：整段（含 Shizuku 绑定 + 多步命令）搬到 IO，避免卡主线程。
                                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    supervisor?.repairAll()
                                }
                                powerText = result?.power?.describe() ?: "无法核验"
                                statusRaw = when {
                                    result == null -> "无法执行"
                                    // 2026-11-15（ADR-073）：**账号离线优先于一切**。
                                    // 真机反馈"一键修复好像有问题"：3000 在监听但账号已离线时，
                                    // 旧文案固定报"本来就在运行"，把"必须扫码"这个唯一有效动作
                                    // 完全掩盖了——用户点多少次都不会好。
                                    result.needsQrLogin ->
                                        "协议端在运行，但账号已离线：请点「扫码登录」重新扫码"
                                    result.alreadyRunning -> "协议端本来就在运行（未重启，避免账号被踢）"
                                    result.started -> "已提交启动命令"
                                    // 验收 AC-1：探针被拒时明确指向 Termux 的 allow-external-apps
                                    !result.probeAccepted ->
                                        "命令没能交给 Termux：见下方提示的最后一步（allow-external-apps）"
                                    else -> "启动未成功：" + QqTermuxBridge.describeReadiness(
                                        result.readiness
                                    )
                                }
                                // 账号态如实展示（不再只在日志里）
                                if (result != null && result.accountState.isNotBlank()) {
                                    accountText = when (result.accountState) {
                                        "ONLINE" -> "账号在线"
                                        "QQ_OFFLINE" -> "账号离线（需扫码）"
                                        else -> "协议端不可达"
                                    }
                                }
                                readiness = result?.readiness
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                statusRaw = "一键修复失败：${e::class.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                )
                PrismMini(
                    text = "启动",
                    enabled = !busy && canOperate,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    QqTermuxBridge.startIfNotRunning(
                                        context,
                                        supervisor?.isProtocolPortListening() ?: false
                                    )
                                }
                                statusRaw = if (ok) {
                                    "已提交启动命令"
                                } else {
                                    "协议端已在运行（未重启，避免账号被踢）；确实要重启请用「重启」"
                                }
                                // H-3（第三轮补漏）：readiness 内部有 3 次 Shizuku fileExists，
                                // 必须在 IO 上算，不能在 Main 直接调。
                                readiness = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    QqTermuxBridge.readiness(context)
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                statusRaw = "启动失败：${e::class.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                )
                PrismMini(
                    text = "重启",
                    enabled = !busy && canOperate,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    QqTermuxBridge.restart(context)
                                }
                                statusRaw = if (ok) "已提交重启（当前在线会话会被重新登录）" else "重启未成功"
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                statusRaw = "重启失败：${e::class.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                )
                PrismMini(
                    text = "停止",
                    enabled = !busy && canOperate,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    QqTermuxBridge.stop(context)
                                }
                                statusRaw = if (ok) "已提交停止命令" else "停止未成功"
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                statusRaw = "停止失败：${e::class.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                )
            }
            PrismActionsRow {
                PrismMini(text = "授予命令权限", onClick = onOpenTermuxPermission)
                PrismMini(
                    text = "扫码登录",
                    enabled = !busy && supervisor != null,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val url = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    supervisor?.resolveWebUiUrl()
                                }
                                if (url != null) {
                                    onOpenWebUi(url)
                                } else {
                                    statusRaw = "还没找到扫码地址：先启动协议端，等日志出现 WebUi 地址后重试"
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                statusRaw = "解析扫码地址失败：${e::class.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                )
                PrismMini(
                    text = if (busy) "处理中…" else "刷新",
                    enabled = !busy,
                    onClick = { refresh() }
                )
            }
            PrismNoteLine(
                "电池白名单只降低被清理概率，不保证进程不死；掉线由通知告警" +
                    "（30 分钟内最多一次），账号离线会额外提醒（需重新扫码）。"
            )
        }
    }
}

/**
 * 协议端状态原文 → 一行人可读结论（设计稿 `settingsQq()`「状态 · 已就绪 · QQ 在线」）。
 *
 * 状态文件是 `ts=… tick=… qq=1 onebot3000=1 state=online since=… restarts=0 tcp=0`
 * 这类机器串，直接铺在卡片上不可读；这里只做关键词判定，**不改写事实**：
 * 判不出来就把原文前 40 字返回（不谎报「在线」）。
 */
private fun protocolStateLabel(raw: String): String = when {
    raw.contains("state=online") && raw.contains("qq=1") -> "已就绪 · QQ 在线"
    raw.contains("state=online") -> "协议端在线 · 账号离线"
    raw.contains("state=starting") || raw.contains("state=restarting") -> "启动中"
    raw.contains("state=stopped") || raw.contains("state=exited") -> "未启动"
    raw.startsWith("读不到") || raw.startsWith("点「刷新」") -> "未启动 · 点刷新重试"
    raw.length <= 40 -> raw
    else -> raw.take(40) + "…"
}

/** 状态结论的语义色（正常态保持正文色；其余为中间/异常态）。 */
private fun protocolStateTone(raw: String): androidx.compose.ui.graphics.Color? =
    if (raw.contains("state=online") && raw.contains("qq=1")) null else PrismWarning
