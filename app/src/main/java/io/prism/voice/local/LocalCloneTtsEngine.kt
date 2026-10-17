package io.prism.voice.local

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import io.prism.voice.LocalClonePlaybackMode
import io.prism.voice.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * LVC-1（ADR-053）：一次合成的产出（采样率由引擎给出——ZipVoice 经 `vocos_24khz` 输出 24kHz，
 * 但以引擎回报值为准，不硬编码）。
 */
data class LocalCloneAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    /**
     * 生成返回时**取消探针是否为真**（即我们已放弃该音频；等价于"回调曾返回 STOP"的**充分不必要**观测——
     * 反向误报窗口见 guardrail TKN-LVC-011 N-5）。
     *
     * 只有"确实放弃这段音频"（用户停止/被取代/已释放）才会为真；**超时不再触发中止**
     * （TKN-LVC-011：native 不可中断，超时无法止损，只会让 sherpa 丢弃其后全部分段 ⇒ 残音频）。
     * 为真时音频可能不完整，引擎据此记 WARN，并按"放弃"语义静默收尾。
     */
    val truncated: Boolean = false
)

/**
 * LVC-1（ADR-053）：合成后端抽象。
 *
 * **为什么抽这一层**：sherpa-onnx 的 `OfflineTts` 是 Android/JNI 类，JVM 单测无法实例化；
 * 把 native 调用收在实现类里（[io.prism.voice.local.SherpaZipVoiceSynthesizer] 位于
 * `src/sherpa/kotlin` 条件源集，主源集经反射创建），引擎的状态机/看门狗/回调契约即可用
 * 假实现在 JVM 上完整验证。
 */
interface LocalCloneSynthesizer {
    /**
     * 合成（**阻塞调用**，须在 IO 调度器执行；且**同一实例不得并发调用**——由引擎单飞保证）。
     *
     * @param text 已规范化的目标文本
     * @param reference 选中的参考音频（含逐字对应的官方台词原文）
     * @param config 生成参数
     * @param isCancelled 取消探针——实现应周期调用并在为 true 时尽早中止（ZipVoice 经
     *   `generateWithConfigAndCallback` 的回调返回 0 实现；**这是阻塞 native 调用唯一的
     *   可中断点**）
     * @return 合成音频；中止/失败可返回空样本（引擎侧按失败处理）
     */
    /**
     * 合成一段文本（阻塞直到完成、或因 [isCancelled] 变真而被中止）。
     *
     * @param reference 参考音频（**可空**，ADR-055）：ZipVoice **必填**（音色与韵律的唯一来源），
     *   VITS **必须为 null**（音色在权重里；引擎按 `pack.tts` 分流，不合法组合 ⇒ 可回退失败而非 native 崩溃）
     * @param isCancelled 取消探针——**必须**在入口与生成回调中检查（返回 true ⇒ 立即中止生成）
     * @param onChunk 分段心跳（TKN-LVC-011 新-4）：每产出一个分段回调一次。
     *   看门狗据此判定"合成仍在推进"，避免长文本被绝对时限误杀（真机实证：误杀会让 sherpa
     *   丢弃后续分段，产出**残音频**，表现为"播报播到一半停止"）。默认空实现以兼容既有调用方。
     */
    fun synthesize(
        text: String,
        reference: LocalReference?,
        config: LocalCloneConfig,
        isCancelled: () -> Boolean,
        onChunk: (FloatArray) -> Unit = {}
    ): LocalCloneAudio

    /**
     * 声明本合成器的输出采样率（Hz）。
     *
     * **为什么需要单独声明**（LVC-2 流式播放）：流式播放必须在**收到第一块音频之前**就打开音轨
     * （AudioTrack 的采样率是构造期参数、不可中途更改），而 [synthesize] 的返回值要等整段生成结束
     * 才拿得到——此时首块音频早已到达。`OfflineTts.sampleRate()` 是权威来源（ZipVoice 经
     * `vocos_24khz` 输出 24kHz，但不硬编码）。
     *
     * @return 采样率；**返回 ≤0 表示本实现不声明**（默认实现即如此）⇒ 引擎退回
     *   [LocalClonePlayer.play] 的「整段合成→整段播放」路径。默认值刻意不用"猜一个 24000"：
     *   猜错只会静默变调，而退回整段播放是**可听且可测**的降级。
     */
    fun sampleRate(): Int = 0

    /** 释放 native 资源（**须在无 in-flight [synthesize] 时调用**）。 */
    fun release()
}

/** LVC-1：PCM 播放后端抽象（生产为 [AudioTrackLocalClonePlayer]；测试注入假实现）。 */
interface LocalClonePlayer {
    /**
     * 播放（阻塞直到播完、被 [stop] 打断、或 [isCancelled] 变真）。
     *
     * @param isCancelled 取消探针——**必须**在入口与循环中检查：`stop()` 与 `play()` 之间存在
     *   时间窗口（音轨构建），仅靠内部停止标记会丢失"早到的停止"（guardrail F-2）
     */
    fun play(samples: FloatArray, sampleRate: Int, isCancelled: () -> Boolean)

    /**
     * 打开**流式播放会话**（一条常开音轨，逐块写入、边收边播）——LVC-2。
     *
     * **为什么必须有它**：`play()` 的语义是"把一整段 PCM 播完才返回"，而合成侧的
     * `onChunk` 是**按内部句子逐块**回调的（上游 `offline-tts-zipvoice-impl.h` 每段生成完即
     * `callback(cur.samples...)`，实测各块样本数之和**恰等于**整段样本数 ⇒ 块是增量音频）。
     * 只有把每块立刻写进音轨，首声才不必等整段算完。
     *
     * @param sampleRate 音轨采样率（来自 [LocalCloneSynthesizer.sampleRate]）
     * @param isCancelled 取消探针（会话内每次写入前与轮询中检查）
     * @return 会话；**返回 null 表示本实现不支持流式**（默认实现即如此）⇒ 引擎退回 [play]。
     *   刻意用可空默认实现而非抽象方法：生产实现只有 `AudioTrack`，而既有单测的假播放器无需改动
     *   即可继续覆盖整段播放路径。
     */
    fun openStream(sampleRate: Int, isCancelled: () -> Boolean): LocalCloneStreamSession? = null

    /** 立即静音（可从任意线程调用；并使**当前及更早**的播放作废）。 */
    fun stop()

    /** 释放（幂等）。 */
    fun release()
}

/**
 * LVC-2：流式播放会话（由 [LocalClonePlayer.openStream] 打开）。
 *
 * 契约：
 * - [write] **可以阻塞**（音轨缓冲满时轮询等待其被消费完）——这是流式路径唯一的流控点；
 * - [write] 返回 false 表示**会话已作废**（被 `stop()`／新请求取代／已取消／输出栈卡死），
 *   调用方必须立即停止写入；返回值**不区分**这些成因，调用方用取消探针区分（见引擎侧用法）；
 * - [close] 负责**排空**已写入但尚未播出的音频（否则尾音被截断），随后释放音轨；幂等。
 */
interface LocalCloneStreamSession {
    /** 已写入音轨的帧数（诊断用；不含被丢弃的部分）。 */
    val framesWritten: Int

    /**
     * 写入一块 PCM（Float32，[-1,1]）。
     *
     * **空数组 = 无操作**（返回 true、不入队任何帧）：刻意不把"这一块没有音频"与"会话已作废"
     * 混成同一个返回值——调用方据此判定"输出栈是否失效"，混淆会让一次空回调被误判为永久失效
     * 并丢弃其后全部分段（guardrail F-2）。判定逻辑见纯函数 [streamWriteAccepted]（可单测）。
     *
     * @return true = 本块已全部入队（或为空块，无事可做）；false = 会话已作废，调用方不得再写
     */
    fun write(samples: FloatArray): Boolean

    /** 排空并释放（幂等；可从任意线程调用）。 */
    fun close()
}

