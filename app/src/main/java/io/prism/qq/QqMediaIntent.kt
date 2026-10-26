package io.prism.qq

/**
 * 媒体**意图识别**（2026-10-26 新增；PRD US-3122 修订）。
 *
 * **为什么需要**：真机实测，用户直接说"发个表情包"，模型只回了文字
 * （`好呀，给你一个～接住啦♪`），压根没输出 `[[sticker:…]]`。
 * 把"发不发"完全交给模型，等于把功能交给运气。
 *
 * 规则：**用户明确要求表情/语音时，由代码兜底触发**，不依赖模型是否写标记；
 * 情绪从文案里猜（猜不到就用中性"开心"），保证"说了就会发"。
 *
 * 纯函数，便于 JVM 单测。
 */
object QqMediaIntent {

    private val STICKER_WORDS = listOf(
        "表情包", "表情", "贴纸", "emoticon", "sticker", "发表情", "斗图",
        "发个图", "发张图", "来张图", "来张表情", "emoji"
    )

    private val VOICE_WORDS = listOf(
        "语音", "发条语音", "发个语音", "说句话", "说一句", "念一下", "读一下", "voice"
    )

    /** 情绪关键词 → 内置表情情绪标签（顺序敏感：先长词）。 */
    private val EMOTION_WORDS: List<Pair<String, String>> = listOf(
        "开心" to "开心", "高兴" to "开心", "哈哈" to "大笑", "笑死" to "开心",
        "难过" to "难过", "伤心" to "难过", "委屈" to "委屈", "哭" to "哭",
        "生气" to "生气", "讨厌" to "生气", "无语" to "无语", "服了" to "无语",
        "尴尬" to "尴尬", "汗" to "汗", "惊讶" to "惊讶", "震惊" to "惊讶",
        "疑问" to "疑问", "不懂" to "疑问", "为啥" to "疑问", "为什么" to "疑问",
        "赞同" to "赞同", "同意" to "赞同", "厉害" to "赞同", "牛" to "赞同",
        "加油" to "加油", "抱抱" to "抱抱", "亲亲" to "亲亲", "害羞" to "害羞",
        "调皮" to "调皮", "困" to "困", "晚安" to "困"
    )

    /** 用户是否在要表情。 */
    fun wantsSticker(text: String): Boolean {
        val lower = text.lowercase()
        return STICKER_WORDS.any { lower.contains(it) }
    }

    /** 用户是否在要语音。 */
    fun wantsVoice(text: String): Boolean {
        val lower = text.lowercase()
        return VOICE_WORDS.any { lower.contains(it) }
    }

    /**
     * 从对方文案中猜一个情绪标签（用于"对方要表情"时挑表情）。
     *
     * @return 命中的情绪标签；未命中返回 null（调用方用默认情绪）
     */
    fun guessEmotion(text: String): String? =
        EMOTION_WORDS.firstOrNull { text.contains(it.first) }?.second

    /** 兜底情绪（无任何线索时用，保证"总能发一个表情"）。 */
    const val DEFAULT_EMOTION: String = "开心"
}
