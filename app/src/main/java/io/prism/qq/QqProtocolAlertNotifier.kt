package io.prism.qq

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import io.prism.MainActivity
import io.prism.qq.onebot.QqDiagnosticsRules
/**
 * 协议端**失联/恢复**的系统通知（M2；US-3128）。
 *
 * ## 为什么必须是系统通知
 *
 * 10-31 事故里 Prism 与协议端先后被杀。**App 内的任何 UI 提示在 App 死掉后都发不出去**，
 * 所以告警只能走通知栏（前提是 `POST_NOTIFICATIONS` 已授予，Android 13+ 为运行时权限）。
 *
 * ## 为什么复用既有渠道
 *
 * 新建渠道会让用户在系统设置里看到重复的 Prism 条目（交接文档 B3 明确要求复用）。
 * 这里复用 `prism_phone_status`（「手机操控状态」），它是同类"后台状态回报"语义，
 * 且已由 [io.prism.phonecontrol.PhoneControlCompletionNotifier] 在启动时建好。
 * 本类**只在本渠道缺失时**补建（例如用户清过通知设置），渠道名保持一致以免出现两个同名项。
 *
 * ## 文案必须可执行（AC-5）
 *
 * 通知正文直接复用 [QqDiagnosticsRules.topologyAdvice]，与设置页"连接诊断"同源，
 * 避免两处文案漂移。点击通知进入 Prism（设置页 QQ 区块是恢复动作的入口）。
 */
object QqProtocolAlertNotifier {

    private const val TAG = "QqProtocolAlertNotifier"

    /** 协议端告警通知固定 id（新告警覆盖旧告警——只关心最新状态）。 */
    const val NOTIFICATION_ID = 8110

    /** 重提醒通知 id（ADR-072 F4）：独立于首条，避免互相覆盖。 */
    const val REMINDER_NOTIFICATION_ID = 8111

    /** 与 `PhoneControlCompletionNotifier` 共用的渠道 id（**不要改**，改了等于新建渠道）。 */
    private const val CHANNEL_ID = "prism_phone_status"

    /** 渠道名必须与既有渠道**完全一致**，否则用户在系统设置里会看到两个不同的 Prism 条目。 */
    private const val CHANNEL_NAME = "手机操控状态"

    private const val REQUEST_CODE_ALERT = 8101

    /**
     * 发一条「协议端掉线」通知。
     *
     * @param kind 失联原因（决定文案与建议）
     * @param detail 探测给出的可读说明（已脱敏，可为空）
     * @param loopback 是否本机模式（回环）——决定标题措辞
     */
    fun notifyDown(
        context: Context,
        kind: QqProtocolAlertPolicy.Kind,
        detail: String = "",
        loopback: Boolean = true,
        reminder: Boolean = false
    ) {
        ensureChannel(context)
        val baseTitle = when (kind) {
            QqProtocolAlertPolicy.Kind.LOCAL_PROTOCOL_NOT_RUNNING -> "QQ 小号协议端未运行"
            QqProtocolAlertPolicy.Kind.PROTOCOL_UNREACHABLE -> "QQ 小号协议端连接不上"
            QqProtocolAlertPolicy.Kind.QQ_ACCOUNT_OFFLINE -> "QQ 小号账号已离线"
            QqProtocolAlertPolicy.Kind.PROTOCOL_NEVER_STARTED -> "QQ 小号协议端还没启动成功过"
        }
        // 2026-11-14（ADR-072 F4）：重提醒必须在标题上可见差异——否则用户会当成旧通知划走。
        val title = if (reminder) "【仍未恢复】$baseTitle" else baseTitle
        val advice = adviceFor(kind)
        val body = buildString {
            append(advice)
            // 验收安全项：detail 来自探测的服务端 message，未过 redact，这里也脱敏一次
            if (detail.isNotBlank()) {
                append("\n（探测结果：")
                    .append(QqProtocolSupervisor.redactForDisplay(detail).take(80))
                    .append("）")
            }
            append("\n打开 Prism → 设置 → QQ 小号助手，可一键启动/修复或重新扫码。")
        }
        // 2026-11-14：重提醒走独立通知 id，避免与首条互相覆盖；
        // reminder 用 CATEGORY_REMINDER 让系统在勿扰/锁屏上更显著。
        post(context, title, body, reminder = reminder)
    }