/**
 * LVC-1（ADR-053 决策 3）：端侧本地音色克隆 TTS 引擎（实现既有 [TtsEngine] 接口，**接口零改动**）。
 *
 * **语义对齐**（考古报告「不可破坏的不变量」）：
 * - `onStart/onDone/onError` **恰好一次**；被 `stop()`／被新 `speak()` 取代／被 `release()` 的请求
 *   **不回调**（上层 [io.prism.voice.TextPlaybackController] 的 `stopInternal` 已自行收敛状态，
 *   回调"新"结果反而会误清新状态）；
 * - `stop()` 同步静音并作废在途请求（停止后不得再有新生效出声）；
 * - `isAvailable` 恒为 pack 有效即 true（**不随模型加载状态变化**——`VoiceSessionController`
 *   在 `isAvailable=false` 时会静默跳过整轮播报）；模型加载失败经 `onError` 走既有降级链；
 * - **永不抛异常**：所有路径的错误都转为 `onError`（`speak` 在无异常处理器的协程里被调用，
 *   抛穿即崩溃）。
 *
 * **单飞（guardrail R1 F-1/C1）**：同一 `OfflineTts` 实例的 `synthesize` 与音频播放被
 * [engineAccess] 互斥——被取代的请求若未能在探针处及时退出，新请求会**排队**而不是并发进入
 * native（并发 `generate` 是未定义行为；并发 `play` 会产生双份音频）。
 * 等锁亦有界：超过 `timeoutMs × 2 + LOCK_WAIT_GRACE_MS` 即按首因报错或静默。
 *
 * **取消首因（guardrail R1 F-3/C5）**：[Request.cancelBy] 用 CAS 记录**首个**取消原因——
 * 被 stop/取代的请求不会被随后触发的看门狗改写成"超时"，看门狗也不会静音**新**请求
 * （仅在 `current === request` 时才动播放器）。
 *
 * **看门狗**（ADR-053 决策 7；语义已于 TKN-LVC-011 修订）：看门狗 **只告警，不取消合成**——
 * 超时**不再**置位取消探针（native 调用不可中断，取消无法止损，只会让 sherpa 丢弃其后全部分段
 * 而产出**残音频**，真机表现为"播报播到一半异常停止"）。取消探针只反映"确实放弃这段音频"
 * （用户停止 / 被取代 / 已释放）。**真挂死的兜底是"等锁超时"**：后续请求有界失败并走既有降级链
 * 回系统播报。看门狗在合成结束即取消，不会打断正在播放的音频。
 *
 * **模型懒加载**：合成器在**首次合成时于 IO 线程**构建（118MB 解码器加载不得发生在主线程）；
 * 构建失败被记住（不反复重试），后续 `speak` 直接 `onError`。
 *
 * **release 取舍（guardrail R1 F-8）**：释放需与在途 native 调用互斥（并发 `release` 会崩），
 * 故 [release] 以**有界等待**获取 [engineAccess]：超时则**跳过 native 释放**（宁可泄漏内存也不
 * 崩溃），并记 `leaked` 计数供真机观测。该取舍已由 guardrail 独立复核为成立。
 *
 * **流式播放（LVC-2）**：合成侧的 `onChunk` 是**按内部句子逐块**回调的（上游
 * `offline-tts-zipvoice-impl.h` 每段生成完即 `callback(cur.samples…)`，实测各块样本数之和恰等于
 * 整段样本数 ⇒ 块为**增量音频**）。流式路径把每块**立即写入一条常开音轨**，于是首声只需等第一句，
 * 且合成与播放**重叠**（不再"播完一段才开始算下一段"）。触发条件（**三者同时成立**）：
 * ① `LocalCloneSynthesizer.sampleRate() > 0`；② `LocalClonePlayer.openStream()` 返回会话；
 * ③ `config.silenceScale == 1.0f`。任一不成立即退回「整段合成 → 整段播放」。
 * 条件 ③ 的理由见 [SILENCE_SCALE_STREAMABLE]：`silenceScale != 1` 时上游会对**整段**做
 * `ScaleSilence`，而流式块绕过该后处理 ⇒ 不设门控就是"外观正常但音频被换了"的静默行为变化。
 * **不改任何对外回调契约**（`onStart` 在首块入队前发出、`onDone` 在音轨排空后发出，仍各恰好一次）。
 *
 * **回调线程（F-5 登记）**：流式路径下 `onStart` 在 `ioDispatcher` 线程发出（它由合成回调内联触发），
 * 而 `onDone`/`onError` 仍在协程原上下文发出。这**不构成契约破坏**——"监听器回调可来自任意线程"
 * 已是本项目既有契约（`TextPlaybackController` 对回调统一 `scope.launch` 回主线程；
 * `MiniMaxTtsEngine` 用自有串行 executor 投递，同样非主线程）。
 *
 * **回调线程与可见性（F-6 登记）**：上游生成循环是**单线程内联**调用回调
 * （`result.samples.insert(...)` 之后紧接着 `callback(cur.samples.data(), …)`，无任何线程切换）
 * ⇒ 回调内写的协程局部量（`started`/`playedAny`/`outputSinkDead`）与该次 `synthesize` 的调用线程
 * 同源，**不需要** volatile；跨 `withContext` 的可见性由调度器的 happens-before 保证。
 *
 * @param pack 已校验的本地音色包（由 [LocalVoicePackStore] 产出）
 * @param config 生效生成参数（**已过 [LocalCloneConfig.sanitized]**）
 * @param synthesizerFactory 合成器工厂（生产：反射创建 sherpa 实现；测试：假实现）
 * @param playerFactory 播放器工厂（生产：[AudioTrackLocalClonePlayer]；测试：假实现）
 * @param ioDispatcher IO 调度器（合成与播放的阻塞调用都在此执行）
 * @param scope 引擎协程域（缺省 Main.immediate——对齐本项目播报链主线程约定）
 * @param disposeTimeoutMs `release()` 获取独占权的上限（ms；构造注入以便单测覆盖超时分支）
 * @param playbackMode 播报管线模式（TKN-LVC-016，ADR-056）：[LocalClonePlaybackMode.CONTINUOUS]
 *   合成与播放重叠（默认），[LocalClonePlaybackMode.WHOLE] 全部合成完再一次性播放。
 *   **默认为连续**——只有调用方显式读到用户开关时才传 WHOLE。
 */
