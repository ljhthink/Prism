package io.prism.tier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PerformanceTier 单元测试（US-007 AC-1~5，ADR-017 4.2）。
 *
 * 测试覆盖：
 * - 四档扩展属性（isRagEnabled / isMemoryL2Enabled / isEmbedderEnabled / ragTopK）
 * - RAM 字节映射到档位（fromRamBytes）
 * - 阈值边界（6GB / 4GB / 3GB 临界值）
 * - 闲置卸载参数（embedderIdleThresholdMs / checkIntervalMs）
 */
class PerformanceTierTest {

    // ============ isRagEnabled ============

    @Test
    fun isRagEnabled_full_returns_true() {
        assertTrue("FULL 档应启用 RAG", PerformanceTier.FULL.isRagEnabled)
    }

    @Test
    fun isRagEnabled_standard_returns_true() {
        assertTrue("STANDARD 档应启用 RAG", PerformanceTier.STANDARD.isRagEnabled)
    }

    @Test
    fun isRagEnabled_minimal_returns_false() {
        assertFalse("MINIMAL 档应禁用 RAG", PerformanceTier.MINIMAL.isRagEnabled)
    }

    @Test
    fun isRagEnabled_chatOnly_returns_false() {
        assertFalse("CHAT_ONLY 档应禁用 RAG", PerformanceTier.CHAT_ONLY.isRagEnabled)
    }

    // ============ isMemoryL2Enabled ============

    @Test
    fun isMemoryL2Enabled_full_returns_true() {
        assertTrue("FULL 档应启用 L2 跨会话记忆", PerformanceTier.FULL.isMemoryL2Enabled)
    }

    @Test
    fun isMemoryL2Enabled_standard_returns_true() {
        assertTrue("STANDARD 档应启用 L2 跨会话记忆", PerformanceTier.STANDARD.isMemoryL2Enabled)
    }

    @Test
    fun isMemoryL2Enabled_minimal_returns_false() {
        assertFalse("MINIMAL 档应禁用 L2 跨会话记忆", PerformanceTier.MINIMAL.isMemoryL2Enabled)
    }

    @Test
    fun isMemoryL2Enabled_chatOnly_returns_false() {
        assertFalse("CHAT_ONLY 档应禁用 L2 跨会话记忆", PerformanceTier.CHAT_ONLY.isMemoryL2Enabled)
    }

    // ============ isEmbedderEnabled ============

    @Test
    fun isEmbedderEnabled_full_returns_true() {
        assertTrue("FULL 档应加载 embedder", PerformanceTier.FULL.isEmbedderEnabled)
    }

    @Test
    fun isEmbedderEnabled_standard_returns_true() {
        assertTrue("STANDARD 档应加载 embedder", PerformanceTier.STANDARD.isEmbedderEnabled)
    }

    @Test
    fun isEmbedderEnabled_minimal_returns_false() {
        assertFalse("MINIMAL 档不应加载 embedder", PerformanceTier.MINIMAL.isEmbedderEnabled)
    }

    @Test
    fun isEmbedderEnabled_chatOnly_returns_false() {
        assertFalse("CHAT_ONLY 档不应加载 embedder", PerformanceTier.CHAT_ONLY.isEmbedderEnabled)
    }

    // ============ isPhoneControlEnabled（v1 US-204，LLM 操控手机档位门控） ============

    @Test
    fun isPhoneControlEnabled_full_returns_true() {
        assertTrue("FULL 档应启用手机操控", PerformanceTier.FULL.isPhoneControlEnabled)
    }

    @Test
    fun isPhoneControlEnabled_standard_returns_true() {
        assertTrue("STANDARD 档应启用手机操控", PerformanceTier.STANDARD.isPhoneControlEnabled)
    }

    @Test
    fun isPhoneControlEnabled_minimal_returns_false() {
        assertFalse("MINIMAL 档应禁用手机操控（低端优先稳定）", PerformanceTier.MINIMAL.isPhoneControlEnabled)
    }

