package io.prism.qq

/**
 * QQ 回复文本的**出口闸门**（2026-11-22；ADR-080，用户问题 1「没有活人感」）。
 *
 * **为什么必须在代码层再做一遍**：人设卡里的风格约束是「软约束」——模型在多轮上下文、
 * 工具结果、长历史之后会稳定漂移回默认腔调。真机 101 条 bot 回复实测：
 * 32.7% 以问句收尾、42.6% 带 `\n\n` 段落（像写小作文）、5.9% 出现「不是…而是」对偶、
 * 工具轮次还会把 `[1][3]` 引用编号直接发给用户。这些是**机械特征**，
 * 用纯函数兜底比继续堆提示词可靠（且可断言、可回归）。
 *
 * **分工**（不要在这里做语义改写）：
 * - 人设卡（A1）负责「说什么、什么态度」；
 * - 本闸门只负责「形态」：Markdown / 引用编号 / 段落 / 问句数量 / 对偶句式；
 * - 不删事实、不改语气、不缩短技术解释（见 [isChatty] 的判据）。
 *
 * 调用点：`PrismApplication` 拿到 `QqReplyDirective.parse` 的 **`parsed.text` 之后**、
 * 气泡拆分之前。**不能**放在 parse 之前——那样 `[[sticker:…]]` 会被当成正文处理。
 *
 * 已知取舍（刻意，不要"修"）：二维数组下标 `a[1][2]` 的第二维会被当引用清掉
 * （`a[1]` 保留）。若为它排除 `]` 前缀，`[1][3]` 这种**真机常态**的检索编号就清不掉了；
 * 引用噪声的危害远大于二维下标。
 *
 * 纯函数，便于 JVM 单测。
 */
object QqReplyTextGuard {

    /** 单条聊天回复最多保留几个问句（超过的部分按 [capQuestions] 的规则收敛）。 */
    const val MAX_QUESTIONS_CHAT = 2

    /**
     * 判「闲聊体」的长度阈值：超过它的回复按「可能是在讲正事」处理，只做最小干预。
     *
     * 为什么用长度而不是意图分类：人设卡 §十五 明确「解释、分析、方案、代码可以很长、
     * 可以用 Markdown 结构」。硬套聊天规范会把技术回答打散，那比模板化更糟。
     */
    const val CHATTY_MAX_CHARS = 200

    /** 统计与结果（日志用：只记数量，不记正文，避免隐私进日志）。 */
    data class Result(
        val text: String,
        /** 被清掉的引用编号个数（`[1]` 这类）。 */
        val citationsRemoved: Int = 0,
        /** 被清掉的 Markdown 标记个数（标题/加粗/斜体/行内代码/列表符）。 */
        val markdownRemoved: Int = 0,
        /** 收敛掉的问句个数。 */
        val questionsRemoved: Int = 0,
        /** 合并掉的空行段数（`\n\n` → `\n`）。 */
        val paragraphsMerged: Int = 0,
        /** 被改写的「不是…而是」对偶个数（只保留第一处）。 */
        val oppositionRewritten: Int = 0
    ) {
        /** 是否发生过任何改动（决定要不要打日志）。 */
        val changed: Boolean
            get() = citationsRemoved + markdownRemoved + questionsRemoved +
                paragraphsMerged + oppositionRewritten > 0

        /** 一行诊断（不含正文；guardrail L-02：日志不回显聊天内容）。 */
        fun logLine(): String =
            "风格闸门：引用-$citationsRemoved 标记-$markdownRemoved 问句-$questionsRemoved " +
                "段落-$paragraphsMerged 对偶-$oppositionRewritten"
    }