class LocalCloneTtsEngine(
    private val pack: LocalVoicePack,
    private val config: LocalCloneConfig,
    private val synthesizerFactory: () -> LocalCloneSynthesizer,
    private val playerFactory: () -> LocalClonePlayer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val disposeTimeoutMs: Long = DEFAULT_DISPOSE_TIMEOUT_MS,
    private val playbackMode: LocalClonePlaybackMode = LocalClonePlaybackMode.CONTINUOUS,
    /**
     * 单调时基（ms）——**构造注入以便单测驱动**（guardrail TKN-LVC-011 C-1）。
     *
     * 为什么必须可注入：`app/build.gradle.kts` 设 `isReturnDefaultValues = true`，JVM 单测里
     * `SystemClock.elapsedRealtime()` **恒返回 0** ⇒ 若不注入，看门狗的"无进展"判定在单测中
     * **永不触发**，相关用例会退化为**空断言**（已被封口复审用变异 M1 实证）。
     */
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) : TtsEngine {

    private val idCounter = AtomicLong(0)

    /** 合成器/播放器的创建锁（native 资源不可重入创建）。 */
    private val resourceLock = Any()

    /**
     * 单飞闸（guardrail C1）：`synthesize` + `play` 全段互斥，且 `release` 的释放也在其内。
     * 用协程 [Mutex] 而非 `synchronized`——它可被挂起等待与超时取消，不会阻塞调度线程。
     */
    private val engineAccess = Mutex()

    @Volatile
    private var synthesizer: LocalCloneSynthesizer? = null

    /** 合成器构建是否已失败（失败后不再重试——避免每次播报都重付加载代价）。 */
    @Volatile
    private var synthesizerFailed = false

    @Volatile
    private var player: LocalClonePlayer? = null

    /** 当前在途请求（`stop()`/新 `speak()` 定向作废；看门狗据此判断是否仍"在台"）。 */
    @Volatile
    private var current: Request? = null

    @Volatile
    private var released = false

    /** native 资源泄漏计数（release 超时跳过释放时自增；真机验收观测用）。 */
    private val leaked = AtomicInteger(0)

    /**
     * 可用性门（**按引擎分流**，ADR-055）。
     *
     * ZipVoice 的音色与韵律**全部来自参考音频** ⇒ 参考集为空即不可用；
     * VITS 的音色在**权重**里 ⇒ 参考集为空是**合法**配置（ADR-055 决策 3）。
     *
     * ⚠️ 历史上这里是 `pack.references.isNotEmpty()` 的无条件判断；ADR-055 引入 VITS 后，
     * 若不按引擎分流，按 ADR 样例打包的 VITS 包会出现「store 判 Ready、引擎却 isAvailable=false」
     * ⇒ **整条链路静默不出声**（guardrail TKN-LVC-015 N1/B1）。
     */
    override val isAvailable: Boolean
        get() = !released &&
            (pack.tts != LocalTtsKind.ZIPVOICE || pack.references.isNotEmpty())

    override fun speak(text: String, listener: TtsEngine.Listener) {
        if (released) {
            listener.onError(newUtteranceId(), RELEASED_MESSAGE)
            return
        }
        val normalized = LocalCloneTextNormalizer.normalize(text)
        if (normalized.isBlank()) {
            listener.onError(newUtteranceId(), "本地音色：文本为空，已回退")
            return
        }
        // 参考音频**只有 ZipVoice 需要**（VITS 忽略它，见 LocalCloneSynthesizer.synthesize 的契约）
        val reference: LocalReference? = if (pack.tts == LocalTtsKind.ZIPVOICE) {
            val selected = LocalReferenceSelector.select(normalized, pack.references)
            if (selected == null) {
                listener.onError(newUtteranceId(), "本地音色：无可用参考音频，已回退")
                return
            }
            selected
        } else {
            null
        }
        val request = Request(newUtteranceId(), listener, nowMs)
        // 取代在途请求：首因记为 REPLACED（若它已被 stop，CAS 失败 → 保留 STOPPED）
        current?.cancelBy(CancelCause.REPLACED)
        runCatching { player?.stop() }
        current = request
        scope.launch { runRequest(request, normalized, reference) }
    }

    override fun stop() {
        // userStopped 无条件置位（与首因解耦）：看门狗先置 TIMEOUT 时 CAS 会拒绝 STOPPED，
        // 但"用户已停止"必须仍然可见，否则乐观回执会把已停止的请求播出来（TKN-LVC-008）。
        current?.userStopped = true
        current?.cancelBy(CancelCause.STOPPED)
        runCatching { player?.stop() }
    }

    /**
     * 释放（幂等）。
     *
     * **顺序**：作废在途请求 → 同步静音 → **有界等待**独占权（native `release()` 与在途
     * `generate` 并发会崩，故必须互斥）→ 释放合成器与播放器 → 取消协程域。
     * 超时则跳过释放并计入 [leaked]（见类 KDoc 的取舍说明）。
     */
    override fun release() {
        if (released) return
        released = true
        current?.cancelBy(CancelCause.RELEASED)
        runCatching { player?.stop() }
        scope.launch {
            // 锁内标志位：区分"根本没拿到独占权（真泄漏）"与"已释放完但外层计时恰好超时"（guardrail R3 新-1）
            val disposalDone = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                val withinBudget = withTimeoutOrNull(disposeTimeoutMs) {
                    // native 释放派发到 IO：默认 scope 是 Main.immediate，`OfflineTts.release()` /
                    // `AudioTrack.release()` 是阻塞调用，不得内联在主线程执行（guardrail R2 N-6）
                    withContext(ioDispatcher) {
                        engineAccess.withLock {
                            disposeResourcesLocked()
                            disposalDone.set(true)
                        }
                    }
                    true
                } ?: false
                if (!withinBudget && !disposalDone.get()) {
                    val count = leaked.incrementAndGet()
                    Log.w(TAG, "释放超时：跳过 native 释放（防与在途合成并发崩溃；泄漏计数=$count）")
                } else if (!withinBudget) {
                    Log.i(TAG, "释放已在本预算内完成（仅外层计时超时），不计泄漏")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 清理路径同样不得抛穿；但仍要让 scope.cancel() 执行（guardrail R3 新-2）
                Log.w(TAG, "释放流程异常：${e::class.simpleName}")
            } finally {
                scope.cancel()
            }
        }
    }

    // ==================== 内部实现 ====================

    private fun newUtteranceId(): String = "prism-local-${idCounter.incrementAndGet()}"

    private suspend fun runRequest(request: Request, text: String, reference: LocalReference?) {
        var watchdog: Job? = null
        // 是否已**播放过任意一段**（guardrail TKN-LVC-012 N2/N3：一旦出声就不得再走带"已回退"的
        // onError——上层会据此重播整段，用户会听到重复。该标志在 catch 里同样需要，故提到 try 之外）
        var playedAny = false
        try {
            // 看门狗 = **进展观测**（TKN-LVC-011 G-1 闭合）：只告警，**绝不取消合成**。
            // 为什么绝不能取消：native 调用不可中断，超时无法止损，只会让取消探针变真 ⇒ 回调返回 STOP
            // ⇒ sherpa **丢弃其后全部分段**（上游 `offline-tts-zipvoice-impl.h` 先 append 后回调 + break）
            // ⇒ 产出残音频而日志仍打"合成完成" ⇒ 真机表现为"播报播到一半异常停止"
            // （PC 复现：完整 19.02s ⇒ 拒绝后 13.25s，丢弃 5.77s）。
            // 触发条件 = **某个非末段分段**生成耗时 ≥ timeoutMs（含首段——首段产出前没有任何心跳）。
            // 真挂死的兜底是**等锁超时**（后续请求有界失败并降级），而非丢弃已生成的音频。
            watchdog = scope.launch {
                while (isActive) {
                    delay(WATCHDOG_POLL_MS)
                    if (request.cancelled) break // 已被 stop/取代/释放：无需再看
                    val idleMs = nowMs() - request.lastProgressAtMs
                    if (idleMs >= config.timeoutMs && !request.stallWarned) {
                        request.stallWarned = true
                        Log.w(
                            TAG,
                            "合成已 ${idleMs}ms 无进展（阈值 ${config.timeoutMs}ms）：继续等待，" +
                                "不取消以免丢弃已生成分段"
                        )
                    }
                }
            }
            // **超时只约束"等锁"，不包住合成**（TKN-LVC-011）：原实现用
            // `withTimeoutOrNull(handoffMs) { engineAccess.withLock { …合成… } }`，把长合成也纳入时限，
            // 会在 121s/241s 处取消一个**仍在正常推进**的合成（第二条截短路径）。改为只对有界获取独占权计时。
            val handoffMs = config.timeoutMs * 2 + LOCK_WAIT_GRACE_MS
            // 竞态护栏（guardrail TKN-LVC-011 G-2）：`withTimeoutOrNull` 若在"锁已取得、块未完成"处
            // 赢得竞态，块值会被丢弃而锁**仍被持有** ⇒ 引擎此后永久等锁超时。故用块内标志兜住：
            // 一旦锁真的拿到，就按"已取得"处理，交由 finally 解锁。
            var lockedInside = false
            val acquired = withTimeoutOrNull(handoffMs) {
                engineAccess.lock()
                lockedInside = true
                true
            } ?: lockedInside
            if (!acquired) {
                // 等锁超时：本请求**未产出任何音频**（无数据可丢），报超时以触发降级链
                if (current === request && !request.cancelled) {
                    request.cancelBy(CancelCause.TIMEOUT)
                    request.listener.onError(request.utteranceId, TIMEOUT_MESSAGE)
                } else {
                    Log.w(TAG, "本地合成等锁超时（首因=${request.causeOrNull ?: "无"}），本轮不回调")
                }
                return
            }
            try {
                    if (request.cancelled) return // stop/被取代：静默（上层已收敛状态）

                    // 取消探针：**只反映"确实放弃这段音频"**（用户停止/被取代/已释放/输出栈已失效），
                    // 不含超时（G-1）。`outputSinkDead` 只在流式分支中可能置位（音轨静默失效）。
                    var outputSinkDead = false
                    val abandonProbe: () -> Boolean = {
                        request.userStopped || current !== request || released || outputSinkDead
                    }
                    // 长文本**切段**（TKN-LVC-012 / BUG-20260920-006）：单次无界合成会让"合成期间完全没
                    // 声音"并把内存/耗时推到系统容忍线外（真机被 SIGKILL）。切段后每段有界。
                    // 规则 6 门控（TKN-LVC-016 续）：**仅当该 pack 显式配置了段间停顿**时才让短文本也按
                    // 句末标点切分。没有停顿配置时切段只会增加合成次数、**不会插任何静音**
                    // （`gapMsFor` 在 baseGapMs ≤ 0 时恒返 0），属于"外观正常但行为变了"。
                    // ⚠️ 门控必须与"**实际会插静音**"的条件**逐字等价**（guardrail F4）：`silenceSamples`
                    // 在 `gapMs > MAX_SENTENCE_GAP_MS` 时同样返 0 ⇒ 只判 `> 0` 会让"越界值切了段却不插静音"。
                    // 生产路径经 `create()`→`sanitized()` 已回落，此处是**纵深防御**（覆盖直接调构造器的场景）。
                    // 既有 pack 均未配置该字段（默认 0）⇒ **行为逐字不变**。
                    val segments = segmentForSynthesis(
                        text,
                        DEFAULT_SEGMENT_CHARS,
                        FIRST_SEGMENT_CHARS,
                        splitSentences = config.sentenceGapMs in
                            1..LocalCloneConfig.MAX_SENTENCE_GAP_MS
                    )
                    // 流式能力探测（LVC-2）：**四个条件同时成立**才走流式 ——
                    // ① 合成器能声明采样率；② 播放器支持常开音轨；③ `silenceScale == 1`；
                    // ④ 播报模式为**连续**（TKN-LVC-016：WHOLE 模式的语义就是"全部合成完再播"，
                    //    若仍走逐块流式就自我矛盾）。
                    // 条件③的必要性（guardrail F-1）：上游 `offline-tts-zipvoice-impl.h` 在**整段**
                    // 生成结束后才做 `if (config.silence_scale != 1) result.ScaleSilence(...)`，
                    // 而流式路径只消费回调块、**丢弃** `result` ⇒ 非 1.0 时流式会静默丢掉该后处理，
                    // 产出与整段路径不同的音频（属于"外观正常但行为变了"）。故非 1.0 一律退回整段路径。
                    // 段间停顿（sentenceGapMs）需要"引擎回报的采样率"，而它目前只在 withContext 内取得；
                    // 故把采样率提到 block 外（**不硬编码** 24000/16000——VITS 与 ZipVoice 未必同率）。
                    var streamRate = 0
                    val streamSession: LocalCloneStreamSession? = withContext(ioDispatcher) {
                        val synth = ensureSynthesizer() ?: throw IllegalStateException(SYNTH_UNAVAILABLE)
                        val rate = synth.sampleRate()
                        streamRate = rate
                        if (rate <= 0 || config.silenceScale != SILENCE_SCALE_STREAMABLE ||
                            playbackMode != LocalClonePlaybackMode.CONTINUOUS
                        ) null
                        else playerRef().openStream(rate, abandonProbe)
                    }
                    var started = false
                    if (streamSession != null) {
                        // ---- 流式：合成回调的**每个内部句子分段**立即写入同一条常开音轨 ----
                        // 上游 `offline-tts-zipvoice-impl.h` 每段生成完即 `callback(cur.samples...)`，
                        // 且块是**增量音频**（实测各块样本数之和恰等于整段样本数）⇒ 首声只需等第一句。
                        try {
                            for ((index, segment) in segments.withIndex()) {
                                if (request.cancelled || outputSinkDead) break
                                val audio = withContext(ioDispatcher) {
                                    val synth = ensureSynthesizer()
                                    if (synth == null) throw IllegalStateException(SYNTH_UNAVAILABLE)
                                    // 第 4 参 = 取消探针（只反映"放弃"）；第 5 参 = 逐块回调
                                    synth.synthesize(segment, reference, config, abandonProbe) { chunk ->
                                        request.touch()
                                        // 空块**整体跳过**（guardrail F-2）：`write()` 把"空"与"作废"合并成
                                        // 同一个 false 会误判 `outputSinkDead` ⇒ 静默丢弃后续全部分段。
                                        // 上游在回调**之前**就 `if (cur.samples.empty()) continue;`，
                                        // 故正常路径不会出现空块；这里只是防御（若出现，视作"本块无音频"，
                                        // 既不出声也不判失效）。
                                        if (chunk.isNotEmpty()) {
                                            if (!started) {
                                                // onStart 在**首块入队前**发出——与既有整段路径完全同序
                                                // （那里也是先 onStart、后 play、play 成功才置 playedAny）。
                                                // 若等 write 返回再回调，首块（可达十余秒）会一直占着 write，
                                                // 使上层状态与真实首声相差整块时长。
                                                started = true
                                                request.listener.onStart(request.utteranceId)
                                            }
                                            if (streamSession.write(chunk)) {
                                                playedAny = true
                                            } else if (!abandonProbe()) {
                                                outputSinkDead = true
                                            }
                                        }
                                    }
                                }
                                request.touch()
                                if (request.cancelled || outputSinkDead) break
                                if (audio.truncated) {
                                    Log.w(TAG, "该段合成被中止（truncated=true）：停止后续分段，不按成功播放")
                                    break
                                }
                                // ---- 段间停顿（sentenceGapMs，播放层补偿被切段吃掉的停顿）----
                                // **最后一段之后不插**：否则尾音被拖长、onDone 被无谓延迟。
                                // 写失败按**既有**口径处理（只反映"确实放弃这段音频"；空块已在上方整体跳过，
                                // 故此处非空仍返回 false ⇒ 输出栈失效）。
                                if (index != segments.lastIndex) {
                                    // 边界类型判定（**段边界 ≠ 句边界**）：只有真句末给满额，
                                    // 子句（逗号/分号/冒号）打折，硬切/无标点一律 0
                                    val gap = LocalCloneSegmentGap.silence(
                                        LocalCloneSegmentGap.gapMsFor(segment, config.sentenceGapMs),
                                        streamRate
                                    )
                                    if (gap.isNotEmpty()) {
                                        request.touch()
                                        if (streamSession.write(gap)) {
                                            playedAny = true
                                        } else if (!abandonProbe()) {
                                            outputSinkDead = true
                                        }
                                    }
                                }
                            }
                            // 看门狗须在**排空**之前停表：排空期间没有合成，不该按"无进展"告警
                            watchdog.cancel()
                            // 一声未出而输出栈已死 ⇒ 可安全走降级（上层会改用系统播报，无重复风险）。
                            //
                            // `&& !playedAny` 的用途**只是错误文案归因**（未出声 ⇒ 报"已回退"触发降级；
                            // 已出声 ⇒ 由下方与 catch 的 `playedAny` 分支收敛为 onDone）。
                            // 它**不影响可观测行为**：去掉它后该 case 走 catch 的 `playedAny` 分支，
                            // 结果同为 onDone——guardrail 变异 M2b 因此**存活**，属已有记录的冗余守卫，
                            // 保留它是为了日志/错误文案不误导（"输出栈失效"vs"分段播放中途失败"）。
                            if (outputSinkDead && !playedAny) throw IllegalStateException(STREAM_WRITE_FAILED)
                            if (!started && !request.cancelled) {
                                request.listener.onError(request.utteranceId, EMPTY_AUDIO_MESSAGE)
                                return
                            }
                        } finally {
                            // 排空必须在 onDone **之前**完成，否则尾音被紧随的收尾截断（原 play() 的语义）
                            withContext(NonCancellable) {
                                withContext(ioDispatcher) { runCatching { streamSession.close() } }
                            }
                        }
                        if (started && !request.cancelled) {
                            if (outputSinkDead) {
                                Log.w(TAG, "输出栈在流式播放中失效：已出声，按本轮结束收敛（不触发上层重播）")
                            }
                            request.listener.onDone(request.utteranceId)
                        }
                    } else {
                        // ---- 非流式路径：**合成与播放解耦**（TKN-LVC-016 / ADR-056）----
                        //
                        // 生产清单 `silenceScale != 1` 时上游会丢弃流式路径的 `ScaleSilence`
                        // （见上方条件③），因此本项目实际走的就是这条路径。改造前它是
                        // "整段合成 → 整段播放 → 再合成下一段"的**串行**结构 ⇒ 段间静音 =
                        // 下一段的**完整合成耗时**，用户主诉的"一句一句推理、一句一句播报"即此。
                        //
                        // 现在：**合成协程**按段推进并写入有界队列，**消费端**（本协程）顺序取用
                        // ⇒ "段 N 播放期间段 N+1 已在合成"。两种模式的差别只在消费端（ADR-056 决策 1）：
                        // - CONTINUOUS：取到一段就写入**同一条常开音轨**（`openStream` 不可用则退回逐段
                        //   `play`）——段间只保留音频自带停顿；
                        // - WHOLE：先收齐所有段，拼接成一条 PCM，**只调用一次** `play`——结构上不可能
                        //   被合成打断，代价是首声延迟 = 全段合成总时长。
                        //
                        // 长文本上限（guardrail BUG-20260920-006 的教训）：整段模式的内存占用 ∝ 总时长，
                        // 无界累积曾把进程推到系统容忍线外被 SIGKILL。故超过 [WHOLE_MODE_MAX_CHARS]
                        // 一律**在开始合成之前**回落连续模式——刻意不做"合成中途降级"：那会让已产出
                        // 未播放的音频悬空，属难以测准的半成品状态。
                        val effectiveMode =
                            if (playbackMode == LocalClonePlaybackMode.WHOLE &&
                                text.length > WHOLE_MODE_MAX_CHARS
                            ) {
                                Log.i(
                                    TAG,
                                    "整段播报：文本 ${text.length} 字超过上限 $WHOLE_MODE_MAX_CHARS，" +
                                        "本轮回落连续模式（防无界累积被系统回收）"
                                )
                                LocalClonePlaybackMode.CONTINUOUS
                            } else {
                                playbackMode
                            }

                        // 连续模式的播放**刻意仍走逐段 `player.play`**，不复用一条常开音轨：
                        // ① 收益很小——两段之间只剩"音轨重建 + 排空"（十毫秒量级），而本批要消除的是
                        //    **整段合成耗时**（秒级）造成的静音；
                        // ② 复用常开音轨必须绕过 `silenceScale != 1` 的流式互斥判定（ADR-054/F-1 的既有
                        //    守卫），而"消费整段产物再写入"与"消费回调块"在语义上太接近，日后极易被
                        //    误改成真的流式路径，从而再次静默丢弃上游的整段 `ScaleSilence`。
                        // 用后者换十毫秒，不值。

                        val queue = Channel<LocalCloneAudio>(PREFETCH_CAPACITY)
                        val producerFailure = AtomicReference<Throwable?>(null)
                        // 每轮汇总的计时/计数（AC-7.1：八字段结构化日志）。全部在 join 之后读取，
                        // 故不需要原子类型；跨线程写只发生在 join 之前。
                        var synthNanos = 0L
                        var firstAudioNanos = 0L
                        var playedSegments = 0
                        var playedSamples = 0
                        var playedRate = 0
                        val roundStartNanos = System.nanoTime()
                        val producer = scope.launch(ioDispatcher) {
                            try {
                                for ((index, segment) in segments.withIndex()) {
                                    if (request.cancelled || outputSinkDead) break
                                    val synth = ensureSynthesizer()
                                        ?: throw IllegalStateException(SYNTH_UNAVAILABLE)
                                    // 第 4 个参数是**取消探针**（只反映"放弃"）；第 5 个是**分段心跳**
                                    val t0 = System.nanoTime()
                                    val audio = synth.synthesize(
                                        segment, reference, config, abandonProbe
                                    ) { request.touch() }
                                    synthNanos += System.nanoTime() - t0
                                    // 每段算完即刷新看门狗：段与段之间也不算"无进展"
                                    request.touch()
                                    if (audio.truncated) {
                                        Log.w(TAG, "该段合成被中止（truncated=true）：停止后续分段，不按成功播放")
                                        break
                                    }
                                    if (firstAudioNanos == 0L) firstAudioNanos = System.nanoTime()
                                    queue.send(audio)
                                    // ---- 段间停顿（sentenceGapMs）----
                                    // 作为**独立 piece** 入队：CONTINUOUS（逐段 `play`）与 WHOLE（拼接后一次 `play`）
                                    // 两条消费路径都因此自动获得段间停顿，无需改消费端。
                                    // **最后一段之后不插**（否则尾音被拖长、onDone 延迟）；不计入 synthNanos；
                                    // 除 request.touch() 外不产生任何副作用。
                                    if (index != segments.lastIndex) {
                                        // 边界类型判定（**段边界 ≠ 句边界**：切分可能退到子句标点甚至硬切）：
                                        // 真句末给满额、子句打折、硬切/无标点 0 —— 否则逗号处顿挫、
                                        // 硬切处出现"词中间停顿"，比改动前更糟。
                                        val gap = LocalCloneSegmentGap.silence(
                                            LocalCloneSegmentGap.gapMsFor(
                                                segments[index], config.sentenceGapMs
                                            ),
                                            audio.sampleRate
                                        )
                                        if (gap.isNotEmpty()) {
                                            request.touch()
                                            queue.send(
                                                LocalCloneAudio(
                                                    samples = gap,
                                                    sampleRate = audio.sampleRate,
                                                    truncated = false
                                                )
                                            )
                                        }
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                producerFailure.set(e)
                            } finally {
                                // 看门狗在**合成全部结束**的精确时点停表（与改造前同一口径）
                                watchdog.cancel()
                                queue.close()
                            }
                        }

                        try {
                            if (effectiveMode == LocalClonePlaybackMode.WHOLE) {
                                // ---------- 整段模式：收齐 → 拼接 → 一次播放 ----------
                                val pieces = ArrayList<LocalCloneAudio>(segments.size)
                                var mergedRate = 0
                                var mergedSamples = 0
                                var rateMismatch = false
                                for (audio in queue) {
                                    if (request.cancelled) break
                                    if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                                        // 与连续模式同一口径（N3）：已收齐的部分不因一段零音频而全丢
                                        if (pieces.isNotEmpty()) continue
                                        request.listener.onError(request.utteranceId, EMPTY_AUDIO_MESSAGE)
                                        return
                                    }
                                    if (mergedRate == 0) mergedRate = audio.sampleRate
                                    else if (audio.sampleRate != mergedRate) rateMismatch = true
                                    mergedSamples += audio.samples.size
                                    pieces += audio
                                }
                                if (rateMismatch) {
                                    // 采样率不一致时**不能**拼接（按错误采样率播放 = 变调变速），
                                    // 收敛为可回退失败：上层据此改走系统播报。
                                    throw IllegalStateException(SAMPLE_RATE_MISMATCH)
                                }
                                if (pieces.isEmpty()) {
                                    request.listener.onError(request.utteranceId, EMPTY_AUDIO_MESSAGE)
                                    return
                                }
                                val merged = FloatArray(mergedSamples)
                                var offset = 0
                                for (p in pieces) {
                                    System.arraycopy(p.samples, 0, merged, offset, p.samples.size)
                                    offset += p.samples.size
                                }
                                if (!request.cancelled) {
                                    request.listener.onStart(request.utteranceId)
                                    started = true
                                    withContext(ioDispatcher) {
                                        playerRef().play(merged, mergedRate) { request.cancelled }
                                    }
                                    playedAny = true
                                    playedSegments = pieces.size
                                    playedSamples = mergedSamples
                                    playedRate = mergedRate
                                }
                            } else {
                                // ---------- 连续模式：边合成边播 ----------
                                for (audio in queue) {
                                    if (request.cancelled) break
                                    if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                                        if (playedAny) {
                                            Log.w(TAG, "某分段零音频：跳过该段，继续播后续段（已播部分保留）")
                                            continue
                                        }
                                        request.listener.onError(request.utteranceId, EMPTY_AUDIO_MESSAGE)
                                        return
                                    }
                                    if (!started) {
                                        request.listener.onStart(request.utteranceId)
                                        started = true
                                    }
                                    withContext(ioDispatcher) {
                                        playerRef().play(audio.samples, audio.sampleRate) {
                                            request.cancelled
                                        }
                                    }
                                    playedAny = true
                                    playedSegments++
                                    playedSamples += audio.samples.size
                                    if (playedRate == 0) playedRate = audio.sampleRate
                                }
                            }
                        } finally {
                            // 生产者**必须被等干净**再让出 engineAccess（guardrail TKN-LVC-016 B1）：
                            // 它跑在锁外，若只 `cancel()` 就返回，则：
                            // ① 新请求可能立刻进入 `synthesize`，与仍在 generate 的 native 调用并发；
                            // ② `release()` 会走到 `disposeResourcesLocked(synth.release())`，与在途 native
                            //    调用并发——类 KDoc 已自述该情形为 UB（真机表现为崩溃/静默）。
                            // 改造前 `synthesize` 在**持锁的消费协程内**阻塞执行，故不存在该窗口；
                            // 这里 join 正是把"单飞"语义补回来（取消探针会在挂起点让 producer 退出；
                            // 其行为与改造前"持锁等 native 返回"完全等价，不会引入新的无限等待）。
                            withContext(NonCancellable) { producer.cancelAndJoin() }
                        }
                        // ---------- 每轮汇总（AC-7.1；八字段） ----------
                        if (!request.cancelled) {
                            val totalNanos = System.nanoTime() - roundStartNanos
                            Log.i(
                                TAG,
                                "播报汇总：mode=${effectiveMode.storeValue} segments=${segments.size} " +
                                    "played=$playedSegments samples=$playedSamples rate=$playedRate " +
                                    "synthMs=${synthNanos / 1_000_000} firstMs=" +
                                    "${if (firstAudioNanos == 0L) -1 else (firstAudioNanos - roundStartNanos) / 1_000_000} " +
                                    "totalMs=${totalNanos / 1_000_000} rtf=%.2f".format(
                                        if (synthNanos <= 0L) 0.0 else totalNanos.toDouble() / synthNanos
                                    )
                            )
                        }
                        // 合成侧的异常在此**原样重抛**，交由外层既有的失败/降级分支统一处置
                        // （不得吞掉：吞掉会变成"没播完却报成功"）。
                        // 被取消的请求例外：此时 producer 往往正是被 `queue.close()` 撞出来的
                        // `ClosedSendChannelException`——那是**取消的正常副作用**，不是合成失败，
                        // 重抛只会给日志加一条误导性的警告（回调侧本来就按 cancelled 收敛）。
                        if (!request.cancelled) producerFailure.get()?.let { throw it }
                        if (started && !request.cancelled) {
                            if (outputSinkDead) {
                                Log.w(TAG, "输出栈在连续播放中失效：已出声，按本轮结束收敛（不触发上层重播）")
                            }
                            request.listener.onDone(request.utteranceId)
                        }
                    }
            } finally {
                engineAccess.unlock()
            }
        } catch (e: CancellationException) {
            throw e // BR-error-handling-007：取消异常必须重抛，不得吞成业务失败
        } catch (e: Throwable) {
            // 含 NoClassDefFoundError（AAR 缺失）等 Error——全部收敛为 onError，绝不抛穿
            // 注：此处**不再**判 TIMEOUT——等锁超时分支已在进入合成前 return 并自行回调，
            // 故"超时"状态不可能伴随异常走到这里（guardrail TKN-LVC-011 N-6：原分支不可达且会二次 onError）
            // N2（guardrail TKN-LVC-012）：**已出声**后不得再报带"已回退"的 onError——
            // 上层（TextPlaybackController/VoiceSessionController）会据此**重播整段**，用户听到重复。
            // 此时按"本轮播报结束"收敛（onDone），异常详情留在日志。
            if (playedAny) {
                Log.w(TAG, "分段播放中途失败，已播部分保留、不触发降级重播：${e::class.simpleName}${internalDetailSuffix(e)}")
                if (!request.cancelled) request.listener.onDone(request.utteranceId)
            } else if (!request.cancelled) {
                Log.w(TAG, "本地音色合成失败：${e::class.simpleName}${internalDetailSuffix(e)}")
                request.listener.onError(request.utteranceId, failureMessage(e))
            }
        } finally {
            watchdog?.cancel()
        }
    }

    /**
     * 独占段（合成 → 播放 → 回调）**已内联在 [runRequest] 的 `withLock` 块内**。
     *
     * 之所以不抽成独立方法：看门狗必须在"合成返回"这一精确时点停表（早停 → 不可中断的
     * native 调用无人置位探针；晚停 → 会打断正在播放的音频），把停表与合成放在同一作用域
     * 是最不容易出错的形式（R2 修复点，见 [runRequest] 注释）。
     */

    private fun ensureSynthesizer(): LocalCloneSynthesizer? {
        synchronized(resourceLock) {
            synthesizer?.let { return it }
            if (synthesizerFailed) return null
            return try {
                val created = synthesizerFactory()
                synthesizer = created
                Log.i(TAG, "本地音色合成器就绪（voice=${pack.voiceId}）")
                created
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                synthesizerFailed = true
                Log.w(TAG, "本地音色合成器构建失败：${e::class.simpleName}")
                null
            }
        }
    }

    private fun playerRef(): LocalClonePlayer = synchronized(resourceLock) {
        player ?: playerFactory().also { player = it }
    }

    /** 释放 native 资源（**调用前提：已持有 [engineAccess]**，即无在途合成/播放）。幂等。 */
    private fun disposeResourcesLocked() {
        val synth: LocalCloneSynthesizer?
        val p: LocalClonePlayer?
        synchronized(resourceLock) {
            synth = synthesizer
            p = player
            synthesizer = null
            player = null
        }
        runCatching { synth?.release() }
        runCatching { p?.release() }
    }

    /** 失败文案（含"已回退"标记——[io.prism.voice.shouldFallbackToOnDevice] 据此触发降级链）。 */
    private fun failureMessage(e: Throwable): String = when {
        e is IllegalStateException && e.message == SYNTH_UNAVAILABLE -> "本地音色模型加载失败，已回退"
        else -> "本地音色合成失败（${e::class.simpleName}），已回退"
    }

    /**
     * 日志明细后缀：**只回显本方自产的标记文案**（如 `callback-signature-mismatch`、
     * `synthesizer-unavailable`）。
     *
     * 为什么不直接落 `e.message`：上游异常 message 可能内嵌**绝对路径**（模型/音频文件），
     * 违反 rules/07 §19.3 的日志安全口径。白名单前缀可保证"可诊断"与"不泄路径"同时成立
     * （TKN-LVC-002 M-2：预检失败此前只打类名，排障时无法区分"签名不符"与"模型加载失败"）。
     */
    private fun internalDetailSuffix(e: Throwable): String {
        val message = e.message ?: return ""
        return if (INTERNAL_MARKERS.any { message.startsWith(it) }) " | $message" else ""
    }

    /** 取消首因（CAS 写入，首个原因胜出）。 */
    private enum class CancelCause { STOPPED, REPLACED, RELEASED, TIMEOUT }

    /**
     * 在途请求：**单一首因** + 回调目标。
     *
     * 为什么不是两个布尔（`cancelled`/`timedOut`）：先被 `stop()` 再被看门狗触发的请求，
     * 若按"timedOut 优先"判定就会为已停止的请求发出超时错误（guardrail F-3）。
     */
    private class Request(val utteranceId: String, val listener: TtsEngine.Listener, private val now: () -> Long) {
        private val cause = AtomicReference<CancelCause?>(null)

        val causeOrNull: CancelCause? get() = cause.get()

        val cancelled: Boolean get() = cause.get() != null

        /**
         * 用户显式 stop 过（**与首因无关**，无条件置位）。
         *
         * 为什么不能只看首因：看门狗先置 TIMEOUT 后用户再 stop，CAS 会保留 TIMEOUT ⇒
         * 若只看 `timedOut`，"乐观回执"会把用户已停止的请求又播出来（TKN-LVC-008）。
         */
        @Volatile
        var userStopped: Boolean = false

        /**
         * 最近一次进展时刻（ms）——由合成器的**分段心跳**刷新（TKN-LVC-011）。
         *
         * 看门狗据此判定"是否仍在推进"：只要还在产出分段就**不得**判超时。
         * 真机缺陷（BUG-20260920-005）：绝对时限到点会让取消探针变真 ⇒ sherpa 回调返回 STOP ⇒
         * **丢弃后续分段**，产出残音频而日志仍打"合成完成" ⇒ 用户听到"播报播到一半停止"。
         */
        @Volatile
        var lastProgressAtMs: Long = now()

        /** 记录一次进展（合成器每产出一个分段调用一次）。 */
        fun touch() {
            lastProgressAtMs = now()
        }
        /** 「无进展」告警是否已发过（每请求仅一次，防日志刷屏）。 */
        @Volatile
        var stallWarned: Boolean = false

        /** 记录取消原因（CAS：**首个原因胜出**，后续写入被忽略）。 */
        fun cancelBy(reason: CancelCause) {
            cause.compareAndSet(null, reason)
        }
    }

    companion object {
        private const val TAG = "LocalClone"

        /** 看门狗轮询间隔（ms）：只用于判「无进展」，与总时长无关（TKN-LVC-011）。 */
        private const val WATCHDOG_POLL_MS = 250L

        /** 超时文案（"已回退"标记为降级链触发条件，勿改）。 */
        const val TIMEOUT_MESSAGE = "本地音色合成超时，已回退"

        /** 零音频文案（"已回退"标记同上）。 */
        const val EMPTY_AUDIO_MESSAGE = "本地音色未产出音频，已回退"

        /** 已释放仍被调用的文案（**含"已回退"标记**——否则通话链会静默结束本轮，guardrail F-7）。 */
        const val RELEASED_MESSAGE = "本地音色引擎已释放，已回退"

        /** 合成器不可用内部标记（映射为"模型加载失败，已回退"文案）。 */
        private const val SYNTH_UNAVAILABLE = "synthesizer-unavailable"

        /**
         * 流式播放输出栈失效内部标记（LVC-2）：音轨在**一声未出**之前就拒绝写入。
         *
         * 该路径按失败处理（走 `onError` + "已回退"）⇒ 上层改用系统播报——因为**没有任何音频播出**，
         * 降级不会造成重复播报；若已出声则改按"本轮结束"收敛（见 runRequest）。
         */
        private const val STREAM_WRITE_FAILED = "stream-write-failed"

        /** 允许落盘的错误明细前缀（白名单；防止上游 message 内嵌绝对路径泄入日志）。 */
        private val INTERNAL_MARKERS =
            listOf("callback-signature-mismatch", SYNTH_UNAVAILABLE, STREAM_WRITE_FAILED, SAMPLE_RATE_MISMATCH)

        /** 等锁超时相对看门狗的余量（ms）：略大于看门狗，确保首因判定先由看门狗写入。 */
        const val LOCK_WAIT_GRACE_MS = 1_000L

        /**
         * 预取队列容量（段数，TKN-LVC-016）。
         *
         * 取 2 的取舍：1 段缓冲已足以让"合成段 N+1"与"播放段 N"重叠（这正是本批要解决的问题），
         * 第 2 段的余量用于吸收播放侧的抖动；再大只是把音频无谓地堆在内存里
         * （每段按 40 字 ≈ 10s@24kHz ≈ 960KB float），并不会让首声更早。
         * **不是**内存上界——上界由 [WHOLE_MODE_MAX_CHARS] 与分段数共同约束。
         */
        const val PREFETCH_CAPACITY = 2

        /**
         * 整段播报模式的文本长度上限（字符，TKN-LVC-016）。
         *
         * 整段模式的峰值内存 ∝ 音频总时长（float 4B + PCM16 2B 各一份）。按实测约 0.24s/字
         * （24kHz）计，200 字 ≈ 48s ≈ 4.6M 采样 ≈ 18MB(float) + 9MB(PCM16)；超过此量级
         * 就有重演 BUG-20260920-006（无界累积被系统 SIGKILL）的风险。
         * 超限时**在合成开始前**回落连续模式（见 runRequest），不做中途降级。
         */
        const val WHOLE_MODE_MAX_CHARS = 200

        /** 分段采样率不一致内部标记（整段模式无法拼接 ⇒ 按可回退失败处理）。 */
        private const val SAMPLE_RATE_MISMATCH = "segment-sample-rate-mismatch"

        /**
         * 允许走**流式播放**的 `silenceScale` 取值（guardrail F-1）。
         *
         * 上游只在 `silence_scale != 1` 时执行整段 `ScaleSilence`（`offline-tts-zipvoice-impl.h`：
         * `if (config.silence_scale != 1) result = result.ScaleSilence(...)`）⇒ **恰在 1.0 时该后处理是
         * 恒等变换**，流式块与整段结果逐样本等价；任何其它取值都必须退回整段路径，否则流式会
         * 静默产出"未经停顿缩放"的音频（用户听感变化、日志无痕）。
         *
         * 用精确浮点比较是**有意**的：清单里写 `1.0` 才走流式，`1.0000001` 之类都会安全退回。
         */
        const val SILENCE_SCALE_STREAMABLE = 1.0f

        /** `release()` 等待独占权的默认上限（ms）；超限则跳过 native 释放（见类 KDoc）。 */
        const val DEFAULT_DISPOSE_TIMEOUT_MS = 5_000L
    }
}

