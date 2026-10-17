package io.prism.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 批次22（SZ-201/SZ-202，ADR-047）：sherpa-onnx 端侧流式 ASR 引擎（贾维斯形态核心）。
 *
 * - **流式 zipformer 中文 14M int8**（assets/models/streaming-zipformer-zh-14M/，约 24MB）：
 *   边说边出字（onPartial）+ **endpoint 自动断句**（trailingSilence 规则，免按压）；
 * - 持续聆听：内部 AudioRecord 循环（16k/16bit/mono，VOICE_COMMUNICATION 源带 AEC 防 TTS
 *   回声自触发），[startListening] 进入循环直至 [cancel]——[VoiceSessionController] 对
 *   流式引擎全程只 start 一次（免按压连续对话），endpoint 断句经 onFinal 驱动提交；
 * - [resetCapture]：回聆听前清解码缓冲（防 AI 播报残留被误提交）；
 * - **回调全部经主线程 Handler 派发**（[AsrEngine.Listener] 主线程契约——onFinal 触发的
 *   sendMessage/UI 状态机要求主线程；RMS 音量同步回调驱动播报打断 barge-in 与波形动画）；
 * - 模型文件由 [copyAssetsIfAbsent] 从 assets 拷贝到 filesDir（sherpa 需要文件路径），
 *   [isAvailable] 同时检查 assets（首装未拷贝时仍可用）。
 *
 * 本文件位于 **sherpa 条件源集**（app/src/sherpa/kotlin）——libs/sherpa-onnx-android.aar
 * 存在时才参与编译（PRD SZ-201 降级兼容：缺失时 AsrEngineFactory 回落 SystemAsrEngine）。
 * proguard-rules.pro 对本类与 com.k2fsa.sherpa.onnx.** 有 keep（AsrEngineFactory 反射
 * 创建 + JNI 按名访问，R8 缺 keep 会正式包静默失效——批次21 Shizuku 同款坑）。
 */
class SherpaAsrEngine(private val context: Context) : AsrEngine {

    private val running = AtomicBoolean(false)

    /** 识别线程引用（@Volatile——旧线程 finally 守卫跨线程读取，JMM 可见性必须保证）。 */
    @Volatile
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    /** [resetCapture] 跨线程请求标记（loop 每轮消费——语义=重建流，见 loop 内注释）。 */
    private val resetRequested = AtomicBoolean(false)

    override val isStreaming: Boolean get() = true

    /**
     * 模型就绪 = **assets 或 filesDir** 任一处含 encoder+tokens（assets 在 → 首次
     * copyAssetsIfAbsent 后即用；只查 filesDir 会导致首装永远不可用）。
     */
    override val isAvailable: Boolean
        get() = hasAsset(ENCODER) && hasAsset(TOKENS) || run {
            val dir = modelDir()
            File(dir, ENCODER).exists() && File(dir, TOKENS).exists()
        }

    private fun hasAsset(name: String): Boolean = runCatching {
        context.assets.open("$MODEL_ASSET_DIR/$name").close()
        true
    }.getOrDefault(false)

    private fun modelDir(): File = File(context.filesDir, MODEL_ASSET_DIR).apply {
        if (!exists()) mkdirs()
    }

