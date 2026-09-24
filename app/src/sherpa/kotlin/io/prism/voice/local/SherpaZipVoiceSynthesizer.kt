package io.prism.voice.local

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.k2fsa.sherpa.onnx.WaveReader

/**
 * LVC-1（ADR-053）：ZipVoice 端侧合成器（sherpa-onnx `OfflineTts`）。
 *
 * **为什么在 `src/sherpa/kotlin` 条件源集**：本类直接引用 `com.k2fsa.sherpa.onnx.*`，
 * 而该依赖仅在 `app/libs/sherpa-onnx-android.aar` 存在时参与编译（`app/build.gradle.kts` 第 93-98 行）。
 * 放进主源集会让"AAR 缺失"从"降级为系统播报"退化成"整个项目编译失败"。
 * 主源集经 [LocalCloneEngineFactory] 反射创建本类。
 *
 * **构造即加载模型**（119MB 解码器 + 52MB 声码器 + 17MB 音素数据），因此**只允许在 IO 线程构造**
 * （引擎在首次合成时经 [LocalCloneSynthesizer] 工厂触发）。
 *
 * **关键参数依据**（调研报告 `docs/reports/2026-09-19-local-voice-clone-research.md`）：
 * - `numSteps = 4`：ZipVoice 官方对 distill 模型的速度优先推荐值（所有语言绑定统一 4）；
 * - `lexicon` + `dataDir` **两者都必须给**：中文经 `lexicon.txt`（汉字→拼音），
 *   英文/音素化经 `espeak-ng-data`；缺一即掉字或直接失败；
 * - 参考文本必须与参考音频**逐字一致**（官方 Important），故两侧都过
 *   [LocalCloneTextNormalizer]；
 * - 输出采样率以引擎回报为准（`vocos_24khz` → 24kHz），不硬编码。
 *
 * @param pack 已校验的音色包（模型文件路径均为绝对路径）
 * @param config 生效生成参数
 */