/**
 * LVC-1：`AudioTrack` PCM 播放器（16bit 单声道；参数与 ZipVoice 输出严格一致）。
 *
 * **为什么自建而不是复用云端引擎的播放代码**：[MiniMaxTtsEngine] 的 AudioTrack 与其 WS 会话/
 * 分片写入强耦合（私有内部类），抽取会改动已验收的云端链路；本类只做"一段 PCM 播完"这一件事。
 *
 * **打断语义（guardrail F-2 修复）**：改用**世代计数**（[epoch]）而非可复位布尔——
 * `stop()` 自增世代，`play()` 在入口快照世代并在写循环/排空循环中与外部 [isCancelled] 探针
 * 一并比对。这样"早到的 stop"（用户停止 / 打断 / 新请求取代 / hangUp）不会被 `play()` 内部
 * 的复位抹除；**同一代内后续的播放不受影响**（新请求入口取到的是新世代）。
 */
class AudioTrackLocalClonePlayer : LocalClonePlayer {

    private val lock = Any()

    @Volatile
    private var track: AudioTrack? = null

    /** 播放世代：`stop()` 自增；`play()` 入口快照，用于识别"早到的停止"。 */
    @Volatile
    private var epoch = 0

    override fun play(samples: FloatArray, sampleRate: Int, isCancelled: () -> Boolean) {
        if (samples.isEmpty() || sampleRate <= 0) return
        // 整段播放 = 「开会话 → 一次性写入 → 排空释放」：与流式路径**共用同一套写入/排空/停走检测**，
        // 避免两套 AudioTrack 逻辑各自漂移（LVC-2）。早到的 stop 由 openStream 的两道探针拦截。
        val session = openStream(sampleRate, isCancelled) ?: return
        try {
            session.write(samples)
        } finally {
            session.close()
        }
    }

