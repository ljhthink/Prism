package io.prism.voice.local

/**
 * 长文本切段（纯函数，TKN-LVC-012 / BUG-20260920-006）。
 *
 * ## 为什么必须切段
 *
 * 真机缺陷：长文本被当作**一次无界合成**——sherpa 内部虽有 `max_char_in_sentence` 分段，
 * 但**全部分段落都累积进同一个音频对象**，直到全部算完才可能播放。后果（真机日志）：
 *
 * ```
 * 19:44:30.670 I/Process: Sending signal. PID: 24565 SIG: 9      ← 进程被系统杀掉
 * （同时段 Java 堆: Clamp target GC heap from 284MB to 256MB / 217MB/221MB / Waiting for a blocking GC Alloc）
 * ```
 *
 * ⇒ ① 合成期间**完全没有声音**（第一段要等整段算完）；② 音频与算力累积把 App 推到内存/耗时上限外被 kill。
 *
 * 切段后：每段的合成时长与峰值内存都有界，且**第一段算完即可出声**（逐段播放）。
 *
 * ## 切分规则（优先自然边界，硬保证不超长）
 *
 * 1. 长度 ≤ [maxChars] ⇒ 原样返回（**不改变既有短文本行为**）；
 * 2. 否则在**句末标点**（`。！？!?.`）后切（若当前段已达 [maxChars] 的一半以上）；
 * 3. 句内过长时退而在**子句标点**（`，；：,;:`）后切；
 * 4. 仍无标点可用则**硬切**；
 * 5. **不丢字符**：`segments.joinToString("") == text`（由单测钉住——这是最重要的不变量）；
 * 6. **仅当 [splitSentences] 为 true 时**：长度 ≤ [maxChars] 的短文本也按**句末标点**切分
 *    （无句末标点时仍返回单段）。默认 false ⇒ **既有调用方行为逐字不变**。
 *
 * ## 规则 6 的理由（TKN-LVC-016 续）
 *
 * 播放层的段间停顿（[LocalCloneSegmentGap.gapMsFor]）**只在段边界插入**，而规则 1
 * 让短文本恒为单段 ⇒ `sentenceGapMs` 对短文本**恒不触发**（实测：25 字文本在
 * `sentenceGapMs` = 0/300/600 三种取值下产出**字节完全相同**的音频）。
 *
 * 而实测已确认：**本模型单段停顿的结构上限约 200–280 ms**（`silence_scale` 0.0→8.0
 * 全档扫描 + 长度 6→96 字扫描，`≥250 ms` 停顿数恒为 0），而真人语料**每句有 6.5 个
 * ≥250 ms 停顿、最长 713 ms** ⇒ **长停顿只能由播放层补**。故需要让短文本也能切出段边界。
 *
 * ⚠️ **本参数由调用方按 pack 配置门控**（`config.sentenceGapMs in 1..MAX_SENTENCE_GAP_MS`），
 * 不改变任何未开启该配置的既有音色包行为。
 *
 * ⚠️ **覆盖范围声明（guardrail F3，已登记未修）**：本机制只对**内层**（引擎内 `segmentForSynthesis`）
 * 产生的段边界生效。**通话/播报链另有外层句级切分**（`VoiceSessionController.resolveBroadcastSegments`
 * → `speakSegments`，每段各调一次 `tts.speak`），**那些外层边界不经过本函数、也不经过
 * [LocalCloneSegmentGap.gapMsFor]** ⇒ **外层边界永不插入 `sentenceGapMs` 静音**。
 * 故当外层已把回复切成"整句且 ≤ [maxChars]"时，内层仍返回单段 ⇒ **该链上短句依旧零停顿**。
 * 这是既有结构缺口（非本次引入），若要让通话/播报链也受益，需另行评估与角色包语义的冲突。
 */