    @Test
    fun isPhoneControlEnabled_chatOnly_returns_false() {
        assertFalse("CHAT_ONLY 档应禁用手机操控（低端优先稳定）", PerformanceTier.CHAT_ONLY.isPhoneControlEnabled)
    }

    // ============ ragTopK ============

    @Test
    fun ragTopK_full_returns_5() {
        assertEquals("FULL 档 top-k 应为 5", 5, PerformanceTier.FULL.ragTopK)
    }

    @Test
    fun ragTopK_standard_returns_3() {
        assertEquals("STANDARD 档 top-k 应为 3", 3, PerformanceTier.STANDARD.ragTopK)
    }

    @Test
    fun ragTopK_minimal_returns_0() {
        assertEquals("MINIMAL 档 top-k 应为 0（RAG 禁用）", 0, PerformanceTier.MINIMAL.ragTopK)
    }

    @Test
    fun ragTopK_chatOnly_returns_0() {
        assertEquals("CHAT_ONLY 档 top-k 应为 0（RAG 禁用）", 0, PerformanceTier.CHAT_ONLY.ragTopK)
    }

    // ============ embedderIdleThresholdMs ============

    @Test
    fun embedderIdleThresholdMs_full_returns_5_minutes() {
        assertEquals(
            "FULL 档闲置卸载阈值应为 5 分钟",
            5L * 60L * 1000L,
            PerformanceTier.FULL.embedderIdleThresholdMs
        )
    }

    @Test
    fun embedderIdleThresholdMs_standard_returns_2_minutes() {
        assertEquals(
            "STANDARD 档闲置卸载阈值应为 2 分钟",
            2L * 60L * 1000L,
            PerformanceTier.STANDARD.embedderIdleThresholdMs
        )
    }

    @Test
    fun embedderIdleThresholdMs_minimal_returns_0() {
        assertEquals(
            "MINIMAL 档闲置卸载阈值应为 0（不加载 embedder）",
            0L,
            PerformanceTier.MINIMAL.embedderIdleThresholdMs
        )
    }

    @Test
    fun embedderIdleThresholdMs_chatOnly_returns_0() {
        assertEquals(
            "CHAT_ONLY 档闲置卸载阈值应为 0（不加载 embedder）",
            0L,
            PerformanceTier.CHAT_ONLY.embedderIdleThresholdMs
        )
    }

    // ============ checkIntervalMs ============

    @Test
    fun checkIntervalMs_full_returns_60_seconds() {
        assertEquals(
            "FULL 档调度间隔应为 60 秒",
            60L * 1000L,
            PerformanceTier.FULL.checkIntervalMs
        )
    }

    @Test
    fun checkIntervalMs_standard_returns_30_seconds() {
        assertEquals(
            "STANDARD 档调度间隔应为 30 秒",
            30L * 1000L,
            PerformanceTier.STANDARD.checkIntervalMs
        )
    }

    @Test
    fun checkIntervalMs_minimal_returns_0() {
        assertEquals(
            "MINIMAL 档调度间隔应为 0（不调度）",
            0L,
            PerformanceTier.MINIMAL.checkIntervalMs
        )
    }

    @Test
    fun checkIntervalMs_chatOnly_returns_0() {
        assertEquals(
            "CHAT_ONLY 档调度间隔应为 0（不调度）",
            0L,
            PerformanceTier.CHAT_ONLY.checkIntervalMs
        )
    }

    // ============ fromRamBytes ============

    @Test
    fun fromRamBytes_exactly_6gb_returns_full() {
        val exactly6Gb = PerformanceTier.FULL_THRESHOLD_BYTES
        assertEquals(
            "恰好 6GB 应归入 FULL 档（≥ 阈值）",
            PerformanceTier.FULL,
            PerformanceTier.fromRamBytes(exactly6Gb)
        )
    }

    @Test
    fun fromRamBytes_above_6gb_returns_full() {
        val above6Gb = PerformanceTier.FULL_THRESHOLD_BYTES + 1
        assertEquals(
            "大于 6GB 应归入 FULL 档",
            PerformanceTier.FULL,
            PerformanceTier.fromRamBytes(above6Gb)
        )
    }

