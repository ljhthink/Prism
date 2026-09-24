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

    // ==================== LVC-1（ADR-053）：本地音色选择解析 ====================

    @Test
    fun `TTS 解析 - 本地音色合法值与宽容`() {
        assertEquals(TtsEngineChoice.LOCAL_CLONE, TtsEngineChoice.fromStoreValue("localClone"))
        assertEquals(TtsEngineChoice.LOCAL_CLONE, TtsEngineChoice.fromStoreValue("LOCALCLONE"))
        assertEquals(TtsEngineChoice.LOCAL_CLONE, TtsEngineChoice.fromStoreValue(" localclone "))
    }

    @Test
    fun `TTS 解析 - 本地音色不与既有值串档且非法值回落系统`() {
        // storeValue 只增不改：新增值不得吞掉/复用既有值域
        assertEquals(TtsEngineChoice.MINIMAX, TtsEngineChoice.fromStoreValue("minimax"))
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue("system"))
        // 非本域值（含 ASR 域值、其他引擎名）一律回落系统
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue("pocket"))
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue("zipvoice"))
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue("local"))
        assertEquals(TtsEngineChoice.SYSTEM, TtsEngineChoice.fromStoreValue("LOCAL_CLONE"))
    }

    @Test
    fun `storeValue 唯一性 - TTS`() {
        val values = TtsEngineChoice.values().map { it.storeValue.lowercase() }
        assertEquals(values.size, values.toSet().size)
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

    // ==================== TKN-LVC-016（ADR-056）：档位与播报模式 ====================

    @Test
    fun `播报模式解析 - 合法值 大小写宽容 缺省回落连续`() {
        assertEquals(LocalClonePlaybackMode.WHOLE, LocalClonePlaybackMode.fromStoreValue("whole"))
        assertEquals(LocalClonePlaybackMode.WHOLE, LocalClonePlaybackMode.fromStoreValue(" WHOLE "))
        assertEquals(
            LocalClonePlaybackMode.CONTINUOUS,
            LocalClonePlaybackMode.fromStoreValue("continuous")
        )
        // 出厂默认 = 连续（用户 Q1 裁决）；脏数据也不得把默认换成另一种交互
        assertEquals(LocalClonePlaybackMode.CONTINUOUS, LocalClonePlaybackMode.fromStoreValue(null))
        assertEquals(LocalClonePlaybackMode.CONTINUOUS, LocalClonePlaybackMode.fromStoreValue(""))
        assertEquals(LocalClonePlaybackMode.CONTINUOUS, LocalClonePlaybackMode.fromStoreValue("   "))
        assertEquals(LocalClonePlaybackMode.CONTINUOUS, LocalClonePlaybackMode.fromStoreValue("bogus"))
        // 与 TTS 域值不串档
        assertEquals(LocalClonePlaybackMode.CONTINUOUS, LocalClonePlaybackMode.fromStoreValue("minimax"))
    }

    @Test
    fun `档位解析 - 合法值 大小写宽容 缺省回落质量优先`() {
        assertEquals(LocalVoiceTier.BALANCED, LocalVoiceTier.fromStoreValue("balanced"))
        assertEquals(LocalVoiceTier.SPEED, LocalVoiceTier.fromStoreValue("SPEED"))
        assertEquals(LocalVoiceTier.SPEED, LocalVoiceTier.fromStoreValue(" speed "))
        assertEquals(LocalVoiceTier.QUALITY, LocalVoiceTier.fromStoreValue("quality"))
        // **回落方向必须是最保真的那一档**：脏数据不得悄悄把听感基线换成低质量档
        assertEquals(LocalVoiceTier.QUALITY, LocalVoiceTier.fromStoreValue(null))
        assertEquals(LocalVoiceTier.QUALITY, LocalVoiceTier.fromStoreValue(""))
        assertEquals(LocalVoiceTier.QUALITY, LocalVoiceTier.fromStoreValue("bogus"))
        assertEquals(LocalVoiceTier.QUALITY, LocalVoiceTier.fromStoreValue("whole"))
    }

    @Test
    fun `storeValue 与解析往返一致 - 档位与播报模式`() {
        LocalClonePlaybackMode.values().forEach { mode ->
            assertEquals(mode, LocalClonePlaybackMode.fromStoreValue(mode.storeValue))
        }
        LocalVoiceTier.values().forEach { tier ->
            assertEquals(tier, LocalVoiceTier.fromStoreValue(tier.storeValue))
        }
    }

    @Test
    fun `storeValue 唯一性 - 档位与播报模式（不可复用值名）`() {
        val modes = LocalClonePlaybackMode.values().map { it.storeValue.lowercase() }
        assertEquals(modes.size, modes.toSet().size)
        val tiers = LocalVoiceTier.values().map { it.storeValue.lowercase() }
        assertEquals(tiers.size, tiers.toSet().size)
    }
}
