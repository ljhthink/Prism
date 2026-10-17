package io.prism.voice.local

import java.io.File

/**
 * LVC-1（ADR-053）：端侧本地音色包的数据模型。
 *
 * **定位**：一个「本地音色包」= 一个角色音色的全部离线资产（ZipVoice 模型 + 参考音频集），
 * 由 `voice-clone-kit\` 的脚本产出并**侧载**到设备（不入 APK、不入公开仓库）。
 * [io.prism.voice.rolepack.RolePack.Voice.local] 只以 `voiceId` 引用它——
 * **不内嵌路径、不内嵌模型**（模型约 195MB/角色）。
 *
 * 目录布局（`<root>/<voiceId>/`）：
 * ```
 * voicepack.json          清单（本文件 §解析）
 * model/                  ZipVoice 模型（tokens/encoder/decoder/vocoder/lexicon/espeak-ng-data）
 * refs/ref_XX.wav         参考音频（24kHz 单声道 16bit PCM）
 * ```
 *
 * @property voiceId 音色包 id（与目录名一致；角色包 `voice.local.voiceId` 的值）
 * @property name 显示名
 * @property root 音色包根目录（所有相对路径的解析基准；解析期已做过逃逸校验）
 * @property tts 引擎类型（清单可选顶层 `"tts"`；**缺省 [LocalTtsKind.ZIPVOICE]** ⇒ 旧包全兼容）
 * @property model 模型文件集合（按 [tts] 分流；全部为已解析的绝对 [File]）
 * @property generation 生成参数（来自清单 `generation`，已被角色包覆盖项合并）
 * @property references 参考音频集（**ZipVoice 必非空**；VITS 允许为空——音色在权重里，不需要 prompt）
 * @property totalBytes 音色包可统计字节数（仅清单内文件与参考音频；用于设置页展示）
 */
data class LocalVoicePack(
    val voiceId: String,
    val name: String,
    val root: File,
    val tts: LocalTtsKind = LocalTtsKind.ZIPVOICE,
    val model: LocalModelFiles,
    val generation: LocalCloneConfig,
    val references: List<LocalReference>,
    val totalBytes: Long,
    /**
     * VITS 专属生成旋钮（TKN-LVC-016）。非 VITS 包取默认值即可（不会被读取）。
     *
     * **为什么与 [generation] 分开**：`noiseScale/noiseScaleW` 是 sherpa VITS 的通用推理参数，
     * 与 ZipVoice 的同名概念**不同源**；共用一个配置域会让"改了对另一种引擎换味"。
     * 位置放在末位并给默认值，是为了不改变既有构造调用点的语义（全部具名传参）。
     */
    val vitsGeneration: VitsGeneration = VitsGeneration()
) {
    /** 参考音频集内所有音频文件（供校验/统计/清理）。 */
    val referenceFiles: List<File> get() = references.map { it.audio }

    /**
     * 参与**模型身份**（缓存键）与体积统计的文件（不含参考音频与目录型数据）。
     *
     * 抽在一处的理由：`localCloneModelIdentity` 少算一个文件就会出现"换了模型没效果"
     * （ADR-053 F-3/N-2 的同一教训），因此两种引擎各自的清单**只能在这里定义一次**。
     */
    val assetFiles: List<File>
        get() = when (val m = model) {
            is LocalModelFiles.ZipVoice -> listOf(m.tokens, m.encoder, m.decoder, m.vocoder, m.lexicon)
            is LocalModelFiles.Vits -> listOf(m.model, m.lexicon, m.tokens) + m.ruleFsts
        }

    /** 目录型资产（ZipVoice 的 espeak-ng-data / VITS 的 `dict/`），用于体积统计。 */
    val assetDirs: List<File>
        get() = when (val m = model) {
            is LocalModelFiles.ZipVoice -> listOf(m.dataDir)
            is LocalModelFiles.Vits -> listOfNotNull(m.dictDir)
        }
}

