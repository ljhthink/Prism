package io.prism.qq

/**
 * 应答结果的**三态判定**（guardrail/ac-verifier **D-01**）。
 *
 * **缺陷背景**：原实现用"是否以 `错误`/`⚠️` 开头"二分成功与失败，而应答链路的返回值里
 * 大量是**跳过**语义（`跳过：未读取到会话消息`、`跳过：该消息已回应过`、`跳过（节流）：…`）。
 * 于是真机日志出现「双向对话应答：成功」但 NapCat 侧**没有任何出站消息** —— 这既污染
 * US-3109 AC-1 的"送达证据"，也违反 US-3115 AC-6 要求的"状态如实可见"。
 *
 * **为什么必须显式三态而不是猜前缀**：跳过的原因与成功在同一字符串通道里传递，
 * 靠 `startsWith("跳过")` 判断同样脆弱（文案会改）。故由应答器**显式返回结构化状态**，
 * 本对象只负责"如何呈现"，不再解析自由文本。
 */
object QqReplyOutcome {

    /** 应答结果三态。 */
    enum class Kind {
        /** 已实际发出（含"已发出但未能确认"——同样产出了出站动作）。 */
        SENT,

        /** 明确的失败（内容/模型/发送被拒）。 */
        FAILED,

        /** 未发送：没有新消息 / 已回应过 / 节流跳过 / 环境不可用等。 */
        SKIPPED
    }

    /**
     * @param sent 应答链路是否真的调用了发送（由调用方显式告知）
     * @param skippedReason 非空表示"决定不发送"的原因（`SENT` 时必须为空）
     * @param errorResult 发送返回的错误文案（`null`=成功）
     */
    fun of(sent: Boolean, skippedReason: String?, errorResult: String?): Kind = when {
        !sent -> Kind.SKIPPED
        errorResult == null -> Kind.SENT
        QqFailureClassifier.isUnconfirmedDelivery(QqFailureClassifier.classify(errorResult)) -> Kind.SENT
        else -> Kind.FAILED
    }

    /** 日志用短标签（不含正文/联系人）。 */
    fun label(kind: Kind): String = when (kind) {
        Kind.SENT -> "成功"
        Kind.FAILED -> "失败"
        Kind.SKIPPED -> "跳过"
    }

    /**
     * 应答链路的结构化返回（**替代"裸字符串 + 前缀判定"**）。
     *
     * @param kind 三态
     * @param message 面向工具/日志的可读文案（跳过原因或发送结果；**已脱敏**）
     */
    data class ReplyResult(val kind: Kind, val message: String) {
        val logLine: String
            get() = describe(kind, if (kind == Kind.SENT) "" else message.take(80))
    }

    /** 便捷构造：未发送（跳过）。 */
    fun skipped(reason: String): ReplyResult = ReplyResult(Kind.SKIPPED, reason)

    /** 便捷构造：已发送（含未确认）。 */
    fun sent(message: String): ReplyResult = ReplyResult(Kind.SENT, message)

    /** 便捷构造：失败。 */
    fun failed(message: String): ReplyResult = ReplyResult(Kind.FAILED, message)

    /**
     * 日志行的完整描述。
     *
     * @param kind 三态
     * @param reason 跳过原因或失败类别（**已脱敏**，调用方保证不含正文/联系人）
     */
    fun describe(kind: Kind, reason: String = ""): String = buildString {
        append("双向对话应答：").append(label(kind))
        if (kind != Kind.SENT && reason.isNotBlank()) append("（").append(reason).append("）")
    }
}
