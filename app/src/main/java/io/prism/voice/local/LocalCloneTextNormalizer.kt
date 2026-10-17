package io.prism.voice.local

/**
 * LVC-1（ADR-053 决策 6）：ZipVoice 文本规范化的**唯一出口**（纯函数，无 IO，可 JVM 单测）。
 *
 * **为什么需要**：ZipVoice 是参考音频驱动的零样本克隆，模型经 `lexicon.txt`（汉字→拼音）+
 * `espeak-ng-data`（音素化）把文本转成音素；**词表外的字符会被丢弃**（官方 Notes：未收录字符会掉字）。
 * 角色台词里常见的 `♪`、`「」`、`【】`、`…`、`——` 都不承载音素。
 *
 * **做什么**（三条规则，逐条可测）：
 * 1. **删除无音素符号**：`♪ ♫ 「 」 『 』 【 】`；
 * 2. **停顿时长符号归一为逗号**：`… —— ～ ~` → `，`（保留停顿意图，改用词表内字符）；
 * 3. **压缩连续逗号与空白**，并 trim。
 *
 * **不做什么**（有意为之，避免过度加工）：
 * - **不做全角→半角标点转换**：官方示例中半角 `, .` 与全角 `，` 混用均可正常工作（标点仅用于分句），
 *   强制转换收益为零、却引入新的失真面；
 * - **不改写数字/字母**：阿拉伯数字与拉丁字母均在词表内；
 * - **不做多音字替换**：官方 `<chang2>` 语法在 sherpa-onnx C++ 端是否支持**无证据**（调研已登记），
 *   因此不做基于猜测的改写。
 *
 * **单点应用**：参考音频的 `text` 与待合成文本**都在本函数规范化的结果上送模型**——两侧规则不一致
 * 会导致"音频与文本不逐字一致"，而官方明确该情形下音质明显退化。
 */
object LocalCloneTextNormalizer {

    /** 无音素符号（直接删除）。 */
    private val DROPPED = charArrayOf(
        '\u266A', // ♪
        '\u266B', // ♫
        '「', '」', '『', '』', '【', '】'
    )

    /** 停顿类符号（归一为逗号，保留停顿意图并使用词表内字符）。 */
    private val PAUSE_TO_COMMA = charArrayOf(
        '\u2026', // …
        '\u2014', // ——（两个 U+2014；连续删除后由规则 3 压缩）
        '\uFF5E', // ～
        '~'
    )

    /** 连续逗号压缩（含全角/半角混排）。 */
    private val CONSECUTIVE_COMMA = Regex("[,，、]{2,}")

    /** CJK 标点两侧空白剥离（"等等， 好吗" → "等等，好吗"；不触碰半角逗号，避免改动英文文本）。 */
    private val SPACE_AROUND_CJK_PUNCT = Regex("\\s*([，。！？；：、])\\s*")

    /** 连续空白压缩（含全角空格）。 */
    private val CONSECUTIVE_SPACE = Regex("[\\s\\u3000]+")

    /**
     * 规范化文本。
     *
     * @param raw 原始文本（可为空串/null 安全由调用方保证——本项目 Kotlin 非空类型）
     * @return 规范化结果；输入全为无音素符号/空白时返回**空串**（调用方据此判失败，
     *   不得把空串送进模型——实测会产出零长度或异常音频）
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                DROPPED.contains(ch) -> Unit
                PAUSE_TO_COMMA.contains(ch) -> sb.append('，')
                else -> sb.append(ch)
            }
        }
        var out = sb.toString()
        out = CONSECUTIVE_COMMA.replace(out, "，")
        out = SPACE_AROUND_CJK_PUNCT.replace(out) { m -> m.groupValues[1] }
        out = CONSECUTIVE_SPACE.replace(out, " ")
        return out.trim().trim('，').trim()
    }
}