/**
 * VITS 专属生成旋钮（TKN-LVC-016，ADR-056）。
 *
 * ## 为什么只有两个字段
 *
 * 上游 `offline-tts-vits-model.cc:RunVits` 的三元组成员是
 * `noise_scale / length_scale / noise_scale_w`，其中 `length_scale` 会被**覆盖**：
 *
 * ```cpp
 * float length_scale = config_.vits.length_scale;
 * if (speed != 1 && speed > 0) { length_scale = 1. / speed; }   // ← speed 非 1 时 config 值被丢弃
 * ```
 *
 * 也就是说**只要清单给了 `generation.speed`（本仓默认非 1）**，暴露 `lengthScale` 就是一个
 * "看起来调了其实没意义"的死旋钮——正是 ADR-055 明令避免的那类字段。故：
 * **时长统一由 `generation.speed` 控制，本类不提供 `lengthScale`**。
 *
 * ## 为什么默认值就是官方值
 *
 * 0.667 / 0.8 是 sherpa-onnx VITS 与上游 VITS-fast-fine-tuning 推理侧的同一组默认值；
 * 缺省即它们 ⇒ **旧清单（没有这两个字段）行为逐字节不变**。
 *
 * @property noiseScale 生成噪声标准差（越小越"干净"但可能发闷，越大越"活"但可能有杂音）
 * @property noiseScaleW 时长预测器噪声标准差（影响韵律波动幅度）
 */
data class VitsGeneration(
    val noiseScale: Float = DEFAULT_NOISE_SCALE,
    val noiseScaleW: Float = DEFAULT_NOISE_SCALE_W
) {
    /**
     * 值域钳制：清单来自**设备可写目录**（可被任意改写），NaN/天文数字会直达 native。
     * 策略与 [LocalCloneConfig.sanitized] 一致——越界回落默认值（不判整包不可用）。
     */
    fun sanitized(): VitsGeneration = VitsGeneration(
        noiseScale = if (noiseScale.isFinite() && noiseScale in MIN_NOISE_SCALE..MAX_NOISE_SCALE)
            noiseScale else DEFAULT_NOISE_SCALE,
        noiseScaleW = if (noiseScaleW.isFinite() && noiseScaleW in MIN_NOISE_SCALE..MAX_NOISE_SCALE)
            noiseScaleW else DEFAULT_NOISE_SCALE_W
    )

    companion object {
        /** 官方默认（与 `SherpaVitsSynthesizer.DEFAULT_NOISE_SCALE` 同一来源，见该类 KDoc）。 */
        const val DEFAULT_NOISE_SCALE = 0.667f
        const val DEFAULT_NOISE_SCALE_W = 0.8f

        /**
         * 噪声标准差合法域。
         *
         * 下限 0 = 完全确定性（上游允许，听感发闷，保留以便 A/B）；上限 1.5 是**工程上界**：
         * 0.667/0.8 附近的官方值已在自然度上取平衡，再往上只会引入杂音（上游无公开上限，
         * 故此为可证边界而非官方约定，已在 ADR-056 登记）。
         */
        const val MIN_NOISE_SCALE = 0.0f
        const val MAX_NOISE_SCALE = 1.5f
    }
}

/**
 * 音色包使用的**合成引擎**（ADR-055）。
 *
 * 清单里以顶层字符串 `"tts"` 声明；**缺省 = [ZIPVOICE]**，使 ADR-053 时代已侧载的包无需改动即可继续工作。
 */
enum class LocalTtsKind(val wire: String) {
    /** ZipVoice 零样本克隆（ADR-053）：需要参考音频集 + encoder/decoder/vocoder。 */
    ZIPVOICE("zipvoice"),

    /** 微调中文 VITS（ADR-055）：音色在权重里，只需要冻结的模型资产，**不需要参考音频**。 */
    VITS("vits");

