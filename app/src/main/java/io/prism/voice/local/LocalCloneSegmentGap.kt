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
     * **单次播报**允许插入的静音**总时长**上界（毫秒）。
     *
     * 为什么要它（批次29 验收 DEF-01）：[gapMsFor] 只按"边界类型"逐边界给停顿，**没有任何总量概念**
     * ⇒ 插入静音随输入长度**线性增长**。实测（批次29 验收 §3.5，`gapMs = 2000`＝合法域上界）：
     * 40 字 ⇒ 40 段 / **78.0 s**；4000 字 ⇒ 201 段 / **400 s**；20000 字 ⇒ 1001 段 / **2000 s（33 分钟）**。
     * 换成生产要启用的 500 ms 仍有 ≈25 ms/字（2000 字回复 ⇒ 50 s 静音）——**属体验灾难级**。
     * 不 OOM 也不 SIGKILL（单边界硬顶 [MAX_SILENCE_SAMPLES]、无溢出），但"播报中间静默几十秒"
     * 与"语速过快"是同一类用户可感缺陷。
     *
     * ## 为什么取 20 秒
     * 真人语料的**整句**停顿最长 680~780 ms；本值不是"自然语言的合理停顿"，而是
     * **"异常兜底"**——正常回复根本触不到它：
     * 500 ms 档下可容纳 **40 个句末边界**（20 000 ÷ 500；子句档 0.4×500=200 ms 则可容纳 100 个）。
     * 触到即说明文本异常长，此时**牺牲尾部停顿保播报完整**比"安静几十秒"更可接受。
     * （勘误：初版此处误写"960 个"，把量级算错 24 倍，guardrail F3 指出。）
     *
     * ## 语义（与 [gapPlanFor] 严格一致）
     * 预算**按毫秒累计**，达到上界后**其后所有边界一律 0**（硬停，不是"绕过后再试小的"）——
     * 硬停让行为**单调可预测**：文本越长，尾部停顿越早退化为无停顿，不会出现
     * "中间某处又冒出停顿"的跳变。
     *
     * ⚠️ **本值参与缓存键**（[localCloneCacheKey] 的 `maxTotalGapMs`）：它是**代码常量**，
     * 改它不会改变任何 `config` 字段 ⇒ 不并入键就会出现"改了没效果"（F-3 同型）。
     */
    const val MAX_TOTAL_GAP_MS = 20_000

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

    /**
     * 为一次播报算出**每个段边界**应插入的静音样本数（末段恒 0），并施加总时长上界。
     *
     * 这是段间停顿的**唯一出口**：流式与非流式两条播放路径都必须消费本函数的返回值，
     * 不得各自再调 [gapMsFor]/[silenceSamples]——批次29 时两路各有一份等价实现
     * （引擎内两处独立 `gapMsFor` + `silence` 调用），属同一语义两处实现，是漂移点；
     * 本函数把它们收敛为一处（批次30）。
     *
     * ## 规则
     * 1. 逐边界按 [gapMsFor] 定档（句末满额 / 子句 0.4 / 硬切 0），再按 [silenceSamples] 折算样本；
     * 2. **按毫秒累计**已用预算；某边界会使累计**超过** [maxTotalGapMs] 时，
     *    该边界及**其后所有边界**一律 0（硬停，见 [MAX_TOTAL_GAP_MS] 的语义说明）；
     * 3. 末段之后不插（`plan[lastIndex]` 恒 0）。
     *
     * @param segments 本次播报的全部段（顺序即播放顺序）
     * @param baseGapMs 清单里的基准段间停顿（`<= 0` ⇒ 全 0，即"默认不改变行为"）
     * @param sampleRate 采样率（由引擎回报）
     * @param maxTotalGapMs 总静音上界（默认 [MAX_TOTAL_GAP_MS]；`<= 0` 视为 0 ⇒ 全 0，
     *   与"越界/脏值一律不插"的既有约定一致，**不**解释为"无上限"）
     * @return 长度恒等于 `segments.size` 的样本数数组；调用方按 `plan[i] > 0` 决定是否插入
     */
    fun gapPlanFor(
        segments: List<String>,
        baseGapMs: Int,
        sampleRate: Int,
        maxTotalGapMs: Int = MAX_TOTAL_GAP_MS
    ): IntArray {
        val plan = IntArray(segments.size)
        if (baseGapMs <= 0 || sampleRate <= 0 || maxTotalGapMs <= 0) return plan
        var usedMs = 0L
        for (i in 0 until segments.size - 1) {
            val gapMs = gapMsFor(segments[i], baseGapMs)
            if (gapMs <= 0) continue
            // 硬停：预算不足以再容纳一个完整边界时，其后全部为 0（不"跳过它再试更小的"）
            if (usedMs + gapMs > maxTotalGapMs) break
            val samples = silenceSamples(gapMs, sampleRate)
            if (samples <= 0) continue
            plan[i] = samples
            usedMs += gapMs
        }
        return plan
    }
}
