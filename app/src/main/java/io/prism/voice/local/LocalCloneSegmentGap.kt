package io.prism.voice.local

import kotlin.math.roundToInt

/**
 * 段间停顿的纯函数（可测）：边界类型判定 + 毫秒 → 采样数换算。
 *
 * 背景见 [LocalCloneConfig.sentenceGapMs]：真机"本地音色语速过快"的实测根因是**停顿被压缩**
 * （有声帧占比 0.849 vs 语料 0.736；平均停顿 0.232s vs 0.538s），而端侧把长文本切段后
 * **连续播放、段间不插静音**又进一步压缩了停顿。本对象只做"算什么停顿 / 多少样本"，
 * 插入点与顺序由 [LocalCloneTtsEngine] 负责（播放层，ZipVoice/VITS 同源生效）。
 *
 * ## 为什么必须按边界类型分档（不能"每个段边界都插满"）
 *
 * [segmentForSynthesis] 的切分规则是：优先在**句末标点**后收束；句内过长时退而在**子句标点**
 * （逗号/分号/冒号）后切；两者都不可用时**硬切**（可能切在词中间）。
 * ⇒ **段边界 ≠ 句边界**：在逗号处插满基准停顿会听成明显顿挫，在硬切处插满基准停顿
 * 则是**词中间凭空出现停顿**，比改动前更糟。故 [gapMsFor] 只在真句末给满额，
 * 子句边界按比例给，硬切边界**一律 0**。
 *
 * **越界/非法一律 0（不插）**，不 clamp 到边界——与 [LocalCloneConfig.sanitized] 同一约定：
 * 脏数据不得静默改变听感基线。
 */
internal object LocalCloneSegmentGap {

    /** 子句边界（逗号/分号/冒号）的停顿折扣系数：不给满额，避免"逗号处顿挫"。 */
    private const val CLAUSE_GAP_RATIO = 0.4f

    /**
     * 静音样本数**硬上界**（= 2000 ms × 96 kHz 的两倍余量 = 192_000 样本）。
     *
     * 为什么要它（独立审查 L-1）：[silenceSamples] 原先只把结果截到 `Int.MAX_VALUE`，
     * 而 [silence] 会**据此真的分配** `FloatArray` ⇒ 万一 `sampleRate()` 回报异常大值
     * （native 出错时的返回值不在本层可控范围），单次分配可达 **数 GiB → OOM**
     * （`FloatArray(Int.MAX_VALUE)` ≈ 8 GiB）。
     * 当前唯一生产调用方传的是引擎回报的采样率（正常 ≤96 kHz ⇒ ≤384 KB），
     * **不可从音色包清单或用户输入触达**，故这是纵深防御而非可达缺陷。
     * 超限一律**返回 0（不插静音）**而不是抛异常：段间停顿是体验增强，
     * 绝不能让它把整轮播报打崩（"永不抛异常"见引擎契约）。
     */
    private const val MAX_SILENCE_SAMPLES = 192_000

    /**
     * 按**上一段的结尾字符**判定该边界该插多少静音。
     *
     * 判定前 `trimEnd()` 取最后一个**非空白**字符（清单/切分可能留下尾随空白）。
     * 字符集直接复用 [isSentenceBoundary]/[isClauseBoundary]——与切分规则**同源**，
     * 避免"切分按 A 判、插停顿按 B 判"的静默漂移。
     *
     * @param previousSegment 上一个段的文本（调用方保证非空；空/全空白 ⇒ 0）
     * @param baseGapMs 清单里的基准段间停顿（`<= 0` ⇒ 恒返回 0，即"默认不改变行为"）
     * @return 句末标点（`。！？!?.`）⇒ [baseGapMs]；子句标点（`，；：,;:`）⇒
     *   `max(1, round(baseGapMs × 0.4))` 且**不超过** [baseGapMs]；其它（硬切/无标点）⇒ 0
     */
    fun gapMsFor(previousSegment: String, baseGapMs: Int): Int {
        if (baseGapMs <= 0) return 0
        val trimmed = previousSegment.trimEnd()
        if (trimmed.isEmpty()) return 0
        val last = trimmed[trimmed.length - 1]
        return when {
            isSentenceBoundary(last) -> baseGapMs
            // 子句档：max(1, round(0.4×base)) 且封顶 base——base 很小时（如 1）不得算出比满额更大的值
            isClauseBoundary(last) -> (baseGapMs * CLAUSE_GAP_RATIO).roundToInt().coerceIn(1, baseGapMs)
            // 硬切/无标点：**不插**（词中间停顿比原来更糟）
            else -> 0
        }
    }

    /**
     * 换算静音样本数。
     *
     * @param gapMs 段间停顿毫秒（合法域 [LocalCloneConfig.MIN_SENTENCE_GAP_MS]..[LocalCloneConfig.MAX_SENTENCE_GAP_MS]）
     * @param sampleRate 采样率（Hz，由引擎回报，不硬编码 24000/16000）
     * @return 静音样本数；`gapMs <= 0`、`gapMs` 超上限（[LocalCloneConfig.MAX_SENTENCE_GAP_MS]）、
     *   `sampleRate <= 0`、或折算结果超 [MAX_SILENCE_SAMPLES]（异常采样率）时为 0（不插）
     */
    fun silenceSamples(gapMs: Int, sampleRate: Int): Int {
        if (gapMs <= 0 || sampleRate <= 0) return 0
        // 超上限同样回落 0（**不是**钳到 2000ms）：纵深防御——即便调用方绕过 `sanitized()` 直接传脏值，
        // 也不得凭一个越界毫秒数把整段播报拖成灾难，更不得静默改变听感基线。
        if (gapMs > LocalCloneConfig.MAX_SENTENCE_GAP_MS) return 0
        // 中间量用 Long：`sampleRate * gapMs` 在 Int 下会溢出（例如 48000 × 2000 = 9600 万尚可，
        // 但脏数据/未来上限一旦放大就会绕回负数 ⇒ 负长度数组）。溢出后回落 0，绝不产出负样本数。
        val samples = sampleRate.toLong() * gapMs / 1000L
        // 见 MAX_SILENCE_SAMPLES：异常采样率不得让调用方按 Int.MAX_VALUE 去分配数 GiB。
        return if (samples <= 0L || samples > MAX_SILENCE_SAMPLES) 0 else samples.toInt()
    }

    /**
     * 生成静音（全零）样本。
     *
     * @return 长度为 [silenceSamples] 的全零 [FloatArray]；为 0 时返回**空数组**（调用方据此跳过插入）
     */
    fun silence(gapMs: Int, sampleRate: Int): FloatArray {
        val n = silenceSamples(gapMs, sampleRate)
        return if (n <= 0) FloatArray(0) else FloatArray(n)
    }
}