    /**
     * 发一条「协议端已恢复」通知（AC-4）。
     *
     * @param downKind 之前掉线的原因（[QqProtocolAlertPolicy.Kind.name]；空=未知）
     * @param downDurationMs 掉线持续时长（0=未知）
     */
    fun notifyRecovered(context: Context, downKind: String, downDurationMs: Long) {
        ensureChannel(context)
        val what = when (downKind) {
            QqProtocolAlertPolicy.Kind.LOCAL_PROTOCOL_NOT_RUNNING.name -> "协议端进程未运行"
            QqProtocolAlertPolicy.Kind.PROTOCOL_UNREACHABLE.name -> "协议端不可达"
            QqProtocolAlertPolicy.Kind.QQ_ACCOUNT_OFFLINE.name -> "账号离线"
            else -> "协议端失联"
        }
        val duration = if (downDurationMs > 0L) "，持续 ${humanDuration(downDurationMs)}" else ""
        post(
            context,
            "QQ 小号协议端已恢复",
            "此前的「$what」已恢复$duration。收发功能已可用。"
        )
    }

    /**
     * 发一条**存活看门狗 / 停摆补偿**通知（2026-11-14；ADR-072 F1/F2）。
     *
     * 与 [notifyDown] 分开的原因：这两类通知的性质是"我们曾经不在，现在回来了"，
     * 与"协议端掉线"是不同的语义；混用会让用户分不清是该去扫码还是该重新打开 App。
     */
    fun notifyWatchdog(context: Context, title: String, body: String) {
        ensureChannel(context)
        post(context, title, body, reminder = true)
    }

    /**
     * 「后台被系统限制，自动回复已暂停」通知（2026-11-15；ADR-073）。
     *
     * 这是**受限态下唯一一定能送达**的通道：发通知不需要前台服务、不需要进程常驻。
     * 真机 177 次 `startForeground() not allowed due to bg restriction` 期间，
     * 用户完全不知道功能已停摆——本方法就是补这个洞。
     *
     * @param attempt 连续被拒次数（用于文案与诊断）
     * @param hasExactAlarm 是否持有「闹钟与提醒」权限（决定给出的修复建议）
     */
    fun notifyBackgroundRestricted(context: Context, attempt: Int, hasExactAlarm: Boolean) {
        ensureChannel(context)
        val advice = if (!hasExactAlarm) {
            "请到 设置 → 应用 → Prism → 授予「闹钟与提醒」权限，" +
                "否则系统会阻止后台唤醒，自动回复随时会停。"
        } else {
            "请在系统设置里把 Prism 的省电策略改为「无限制」，并允许后台弹出界面。"
        }
        post(
            context,
            "QQ 小号助手：后台被系统限制",
            "系统拒绝了后台运行许可（连续第 $attempt 次），自动回复与定时消息暂时无法工作。" +
                "打开 Prism 一次即可恢复。\n$advice",
            reminder = attempt == 1
        )
    }

    /**
     * 「自动拉起失败」通知（2026-11-15；ADR-073）。
     *
     * 真机实证：Shizuku 未就绪时自动拉起连命令都发不出去，用户只看到"又断了"
     * 却不知道要做什么。这里给出**一步**可执行动作（而不是罗列所有可能原因）。
     */
    fun notifyRestartFailed(context: Context, reason: String, shizukuReady: Boolean) {
        ensureChannel(context)
        val advice = if (!shizukuReady) {
            "Shizuku 未在运行——自动拉起需要它。请打开 Shizuku 应用并启动服务" +
                "（手机重启后必须重做一次），然后回到 Prism 点「一键修复」。"
        } else {
            "请打开 Prism → 设置 → QQ 小号助手 → 协议端管家，点「一键修复」查看具体原因。"
        }
        post(
            context,
            "QQ 小号助手：自动拉起失败",
            "协议端掉了但未能自动恢复。\n原因：$reason\n$advice",
            reminder = true
        )
    }

