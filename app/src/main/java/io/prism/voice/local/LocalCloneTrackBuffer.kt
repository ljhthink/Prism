package io.prism.voice.local

/**
 * AudioTrack 缓冲区尺寸计算（纯函数，TKN-LVC-011 / BUG-20260920-004）。
 *
 * ## 为什么单独抽出来
 *
 * 真机缺陷：原实现 `maxOf(minBuffer, pcm.size * BYTES_PER_FRAME)` 让缓冲区**随音频长度线性增长**
 * ——14s 音频 ⇒ 662KB、19.5s ⇒ 916KB，远超正常 STREAM 音轨量级（几 KB～几十 KB）。后果（真机日志）：
 *
 * ```
 * AudioFlinger: prepareTracks_l BUFFER TIMEOUT: remove track(5922) from active list due to underrun
 * AudioTrack:   restartIfDisabled(8301): releaseBuffer() track disabled due to previous underrun, restarting
 * AudioFlinger: threadLoop: blocked before threadloop_write() for 820573 msecs
 * ```
 *
 * ⇒ **长音频播到一半无声、短句正常**。缓冲区尺寸必须**与音频长度无关**；
 * `write(..., WRITE_BLOCKING)` 在缓冲满时自然阻塞即为节流（STREAM 模式的正常用法）。
 *
 * ## 优先级（安全性）
 *
 * **`minBuffer` 是硬下限**：官方口径是"MODE_STREAM 下**创建** AudioTrack 所需的最小缓冲"，
 * 低于它 `AudioTrack.Builder.build()` 会失败（AOSP 抛 `UnsupportedOperationException`）。
 * 故上限只约束"我们的偏好值"，**不得压穿设备下限**：
 * `bytes = max(minBuffer, min(target, cap))`。
 *
 * 实测取值（`minBuffer` 取真机 24kHz 的 ~3856B）：16k→3200B、24k→**4800B(100ms)/9600B(200ms)**、
 * 44.1k→8820B、48k→9600B。32KB 的 cap 在真实采样率下**不生效**（需 ≥163.8kHz 才触发），
 * 它只是防高采样率/异常入参的护栏。
 */
internal fun localCloneTrackBufferBytes(minBuffer: Int, sampleRate: Int): Int {
    val target = if (sampleRate > 0) sampleRate * TRACK_BYTES_PER_FRAME * TARGET_BUFFER_MS / 1000 else 0
    val preferred = minOf(target, MAX_TRACK_BUFFER_BYTES)
    return maxOf(minBuffer, preferred, 1)
}

/** 每帧字节数（16bit 单声道）。 */
internal const val TRACK_BYTES_PER_FRAME = 2

/**
 * 目标缓冲时长（ms）。
 *
 * 取 **200ms**（guardrail TKN-LVC-011 新-2）：100ms 相对真机下限（80.3ms@24k）仅余约 25% 余量，
 * 写入线程一旦被调度延迟 >100ms 就会 underrun；200ms 给出约 2.5× 余量，且仍是**常量**
 * （与音频长度无关 ⇒ 不回归 BUG-20260920-004）。
 */
internal const val TARGET_BUFFER_MS = 200

/** 偏好上限：32KB（≈0.68s@24kHz）——防超长音频/高采样率把缓冲推回大块头（但不压穿设备下限）。 */
internal const val MAX_TRACK_BUFFER_BYTES = 32 * 1024
