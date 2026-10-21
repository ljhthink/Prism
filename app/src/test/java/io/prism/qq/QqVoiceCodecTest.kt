package io.prism.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [QqVoiceCodec] 音频编码纯函数测试（PRD US-3113 AC-2）。
 */
class QqVoiceCodecTest {

    @Test
    fun `WAV 头字段正确（RIFF、fmt、单声道16位）`() {
        val wav = QqVoiceCodec.encodeWav16Mono(FloatArray(4) { 0.5f }, 24_000)
        assertEquals("44 头 + 4 样本 × 2 字节", 52, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        // RIFF chunk size = 36 + dataSize
        assertEquals(36 + 8, le32(wav, 4))
        assertEquals(1, le16(wav, 20))
        assertEquals(1, le16(wav, 22))
        assertEquals(24_000, le32(wav, 24))
        assertEquals(48_000, le32(wav, 28))
        assertEquals(2, le16(wav, 32))
        assertEquals(16, le16(wav, 34))
        assertEquals(8, le32(wav, 40))
    }

    @Test
    fun `样本被钳制到 -1 到 1，不溢出翻转`() {
        val wav = QqVoiceCodec.encodeWav16Mono(floatArrayOf(2f, -2f), 16_000)
        assertEquals(Short.MAX_VALUE.toInt(), le16Signed(wav, 44))
        assertEquals(-Short.MAX_VALUE, le16Signed(wav, 46))
    }

    @Test
    fun `非法采样率或空音频返回空数组`() {
        assertTrue(QqVoiceCodec.encodeWav16Mono(FloatArray(3), 0).isEmpty())
        assertTrue(QqVoiceCodec.encodeWav16Mono(FloatArray(0), 16_000).isEmpty())
    }

    @Test
    fun `base64 负载带 base64 前缀且可解码回 WAV`() {
        val url = QqVoiceCodec.base64WavUrl(floatArrayOf(0.1f, -0.1f), 16_000)
        assertTrue(url.startsWith("base64://"))
        val decoded = Base64.getDecoder().decode(url.removePrefix("base64://"))
        assertEquals("RIFF", String(decoded, 0, 4, Charsets.US_ASCII))
        assertEquals(44 + 4, decoded.size)
    }

    private fun le16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun le16Signed(b: ByteArray, offset: Int): Int =
        le16(b, offset).toShort().toInt()

    private fun le32(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
}
