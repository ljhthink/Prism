package io.prism.voice.local

/**
 * 流式写入的**准入判定**（纯函数，guardrail R2-N2 要求的可测出口）。
 *
 * ## 语义
 *
 * **空块总是可接受**（无操作、返回 true）：刻意不把"这一块没有音频"与"会话已作废"混成同一个
 * 返回值——否则一次空回调会被调用方误判为"输出栈永久失效"，进而**丢弃其后全部分段**
 * （用户听到"念到一半没了"，日志却写正常完成；guardrail 第一轮 F-2）。
 *
 * ## 为什么必须抽成纯函数
 *
 * 生产实现是 `AudioTrackLocalClonePlayer` 的 **private inner class**（构造 AudioTrack、
 * 依赖 Android 运行时）⇒ JVM 单测**无法实例化**，写在它内部的判定只能靠集成/真机覆盖；
 * guardrail 第二轮就此点判定"该语义无法被用例钉住"（R2-N2）。抽成纯函数后，
 * "空块 ≠ 作废"与"会话失效 ⇒ 拒绝写入"两条语义都可被单测直接钉住。
 *
 * @param sampleCount 本块样本数（0 = 空块）
 * @param sessionValid 会话当前是否有效（未关闭、播放世代一致、未被取消）
 * @return true = 可以接受本块（空块视为无操作）；false = 会话已作废，调用方必须停止写入
 */
internal fun streamWriteAccepted(sampleCount: Int, sessionValid: Boolean): Boolean =
    sampleCount == 0 || sessionValid
