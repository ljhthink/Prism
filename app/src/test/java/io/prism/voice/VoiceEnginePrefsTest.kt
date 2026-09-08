package io.prism.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 批次23-A（ADR-048，A4）：语音引擎选择持久化纯函数单测 ——
 * [AsrEngineChoice.fromStoreValue] / [TtsEngineChoice.fromStoreValue]。
 *
 * 固化契约：合法值解析（大小写/空白宽容）、null/空白/非法值回落出厂默认（system），
 * 与 storeValue 往返一致（防枚举改名破坏既有持久化数据）。
 */
class VoiceEnginePrefsTest {

    // ==================== ASR 选择解析 ====================

    @Test
    fun `ASR 解析 - 合法值`() {
        assertEquals(AsrEngineChoice.SYSTEM, AsrEngineChoice.fromStoreValue("system"))
        assertEquals(AsrEngineChoice.ALI_CLOUD, AsrEngineChoice.fromStoreValue("aliCloud"))
    }

    @Test
    fun `ASR 解析 - 大小写与空白宽容`() {
        assertEquals(AsrEngineChoice.ALI_CLOUD, AsrEngineChoice.fromStoreValue("ALICLOUD"))
        assertEquals(AsrEngineChoice.ALI_CLOUD, AsrEngineChoice.fromStoreValue(" alicloud "))
        assertEquals(AsrEngineChoice.SYSTEM, AsrEngineChoice.fromStoreValue(" System "))
    }

    @Test
    fun `ASR 解析 - 缺省与非法值默认端侧`() {
        assertEquals(AsrEngineChoice.SYSTEM, AsrEngineChoice.fromStoreValue(null))
        assertEquals(AsrEngineChoice.SYSTEM, AsrEngineChoice.fromStoreValue(""))
        assertEquals(AsrEngineChoice.SYSTEM, AsrEngineChoice.fromStoreValue("   "))
        assertEquals(AsrEngineChoice.SYSTEM, AsrEngineChoice.fromStoreValue("bogus"))
        assertEquals(AsrEngineChoice.SYSTEM, AsrEngineChoice.fromStoreValue("minimax"))
    }

    // ==================== TTS 选择解析 ====================

    @Test
    fun `TTS 解析 - 合法值`() {
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue("system"))
        assertEquals(TtsEngineChoice.MINIMAX, TtsEngineChoice.fromStoreValue("minimax"))
    }

    @Test
    fun `TTS 解析 - 大小写与空白宽容`() {
        assertEquals(TtsEngineChoice.MINIMAX, TtsEngineChoice.fromStoreValue("MINIMAX"))
        assertEquals(TtsEngineChoice.MINIMAX, TtsEngineChoice.fromStoreValue(" minimax "))
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue("System"))
    }

    @Test
    fun `TTS 解析 - 缺省与非法值默认系统`() {
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue(null))
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue(""))
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue("bogus"))
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue("aliCloud"))
    }

    // ==================== storeValue 往返一致 ====================

    @Test
    fun `storeValue 与解析往返一致 - ASR`() {
        AsrEngineChoice.values().forEach { choice ->
            assertEquals(choice, AsrEngineChoice.fromStoreValue(choice.storeValue))
        }
    }

    @Test
    fun `storeValue 与解析往返一致 - TTS`() {
        TtsEngineChoice.values().forEach { choice ->
            assertEquals(choice, TtsEngineChoice.fromStoreValue(choice.storeValue))
        }
    }
}
