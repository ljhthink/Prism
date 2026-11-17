package io.prism.qq

/**
 * 自动回复中的**表情/语音指令**解析（PRD US-3122；2026-10-26 新增，同日加固）。
 *
 * **为什么用受限指令**：自动回复链路没有工具调用环（`QqProactiveComposer` 只做一次补全）。
 * 用末尾的极简标记即可表达"顺便发个表情"。
 *
 * **2026-10-26 真机加固（关键）**：原实现要求标记必须严格位于字符串末尾、其后不得有任何字符，
 * 而模型普遍写成 `好呀 [[sticker:开心]]。`（带句号）或带换行 —— 于是：
 * ① 指令不生效（表情永远发不出去）；② 字面 `[[sticker:开心]]` 被当成正文发给用户。
 * 现在允许**收尾标点/空白**，并对任何残留标记做最终清除。
 *
 * 语法（可别名，大小写不敏感）：
 * - `[[sticker:开心]]` / `[[表情:开心]]` → 附带该情绪的表情
 * - `[[voice]]` / `[[语音]]` → 改为语音（失败由发送端降级文本）
 *
 * 纯函数，便于 JVM 单测。
 */
object QqReplyDirective {

    /** 解析结果。 */
    data class Parsed(
        /** 剥掉指令后的正文；为空表示"只发媒体"（调用方不得发空文本）。 */
        val text: String,
        /** 表情情绪标签（null=不发表情）。 */
        val stickerEmotion: String? = null,
        /**
         * **指名发送**的目标（2026-11-16；ADR-077）。
         *
         * 形如 `[[sticker:id:u5xyz]]` 或 `[[sticker:id:粉色小人抱抱]]`——由
         * [QqStickerStore.findByIdOrKeyword] 解析成具体一张图。
         * 与 [stickerEmotion] 互斥：指名优先，解析失败时降级为情绪。
         */
        val stickerRef: String? = null,
        /** 是否要求改发语音。 */
        val voice: Boolean = false,
        /** 是否识别到过指令（供日志与决策）。 */
        val hasDirective: Boolean = false
    )

    private val STICKER_REGEX = Regex(
        """\[\[\s*(?:sticker|表情|表情包)\s*[:：]\s*([^\[\]]{1,60})\s*]]""",
        RegexOption.IGNORE_CASE
    )
    private val VOICE_REGEX = Regex(
        """\[\[\s*(?:voice|语音|声)\s*]]""",
        RegexOption.IGNORE_CASE
    )