    /** assets → filesDir 拷贝（幂等，在识别线程执行避免主线程 I/O）。 */
    private fun copyAssetsIfAbsent() {
        val dir = modelDir()
        val files = listOf(ENCODER, DECODER, JOINER, TOKENS)
        val am = context.assets
        for (f in files) {
            val out = File(dir, f)
            if (out.exists() && out.length() > 0) continue
            am.open("$MODEL_ASSET_DIR/$f").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    override fun startListening(listener: AsrEngine.Listener) {
        if (running.get()) return  // 常驻流：已在聆听则幂等（免按压模式每轮复用）
        if (!isAvailable) {
            listener.onError("端侧语音模型未就绪（请查看 docs/runbooks/setup-sherpa-voice.md）")
            return
        }
        running.set(true)
        thread = Thread {
            loop(listener)
        }.apply {
            name = "SherpaAsr"
            start()
        }
    }

    private fun loop(listener: AsrEngine.Listener) {
        // guardrail R3#1：资源在 finally 幂等收口——read/decode 任一异常时 AudioRecord
        // 若不 release 将保持录音态（系统隐私指示器常亮、其它 App 无法录音）。
        // recognizerRef/streamRef 为非空局部变量的镜像（循环内用非空局部驱动，finally 收口）
        var recorder: AudioRecord? = null
        var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
        var recognizerRef: OnlineRecognizer? = null
        var streamRef: com.k2fsa.sherpa.onnx.OnlineStream? = null
        try {
            copyAssetsIfAbsent()
            val dir = modelDir()
            // 批次22 修复轮五（2026-09-07，同音字优化）：热词偏置（contextual biasing）——
            // sherpa 官方仅 transducer 模型 + **modified_beam_search** 解码支持热词
            //（greedy_search 不支持，见 k2-fsa 文档 Hotwords 章节）；用户在设置页维护
            // 人名/专有名词热词表（每行一个），解码时 Aho-Corasick 加权提升同音字区分。
            // modified_beam_search 较 greedy 略耗 CPU（14M/small 模型双线程可承受）。
            val hotwordsFile = File(context.filesDir, VOICE_HOTWORDS_FILE)
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(dir, ENCODER).absolutePath,
                        decoder = File(dir, DECODER).absolutePath,
                        joiner = File(dir, JOINER).absolutePath,
                    ),
                    tokens = File(dir, TOKENS).absolutePath,
                    numThreads = 2,
                    // 批次22 修复轮六（2026-09-07）：热词编码必须声明 modelingUnit——缺失时
                    // utils.cc EncodeHotwords "modeling_unit should be one of bpe, cjkchar or
                    // cjkchar+bpe" → SHERPA_ONNX_EXIT(-1) 打开语音通话即崩（真机两进程复现，
                    // guardrail 上轮 W2 预警命中）。本模型为中文 char 级 tokens → cjkchar。
                    modelingUnit = "cjkchar",
                ),
                decodingMethod = "modified_beam_search",
                enableEndpoint = true,
                // 1.13.x endpoint 规则结构（EndpointConfig/EndpointRule）：
                // rule1=未出内容静音 2.4s 断句；rule2=已出内容静音 1.2s 快速断句（免按压）；
                // rule3=单句超 20s 强制断句（防无限累积）
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0f),
                    rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0f),
                    rule3 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0f, minUtteranceLength = 20f),
                ),
                // 热词文件存在且非空时启用（热词得分 2.0：官方示例常用区间 1.5~2.5，
                // 过高会导致热词"霸屏"误插）
                hotwordsFile = hotwordsFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath ?: "",
                hotwordsScore = 2.0f,
            )
            val recognizer = OnlineRecognizer(assetManager = null, config = config)
            recognizerRef = recognizer
            // 批次22 真机修复（2026-09-06）：**禁用 recognizer.reset(stream)**——官方 Reset
            // "只更新计数器、不丢弃底层音频"（online-recognizer-impl.h Reset 注释），与本
            // featurizer 的物理帧丢弃（features.cc GetFrames→PopWrapper→last_frame_index_ 前移）
            // 组合会破坏 GetFrames 不变量 → SHERPA_ONNX_EXIT(-1) 进程静默死亡。
            // **断句/清缓冲一律改为丢弃旧流、重建新流**（新 featurizer+新计数器，零错位）。
            var stream = recognizer.createStream()
            streamRef = stream

            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            // 批次22 修复轮七（lint MissingPermission）：显式权限闸——未授予 RECORD_AUDIO 时
            // 立即失败回调，杜绝 SecurityException/未初始化 AudioRecord 的静默路径
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                main.post { listener.onError("录音初始化失败（请检查麦克风权限）") }
                running.set(false)
                return
            }
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                main.post { listener.onError("录音初始化失败（请检查麦克风权限）") }
                running.set(false)
                return
            }
            rec.startRecording()
            recorder = rec
            // BUG-20260909-005：挂 AEC 回声消除——TTS 播报声经麦克风拾取被识别成文本，
            // 曾致一次通话 SPEAKING 门丢弃 5 次回声转写并误触发语义打断（云端的
            // VoiceAudio 路径已挂 AEC，sherpa 直读 AudioRecord 路径此前缺失）。
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                val aec = android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)
                val enabled = aec != null && runCatching { aec.enabled = true }.isSuccess
                echoCanceler = aec
                // guardrail F5：如实记录挂载结果（create 失败/enabled 失败不再误报"已挂载"）
                Log.i(TAG, "AEC 回声消除挂载：available=true, attached=${aec != null}, enabled=$enabled")
            } else {
                Log.i(TAG, "AEC 回声消除：设备不支持（isAvailable=false）")
            }
            val chunk = ShortArray(1600)  // 100ms @16k
            Log.i(TAG, "sherpa 流式识别循环启动")
            while (running.get()) {
                if (resetRequested.compareAndSet(true, false)) {
                    // resetCapture 语义 = 重建流（回聆听清回声残留；同 reset 规避理由）
                    runCatching { stream.release() }
                    stream = recognizer.createStream()
                    streamRef = stream
                    Log.i(TAG, "识别流已重建（resetCapture）")
                }
                val n = recorder.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { chunk[it] / 32768f }
                // RMS 音量（0..1 归一，×6 增益对齐会话级波形/barge-in 阈值刻度）
                var sum = 0.0
                for (s in samples) sum += s.toDouble() * s
                val rms = kotlin.math.sqrt(sum / n).toFloat().coerceIn(0f, 1f)
                main.post { listener.onVolume((rms * 6f).coerceAtMost(1f)) }

                stream.acceptWaveform(samples, sampleRate = 16000)
                // 中间结果（低延迟回显）
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }
                val partial = recognizer.getResult(stream).text
                if (partial.isNotBlank()) main.post { listener.onPartial(partial) }
                // endpoint 断句（免按压核心）——官方模式：**此处禁止再 decode**（IsReady 门控
                // 只保证剩余 ≥ChunkSize，但 DecodeStreams 固定拉 chunk_size 帧；静音期剩余不足
                // 时 GetFrames 不变量破坏 → SHERPA_ONNX_EXIT(-1) 静默杀进程，真机 5 次复现）。
                // 直接用已 drain 的 partial 作为断句结果；残余 <1 chunk（≤390ms，多为静音尾）
                // 随流重建丢弃。
                if (recognizer.isEndpoint(stream)) {
                    if (partial.isNotBlank()) {
                        // guardrail R2#4：用户口述属 PII，不写 logcat（仅记长度）
                        Log.i(TAG, "endpoint 断句（长度=${partial.length}）")
                        main.post { listener.onFinal(partial) }
                    }
                    runCatching { stream.release() }
                    stream = recognizer.createStream()
                    streamRef = stream
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.w(TAG, "流式循环异常：${e::class.simpleName}: ${e.message}")
                main.post { listener.onError("端侧识别异常（${e::class.simpleName}），已回退按住说话模式") }
            }
        } finally {
            runCatching { echoCanceler?.release() }  // BUG-20260909-005：AEC 随录音循环释放
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { streamRef?.release() }
            runCatching { recognizerRef?.release() }
            // 仅当本线程仍是当前活跃循环时才清 running（防 cancel→立刻重启场景：
            // 旧线程的 finally 把新循环的标志误清，引擎静默死亡）
            if (Thread.currentThread() == thread) running.set(false)
        }
    }

    /** 停止断句本轮（保持流循环——连续对话语义）。 */
    override fun stopListening() {
        // 流式引擎无"单轮停止"概念——endpoint 自动断句；此方法仅为接口兼容
    }

    override fun cancel() {
        running.set(false)
        thread = null
    }

    /** 清空识别缓冲（AI 播报结束回聆听前调用，防播报残留被误提交）。 */
    override fun resetCapture() {
        resetRequested.set(true)
    }

    override fun release() {
        cancel()
    }

    companion object {
        private const val TAG = "SherpaAsrEngine"

        /** 模型 assets/ filesDir 相对目录（批次22 修复轮：升级为 small-bilingual 中英双语模型）。 */
        private const val MODEL_ASSET_DIR = "models/streaming-zipformer-small-bilingual-zh-en"
        private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER = "decoder-epoch-99-avg-1.onnx"
        private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS = "tokens.txt"
    }
}
