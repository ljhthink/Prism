package io.prism.qq

import java.util.Base64

/**
 * QQ 语音消息的音频编码（PRD US-3113 AC-2）。
 *
 * **为什么是 WAV**：协议端（NapCat / SnowLuma）负责把常见音频转成 QQ 的 SILK；
 * OneBot 11 的 `record` 消息段接受 `base64://` 负载。Prism 侧只需产出**标准 16-bit PCM WAV**，
 * 不自行实现 SILK 编码（避开了专利/实现复杂度，且协议端已内置转码）。
 *
 * **纯函数可测**：[encodeWav16Mono] 只做字节拼装，不依赖 Android 音频栈。
 */
object QqVoiceCodec {

    /** 采样率安全范围（过低的采样率在 QQ 侧会被重采样，过高徒增负载）。 */
    const val MIN_SAMPLE_RATE = 8_000
    const val MAX_SAMPLE_RATE = 48_000

    /**
     * 把单声道浮点 PCM（[-1,1]）编码为 16-bit PCM WAV。
     *
     * @param pcm 浮点样本；超出 [-1,1] 的值会被钳制（防整数溢出翻转极性）
     * @param sampleRate 采样率（Hz）；非正值返回空数组（调用方按失败处理）
     * @return WAV 字节（44 字节头 + 数据）
     */
    fun encodeWav16Mono(pcm: FloatArray, sampleRate: Int): ByteArray {
        if (sampleRate <= 0 || pcm.isEmpty()) return ByteArray(0)
        val dataSize = pcm.size * 2
        val out = ByteArray(44 + dataSize)
        val header = wavHeader(dataSize, sampleRate.coerceIn(MIN_SAMPLE_RATE, MAX_SAMPLE_RATE))
        header.copyInto(out, 0)
        var pos = 44
        for (sample in pcm) {
            val v = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            out[pos++] = (v and 0xFF).toByte()
            out[pos++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** 编码并转 `base64://` 负载（OneBot `record` 段的 `file` 字段值）。 */
    fun base64WavUrl(pcm: FloatArray, sampleRate: Int): String =
        "base64://" + Base64.getEncoder().encodeToString(encodeWav16Mono(pcm, sampleRate))

    /** 标准 44 字节 RIFF/WAVE 头（单声道 16-bit）。 */
    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        return ByteArray(44).also { h ->
            fun ascii(offset: Int, s: String) {
                s.forEachIndexed { i, c -> h[offset + i] = c.code.toByte() }
            }
            fun le32(offset: Int, value: Int) {
                h[offset] = (value and 0xFF).toByte()
                h[offset + 1] = ((value shr 8) and 0xFF).toByte()
                h[offset + 2] = ((value shr 16) and 0xFF).toByte()
                h[offset + 3] = ((value shr 24) and 0xFF).toByte()
            }
            fun le16(offset: Int, value: Int) {
                h[offset] = (value and 0xFF).toByte()
                h[offset + 1] = ((value shr 8) and 0xFF).toByte()
            }
            ascii(0, "RIFF")
            le32(4, 36 + dataSize)
            ascii(8, "WAVE")
            ascii(12, "fmt ")
            le32(16, 16)          // fmt 块长度
            le16(20, 1)           // PCM
            le16(22, 1)           // 单声道
            le32(24, sampleRate)
            le32(28, byteRate)
            le16(32, 2)           // block align
            le16(34, 16)          // bits per sample
            ascii(36, "data")
            le32(40, dataSize)
        }
    }
}
