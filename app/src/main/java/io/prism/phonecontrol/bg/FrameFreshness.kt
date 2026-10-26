package io.prism.phonecontrol.bg

/**
 * 帧新鲜度的**纯逻辑**（2026-10-25 帧健康整改；PRD US-3114 AC-7）。
 *
 * 抽成独立对象的原因：判定逻辑（帧龄计算、阈值比较）是唯一需要密集单测的部分，
 * 而 [DisplayFrame] 持有 Bitmap（JVM 单测无图形栈）。业务代码与测试共用本对象，
 * 避免"测试另写一套阈值"的漂移。
 */
object FrameFreshness {

    /** 帧龄超过该值即视为「画面已冻结」（隐藏虚拟屏随整机熄屏停更属已知现象）。 */
    const val STALE_MS: Long = 3_000L

    /**
     * 帧龄（毫秒）。
     *
     * @param capturedAtMs 帧在系统侧产生的时刻；<=0 表示"无元信息"→ 返回 [Long.MAX_VALUE]
     *   （fail-closed：拿不到产生时刻就不能把缓存帧当实时画面）
     * @param nowMs 当前时刻（可注入以便测试）
     */
    fun ageMs(capturedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Long =
        if (capturedAtMs <= 0L) Long.MAX_VALUE else (nowMs - capturedAtMs).coerceAtLeast(0L)

    /** 帧是否新鲜（`0 <= age < maxAgeMs`）。无帧/无元信息一律 false。 */
    fun isFresh(ageMs: Long, maxAgeMs: Long = STALE_MS): Boolean =
        ageMs in 0 until maxAgeMs

    /**
     * 「帧龄偏大」是否**值得怀疑为冻结**（ac-verifier **D-02** 的判定依据）。
     *
     * **为什么不能只看帧龄**：隐藏虚拟屏只在目标应用**重绘**时产生新帧。真实场景里
     * "打开一个静态页面就不动"极为常见——此时帧龄持续增长，但画面**完全有效**。
     * 若仅凭帧龄就 `nudge → recoverDisplay`，而重建是 `force-stop` 重启目标应用，
     * 等于"AI 看一眼静态页面就把用户的 QQ 重启一遍"（真机实测 10 秒就超过阈值）。
     *
     * **因此只有环境本身可疑时才算冻结**：
     * - 设备不可交互（熄屏/锁屏），或
     * - 执行面 display 处于 OFF/UNKNOWN（隐藏屏随整机熄屏停更，本轮修复的核心场景）。
     *
     * 屏幕亮着且 display 正常时的"旧帧"是**静态画面的正常表现**，应直接使用。
     *
     * @param ageMs 帧龄
     * @param interactive 设备是否可交互（`PowerManager.isInteractive`）
     * @param displayOn 执行面 display 是否处于可用状态（非 OFF/UNKNOWN）
     */
    fun isStallSuspicious(
        ageMs: Long,
        interactive: Boolean,
        displayOn: Boolean
    ): Boolean = !isFresh(ageMs) && (!interactive || !displayOn)

    /**
     * 静态画面是否应提示"可能已冻结"（Watch 用）。
     *
     * 与 [isStallSuspicious] 同源：屏幕亮着时**不提示**（否则正常静态页面会一直挂着
     * "可能已冻结"，反而误导用户）。
     */
    fun shouldHintStale(ageMs: Long, interactive: Boolean, displayOn: Boolean): Boolean =
        isStallSuspicious(ageMs, interactive, displayOn)
}