    @Test
    fun fromRamBytes_just_below_6gb_returns_standard() {
        val justBelow6Gb = PerformanceTier.FULL_THRESHOLD_BYTES - 1
        assertEquals(
            "略低于 6GB 应归入 STANDARD 档",
            PerformanceTier.STANDARD,
            PerformanceTier.fromRamBytes(justBelow6Gb)
        )
    }

    @Test
    fun fromRamBytes_exactly_4gb_returns_standard() {
        val exactly4Gb = PerformanceTier.STANDARD_THRESHOLD_BYTES
        assertEquals(
            "恰好 4GB 应归入 STANDARD 档",
            PerformanceTier.STANDARD,
            PerformanceTier.fromRamBytes(exactly4Gb)
        )
    }

    @Test
    fun fromRamBytes_just_below_4gb_returns_minimal() {
        val justBelow4Gb = PerformanceTier.STANDARD_THRESHOLD_BYTES - 1
        assertEquals(
            "略低于 4GB 应归入 MINIMAL 档",
            PerformanceTier.MINIMAL,
            PerformanceTier.fromRamBytes(justBelow4Gb)
        )
    }

    @Test
    fun fromRamBytes_exactly_3gb_returns_minimal() {
        val exactly3Gb = PerformanceTier.MINIMAL_THRESHOLD_BYTES
        assertEquals(
            "恰好 3GB 应归入 MINIMAL 档",
            PerformanceTier.MINIMAL,
            PerformanceTier.fromRamBytes(exactly3Gb)
        )
    }

    @Test
    fun fromRamBytes_just_below_3gb_returns_chatOnly() {
        val justBelow3Gb = PerformanceTier.MINIMAL_THRESHOLD_BYTES - 1
        assertEquals(
            "略低于 3GB 应归入 CHAT_ONLY 档",
            PerformanceTier.CHAT_ONLY,
            PerformanceTier.fromRamBytes(justBelow3Gb)
        )
    }

    @Test
    fun fromRamBytes_8gb_returns_full() {
        val eightGb = 8L * 1024L * 1024L * 1024L
        assertEquals(
            "8GB 设备应归入 FULL 档（中端机典型配置）",
            PerformanceTier.FULL,
            PerformanceTier.fromRamBytes(eightGb)
        )
    }

    @Test
    fun fromRamBytes_zero_returns_chatOnly() {
        assertEquals(
            "0 字节应归入 CHAT_ONLY 档（防御边界）",
            PerformanceTier.CHAT_ONLY,
            PerformanceTier.fromRamBytes(0L)
        )
    }

    @Test
    fun fromRamBytes_negative_returns_chatOnly() {
        assertEquals(
            "负值应归入 CHAT_ONLY 档（防御边界）",
            PerformanceTier.CHAT_ONLY,
            PerformanceTier.fromRamBytes(-1L)
        )
    }

    @Test
    fun fromRamBytes_3_2gb_reported_returns_minimal() {
        // ADR-017 4.3：3.5GB 设备可能报告为 3.2GB，仍 ≥ 3GB 阈值，归入 MINIMAL 档
        // （仅当报告值低于 3GB 时才归入 CHAT_ONLY，保守阈值）
        val reported3_2Gb = (3.2 * 1024 * 1024 * 1024).toLong()
        assertEquals(
            "3.2GB 报告值应归入 MINIMAL 档（≥ 3GB 阈值，ADR-017 4.3）",
            PerformanceTier.MINIMAL,
            PerformanceTier.fromRamBytes(reported3_2Gb)
        )
    }

    @Test
    fun fromRamBytes_2_8gb_reported_returns_chatOnly() {
        // ADR-017 4.3 风险：3GB 设备可能报告为 2.8GB，归入 CHAT_ONLY 档（保守策略）
        val reported2_8Gb = (2.8 * 1024 * 1024 * 1024).toLong()
        assertEquals(
            "2.8GB 报告值应归入 CHAT_ONLY 档（< 3GB 阈值，保守降级）",
            PerformanceTier.CHAT_ONLY,
            PerformanceTier.fromRamBytes(reported2_8Gb)
        )
    }
}
