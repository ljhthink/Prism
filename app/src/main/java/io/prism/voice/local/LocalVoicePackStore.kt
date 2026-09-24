package io.prism.voice.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * LVC-1（ADR-053 决策 4）：本地音色包安装状态（设置页展示 + 引擎装配门控）。
 *
 * 三态**互斥且穷尽**：未安装 / 已安装但不完整（列出缺什么）/ 就绪。
 * 只有 [Ready] 可作为本地引擎的装配来源——不安全地"尽力而为"会让用户在通话里得到静音。
 */
sealed interface LocalVoiceStatus {
    /** 音色包 id（UI 与日志用）。 */
    val voiceId: String

    /** 未安装（任何候选根目录下都没有该 id 的清单文件）。 */
    data class NotInstalled(override val voiceId: String) : LocalVoiceStatus

    /**
     * 已发现清单但不可用（清单畸形／模型文件缺失／参考集为空／路径逃逸）。
     *
     * @property problems 具体问题清单（面向用户的简短描述，**不含绝对路径**）
     */
    data class Incomplete(override val voiceId: String, val problems: List<String>) : LocalVoiceStatus

    /** 就绪（清单 + 全部模型文件 + 非空参考集均已校验通过）。 */
    data class Ready(override val voiceId: String, val pack: LocalVoicePack) : LocalVoiceStatus
}

/**
 * LVC-1（ADR-053）：本地音色包的定位、解析与校验（**只读**；安装由 `voice-clone-kit\` 脚本完成）。
 *
 * **候选根目录（按序取首个命中）**：
 * 1. `<externalFilesDir>/voicepack/` —— 应用专属外部目录（`/sdcard/Android/data/io.prism/files/voicepack/`）：
 *    **adb 可直接推送、无需运行时权限、随卸载清除**，是侧载主通道；
 * 2. `<filesDir>/voicepack/` —— 内部目录（备用：未来经应用内导入落盘时使用）。
 *
 * **校验强度声明**：本类做的是**完整性（presence + 尺寸非零 + 路径不逃逸）**校验，**不是内容一致性**
 * 校验（不比对哈希）——195MB 逐文件哈希在设置页打开时机不可接受。哈希核对由侧载脚本在主机侧完成。
 *
 * **可测性**：[roots] 与 [ioDispatcher] 均为构造注入（JVM 单测用临时目录 + 测试调度器，不依赖 Robolectric）。
 *
 * @param roots 候选根目录（按优先级；不存在/不可读时自动跳过）
 * @param ioDispatcher 文件 IO 调度器（缺省 [Dispatchers.IO]）
 */