internal fun segmentForSynthesis(
    text: String,
    maxChars: Int,
    firstMaxChars: Int = maxChars,
    splitSentences: Boolean = false
): List<String> {
    if (maxChars <= 0) return listOf(text)
    if (text.length <= maxChars) {
        return if (splitSentences) splitAtSentenceEnds(text) else listOf(text)
    }
    val out = ArrayList<String>(text.length / maxChars + 1)
    val sb = StringBuilder()
    var lastClauseBreak = -1
    var limit = if (firstMaxChars in 1..maxChars) firstMaxChars else maxChars
    for (ch in text) {
        sb.append(ch)
        if (isClauseBoundary(ch)) lastClauseBreak = sb.length
        val atHardLimit = sb.length >= limit
        val atSentenceBoundary = isSentenceBoundary(ch) && sb.length >= limit / 2
        if (atSentenceBoundary || atHardLimit) {
            var cut = when {
                // 句末标点：直接在此收束
                atSentenceBoundary -> sb.length
                // 句内过长：优先回退到最近的子句标点（避免切在词中间）
                lastClauseBreak in 1 until sb.length -> lastClauseBreak
                else -> sb.length
            }
            // 代理对安全（guardrail TKN-LVC-012 N4）：不得把代理对切成两半
            if (cut > 0 && cut < sb.length && Character.isHighSurrogate(sb[cut - 1])) cut -= 1
            if (cut <= 0) cut = sb.length
            out.add(sb.substring(0, cut))
            sb.delete(0, cut)
            lastClauseBreak = -1
            limit = maxChars // 仅**第一段**用较短上限（尽快出声），其后恢复常规上限
        }
    }
    if (sb.isNotEmpty()) {
        // 收尾修正：若剩余**只含标点**（典型场景：硬切恰好落在句末标点之前），
        // 并回上一段而不是产出孤立的"。"段——否则既产生怪异分段、又让该段缺句末停顿线索。
        // 允许因此超出上限至多 2 字符（已在单测与 KDoc 中显式声明该边界）。
        if (out.isNotEmpty() && sb.length <= 2 && sb.all { isSentenceBoundary(it) || isClauseBoundary(it) }) {
            out[out.size - 1] = out[out.size - 1] + sb
        } else {
            out.add(sb.toString())
        }
    }
    return out
}

/**
 * 短文本按**句末标点**切分（规则 6；仅 [segmentForSynthesis] 在 `splitSentences` 为 true 时调用）。
 *
 * - 在每个句末标点**之后**收束（标点归**上一段**，与长文本路径一致——"标点随前段"正是
 *   [LocalCloneSegmentGap.gapMsFor] 判定该边界类型的依据）；
 * - **不丢字符**：各段拼接与入参逐字相同（`joinToString("") == text`）；
 * - 句末标点本身是 BMP 字符、不可能是代理对的高位 ⇒ 在此切分**天然代理对安全**；
 * - 无句末标点时返回单段 ⇒ 与规则 1 行为一致（短文本**不会**因此被硬切）。
 *
 * ⚠️ **已知特性（guardrail F2，已登记未修）**：本函数**没有最小段长门槛**（与长文本路径的
 * `limit/2` 不对称）⇒ ①段数上界为 `text.length`（≤ [maxChars]，**有界不会爆炸**）；
 * ②可产出**纯标点段**（如 `"。".repeat(40)` ⇒ 40 段，而 41 字因走长路径只有 3 段，
 * 存在 **40/41 悬崖**）；③前导标点会产生**孤立全标点首段**（`"。你好。"` ⇒ `["。","你好。"]`）。
 * 触发需 pack 显式开 `sentenceGapMs > 0`（既有 pack 全为 0 ⇒ 当前不可达）。
 */
private fun splitAtSentenceEnds(text: String): List<String> {
    val out = ArrayList<String>(4)
    var start = 0
    for (i in text.indices) {
        if (isSentenceBoundary(text[i])) {
            out.add(text.substring(start, i + 1))
            start = i + 1
        }
    }
    if (start < text.length) out.add(text.substring(start))
    return if (out.isEmpty()) listOf(text) else out
}

/**
 * 句末标点判定。**internal 而非 private**：段间停顿（[LocalCloneSegmentGap.gapMsFor]）必须按
 * **同一套**边界定义判定"这个段边界是句末 / 子句 / 硬切"，两处若各写一份字符集会静默漂移
 * （切分按 A 判、插停顿按 B 判 ⇒ 逗号处插满停顿或句末处不插）。
 */
internal fun isSentenceBoundary(c: Char): Boolean =
    c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '.'

/** 子句（逗号/分号/冒号）标点判定；internal 理由同 [isSentenceBoundary]。 */
internal fun isClauseBoundary(c: Char): Boolean =
    c == '，' || c == '；' || c == '：' || c == ',' || c == ';' || c == ':'

/**
 * 单段最大字符数（第一段之后）。
 *
 * 取 40：按实测 0.24s 音频/字、RTF 2.2–8.4 估算，单段音频约 10s、合成约 22–84s，
 * 峰值内存 O(40 字) ≈ 0.88MiB（实测单次整段 PCM 约 23KB/字，9000 字可达 198MB）。
 *
 * ⚠️ 与 `LocalCloneConfig.maxCharsInSentence` **不是同一概念**（后者是 sherpa 内部按句切分的上限）：
 * 本常量是**播放粒度**（每次合成/播放的单位），目的不同，故不合并为单一来源（guardrail N6 已登记口径）。
 */
internal const val DEFAULT_SEGMENT_CHARS = 40

/**
 * **第一段**的字符上限（guardrail TKN-LVC-012 N1）。
 *
 * 取 16：按 0.24s 音频/字、RTF 2.2–8.4 估算，首段音频约 3.8s、合成约 **8–32s**
 * ⇒ 首声延迟从 21–81s 降到 8–32s（用户主诉"完全没声音"的关键指标）。
 */
internal const val FIRST_SEGMENT_CHARS = 16
