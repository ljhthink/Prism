package io.prism.qq

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **ac-verifier 补充用例**（TKN-QQ-FIX-001，2026-10-25 验收）。
 *
 * 目的：覆盖主 Agent 既有用例矩阵的盲区（不是重复既有断言）：
 * 1. `QqTimeOfDay.parse` 的**验收任务书逐字点名的 8 个取值**（含 `23:60`、`-1`、中文）；
 * 2. 跨零点窗 `22:00–02:00` 在 **23:30 / 次日 01:30** 的
 *    `isWithinWindow` / `windowAnchor` / `nextWindowAnchor` **三者一致性**（同一窗口实例）；
 * 3. `QqFailureClassifier` 的 r3 G-r3-06 **反向攻击面**：协议端 `message` 插值里带
 *    「可能已送达」字样时，**确定拒绝**不得被降级为 `SEND_UNCONFIRMED`。
 *
 * 保留在 `app/src/test`（rules/04 §11 要求新增测试落库并保留）。
 */
class QqFixAcceptanceSupplementTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // ==================== 1. QqTimeOfDay.parse 验收点名单 ====================

    @Test
    fun `parse 验收点名单_合法写法`() {
        assertEquals("7 → 07:00", 420, QqTimeOfDay.parse("7"))
        assertEquals("7:5 → 07:05", 425, QqTimeOfDay.parse("7:5"))
        assertEquals("24:00 → 当日结束", 1440, QqTimeOfDay.parse("24:00"))
        assertEquals("07:00", 420, QqTimeOfDay.parse("07:00"))
        assertEquals("0:00", 0, QqTimeOfDay.parse("0:00"))
        assertEquals("23:59", 1439, QqTimeOfDay.parse("23:59"))
    }

    @Test
    fun `parse 验收点名单_非法写法一律 null`() {
        // 24:01 —— 越过「当日结束」上界
        assertNull("24:01 必须非法", QqTimeOfDay.parse("24:01"))
        // 23:60 —— 分钟越界（等价类：分钟 60 与 59 的边界）
        assertNull("23:60 必须非法", QqTimeOfDay.parse("23:60"))
        // -1 —— 负数（正则 `\d` 不匹配负号）
        assertNull("-1 必须非法", QqTimeOfDay.parse("-1"))
        assertNull("-1:00 必须非法", QqTimeOfDay.parse("-1:00"))
        // 7:00:00 —— 三段式（含秒）不在契约内
        assertNull("7:00:00 必须非法", QqTimeOfDay.parse("7:00:00"))
        // 中文
        assertNull("中文必须非法", QqTimeOfDay.parse("中文"))
        assertNull("7点 必须非法", QqTimeOfDay.parse("7点"))
        // 空/空白/null
        assertNull(QqTimeOfDay.parse(""))
        assertNull(QqTimeOfDay.parse("   "))
        assertNull(QqTimeOfDay.parse(null))
        // 超大小时 / 三位数小时（正则限 1~2 位）
        assertNull(QqTimeOfDay.parse("25:00"))
        assertNull(QqTimeOfDay.parse("100:00"))
    }

    @Test
    fun `parse 与 isValidWindow 的口径一致性_24 00 只能作窗止`() {
        val end = QqTimeOfDay.parse("24:00")!!
        assertFalse("24:00 作窗起非法（0 until 1440）", QqTimeOfDay.isValidWindow(end, 0))
        assertTrue("24:00 作窗止合法", QqTimeOfDay.isValidWindow(0, end))
        assertFalse("起止相同非法", QqTimeOfDay.isValidWindow(420, 420))
    }

    // ==================== 2. 跨零点 22:00–02:00 三函数一致性 ====================

    private val ws = 22 * 60          // 1320
    private val we = 2 * 60           // 120

    @Test
    fun `跨零点窗口 23点30 与次日1点30 属同一窗口实例`() {
        val lateNight = at(2026, 10, 25, 23, 30)
        val afterMidnight = at(2026, 10, 26, 1, 30)

        // (a) 两点都在窗内
        assertTrue("23:30 在 22:00-02:00 内", QqSchedulePlanner.isWithinWindow(lateNight, ws, we, zone))
        assertTrue("次日 01:30 在 22:00-02:00 内", QqSchedulePlanner.isWithinWindow(afterMidnight, ws, we, zone))

        // (b) 锚点必须是同一实例起点（10-25 22:00），不得因午夜翻页而漂移
        val anchorLate = QqSchedulePlanner.windowAnchor(lateNight, ws, we, zone)
        val anchorAfter = QqSchedulePlanner.windowAnchor(afterMidnight, ws, we, zone)
        assertEquals("跨零点同一实例的锚点必须相等（G-r2-02 承重断言）", anchorLate, anchorAfter)
        assertEquals("锚点 = 窗口实例起点 10-25 22:00", at(2026, 10, 25, 22, 0), anchorLate)

        // (c) 下一窗口锚点一致：10-26 22:00
        assertEquals(
            "nextWindowAnchor 必须指向下一天窗口起点",
            at(2026, 10, 26, 22, 0),
            QqSchedulePlanner.nextWindowAnchor(anchorLate, zone)
        )
        assertEquals(
            "同一实例的两个时刻给出相同的下一窗口锚点",
            QqSchedulePlanner.nextWindowAnchor(anchorLate, zone),
            QqSchedulePlanner.nextWindowAnchor(anchorAfter, zone)
        )
    }

    @Test
    fun `跨零点窗口外时刻的锚点稳定指向下一个窗口起点`() {
        // 03:00 已过窗止（02:00）→ 下一实例应为当日 22:00
        val outside = at(2026, 10, 26, 3, 0)
        assertFalse(QqSchedulePlanner.isWithinWindow(outside, ws, we, zone))
        assertEquals(
            "窗外时刻锚点 = 当日 22:00",
            at(2026, 10, 26, 22, 0),
            QqSchedulePlanner.windowAnchor(outside, ws, we, zone)
        )
    }

    @Test
    fun `跨零点窗口 nextTriggerAt 在 23-30 与 01-30 都落在窗内`() {
        val rnd = kotlin.random.Random(20261025)
        listOf(at(2026, 10, 25, 23, 30), at(2026, 10, 26, 1, 30)).forEach { now ->
            val planned = QqSchedulePlanner.nextTriggerAt(
                nowMs = now, windowStartMinute = ws, windowEndMinute = we,
                minGapMs = 0L, random = rnd, zone = zone
            )
            assertTrue("必须有落点（$now）", planned != null && planned > now)
            assertTrue(
                "落点必须在 22:00~次日 02:00 之间（now=$now planned=$planned）",
                QqSchedulePlanner.isWithinWindow(planned!!, ws, we, zone)
            )
        }
    }

    @Test
    fun `窗口非法时三函数口径一致（isWithinWindow false anchor 0 nextWindowAnchor 0）`() {
        val now = at(2026, 10, 25, 23, 30)
        assertFalse(QqSchedulePlanner.isWithinWindow(now, 420, 420, zone))
        assertEquals(0L, QqSchedulePlanner.windowAnchor(now, 420, 420, zone))
        assertEquals(0L, QqSchedulePlanner.nextWindowAnchor(0L, zone))
    }

    // ==================== 3. 分类器反向攻击面（r3 G-r3-06） ====================

    @Test
    fun `协议端 message 插值含「已点击发送但未能…确认」时仍是确定拒绝`() {
        // 生产侧前缀原文
        val prefix = QqLocalToolExecutor.MSG_SEND_UNCONFIRMED_PREFIX
        // 攻击面：确定拒绝（retcode>=0）的错误文案里，插值了协议端返回的 message，
        // 而该 message 恰好**完整包含**未确认前缀。
        val spoofed = "发送失败（协议端 retcode=1200，message=$prefix，已点击发送…）"
        assertEquals(
            "确定拒绝不得被降级为 SEND_UNCONFIRMED（否则熔断永不触发）",
            QqFailureClassifier.Kind.SEND_REJECTED,
            QqFailureClassifier.classify(spoofed)
        )
        assertFalse(QqFailureClassifier.isUnconfirmedDelivery(QqFailureClassifier.classify(spoofed)))
        assertTrue(
            "确定拒绝必须计入熔断",
            QqFailureClassifier.countsTowardCircuit(QqFailureClassifier.classify(spoofed))
        )
    }

    @Test
    fun `前置噪声 + 未确认前缀也不得判为未确认（只认行首前缀）`() {
        val prefix = QqLocalToolExecutor.MSG_SEND_UNCONFIRMED_PREFIX
        val noisy = "QQ 通道：$prefix"
        assertEquals(
            "前缀不在行首 → 不得判为未确认（宁可保守判 UNKNOWN 也不得跳过重试语义）",
            QqFailureClassifier.Kind.UNKNOWN,
            QqFailureClassifier.classify(noisy)
        )
    }

    @Test
    fun `真正的未确认文案仍按未确认处理（正向不回归）`() {
        val produced = QqLocalToolExecutor.MSG_SEND_UNCONFIRMED_PREFIX +
            "（可能因屏幕刷新延迟）。不要盲目重发（重复消息会显得不自然）"
        assertEquals(QqFailureClassifier.Kind.SEND_UNCONFIRMED, QqFailureClassifier.classify(produced))
        assertTrue(QqFailureClassifier.isUnconfirmedDelivery(QqFailureClassifier.classify(produced)))
        assertFalse(
            "未确认不得计入熔断",
            QqFailureClassifier.countsTowardCircuit(QqFailureClassifier.classify(produced))
        )
        assertFalse(
            "未确认不得重试（重发 = 重复消息）",
            QqFailureClassifier.isRetryable(QqFailureClassifier.classify(produced))
        )
    }

    @Test
    fun `语音未确认前缀同样走前缀匹配`() {
        val produced = QqLocalToolExecutor.VOICE_SEND_UNCONFIRMED_PREFIX + "（传输异常，可能已送达）"
        assertEquals(QqFailureClassifier.Kind.SEND_UNCONFIRMED, QqFailureClassifier.classify(produced))
    }

    @Test
    fun `环境类与计入熔断类是互斥且穷尽的`() {
        QqFailureClassifier.Kind.values().forEach { kind ->
            if (QqFailureClassifier.countsTowardCircuit(kind)) {
                assertFalse("计入熔断的类别不得是环境类：$kind",
                    QqFailureClassifier.isTransientEnvironmentIssue(kind))
                assertFalse("计入熔断的类别不得可重试：$kind", QqFailureClassifier.isRetryable(kind))
            }
        }
        // 未确认永不重试、永不熔断
        assertFalse(QqFailureClassifier.countsTowardCircuit(QqFailureClassifier.Kind.SEND_UNCONFIRMED))
        assertFalse(QqFailureClassifier.isRetryable(QqFailureClassifier.Kind.SEND_UNCONFIRMED))
    }
}