class SherpaZipVoiceSynthesizer(
    private val pack: LocalVoicePack,
    private val config: LocalCloneConfig
) : LocalCloneSynthesizer {

    /** sherpa 离线 TTS 引擎（构造即加载模型；释放见 [release]）。 */
    private val tts: OfflineTts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                zipvoice = OfflineTtsZipVoiceModelConfig(
                    tokens = zipVoiceFiles().tokens.absolutePath,
                    encoder = zipVoiceFiles().encoder.absolutePath,
                    decoder = zipVoiceFiles().decoder.absolutePath,
                    vocoder = zipVoiceFiles().vocoder.absolutePath,
                    dataDir = zipVoiceFiles().dataDir.absolutePath,
                    lexicon = zipVoiceFiles().lexicon.absolutePath,
                    // 双保险（guardrail TKN-LVC-003 F-6 同类漏改排查）：这 4 个字段虽会被生成时的
                    // `extra` 覆盖（上游 `GetExtraFloat(key, config.model.zipvoice.X)`），但**若将来键名
                    // 被改错，模型级配置会成为静默回落点**（guidance 回落 1.0 会直接毁掉音色）。
                    // 此处显式设定，使"键名笔误"与"我们确实要这个值"解耦——两处都错才可能失效。
                    featScale = config.featScale,
                    tShift = config.tShift,
                    targetRms = config.targetRms,
                    guidanceScale = config.guidanceScale
                ),
                numThreads = config.numThreads,
                debug = false
            )
        )
    )

    /**
     * 取本包内的 ZipVoice 资产（ADR-055 起 `model` 为密封类型）。
     *
     * 不合法组合（VITS 包走到这里）由工厂 [`LocalCloneEngineFactory.synthesizerClassFor`] 按
     * `pack.tts` 阻断在**创建阶段**；此处是第二道，把"类型不符"变成可回退的业务失败而不是 native 崩溃。
     */
    private fun zipVoiceFiles(): LocalModelFiles.ZipVoice =
        pack.model as? LocalModelFiles.ZipVoice
            ?: error("engine-mismatch: ZipVoice 合成器收到 tts=${pack.tts.wire} 的音色包")

    override fun synthesize(
        text: String,
        reference: LocalReference?,
        config: LocalCloneConfig,
        isCancelled: () -> Boolean,
        onChunk: (FloatArray) -> Unit
    ): LocalCloneAudio {
        // 契约（ADR-055 起 reference 可空）：**ZipVoice 必须**拿到参考音频——它是音色与韵律的唯一来源。
        // 引擎已按 pack.tts 分流，此处的 error 是第二道（可回退失败，而非让 native 拿空 prompt 崩）。
        val prompt = reference ?: error("prompt-missing: ZipVoice 合成必须提供参考音频")
        val wave = WaveReader.readWave(prompt.audio.absolutePath)
        val generation = GenerationConfig(
            // TKN-LVC-003：silenceScale 必须显式给 1.0——sherpa 默认 0.2 会把所有 >0.2s 停顿压到 20%
            // （`if (config.silence_scale != 1) ScaleSilence(...)`），机制上这是"语速过快、节奏不像"的
            // 首要嫌疑（需真机 A/B 确认听感）
            silenceScale = config.silenceScale,
            speed = config.speed,
            referenceAudio = wave.samples,
            referenceSampleRate = wave.sampleRate,
            // 官方要求 reference_text 与参考音频逐字一致；规范化与目标文本同一出口
            referenceText = LocalCloneTextNormalizer.normalize(prompt.text),
            // num_steps 由 C++ 读 `config.num_steps`（字段生效）；extra 里的其余键优先于模型级配置
            numSteps = config.numSteps,
            extra = config.extra + mapOf("speed" to config.speed.toString())
        )
        val started = System.nanoTime()
        // 分段计数（TKN-LVC-011）：**仅作现场对照**（无"应有分段数"分母，不能单独判定截短）
        val chunkCount = java.util.concurrent.atomic.AtomicInteger(0)        // **回调必须是具名类的实例**（见 LocalCloneSampleCallback KDoc）：lambda 经 invokedynamic/D8
        // 脱糖后只暴露擦除签名，而 sherpa JNI 按运行时类硬查 `invoke([F)Ljava/lang/Integer;` →
        // GetMethodID 失败 → 挂起异常 + NewFloatArray → SIGABRT（真机 2026-09-20 实证）。
        val callback = LocalCloneSampleCallback(isCancelled) { chunk -> chunkCount.incrementAndGet(); onChunk(chunk) }
        // 预检：把"签名不符"从不可捕获的 native abort 降级为可回退的业务失败
        check(LocalCloneSampleCallback.hasJniCompatibleInvoke(callback)) {
            "callback-signature-mismatch: 需要 ${LocalCloneSampleCallback.JNI_REQUIRED_DESCRIPTOR}"
        }
        val audio = tts.generateWithConfigAndCallback(
            text = text,
            config = generation,
            callback = callback
        )
        val elapsedSec = (System.nanoTime() - started) / 1_000_000_000.0
        val audioSec = if (audio.sampleRate > 0) audio.samples.size.toDouble() / audio.sampleRate else 0.0
        // `truncated` = **生成返回时取消探针为真**（即已放弃；可能是回调返回过 STOP，也可能是返回瞬间才置位）。
        // **只有"确实放弃"才会**（用户停止/被取代/已释放）——
        // 超时不再触发中止（TKN-LVC-011）。为真时音频可能被截短，故显式记录。
        val truncated = isCancelled()
        if (truncated) {
            Log.w(TAG, "本次合成分段被中止（truncated=true, chunks=${chunkCount.get()}）：音频可能不完整")
        }
        // 可观测性：合成耗时/音频时长/RTF 估算（真机 RTF 门禁的数据来源，见 PRD AC6）。
        // chars/chunks 用于现场对照（⚠️ 无"应有分段数"分母，**不能单独判定**音频是否被截短；
        // 判据是 truncated 与 chars 的组合——guardrail TKN-LVC-011 G-3）。
        Log.i(
            TAG,
            "合成完成：elapsed=%.2fs audio=%.2fs rtf=%.2f sr=%d steps=%d chars=%d chunks=%d truncated=%s".format(
                elapsedSec, audioSec, if (audioSec > 0) elapsedSec / audioSec else -1.0,
                audio.sampleRate, config.numSteps, text.length, chunkCount.get(), truncated
            )
        )
        return LocalCloneAudio(samples = audio.samples, sampleRate = audio.sampleRate, truncated = truncated)
    }

    /**
     * 声明输出采样率（LVC-2 流式播放需要它在**首块音频到达前**就建好 AudioTrack）。
     *
     * 取自 sherpa 的 `OfflineTts.sampleRate()`（ZipVoice 经 `vocos_24khz` 输出 24kHz），
     * **不硬编码**——与 [synthesize] 返回的 `GeneratedAudio.sampleRate` 同源。
     */
    override fun sampleRate(): Int = tts.sampleRate()

    override fun release() {
        runCatching { tts.release() }
    }

    companion object {
        private const val TAG = "LocalClone/ZipVoice"
    }
}
