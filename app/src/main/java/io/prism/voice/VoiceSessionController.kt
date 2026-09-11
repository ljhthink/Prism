package io.prism.voice

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 批次20（VC-005，ADR-044）：语音通话会话控制器 —— 语音子系统的状态机编排层。
 *
 * **状态机**：`IDLE →(按住) RECORDING →(松开/识别完成) THINKING →(AI 回复完成) SPEAKING →(播完) IDLE`。
 * RECORDING 阶段松开先短暂经过 RECOGNIZING（系统引擎 onFinal 异步回调）。
 *
 * **会话集成**：最终转写文本经 [ConversationViewModel.sendMessage] 进入现有会话管线——
 * 工具调用（搜索/MCP/手机操控/todo）全生效；AI 回复完成后自动 TTS 播报（[TtsEngine]）。
 * 消息落库由 ConversationViewModel 现有逻辑完成（语音轮次与文字轮次完全同构，可回退/重试）。
 *
 * **并发守卫**：THINKING/SPEAKING 阶段禁用录音入口（防并发打断会话管线，PRD VC-005）。
 * **线程模型**：ASR/TTS 事件回调在主线程；[conversation.sendMessage] 要求主线程（UI 层调用约定）。
 *
 * **批次23-A（ADR-048，A5）双轨降级链**：云端引擎（手选，装配见 [VoiceEngineAssembly]）在
 * 层②启动探测 / 层③会话中失败（错误文案经 [shouldFallbackToOnDevice] 判定为降级类）时，
 * 本次通话自动切换端侧引擎（[VoiceOnDeviceEngines] 懒创建缓存）并**重试当前动作**
 * （ASR=回聆听、TTS=重播当前段），经 [fallbackNotice] 提示"云端不可用，已回退端侧"。
 * 未选云端引擎（supplier=null）时降级链关闭，行为与批次23-B 完全一致。
 */
