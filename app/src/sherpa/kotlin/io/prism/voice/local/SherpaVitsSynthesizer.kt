package io.prism.voice.local

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * LVC-3（ADR-055）：**微调中文 VITS** 的 sherpa 实现。
 *
 * 主源集经 [`LocalCloneEngineFactory`] 反射创建本类（`pack.tts == "vits"` 时）。
 *
 * ## 与 ZipVoice 实现的关键差异
 *
 * | 维度 | ZipVoice（ADR-053） | VITS（本类） |
 * | --- | --- | --- |
 * | 音色来源 | **参考音频**（每次合成都要读 prompt wav） | **模型权重**（[synthesize] 的 `reference` 被忽略） |
 * | 必需资产 | tokens/encoder/decoder/vocoder/lexicon/espeak-ng-data | model/lexicon/tokens（+ 可选 dict / ruleFsts） |
 * | 生成参数 | `extra` 里 6 个 ZipVoice 专有键 + `numSteps` | **只有 `speed`**（其余字段对 VITS 无意义，**不得透传**） |
 * | 输出采样率 | 24kHz（vocos） | **16kHz**（中文单语底模；见验证报告 §3） |
 *
 * ## 端侧字段映射（AAR v1.13.7 字节码实证，ADR-055 事实 1/2）
 *
 * - `OfflineTtsVitsModelConfig(model, lexicon, tokens, dataDir, dictDir, noiseScale, noiseScaleW, lengthScale)`
 *   —— **没有 fst 字段**；
 * - 规则 FST 只能走 **`OfflineTtsConfig.ruleFsts`**（顶层字符串，逗号分隔）。放错位置 = **静默丢规则**
 *   （数字/多音字读法出错，且无任何日志）。
 *
 * ## 已知前提（不在本类内兜底，属侧载流程职责）
 *
 * 导出的 `model.onnx` **必须已修好非空 `comment` 元数据**：上游
 * `offline-tts-vits-model.cc:197-198` 用的是**唯一没有 DEFAULT 的**元数据宏
 * （`SHERPA_ONNX_READ_META_DATA_STR`），缺失即 `SHERPA_ONNX_EXIT(-1)`——**进程直接 abort，
 * Kotlin 侧无法捕获**。Kotlin 侧读不到 onnx 元数据（无 onnxruntime API），故只能由
 * 侧载脚本/文档保证（ADR-055 风险 3 已显式登记为"只能靠流程保证"）。
 *
 * @param pack 已校验的 VITS 音色包（资产路径均为绝对路径）
 * @param config 生效生成参数（**已过 [LocalCloneConfig.sanitized]**）
 */
