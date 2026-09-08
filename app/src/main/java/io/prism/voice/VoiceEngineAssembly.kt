package io.prism.voice

import android.content.Context
import android.util.Log
import io.prism.security.ApiKeyRepository

/**
 * 批次23-A（ADR-048，A5）：端侧降级引擎对（懒创建——首个降级事件才占用端侧资源，本次
 * 通话复用；释放由 [VoiceSessionController.release] 引用比对补收，防双重释放）。
 *
 * @property asr 端侧 ASR（AsrEngineFactory 既有降级链：sherpa 流式端侧 → 系统按住说话）
 * @property tts 端侧 TTS（系统 TextToSpeech）
 */
class VoiceOnDeviceEngines(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 端侧 ASR（懒创建；首次访问须在主线程——enterListening 主线程编排约定）。 */
    val asr: AsrEngine by lazy { AsrEngineFactory.create(appContext) }

    /** 端侧 TTS（懒创建；首次访问须在主线程——TextToSpeech 主线程创建约定）。 */
    val tts: TtsEngine by lazy { SystemTtsEngine(appContext) }
}

/**
 * 批次23-A（ADR-048，A4/A5）：通话链路引擎装配器 —— 按 [VoiceEnginePrefs] 手选引擎构建
 * [VoiceSessionController]（A4 设置页选择 → A5 通话装配 + 降级链）。
 *
 * **装配规则**（ADR-048 决策 3：用户手选 → Key 就绪 → 探测失败回端侧）：
 * - ASR=aliCloud：读 DashScope Key（[io.prism.voice.cloud.AliCloudAsrEngine.KEY_REF]）→
 *   [AsrEngineFactory.create](context, key)（云端流式常驻）；Key 缺失 → **静默回退端侧**
 *   （AsrEngineFactory 既有链）+ 装配提示（覆盖层 toast）。
 * - TTS=minimax：读 MiniMax Key/GroupId（KEY_API_KEY/KEY_GROUP_ID 分条）→
 *   [io.prism.voice.cloud.MiniMaxTtsEngine]；voiceId/speed 从
 *   effectiveRolePack().voice.minimax 取值（RolePack 缺省/字段缺省 → 引擎默认）；Key 缺失
 *   → **静默回退系统播报** + 装配提示。
 * - 降级链供给：注入端侧引擎懒创建缓存（层②启动探测 / 层③会话中失败时由
 *   VoiceSessionController 消费，本次通话切端侧重试当前动作）。
 *
 * **调用线程**：suspend（DataStore/Key/RolePack 读取）——VoiceCallOverlay 于组合期
 * LaunchedEffect 调用，装配完成前覆盖层显示"准备中"占位。
 */
object VoiceEngineAssembly {

    private const val TAG = "VoiceEngineAssembly"

    /**
     * 按用户手选装配语音通话控制器。
     *
     * @param context 任意上下文（内部取 applicationContext，引擎生命周期对齐覆盖层）
     * @param conversation 会话 ViewModel（语音轮次管线，主线程约定不变）
     * @param rolePackLoader 角色包加载器（MiniMax 音色取值 + 既有 voice.system/style 装配；可 null）
     * @param voiceEnginePrefs 引擎手选持久化（A4 设置页写入）
     * @param apiKeyRepository 加密 Key 仓库（DashScope / MiniMax Key 读取）
     * @return 就绪的 [VoiceSessionController]（装配期静默回退提示经 fallbackNotice toast）
     */
    suspend fun create(
        context: Context,
        conversation: io.prism.ui.chat.ConversationViewModel,
        rolePackLoader: io.prism.voice.rolepack.RolePackLoader?,
        voiceEnginePrefs: VoiceEnginePrefs,
        apiKeyRepository: ApiKeyRepository,
    ): VoiceSessionController {
        val appContext = context.applicationContext
        val notices = mutableListOf<String>()

        // ---- ASR：手选云端 → Key 就绪才构建；Key 缺失静默回退端侧 ----
        val asr: AsrEngine = when (voiceEnginePrefs.asrEngineOnce()) {
            AsrEngineChoice.ALI_CLOUD -> {
                val key = runCatching {
                    apiKeyRepository.readApiKeyOnce(io.prism.voice.cloud.AliCloudAsrEngine.KEY_REF)
                }.getOrNull()
                if (key.isNullOrBlank()) {
                    notices.add("云端识别 Key 未配置，已回退端侧识别")
                    Log.w(TAG, "云端 ASR Key 缺失，装配回退端侧")
                    AsrEngineFactory.create(appContext)
                } else {
                    AsrEngineFactory.create(appContext, key)
                }
            }
            AsrEngineChoice.SYSTEM -> AsrEngineFactory.create(appContext)
        }

        // ---- TTS：手选 MiniMax → Key+GroupId 就绪才构建；音色/语速取生效角色包 voice.minimax ----
        val tts: TtsEngine = when (voiceEnginePrefs.ttsEngineOnce()) {
            TtsEngineChoice.MINIMAX -> {
                val apiKey = runCatching {
                    apiKeyRepository.readApiKeyOnce(io.prism.voice.cloud.MiniMaxTtsConfig.KEY_API_KEY)
                }.getOrNull()
                val groupId = runCatching {
                    apiKeyRepository.readApiKeyOnce(io.prism.voice.cloud.MiniMaxTtsConfig.KEY_GROUP_ID)
                }.getOrNull()
                if (apiKey.isNullOrBlank() || groupId.isNullOrBlank()) {
                    notices.add("云端 TTS Key 未配置，已回退系统播报")
                    Log.w(TAG, "云端 TTS Key/GroupId 缺失，装配回退系统播报")
                    SystemTtsEngine(appContext)
                } else {
                    val pack = runCatching { rolePackLoader?.effectiveRolePack() }
                        .getOrNull() ?: io.prism.voice.rolepack.RolePack.DEFAULT_ROLE_PACK
                    io.prism.voice.cloud.MiniMaxTtsEngine(
                        appContext,
                        io.prism.voice.cloud.MiniMaxTtsConfig(
                            apiKey = apiKey,
                            groupId = groupId,
                            // 批次23（ADR-048）：克隆音色优先——MiniMax 平台克隆得到的自定义
                            // voice_id 在合成接口中与系统音色同参（voice_id），无需独立字段；
                            // 未克隆时回退 RolePack.voiceId，再回退引擎默认
                            voiceId = pack.voice.minimax.cloneVoiceId?.takeIf { it.isNotBlank() }
                                ?: pack.voice.minimax.voiceId?.takeIf { it.isNotBlank() }
                                ?: io.prism.voice.cloud.MiniMaxTtsConfig.DEFAULT_VOICE_ID,
                            speed = pack.voice.minimax.speed
                        )
                    )
                }
            }
            TtsEngineChoice.SYSTEM -> SystemTtsEngine(appContext)
        }

        val controller = VoiceSessionController(
            conversation = conversation,
            asr = asr,
            tts = tts,
            rolePackLoader = rolePackLoader,
            // 层②/层③降级供给：端侧引擎懒创建缓存（首个降级事件才占用端侧资源）
            onDeviceEngineSupplier = { VoiceOnDeviceEngines(appContext) }
        )
        notices.joinToString("；").takeIf { it.isNotEmpty() }?.let(controller::postFallbackNotice)
        return controller
    }
}