class LocalVoicePackStore(
    /**
     * 候选根目录（**外部优先**，顺序即优先级，见 [resolve] 的取根规则）。
     *
     * TKN-LVC-016 起对外可见：设置页的「已安装音色包」列表要按**同一套根与同一优先级**枚举，
     * 若 UI 自己拼一份根列表，就会与引擎实际取到的包不一致（"列表里有、播报说没装"）。
     * 判定"哪个根"的唯一口径因此必须留在本类。
     */
    val roots: List<File>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /** 观察/读取音色包状态（挂起，IO 调度器执行）。 */
    suspend fun status(voiceId: String): LocalVoiceStatus =
        withContext(ioDispatcher) { resolve(voiceId) }

    /**
     * 同步解析音色包状态（核心实现；[status] 的调度器包装）。
     *
     * 任何 IO 异常都被收敛为 [LocalVoiceStatus.NotInstalled] 或
     * [LocalVoiceStatus.Incomplete]——**本方法不抛异常**（设置页可用性优先，对齐 RolePackLoader
     * 的"损坏文件不抛"语义）。
     */
    fun resolve(voiceId: String): LocalVoiceStatus {
        val id = voiceId.trim()
        if (id.isEmpty()) return LocalVoiceStatus.NotInstalled(id)
        // 目录名白名单（防 `..` 逃逸、宿主语义差异与超长串灌入 UI 文案；guardrail F-12）
        if (!VOICE_ID_PATTERN.matches(id)) {
            Log.w(TAG, "非法 voiceId（不在 [A-Za-z0-9_-]{1,64} 值域），拒绝解析")
            return LocalVoiceStatus.NotInstalled(id)
        }
        // 取首个**含清单**的根（不是首个存在的目录）：外部目录残留同名目录时不得遮蔽内部根（F-11）
        val packDir = roots
            .map { File(it, id) }
            .firstOrNull { File(it, MANIFEST_NAME).isFile }
            ?: return LocalVoiceStatus.NotInstalled(id)
        return resolveAtDirectory(packDir = packDir, fallbackId = id)
    }

    /**
     * 对**指定目录**做完整校验（TKN-LVC-016：导入链路复用同一套校验，见 ADR-057 决策 3）。
     *
     * 为什么要复用而不是另写一套：`validate()` 里已经沉淀了三代 guardrail 结论（按引擎分流的必需项、
     * 参考音频上限、`dictDir/ruleFsts` 的存在性与逃逸、总字节统计口径）。导入链路若自建一套，
     * 必然出现"导入时判合格、侧载同一包却判不合格"这类漂移——而两侧的判据本应只有一个。
     *
     * **不抛异常**（同 [resolve]）：IO 异常收敛为 `Incomplete`。
     *
     * @param packDir 待校验的包根目录（任意位置，不必位于 [roots] 下）
     * @param fallbackId 清单缺失/非法时的回退 id（仅用于文案）
     */
    internal fun resolveAtDirectory(packDir: File, fallbackId: String): LocalVoiceStatus {
        val manifest = File(packDir, MANIFEST_NAME)

        // 清单体积上限：清单来自设备可写目录，"数十万条目/超大字符串"会把 readText +
        // JSON 建树变成 OOM 源；MAX_REFERENCES 只在解析后才生效，拦不住（guardrail F-5）
        val manifestBytes = runCatching { manifest.length() }.getOrDefault(0L)
        if (manifestBytes <= 0L) return LocalVoiceStatus.Incomplete(fallbackId, listOf("缺少 $MANIFEST_NAME"))
        if (manifestBytes > MAX_MANIFEST_BYTES) {
            return LocalVoiceStatus.Incomplete(
                fallbackId,
                listOf("清单过大（上限 ${MAX_MANIFEST_BYTES / 1024}KB）")
            )
        }

        val text = try {
            manifest.readText()
        } catch (e: Exception) {
            // 只 catch Exception：不用 Throwable 兜 Error（吞 OutOfMemoryError/StackOverflowError
            // 会让进程在不可信状态下继续运行，且使"清单畸形不崩溃"的承诺变成假成立）
            Log.w(TAG, "清单读取失败（${e::class.simpleName}）")
            return LocalVoiceStatus.Incomplete(fallbackId, listOf("清单文件无法读取"))
        }
        return when (val outcome = parseManifest(voiceId = fallbackId, json = text)) {
            is ManifestOutcome.Bad -> LocalVoiceStatus.Incomplete(fallbackId, outcome.problems)
            is ManifestOutcome.Ok -> validate(packDir = packDir, manifestOutcome = outcome)
        }
    }

    /**
     * 只读清单声明的 `voiceId`（**供导入链路在解压前预检目录名**）。
     *
     * 与 [resolveAtDirectory] 的区别：本方法**不做**文件级校验、不判可用性——它只回答
     * "这个包想叫什么名字"。之所以单独提供：导入必须在**决定落盘目录之前**知道目标名，
     * 而此时包还没落到最终位置。
     *
     * @return 合法（[isValidVoiceId]）的 voiceId；缺失/非法/读不出来一律 null（调用方据此判失败）
     */
    fun declaredVoiceId(packDir: File): String? {
        val manifest = File(packDir, MANIFEST_NAME)
        val bytes = runCatching { manifest.length() }.getOrDefault(0L)
        if (bytes <= 0L || bytes > MAX_MANIFEST_BYTES) return null
        val text = runCatching { manifest.readText() }.getOrNull() ?: return null
        return declaredVoiceIdFromJson(text)
    }

    /**
     * 从清单**文本**里取合法 `voiceId`（[declaredVoiceId] 的纯函数内核）。
     *
     * 单独暴露的理由：导入器从 zip 里流式取出 `voicepack.json` 的字节时，包里**还没有目录**，
     * 无法走 [declaredVoiceId]；而"什么算合法 voiceId"必须与 [resolve] 逐字一致（F-12）。
     */
    fun declaredVoiceIdFromJson(json: String): String? {
        val body = if (json.isNotEmpty() && json[0] == '\uFEFF') json.substring(1) else json
        val root = try {
            jsonFormat.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            return null // 畸形 JSON 属业务失败（OOM/SOE 会作为 Error 上抛，不被吞）
        } ?: return null
        return root.optString("voiceId")?.takeIf { isValidVoiceId(it) }
    }

    /** 清单解析通过后的文件级校验（存在性 + 尺寸 + 参考集）。 */
    private fun validate(packDir: File, manifestOutcome: ManifestOutcome.Ok): LocalVoiceStatus {
        val m = manifestOutcome.manifest
        val problems = mutableListOf<String>()
        // 清单 voiceId 与目录名必须一致：不一致说明用户改了目录名或混放了包，
        // 若容忍则"角色包声明 A、实际加载 B 的音色"——静默换音色比报错更难排查。
        if (m.id != packDir.name) {
            problems += "清单 voiceId（${m.id}）与目录名不一致"
        }

        fun fileOf(rel: String, label: String): File? {
            val f = resolveInside(packDir, rel)
            if (f == null) {
                problems += "$label 路径不合法（禁止绝对路径与 .. 逃逸）"
                return null
            }
            if (!f.isFile) {
                problems += "$label 缺失"
                return null
            }
            if (f.length() <= 0L) {
                problems += "$label 为空文件"
                return null
            }
            return f
        }

        // ---- 按引擎校验各自真正需要的资产（ADR-055 决策 3）----
        val lexiconFile = fileOf(m.lexicon, "拼音词典 lexicon")
        val modelFiles: LocalModelFiles? = if (m.tts == LocalTtsKind.ZIPVOICE) {
            val tokens = fileOf(m.tokens, "词表 tokens")
            val encoder = fileOf(m.encoder, "编码器 encoder")
            val decoder = fileOf(m.decoder, "解码器 decoder")
            val vocoder = fileOf(m.vocoder, "声码器 vocoder")
            val dataDir = resolveInside(packDir, m.dataDir)
            if (dataDir == null) {
                problems += "音素数据目录路径不合法"
            } else if (!dataDir.isDirectory) {
                problems += "音素数据目录 espeak-ng-data 缺失"
            } else if (dataDir.listFiles()?.isEmpty() != false) {
                problems += "音素数据目录 espeak-ng-data 为空"
            }
            if (tokens != null && encoder != null && decoder != null && vocoder != null &&
                lexiconFile != null && dataDir != null
            ) {
                LocalModelFiles.ZipVoice(tokens, encoder, decoder, vocoder, lexiconFile, dataDir)
            } else {
                null
            }
        } else {
            val vitsOnnx = fileOf(m.vitsModel, "VITS 模型 model.onnx")
            val vitsTokens = fileOf(m.vitsTokens, "词表 tokens")
            // dictDir / ruleFsts 是**可选**资产：**未声明**时缺失只降级功能（分词质量、数字/多音字读法），
            // 不阻断安装——否则用户少拷一个 dict/ 就整包不可用，而音色本身完全可用。
            // **但"声明了却找不到"仍判不可用**（fail-fast）：清单说要用它、文件却不在，
            // 运行期只会静默丢规则（数字/多音字读错），比安装期报错难查得多（guardrail N3）。
            val dictDeclared = m.vitsDictDir.takeIf { it.isNotBlank() }
            var dictDir: File? = null
            if (dictDeclared != null) {
                dictDir = resolveInside(packDir, dictDeclared)
                // 与 ruleFsts 同口径（guardrail TKN-LVC-015 A1）：**声明了**却解析不出来（绝对路径/`..` 逃逸）
                // 必须记 problem，而不是静默当成"未声明"而放行——否则"清单要求用它、路径却非法"无任何痕迹。
                if (dictDir == null) {
                    problems += "结巴词典目录路径不合法（禁止绝对路径与 .. 逃逸）"
                } else if (!dictDir.isDirectory) {
                    problems += "结巴词典目录 dictDir 缺失"
                }
            }
            // ruleFsts 上限 + 去重（guardrail N2）：该数组由清单（设备可写）提供，
            // 无上限时 256KB 清单可塞入约 1.2 万条（还能重复指向同一真实文件从而绕过"文件存在"检查），
            // 构造 OfflineTts 时会把它们全量拼进 `ruleFsts` 交给 native。同文件其它数组（参考音频）已有上限。
            val ruleFstRels = m.vitsRuleFsts.distinct()
            if (ruleFstRels.size > MAX_RULE_FSTS) {
                problems += "文本规则 FST 条目过多（${ruleFstRels.size} > $MAX_RULE_FSTS）"
            }
            val ruleFsts = ruleFstRels.take(MAX_RULE_FSTS).mapNotNull { rel ->
                fileOf(rel, "文本规则 FST $rel")
            }
            if (vitsOnnx != null && vitsTokens != null && lexiconFile != null) {
                LocalModelFiles.Vits(
                    model = vitsOnnx,
                    lexicon = lexiconFile,
                    tokens = vitsTokens,
                    dictDir = dictDir?.takeIf { it.isDirectory },
                    ruleFsts = ruleFsts
                )
            } else {
                null
            }
        }

        val refs = mutableListOf<LocalReference>()
        if (m.references.size > MAX_REFERENCES) {
            problems += "参考音频条目过多（${m.references.size} > $MAX_REFERENCES）"
        }
        for (r in m.references.take(MAX_REFERENCES)) {
            val f = resolveInside(packDir, r.audio)
            if (f == null || !f.isFile || f.length() <= 0L) {
                problems += "参考音频缺失或为空（${r.id}）"
                continue
            }
            // 上限守卫：参考音频会被一次性读入内存（WaveReader → FloatArray），清单来自设备可写目录
            // （可被任意改写）——无上界时单条超大 wav 即可造成 OOM。
            if (f.length() > MAX_REFERENCE_BYTES) {
                problems += "参考音频过大（${r.id}，上限 ${MAX_REFERENCE_BYTES / 1024 / 1024}MB）"
                continue
            }
            refs += LocalReference(id = r.id, audio = f, text = r.text, emotion = r.emotion)
        }
        // 参考音频：ZipVoice 必需（音色与韵律都来自 prompt）；**VITS 允许为空**（音色在权重里）
        if (m.tts == LocalTtsKind.ZIPVOICE && refs.isEmpty()) problems += "参考音频集为空"

        if (problems.isNotEmpty()) {
            Log.w(TAG, "本地音色包不可用：voiceId=${m.id}，tts=${m.tts.wire}，问题 ${problems.size} 项")
            return LocalVoiceStatus.Incomplete(
                voiceId = m.id,
                // 最多展示 4 条（设置页单行；其余以计数概括）
                problems = if (problems.size <= 4) problems else problems.take(4) + "等 ${problems.size} 项"
            )
        }

        val model = modelFiles
            ?: return LocalVoiceStatus.Incomplete(m.id, listOf("模型资产不完整（tts=${m.tts.wire}）"))
        // 先建包再用包自己的 assetFiles/assetDirs 统计体积：**分母只有一处定义**
        // （少算一个文件会出现"换了模型没效果"；两处各写一份必然漂移）
        val pack = LocalVoicePack(
            voiceId = m.id,
            name = m.name,
            root = packDir,
            tts = m.tts,
            model = model,
            generation = m.generation,
            references = refs,
            totalBytes = 0L,
            vitsGeneration = m.vitsGeneration
        ).let { p ->
            p.copy(
                totalBytes = p.assetFiles.sumOf { it.length() } +
                    p.assetDirs.sumOf { dirSize(it) } +
                    refs.sumOf { it.audio.length() }
            )
        }
        return LocalVoiceStatus.Ready(voiceId = m.id, pack = pack)
    }

    // ==================== 清单解析（纯函数；宽松策略对齐 RolePackParser） ====================

    private sealed interface ManifestOutcome {
        data class Ok(val manifest: Manifest) : ManifestOutcome
        data class Bad(val problems: List<String>) : ManifestOutcome
    }

    private data class Manifest(
        val id: String,
        val name: String,
        val tts: LocalTtsKind,
        /** ZipVoice 六项（ADR-053）。VITS 包留空。 */
        val tokens: String,
        val encoder: String,
        val decoder: String,
        val vocoder: String,
        val lexicon: String,
        val dataDir: String,
        /** VITS 资产（ADR-055）。ZipVoice 包留空。 */
        val vitsModel: String,
        val vitsTokens: String,
        val vitsDictDir: String,
        val vitsRuleFsts: List<String>,
        val generation: LocalCloneConfig,
        /** VITS 专属旋钮（TKN-LVC-016）；ZipVoice 包取默认值（不会被读取）。 */
        val vitsGeneration: VitsGeneration,
        val references: List<ManifestRef>
    )

    private data class ManifestRef(val id: String, val audio: String, val text: String, val emotion: String?)

    /**
     * 解析 `voicepack.json`。
     *
     * **宽松策略**（对齐 [io.prism.voice.rolepack.RolePackParser]）：未知字段忽略、标量类型宽松
     * （数字写成字符串可解析）、缺省兜底；**但必需项缺失/ schema 不符 → 判不可用**（fail-fast，
     * 不得用默认模型路径"猜"出一个可用包）。
     */
    private fun parseManifest(voiceId: String, json: String): ManifestOutcome {
        // BOM 容错：手工编辑的清单可能带 UTF-8 BOM，kotlinx-serialization 会把 BOM 当非法字符
        val body = if (json.isNotEmpty() && json[0] == '\uFEFF') json.substring(1) else json
        val rootEl = try {
            jsonFormat.parseToJsonElement(body)
        } catch (e: Exception) {
            // 只 catch Exception（不吃 Error）：畸形 JSON 属业务失败，OOM/SOE 必须上抛由进程处置
            return ManifestOutcome.Bad(listOf("清单不是合法 JSON（${e::class.simpleName}）"))
        }
        val root = rootEl as? JsonObject ?: return ManifestOutcome.Bad(listOf("清单顶层必须是 JSON 对象"))
        val schema = root.optString("schema")
        if (schema != SCHEMA) {
            return ManifestOutcome.Bad(listOf("清单 schema 不支持（期望 $SCHEMA）"))
        }
        val model = root.optObj("model")
            ?: return ManifestOutcome.Bad(listOf("清单缺少 model 段"))
        val problems = mutableListOf<String>()
        fun required(rel: String?, label: String): String {
            if (rel.isNullOrBlank()) problems += "清单缺少 $label 路径"
            return rel.orEmpty()
        }

        // 引擎分流（ADR-055）：`tts` 缺省 = zipvoice（旧包全兼容）；**无法识别的取值判不可用**
        // ——静默回落到 ZipVoice 会让"VITS 包被当 ZipVoice 加载"这类**静默换引擎**极难排查。
        val ttsRaw = root.optString("tts")
        val tts = LocalTtsKind.parse(ttsRaw)
        if (tts == null) {
            return ManifestOutcome.Bad(listOf("清单 tts 取值无法识别（${ttsRaw?.take(16)}）"))
        }

        val refs = mutableListOf<ManifestRef>()
        (root.opt("references") as? JsonArray)?.forEachIndexed { idx, el ->
            val obj = el as? JsonObject ?: return@forEachIndexed
            val audio = obj.optString("audio").orEmpty()
            val text = obj.optString("text").orEmpty()
            if (audio.isBlank() || text.isBlank()) {
                problems += "第 ${idx + 1} 条参考缺少 audio/text"
                return@forEachIndexed
            }
            refs += ManifestRef(
                id = obj.optString("id")?.takeIf { it.isNotBlank() } ?: "ref_${idx.toString().padStart(2, '0')}",
                audio = audio,
                text = text,
                emotion = obj.optString("emotion")
            )
        }

        val gen = root.optObj("generation")
        val rawGeneration = LocalCloneConfig(
            numSteps = gen?.optInt("numSteps") ?: LocalCloneConfig.DEFAULT_NUM_STEPS,
            speed = gen?.optFloat("speed") ?: LocalCloneConfig.DEFAULT_SPEED,
            // TKN-LVC-003 音质/韵律调优新增（缺省即官方值：silence 1.0 = 不压缩停顿；guidance 3.0 = distill 官方默认）
            silenceScale = gen?.optFloat("silenceScale") ?: LocalCloneConfig.DEFAULT_SILENCE_SCALE,
            guidanceScale = gen?.optFloat("guidanceScale") ?: LocalCloneConfig.DEFAULT_GUIDANCE_SCALE,
            featScale = gen?.optFloat("featScale") ?: LocalCloneConfig.DEFAULT_FEAT_SCALE,
            tShift = gen?.optFloat("tShift") ?: LocalCloneConfig.DEFAULT_T_SHIFT,
            targetRms = gen?.optFloat("targetRms") ?: LocalCloneConfig.DEFAULT_TARGET_RMS,
            minCharInSentence = gen?.optInt("minCharInSentence") ?: LocalCloneConfig.DEFAULT_MIN_CHARS,
            maxCharInSentence = gen?.optInt("maxCharInSentence") ?: LocalCloneConfig.DEFAULT_MAX_CHARS,
            numThreads = gen?.optInt("numThreads") ?: LocalCloneConfig.DEFAULT_NUM_THREADS,
            timeoutMs = gen?.optLong("timeoutMs") ?: LocalCloneConfig.DEFAULT_TIMEOUT_MS,
            // 段间停顿（播放层参数，ZipVoice/VITS 同源生效）：缺省 = 0 = 不插静音（与改造前等价）；
            // 越界由 `sanitized()` **回落默认 0**（不是钳到边界——见 LocalCloneConfig KDoc）
            sentenceGapMs = gen?.optInt("sentenceGapMs") ?: LocalCloneConfig.DEFAULT_SENTENCE_GAP_MS
        )
        // 值域钳制（guardrail F-4/C3）：清单可被任意改写，数值必须先钳再进 native
        val generation = rawGeneration.sanitized()
        if (generation != rawGeneration) {
            Log.w(TAG, "清单 generation 存在越界字段，已钳制为默认值（不判不可用）")
        }
        // VITS 专属旋钮（TKN-LVC-016）：与 generation 同口径——缺省即官方值、越界钳制。
        // 刻意**不**解析 lengthScale：speed != 1 时它会被上游 `1/speed` 覆盖（见 VitsGeneration KDoc）。
        val rawVits = VitsGeneration(
            noiseScale = gen?.optFloat("noiseScale") ?: VitsGeneration.DEFAULT_NOISE_SCALE,
            noiseScaleW = gen?.optFloat("noiseScaleW") ?: VitsGeneration.DEFAULT_NOISE_SCALE_W
        )
        val vitsGeneration = rawVits.sanitized()
        if (vitsGeneration != rawVits) {
            Log.w(TAG, "清单 VITS 旋钮存在越界字段，已钳制为默认值（不判不可用）")
        }
        val manifest = Manifest(
            id = manifestVoiceId(root, voiceId, problems),
            name = root.optString("name")?.takeIf { it.isNotBlank() }?.take(MAX_NAME_CHARS) ?: voiceId,
            tts = tts,
            // 按引擎只要求**该引擎真正需要**的路径（ADR-055 决策 3）：VITS 包没有 encoder/decoder，
            // 若仍按 ZipVoice 的必需项校验，任何合法 VITS 包都会被判"清单缺少 encoder 路径"。
            tokens = if (tts == LocalTtsKind.ZIPVOICE) required(model.optString("tokens"), "tokens") else "",
            encoder = if (tts == LocalTtsKind.ZIPVOICE) required(model.optString("encoder"), "encoder") else "",
            decoder = if (tts == LocalTtsKind.ZIPVOICE) required(model.optString("decoder"), "decoder") else "",
            vocoder = if (tts == LocalTtsKind.ZIPVOICE) required(model.optString("vocoder"), "vocoder") else "",
            // `lexicon` 两种引擎都要（同名字段）
            lexicon = required(model.optString("lexicon"), "lexicon"),
            dataDir = if (tts == LocalTtsKind.ZIPVOICE) required(model.optString("dataDir"), "dataDir") else "",
            vitsModel = if (tts == LocalTtsKind.VITS) required(model.optString("model"), "model") else "",
            vitsTokens = if (tts == LocalTtsKind.VITS) required(model.optString("tokens"), "tokens") else "",
            vitsDictDir = model.optString("dictDir").orEmpty(),
            vitsRuleFsts = (model.opt("ruleFsts") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
                ?: emptyList(),
            generation = generation,
            vitsGeneration = vitsGeneration,
            references = refs
        )
        return if (problems.isEmpty()) ManifestOutcome.Ok(manifest)
        else ManifestOutcome.Bad(problems)
    }

    /**
     * 取清单声明的 voiceId，并做**上界与字符集**校验（guardrail TKN-LVC-015 N5）。
     *
     * 为什么必须校验：该值直接进日志与设置页展示，而清单来自**设备可写目录**（可被任意改写）——
     * 无上界时一条 100KB 的 `voiceId` 就能污染日志/界面。字符集与"请求侧"用同一个
     * [VOICE_ID_PATTERN]（它本来就是目录名约束），使"清单声明"与"目录名"的口径一致。
     */
    private fun manifestVoiceId(root: JsonObject, fallback: String, problems: MutableList<String>): String {
        val raw = root.optString("voiceId")?.takeIf { it.isNotBlank() } ?: return fallback
        if (raw.length > MAX_VOICE_ID_CHARS || !VOICE_ID_PATTERN.matches(raw)) {
            problems += "清单 voiceId 不合法（需 ${VOICE_ID_PATTERN.pattern}）"
            return fallback
        }
        return raw
    }

    // ==================== 路径与尺寸工具 ====================

    /**
     * 把清单内的相对路径解析到 [root] 之内；**逃逸即拒绝**。
     *
     * 拒绝条件：空白、绝对路径（`/` 起始或 Windows 盘符）、任一 `..` 段、解析后不在 root 之下。
     * 依据：清单来自用户侧载目录（可被任意改写），`../` 逃逸可让引擎去加载设备上任意文件。
     */
    private fun resolveInside(root: File, rel: String): File? {
        val r = rel.trim()
        if (r.isEmpty()) return null
        if (r.startsWith("/") || r.startsWith("\\") || DRIVE_PREFIX.containsMatchIn(r)) return null
        if (r.split('/', '\\').any { it == ".." }) return null
        val f = File(root, r)
        return try {
            val rootPath = root.canonicalFile.path + File.separator
            if (f.canonicalFile.path.startsWith(rootPath)) f else null
        } catch (e: Exception) {
            null
        }
    }

    /** `dataDir` 递归尺寸缓存（键 = canonical path；值 = 字节数）。热路径（每次播报点击）只走一次真实遍历。 */
    @Volatile
    private var dataDirSizeCache: Pair<String, Long>? = null

    /** 递归统计目录字节数（IO 失败按 0 计——仅用于展示，不影响可用性判定）。 */
    private fun dirSize(dir: File): Long {
        val key = try {
            dir.canonicalPath
        } catch (e: Exception) {
            dir.absolutePath
        }
        dataDirSizeCache?.let { if (it.first == key) return it.second }
        val size = try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            0L
        }
        dataDirSizeCache = key to size
        return size
    }

    // ==================== 宽松标量提取（对齐 RolePackParser 私有工具语义） ====================

    private fun JsonObject.optString(key: String): String? = (opt(key) as? JsonPrimitive)?.content

    private fun JsonObject.optInt(key: String): Int? =
        optString(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    private fun JsonObject.optLong(key: String): Long? =
        optString(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

    private fun JsonObject.optFloat(key: String): Float? = optString(key)?.toFloatOrNull()

    private fun JsonObject.optObj(key: String): JsonObject? = opt(key) as? JsonObject

    private fun JsonObject.opt(key: String): Any? {
        val v = this[key] ?: return null
        return if (v is JsonNull) null else v
    }

    companion object {
        private const val TAG = "LocalVoicePack"

        /** 清单 schema 标记（与 `voice-clone-kit\build-local-voicepack.ps1` 产出一致）。 */
        const val SCHEMA = "prism-localvoice/1"

        /** 清单文件名。 */
        const val MANIFEST_NAME = "voicepack.json"

        /** 参考音频条目上限（防畸形/恶意清单造成长循环；正常音色包为个位数）。 */
        const val MAX_REFERENCES = 64
        /** 单条参考音频字节上限（一次性读入内存；8MB ≈ 24kHz 单声道 16bit 约 170 秒）。 */
        const val MAX_REFERENCE_BYTES = 8L * 1024 * 1024

        /**
         * VITS 文本规则 FST 条目上限（guardrail TKN-LVC-015 N2）。
         *
         * 依据：官方/我们导出的规则集是 **4 条**（`date/number/phone/new_heteronym`），留一倍余量到 8；
         * 无上限时 256KB 清单（[MAX_MANIFEST_BYTES]）可塞入约 1.2 万条，且可重复指向同一真实文件
         * 从而绕过"文件是否存在"的检查，最终被 `joinToString` 全量拼进 native 配置。
         */
        const val MAX_RULE_FSTS = 8

        /** 清单 voiceId 字符数上限（与 [VOICE_ID_PATTERN] 的量词一致；该值会进日志与设置页）。 */
        const val MAX_VOICE_ID_CHARS = 64

        /** 清单 name 字符数上限（同上：会进设置页与日志）。 */
        const val MAX_NAME_CHARS = 64

        /** 盘符前缀（拒绝对路径；Windows 风格 `C:\...`）。 */
        private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

        /** `voiceId` 白名单（目录名安全 + 防宿主语义差异 + 防超长串灌入 UI 文案）。 */
        private val VOICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        /**
         * `voiceId` 是否合法（[VOICE_ID_PATTERN] 的**唯一对外口径**）。
         *
         * 导入链路（ADR-057）必须在"用清单里的 voiceId 拼落盘目录名"之前判定它，
         * 而该判定必须与校验链路**同一个正则**——两处各写一份必然漂移（F-12 的教训）。
         */
        fun isValidVoiceId(raw: String?): Boolean = raw != null && VOICE_ID_PATTERN.matches(raw)

        /** 清单文件体积上限（256KB；正常清单约 2–4KB，留两个数量级裕量）。 */
        const val MAX_MANIFEST_BYTES = 256L * 1024

        /** 候选根目录目录名。 */
        const val DIR_NAME = "voicepack"

        private val jsonFormat = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 生产候选根目录（外部优先——侧载主通道）。
         *
         * @param context 任意上下文（内部只取目录，不持有引用）
         */
        fun defaultRoots(context: Context): List<File> {
            val app = context.applicationContext
            val list = mutableListOf<File>()
            runCatching { app.getExternalFilesDir(null) }.getOrNull()?.let { list += File(it, DIR_NAME) }
            list += File(app.filesDir, DIR_NAME)
            return list
        }
    }
}