    /** AC-5：可执行建议（**三条全部**指向 Prism 内的入口，不再把用户引回终端）。 */
    private fun adviceFor(kind: QqProtocolAlertPolicy.Kind): String = when (kind) {
        // 2026-11-01 验收 AC-5：三条文案统一指向管家（与设置页诊断同源：诊断文案也已改为指向管家）
        QqProtocolAlertPolicy.Kind.LOCAL_PROTOCOL_NOT_RUNNING ->
            QqDiagnosticsRules.topologyAdvice(QqDiagnosticsRules.Verdict.LOCAL_PROTOCOL_NOT_RUNNING)
                ?: "打开 Prism → 设置 → QQ 小号助手 → 协议端管家，点「一键修复」"

        QqProtocolAlertPolicy.Kind.PROTOCOL_UNREACHABLE ->
            QqDiagnosticsRules.topologyAdvice(QqDiagnosticsRules.Verdict.FIREWALL_TIMEOUT)
                ?: "检查协议端所在主机与网络是否可达"

        QqProtocolAlertPolicy.Kind.QQ_ACCOUNT_OFFLINE ->
            "QQ 账号登录已失效（常见于被踢或风控），需要在手机上重新扫码：" +
                "打开 Prism → 设置 → QQ 小号助手 → 协议端管家 → 「扫码登录」。"

        // 2026-11-02：从未连上过的文案必须直接点出"差哪一步"，而不是笼统说"连不上"
        QqProtocolAlertPolicy.Kind.PROTOCOL_NEVER_STARTED ->
            "协议端一次都没能启动成功。请在 Prism → 设置 → QQ 小号助手 → 协议端管家 依次检查：" +
                "① 已允许「在 Termux 环境中运行命令」；② ZeroTermux 的 allow-external-apps 已设为 true；" +
                "③ 点「一键修复」后看卡片给的最后一步提示。"
    }

    private fun post(context: Context, title: String, body: String, reminder: Boolean = false) {
        val appContext = context.applicationContext
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 2026-11-01 审查 M1/M-4：不仅要看 POST_NOTIFICATIONS，还要看**渠道是否被用户关闭**
        // 与系统级通知开关——三者任一关闭时 notify() 都会被静默丢弃，
        // 而那正是本需求要消除的"静默离线"。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS 未授予，本次协议端告警无法送达用户")
        }
        if (!nm.areNotificationsEnabled()) {
            Log.w(TAG, "系统已关闭 Prism 的通知，本次协议端告警无法送达用户")
        }
        val channel = nm.getNotificationChannel(CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            Log.w(TAG, "通知渠道 $CHANNEL_ID 已被用户关闭，本次协议端告警无法送达用户")
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_ALERT,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty().take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(500)))
            .setCategory(
                if (reminder) NotificationCompat.CATEGORY_REMINDER
                else NotificationCompat.CATEGORY_STATUS
            )
            .setPriority(
                if (reminder) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setOnlyAlertOnce(!reminder)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching { nm.notify(if (reminder) REMINDER_NOTIFICATION_ID else NOTIFICATION_ID, notification) }
    }

    /**
     * 确保渠道存在。
     *
     * 渠道已存在时 `createNotificationChannel` 对用户已修改的设置**不会**覆盖（Android 行为），
     * 因此这里改名不会强行抢走用户配置，只保证首次创建时名称合理。
     */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "LLM 操控手机任务的完成/失败回报，以及 QQ 小号协议端的连接状态告警"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
    }

    /** 人类可读时长（≤2 位小数；只用于通知文案）。 */
    internal fun humanDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
        return when {
            totalMinutes < 1L -> "不到 1 分钟"
            totalMinutes < 60L -> "${totalMinutes} 分钟"
            else -> {
                val hours = totalMinutes / 60L
                val minutes = totalMinutes % 60L
                if (minutes == 0L) "${hours} 小时" else "${hours} 小时 ${minutes} 分钟"
            }
        }
    }
}
