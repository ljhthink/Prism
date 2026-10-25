package io.prism.phonecontrol.bg

/**
 * 帧编码的**降级计划**（2026-10-25 帧健康整改；guardrail **G-r2-03**）。
 *
 * **为什么抽成纯函数**：`ShizukuShell.encodeHiddenFrame` 的降级循环是"取不到画面/泄漏位图"
 * 这类缺陷的所在地，但它依赖 `Bitmap.compress`（JVM 无图形栈）。把"每一步用什么质量、
 * 什么尺寸、何时终止"抽成纯函数后，测试可以真正锁住三件**承重行为**：
 * 1. 尝试次数有界（不在 shell 特权进程里无限分配位图）；
 * 2. 缩小是**单调**的（新尺寸严格小于上一步，否则会死循环）；
 * 3. 一定有终止（尺寸到 1×1 或步数耗尽 → 返回 null 而不是继续）。
 *
 * 这样"把降级删掉改回 r1 的 `takeIf { size <= 900KB }`"会直接让测试变红。
 */
internal object FrameEncodePlan {

    /** 单步计划：用 [quality] 编码 [width]×[height]。 */
    data class Step(val width: Int, val height: Int, val quality: Int)

    /**
     * 生成完整的降级序列（**不含**原始尺寸那一步之外的任何"成功"判断——成功与否取决于
     * 实际编码字节数，只有调用方知道）。
     *
     * 顺序：先降质（JPEG 有效；PNG 忽略质量 → 首个质量级后直接进缩放），再逐步缩小。
     *
     * @param baseWidth/baseHeight 起始尺寸（未缩放时等于帧尺寸）
     * @param initialQuality 初始质量（调用方传入，已钳制）
     * @param png true=PNG（质量无效，直接缩放）
     * @param attempts 最多尝试步数（含第一步）
     * @param minQuality 最低质量
     * @param scaleStep 每级缩放系数（0<step<1）
     */
    fun steps(
        baseWidth: Int,
        baseHeight: Int,
        initialQuality: Int,
        png: Boolean,
        attempts: Int = HiddenFrameSpec.ENCODE_ATTEMPTS,
        minQuality: Int = HiddenFrameSpec.MIN_QUALITY,
        scaleStep: Double = HiddenFrameSpec.SCALE_STEP
    ): List<Step> {
        if (baseWidth <= 0 || baseHeight <= 0 || attempts <= 0) return emptyList()
        val out = ArrayList<Step>(attempts)
        var w = baseWidth
        var h = baseHeight
        var q = initialQuality.coerceIn(1, 100)
        var scale = 1.0
        repeat(attempts) {
            out += Step(w, h, q)
            // 先降质（PNG 质量问题：质量对 PNG 无意义，直接进入缩放分支）
            if (!png && q > minQuality) {
                q = (q * 2 / 3).coerceAtLeast(minQuality)
            } else {
                scale *= scaleStep
                val nw = (baseWidth * scale).toInt().coerceAtLeast(1)
                val nh = (baseHeight * scale).toInt().coerceAtLeast(1)
                // 已到 1×1 且无法再缩 → 后续步骤无意义，提前终止
                if (nw == w && nh == h) return out
                w = nw
                h = nh
            }
        }
        return out
    }

    /**
     * 计划是否**必然终止**（用于测试与自检）：
     * 尺寸单调不增，且存在严格递减的缩放步，或步数有限。
     */
    fun terminates(plan: List<Step>): Boolean {
        for (i in 1 until plan.size) {
            val prev = plan[i - 1]
            val cur = plan[i]
            val sameSize = cur.width == prev.width && cur.height == prev.height
            val smallerQuality = cur.quality < prev.quality
            // 每一步要么尺寸更小、要么质量更低；两者都不满足说明卡住了
            if (sameSize && !smallerQuality) return false
            if (cur.width > prev.width || cur.height > prev.height) return false
        }
        return true
    }
}