class SherpaVitsSynthesizer(
    private val pack: LocalVoicePack,
    private val config: LocalCloneConfig
) : LocalCloneSynthesizer {

    /**
     * 取本包内的 VITS 资产（ADR-055：`model` 为密封类型）。
     *
     * 不合法组合由工厂按 `pack.tts` 阻断在**创建阶段**；此处是第二道，把"类型不符"变成
     * 可回退的业务失败（`error` → 引擎 catch → 降级系统播报），而不是让 native 拿到错路径后崩溃。
     */
    private fun vitsFiles(): LocalModelFiles.Vits =
        pack.model as? LocalModelFiles.Vits
            ?: error("engine-mismatch: VITS 合成器收到 tts=${pack.tts.wire} 的音色包")

    /** sherpa 离线 TTS 引擎（构造即加载模型；释放见 [release]）。 */
    private val tts: OfflineTts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = vitsFiles().model.absolutePath,
                    lexicon = vitsFiles().lexicon.absolutePath,
                    tokens = vitsFiles().tokens.absolutePath,
                    dataDir = "",
                    // 结巴词典目录：缺失时传空串（上游按"无词典"处理），**不传 null**——Kotlin 侧字段是非空 String
                    dictDir = vitsFiles().dictDir?.absolutePath ?: "",
                    // 生成质量旋钮（TKN-LVC-016 起可由清单 `generation.noiseScale/noiseScaleW` 覆盖）：
                    // 缺省即官方值（0.667 / 0.8）⇒ 旧清单行为逐字节不变。
                    // **刻意不从 LocalCloneConfig 取 guidanceScale/featScale 等**——那些是 ZipVoice 专有语义，
                    // 套到 VITS 上只会是"看起来调了其实没意义"。
                    //
                    // `lengthScale` 是**有意留在这里的常量**而非清单字段：上游 `RunVits` 在
                    // `speed != 1` 时用 `length_scale = 1. / speed` **覆盖**它（见 VitsGeneration KDoc），
                    // 暴露出去就是个死旋钮。时长由 `generation.speed` 唯一控制。
                    noiseScale = pack.vitsGeneration.noiseScale,
                    noiseScaleW = pack.vitsGeneration.noiseScaleW,
                    lengthScale = DEFAULT_LENGTH_SCALE
                ),
                numThreads = config.numThreads,
                debug = false
            ),
            // 规则 FST 的**唯一**通道（见类 KDoc）。空列表时传空串 = 不启用规则。
            ruleFsts = vitsFiles().ruleFsts.joinToString(",") { it.absolutePath },
            // 与 ZipVoice 实现同一口径：显式给值而不是靠 sherpa 默认（默认 0.2 会压缩停顿）
            silenceScale = config.silenceScale
        )
    )

    override fun synthesize(
        text: String,
        reference: LocalReference?,
        config: LocalCloneConfig,
        isCancelled: () -> Boolean,
        onChunk: (FloatArray) -> Unit
    ): LocalCloneAudio {
        // VITS **不需要**参考音频：音色在权重里。参数保留是为了满足 LocalCloneSynthesizer 契约
        // （引擎层对两种引擎走同一条调用路径）；此处刻意不读 reference.audio —— 读它只会浪费 IO
        // 并且会误导"VITS 也依赖 prompt"。
        val generation = GenerationConfig(
            silenceScale = config.silenceScale,
            speed = config.speed
        )
        val started = System.nanoTime()
        val chunkCount = java.util.concurrent.atomic.AtomicInteger(0)
        // 回调必须是**具名类的实例**（同 ZipVoice：lambda 脱糖后擦除签名，sherpa JNI 按运行时类
        // 硬查 `invoke([F)Ljava/lang/Integer;` → GetMethodID 失败 → SIGABRT）。
        val callback = LocalCloneSampleCallback(isCancelled) { chunk -> chunkCount.incrementAndGet(); onChunk(chunk) }
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
        val truncated = isCancelled()
        if (truncated) {
            Log.w(TAG, "本次合成分段被中止（truncated=true, chunks=${chunkCount.get()}）：音频可能不完整")
        }
        Log.i(
            TAG,
            "合成完成：elapsed=%.2fs audio=%.2fs rtf=%.2f sr=%d chars=%d chunks=%d truncated=%s".format(
                elapsedSec, audioSec, if (audioSec > 0) elapsedSec / audioSec else -1.0,
                audio.sampleRate, text.length, chunkCount.get(), truncated
            )
        )
        return LocalCloneAudio(samples = audio.samples, sampleRate = audio.sampleRate, truncated = truncated)
    }

    /**
     * 声明输出采样率（LVC-2 流式播放需要它在**首块音频到达前**就建好 AudioTrack）。
     *
     * 取自 `OfflineTts.sampleRate()`（微调中文单语底模 → **16 kHz**，实测一致），**不硬编码**。
     */
    override fun sampleRate(): Int = tts.sampleRate()

    override fun release() {
        runCatching { tts.release() }
    }

    companion object {
        private const val TAG = "LocalClone/Vits"

        /**
         * 生成旋钮的固定值（**不暴露给 [LocalCloneConfig]**）。
         *
         * 依据：这三个字段是 sherpa VITS 的通用推理参数，与 ZipVoice 的同名概念**不同源**；
         * 用一个共用配置域去驱动两种引擎，会让"改了没效果"或"改了换个引擎就变味"同时发生。
         *
         * TKN-LVC-016 起：`noiseScale`/`noiseScaleW` 改由 [LocalVoicePack.vitsGeneration]
         * （清单 `generation.noiseScale/noiseScaleW`）提供，这里保留为**同源默认值**；
         * `lengthScale` 仍是常量——上游在 `speed != 1` 时会覆盖它，做成配置项只会是死旋钮。
         */
        const val DEFAULT_NOISE_SCALE = VitsGeneration.DEFAULT_NOISE_SCALE
        const val DEFAULT_NOISE_SCALE_W = VitsGeneration.DEFAULT_NOISE_SCALE_W
        const val DEFAULT_LENGTH_SCALE = 1.0f
    }
}
