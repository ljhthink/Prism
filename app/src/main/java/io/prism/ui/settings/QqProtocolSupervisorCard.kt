package io.prism.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    var statusText by remember { mutableStateOf("点「刷新」查看协议端状态") }
    var powerText by remember { mutableStateOf("尚未核验") }
    var readiness by remember { mutableStateOf<QqTermuxBridge.Readiness?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        val app = supervisor
        if (app == null) {
            statusText = "App 尚未初始化完成，请稍后重试"
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
                statusText = text
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

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("协议端管家（本机模式）", style = MaterialTheme.typography.titleSmall)
        Text(
            "把「启动协议端 / 修复电池白名单 / 扫码登录」变成按钮。仍需安装一次 ZeroTermux，" +
                "但之后不需要再打开它、也不需要敲任何命令。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("状态：$statusText", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Text("省电策略：$powerText", style = MaterialTheme.typography.bodySmall)
        if (!notificationsEnabled) {
            Text(
                "⚠ 未授予通知权限：协议端掉线时无法通知你。请到系统设置允许 Prism 发送通知。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        readiness?.let {
            Text(
                QqTermuxBridge.describeReadiness(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Shizuku 不可用时：让用户至少能把 Prism 自己排除出电池优化，
        // 否则连「协议端掉线」的告警都发不出去（M2 一起失效）。
        if (readiness == QqTermuxBridge.Readiness.SHIZUKU_NOT_READY) {
            TextButton(
                onClick = {
                    QqTermuxBridge.requestIgnoreBatteryOptimizations(context)
                    statusText = "已请求系统豁免；请在弹出的确认框点「允许」"
                }
            ) { Text("让系统别冻结 Prism（电量优化豁免）") }
        }
        Text(
            "说明：电池白名单只降低被系统清理的概率，不能保证进程不死；掉线后由系统通知告警" +
                "（同一轮掉线 30 分钟内最多通知一次；若期间升级为「账号离线」会额外提醒一次，" +
                "因为那需要你重新扫码）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(
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
                            statusText = when {
                                result == null -> "无法执行"
                                // N-3：本来就在跑时**不要**报"启动未成功"，那会误导用户
                                result.alreadyRunning -> "协议端本来就在运行（未重启，避免账号被踢）"
                                result.started -> "已提交启动命令"
                                // 验收 AC-1：探针被拒时明确指向 Termux 的 allow-external-apps
                                !result.probeAccepted ->
                                    "命令没能交给 Termux：见下方提示的最后一步（allow-external-apps）"
                                else -> "启动未成功：" + QqTermuxBridge.describeReadiness(
                                    result.readiness
                                )
                            }
                            readiness = result?.readiness
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            statusText = "一键修复失败：${e::class.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("一键修复") }
            TextButton(
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
                            statusText = if (ok) {
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
                            statusText = "启动失败：${e::class.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("启动") }
            TextButton(
                enabled = !busy && canOperate,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                QqTermuxBridge.restart(context)
                            }
                            statusText = if (ok) "已提交重启（当前在线会话会被重新登录）" else "重启未成功"
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            statusText = "重启失败：${e::class.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("重启") }
            TextButton(
                enabled = !busy && canOperate,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                QqTermuxBridge.stop(context)
                            }
                            statusText = if (ok) "已提交停止命令" else "停止未成功"
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            statusText = "停止失败：${e::class.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("停止") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(onClick = onOpenTermuxPermission) { Text("授予命令权限") }
            TextButton(
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
                                statusText = "还没找到扫码地址：先启动协议端，等日志出现 WebUi 地址后重试"
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            statusText = "解析扫码地址失败：${e::class.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("扫码登录") }
            TextButton(enabled = !busy, onClick = { refresh() }) { Text("刷新") }
        }
    }
}