class VoiceSessionController(
    private val conversation: io.prism.ui.chat.ConversationViewModel,
    asr: AsrEngine,
    tts: TtsEngine,
    /** 播报开关（用户可关）；默认开。 */
    private val speakEnabled: () -> Boolean = { true },
    /**
     * 批次23-B（ADR-048）：RolePack 角色包加载器（null = 无角色包能力，行为与批次22 完全一致）。
     *
     * 装配点：
     * - [startCall]：voice.system → [SystemTtsEngine.configure]（音高/语速）；
     * - [watchAssistantReply]：style.voiceCall 分段（仅用户启用非出厂角色包时生效）。
     * persona 注入装配点在 ConversationViewModel（语音轮次 systemPrompt 层替代）。
     */
    private val rolePackLoader: io.prism.voice.rolepack.RolePackLoader? = null,
    /**
     * 批次23-A（ADR-048，A5）：端侧降级引擎供给（null = 装配未选云端引擎，降级链关闭）。
     * 由 [VoiceEngineAssembly] 注入；首个降级事件经 [obtainOnDeviceEngines] 懒创建并缓存。
     */
    private val onDeviceEngineSupplier: (() -> VoiceOnDeviceEngines)? = null
) {

    // 引擎持有为 var（@Volatile 引用写原子）：降级链（层②/层③）运行时把云端引擎替换为
    // 端侧引擎（批次23-A，ADR-048 决策 3）。读写均以主线程为主（ASR 回调主线程派发；
    // TTS 回调线程仅经 scope.launch(Main) 改写状态）。
    @Volatile
    private var asr: AsrEngine = asr

    @Volatile
    private var tts: TtsEngine = tts

    /**
     * 批次22（SZ-101，ADR-047）：当前识别引擎包名（Shizuku pmGrant 引擎修复用；
     * 非系统引擎实现返回 null——跳过自动修复）。
     */
    fun peekEnginePackage(): String? = (asr as? SystemAsrEngine)?.peekEnginePackage()

    /**
     * 批次22（SZ-202）：当前是否流式常驻引擎（免按压连续对话形态）——UI 据此隐藏按住
     * 说话按钮、切换状态文案与隐私声明。
     */
    val isStreamingCall: Boolean get() = asr.isStreaming

    /** 通话阶段。 */
    enum class Phase { IDLE, RECORDING, RECOGNIZING, THINKING, SPEAKING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** 识别中增量文本（UI 实时回显）。 */
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    /** 最近一次发送的转写文本（轮次回显）。 */
    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    /** 音量等级 0..1（波形动画）。 */
    private val _volume = MutableStateFlow(0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    /** TTS 是否可用（不可用时覆盖层提示"仅文字模式"）。 */
    val ttsAvailable: Boolean get() = tts.isAvailable

    /** 批次23-A（ADR-048）：当前 ASR 是否云端引擎（覆盖层隐私声明区分——云端音频出设备）。 */
    val isCloudAsr: Boolean get() = asr is io.prism.voice.cloud.AliCloudAsrEngine

    // ==================== 批次23-A（ADR-048，A5）：双轨降级链 ====================

    /** 端侧降级引擎缓存（首个降级事件时经 supplier 懒创建，本次通话复用）。 */
    @Volatile
    private var onDeviceCache: VoiceOnDeviceEngines? = null

    /**
     * 批次23-A（ADR-048）：最近一次装配的系统播报参数（startCall 读角色包缓存）——
     * TTS 层②降级切端侧 SystemTtsEngine 后补装配，角色音高/语速不因降级丢失。
     */
    @Volatile
    private var cachedSystemVoice: io.prism.voice.rolepack.RolePack.SystemVoice? = null

    private val _fallbackNotice = MutableStateFlow<String?>(null)

    /** 降级 toast 文案（覆盖层消费后 [consumeFallbackNotice] 清除，防重组重复弹）。 */
    val fallbackNotice: StateFlow<String?> = _fallbackNotice.asStateFlow()

    /** 消费降级 toast（展示一次即清除）。 */
    fun consumeFallbackNotice() {
        _fallbackNotice.value = null
    }

    /** 装配期静默回退提示（Key 缺失等；[VoiceEngineAssembly] 调用，覆盖层 toast）。 */
    fun postFallbackNotice(text: String) {
        _fallbackNotice.value = text
    }

    /** 端侧降级引擎取用（首个降级事件懒创建并缓存；须在主线程调用）。 */
    private fun obtainOnDeviceEngines(): VoiceOnDeviceEngines? {
        onDeviceCache?.let { return it }
        val created = onDeviceEngineSupplier?.invoke() ?: return null
        onDeviceCache = created
        return created
    }

    /**
     * 层②启动探测 / 层③会话中降级（ASR）：当前引擎为云端且错误为降级类
     * （[shouldFallbackToOnDevice]）→ 本次通话切端侧引擎并**重试当前动作**（回聆听——
     * 层③"当句回退端侧重听"最小实现）。切换同步完成（@Volatile 引用写 + 引擎 cancel 线程
     * 安全），回聆听经主线程 scope 派发。非降级类错误/无端侧供给 → false（走普通错误路径）。
     */
    private fun tryFallbackAsr(message: String): Boolean {
        if (asr !is io.prism.voice.cloud.AliCloudAsrEngine) return false
        if (!shouldFallbackToOnDevice(VoiceEngineKind.ASR, message)) return false
        if (onDeviceEngineSupplier == null) return false
        val engines = obtainOnDeviceEngines() ?: return false
        val old = asr
        asr = engines.asr
        runCatching { old.cancel() }  // 云端会话断开（丢弃语义）
        _fallbackNotice.value = FALLBACK_TOAST
        Log.w(TAG, "云端 ASR 不可用，已回退端侧引擎（错误长度=${message.length}）")
        scope.launch {
            errorHandled = false
            streamingErrorRetries = 0
            _partialText.value = ""
            if (callActive) {
                _phase.value = Phase.IDLE
                // 端侧引擎若为流式（sherpa）→ 自动回聆听重试；系统引擎（按住说话形态）等待手势
                if (asr.isStreaming) enterListening(resetBefore = true)
            }
        }
        return true
    }

    /**
     * 层②启动探测降级（TTS）：当前引擎为云端且错误为降级类（探测失败文案自带"已回退"）
     * → 本次通话切端侧系统播报并**重试当前动作**（重播当前段，由调用方经 [retry] 注入；
     * 主线程执行——SystemTtsEngine 主线程约定）。鉴权/单句合成失败等非降级类错误不回退。
     */
    private fun tryFallbackTts(message: String, retry: (() -> Unit)? = null): Boolean {
        if (tts !is io.prism.voice.cloud.MiniMaxTtsEngine) return false
        if (!shouldFallbackToOnDevice(VoiceEngineKind.TTS, message)) return false
        if (onDeviceEngineSupplier == null) return false
        scope.launch {
            // 主线程取端侧引擎（SystemTtsEngine 主线程创建约定）并完成切换
            val engines = obtainOnDeviceEngines() ?: return@launch
            val old = tts
            tts = engines.tts
            runCatching { old.release() }
            cachedSystemVoice?.let { v ->
                (tts as? SystemTtsEngine)?.configure(v.pitch, v.speechRate)
            }
            _fallbackNotice.value = FALLBACK_TOAST
            Log.w(TAG, "云端 TTS 不可用，已回退端侧引擎（错误长度=${message.length}）")
            // 端侧 TTS 初始化异步（刚创建时 ready=false）——短暂等待就绪再重试当前动作，
            // 防回退后首句播报被"未就绪"错误丢弃（上限 2s；无 TTS 引擎设备照常文字呈现）
            var waitedMs = 0L
            while (waitedMs < TTS_FALLBACK_READY_WAIT_MS &&
                (tts as? SystemTtsEngine)?.isAvailable != true
            ) {
                kotlinx.coroutines.delay(TTS_FALLBACK_READY_POLL_MS)
                waitedMs += TTS_FALLBACK_READY_POLL_MS
            }
            retry?.invoke()
        }
        return true
    }

    /** AI 回复完成监听（挂断/下次通话防串扰）。 */
    private var watchJob: Job? = null

    /** 是否通话中（FGS 生命周期对齐）。 */
    val inCall: Boolean
        get() = _phase.value != Phase.IDLE || watchJob?.isActive == true || callActive

    @Volatile
    private var callActive = false

    /** 开启通话（FGS 保活启动 + 注册通知挂断回调；幂等）。 */
    fun startCall(context: android.content.Context) {
        if (callActive) return
        // guardrail P3-2：先启动 FGS，成功才置 callActive（失败时状态与"无保活"事实一致，
        // 仅提示——不阻断通话，前台场景麦克风仍可用）
        callActive = VoiceForegroundService.start(context)
        // guardrail P1-1：注册通知挂断回调——通知栏"挂断"触发完整挂断逻辑（状态机回写）
        VoiceForegroundService.hangUpRequested = { hangUp(context) }
        Log.i(TAG, "语音通话开始（FGS 启动=$callActive，流式引擎=${asr.isStreaming}）")
        // BUG-20260909-001 复测取证：TTS 输出被系统路由到蓝牙 A2DP（用户听手机扬声器 → "全程
        // 无声"假象，考古报告 13 段播放序列实证）。通话开始时记录输出路由；蓝牙设备连接时
        // 提示用户（App 无权改系统路由决策，提示是唯一可靠干预）。
        runCatching {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            val outputs = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            val hasBt = outputs?.any {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } == true
            Log.i(TAG, "音频输出设备：[${outputs?.joinToString(",") { it.type.toString() } ?: "?"}]（蓝牙连接=$hasBt）")
            if (hasBt) {
                postFallbackNotice("检测到蓝牙音频设备，播报将输出到蓝牙；若未听到声音请检查耳机连接")
            }
        }
        // 批次23-B（ADR-048）：装配角色包 voice.system → 系统 TTS 音高/语速（出厂 B0 = 1.0/1.0
        // 中性值，装配后行为不变；读取失败静默跳过——引擎保持默认参数，不阻断通话）。
        // MiniMax/GPT-SoVITS 音色映射（voice.minimax/gptsovits）随云端引擎选择器下批接线。
        rolePackLoader?.let { loader ->
            scope.launch {
                try {
                    val pack = loader.effectiveRolePack()
                    (tts as? SystemTtsEngine)?.configure(pack.voice.system.pitch, pack.voice.system.speechRate)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "角色包 voice.system 装配失败：${e::class.simpleName}")
                }
            }
        }
        // 批次22（SZ-202）：流式引擎 = 免按压连续对话——通话开始即持续聆听
        if (asr.isStreaming && asr.isAvailable) {
            enterListening(resetBefore = false)
        }
    }

    /** 是否允许开始录音（仅 IDLE；THINKING/SPEAKING/RECORDING 禁用——PRD VC-005 并发守卫）。 */
    fun canStartRecording(): Boolean =
        callActive && _phase.value == Phase.IDLE

    /**
     * 进入聆听（批次22 统一入口）。
     * @param resetBefore true 时先清引擎识别缓冲（回聆听场景，防 AI 播报残留被误提交）；
     *   false 保留在途音频（播报打断场景——用户正说的话不能丢）。
     */
    private fun enterListening(resetBefore: Boolean) {
        if (!asr.isAvailable) return
        if (resetBefore) asr.resetCapture()
        _partialText.value = ""
        errorHandled = false
        _phase.value = Phase.RECORDING
        asr.startListening(asrListener)
    }

    /**
     * 一轮结束（播报完成/跳过播报/会话忙拒绝）→ IDLE；流式引擎自动回聆听（清缓冲），
     * 连续对话循环（SZ-202）；系统引擎停在 IDLE 等待下一次按住。
     */
    private fun onTurnEnded() {
        _phase.value = Phase.IDLE
        if (asr.isStreaming && callActive) {
            enterListening(resetBefore = true)
        }
    }

    /** 引擎级错误防抖（批次20 R2 连环错误只取首个；每轮聆听开始时复位）。 */
    @Volatile
    private var errorHandled = false

    /** 批次22 R4-1：流式引擎连续错误重试计数（成功识别后清零）。 */
    private var streamingErrorRetries = 0

    /**
     * 批次22 修复轮四：思考期用户说"停"的静音标记——任务管线无法中途取消（会话守卫），
     * 但本轮回复到达时跳过 TTS 播报（仅文字呈现），尊重用户"停止"意图。
     */
    @Volatile
    private var muteNextBroadcast = false

    /**
     * BUG-20260909-005（打断链 TOCTOU 竞态根修）：播报链条代号。
     * 所有强制离开 SPEAKING 的路径（语义打断/挂断）**先自增代号再 tts.stop()**；
     * [speakSegments] 续播判定 `phase==SPEAKING && 代号一致`——根修 cb 线程 onDone 与
     * 主线程 phase 写之间无 happens-before 导致的幽灵段续播（真机 51.280 幽灵会话）。
     */
    @Volatile
    private var speakGeneration = 0

    /** 语义打断执行：代号自增（根修链条竞态）→ 停播 → 回聆听（保留在途音频）。 */
    private fun interruptPlaybackAndListen() {
        speakGeneration++
        tts.stop()
        if (asr.isStreaming && callActive) {
            enterListening(resetBefore = false)
        } else {
            _phase.value = Phase.IDLE
        }
    }

    /** 打断分类（见 [classifyInterruption]）。 */
    private enum class Interruption { PURE, WITH_CONTENT, NONE }

    /**
     * 停止/打断指令分类（BUG-20260909-005，替代旧 containsStopPhrase 子串匹配）。
     * - PURE：整句即停止/打断指令（正则整句锚定——旧实现单字"停"子串匹配，
     *   任意含"停"的 10 字回声转写都会误触发打断）；
     * - WITH_CONTENT：停止引导词 + 实际内容（"等一下，地址改成X"）→ 打断后余量
     *   作为新轮次 dispatch（旧实现整句丢弃，用户内容丢失="后续无声"感知的一半）；
     * - NONE：非打断（多为回声/环境音误识别）→ 丢弃。
     */
    private fun classifyInterruption(text: String): Pair<Interruption, String?> {
        val t = text.trim()
        if (t.length > 30) return Interruption.NONE to null  // 长句几乎不可能是打断指令（多为回声误识别）
        if (PURE_STOP_REGEX.matches(t)) return Interruption.PURE to null
        // 逐层剥离句首引导词（guardrail F2：嵌套停止语"停，别说了"不得把"别说了"当内容 dispatch；
        // F1：引导词后允许语气词——"等一下啊，X"）
        var rest = t
        var stripped = false
        repeat(3) {
            val next = STOP_LEAD_REGEX.replaceFirst(rest, "").trim()
            if (next == rest) return@repeat
            rest = next
            stripped = true
        }
        if (!stripped) return Interruption.NONE to null
        if (PURE_STOP_REGEX.matches(rest)) return Interruption.PURE to null
        // guardrail F3：余量须含实际文字（纯标点/语气词不算——"等一下，。。"→ 纯打断）
        if (rest.length >= 2 && rest.any { it.isLetterOrDigit() }) {
            return Interruption.WITH_CONTENT to rest
        }
        return Interruption.PURE to null  // 引导词 + 语气词/标点短尾（"等一下啊"）→ 纯打断
    }

    /** 常驻引擎监听器（批次22 抽为字段——流式引擎全程复用同一实例）。 */
    private val asrListener = object : AsrEngine.Listener {
        override fun onVolume(level: Float) {
            _volume.value = level
            // 批次22 修复轮四（2026-09-07）：**移除音量阈值打断**——真机实证 TTS 播报的回声
            // 经麦克风拾取（AEC 残留）即超阈值（0.32/0.54/0.57），播报开头两字即被打断
            //（"只能播出前两个字"）。打断改由语义判定（onFinal 停止词），音量仅驱动波形 UI。
            // 行业依据：豆包 Seeduplex/GPT-Live 的打断均为语义级判停（VAD/音量命中 ≠ 话权决策）。
        }

        override fun onPartial(text: String) {
            if (_phase.value != Phase.RECORDING) return
            streamingErrorRetries = 0  // 识别链路健康：清零错误重试计数
            _partialText.value = text
        }

        override fun onFinal(text: String) {
            // 批次22 修复轮四：**语义打断**（行业模式——播报/执行期仅停止词可接管话权）。
            // SPEAKING：停止/补充词 → 停 TTS 回聆听（用户继续说补充内容）；其余文本
            //（多为 TTS 回声/环境音误识别）→ 丢弃并重建流清缓冲。THINKING：停止词 →
            // 置静音标记（回复仅文字不播报）；其余丢弃。
            if (_phase.value == Phase.SPEAKING) {
                // BUG-20260909-005：打断分类——纯停止指令→停播回聆听；停止引导词+内容→
                // 打断后余量作为新轮次（旧实现整句丢弃，用户补充内容丢失）；其余丢弃
                val (kind, remainder) = classifyInterruption(text)
                when (kind) {
                    Interruption.WITH_CONTENT -> {
                        Log.i(TAG, "语义打断：停止+补充内容（余量=${remainder?.length}字）→ 打断并开新轮次")
                        interruptPlaybackAndListen()
                        remainder?.let { dispatchToConversation(it) }
                    }
                    Interruption.PURE -> {
                        Log.i(TAG, "语义打断：纯停止指令（长度=${text.length}）→ 停播回聆听")
                        interruptPlaybackAndListen()
                    }
                    Interruption.NONE -> {
                        Log.d(TAG, "SPEAKING 门丢弃非停词续话（长度=${text.length}）")
                        asr.resetCapture()  // 非打断语音（回声/环境音）→ 清流防缓冲累积
                    }
                }
                return
            }
            if (_phase.value == Phase.THINKING) {
                // 思考期：停止类指令（纯停止或带内容）→ 本轮回复仅文字不播报；
                // 带内容的余量在本轮无法 dispatch（会话忙），用户需在回复后重述（设计限制）
                val (kind, _) = classifyInterruption(text)
                if (kind != Interruption.NONE) {
                    Log.i(TAG, "语义打断：思考期收到停止指令 → 本轮回复仅文字不播报")
                    muteNextBroadcast = true
                }
                asr.resetCapture()
                return
            }
            if (_phase.value != Phase.RECORDING && _phase.value != Phase.RECOGNIZING) return
            streamingErrorRetries = 0
            _lastTranscript.value = text
            _partialText.value = ""
            dispatchToConversation(text)
        }

        override fun onError(message: String) {
            if (errorHandled) return  // 防抖：连环错误只取首个
            // 批次23-A（ADR-048，A5）：层②启动探测 / 层③会话中降级 —— 云端 ASR 失败
            //（文案含"已回退"或网络类）→ 本次通话切端侧引擎重试当前动作（回聆听）+ toast。
            // 鉴权/权限等非降级类错误返回 false，走下方原有防抖路径（文案原样上抛）。
            if (tryFallbackAsr(message)) return
            errorHandled = true
            Log.w(TAG, "ASR 错误：$message")
            _phase.value = Phase.IDLE
            _partialText.value = ""
            _lastTranscript.value = "（识别失败：$message）"
            // 批次22 R4-1：流式引擎异常退出后循环已死、UI 又隐藏了按住按钮（免按压形态）——
            // 自动重试（退避 1.5s，≤2 次）防"假聆听"；连续失败超限停回 IDLE（错误文案保留）
            if (asr.isStreaming && callActive && streamingErrorRetries < STREAMING_ERROR_RETRY_LIMIT) {
                streamingErrorRetries++
                scope.launch {
                    kotlinx.coroutines.delay(1500)
                    if (callActive && _phase.value == Phase.IDLE) {
                        enterListening(resetBefore = true)
                    }
                }
            }
        }
    }

    /** 按住：开始聆听。 */
    fun startRecording() {
        if (!canStartRecording()) {
            Log.i(TAG, "startRecording 被拒绝（phase=${_phase.value}）——并发守卫生效")
            return
        }
        if (!asr.isAvailable) {
            _partialText.value = ""
            // 批次20 真机修复（RCA 问题2）：ASR 不可用必须有 UI 回执（此前只打日志，
            // 用户感知"按住说话没有任何反应"）。乐观尝试已在引擎层兜底，此处为双保险提示。
            _lastTranscript.value = "（语音识别引擎不可用：请在系统设置中检查语音识别服务，或重启应用后重试）"
            Log.w(TAG, "ASR 不可用")
            _phase.value = Phase.IDLE
            return
        }
        enterListening(resetBefore = false)
    }

    /** 松开：停止聆听并等待最终结果（RECORDING → RECOGNIZING）。 */
    fun stopRecording() {
        if (_phase.value != Phase.RECORDING) return
        _phase.value = Phase.RECOGNIZING
        asr.stopListening()
    }

    /** 取消本次录音（滑出按钮/松手取消）。 */
    fun cancelRecording() {
        if (_phase.value != Phase.RECORDING && _phase.value != Phase.RECOGNIZING) return
        asr.cancel()
        _partialText.value = ""
        _phase.value = Phase.IDLE
    }

    /** 转写文本 → 会话管线（sendMessage 非阻塞；后续 watch AI 完成 → 播报）。 */
    private fun dispatchToConversation(text: String) {
        if (text.isBlank()) {
            onTurnEnded()
            return
        }
        // guardrail P3-5：会话正忙（用户同时发文字/AI 回复中）时 sendMessage 守卫会静默丢弃，
        // 语音轮次将无回执且 watcher 可能误播文字轮结果——预检后拒绝本轮并明示
        if (conversation.isTyping.value) {
            Log.i(TAG, "会话正忙（isTyping=true），语音轮次被拒")
            _lastTranscript.value = "（会话正忙，语音未发送：$text）"
            onTurnEnded()
            return
        }
        _phase.value = Phase.THINKING
        // 复用现有会话管线：工具调用/RAG/记忆/落库全部生效（ADR-044 决策核心）。
        // 「🎙 语音」标记（PRD VC-005）：USER 消息正文前缀，历史页可辨识语音轮次，
        // LLM 看到的语义不变（明确来自用户口述）。
        // 批次23-B（ADR-048）：前缀常量化（ConversationViewModel.VOICE_TURN_PREFIX）——
        // 该标记同时是角色包 persona 注入的"语音轮次"作用域判定依据。
        conversation.sendMessage(io.prism.ui.chat.ConversationViewModel.VOICE_TURN_PREFIX + text)
        watchAssistantReply()
    }

    /**
     * 监听 isTyping true→false 下降沿 → 取最后一条 ASSISTANT 回复播报。
     * 仅在 THINKING 阶段挂载（历史加载等无关下降沿不误播）。
     */
    private fun watchAssistantReply() {
        watchJob?.cancel()
        watchJob = scope.launch {
            conversation.isTyping.first { !it }
            if (_phase.value != Phase.THINKING) return@launch // 已挂断/取消
            val reply = lastAssistantContent()
            // 批次22 修复轮四：思考期用户说"停" → 本轮回复仅文字呈现（不播报）
            if (muteNextBroadcast) {
                Log.i(TAG, "静音标记生效：本轮回复不播报（用户已说停）")
                muteNextBroadcast = false
                onTurnEnded()
                return@launch
            }
            if (reply.isNullOrBlank() || !speakEnabled() || !tts.isAvailable) {
                Log.i(TAG, "跳过播报（reply 空=${reply.isNullOrBlank()} speakEnabled=${speakEnabled()} tts=${tts.isAvailable}）")
                onTurnEnded()
                return@launch
            }
            _phase.value = Phase.SPEAKING
            // 批次23-B（ADR-048）：style.voiceCall 分段播报——用户已启用角色包（非出厂 B0）时
            // 按 maxSentenceLen/sentenceBreak 切句逐段 speak（链式，最后一段 onDone 才回 listening，
            // 状态机语义不变）。出厂 B0 / 无角色包 → 单元素列表，与批次22 整段播报逐字节等价。
            val segments = resolveBroadcastSegments(reply)
            speakSegments(segments, 0, object : TtsEngine.Listener {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) {
                    if (_phase.value == Phase.SPEAKING) onTurnEnded()
                }

                override fun onError(utteranceId: String, message: String) {
                    Log.w(TAG, "TTS 失败：$message")
                    if (_phase.value == Phase.SPEAKING) onTurnEnded()
                }
            })
        }
    }

    /**
     * 批次23-B（ADR-048）：解析本轮播报分段列表（suspend——读角色包）。
     *
     * **生效条件**：用户已选中角色包且非出厂 B0（[RolePackLoader.effectiveRolePack] +
     * isBuiltin=false）——出厂默认行为不变是本批次的硬约束，B0 的 voiceCall 约束
     * （maxSentenceLen=30/sentenceBreak=true）**不应用**到出厂播报路径，仅作为 Schema
     * 出厂示例与回落兜底。读取失败降级为整段（不阻断播报；显式 catch 重抛
     * CancellationException，对齐 BR-error-handling-007 纪律）。
     */
    private suspend fun resolveBroadcastSegments(reply: String): List<String> {
        val loader = rolePackLoader ?: return listOf(reply)
        val pack = try {
            loader.effectiveRolePack()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "角色包 style 读取失败，整段播报：${e::class.simpleName}")
            return listOf(reply)
        }
        if (pack.isBuiltin) return listOf(reply)
        return io.prism.voice.rolepack.RolePackStyleApplier
            .apply(reply, pack.style.voiceCall)
            .ifEmpty { listOf(reply) }
    }

    /**
     * 批次23-B（ADR-048）：分段链式播报（逐段 speak，段间串行）。
     *
     * **状态机语义**（与原单段播报一致）：最后一段 onDone/onError 才转发给外部监听器
     * （驱动 SPEAKING → IDLE/回聆听）；任一段播完后若 phase 已离开 SPEAKING（语义打断/
     * 挂断），链条静默终止——打断路径已自行接管话权（enterListening），不允许残留分段
     * 续播抢回话权。单段场景与原 `tts.speak(reply, listener)` 行为等价
     * （onStart/onDone/onError 直通转发）。
     */
    private fun speakSegments(segments: List<String>, index: Int, listener: TtsEngine.Listener) {
        if (_phase.value != Phase.SPEAKING) return
        val gen = speakGeneration  // 链条代号快照（BUG-20260909-005：续播须代号一致，根修 TOCTOU）
        val segment = segments.getOrNull(index) ?: return
        tts.speak(segment, object : TtsEngine.Listener {
            override fun onStart(utteranceId: String) {
                if (index == 0) listener.onStart(utteranceId)
            }

            override fun onDone(utteranceId: String) {
                if (_phase.value != Phase.SPEAKING || speakGeneration != gen) {
                    // 打断/挂断：链条终止（不再续播）。留痕——旧实现静默死亡是"后续无声"排障盲区
                    Log.i(TAG, "播报链条终止：剩余 ${segments.size - index - 1} 段丢弃（打断/挂断/代号过期）")
                    return
                }
                if (index + 1 < segments.size) {
                    speakSegments(segments, index + 1, listener)
                } else {
                    listener.onDone(utteranceId)
                }
            }

            override fun onError(utteranceId: String, message: String) {
                // 批次23-A（ADR-048，A5）：层②启动探测降级 —— 云端 TTS speak 失败（探测
                // 超时文案自带"已回退"）→ 切端侧系统播报并**重试当前动作**（重播当前段；
                // 切换在主线程完成后 retry 才执行）。非降级类错误照常终止本轮。
                val fallbackTriggered = tryFallbackTts(message) {
                    if (_phase.value == Phase.SPEAKING && speakGeneration == gen) {
                        speakSegments(segments, index, listener)
                    }
                }
                if (fallbackTriggered) return
                // 任一段失败即终止本轮（剩余分段放弃，交外部监听器回写状态机）
                listener.onError(utteranceId, message)
            }
        })
    }

    /** 从会话消息取最后一条 ASSISTANT 的正文（工具卡等 TOOL 消息不播）。 */
    private fun lastAssistantContent(): String? =
        conversation.messages.value
            .lastOrNull { it.role == io.prism.ui.model.Role.ASSISTANT }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** 挂断：终止一切（录音/播报/监听 + FGS），复位状态机（通知挂断与 UI 挂断共用；幂等）。 */
    fun hangUp(context: android.content.Context) {
        speakGeneration++  // BUG-20260909-005：先失效播报链（防 cb 线程 onDone 竞态续播幽灵段）
        asr.cancel()
        tts.stop()
        watchJob?.cancel()
        watchJob = null
        _phase.value = Phase.IDLE
        _partialText.value = ""
        callActive = false
        // guardrail P1-1：清空回调钩子（防覆盖层销毁后通知挂断触发已释放的 controller）
        VoiceForegroundService.hangUpRequested = null
        VoiceForegroundService.stop(context)
        Log.i(TAG, "语音通话结束")
    }

    /** 永久释放（Screen 销毁；非挂断）。 */
    fun release() {
        asr.release()
        tts.release()
        scope.cancel()
        VoiceForegroundService.hangUpRequested = null
    }

    companion object {
        private const val TAG = "VoiceSession"

        /**
         * BUG-20260909-005：停止/打断指令词表（配合 [classifyInterruption] 整句锚定使用）。
         * 旧实现单字"停"做子串匹配——任意含"停"的回声转写（如"停下来聊"）都会误触发打断；
         * 现仅作 PURE 正则与引导词剥离的词根，且必须整句/句首锚定。
         * guardrail F1：补"停一下"；"我还要说"/"我还要"双变体（长词在先防 alternation 短路）。
         */
        private val STOP_PHRASES = listOf(
            "停", "停下", "停一下", "停止", "取消", "别说了", "闭嘴", "安静",
            "等一下", "等会", "稍等", "暂停", "打断",
            "我还要说", "我还要", "还有一件事", "补充一下", "我有补充"
        )

        /** 整句纯停止/打断指令（句尾允许语气词与标点；BUG-20260909-005）。 */
        private val PURE_STOP_REGEX = Regex(
            "^(?:" + STOP_PHRASES.joinToString("|") + ")" + "[吧呀呢啦哦嘛啊～~!！。，,．\\s]*$"
        )

        /** 句首停止引导词（允许一个语气词后随标点/空白才剥离——防"我们停不下来"类误剥离）。 */
        private val STOP_LEAD_REGEX = Regex(
            "^(?:" + STOP_PHRASES.joinToString("|") + ")" + "[吧呀呢啦哦嘛啊～]?[，,。\\s]+"
        )

        /** 批次22 R4-1：流式引擎错误自动重试上限（退避 1.5s/次，防错误风暴）。 */
        private const val STREAMING_ERROR_RETRY_LIMIT = 2

        /** 批次23-A（ADR-048，A5）：降级 toast 统一文案（层②/层③与装配期回退共用）。 */
        private const val FALLBACK_TOAST = "云端不可用，已回退端侧"

        /** 批次23-A（ADR-048，A5）：TTS 降级后等待端侧引擎就绪上限（防首句播报丢失）。 */
        private const val TTS_FALLBACK_READY_WAIT_MS = 2_000L

        /** 批次23-A（ADR-048，A5）：端侧 TTS 就绪轮询间隔。 */
        private const val TTS_FALLBACK_READY_POLL_MS = 100L
    }
}
