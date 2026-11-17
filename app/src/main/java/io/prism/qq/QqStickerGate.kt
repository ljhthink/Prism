package io.prism.qq

/**
 * 表情**发送闸门**（2026-11-16 新增；ADR-077）。
 *
 * ## 为什么需要
 *
 * 现有实现在"没有匹配情绪"时按 [QqStickerStore.USER_STICKER_RATIO] 随机发一张，
 * 但**没有任何刹车**：同一张图可能连发、一小时能发几十张。真机上随后就是"刷图感"
 * ——这是拟人化的反效果。
 *
 * ## 不自己发明
 *
 * 参考 [`dsh-qq-bot` 0.13.0 的 sticker gate](https://cdn.jsdelivr.net/npm/@sputnikzaychik/dsh-qq-bot@0.13.0/CHANGELOG.md)
 * 已生产验证的一组刹车：
 *
 * | 它的配置 | 语义 | 我们的对应 |
 * | --- | --- | --- |
 * | `sticker.chance`（0.3） | 模型自己决定配图时按概率真发 | 复用既有 `stickerChancePercent` |
 * | `sticker.maxPerHour`（6） | 每小时上限，**连 force 也受限**（防刷屏） | [Config.maxPerHour] |
 * | `sticker.dupTTLHours`（24） | 同一张图 TTL 内不重复（`force` 可绕过） | [Config.duplicateTtlHours] |
 * | `force` 参数 | 用户**点名**要表情时绕过 chance/dup | [Decision.Force] |
 *
 * ## 设计取舍
 *
 * - **纯函数**：输入当前状态与候选，输出"发或不发"。便于 JVM 单测与真机对账。
 * - **每小时上限不受 force 影响**：这是防刷屏的最后一道闸，用户点名也不该让它突破。
 * - **用户明确要求表情（意图/指令）时**：视为 force，跳过 chance 与 dup，但仍受小时上限约束。
 */
object QqStickerGate {

    /**
     * @param maxPerHour 每小时最多发几张（0=不限；默认 6）
     * @param duplicateTtlHours 同图去重 TTL（小时；0=不去重；默认 24）
     * @param chancePercent 无明确要求时的发送概率（复用配置项）
     */
    data class Config(
        val maxPerHour: Int = DEFAULT_MAX_PER_HOUR,
        val duplicateTtlHours: Int = DEFAULT_DUPLICATE_TTL_HOURS,
        val chancePercent: Int = DEFAULT_CHANCE_PERCENT
    )

    /** 决策结果。 */
    enum class Decision {
        /** 允许发送。 */
        Allow,

        /** 用户点名/明确要表情：跳过 chance 与同图去重（仍受小时上限）。 */
        Force,

        /** 拒绝：本小时额度用尽。 */
        DenyHourlyCap,

        /** 拒绝：这张图还在去重 TTL 内。 */
        DenyDuplicate,

        /** 拒绝：概率未命中。 */
        DenyChance
    }

    /**
     * 是否允许发送。
     *
     * @param stickerId 候选表情 id（内置 face 用 `face:<id>`，学到的 mface 用 `mface:<id>`）
     * @param explicitlyRequested 用户是否明确要表情（意图识别命中 / 模型给了指令）
     * @param nowMs 当前时间
     * @param sendsInLastHour 最近一小时已发数
     * @param sentWithinTtl 该图是否仍在 TTL 内
     * @param random 随机源（测试注入）
     */
    fun decide(
        stickerId: String,
        explicitlyRequested: Boolean,
        nowMs: Long,
        sendsInLastHour: Int,
        sentWithinTtl: Boolean,
        config: Config = Config(),
        random: kotlin.random.Random = kotlin.random.Random.Default
    ): Decision {
        // 每小时上限：**force 也不能突破**（防刷屏的最后一道闸）
        if (config.maxPerHour > 0 && sendsInLastHour >= config.maxPerHour) {
            return Decision.DenyHourlyCap
        }
        if (explicitlyRequested) {
            // 用户点名：跳过概率与去重（"就要这张"是用户的显式意图）
            return Decision.Force
        }
        if (config.duplicateTtlHours > 0 && sentWithinTtl) {
            return Decision.DenyDuplicate
        }
        val chance = config.chancePercent.coerceIn(0, 100)
        if (chance <= 0) return Decision.DenyChance
        if (chance >= 100) return Decision.Allow
        return if (random.nextInt(100) < chance) Decision.Allow else Decision.DenyChance
    }

    /** 决策是否放行（[Decision.Allow] / [Decision.Force]）。 */
    fun allows(decision: Decision): Boolean =
        decision == Decision.Allow || decision == Decision.Force

    /** 该候选是否属于"用户点名"路径（供日志标注来源）。 */
    fun isForced(decision: Decision): Boolean = decision == Decision.Force

    const val DEFAULT_MAX_PER_HOUR = 6
    const val DEFAULT_DUPLICATE_TTL_HOURS = 24
    const val DEFAULT_CHANCE_PERCENT = 25

    /** 内置 face 的闸门 id 前缀（与用户表情 id 区分，避免撞键）。 */
    fun faceKey(faceId: String): String = "face:$faceId"

    /** 学到的 mface 的闸门 id 前缀。 */
    fun mfaceKey(emojiId: String): String = "mface:$emojiId"
}