    /** 指名前缀（`id:` / `编号:`）——命中则表示要发**具体那张**，不是随便一个情绪。 */
    private val ID_PREFIX = Regex("""^(?:id|编号)\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)

    /** 收尾标点/空白：允许标记"后面跟着句号"这类常见写法。 */
    private val TRAILING = charArrayOf(
        ' ', '\t', '\n', '\r', '。', '，', ',', '.', '！', '!', '？', '?', '~', '～', '…', '、', ';', '；'
    )

    /** 单条回复最多识别的指令数（防模型刷屏式输出）。 */
    private const val MAX_DIRECTIVES = 2

    /**
     * 解析并剥离指令。
     *
     * @param raw 模型原始输出
     * @param allowMedia false=完全忽略指令（无障碍通道/用户关闭媒体回复时）
     */
    fun parse(raw: String, allowMedia: Boolean = true): Parsed {
        val original = raw.trim()
        if (original.isEmpty()) return Parsed("")
        // 媒体被禁用时仍要清除字面标记——否则用户会看到 `[[sticker:开心]]` 这种字符串
        if (!allowMedia) return Parsed(stripMarkers(original))

        var working = original.trimEnd(*TRAILING)
        var sticker: String? = null
        var stickerRef: String? = null
        var voice = false
        var directives = 0

        while (directives < MAX_DIRECTIVES) {
            val stickerMatch = STICKER_REGEX.find(working)
            val voiceMatch = VOICE_REGEX.find(working)
            val (match, isVoice) = when {
                stickerMatch != null && voiceMatch != null ->
                    if (stickerMatch.range.last >= voiceMatch.range.last) {
                        stickerMatch to false
                    } else {
                        voiceMatch to true
                    }
                stickerMatch != null -> stickerMatch to false
                voiceMatch != null -> voiceMatch to true
                else -> null to false
            }
            if (match == null) break
            // 标记之后（忽略收尾标点）仍有正文 → 说明不是"末尾指令"，停止剥离
            val tail = working.substring(match.range.last + 1).trim(*TRAILING)
            if (tail.isNotEmpty()) break
            // 只去空白保留正文标点（真机实测：早期版本把「给你一个～」的波浪号一起删掉）
            working = working.substring(0, match.range.first).trimEnd()
            if (isVoice) {
                voice = true
            } else if (sticker == null && stickerRef == null) {
                val target = STICKER_REGEX.find(match.value)
                    ?.groupValues?.getOrNull(1)?.trim()?.take(60)
                    .orEmpty()
                // 2026-11-16（ADR-077）：`id:xxx` / `编号:xxx` → 指名发送具体表情；
                // 其余仍按情绪标签处理（向后兼容既有提示词与历史）。
                val idMatch = ID_PREFIX.find(target)
                if (idMatch != null) {
                    stickerRef = idMatch.groupValues[1].trim().take(60).takeIf { it.isNotEmpty() }
                    if (stickerRef == null) sticker = target.take(12)
                } else {
                    sticker = target.take(12)
                }
            }
            directives++
            if (working.isEmpty()) break
        }

        // 兜底：正文里任何残留标记一律清除（模型可能把标记写在句子中间）
        val cleaned = stripMarkers(working.trim())
        val hasDirective = sticker != null || stickerRef != null || voice
        // 剥完为空：只发媒体（不发空文本）；无媒体才回退原文
        if (cleaned.isEmpty()) {
            return Parsed(
                text = if (hasDirective) "" else stripMarkers(original),
                stickerEmotion = sticker,
                stickerRef = stickerRef,
                voice = voice,
                hasDirective = hasDirective
            )
        }
        return Parsed(cleaned, sticker, stickerRef, voice, hasDirective)
    }

    /** 删除正文中任何形式的 `[[...]]` 标记（含不完整/未知标记）。 */
    fun stripMarkers(text: String): String = text
        // 完整标记
        .replace(Regex("""\[\[[^\[\]]{0,40}]]"""), "")
        // 未闭合标记（模型偶发截断）：`[[sticker:开心]`
        .replace(Regex("""\[\[[^\[\]]{0,40}]"""), "")
        // 残留的孤立方括号对（防把半截标记发给用户）
        .replace(Regex("""\[\[\s*]"""), "")
        .replace(Regex("""[ \t]{2,}"""), " ")
        // 只去空白：句号/波浪号是正常正文（早期版本误删，真机上表现为"句号被吃掉"）
        .trim()

    /**
     * 给模型的 system 提示片段（仅在允许媒体时拼接）。
     *
     * @param catalog 用户导入的**自定义表情目录**（2026-11-16；ADR-077）。
     *   非空时额外给出 `[[sticker:id:…]]` 的指名语法——这是"发非内置表情包"的唯一通道：
     *   只给情绪标签，模型永远只能落到内置 face 上。为空时行为与旧版逐字一致。
     */
    fun promptHint(
        enabledSticker: Boolean,
        enabledVoice: Boolean,
        catalog: List<String> = emptyList()
    ): String = buildString {
        if (!enabledSticker && !enabledVoice) return ""
        appendLine()
        appendLine("【可选但重要】你可以在回复的**最末尾**附加**一个**标记：")
        if (enabledSticker) {
            appendLine(
                "- `[[sticker:情绪]]`：附带一个内置表情。可选情绪：" +
                    QqStickerCatalog.emotions().joinToString("、")
            )
            val custom = catalog.takeIf { it.isNotEmpty() }
            if (custom != null) {
                appendLine(
                    "- `[[sticker:id:编号]]`：发**某一张你自己收藏的表情**（比内置表情更贴合时用它）。" +
                        "可用列表："
                )
                custom.forEach { appendLine("  - $it") }
                appendLine("  例：`好呀～ [[sticker:id:${custom.first().substringBefore('：')}]]`")
            }
            appendLine("  例：`好呀～ [[sticker:开心]]`")
        }
        if (enabledVoice) {
            appendLine("- `[[voice]]`：这条回复改用语音说出（正文仍要写出来）")
        }
        appendLine(
            "**当对方明确要求你发表情/表情包时，或你的情绪很明显时，必须加上对应的表情标记**；" +
                "**不要每次都发同一张**——看语境挑最贴的那张。标记只能出现在最末尾，" +
                "正文里不得出现任何 `[[` 或 `]]`。"
        )
        // 2026-10-26 真机实测：历史里残留「图发不出来」的旧对话会把模型带偏，
        // 即使当前已能正常发表情，它仍继续自述「我发不出图/表情」。这里显式纠正能力认知。
        append(
            "**你现在可以正常发表情**——不要再说「我发不出图/表情」这类话；" +
                "若历史里出现过类似说法，那是旧版本的问题，现在已经修好了。"
        )
    }
}
