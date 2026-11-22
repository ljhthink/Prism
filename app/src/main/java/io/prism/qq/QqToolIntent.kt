package io.prism.qq

/**
 * 「这句话该不该先去查」的**代码侧前置判定**（2026-11-22 新增；ADR-080）。
 *
 * ## 为什么不让模型自己决定
 *
 * 真机日志（2026-11-22 22:23:39）证据：工具回路确实下发了 20 个工具，但
 * `工具回路结束（未调用工具，1 条消息）→ 回落单次补全` —— 模型面对"还没呢，还在忙"
 * 这类闲聊不查是对的，可面对**会变的事实**它也常常不查，只凭训练记忆开口。
 * 用户的抱怨正是「不会像真人一样去搜不知道的信息，需要人去提醒」。
 *
 * 业界做法（qq-fish-bot 的 net 段）：**先用关键词前置命中直接给"该查"的强提示**，
 * 模型只负责转述，而不是每轮先花一次推理决定"要不要查"。
 *
 * ## 判据（宁可漏，不可滥）
 *
 * 只命中三类，且都是"真人也会掏手机查一下"的场景：
 * 1. 明确的查询动作（「查一下」「搜搜」）；
 * 2. 天然带时效的名词（新闻 / 热搜 / 天气 / 股价 / 汇率 / 比分 / 票价…）；
 * 3. 时间词 + 疑问（「今天有什么新闻」）或事实疑问（「是谁」「多少钱」）。
 *
 * 反例保护：「今天好累」只命中时间词、没有疑问也没有查询动词 → **不触发**，
 * 否则每句"今天"都要联网，反而更像机器人。
 *
 * 纯函数，便于 JVM 单测。
 */
object QqToolIntent {

    /** 明确要求去查（用户已经在让你查了）。 */
    private val QUERY_VERBS = Regex(
        "查一下|查查|查下|搜一下|搜搜|搜下|帮我查|帮我搜|你查|上网看|网上搜|百度|谷歌|搜不到|找一下"
    )

    /** 天生带时效的名词：命中即查（这些答案不查一定是旧的）。 */
    private val FRESH_NOUNS = Regex(
        "新闻|热搜|热榜|头条|天气|气温|下雨|台风|股价|行情|汇率|金价|油价|票价|比分|赛程|" +
            "票房|发布会|更新了|上线了|发售|预售|多少钱|价格|几点开|末班车|限行|停电"
    )

    /** 时间词（单独出现不算，必须配疑问/事实追问）。 */
    private val TIME_MARKERS = Regex(
        "今天|昨天|明天|现在|最近|最新|刚才|刚刚|这周|本周|上周|今年|今晚|目前"
    )

    /** 事实型追问（需要外部信息才能答）。 */
    private val FACT_ASKS = Regex(
        "是谁|是什么|什么时候|哪一年|哪一天|在哪里|哪儿|多少钱|怎么回事|真的假的|" +
            "出了没|上线没|有没有|能不能查|有人知道"
    )

    /** 是否问句（含中文/英文问号）。 */
    private fun hasQuestion(text: String): Boolean =
        text.contains('？') || text.contains('?')

    /**
     * 这一句是否应该先调工具再回答。
     *
     * @param text 对方刚发来的正文（不含媒体说明）
     */
    fun shouldSearch(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // 1) 明确查询动作：永远该查
        if (QUERY_VERBS.containsMatchIn(t)) return true
        // 2) 时效名词：永远该查
        if (FRESH_NOUNS.containsMatchIn(t)) return true
        // 3) 时间词 + （疑问 或 事实追问）
        if (TIME_MARKERS.containsMatchIn(t) && (hasQuestion(t) || FACT_ASKS.containsMatchIn(t))) {
            return true
        }
        // 4) 纯事实追问（有问号才算——「你问我我是谁」不该触发）
        return hasQuestion(t) && FACT_ASKS.containsMatchIn(t)
    }

    /**
     * 追加到 user 末尾的**硬提示**；不需要查时返回 null（不污染 prompt）。
     *
     * 措辞刻意避开"工具名清单"：模型只需要一条动作指令，不需要第二条系统说明。
     */
    fun hintFor(text: String): String? {
        if (!shouldSearch(text)) return null
        return "\n\n【这一句先去查】他问的是会变的事实，别凭印象答。" +
            "先调用 `web_search__search` 查一次（只查一次，不要反复搜）。" +
            "然后用你自己的话把结果讲出来：**别念标题、别贴链接列表、别带 [1][2] 这种编号**；" +
            "如果查到的东西对不上他的问题，就直说没查到，不要编。"
    }

    /** 诊断用：命中原因（只记类别，不记正文）。 */
    fun reasonOf(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (QUERY_VERBS.containsMatchIn(t)) return "查询动作"
        if (FRESH_NOUNS.containsMatchIn(t)) return "时效事实"
        if (TIME_MARKERS.containsMatchIn(t) && (hasQuestion(t) || FACT_ASKS.containsMatchIn(t))) {
            return "时间+疑问"
        }
        if (hasQuestion(t) && FACT_ASKS.containsMatchIn(t)) return "事实追问"
        return null
    }
}
