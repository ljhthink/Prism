package io.prism.qq

/**
 * 「纯媒体消息要不要单独回」的判定（2026-11-22 新增；ADR-080）。
 *
 * ## 为什么需要（真机证据）
 *
 * 2026-11-22 22:37:53 用户发「要去玩王者啦…」，22:37:58 补了一张表情包。
 * 结果 22:38:07 回一段、22:38:44 又回一段专门描述那张表情——
 * 这就是用户说的「一句问候 + 一个表情包，LLM 两条都会回」。
 *
 * 真人的习惯相反：**表情包是对上一条情绪的补充，不另起一轮**。
 *
 * ## 为什么不折叠图片/视频（guardrail M-03）
 *
 * 截图、照片、视频是**有信息量的东西**：他发一张截图过来是"你看这个"，不回应等于无视。
 * 折叠只对「情绪补充型」媒体生效——[FOLDABLE_KINDS]（表情包、内置表情）。
 * 图片/视频即使在 10s 窗口内也走正常回复链路。
 *
 * ## 判据
 *
 * 折叠需要同时满足：
 * 1. 批内**全部**条目都没有可见文本（纯媒体）；
 * 2. 本批媒体**全部**属于可折叠类型（表情包 / 内置表情）；
 * 3. 距上次回复不超过 [FOLD_WINDOW_MS]——超过这个窗口的贴图是"新话题的开场"，要单独回；
 * 4. 上次回复确实存在（`lastRepliedAtMs > 0`）。
 *
 * 被折叠的条目**仍然入会话历史**（调用方在生成前已 append），因此下一轮模型照样看得见
 * "他发过一张表情"，只是不会为它单独生成一条回复。
 *
 * 纯函数，便于 JVM 单测。
 */
object QqMediaFollow {

    /** 折叠窗口：上次回复后这么久内的纯媒体，视为"补一张"。 */
    const val FOLD_WINDOW_MS = 10_000L

    /**
     * 可折叠的媒体类型（情绪补充型）。
     *
     * 刻意**不含** `IMAGE` / `VIDEO`：截图与照片承载信息，必须正常回应（guardrail M-03）。
     */
    private val FOLDABLE_KINDS = setOf("STICKER", "FACE")

    /**
     * 无文本时的占位符（与 `QqInboxStore` 入队时一致）。
     *
     * **刻意用固定白名单，而不是正则「任意 1–8 字方括号内容」**（ac-verifier D-2）：
     * 后者会把 `[1]` 这类引用编号、`[笑]` 这类用户输入的真实短词也当成占位符，
     * 使「你看这个[1]」被判成"没有可见文本"。白名单漏判的代价只是"没折叠"（多回一条），
     * 而误判的代价是"该回的话不回"——宁可漏。
     */
    private val PLACEHOLDERS = setOf(
        "", "[媒体消息]", "[图片]", "[表情]", "[表情包]", "[语音]", "[视频]", "[文件]", "[动画表情]"
    )

    /**
     * 这一批是否应折叠进上一轮（不单独生成回复）。
     *
     * @param texts 本批各条消息的文本（已按到达顺序）
     * @param mediaKinds 本批全部媒体的类型名（`QqMediaKind.name`）；空 = 无媒体
     * @param nowMs 当前时刻
     * @param lastRepliedAtMs 上次成功回复的时刻（0=从未回复）
     */
    fun shouldFold(
        texts: List<String>,
        mediaKinds: List<String>,
        nowMs: Long,
        lastRepliedAtMs: Long
    ): Boolean {
        if (texts.isEmpty()) return false
        if (mediaKinds.isEmpty()) return false
        // 含图片/视频 → 不折叠（有信息量，必须回应）
        if (mediaKinds.any { it !in FOLDABLE_KINDS }) return false
        if (lastRepliedAtMs <= 0L) return false
        val gap = nowMs - lastRepliedAtMs
        if (gap < 0L || gap > FOLD_WINDOW_MS) return false
        // 全部条目都没有可见文本 → 纯媒体
        return texts.all { isInvisible(it) }
    }

    /** 文本是否"没有可见内容"（占位符或空白）。 */
    fun isInvisible(text: String): Boolean {
        val t = text.trim()
        if (t in PLACEHOLDERS) return true
        if (t.isEmpty()) return true
        // 形如 "[图片] [表情]" 的**组合占位符**：按空白切开后，每一段都必须是已知占位符。
        // 不做"删掉所有方括号内容再看剩没剩字"的正则近似——那会把 "[1]" 也吃掉（D-2）。
        val tokens = t.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return tokens.isNotEmpty() && tokens.all { it in PLACEHOLDERS }
    }

    /** 诊断日志用（只记类别与秒数，不记内容）。 */
    fun logLine(nowMs: Long, lastRepliedAtMs: Long, folded: Boolean): String {
        val gapSec = if (lastRepliedAtMs > 0L) (nowMs - lastRepliedAtMs) / 1000.0 else -1.0
        return if (folded) {
            "纯表情跟随上一条已回复消息（+%.1fs），不单独回复".format(gapSec)
        } else {
            "媒体不满足跟随条件（+%.1fs / 窗口 %.0fs 或非表情类），单独处理"
                .format(gapSec, FOLD_WINDOW_MS / 1000.0)
        }
    }
}
