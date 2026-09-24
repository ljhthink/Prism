package io.prism.voice.local

import io.prism.voice.LocalClonePlaybackMode
import java.io.File

/**
 * 本地音色引擎的**缓存键**（纯函数，guardrail TKN-LVC-003 F-3 / N-1 / N-2 的收口）。
 *
 * ## 为什么必须是纯函数
 *
 * `LocalCloneTtsEngine` 在**构造时**把 [LocalCloneConfig] 定死（模型也在构造时加载），
 * 因此"改参数/换模型后是否要重建引擎"完全取决于这个键。历史上它只含
 * `voiceId|numSteps|refCount` → 真机改 `voicepack.json` 后同一进程内仍走旧参数，
 * 表现为"改了没效果"，会直接污染音质 A/B（F-3）。抽成纯函数是为了让它**可被单测逐字段钉住**
 * （原来的内联字符串拼接没有任何测试，复发成本为零）。
 *
 * ## 编码约定（N-1：消除分隔符碰撞）
 *
 * 每段写成 `tag=值长度:值;`。**长度前缀**使"值本身含分隔符"也无法构造别名碰撞
 * （例如 `voiceId` 里带 `|` 时，朴素的 `a|b` 拼接会让两组不同输入产出同一字符串）。
 *
 * @param voiceId 音色 id
 * @param tts 引擎类型（ADR-055 新增）——**必须参与**：同一 voiceId 由 ZipVoice 换成 VITS 时，
 *   若不进键，同一进程内会继续复用旧引擎 → 又一次"换了没效果"（与 F-3 同源）
 * @param referenceCount 参考音频条数（参考集变了要重建）
 * @param config 生效生成参数（**全部字段**都参与——少一个就会出现"改了没效果"）。
 *   其中 [LocalCloneConfig.sentenceGapMs]（段间停顿）虽只在**播放层**生效，却是在流水线构造期
 *   定死的输入，故同样必须进键（与 F-3 同型：漏掉它会让"改了 gap 没效果"）
 * @param vits VITS 专属旋钮（TKN-LVC-016）——同样必须参与：只改 `noiseScale` 而键不变时，
 *   同一进程内会继续复用旧引擎，属同一类"改了没效果"（与 F-3 同源）
 * @param playbackMode 播报管线模式（TKN-LVC-016）——**必须参与**：它是在**构造时**注入引擎的
 *   （`LocalCloneTtsEngine(playbackMode = ...)`），键若不含它，用户在设置页改"连续/整段"后
 *   文本播报链会**继续复用旧引擎** ⇒ 开关看起来生效实则无效（guardrail TKN-LVC-016 B2 实测）。
 *   注：通话链每次装配都新建引擎，不受此影响。
 * @param modelIdentity 模型资产身份（[localCloneModelIdentity]；覆盖"原地替换模型文件"场景）
 */
internal fun localCloneCacheKey(
    voiceId: String,
    tts: LocalTtsKind,
    referenceCount: Int,
    config: LocalCloneConfig,
    vits: VitsGeneration,
    playbackMode: LocalClonePlaybackMode,
    modelIdentity: String
): String = buildString {
    appendField("voiceId", voiceId)
    appendField("tts", tts.wire)
    appendField("refs", referenceCount.toString())
    appendField("model", modelIdentity)
    appendField("playbackMode", playbackMode.storeValue)
    appendField("numSteps", config.numSteps.toString())
    appendField("speed", config.speed.toString())
    appendField("silenceScale", config.silenceScale.toString())
    appendField("guidanceScale", config.guidanceScale.toString())
    appendField("featScale", config.featScale.toString())
    appendField("tShift", config.tShift.toString())
    appendField("targetRms", config.targetRms.toString())
    appendField("minChar", config.minCharInSentence.toString())
    appendField("maxChar", config.maxCharInSentence.toString())
    appendField("threads", config.numThreads.toString())
    appendField("timeoutMs", config.timeoutMs.toString())
    // 段间停顿（播放层参数）也必须参与：它在流水线/引擎构造期定死，漏进键就会"改了没效果"（F-3 同型）
    appendField("sentenceGapMs", config.sentenceGapMs.toString())
    appendField("vitsNoiseScale", vits.noiseScale.toString())
    appendField("vitsNoiseScaleW", vits.noiseScaleW.toString())
}

/**
 * 模型资产身份：`文件名:字节数:最后修改时间`（按传入顺序）。
 *
 * 为什么需要它（N-2）：A/B 换模型（如 int8 → fp32）时**文件名是规范化的**（都是 `encoder.onnx`），
 * 若键里不含内容/时间特征，同一进程内会继续复用为旧模型构建的引擎 → 再次表现为"换了没效果"。
 * 用"字节数 + mtime"作身份：换模型（体积或时间必变）会触发重建，且零 IO 成本。
 * （不宣称"必然"——同路径替换 + 同字节数 + 同 mtime 粒度三者同真时会漏判，概率极低，
 * 与"不为 195MB 资产做内容哈希"同一取舍；见 guardrail TKN-LVC-003 R3 N-8）
 */
internal fun localCloneModelIdentity(files: List<File>): String =
    files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }

/** 追加一段带长度前缀的字段（见 [localCloneCacheKey] 的编码约定）。 */
private fun StringBuilder.appendField(tag: String, value: String) {
    append(tag).append('=').append(value.length).append(':').append(value).append(';')
}
