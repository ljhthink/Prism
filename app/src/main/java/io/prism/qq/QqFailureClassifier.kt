package io.prism.qq

/**
 * 主动消息/自动回复的**失败分类**（ADR-063 修订 2026-10-25；PRD US-3108 AC-6）。
 *
 * **问题**：原实现把所有失败一律 `recordFailure`，连续 3 次即熔断停摆。但"屏幕熄灭"
 * "显示面消失"或"协议端离线"都是**环境/通道故障**，不是账号或内容问题；把它们计入
 * 熔断会让功能在用户睡觉时被自己关掉，且 UI 只说"已熔断"，无法定位。
 *
 * **原则**（对齐 ADR-064「离线不熔断」）：只有**内容/发送被拒**与**模型调用失败**计入熔断；
 * 通道、显示、屏幕、目标解析类失败一律**不计**，但必须留痕并可在 UI 查看。
 *
 * 纯函数（字符串 → 类别），不依赖 Android。
 */
object QqFailureClassifier {

    /** 失败类别。 */
    enum class Kind {
        /** 通道不可用（协议端离线/未配置；无障碍不可用）。 */
        CHANNEL_UNAVAILABLE,

        /** 执行面不可用（虚拟屏未创建/已消失/无可用画面/执行面不可用）。 */
        DISPLAY_UNAVAILABLE,

        /** 屏幕熄灭等系统输入策略导致的阻塞。 */
        SCREEN_OFF_BLOCKED,

        /** 目标联系人无法解析/不存在。 */
        TARGET_NOT_FOUND,

        /** 模型调用失败（无 Provider / Key 不可用 / 生成失败）。 */
        LLM_FAILED,

        /** 发送被明确拒绝/失败（协议端 retcode、UI 链路发送失败）。 */
        SEND_REJECTED,

        /**
         * **已发送但未能确认送达**（2026-10-25 真机实证）。
         *
         * 无障碍链路点完「发送」后要靠读聊天记录气泡二次确认；隐藏执行面/慢刷新时确认
         * 会假阴性——真机上消息**已经送达**，日志却报"未能确认"。
         * 语义：**可能已送达**。既不计熔断，也**绝不能重试**（重发会制造重复消息）。
         */
        SEND_UNCONFIRMED,

        /** 无法归类（保守：不计入熔断，但需留痕）。 */
        UNKNOWN
    }

    /**
     * 把发送器/生成器返回的可读文本归类。
     *
     * 判定顺序有讲究：先判**环境类**（屏幕 → 显示 → 通道 → 目标），最后才判模型/发送；
     * 因为错误文案里可能同时出现"QQ""发送"等通用词，先匹配具体前缀更稳。
     */
    fun classify(result: String): Kind {
        val text = result.trim()
        if (text.isEmpty()) return Kind.UNKNOWN
        // guardrail G-r3-06：`SEND_UNCONFIRMED` 必须用**固定前缀**匹配。
        // 原因：`OneBotQqChannel` 的错误文案会**插值协议端返回的 message**，若用 contains，
        // 协议端消息里恰好含这些子串时会把"确定拒绝（retcode≥0）"降级成"未确认"——
        // 既不重试也不计熔断，熔断永远不会触发。
        if (UNCONFIRMED_PREFIXES.any { text.startsWith(it) }) return Kind.SEND_UNCONFIRMED
        return when {
            text.contains("屏幕已熄灭") || text.contains("屏幕熄灭") || text.contains("熄屏") ->
                Kind.SCREEN_OFF_BLOCKED

            text.contains("虚拟屏") || text.contains("执行面") || text.contains("暂无可用画面") ||
                text.contains("后台虚拟屏") -> Kind.DISPLAY_UNAVAILABLE

            text.contains("无可用 QQ 通道") || text.contains("协议端离线") ||
                text.contains("未配置 access_token") || text.contains("协议端未配置") ||
                text.contains("无障碍服务未开启") || text.contains("无障碍不可用") ->
                Kind.CHANNEL_UNAVAILABLE

            text.contains("未能") && text.contains("找到") ||
                text.contains("未解析到") || text.contains("好友列表") ||
                text.contains("联系人") -> Kind.TARGET_NOT_FOUND

            text.contains("文案生成失败") || text.contains("回复生成失败") ||
                text.contains("无激活的 LLM Provider") || text.contains("API Key") ->
                Kind.LLM_FAILED

            text.contains("发送失败") || text.contains("发送被拒绝") || text.contains("retcode") ||
                text.contains("未找到「发送」") || text.contains("输入失败") ->
                Kind.SEND_REJECTED

            else -> Kind.UNKNOWN
        }
    }

