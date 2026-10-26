package io.prism.qq

/**
 * 自动回复中的**表情/语音指令**解析（PRD US-3122；2026-10-26 新增）。
 *
 * **为什么用受限指令而不是让模型直接调工具**：自动回复链路没有工具调用环
 * （`QqProactiveComposer` 只做一次补全）。用末尾的极简标记即可表达"顺便发个表情"，
 * 且**解析失败必须退化为纯文本**——绝不能因为一个标记让整条回复丢失。
 *
 * 语法（只认末尾，最多一个）：
 * - `[[sticker:开心]]` → 附带该情绪的表情
 * - `[[voice]]` → 改为语音（失败由发送端降级文本）
 *
 * 纯函数，便于 JVM 单测。
 */
object QqReplyDirective {

    /** 解析结果。 */
    data class Parsed(
        /** 剥掉指令后的正文（保证非空；若剥完为空则回退原文）。 */
        val text: String,
        /** 表情情绪标签（null=不发表情）。 */
        val stickerEmotion: String? = null,
        /** 是否要求改发语音。 */
        val voice: Boolean = false
    )

    private val STICKER_REGEX = Regex("""\[\[\s*sticker\s*[:：]\s*([^\[\]]{1,12})\s*]]""")
    private val VOICE_REGEX = Regex("""\[\[\s*voice\s*]]""")

    /** 单条回复的指令上限（防模型刷屏式输出）。 */
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
        if (!allowMedia) return Parsed(original)
        var sticker: String? = null
        var voice = false
        var directives = 0

        // 只认**末尾**出现的指令：避免正文里的方括号被误吃
        var working = original
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
            // 指令之后若还有正文，说明不是"末尾指令"——不剥离，交由正文原样发送
            val tail = working.substring(match.range.last + 1)
            if (tail.isNotBlank()) break
            working = working.substring(0, match.range.first).trimEnd()
            if (isVoice) {
                voice = true
            } else if (sticker == null) {
                sticker = STICKER_REGEX.find(match.value)
                    ?.groupValues?.getOrNull(1)?.trim()
            }
            directives++
            if (working.isEmpty()) break
        }
        val cleaned = working.trim()
        // 剥完为空 → 回退原文（宁可发出带标记的文字，也不要发空消息）
        if (cleaned.isEmpty()) return Parsed(original, sticker, voice)
        return Parsed(cleaned, sticker, voice)
    }

    /** 给模型的 system 提示片段（仅在允许媒体时拼接）。 */
    fun promptHint(enabledSticker: Boolean, enabledVoice: Boolean): String = buildString {
        if (!enabledSticker && !enabledVoice) return ""
        appendLine()
        appendLine("【可选】你可以在回复**最末尾**附加一个标记（最多一个）：")
        if (enabledSticker) {
            appendLine("- [[sticker:开心]] 表示顺便发一个对应情绪的表情（可选情绪：")
            appendLine(QqStickerCatalog.emotions().joinToString("、"))
            appendLine("）")
        }
        if (enabledVoice) {
            appendLine("- [[voice]] 表示这条回复改用语音说出（内容仍按文字给出）")
        }
        append("标记必须放在最后；正文里不要出现这些标记。")
    }
}