    /**
     * 构建一条 [AudioTrack]（16bit 单声道、MODE_STREAM、固定量级缓冲）——整段播放与流式播放**共用**。
     *
     * **缓冲区必须与音频长度无关**（TKN-LVC-011）：原实现 `maxOf(minBuffer, pcm.size * 2)` 让缓冲区
     * 随音频线性增长——14s 音频 ⇒ 662KB、19.5s ⇒ 916KB，远超正常 STREAM 音轨量级。真机后果：
     * `AudioFlinger: prepareTracks_l BUFFER TIMEOUT: remove track from active list due to underrun`
     * → `restartIfDisabled: releaseBuffer() track disabled due to previous underrun, restarting`
     * → 播放中途无声、`threadloop_write` 长时间阻塞。改为固定量级（按采样率算 ~100ms，带上限），
     * 由写入侧的"缓冲满则轮询等待消费"来节流——这正是 STREAM 模式的正常用法。
     */
    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = localCloneTrackBufferBytes(minBuffer, sampleRate)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Float32 [-1,1] → 16bit PCM（NaN 归零、越界钳制；与整段播放路径逐字一致）。 */
    private fun toPcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { i ->
        val v = samples[i]
        val clamped = if (v.isNaN()) 0f else v.coerceIn(-1f, 1f)
        (clamped * 32767f).toInt().toShort()
    }