    /**
     * 该类别是否计入「连续失败 → 熔断」。
     *
     * 只有 [Kind.LLM_FAILED] 与 [Kind.SEND_REJECTED] 计入；环境类故障会自行恢复
     * （用户开屏、协议端重连、显示面重建），把它们计入熔断等于"睡觉时把功能关掉"。
     */
    fun countsTowardCircuit(kind: Kind): Boolean =
        kind == Kind.LLM_FAILED || kind == Kind.SEND_REJECTED

    /**
     * 失败是否应被视为"本次跳过"（不计失败、不影响计划）。
     */
    fun isTransientEnvironmentIssue(kind: Kind): Boolean =
        kind == Kind.SCREEN_OFF_BLOCKED ||
            kind == Kind.DISPLAY_UNAVAILABLE ||
            kind == Kind.CHANNEL_UNAVAILABLE

    /**
     * **是否允许重试**（2026-10-25 真机实证的反向约束；guardrail **G-r3-11** 收窄）。
     *
     * 只有**环境类**失败值得重试（用户开屏、通道重连、执行面重建）。
     * 其余一律不重试：
     * - [Kind.SEND_UNCONFIRMED]：消息很可能已送达，重试即重复发送；
     * - [Kind.SEND_REJECTED]：明确被拒，重发同样的内容没有意义；
     * - [Kind.LLM_FAILED] / [Kind.TARGET_NOT_FOUND] / [Kind.UNKNOWN]：重试只会**再调一次
     *   LLM 生成文案**（真机上单次 ~16s），既烧额度又可能连续产生多条文案，收益极低。
     */
    fun isRetryable(kind: Kind): Boolean = isTransientEnvironmentIssue(kind)

    /** 是否属于"可能已送达"（不可重试、不可熔断）。 */
    fun isUnconfirmedDelivery(kind: Kind): Boolean = kind == Kind.SEND_UNCONFIRMED

    /**
     * 「可能已送达」的固定前缀（见 [classify] 的 G-r3-06 说明：必须前缀匹配）。
     *
     * **单一来源**（guardrail G-r4-05）：直接引用生产者侧常量，避免"改文案 → 静默失配 →
     * 变回重复发送"。新增"可能已送达"文案时必须同时在此登记。
     *
     * **⚠️ 依赖 `const` 的编译期内联**（guardrail G-r5-05）：这两个引用必须是 `const val`。
     * 若将来改成普通 `val` / `@JvmField`，本对象初始化时就要加载 `QqLocalToolExecutor` 类，
     * 在 Robolectric/裁剪环境下可能改变初始化顺序（甚至触发静态初始化失败），
     * 那时应改为在 `classify` 内联字面量或改为惰性读取。
     */
    private val UNCONFIRMED_PREFIXES = listOf(
        // QqLocalToolExecutor.sendMessage：点完发送但读不回气泡（真机实证会误报）
        io.prism.qq.QqLocalToolExecutor.MSG_SEND_UNCONFIRMED_PREFIX,
        // OneBotQqChannel.sendVoice：retcode=-1（传输异常），消息可能已送达
        io.prism.qq.QqLocalToolExecutor.VOICE_SEND_UNCONFIRMED_PREFIX
    )
}
