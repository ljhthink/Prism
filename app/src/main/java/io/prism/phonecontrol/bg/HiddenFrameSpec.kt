package io.prism.phonecontrol.bg

/**
 * 隐藏虚拟屏**取帧规格**（2026-10-25 帧健康整改；ADR-065 修订）。
 *
 * **为什么单独成文件**：这些常量是"帧能不能传回来"的**承重件**——帧缓冲数决定生产端会不会
 * 被消费端拖住，字节上限必须低于 Binder 事务上限，降级阶梯必须有界（否则在 shell 特权进程里
 * 反复分配位图）。它们原先散落在 shell 用户服务类的私有 companion 里，单测无法断言，
 * 而这类参数改坏不会编译失败、只会表现为"取不到画面"（本轮故障的真实形态）。
 *
 * 现在：实现与单测共用本文件，改坏即红。
 */
internal object HiddenFrameSpec {

    /**
     * ImageReader 帧缓冲数。
     *
     * 2 太小：消费端（每帧要拷贝 ≈4.5MB RGBA）稍慢一点，生产端就被阻塞、画面卡顿；
     * 参考实现 OpenCyvis 用 8，这里取 4 折中（内存 vs 抗 stall）。
     */
    const val MAX_IMAGES = 4

    /**
     * 单帧字节上限。
     *
     * Binder 单次事务上限 1MB；留 ~148KB 余量。**超限不是"没有画面"**——走降质/降尺寸。
     */
    const val MAX_FRAME_BYTES = 900_000

    /** 编码逐级降级的最大尝试次数（质量 1 级 + 缩放 3 级）。 */
    const val ENCODE_ATTEMPTS = 4

    /** 逐级降级时的最低 JPEG 质量（再低就失去可读性，交给缩放）。 */
    const val MIN_QUALITY = 45

    /** 每次缩放的系数（宽高同乘）。 */
    const val SCALE_STEP = 0.8

    /** 帧转换最小间隔（消费节流；≈12fps，足够截图/查看，不拖住生产端）。 */
    const val MIN_INTERVAL_MS = 80L

    /** 建屏尺寸上界（AIDL 契约面防 shell 进程 OOM）。 */
    const val MAX_DIMENSION = 4096
    const val MAX_PIXELS = 4_000_000L
}
