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
        /** 是否要求改发语音。 */
        val voice: Boolean = false,
        /** 是否识别到过指令（供日志与决策）。 */
        val hasDirective: Boolean = false
    )

    private val STICKER_REGEX = Regex(
        """\[\[\s*(?:sticker|表情|表情包)\s*[:：]\s*([^\[\]]{1,12})\s*]]""",
        RegexOption.IGNORE_CASE
    )
    private val VOICE_REGEX = Regex(
        """\[\[\s*(?:voice|语音|声)\s*]]""",
        RegexOption.IGNORE_CASE
    )

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
            } else if (sticker == null) {
                sticker = STICKER_REGEX.find(match.value)
                    ?.groupValues?.getOrNull(1)?.trim()?.take(12)
            }
            directives++
            if (working.isEmpty()) break
        }

        // 兜底：正文里任何残留标记一律清除（模型可能把标记写在句子中间）
        val cleaned = stripMarkers(working.trim())
        val hasDirective = sticker != null || voice
        // 剥完为空：只发媒体（不发空文本）；无媒体才回退原文
        if (cleaned.isEmpty()) {
            return Parsed(
                text = if (hasDirective) "" else stripMarkers(original),
                stickerEmotion = sticker,
                voice = voice,
                hasDirective = hasDirective
            )
        }
        return Parsed(cleaned, sticker, voice, hasDirective)
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

    /** 给模型的 system 提示片段（仅在允许媒体时拼接）。 */
    fun promptHint(enabledSticker: Boolean, enabledVoice: Boolean): String = buildString {
        if (!enabledSticker && !enabledVoice) return ""
        appendLine()
        appendLine("【可选但重要】你可以在回复的**最末尾**附加**一个**标记：")
        if (enabledSticker) {
            appendLine(
                "- `[[sticker:情绪]]`：附带一个表情。可选情绪：" +
                    QqStickerCatalog.emotions().joinToString("、")
            )
            appendLine("  例：`好呀～ [[sticker:开心]]`")
        }
        if (enabledVoice) {
            appendLine("- `[[voice]]`：这条回复改用语音说出（正文仍要写出来）")
        }
        appendLine(
            "**当对方明确要求你发表情/表情包时，或你的情绪很明显时，必须加上对应的表情标记**；" +
                "标记只能出现在最末尾，正文里不得出现任何 `[[` 或 `]]`。"
        )
    }
}
