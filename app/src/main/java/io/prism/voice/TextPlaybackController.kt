package io.prism.voice

import android.content.Context
import android.util.Log
import io.prism.security.ApiKeyRepository
import io.prism.voice.cloud.MiniMaxTtsConfig
import io.prism.voice.cloud.MiniMaxTtsEngine
import io.prism.voice.rolepack.RolePackLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * TASK-20260910-001：文本聊天播报管理器——AI 回复的「播放」按钮后端。
 *
 * **引擎选择**：跟随语音通话设置（[VoiceEnginePrefs.ttsEngineOnce]，每次点击实时读取——
 * 设置页切换立即生效）：
 * - [TtsEngineChoice.MINIMAX]：引擎实例**点击时才构建并缓存**（用户要求文本对话不提前
 *   调用云端引擎，避免不必要的额度消耗）；缓存带**配置签名**（音色/Key/Group 变化即重建，
 *   guardrail Q3）；音色装配口径与 [VoiceEngineAssembly] 一致（cloneVoiceId 优先）；
 * - [TtsEngineChoice.SYSTEM]：[SystemTtsEngine] 懒创建复用（主线程创建约定）。
 *
 * **单播契约 + 代际守卫**（guardrail Q1）：同一时刻至多一条消息在播。所有强制停止路径
 * （toggle 换消息/同条停止）**先自增 [playbackGeneration] 再 stop**；[speakInternal] 的
 * 各挂起点之后与所有引擎回调（可能来自引擎回调线程）中校验代号——杜绝 DataStore/IO
 * 挂起窗口内的双播、陈旧 onDone/onError 误清新状态、stop 后凭空出声。
 *
 * **回调线程约定**（guardrail Q2）：TTS 引擎回调可能在独立线程派发（MiniMax callbacks
 * executor）——所有回调处理经 [scope]（Main.immediate）再派发，[SystemTtsEngine] 的
 * 创建/访问恒在主线程，跨线程可变状态写恒在主线程。
 *
 * **播报净化**：[io.prism.voice.rolepack.RolePackStyleApplier.stripActionText] 剥离
 * 动作/神态旁白段（toggle 内前置——纯旁白消息不进入 Playing 态，防按钮闪烁 Q8）。
 *
 * **降级**：云端播放失败 → 系统播报重播同文本（代际校验后）；ADR-048 降级口径。
 *
 * **生命周期**：[release] 由 ConversationViewModel.onCleared 调用（取消协程 + 释放引擎）。
 */