    override fun openStream(sampleRate: Int, isCancelled: () -> Boolean): LocalCloneStreamSession? {
        if (sampleRate <= 0) return null
        val myEpoch = epoch
        if (myEpoch != epoch || isCancelled()) return null // 已被停止/取代：不出声

        val audioTrack = buildTrack(sampleRate)
        synchronized(lock) { track = audioTrack }
        // 音轨构建耗时可观（毫秒级）：构建期间到达的 stop 必须让本次播放整体作废
        if (myEpoch != epoch || isCancelled()) {
            synchronized(lock) { if (track === audioTrack) track = null }
            runCatching { audioTrack.release() }
            return null
        }
        runCatching { audioTrack.play() }
        return StreamSession(audioTrack, sampleRate, myEpoch, isCancelled)
    }

    /**
     * 把 [pcm] 全部写入音轨；缓冲满时**非阻塞写 + 有界轮询**等待其被消费。
     *
     * **非阻塞写 + 有界轮询**（guardrail TKN-LVC-011 新-1）：小缓冲区若配 WRITE_BLOCKING，
     * 单次 write 会一直阻塞到几乎整段写完（14s 音频 ⇒ ~13.9s），使"播放头停走检测"对
     * **主等待路径**失效。改为每轮只写"当前可容纳量"，缓冲满时睡眠轮询，**在写循环内**判
     * 播放头是否仍在前进 ⇒ 卡死能被及时识别并退出。
     *
     * @return 已写入的**帧数**；**-1 表示被打断/作废**（调用方不得再按整段时长排空）
     */
    private fun writeAll(
        audioTrack: AudioTrack,
        pcm: ShortArray,
        myEpoch: Int,
        isCancelled: () -> Boolean
    ): Int {
        var offset = 0
        var lastHead = audioTrack.playbackHeadPosition
        var stalledMs = 0L
        while (offset < pcm.size && myEpoch == epoch && !isCancelled()) {
            val written = audioTrack.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                return -1 // 错误码（含被 stop/pause 打断）：不得再按整段时长排空
            }
            if (written > 0) {
                offset += written
                lastHead = audioTrack.playbackHeadPosition
                stalledMs = 0L
                continue
            }
            // written == 0：缓冲已满（正常消费中）→ 轮询等待空间，并检查播放头是否前进
            Thread.sleep(DRAIN_POLL_MS)
            val head = audioTrack.playbackHeadPosition
            if (head == lastHead) {
                stalledMs += DRAIN_POLL_MS
                if (stalledMs >= STALL_DETECT_MS) {
                    Log.w(
                        TAG,
                        "播放头停走 ${stalledMs}ms（head=$head/${pcm.size}，underrun=${underrunCountOf(audioTrack)}）：" +
                            "判定音频输出栈卡死，本次播报可能中途无声（设备侧 HAL 阻塞，非本地合成失败）"
                    )
                    return -1
                }
            } else {
                lastHead = head
                stalledMs = 0L
            }
        }
        return if (myEpoch == epoch && !isCancelled()) offset else -1
    }

    /**
     * LVC-2 流式会话：一条常开音轨 + 逐块写入 + 关闭时排空。
     *
     * 与整段播放路径**共用** [writeAll] 与 [drain]，故 TKN-LVC-010（播放头停走检测）与
     * TKN-LVC-011（非阻塞写 + 有界轮询、underrun 计数）的守护在两个路径上完全一致。
     */
    private inner class StreamSession(
        private val audioTrack: AudioTrack,
        private val sampleRate: Int,
        private val ownerEpoch: Int,
        private val isCancelled: () -> Boolean
    ) : LocalCloneStreamSession {

        /** 写锁：保证同一会话内串行写入（`close` 也要等当前块写完，但不抢占）。 */
        private val writeLock = Any()

        @Volatile
        private var interrupted = false

        @Volatile
        private var closed = false

        @Volatile
        private var written = 0

        override val framesWritten: Int get() = written

        override fun write(samples: FloatArray): Boolean {
            // 准入判定走纯函数（可被单测直接钉住，guardrail R2-N2）：空块 = 无操作（true），
            // 会话失效 = 拒绝（false）；两者**不得**混成同一个结果（guardrail F-2）
            if (!streamWriteAccepted(samples.size, !closed && ownerEpoch == epoch && !isCancelled())) return false
            if (samples.isEmpty()) return true
            val pcm = toPcm16(samples)
            synchronized(writeLock) {
                if (closed) return false
                val n = writeAll(audioTrack, pcm, ownerEpoch, isCancelled)
                if (n < 0) {
                    interrupted = true
                    return false
                }
                written += n
            }
            return true
        }

        override fun close() {
            synchronized(writeLock) {
                if (closed) return
                closed = true
            }
            synchronized(lock) {
                if (track === audioTrack) track = null
            }
            try {
                // 仅当写循环**正常写完**时才排空：写入被中断后按整段时长空转既无意义、
                // 又会在单飞化后长时间占住引擎独占锁（guardrail R2 N-3）
                if (!interrupted && ownerEpoch == epoch && !isCancelled()) {
                    drain(audioTrack, written, sampleRate, ownerEpoch, isCancelled)
                }
                // underrun 计数（guardrail TKN-LVC-011 新-3）：真机自证"供数不足"的唯一客观指标
                val underruns = underrunCountOf(audioTrack)
                if (underruns > 0) {
                    Log.w(TAG, "本次播放发生 $underruns 次 underrun（音频输出栈供数不足，可能伴随可闻断音）")
                }
            } finally {
                runCatching { audioTrack.stop() }
                runCatching { audioTrack.release() }
            }
        }
    }

    /** underrun 计数（API 24+；取不到时返回 -1，日志里可区分"不支持"与"0 次"）。 */
    private fun underrunCountOf(audioTrack: AudioTrack): Int =
        runCatching { audioTrack.underrunCount }.getOrDefault(-1)

    /** 等待已写入但尚未播出的帧（防尾音被紧跟的 stop/release 截断）。 */
    private fun drain(
        audioTrack: AudioTrack,
        totalFrames: Int,
        sampleRate: Int,
        myEpoch: Int,
        isCancelled: () -> Boolean
    ) {
        val remainingFrames = totalFrames - audioTrack.playbackHeadPosition
        if (remainingFrames <= 0) return
        var waited = 0L
        val limit = remainingFrames * 1000L / sampleRate + DRAIN_MARGIN_MS
        // 播放头停走检测（TKN-LVC-010）：真机实证音频输出栈会整段卡死
        // （`AudioFlinger: blocked before threadloop_write() for 272427 msecs` + 持续
        //   `ACTIVE underrun due to thread sleep of 1920 frames`）——此时帧"已交付"但**从未出声**，
        // 而原实现只看时长上限 ⇒ 既听不到声音、日志也无线索。改为：播放头连续 STALL_DETECT_MS
        // 不前进即判定音频栈卡死，记 WARN 并提前返回（不额外占用引擎独占锁）。
        var lastHead = audioTrack.playbackHeadPosition
        var stalledMs = 0L
        while (waited < limit && myEpoch == epoch && !isCancelled()) {
            Thread.sleep(DRAIN_POLL_MS)
            waited += DRAIN_POLL_MS
            val head = audioTrack.playbackHeadPosition
            if (head == lastHead) {
                stalledMs += DRAIN_POLL_MS
                if (stalledMs >= STALL_DETECT_MS) {
                    Log.w(
                        TAG,
                        "播放头停走 ${stalledMs}ms（head=$head/$totalFrames）：判定音频输出栈卡死，" +
                            "本次播报可能中途无声（设备侧 HAL 阻塞，非本地合成失败）"
                    )
                    return
                }
            } else {
                lastHead = head
                stalledMs = 0L
            }
        }
    }

    override fun stop() {
        epoch++
        synchronized(lock) {
            track?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
            }
        }
    }

    override fun release() {
        stop()
    }

    companion object {
        /** 日志 tag（与引擎一致，便于真机按同一 tag 过滤）。 */
        private const val TAG = "LocalClone"

        /** 16bit mono → 2 字节/帧。 */
        private const val BYTES_PER_FRAME = 2

        /** 排空轮询间隔（ms）。 */
        private const val DRAIN_POLL_MS = 20L

        /** 排空余量（ms）。 */
        private const val DRAIN_MARGIN_MS = 80L

        /**
         * 播放头停走判定阈值（ms）：连续该时长内 `playbackHeadPosition` 不前进即判音频输出栈卡死。
         * 取 2000ms——明显长于正常调度抖动（毫秒级）与 80ms 级别的 underrun 粒度，
         * 又短于"用户已经明显感知无声"的时间（真机实证 HAL 可阻塞数百秒）。
         */
        private const val STALL_DETECT_MS = 2_000L
    }
}

