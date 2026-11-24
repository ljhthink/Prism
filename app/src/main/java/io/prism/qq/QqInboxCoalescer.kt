package io.prism.qq

/**
 * 入站消息的**接收侧合并窗口**（2026-11-22 新增；ADR-080）。
 *
 * ## 为什么需要（真机证据）
 *
 * 2026-11-22 真机日志：
 * ```
 * 22:23:39 收到「还没呢，还在忙」→ 批=1 → 生成 → 22:23:55 发出回复
 * 22:23:42 收到 [表情包] → 批=1 → 判「跳过（节流：间隔过短）」
 * 22:24:42（47s 后）重试 worker 重新领取 → 又生成一次 → 22:25:06 单独回复表情包
 * ```
 * 用户「发了一条问候 + 一个表情包，LLM 两条都回」正是这个序列：
 * **两条消息被拆成两次生成**，因为事件到达即刻 `claimDue`，批里只有一条。
 *
 * ## 设计（与业界 coalescer 一致）
 *
 * - **静默窗** [QUIET_MS]：从"最后一条入队"起算，再等它一小会儿——真人发完一句后
 *   隔一两秒补个表情/补一句，中间是不回应的；
 * - **硬上限** [MAX_WAIT_MS]：从"最早未处理条目的到达时间"起算。用户一直不停手也必须回，
 *   否则延迟无上限；
 * - 两条都满足才继续等；任一满足即可取件。
 *
 * ## 为什么是纯函数
 *
 * 取件时机必须可断言（`ac-verifier` 要求边界可测），且不能把 `delay` 藏进对象状态里——
 * 真机踩过的坑（arbora coalescer issue）：flush 时 cancel 掉自身 timer 会把整轮回复杀掉，
 * 表现为"消息收到了、没有回复"。这里由调用方 `delay`，本对象只给毫秒数。
 *
 * 纯函数，便于 JVM 单测。
 */
object QqInboxCoalescer {

    /** 静默窗：最后一条入队后至少再等这么久（覆盖"发完补一句/补个表情"）。 */
    const val QUIET_MS = 1_200L

    /** 硬上限：最早那条已经等了这么久，就立刻取件（用户不停手也必须回）。 */
    const val MAX_WAIT_MS = 4_000L

    /**
     * 单轮合并循环的总等待上限（**仅作循环出口保护**，不是取件判据）。
     *
     * guardrail L-03：它必须 > [MAX_WAIT_MS]，否则硬上限永远走不到；
     * 同时它不能出现在 [waitMs] 的返回值钳制里（那会让调用方误以为硬上限是 6s）。
     */
    const val MAX_LOOP_WAIT_MS = 6_000L

    /**
     * 现在该不该继续等？
     *
     * @param nowMs 当前时刻
     * @param enqueuedAtMs 待处理条目的入队时间（升序；空 = 没东西可处理，返回 0）
     * @return 还需等待的毫秒数；0 = 立刻取件
     */
    fun waitMs(nowMs: Long, enqueuedAtMs: List<Long>): Long {
        if (enqueuedAtMs.isEmpty()) return 0L
        val oldest = enqueuedAtMs.min()
        val newest = enqueuedAtMs.max()
        // 硬上限先判：等太久就直接走
        val untilMax = oldest + MAX_WAIT_MS - nowMs
        if (untilMax <= 0L) return 0L
        // 静默窗：等到"最后一条 + QUIET_MS"，但不超过硬上限
        val untilQuiet = newest + QUIET_MS - nowMs
        val wait = if (untilQuiet > 0L) minOf(untilQuiet, untilMax) else 0L
        // 结果天然 ≤ MAX_WAIT_MS < MAX_LOOP_WAIT_MS，无需再钳（guardrail L-03）
        return wait
    }

    /** 诊断用：本次等待的原因（只记类别）。 */
    fun reasonOf(nowMs: Long, enqueuedAtMs: List<Long>): String? {
        if (enqueuedAtMs.isEmpty()) return null
        val oldest = enqueuedAtMs.min()
        val newest = enqueuedAtMs.max()
        val untilMax = oldest + MAX_WAIT_MS - nowMs
        if (untilMax <= 0L) return null
        val untilQuiet = newest + QUIET_MS - nowMs
        return when {
            untilQuiet > 0L && untilQuiet <= untilMax -> "静默窗"
            untilMax > 0L -> "近硬上限"
            else -> null
        }
    }
}