    companion object {
        /**
         * 解析清单里的 `tts` 值。
         *
         * @return 命中的枚举；**无法识别时返回 null**（调用方须判不可用——静默回落到 ZipVoice
         *   会让"VITS 包被当成 ZipVoice 加载"这种**静默换引擎**很难排查，与 F-3 同类）。
         */
        fun parse(raw: String?): LocalTtsKind? {
            val v = raw?.trim()?.lowercase() ?: return ZIPVOICE
            return entries.firstOrNull { it.wire == v }
        }
    }
}

/**
 * 音色包的模型文件集合（ADR-055 起为密封类型：编译器强制所有消费方显式处理两种引擎）。
 *
 * 为什么不用"一个类 + 可空字段"：可空字段会让"VITS 包里 encoder 为 null"这类非法组合在类型上合法，
 * 只能靠运行期 `!!`/校验兜住；密封类型把"两种引擎需要不同资产"这件事**编码进类型**。
 */
sealed interface LocalModelFiles {

    /**
     * ZipVoice 必需文件集合（ADR-053，原 `LocalVoicePack.ModelFiles`）。
     *
     * @property tokens 文本词表（`tokens.txt`）
     * @property encoder ZipVoice 编码器（int8）
     * @property decoder ZipVoice 解码器（int8；参考音频的音色与内容都在此注入）
     * @property vocoder 声码器（`vocos_24khz.onnx`；**不在模型包内**，官方要求单独下载）
     * @property lexicon 汉字→拼音词典（zh 合成必需）
     * @property dataDir espeak-ng 数据目录（en 合成与音素化必需）
     */
    data class ZipVoice(
        val tokens: File,
        val encoder: File,
        val decoder: File,
        val vocoder: File,
        val lexicon: File,
        val dataDir: File
    ) : LocalModelFiles

    /**
     * 微调 VITS 的模型资产（ADR-055）。
     *
     * @property model `model.onnx`（**必须已修好 `comment` 元数据**，否则 native 直接 abort；见 ADR-055 事实 5）
     * @property lexicon 拼音词典（`lexicon.txt`）
     * @property tokens 词表（`tokens.txt`）
     * @property dictDir 结巴分词词典目录（`dict/`；**可选**——缺失只影响分词质量，不阻断安装）
     * @property ruleFsts 文本规则 FST（`date/number/phone/new_heteronym.fst`；**可选**，
     *   经 `OfflineTtsConfig.ruleFsts` 注入。**注意**：该字段在 `OfflineTtsVitsModelConfig` 里**不存在**，
     *   只能走 `OfflineTtsConfig` 顶层——放错位置会**静默丢规则**，见 ADR-055 事实 1/2）
     */
    data class Vits(
        val model: File,
        val lexicon: File,
        val tokens: File,
        val dictDir: File?,
        val ruleFsts: List<File>
    ) : LocalModelFiles
}

/**
 * 一条参考音频（ZipVoice 的 prompt：音色来源 + **逐字对应的文本**）。
 *
 * @property id 参考 id（清单内唯一，如 `ref_00`）
 * @property audio 参考音频文件（16bit 单声道 WAV）
 * @property text **官方台词原文**（未规范化——规范化在 [LocalCloneTextNormalizer] 单点完成，
 *   数据层保持原文，避免双份维护）
 * @property emotion 情绪标签（信息性字段，供展示/后续按情绪检索；v1 不参与选择——见
 *   [LocalReferenceSelector]）
 */
data class LocalReference(
    val id: String,
    val audio: File,
    val text: String,
    val emotion: String? = null
)

