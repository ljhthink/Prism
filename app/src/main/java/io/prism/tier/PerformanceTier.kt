package io.prism.tier

/**
 * 设备性能档位（ADR-017 4.2）。
 *
 * 按 RAM 容量分四档，决定 RAG / 嵌入 / L2 跨会话记忆等功能开关与参数。
 * 用户可在设置中手动覆盖（[TierConfigRepository]），覆盖优先于 RAM 自动检测。
 *
 * | 档位 | RAM 阈值 | RAG | L2 跨会话 | embedder | top-k |
 * |---|---|---|---|---|---|
 * | [FULL] | ≥6GB | ✓ 标准批次 | ✓ | 常驻 5min 卸载 | 5 |
 * | [STANDARD] | 4-6GB | ✓ 小批次 | ✓ | 按需 2min 卸载 | 3 |
 * | [MINIMAL] | 3-4GB | ✗ 关键词检索 | ✗ | 不加载 | N/A |
 * | [CHAT_ONLY] | <3GB | ✗ | ✗ | 不加载 | N/A |
 *
 * **降级语义**（ADR-017 4.6）：ConversationViewModel.Factory 按 [isMemoryL2Enabled]
 * / [isRagEnabled] 决定是否注入 null，复用 M5 Phase E 已验证的 null 降级基建。
 *
 * US-007 验收标准 1：启动时检测设备 RAM，自动选择功能档位
 * US-007 验收标准 2-5：四档功能矩阵
 */
enum class PerformanceTier {
    /** ≥6GB：全功能（RAG 标准批次 + 嵌入常驻 5min 卸载 + L2 跨会话 + top-k=5）。 */
    FULL,

    /** 4-6GB：RAG 小批次 + 嵌入按需 2min 卸载 + L2 跨会话 + top-k=3。 */
    STANDARD,

    /** 3-4GB：禁用 RAG 与 L2，仅关键词检索，嵌入不加载。 */
    MINIMAL,

    /** <3GB：仅聊天 + BYOK，RAG / L2 / 嵌入全禁用。 */
    CHAT_ONLY;

    /** RAG 检索是否启用（FULL / STANDARD 启用，MINIMAL / CHAT_ONLY 禁用）。 */
    val isRagEnabled: Boolean
        get() = this == FULL || this == STANDARD

    /** L2 跨会话记忆是否启用（依赖 embedder，FULL / STANDARD 启用）。 */
    val isMemoryL2Enabled: Boolean
        get() = this == FULL || this == STANDARD

    /** embedder 是否加载（FULL / STANDARD 加载真实模型，MINIMAL / CHAT_ONLY 用 NullEmbedder）。 */
    val isEmbedderEnabled: Boolean
        get() = this == FULL || this == STANDARD

    /** RAG 检索 top-k（FULL=5, STANDARD=3，禁用时返回 0 表示不使用）。 */
    val ragTopK: Int
        get() = when (this) {
            FULL -> 5
            STANDARD -> 3
            MINIMAL -> 0
            CHAT_ONLY -> 0
        }

    /** embedder 闲置卸载阈值（毫秒，FULL=5min, STANDARD=2min，禁用时不使用）。 */
    val embedderIdleThresholdMs: Long
        get() = when (this) {
            FULL -> 5L * 60L * 1000L
            STANDARD -> 2L * 60L * 1000L
            MINIMAL -> 0L
            CHAT_ONLY -> 0L
        }

    /** checkAndUnload 调度间隔（毫秒，通常为闲置阈值的 1/5）。 */
    val checkIntervalMs: Long
        get() = when (this) {
            FULL -> 60L * 1000L
            STANDARD -> 30L * 1000L
            MINIMAL -> 0L
            CHAT_ONLY -> 0L
        }

    companion object {
        /** RAM 阈值（字节）：≥此值归入 FULL 档（6GB）。 */
        const val FULL_THRESHOLD_BYTES: Long = 6L * 1024L * 1024L * 1024L

        /** RAM 阈值（字节）：≥此值归入 STANDARD 档（4GB）。 */
        const val STANDARD_THRESHOLD_BYTES: Long = 4L * 1024L * 1024L * 1024L

        /** RAM 阈值（字节）：≥此值归入 MINIMAL 档（3GB）。 */
        const val MINIMAL_THRESHOLD_BYTES: Long = 3L * 1024L * 1024L * 1024L

        /**
         * 根据 RAM 总量（字节）映射到档位。
         *
         * **保守阈值策略**（ADR-017 4.3）：`ActivityManager.MemoryInfo.totalMem`
         * 在某些设备上报告值小于物理 RAM（实际 3.5GB 可能报告为 3.2GB），
         * 归入 CHAT_ONLY 档，符合 PRD「低端机优先保稳定」语义。
         *
         * @param totalRamBytes 设备 RAM 总量（来自 [ActivityManager.MemoryInfo.totalMem]）
         * @return 对应的 [PerformanceTier]
         */
        fun fromRamBytes(totalRamBytes: Long): PerformanceTier = when {
            totalRamBytes >= FULL_THRESHOLD_BYTES -> FULL
            totalRamBytes >= STANDARD_THRESHOLD_BYTES -> STANDARD
            totalRamBytes >= MINIMAL_THRESHOLD_BYTES -> MINIMAL
            else -> CHAT_ONLY
        }
    }
}