/**
 * LVC-1（ADR-053）：本地音色引擎工厂。
 *
 * **反射创建**（复用 [io.prism.voice.AsrEngineFactory] 的既有模式）：sherpa 实现位于
 * `src/sherpa/kotlin` 条件源集（仅在 `app/libs/sherpa-onnx-android.aar` 存在时参与编译），
 * 主源集**不得**直接引用其类型，否则 AAR 缺失时整个 main 源集编译失败。
 *
 * 工厂本身不做模型加载（那是 118MB 解码器的活，必须发生在 IO 线程首次合成时），
 * 因此这里的失败面只有"类/构造器不存在"（AAR 缺失）。
 */
object LocalCloneEngineFactory {

    private const val TAG = "LocalClone"

    /** ZipVoice 合成器实现类（条件源集，反射解析）。 */
    private const val ZIPVOICE_SYNTHESIZER_CLASS = "io.prism.voice.local.SherpaZipVoiceSynthesizer"

    /** VITS 合成器实现类（条件源集，反射解析；ADR-055）。 */
    private const val VITS_SYNTHESIZER_CLASS = "io.prism.voice.local.SherpaVitsSynthesizer"

    /** 按音色包声明的引擎选实现类（ADR-055 决策 5）。 */
    internal fun synthesizerClassFor(tts: LocalTtsKind): String = when (tts) {
        LocalTtsKind.ZIPVOICE -> ZIPVOICE_SYNTHESIZER_CLASS
        LocalTtsKind.VITS -> VITS_SYNTHESIZER_CLASS
    }