/**
 * LVC-1（ADR-053）：ZipVoice 生成参数。
 *
 * **来源与优先级**：清单 `generation` 段 → 角色包 `voice.local.numSteps` 覆盖（仅 numSteps）→
 * 本类默认值。
 *
 * **默认值的依据（TKN-LVC-003 真机音质调优）**：
 * - [numSteps] = 8：ZipVoice 官方对 **distill** 模型的默认步数（`infer_zipvoice.py` 的
 *   `model_defaults`；README 允许降到 4 换速度）。**sherpa-onnx 侧 Kotlin 默认是 5、示例常用 4**，
 *   与上游默认不一致。
 * - [guidanceScale] = 3.0：机制上**是"电音/机械感"的首要嫌疑**。distill 模型的 `guidance_scale`
 *   **不是 CFG**，而是直接喂进 decoder 的条件 embedding（`use_guidance_scale_embed`），
 *   蒸馏训练采样区间为 [1,3]，官方推理默认 **3.0**；而 **sherpa-onnx 的默认是 1.0**（条件强度不足 →
 *   音色扁平、机械）。证据级别：官方 `infer_zipvoice.py` 的 `model_defaults` = 一手；
 *   "听感改善"= 待真机 A/B。
 * - [silenceScale] = 1.0：机制上**是"语速过快/节奏不像"的首要嫌疑**。sherpa-onnx 默认 0.2 会把所有
 *   **>0.2s 的停顿压缩到 20%**（`ScaleSilence`），自然停顿几乎消失；1.0 = 不缩放。
 *   （证据级别：上游源码 + 默认值 = 一手；"听感改善"= 待真机 A/B）
 * - [featScale] / [tShift] / [targetRms]：**保持官方训练值，不要动**。作者明确警告
 *   "预训练模型以 feat_scale 0.1 训练，改成 0.5 不可用"，社区实测 0.2 就"变机械音"。
 *   之所以仍然暴露，是为了让真机 A/B 能在不改代码的前提下验证——默认值即官方值。
 *
 * @property numSteps 流匹配步数（越小越快、质量略降）；distill 官方默认 8、下限 4
 * @property speed **时长缩放**：>1 加快、<1 变慢（官方语义）；输出时长 ∝ 参考音频"帧/字"比 ÷ speed,
 *   故参考音频的语速才是主控，speed 只作线性微调
 * @property silenceScale 生成后静音缩放（1.0 = 不缩放；<1 会压短自然停顿 ⇒ 听感变快）
 * @property guidanceScale 条件强度（distill = 条件 embedding，官方默认 3.0；base 模型才是 CFG）
 * @property featScale prompt mel 对数幅度缩放（**必须与训练一致：0.1**）
 * @property tShift 时间步 schedule 形变（官方 0.5）
 * @property targetRms prompt RMS 单向归一目标（官方 0.1；必须 > 0）
 * @property minCharInSentence 过短分块合并阈值（字符）
 * @property maxCharInSentence 单次生成分块上限（字符）
 * @property numThreads 推理线程数（**不影响音色**，只影响速度）
 * @property timeoutMs **合成看门狗**无进展阈值（ms）：**只告警，不取消合成**（TKN-LVC-011 修订，ADR-053 决策 7）——
 *   防低端机 RTF 不达标时用户长时间无响应
 * @property sentenceGapMs **段间停顿**（ms）：在**播放层**为相邻文本段之间插入的静音长度。
 *
 *   为什么需要它：真机"本地音色语速过快"的实测根因**不是音素念得快，而是停顿被压缩**
 *   （有声帧占比 0.849 vs 训练语料 0.736；平均停顿 0.232s vs 语料 0.538s，−57%）。
 *   端侧把长文本切段**逐段合成后连续播放，段间不插任何静音** ⇒ 听感进一步变快。
 *   故在段与段之间补固定静音，直接补偿被切段吃掉的停顿。
 *
 *   **为什么默认 0**：0 = 不插静音，与改造前**逐字节等价**（回归基线）；具体值须由真机试听定完后
 *   写进音色包清单，库侧不预设"看起来合理"的非零默认（那会让未标注的音色包听感被静默改动）。
 *
 *   **为什么越界回落默认 0 而不是钳到边界**（与本类其余 Int 字段同口径，但与 `coerceIn` 语义**不同**）：
 *   清单来自设备可写目录，脏数据（负数/超大值）若被钳成 2000 会**静默改变听感基线**；
 *   回落默认 0 则等价于"该字段没写"，与"档位脏数据回落最保真档"同一约定。
 *
 *   **两引擎通用**：段间停顿发生在播放层（[io.prism.voice.local.LocalCloneTtsEngine] 的分段生产/流式写入），
 *   与合成引擎无关，ZipVoice 与 VITS 同源生效。故它属于本类（通用域），**刻意不放进** [VitsGeneration]
 *   ——那里只放 VITS 专属旋钮，以免语义串味。
 *
 *   **必须进缓存键**（[localCloneCacheKey]）：它是在引擎/流水线构造期定死的输入，少一项就会
 *   "改了没效果"（与本仓历史缺陷 F-3 同型）。
 */
