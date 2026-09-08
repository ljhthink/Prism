package io.prism.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 批次23-A（ADR-048，A4）：ASR 引擎手选（设置页「语音引擎」区）。
 *
 * @property storeValue 持久化值（DataStore String；[SYSTEM] 为出厂默认）。
 */
enum class AsrEngineChoice(val storeValue: String) {
    /** 端侧识别（出厂默认：sherpa 流式端侧，缺失回落系统按住说话——AsrEngineFactory 既有降级链）。 */
    SYSTEM("system"),

    /** 云端识别（阿里云百炼 Paraformer-realtime-v2；仅 DashScope Key 就绪时设置页可选）。 */
    ALI_CLOUD("aliCloud");

    companion object {
        /**
         * 纯函数（JVM 单测见 VoiceEnginePrefsTest）：持久化原始值 → 引擎选择。
         * null/空白/非法值一律回落 [SYSTEM]（向后兼容出厂数据 + 防脏数据破坏通话可用性）。
         */
        fun fromStoreValue(raw: String?): AsrEngineChoice =
            if (raw?.trim()?.equals(ALI_CLOUD.storeValue, ignoreCase = true) == true) ALI_CLOUD else SYSTEM
    }
}

/**
 * 批次23-A（ADR-048，A4）：TTS 引擎手选（设置页「语音引擎」区）。
 *
 * @property storeValue 持久化值（DataStore String；[SYSTEM] 为出厂默认）。
 */
enum class TtsEngineChoice(val storeValue: String) {
    /** 系统播报（出厂默认：系统 TextToSpeech，零依赖）。 */
    SYSTEM("system"),

    /** 云端播报（MiniMax T2A v2；仅 API Key + GroupId 就绪时设置页可选）。 */
    MINIMAX("minimax");

    companion object {
        /**
         * 纯函数（JVM 单测见 VoiceEnginePrefsTest）：持久化原始值 → 引擎选择。
         * null/空白/非法值一律回落 [SYSTEM]（向后兼容出厂数据 + 防脏数据破坏通话可用性）。
         */
        fun fromStoreValue(raw: String?): TtsEngineChoice =
            if (raw?.trim()?.equals(MINIMAX.storeValue, ignoreCase = true) == true) MINIMAX else SYSTEM
    }
}

/**
 * 批次23-A（ADR-048，A4）：语音引擎手选持久化仓库 —— asrEngine（system|aliCloud）与
 * ttsEngine（system|minimax）双轨选择。
 *
 * **持久化模式**：对齐项目既有配置仓库（[io.prism.config.SearchEnhancementConfigRepository] /
 * [io.prism.voice.rolepack.DataStoreRolePackSelectedIdStore] 同款）——独立 DataStore<Preferences>
 * key 级原子读写；DataStore 单例由 PrismApplication 的 `preferencesDataStore` 委托保证
 * （多实例会崩溃，仓库自身不创建 DataStore），JVM 可测性由 [AsrEngineChoice.fromStoreValue] /
 * [TtsEngineChoice.fromStoreValue] 纯函数承载。
 *
 * **装配点**：[VoiceEngineAssembly.create]（通话开始时按选择构建引擎；云端选中但 Key 缺失
 * → 静默回退端侧 + toast）与设置页读写。
 *
 * @param dataStore Preferences DataStore（生产：`prism_voice_engine`）
 */
class VoiceEnginePrefs(private val dataStore: DataStore<Preferences>) {

    /** 观察 ASR 引擎选择（未配置 = [AsrEngineChoice.SYSTEM] 出厂默认）。 */
    fun asrEngine(): Flow<AsrEngineChoice> =
        dataStore.data.map { AsrEngineChoice.fromStoreValue(it[KEY_ASR_ENGINE]) }

    /** 一次性读取 ASR 引擎选择（装配点 suspend 取值）。 */
    suspend fun asrEngineOnce(): AsrEngineChoice = asrEngine().first()

    /** 保存 ASR 引擎选择（运行时即时生效——下次通话装配读取）。 */
    suspend fun setAsrEngine(choice: AsrEngineChoice) {
        dataStore.edit { prefs -> prefs[KEY_ASR_ENGINE] = choice.storeValue }
    }

    /** 观察 TTS 引擎选择（未配置 = [TtsEngineChoice.SYSTEM] 出厂默认）。 */
    fun ttsEngine(): Flow<TtsEngineChoice> =
        dataStore.data.map { TtsEngineChoice.fromStoreValue(it[KEY_TTS_ENGINE]) }

    /** 一次性读取 TTS 引擎选择（装配点 suspend 取值）。 */
    suspend fun ttsEngineOnce(): TtsEngineChoice = ttsEngine().first()

    /** 保存 TTS 引擎选择（运行时即时生效——下次通话装配读取）。 */
    suspend fun setTtsEngine(choice: TtsEngineChoice) {
        dataStore.edit { prefs -> prefs[KEY_TTS_ENGINE] = choice.storeValue }
    }

    companion object {
        /** DataStore key：ASR 引擎选择（值域 [AsrEngineChoice.storeValue]）。 */
        val KEY_ASR_ENGINE = stringPreferencesKey("voice_asr_engine")

        /** DataStore key：TTS 引擎选择（值域 [TtsEngineChoice.storeValue]）。 */
        val KEY_TTS_ENGINE = stringPreferencesKey("voice_tts_engine")
    }
}