    /**
     * 创建本地音色引擎。
     *
     * @return 引擎；**合成器实现类不可解析（AAR 缺失）时返回 null**——调用方据此静默回退系统播报
     */
    fun create(
        pack: LocalVoicePack,
        config: LocalCloneConfig,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        disposeTimeoutMs: Long = LocalCloneTtsEngine.DEFAULT_DISPOSE_TIMEOUT_MS,
        playbackMode: LocalClonePlaybackMode = LocalClonePlaybackMode.CONTINUOUS
    ): LocalCloneTtsEngine? = try {
        val sanitized = config.sanitized()
        val className = synthesizerClassFor(pack.tts)
        val synthesizerFactory: () -> LocalCloneSynthesizer = {
            val clazz = Class.forName(className)
            val ctor = clazz.getConstructor(LocalVoicePack::class.java, LocalCloneConfig::class.java)
            ctor.newInstance(pack, sanitized) as LocalCloneSynthesizer
        }
        // 仅探测可解析性（不加载模型）：构造器存在性在首次合成前判明，避免用户走到"没声音"才发现
        synthesizerFactoryProbe(className)
        LocalCloneTtsEngine(
            pack = pack,
            config = sanitized,
            synthesizerFactory = synthesizerFactory,
            playerFactory = { AudioTrackLocalClonePlayer() },
            ioDispatcher = ioDispatcher,
            scope = scope,
            disposeTimeoutMs = disposeTimeoutMs,
            playbackMode = playbackMode
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "本地音色引擎不可用（${e::class.simpleName}，tts=${pack.tts.wire}），回退系统播报")
        null
    }

    /** 探测合成器类与构造器是否存在（不实例化——构造即加载 118MB 模型）。 */
    private fun synthesizerFactoryProbe(className: String) {
        val clazz = Class.forName(className)
        clazz.getConstructor(LocalVoicePack::class.java, LocalCloneConfig::class.java)
    }
}
