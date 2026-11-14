package io.prism.qq

import io.prism.qq.channel.QqMessage

/**
 * 断连补偿（catch-up）判据（2026-11-02 新增；ADR-071 / PRD US-3129）。
 *
 * **背景**（真机取证）：WS 只转发实时事件，协议端不缓存"没有客户端在场"的消息。
 * 2026-11-02 19:49 Termux 被杀 → 20:23 用户发的「在吗」在 21:18 链恢复后仍然没有任何入口
 * 把它找回来；而 00:41 的消息直到 18:22 才被回复（延迟 17.7 小时）——两个极端都是
 * "没有时间窗概念"的表现。
 *
 * **策略**：
 * - 重连后拉最近 [MAX_FETCH] 条历史，只补 **入站 + 未回复 + 落在窗口内** 的消息；
 * - 窗口默认 [DEFAULT_WINDOW_MS]（60 分钟），超窗只记诊断、**不回复**（避免"隔夜补刀"）；
 * - **窗口内命中的消息一次性全部入队**（不在选择阶段截断）：选择阶段截断 + 水位前移
 *   会永久丢掉第 N+1 条以后的消息（guardrail R-02，与本功能目标相反）；
 *   真正的限速发生在**处理阶段**（每轮最多 5 条 + 既有 3s 间隔/60s 窗口节流）；
 * - `lastSeenAtMs` 为 0（首次运行）时不补发，直接把水位推到当前时间。
 *
 * 纯函数：不依赖 Android/网络，便于 JVM 单测（guardrail L-07）。
 */
object QqCatchUp {

    /** 补发时间窗（60 分钟）：断链一小时内补回，更久的不再回复。 */
    const val DEFAULT_WINDOW_MS: Long = 60 * 60 * 1000L

    /** 一次最多拉取多少条历史（覆盖窗口内密集消息）；也是单轮补发上限。 */
    const val MAX_FETCH: Int = 100

    /**
     * 选择需要补处理的消息（时间升序）。
     *
     * @param history 协议端历史（任意顺序）
     * @param lastSeenAtMs 已处理水位；<=0 表示首次运行
     * @param nowMs 当前时刻
     * @param knownIds 已在收件箱/已回复的 messageId 集合（防重复）
     * @param windowMs 补发窗口
     * @param maxBatch 单轮上限；默认取 [MAX_FETCH]（= 历史拉取条数，等价于"窗口内全部"）
     */
    fun selectMissed(
        history: List<QqMessage>,
        lastSeenAtMs: Long,
        nowMs: Long,
        knownIds: Set<String> = emptySet(),
        windowMs: Long = DEFAULT_WINDOW_MS,
        maxBatch: Int = MAX_FETCH
    ): List<QqMessage> {
        if (lastSeenAtMs <= 0L) return emptyList()
        return history.asSequence()
            .filter { it.incoming }
            .filter { it.timestampMs > lastSeenAtMs }
            .filter { it.timestampMs <= nowMs }
            .filter { nowMs - it.timestampMs <= windowMs }
            .filter { msg ->
                val id = msg.messageId
                id.isNullOrBlank() || id !in knownIds
            }
            .sortedBy { it.timestampMs }
            .take(maxBatch)
            .toList()
    }

    /**
     * 历史里最新一条的时间（含自己发出的消息）。
     *
     * 水位推进到"历史最新"，避免把窗口内已看过但未入队的消息无限重复拉取。
     */
    fun newWatermark(history: List<QqMessage>, current: Long): Long {
        val latest = history.maxOfOrNull { it.timestampMs } ?: 0L
        return maxOf(current, latest)
    }

    /**
     * **安全水位**（guardrail R2-03，2026-11-02）：只推进到"已经有着落"的消息。
     *
     * 旧实现直接把水位推到历史最新——历史只取最近 [MAX_FETCH] 条，若窗口内消息多于
     * 这个条数（或 `enqueue` 因容量上限淘汰了最旧项），越过的那一段就**永久丢失**。
     *
     * 规则：
     * - 只推进到本次**实际入队**（`enqueued` 为 true）或**已知已回复**的入站消息；
     * - 一条都没着落时**不推进**（下次重连会重新扫同一段，幂等由 message_id 保证）；
     * - 自己发出的消息不算，避免把自己的回话当成"已处理水位"跳过对方的接续消息。
     */
    fun advanceWatermark(
        history: List<QqMessage>,
        current: Long,
        enqueued: Set<String>,
        knownIds: Set<String> = emptySet()
    ): Long {
        var best = current
        // guardrail R3-01：水位**绝不能跨过任何一条没有着落的消息**。
        // 历史接口只返回最近 N 条，若窗口内消息多于 N，更早的那段拿不到；
        // 此时若把水位推到"已着落的最新"，越过的那段会永久丢失。
        // 因此：先把候选推进到"最早已着落点"，再被"最早未着落点 - 1"封顶。
        var earliestUnaccounted = Long.MAX_VALUE
        history.forEach { msg ->
            if (!msg.incoming) return@forEach
            val id = msg.messageId
            val accounted = (id != null && (id in enqueued || id in knownIds)) ||
                (id == null && msg.timestampMs <= current)
            if (accounted) {
                if (msg.timestampMs > best) best = msg.timestampMs
            } else if (msg.timestampMs > current && msg.timestampMs < earliestUnaccounted) {
                earliestUnaccounted = msg.timestampMs
            }
        }
        if (earliestUnaccounted != Long.MAX_VALUE && best >= earliestUnaccounted) {
            // 退到"未着落点之前"（-1ms 保证下次仍用严格 `>` 能重新取到它）
            best = maxOf(current, earliestUnaccounted - 1)
        }
        return best
    }

    /** 超窗被跳过的条数（诊断用，不含正文）。 */
    fun countExpired(
        history: List<QqMessage>,
        lastSeenAtMs: Long,
        nowMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS
    ): Int = history.count {
        it.incoming && it.timestampMs > lastSeenAtMs && nowMs - it.timestampMs > windowMs
    }
}
