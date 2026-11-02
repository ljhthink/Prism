package io.prism.qq

/**
 * 电源策略的**纯判定逻辑**（2026-11-01；US-3128）。
 *
 * ## 背景（真机取证）
 *
 * 2026-10-31 22:32 协议端被 HyperOS `AutoPowerKill` 杀死，静默 19h42m。事后复核发现
 * `dumpsys deviceidle whitelist | grep -ic 'termux|prism'` = **0**——部署手册 §六 要求的
 * 「省电无限制」**从未真正生效**，而当时没有任何判据能发现这件事。
 *
 * 本对象把「设置到底生效没有」变成纯函数判定，使它可以被单测穷举；真正执行命令的是
 * [io.prism.phonecontrol.bg.ShizukuShell]（shell 身份）。
 *
 * ## 必须如实告知的上限
 *
 * Doze 白名单只豁免 **Doze / App Standby**，**不豁免** HyperOS 的
 * `AutoPowerKill` / `SwipeUpClean` / `OneKeyClean`（dontkillmyapp：无 API、无文档）。
 * 因此这里的修复只能"降低概率"，文案**不得**承诺"不会再掉"。
 */
object QqPowerPolicy {

    /** AOSP App Standby 桶：被 Doze 白名单强制豁免。 */
    const val BUCKET_EXEMPTED = 5

    /** AOSP App Standby 桶：ACTIVE（前台级）。 */
    const val BUCKET_ACTIVE = 10

    /** 需要保障常驻的两个包：协议端（Termux）与 Prism 自身。 */
    const val TERMUX_PACKAGE = "com.termux"

    /**
     * 判断包是否在 `dumpsys deviceidle whitelist` 输出中。
     *
     * 输出形如（每行一条）：
     * ```
     * system,com.android.shell,2000
     * user,io.prism,10165
     * ```
     * 因此按 `,` 切分后做**精确 token 比对**，不能用 `contains`——`com.termux` 是
     * `com.termux.boot` 的前缀，`contains` 会产生假阳性（正是"以为设好了其实没设"的同类错误）。
     *
     * @param dump [io.prism.phonecontrol.bg.ShizukuShell.deviceIdleWhitelistDump] 的原始输出
     * @return true=确实在白名单里；dump 为 null（未就绪/失败）时返回 false（保守）
     */
    fun isWhitelisted(dump: String?, packageName: String): Boolean {
        if (dump.isNullOrBlank() || packageName.isBlank()) return false
        return dump.lineSequence().any { line ->
            line.split(',').any { it.trim() == packageName }
        }
    }

    /**
     * 该包是否**还需要修复**。
     *
     * 判据（任一成立即需要修复）：
     * - 不在 Doze 白名单；
     * - 白名单在，但待机桶不是 EXEMPTED（少数 ROM 白名单与桶不同步）。
     *
     * @param whitelisted 白名单判定结果
     * @param bucket 待机桶（null=读取失败，此时**不**据此判失败——读失败不等于配置错）
     */
    fun needsRepair(whitelisted: Boolean, bucket: Int?): Boolean {
        if (!whitelisted) return true
        return bucket != null && bucket != BUCKET_EXEMPTED
    }

    // ==================== MIUI/HyperOS 自启动（被唤醒）白名单 ====================

    /**
     * MIUI 自定义 AppOp 编号：**自启动 / 允许被其它应用唤醒**。
     *
     * 2026-11-02 真机实证（这是「协议端起不来」的**真正根因**之一）：
     * ```
     * W/WakePathChecker: MIUILOG-AutoStart, Service/Provider/Broadcast Reject
     *   caller= io.prism callee= com.termux classname=com.termux.app.RunCommandService
     *   action=com.termux.RUN_COMMAND wakeType=8
     * ```
     * MIUI 默认禁止应用被**其它应用**唤醒（注意校验的是 **callee**，即 Termux），
     * 于是 Prism 发的 `startService` 被系统在框架层直接丢弃——**不抛异常**，
     * 表现为"Intent 提交成功但什么都没发生"。
     *
     * 把该 op 设为 `allow` 后拦截消失（实测）。非 MIUI 机型 dump 里不会有 MIUIOP 行，
     * 此时本项判定为"不适用"，不得报失败。
     */
    const val MIUI_AUTO_START_OP = 10008

    /**
     * 从 `cmd appops get <pkg>` 的输出里解析 MIUI 自启动 op 的当前模式。
     *
     * 为什么不能用 `cmd appops get <pkg> 10008`：该语法不认识 MIUI 自定义 op，
     * 一律返回 `No operations.`（真机实测）。只能全量 dump 后按行解析。
     *
     * **关键语义（2026-11-02 真机实测，极易写错）**：把该 op 设为 `allow` 之后，
     * 它会**从 dump 里消失**（appops 只列非默认项，而该 op 在 MIUI 上默认即 allow）。
     * 因此：
     * - dump 里有 `MIUIOP(` 行但**没有** 10008 → 说明是 MIUI 且当前为**允许**；
     * - dump 里完全没有 `MIUIOP(` 行 → 非 MIUI，不适用（返回 null）。
     *
     * @return `allow` / `ignore` / `ask` / `deny`；非 MIUI 或不适用返回 null
     */
    fun parseMiuiAutoStartMode(dump: String?): String? {
        if (dump.isNullOrBlank()) return null
        // 非 MIUI：输出里根本没有 MIUIOP 条目
        if (!dump.contains(MIUI_OP_PREFIX)) return null
        val regex = Regex("""MIUIOP\($MIUI_AUTO_START_OP\):\s*(\w+)""")
        return regex.find(dump)?.groupValues?.getOrNull(1)?.lowercase() ?: "allow"
    }

    private const val MIUI_OP_PREFIX = "MIUIOP("

    /**
     * 该包是否**还需要修自启动**。
     *
     * @param mode [parseMiuiAutoStartMode] 的结果
     * @return true=明确被拦（非 allow）才修；null（非 MIUI/读不到）视为不适用，返回 false
     */
    fun needsAutoStartRepair(mode: String?): Boolean = mode != null && mode != "allow"

    /** 自启动状态的一行文案（不适用时返回 null，避免在非 MIUI 机型上显示噪声）。 */
    fun describeAutoStart(mode: String?): String? = when (mode) {
        null -> null
        "allow" -> "自启动（可被唤醒）：已允许"
        else -> "自启动（可被唤醒）：被拦截（$mode）——协议端会被系统丢弃启动请求"
    }

    /** 给用户看的一行状态（如实区分「已生效 / 未生效 / 未知」）。 */
    fun describe(whitelisted: Boolean, bucket: Int?, readFailed: Boolean): String = when {
        readFailed -> "无法确认（Shizuku 未就绪或读取失败）"
        !whitelisted -> "未生效：不在系统电池优化白名单"
        bucket != null && bucket != BUCKET_EXEMPTED ->
            "部分生效：已在白名单，但待机桶为 $bucket（应为 $BUCKET_EXEMPTED）"
        else -> "已生效：在电池优化白名单内"
    }
}