    /**
     * 引用编号：`[1]`、`[12]`（1–3 位数字）。
     *
     * 三重保护（guardrail M-01）：原实现 `\[\d{1,3}]` 会把代码下标 `arr[0]`、`list[12]`
     * 也删掉（`arr[0]` → `arr`），与"不删事实"直接冲突。
     * - `(?<![\w\[])`：前面紧邻标识符字符或 `[` 的不算引用（`arr[0]` 保留；
     *   中文、标点、行首仍然命中 —— 检索排版痕迹正是那种形态「…结果[1]。」）。
     *   连续编号 `[1][2]` 仍会被逐个清掉（第二个前面是 `]`，已从不否定集合里放行）。
     * - `(?!\()`：后面紧跟 `(` 的是 Markdown 链接 `[1](url)`，必须保留。
     * - 含围栏代码块时整段跳过（见 [apply]）。
     */
    private val CITATION = Regex("""(?<![\w\[])\[\d{1,3}](?!\()""")

    /** Markdown 标题：`## 标题` → `标题`。 */
    private val HEADING = Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]+""")

    /** 加粗：`**x**` / `__x__` → `x`。 */
    private val BOLD = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")

    /** 斜体：`*x*` / `_x_`（不误伤 `2*3*4` 之外的常见场景有限，故要求两侧非空白）。 */
    private val ITALIC = Regex("""(?<![\w*])([*_])(?=\S)([^*_\n]{1,80}?)(?<=\S)\1(?![\w*])""")

    /** 行内代码：`` `x` `` → `x`（QQ 里反引号很扎眼）。 */
    private val INLINE_CODE = Regex("""`([^`\n]{1,200})`""")

    /** 无序列表符：`- x` / `* x` / `+ x` → `x`（保留换行，等价于一行一句）。 */
    private val BULLET = Regex("""(?m)^[ \t]{0,3}[-*+•][ \t]+""")

    /** 有序列表符：`1. x` → `x`。 */
    private val ORDERED = Regex("""(?m)^[ \t]{0,3}\d{1,2}[.、)][ \t]+""")

    /** 连续空行（段落）：两个以上换行折成一个。 */
    private val BLANK_LINES = Regex("""\n{2,}""")

    /** 句子切分：以中文/英文句末标点或换行为界。 */
    private val SENTENCE_END = Regex("""(?<=[。！？!?…])""")

    /** 「不是…而是」的**对偶标记**（只动 `而是`，语义不变，见 [breakOpposition]）。 */
    private val OPPOSITION = Regex("""不是[^。！？!?\n]{0,24}?[，,][^。！？!?\n]{0,6}?而是""")

    /**
     * 对模型输出执行全部闸门。
     *
     * **调用点在 `QqReplyDirective.parse` 之后**（拿到 `parsed.text` 再进来）：
     * 指令标记必须原样留到 parse 才能被识别，先 guard 会把 `[[sticker:…]]` 当成正文处理。
     * （guardrail L-01：原 KDoc 写反了。）
     *
     * @param raw 已剥掉 `[[sticker:…]]` 指令的正文
     */
    fun apply(raw: String): Result {
        val original = raw.trim()
        if (original.isEmpty()) return Result("")

        var citations = 0
        var markdown = 0
        var questions = 0
        var paragraphs = 0
        var opposition = 0

        // 0) 含代码块的回复整段跳过引用清理（guardrail M-01）：
        //    代码里的 `arr[0]` 前面虽然已有 lookbehind 保护，但 `x = [1]` 这类字面量数组
        //    仍可能被误清。代码是"事实"，宁可留一个引用编号，不能改代码。
        val hasCodeFence = original.contains("```")

        // 1) 引用编号：任何长度的回复都要清（`[1][3]` 是检索结果的排版痕迹，不是人话）
        var text = if (hasCodeFence) {
            original
        } else {
            CITATION.replace(original) { citations++; "" }
        }

        // 2) 对偶句式：同一段里第二处起的「不是…而是」→「不是…是」（语义等价，破签名）
        val oppMatches = OPPOSITION.findAll(text).toList()
        if (oppMatches.size > 1) {
            var seen = 0
            text = OPPOSITION.replace(text) { m ->
                seen++
                if (seen == 1) {
                    m.value
                } else {
                    opposition++
                    m.value.substringBeforeLast("而是") + "是"
                }
            }
        }

        val chatty = isChatty(original)
        if (chatty) {
            // 3) Markdown：只在闲聊体里拆（技术长文保留结构，人设卡 §十五 要求）
            text = HEADING.replace(text) { markdown++; "" }
            text = BOLD.replace(text) { m -> markdown++; m.groupValues[2] }
            text = ITALIC.replace(text) { m -> markdown++; m.groupValues[2] }
            text = INLINE_CODE.replace(text) { m -> markdown++; m.groupValues[1] }
            text = BULLET.replace(text) { markdown++; "" }
            text = ORDERED.replace(text) { markdown++; "" }
            // 4) 段落：闲聊里 `\n\n` 折成单换行（QQ 上一段一段地发像写报告）
            paragraphs = BLANK_LINES.findAll(text).count()
            text = BLANK_LINES.replace(text, "\n")
            // 5) 问句：最多 MAX_QUESTIONS_CHAT，超出时按「先删尾句」的保守顺序收敛
            val capped = capQuestions(text, MAX_QUESTIONS_CHAT)
            questions = capped.second
            text = capped.first
        }

        // 6) 收尾清理：行尾空白、连续空格、首尾空白
        text = text.replace(Regex("""(?m)[ \t]+$"""), "")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .trim()

        return Result(
            text = text.ifEmpty { original },
            citationsRemoved = citations,
            markdownRemoved = markdown,
            questionsRemoved = questions,
            paragraphsMerged = paragraphs,
            oppositionRewritten = opposition
        )
    }

    /**
     * 「闲聊体」判据：短、且不含代码块 / 表格 / 多级标题。
     *
     * 长回复、代码块、表格一律视为「在讲正事」，只清引用编号与对偶，不动结构。
     */
    fun isChatty(text: String): Boolean {
        if (text.length > CHATTY_MAX_CHARS) return false
        if (text.contains("```")) return false
        if (text.contains("|") && text.lines().count { it.count { c -> c == '|' } >= 2 } >= 2) return false
        return true
    }

    /**
     * 把问句收敛到 [max] 个。
     *
     * 规则（保守，宁可少改）：
     * 1. 逐句扫描，问句超过 [max] 时，**优先删除末尾的疑问句整句**（人设卡里「留白也是说话」）；
     * 2. 末句不删（例如"你吃了吗？"只有一句疑问且未超额）；
     * 3. 若超额的疑问句在中间且长度 > 40 字，不删除——只放弃收敛，避免把正常追问删成残句。
     *
     * @return 收敛后的文本 与 被删除的问句数
     */
    fun capQuestions(text: String, max: Int = MAX_QUESTIONS_CHAT): Pair<String, Int> {
        if (max < 0) return text to 0
        val sentences = text.split(SENTENCE_END)
            .filter { it.isNotBlank() }
            .toMutableList()
        if (sentences.isEmpty()) return text to 0
        val total = sentences.count { isQuestion(it) }
        if (total <= max) return text to 0
        var removed = 0
        var guard = 0
        while (sentences.count { isQuestion(it) } > max && guard++ < 8) {
            // 从末尾往前找"可删的疑问句"：必须是独立句、且不超长
            val idx = sentences.indices.lastOrNull { i ->
                isQuestion(sentences[i]) && sentences[i].trim().length <= 40
            }
            if (idx == null) break
            // 至少保留 max 个：仅当删除后仍 >= max 才删（避免删到 0）
            val after = sentences.count { isQuestion(it) } - 1
            if (after < max) break
            sentences.removeAt(idx)
            removed++
        }
        return sentences.joinToString("") to removed
    }

    /** 判断一句是否是疑问句（问号结尾，含 `？`/`?`）。 */
    private fun isQuestion(sentence: String): Boolean {
        val t = sentence.trimEnd()
        return t.endsWith("？") || t.endsWith("?")
    }
}
