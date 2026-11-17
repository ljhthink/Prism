package io.prism.qq

import io.prism.qq.channel.QqMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Golden Master 对比**（ac-verifier 补充；`rules/08 §21.5` Bug 修复场景强制）。
 *
 * 场景来源：`docs/reports/2026-11-15-qq-context-coherence-and-persona-plan.md` §2.1
 * 真机 21:43–21:53 的错位序列（回复落后 1 条、60–85s）。
 *
 * **诚实边界**：本文件用**逻辑复算**（同一份输入 + 两种策略的确定性模拟）对比
 * 「修复前（逐条 claim + 60s 挂起）」与「修复后（批合并 + 4s 短重试）」的**输出形态**。
 * 它不是真机回放，也不能替代真机验收——它只证明"行为差异恰好落在预期的那一处"。
 *
 * 判据（仅预期行为变化处允许差异）：
 * | 维度 | 修复前 | 修复后 | 允许差异 |
 * | --- | --- | --- | --- |
 * | 每次连发产生的生成次数 | 3 | 1 | ✅ 预期 |
 * | 回复目标 | m1,m2,m3（前两条答旧消息） | m3 | ✅ 预期 |
 * | 节流挂起时长 | 60_000ms | 4_000ms | ✅ 预期 |
 * | 历史渲染格式（`我/对方：` + mediaNote） | 同 | 同 | ❌ 不得有差异 |
 * | 批内条目终结 | 逐条 DONE | 整批 DONE | ❌ 不得悬空 |
 */
class QqAcceptanceGoldenMasterTest {

    private data class Arrival(val id: String, val text: String, val atMs: Long)

    /** 真机 §2.1 的时序结构（内容用中性占位，避免复制用户会话正文）。 */
    private val burst = listOf(
        Arrival("m1", "第一条", 0L),
        Arrival("m2", "第二条", 2_000L),
        Arrival("m3", "第三条", 4_000L)
    )

    private fun msg(a: Arrival) = QqMessage(a.id, true, a.text, a.atMs)

    /** 修复前策略常量（逐条 claim + 统一 60s 挂起）。 */
    private val legacyClaimLimit = 1
    private val legacyHoldMs = 60_000L

    /** 修复后策略常量（来自生产代码，避免复制数值造成漂移）。 */
    private val newClaimLimit = QqInboxStore.BURST_LIMIT
    private val newHoldMs = QqInboxPolicy.THROTTLE_HOLD_MS

    private data class Outcome(
        val generations: Int,
        val replyTargets: List<String>,
        val pendingSeen: List<List<String>>,
        val settled: Set<String>
    )

    /**
     * 确定性模拟：同一批到达，按给定策略领取/去重/生成/终结。
     * 不模拟 3s 最小间隔的门控（两侧一致，不构成差异源）。
     */
    private fun simulate(claimLimit: Int): Outcome {
        val remaining = burst.toMutableList()
        var generations = 0
        val targets = mutableListOf<String>()
        val pendingSeen = mutableListOf<List<String>>()
        val settled = mutableSetOf<String>()
        while (remaining.isNotEmpty()) {
            val claimed = remaining.take(claimLimit)
            remaining.removeAll(claimed)
            val batch = claimed.filter { it.id !in settled }
            if (batch.isEmpty()) continue
            generations += 1
            targets += batch.last().id
            pendingSeen += batch.dropLast(1).map { it.id }
            settled += batch.map { it.id }
        }
        return Outcome(generations, targets, pendingSeen, settled)
    }

    @Test
    fun `连发 3 条 —— 修复后只生成 1 次且回复最后一条`() {
        val legacy = simulate(legacyClaimLimit)
        val fixed = simulate(newClaimLimit)

        assertEquals("修复前：逐条领取 → 3 次生成", 3, legacy.generations)
        assertEquals("修复前：回复目标是 m1,m2,m3（前两条答的是旧消息）", listOf("m1", "m2", "m3"), legacy.replyTargets)

        assertEquals("修复后：批合并 → 只生成 1 次", 1, fixed.generations)
        assertEquals("修复后：回复目标是最后一条 m3", listOf("m3"), fixed.replyTargets)
        assertEquals("修复后：m1/m2 作为 pending 交给提示词（不被逐条点答）", listOf(listOf("m1", "m2")), fixed.pendingSeen)
    }

    @Test
    fun `批内条目必须全部终结 —— 两种策略都不得悬空`() {
        listOf(legacyClaimLimit, newClaimLimit).forEach { limit ->
            val o = simulate(limit)
            assertEquals(
                "claimLimit=$limit 时批内条目必须全部落定（不得悬空到租约回收）",
                burst.map { it.id }.toSet(),
                o.settled
            )
        }
    }

    @Test
    fun `节流挂起时长 —— 唯一允许的差异，其余常量不得漂移`() {
        assertEquals("修复前统一 60s", 60_000L, legacyHoldMs)
        assertEquals("修复后 4s", 4_000L, newHoldMs)
        val reason = "节流：与上次回复间隔过短（防重复发送）"
        assertEquals("生产实现必须把该原因分流到 4s", 4_000L, QqInboxPolicy.holdMs(reason))
        assertEquals(
            "通道类原因的挂起时长**不得**被本轮改动影响",
            60_000L,
            QqInboxPolicy.holdMs("等待协议端事件")
        )
        assertEquals("批上限不得漂移（PRD AC-2 写死 4）", 4, newClaimLimit)
    }

    @Test
    fun `历史渲染格式不因本轮改动而变化（非预期差异为零）`() {
        // 修复前 QqContextBuilder.render 输出 `我/对方：text`；本轮只改"优先 mediaNote"。
        // 对**无 mediaNote** 的历史，输出必须逐字一致。
        val plain = listOf(
            QqMessage("h1", true, "第一条", 1L),
            QqMessage("h2", false, "回复一", 2L)
        )
        assertEquals("对方：第一条\n我：回复一", QqContextBuilder.render(plain))
        // 有 mediaNote 时只允许"占位符 → 语义说明"这一处差异
        val withNote = listOf(QqMessage("h3", true, "[表情包]", 3L, mediaNote = "对方发来一个抱抱的表情包"))
        assertEquals("对方：对方发来一个抱抱的表情包", QqContextBuilder.render(withNote))
    }

    @Test
    fun `提示词新增段的位置只影响 user，不改变 system 与旧段语义`() {
        val h = listOf(QqMessage("h1", true, "前情", 1L))
        val legacy = QqProactiveComposer.buildReplyUserContext(history = h, incoming = "新消息")
        val fixed = QqProactiveComposer.buildReplyUserContext(
            history = h,
            incoming = "新消息",
            shapeHint = "只回半句",
            now = java.time.LocalDateTime.of(2026, 11, 15, 23, 30)
        )
        // 旧段（最近对话 + 当前消息）必须原样保留在 fixed 里 —— 只允许"追加"，不允许改写
        assertTrueContains(fixed, legacy.trim())
        assertEquals(
            "兼容形态（now=null）输出必须与改动前逐字一致",
            "最近对话（由旧到新）：\n对方：前情\n\n对方刚发来：「新消息」",
            legacy
        )
    }

    private fun assertTrueContains(haystack: String, needle: String) {
        org.junit.Assert.assertTrue("新增段不得破坏旧段，缺少：$needle", haystack.contains(needle))
    }
}
