package io.prism.qq

/**
 * 无障碍兜底通道的**轻量新消息探针**（PRD US-3111 AC-4；guardrail H-04）。
 *
 * **为什么需要**：无障碍模式只能通过"建副屏 + 拉起 QQ + 读 UI"检测新消息；若每 90s
 * 无条件执行，就会复现 DEF-118（无消息也建屏）。本探针先用 shell `dumpsys notification`
 * 取通知摘要并只保留 QQ 相关行做散列——**摘要变化才允许建屏**。
 *
 * **诚实降级**：拿不到摘要/摘要中无 QQ 记录时返回 null（未知）。调用方在未知时最多
 * 连续 [FORCE_POLL_AFTER_UNKNOWN] 次后强制放行一次，避免通知被关闭时永久失聪。
 *
 * **纯逻辑可测**：[evaluate] 只做字符串过滤与散列比对，不依赖 Android。
 */
class QqUnreadProbe(
    /** 读取通知摘要（生产：`ShizukuShell.notificationSnapshot()`）。 */
    private val snapshotProvider: suspend () -> String?,
    /** 判断一行是否属于目标应用（默认 QQ 包名；测试可注入）。 */
    private val relevance: (String) -> Boolean = { it.contains(QQ_PACKAGE) },
    /** 时钟（可注入以便测试最长静默兜底）。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 最长静默：超过该时长强制放行一次（防"启动前已有未读且此后无新通知"永久失聪，M-19）。 */
    private val maxSilenceMs: Long = MAX_SILENCE_MS
) {

    @Volatile
    private var lastRelevantHash: Int = 0

    private var unknownStreak: Int = 0

    /** 上次**实际放行**（true）的时刻；null=尚未初始化（guardrail L-29：首次调用才起算）。 */
    private var lastSignalAtMs: Long? = null

    /**
     * @return true=检测到变化（应建屏读取）；false=无变化（跳过，不建屏）；
     *   null=未知（首次/无 QQ 通知/读取失败）——调用方按 [FORCE_POLL_AFTER_UNKNOWN] 兜底
     */
    suspend fun hasNewActivity(): Boolean? {
        // M-19：最长静默兜底——即使通知不可读/无变化，也至少每 maxSilenceMs 读一次
        val now = clock()
        val last = lastSignalAtMs
        if (last == null) {
            // L-29：惰性初始化——服务晚于进程启动才首次轮询时，不得立即强制建屏一次
            lastSignalAtMs = now
        } else if (now - last >= maxSilenceMs) {
            lastSignalAtMs = now
            unknownStreak = 0
            return true
        }
        val snapshot = try {
            snapshotProvider()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val verdict = evaluate(snapshot)
        // M-19：只有**实际放行（true）**才刷新静默计时器。若 false 也刷新，
        // 生产环境每 90s 一次的调用会让 15 分钟永远到不了（存量未读被永久忽略）。
        if (verdict == true) lastSignalAtMs = now
        if (verdict == null) {
            unknownStreak++
            return if (unknownStreak >= FORCE_POLL_AFTER_UNKNOWN) {
                unknownStreak = 0
                true
            } else {
                null
            }
        }
        unknownStreak = 0
        return verdict
    }

    /**
     * 纯判定：对通知摘要中与目标应用相关的行做散列比对。
     *
     * @return true=与上次不同；false=相同；null=无有效摘要（无法判定）
     */
    fun evaluate(snapshot: String?): Boolean? {
        if (snapshot.isNullOrBlank()) return null
        val relevant = snapshot.lineSequence()
            .filter(relevance)
            .joinToString("\n")
        if (relevant.isBlank()) return null
        val hash = relevant.hashCode()
        val first = lastRelevantHash == 0
        val changed = !first && hash != lastRelevantHash
        lastRelevantHash = hash
        return if (first) null else changed
    }

    companion object {
        /** 目标应用包名（QQ 主/小号同包名；按通知摘要区分不了实例，故只作为"有变化"信号）。 */
        const val QQ_PACKAGE = "com.tencent.mobileqq"

        /** 连续未知达到该次数后强制放行一次（避免通知被关闭时永久失聪）。 */
        const val FORCE_POLL_AFTER_UNKNOWN = 5

        /** 最长静默（毫秒）：无论摘要是否变化，至少每 15 分钟读一次（M-19）。 */
        const val MAX_SILENCE_MS = 15 * 60 * 1000L
    }
}
