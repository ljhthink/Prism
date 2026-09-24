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

    /**
     * LVC-1（ADR-053）：本地音色包仓库（**单实例复用**——仓库内含 `espeak-ng-data` 递归尺寸缓存，
     * 每次点击新建实例会把缓存作废，热路径退化为每次遍历 ~700 个文件）。
     */
    private val localVoiceStore by lazy {
        io.prism.voice.local.LocalVoicePackStore(
            io.prism.voice.local.LocalVoicePackStore.defaultRoots(appContext)
        )
    }

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

    /** LVC-1（ADR-053）：本地音色引擎（点击时才构建；音色包/步数变化即重建）。 */
    private var localCloneEngine: io.prism.voice.local.LocalCloneTtsEngine? = null

    /** 本地音色引擎缓存签名（[io.prism.voice.local.localCloneCacheKey]：全部生成参数 + voiceId + 参考集条数 + 模型资产身份）。 */
    private var localCloneSig: String? = null

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
        // LVC-1：本地引擎持有 195MB 级 native 资源——必须显式释放（release 内部等 in-flight 合成退出）
        runCatching { localCloneEngine?.release() }
        systemEngine = null
        minimaxEngine = null
        minimaxConfigSig = null
        localCloneEngine = null
        localCloneSig = null
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
            // LVC-1（ADR-053）：本地音色（端侧 ZipVoice）——不可用时降级系统播报（口径同云端）
            TtsEngineChoice.LOCAL_CLONE -> ensureLocalCloneEngine() ?: run {
                Log.w(TAG, "本地音色引擎不可用，降级系统播报")
                systemTtsEngine()
            }
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

    /**
     * LVC-1（ADR-053）：本地音色引擎懒创建（点击时才构建——不在 App 启动/进入页面时加载
     * 195MB 级模型；构造本身不加载模型，首次合成才在 IO 线程加载）。
     *
     * 缓存带签名（voiceId|numSteps|参考集规模）——换角色包/换音色包/改步数后下次点击自动重建。
     * 任何失败（未声明 voiceId / 音色包未安装或不完整 / 引擎创建失败）返回 null，
     * 调用方降级系统播报。**整个方法体都在 try/catch 内**（含角色包读取与音色包校验——
     * guardrail R3 新-3：原实现的 try 只从工厂调用处开始，校验段是 Error 的唯一逃逸点）：
     * 本方法在 `Dispatchers.Main.immediate` 的 SupervisorJob 协程内被调用，异常抛穿即崩溃
     * （对齐 [ensureMinimaxEngine] 的保护口径）。
     */
    private suspend fun ensureLocalCloneEngine(): io.prism.voice.local.LocalCloneTtsEngine? = try {
        val pack = rolePackLoader?.effectiveRolePack()
        val voiceId = pack?.voice?.local?.voiceId?.takeIf { it.isNotBlank() }
        if (voiceId == null) {
            Log.w(TAG, "角色包未声明本地音色（voice.local.voiceId），降级系统播报")
            null
        } else {
            val status = localVoiceStore.status(voiceId)
            if (status !is io.prism.voice.local.LocalVoiceStatus.Ready) {
                Log.w(TAG, "本地音色包不可用（voice=$voiceId），降级系统播报")
                null
            } else {
                // 档位（TKN-LVC-016）：用户在设置页的主动选择，**优先于**角色包内置的 numSteps ——
                // 否则用户切了档位听不到变化，属"改了没效果"（见 LocalVoiceTiering KDoc 的优先级排列）。
                val tier = voiceEnginePrefs?.localVoiceTierOnce()
                    ?: io.prism.voice.LocalVoiceTier.QUALITY
                // 播报模式：出题"连续（默认）/ 整段"，在**引擎构造期**定死
                val playbackMode = voiceEnginePrefs?.localPlaybackModeOnce()
                    ?: io.prism.voice.LocalClonePlaybackMode.CONTINUOUS
                val config = io.prism.voice.local.tieredLocalCloneConfig(
                    base = status.pack.generation,
                    rolePackNumSteps = pack.voice.local.numSteps,
                    tier = tier
                )
                // 缓存键必须覆盖**全部**生效输入（guardrail F-3/N-1/N-2）：引擎在构造时把 config 与模型
                // 都定死，键少一项就会"改了没效果"。纯函数 + 逐字段单测见 LocalCloneCacheKey.kt。
                val sig = io.prism.voice.local.localCloneCacheKey(
                    voiceId = voiceId,
                    tts = status.pack.tts,
                    referenceCount = status.pack.references.size,
                    config = config,
                    vits = status.pack.vitsGeneration,
                    // 模式也是**构造期**输入（引擎按它决定走哪条管线）⇒ 必须进键，
                    // 否则用户在设置页切换"连续/整段"后本链路会继续复用旧引擎（guardrail B2）。
                    playbackMode = playbackMode,
                    // 参与身份的文件与目录由 LocalVoicePack.assetFiles/assetDirs 单点定义（按引擎分流）。
                    // 目录也纳入：VITS 的 dict/ 被整体替换时目录 mtime 会变，而单看文件列表察觉不到（N4）。
                    modelIdentity = io.prism.voice.local.localCloneModelIdentity(
                        status.pack.assetFiles + status.pack.assetDirs
                    )
                )
                val cached = localCloneEngine
                if (cached != null && localCloneSig == sig) {
                    cached
                } else {
                    if (cached != null) {
                        Log.i(TAG, "本地音色配置变化，重建播报引擎")
                        runCatching { cached.release() }
                        localCloneEngine = null
                        localCloneSig = null
                    }
                    val engine = io.prism.voice.local.LocalCloneEngineFactory.create(
                        pack = status.pack,
                        config = config,
                        playbackMode = playbackMode
                    )
                    if (engine == null) {
                        Log.w(TAG, "本地音色引擎创建失败（sherpa 实现不可解析），降级系统播报")
                        null
                    } else {
                        localCloneEngine = engine
                        localCloneSig = sig
                        Log.i(
                            TAG,
                            "文本播报本地音色引擎就绪（voice=$voiceId, steps=${config.numSteps}, " +
                                "threads=${config.numThreads}, tier=${tier.storeValue}, " +
                                "mode=${playbackMode.storeValue}）"
                        )
                        engine
                    }
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // BR-error-handling-007
    } catch (e: Exception) {
        Log.w(TAG, "本地音色引擎构建/校验失败：${e::class.simpleName}")
        null
    } catch (e: Throwable) {
        // Error（含 NoClassDefFoundError 等）同样不得抛穿调用方协程；本地引擎是增强能力，
        // 任何失败都应收敛为"降级系统播报"而不是崩溃
        Log.w(TAG, "本地音色引擎不可用（${e::class.simpleName}），降级系统播报")
        null
    }

    companion object {
        private const val TAG = "TextPlayback"
    }
}