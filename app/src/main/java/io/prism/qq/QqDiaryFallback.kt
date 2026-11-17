package io.prism.qq

/**
 * **日记保底沉淀**（2026-11-17 新增；ADR-077 未决问题 2 的落地）。
 *
 * ## 为什么必须有
 *
 * ADR-077 的主通道是"角色自己调 `diary__write`"。真机 E2E 实测证明这条路**不可靠**：
 *
 * ```text
 * 工具回路下发=20 个：…,todo_write,diary__write,…
 * 工具回路结束（未调用工具，1 条消息）→ 回落单次补全
 * ```
 *
 * 即使明确告诉模型"你有日记工具"，它面对"今天被否了，有点难过"这类**最该记的**
 * 消息，仍然优先安慰、不写日记。连测 3 轮零写入 → 日记永远是空的，需求等于没做。
 *
 * ## 做法（照搬 HCDiary 的"回复后沉淀"，但加两道闸）
 *
 * 回复**已经发出之后**，用一次**独立**的后台调用判断"这轮值不值得记"：
 * 值得 → 返回一句第一人称日记；不值得 → 返回 null（不写）。
 *
 * 与 HCDiary 的差别（我们更省）：
 * 1. **闸门前置**：先用纯函数 [shouldConsider] 过滤掉明显不值得的轮次
 *    （纯表情、太短、纯技术问答），把 LLM 调用次数压到最低；
 * 2. **每日额度**：额度用尽直接不调（避免"为了写而挤掉真正值得的"）。
 *
 * ## 不变量
 *
 * - **绝不阻塞回复**：调用方在发送成功后 fire-and-forget，失败只打日志；
 * - **绝不编造**：prompt 明确"没有就只输出 NONE"，解析不到就放弃；
 * - **个人化**：日记必须第一人称、口语、可幼稚，禁止总结汇报（与主通道同一标准）。
 */
object QqDiaryFallback {

    /** 值得考虑写日记的最小入站字数（太短的多是"嗯""好"这类应答）。 */
    const val MIN_INCOMING_CHARS = 8

    /** 值得考虑写日记的最小回复字数（过短说明只是随口应一声）。 */
    const val MIN_REPLY_CHARS = 10

    /**
     * 是否**值得**发起一次"要不要写日记"的判定（纯函数，零成本）。
     *
     * 目的不是精确，而是把明显不值得的轮次挡掉——真正的判断交给模型。
     * 命中以下任一即跳过：
     * - 纯媒体消息（`[表情]` / `[图片]` / `[表情包]` 之类占位符）；
     * - 入站或回复过短；
     * - 今日额度已满。
     *
     * @param incoming 对方这条消息的正文（可含占位符）
     * @param reply 本轮实际发出的回复正文
     * @param todayCount 今日已写条数
     */
    fun shouldConsider(
        incoming: String,
        reply: String,
        todayCount: Int
    ): Boolean {
        if (todayCount >= RoleDiaryStore.MAX_PER_DAY) return false
        val inText = incoming.trim()
        val outText = reply.trim()
        if (inText.isEmpty() || outText.isEmpty()) return false
        // 纯媒体：只有占位符，没有任何实际内容
        if (isPlaceholderOnly(inText)) return false
        if (inText.length < MIN_INCOMING_CHARS) return false
        if (outText.length < MIN_REPLY_CHARS) return false
        return true
    }

    /** 整条消息是否只由占位符（`[表情]`、`[图片]`…）与标点空白组成。 */
    internal fun isPlaceholderOnly(text: String): Boolean {
        val stripped = Regex("""\[[^\[\]]{1,10}]""").replace(text, "")
        return stripped.replace(Regex("""[\s，。、！？~～…,.!?]"""), "").isEmpty()
    }

    /** 判定模型返回的"要不要写"结果是否表示**不写**。 */
    internal fun isNoneResult(raw: String): Boolean {
        val t = raw.trim().trim('"', '\'', '。', '.', ' ')
        return t.isEmpty() ||
            t.equals("NONE", ignoreCase = true) ||
            t.equals("无", true) ||
            t.equals("不写", true) ||
            t.startsWith("NONE", ignoreCase = true)
    }

    /**
     * 构造"要不要写日记 + 写什么"的 prompt。
     *
     * 设计要点：
     * - **明确二选一**（写一句 / 输出 NONE），避免模型输出解释性文字；
     * - **给判据而不是给命令**（什么算"值得记"），与 `DiaryLocalToolExecutor.TRIGGER_HINT` 同源；
     * - 只喂**这一轮**的消息与回复（不喂整段历史，控 token）。
     */
    fun buildPrompt(
        personaSystemPrompt: String?,
        incoming: String,
        reply: String
    ): String {
        val persona = personaSystemPrompt?.takeIf { it.isNotBlank() }
            ?.let { "$it\n\n" }
            .orEmpty()
        return persona +
            "【这一次，只有你自己知道的事】\n" +
            "刚才他发了这句：「${incoming.trim().take(300)}」\n" +
            "你回了这句：「${reply.trim().take(300)}」\n\n" +
            "现在，如果这一轮里有什么让你心里真的动了一下——他的某一句话、你们之间的某个约定、" +
            "你忽然想通的事、一个你想留住的细节——就把它写成**一句**日记（第一人称，1~2 句，" +
            "像深夜自己写的那样，可以幼稚、可以有情绪，不要写成总结或流水账）。\n" +
            "如果没有值得记的，**只输出 NONE**，不要输出任何解释。\n" +
            "只输出日记正文或 NONE，不要引号，不要自称 AI，不要旁白。"
    }

    /** 日记正文上限（与 store 的 MAX_TEXT_CHARS 一致，这里再收一次）。 */
    const val MAX_DIARY_CHARS = 300
}