data class LocalCloneConfig(
    val numSteps: Int = DEFAULT_NUM_STEPS,
    val speed: Float = DEFAULT_SPEED,
    val silenceScale: Float = DEFAULT_SILENCE_SCALE,
    val guidanceScale: Float = DEFAULT_GUIDANCE_SCALE,
    val featScale: Float = DEFAULT_FEAT_SCALE,
    val tShift: Float = DEFAULT_T_SHIFT,
    val targetRms: Float = DEFAULT_TARGET_RMS,
    val minCharInSentence: Int = DEFAULT_MIN_CHARS,
    val maxCharInSentence: Int = DEFAULT_MAX_CHARS,
    val numThreads: Int = DEFAULT_NUM_THREADS,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** 段间停顿 ms（见类 KDoc；放在字段末尾以免破坏既有命名参数调用）。默认 0 = 不插（行为等价改造前）。 */
    val sentenceGapMs: Int = DEFAULT_SENTENCE_GAP_MS
) {
    /**
     * 生成的 `extra` 键值对（ZipVoice 专有参数经此传递）。
     *
     * 键名须与上游 `offline-tts-zipvoice-impl.h` 的 `GetExtraFloat/GetExtraInt` **逐字一致**：
     * 本 map 含 `min_char_in_sentence`/`max_char_in_sentence`/`guidance_scale`/`feat_scale`/
     * `t_shift`/`target_rms` 六项；**`speed` 由 [io.prism.voice.local.SherpaZipVoiceSynthesizer]
     * 追加，`num_steps` 走 `GenerationConfig.numSteps` 字段**（上游默认值表达式为
     * `GetExtraInt("num_steps", config.num_steps > 0 ? config.num_steps : 4)`），故不在此重复。
     */
    val extra: Map<String, String>
        get() = mapOf(
            "min_char_in_sentence" to minCharInSentence.toString(),
            "max_char_in_sentence" to maxCharInSentence.toString(),
            "guidance_scale" to guidanceScale.toString(),
            "feat_scale" to featScale.toString(),
            "t_shift" to tShift.toString(),
            "target_rms" to targetRms.toString()
        )

    /**
     * 应用角色包覆盖（仅 [numSteps]——真机上调 RTF/质量的最有用旋钮，无需重建音色包）。
     *
     * @param overrideNumSteps 角色包 `voice.local.numSteps`（null 或越界时不覆盖）
     */
    fun withRolePackOverride(overrideNumSteps: Int?): LocalCloneConfig =
        if (overrideNumSteps == null || overrideNumSteps !in MIN_STEPS..MAX_STEPS) this
        else copy(numSteps = overrideNumSteps)

    /**
     * 值域钳制（guardrail R1 F-4/C3）：清单来自**设备可写目录**（可被任意改写），
     * 因此每个数值参数都必须先钳到安全域再交给 native——否则 `numThreads=100000`（线程风暴）、
     * `timeoutMs=Long.MAX_VALUE`（看门狗永不触发 → ADR-053 决策 7 的整条降级链失效）、
     * `speed=NaN`、`numSteps` 天文数字都会直达 native。
     *
     * **策略**（对齐 rules/07 §19.4"体验型配置异常优先运行期静默钳制为默认值"）：
     * 越界字段**回落本类默认值**（不是判不可用——用户手改一个小数值不该让整包失效），
     * 由调用方在钳制生效时记一条日志。非法 `maxCharInSentence` 还须与 `minCharInSentence` 自洽
     * （`maxChar = maxOf(DEFAULT_MAX_CHARS, minChar)`），保证 **`min ≤ max` 恒成立**且 [sanitized] 幂等。
     */
    fun sanitized(): LocalCloneConfig {
        val steps = if (numSteps in MIN_STEPS..MAX_STEPS) numSteps else DEFAULT_NUM_STEPS
        val threads = if (numThreads in MIN_THREADS..MAX_THREADS) numThreads else DEFAULT_NUM_THREADS
        val timeout = if (timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) timeoutMs else DEFAULT_TIMEOUT_MS
        val sp = if (speed.isFinite() && speed in MIN_SPEED..MAX_SPEED) speed else DEFAULT_SPEED
        val minChar = if (minCharInSentence in MIN_CHARS..MAX_CHARS) minCharInSentence else DEFAULT_MIN_CHARS
        // maxChar 的回落值必须与 minChar 自洽：清单给 min=800 而 max 越界时，若回落 DEFAULT_MAX_CHARS=200
        // 就会产出 min > max 的非法组合并直达 native `extra`（guardrail R2 N-1）
        val maxChar = if (maxCharInSentence in minChar..MAX_CHARS) maxCharInSentence
        else maxOf(DEFAULT_MAX_CHARS, minChar)
        val silence = if (silenceScale.isFinite() && silenceScale in MIN_SILENCE_SCALE..MAX_SILENCE_SCALE)
            silenceScale else DEFAULT_SILENCE_SCALE
        val guidance = if (guidanceScale.isFinite() && guidanceScale in MIN_GUIDANCE..MAX_GUIDANCE)
            guidanceScale else DEFAULT_GUIDANCE_SCALE
        val feat = if (featScale.isFinite() && featScale in MIN_FEAT..MAX_FEAT) featScale else DEFAULT_FEAT_SCALE
        val shift = if (tShift.isFinite() && tShift in MIN_T_SHIFT..MAX_T_SHIFT) tShift else DEFAULT_T_SHIFT
        val rms = if (targetRms.isFinite() && targetRms in MIN_TARGET_RMS..MAX_TARGET_RMS)
            targetRms else DEFAULT_TARGET_RMS
        // 段间停顿：越界/非法**回落默认 0**（与上面各字段同一"if in range else default"口径，
        // 但语义上刻意**不用** coerceIn —— 钳到边界会让脏数据静默改变听感基线，回落 0 等价"没写该字段"）
        val sentenceGap = if (sentenceGapMs in MIN_SENTENCE_GAP_MS..MAX_SENTENCE_GAP_MS) sentenceGapMs
        else DEFAULT_SENTENCE_GAP_MS
        return copy(
            numSteps = steps,
            speed = sp,
            silenceScale = silence,
            guidanceScale = guidance,
            featScale = feat,
            tShift = shift,
            targetRms = rms,
            minCharInSentence = minChar,
            maxCharInSentence = maxChar,
            numThreads = threads,
            timeoutMs = timeout,
            sentenceGapMs = sentenceGap
        )
    }

    companion object {
        /** numSteps 合法域（distill 官方 4~8；上限放宽到 16 以便真机 A/B，1 步以下无意义）。 */
        const val MIN_STEPS = 1
        const val MAX_STEPS = 16

        /** 推理线程数合法域（官方示例 2/4；上限 4 防线程风暴）。 */
        const val MIN_THREADS = 1
        const val MAX_THREADS = 4

        /** 合成看门狗"无进展"阈值合法域（ms）：下限防"秒级即告警"噪音，上限防阈值过大失去告警意义。 */
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 120_000L

        /** 语速合法域（官方对超短文本建议 0.3 拉长；上限 3.0 防花腔变速）。 */
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 3.0f

        /** 静音缩放合法域（**下限 0.01**：上游 `ScaleSilence` 有 `kMinSilenceScale=0.01` 守卫，
         *  低于它会被**拒绝并跳过**（等同不缩放）——故 0 不是"删掉所有停顿"；本类把下限设成 0.01，
         *  使 0（或任何 <0.01 的值）**落在域外 → 回落默认 1.0**（不缩放），意图显式且不会"静默无效果"。
         *  默认 1.0 = 不缩放）。 */
        const val MIN_SILENCE_SCALE = 0.01f
        const val MAX_SILENCE_SCALE = 2.0f

        /** 条件强度合法域（distill 官方 3.0；上限 10 防过曝）。 */
        const val MIN_GUIDANCE = 0.1f
        const val MAX_GUIDANCE = 10.0f

        /** prompt mel 缩放合法域：**官方唯一正确值 0.1**——作者明确"预训练模型以 0.1 训练，0.5 不可用"、
         *  社区实测 0.2 即"变机械音"（**这两条为二手材料，未在随包二进制/官方文档中逐条核验**）。
         *  故只留窄域（0.05~0.15）供真机确认方向，不提供"改坏自己"的空间。 */
        const val MIN_FEAT = 0.05f
        const val MAX_FEAT = 0.15f

        /** 时间步形变合法域（官方 0.5）。 */
        const val MIN_T_SHIFT = 0.0f
        const val MAX_T_SHIFT = 1.0f

        /** prompt RMS 目标合法域（官方 0.1；必须 > 0，否则 native 报错）。 */
        const val MIN_TARGET_RMS = 0.001f
        const val MAX_TARGET_RMS = 1.0f

        /** 分块字符数合法域。 */
        const val MIN_CHARS = 1
        const val MAX_CHARS = 1_000

        /**
         * 段间停顿合法域（ms）：0 = 不插静音；上限 2000 防"每段之间空两秒以上"这种明显不合理的
         * 清单值把播报拖成灾难（真机试听的预期区间在数百 ms 量级）。
         * 越界**回落 [DEFAULT_SENTENCE_GAP_MS]**，不钳到边界（理由见类 KDoc）。
         */
        const val MIN_SENTENCE_GAP_MS = 0
        const val MAX_SENTENCE_GAP_MS = 2_000

        /** 默认值（**依据见类 KDoc**；除 numSteps/numThreads 外均取 ZipVoice 官方训练/推理值）。 */
        const val DEFAULT_NUM_STEPS = 8
        const val DEFAULT_NUM_THREADS = 4
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val DEFAULT_SPEED = 0.9f
        const val DEFAULT_SILENCE_SCALE = 1.0f
        const val DEFAULT_GUIDANCE_SCALE = 3.0f
        const val DEFAULT_FEAT_SCALE = 0.1f
        const val DEFAULT_T_SHIFT = 0.5f
        const val DEFAULT_TARGET_RMS = 0.1f
        const val DEFAULT_MIN_CHARS = 10
        const val DEFAULT_MAX_CHARS = 200

        /**
         * 段间停顿默认值 = **0（不插静音）**。
         *
         * 刻意不给 300/400 之类的非零默认：0 与改造前逐字节等价，是"未标注音色包"的保真基线；
         * 具体毫秒数须由真机多臂试听定完后写进音色包清单 `generation.sentenceGapMs`。
         */
        const val DEFAULT_SENTENCE_GAP_MS = 0
    }
}