class TextPlaybackController(
    context: Context,
    private val voiceEnginePrefs: VoiceEnginePrefs?,
    private val apiKeyRepository: ApiKeyRepository,
    private val rolePackLoader: RolePackLoader?
) {

    /** 播报状态（UI 按钮据此渲染 ▶/⏹）。 */
    sealed interface State {
        data object Idle : State

        /** messageId = 正在播报的消息 id。 */
        data class Playing(val messageId: Long) : State
    }

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<State>(State.Idle)

    /** 播报状态流（UI collect）。 */
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 单播代际（guardrail Q1）：toggle/stop 在主线程自增；[speakInternal] 快照后于各
     * 挂起点与回调中校验——陈旧协程与陈旧回调一律失效。
     */
    @Volatile
    private var playbackGeneration = 0

    /** 懒创建的端侧 TTS（仅主线程创建/访问）。 */
    private var systemEngine: SystemTtsEngine? = null

    /** 懒创建并缓存的云端 TTS（点击时才构建；配置签名变化/释放后置 null 重建）。 */
    private var minimaxEngine: MiniMaxTtsEngine? = null

    /** 云端引擎缓存对应的配置签名（音色|Key指纹|语速；变化即重建，guardrail Q3）。 */
    private var minimaxConfigSig: String? = null

    /** 当前在播的引擎（stop 定向；仅主线程读写）。 */
    private var activeEngine: TtsEngine? = null

    /**
     * 点击播放/停止（主线程调用——SystemTtsEngine 创建约定）。
     * 同条消息点击 = 停止；异条 = 停旧播新。纯旁白消息（净化后为空）不进入 Playing 态。
     */
    fun toggle(messageId: Long, rawText: String) {
        val current = _state.value
        if (current is State.Playing && current.messageId == messageId) {
            stopInternal()
            return
        }
        // 净化前置（guardrail Q8）：纯旁白/空白消息直接忽略，防 ▶→⏹→▶ 闪烁
        val text = io.prism.voice.rolepack.RolePackStyleApplier.stripActionText(rawText)
        if (text.isBlank()) return
        stopInternal()
        playbackGeneration++
        val gen = playbackGeneration
        _state.value = State.Playing(messageId)
        scope.launch { speakInternal(gen, messageId, text) }
    }

    /** 停止当前播报（UI ⏫ 或切换消息；代际自增使 in-flight 协程与陈旧回调失效）。 */
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        playbackGeneration++
        activeEngine?.stop()
        _state.value = State.Idle
    }

    /** 释放一切（ConversationViewModel.onCleared）。 */
    fun release() {
        playbackGeneration++
        scope.cancel()
        _state.value = State.Idle
        runCatching { systemEngine?.release() }
        runCatching { minimaxEngine?.release() }
        systemEngine = null
        minimaxEngine = null
        minimaxConfigSig = null
        activeEngine = null
    }

    private suspend fun speakInternal(gen: Int, messageId: Long, text: String) {
        if (gen != playbackGeneration) return  // 挂起窗口内已被停止/替换
        val choice = runCatching { voiceEnginePrefs?.ttsEngineOnce() }
            .getOrNull() ?: TtsEngineChoice.SYSTEM
        if (gen != playbackGeneration) return  // 引擎选择读取挂起后复验
        val engine = when (choice) {
            TtsEngineChoice.MINIMAX -> ensureMinimaxEngine() ?: run {
                Log.w(TAG, "云端 TTS 引擎不可用，降级系统播报")
                systemTtsEngine()
            }
            TtsEngineChoice.SYSTEM -> systemTtsEngine()
        }
        if (gen != playbackGeneration) return  // 引擎构建挂起后复验
        activeEngine = engine
        // 引擎回调可能来自独立线程（MiniMax callbacks executor）——统一经 scope 派发回
        // 主线程处理（guardrail Q2：主线程创建约定 + 跨线程可见性 + 代际校验）
        engine.speak(text, object : TtsEngine.Listener {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                scope.launch { settle(gen, messageId) }
            }

            override fun onError(utteranceId: String, message: String) {
                scope.launch { onPlaybackError(gen, messageId, message, text, engine) }
            }
        })
    }

    /** 播完收敛：仅当代号一致且仍处于该消息的 Playing 态才回 Idle（防陈旧回调误清）。 */
    private fun settle(gen: Int, messageId: Long) {
        val s = _state.value
        if (s is State.Playing && s.messageId == messageId && gen == playbackGeneration) {
            _state.value = State.Idle
        }
    }

    /** 播放失败：云端引擎 → 代际校验后降级系统播报重播同文本；系统引擎失败 → 收敛 Idle。 */
    private fun onPlaybackError(gen: Int, messageId: Long, message: String, text: String, engine: TtsEngine) {
        Log.w(TAG, "文本播报失败：$message")
        if (gen != playbackGeneration) return  // 陈旧回调（已被停止/替换）——静默失效
        if (engine !== systemEngine) {
            Log.i(TAG, "文本播报降级系统引擎重试")
            val fallback = systemTtsEngine()
            activeEngine = fallback
            fallback.speak(text, object : TtsEngine.Listener {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) {
                    scope.launch { settle(gen, messageId) }
                }

                override fun onError(utteranceId: String, message: String) {
                    scope.launch {
                        Log.w(TAG, "降级播报仍失败：$message")
                        settle(gen, messageId)
                    }
                }
            })
            return
        }
        settle(gen, messageId)
    }

    /** 端侧 TTS 懒创建（Main.immediate 协程内，满足主线程创建约定）。 */
    private fun systemTtsEngine(): SystemTtsEngine =
        systemEngine ?: SystemTtsEngine(appContext).also { systemEngine = it }

    /**
     * 云端 TTS 懒创建（点击时才构建——不提前调用云端，避免不必要的额度消耗）。
     * 缓存带配置签名（音色|Key指纹|语速）——设置页换 Key/音色后下次点击自动重建
     * （guardrail Q3）。Key/GroupId 缺失或构建异常返回 null（调用方降级系统播报）。
     */
    private suspend fun ensureMinimaxEngine(): MiniMaxTtsEngine? {
        val pack = rolePackLoader?.effectiveRolePack()
        val voiceId = pack?.voice?.minimax?.cloneVoiceId?.takeIf { it.isNotBlank() }
            ?: pack?.voice?.minimax?.voiceId?.takeIf { it.isNotBlank() }
            ?: MiniMaxTtsConfig.DEFAULT_VOICE_ID
        val speed = pack?.voice?.minimax?.speed ?: 1.0f
        val apiKey = apiKeyRepository.readApiKeyOnce(MiniMaxTtsConfig.KEY_API_KEY)
        val groupId = apiKeyRepository.readApiKeyOnce(MiniMaxTtsConfig.KEY_GROUP_ID)
        if (apiKey.isNullOrBlank() || groupId.isNullOrBlank()) {
            Log.w(TAG, "MiniMax Key/GroupId 未配置，文本播报降级系统")
            return null
        }
        val sig = "$voiceId|${apiKey.hashCode()}|${groupId.hashCode()}|$speed"
        minimaxEngine?.let { cached ->
            if (minimaxConfigSig == sig) return cached
            // 配置变化（换 Key/音色/语速）：释放旧实例重建（guardrail Q3）
            Log.i(TAG, "MiniMax 配置变化，重建文本播报引擎")
            runCatching { cached.release() }
            minimaxEngine = null
            minimaxConfigSig = null
        }
        return try {
            val engine = MiniMaxTtsEngine(
                appContext,
                MiniMaxTtsConfig(apiKey = apiKey, groupId = groupId, voiceId = voiceId, speed = speed)
            )
            if (!engine.isAvailable) {
                Log.w(TAG, "MiniMax 引擎配置校验未通过")
                runCatching { engine.release() }
                return null
            }
            minimaxEngine = engine
            minimaxConfigSig = sig
            Log.i(TAG, "文本播报云端引擎就绪（voice=$voiceId）")
            engine
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // BR-error-handling-007
        } catch (e: Exception) {
            Log.w(TAG, "MiniMax 引擎构建失败：${e::class.simpleName}")
            null
        }
    }

    companion object {
        private const val TAG = "TextPlayback"
    }
}
