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
 * LVC-1（ADR-053）新增 [LOCAL_CLONE]：端侧本地音色克隆（ZipVoice，离线、零费用）。
 *
 * @property storeValue 持久化值（DataStore String；[SYSTEM] 为出厂默认）。
 */
enum class TtsEngineChoice(val storeValue: String) {
    /** 系统播报（出厂默认：系统 TextToSpeech，零依赖）。 */
    SYSTEM("system"),

    /** 云端播报（MiniMax T2A v2；仅 API Key + GroupId 就绪时设置页可选）。 */
    MINIMAX("minimax"),

    /**
     * 本地昔涟音色（LVC-1，ADR-053）：端侧 ZipVoice 零样本克隆，完全离线、零费用。
     * 仅当生效角色包声明 `voice.local.voiceId` 且对应本地音色包已侧载就绪时可选。
     */
    LOCAL_CLONE("localClone");

    companion object {
        /**
         * 纯函数（JVM 单测见 VoiceEnginePrefsTest）：持久化原始值 → 引擎选择。
         * null/空白/非法值一律回落 [SYSTEM]（向后兼容出厂数据 + 防脏数据破坏通话可用性）。
         *
         * **值域只增不改**：既有 storeValue 不得改名或复用（否则已持久化的用户选择会串档）。
         */
        fun fromStoreValue(raw: String?): TtsEngineChoice {
            val value = raw?.trim()
            return when {
                value.equals(MINIMAX.storeValue, ignoreCase = true) -> MINIMAX
                value.equals(LOCAL_CLONE.storeValue, ignoreCase = true) -> LOCAL_CLONE
                else -> SYSTEM
            }
        }
    }
}

/**
 * TKN-LVC-016（ADR-056）：本地音色播报管线模式。
 *
 * @property storeValue 持久化值（DataStore String；[CONTINUOUS] 为出厂默认）。
 */
enum class LocalClonePlaybackMode(val storeValue: String) {
    /**
     * 连续（出厂默认）：合成与播放**重叠**——合成协程按段推进，播放端取到一段即写同一条常开音轨。
     * 首声延迟 = 首段合成时长。
     */
    CONTINUOUS("continuous"),

    /**
     * 整段：全部语句合成完成后拼接为一条 PCM，**只播放一次**。
     * 结构上不可能被合成打断；代价是首声延迟 = 全段合成总时长（故有长度上限，超限自动回落连续）。
     */
    WHOLE("whole");

    companion object {
        /**
         * 纯函数（JVM 单测见 VoiceEnginePrefsTest）：持久化原始值 → 播报模式。
         * null/空白/非法值一律回落 [CONTINUOUS]（向后兼容出厂数据 + 防脏数据破坏播报可用性）。
         */
        fun fromStoreValue(raw: String?): LocalClonePlaybackMode =
            if (raw?.trim()?.equals(WHOLE.storeValue, ignoreCase = true) == true) WHOLE else CONTINUOUS
    }
}

/**
 * TKN-LVC-016（ADR-056）：本地音色（ZipVoice）播报档位。
 *
 * 三档只调**步数**与**线程数**两个旋钮——这是 PC 侧逐轴实测（`.tmp-cyrene/bench-zipvoice.json`）
 * 唯一被证实稳定收益的两个：步数 8→4 = 2.16×、线程 2→4 = 1.60×（合计约 3.46×）。
 *
 * **刻意不含"缩短参考音频"**：该轴虽然另有 1.46×，但补丁4 已实测短参考会让生成 F0 漂移
 * +40~80Hz（听感"尖锐"、相似度 0.79 vs 0.94）——那是用户已否决过的听感退化，不能藏进"提速档"。
 *
 * @property storeValue 持久化值（DataStore String；[QUALITY] 为出厂默认）。
 */
enum class LocalVoiceTier(val storeValue: String) {
    /** 质量优先（出厂默认）：完全沿用音色包清单里的参数，**听感基线与改造前逐字段一致**。 */
    QUALITY("quality"),

    /** 均衡：6 步 / 3 线程（步数 8→6、线程 2→3）。 */
    BALANCED("balanced"),

    /** 速度优先：4 步 / 4 线程（PC 实测三轴合计 RTF 3.449→0.717，其中本档只吃步数与线程两轴）。 */
    SPEED("speed");

    companion object {
        /**
         * 纯函数（JVM 单测见 VoiceEnginePrefsTest）：持久化原始值 → 档位。
         * null/空白/非法值一律回落 [QUALITY]（**回落方向必须是最保真的那一档**：
         * 脏数据不该把用户的听感基线悄悄换成低质量档）。
         */
        fun fromStoreValue(raw: String?): LocalVoiceTier {
            val value = raw?.trim()
            return when {
                value.equals(BALANCED.storeValue, ignoreCase = true) -> BALANCED
                value.equals(SPEED.storeValue, ignoreCase = true) -> SPEED
                else -> QUALITY
            }
        }
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

    /** 观察本地音色播报模式（未配置 = [LocalClonePlaybackMode.CONTINUOUS] 出厂默认）。 */
    fun localPlaybackMode(): Flow<LocalClonePlaybackMode> =
        dataStore.data.map { LocalClonePlaybackMode.fromStoreValue(it[KEY_LOCAL_PLAYBACK_MODE]) }

    /** 一次性读取本地音色播报模式（播报前取值）。 */
    suspend fun localPlaybackModeOnce(): LocalClonePlaybackMode = localPlaybackMode().first()

    /** 保存本地音色播报模式（运行时即时生效——下次播报读取）。 */
    suspend fun setLocalPlaybackMode(mode: LocalClonePlaybackMode) {
        dataStore.edit { prefs -> prefs[KEY_LOCAL_PLAYBACK_MODE] = mode.storeValue }
    }

    /** 观察本地音色播报档位（未配置 = [LocalVoiceTier.QUALITY] 出厂默认）。 */
    fun localVoiceTier(): Flow<LocalVoiceTier> =
        dataStore.data.map { LocalVoiceTier.fromStoreValue(it[KEY_LOCAL_VOICE_TIER]) }

    /** 一次性读取本地音色播报档位（播报前取值）。 */
    suspend fun localVoiceTierOnce(): LocalVoiceTier = localVoiceTier().first()

    /** 保存本地音色播报档位（运行时即时生效——引擎缓存签名随之变化 ⇒ 自动重建引擎）。 */
    suspend fun setLocalVoiceTier(tier: LocalVoiceTier) {
        dataStore.edit { prefs -> prefs[KEY_LOCAL_VOICE_TIER] = tier.storeValue }
    }

    companion object {
        /** DataStore key：ASR 引擎选择（值域 [AsrEngineChoice.storeValue]）。 */
        val KEY_ASR_ENGINE = stringPreferencesKey("voice_asr_engine")

        /** DataStore key：TTS 引擎选择（值域 [TtsEngineChoice.storeValue]）。 */
        val KEY_TTS_ENGINE = stringPreferencesKey("voice_tts_engine")

        /** DataStore key：本地音色播报模式（值域 [LocalClonePlaybackMode.storeValue]）。 */
        val KEY_LOCAL_PLAYBACK_MODE = stringPreferencesKey("voice_local_playback_mode")

        /** DataStore key：本地音色播报档位（值域 [LocalVoiceTier.storeValue]）。 */
        val KEY_LOCAL_VOICE_TIER = stringPreferencesKey("voice_local_voice_tier")
    }
}
