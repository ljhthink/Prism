package io.prism.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌（尺寸 / 间距 / 字号）—— 与 `design/prism-ui-v1/tokens.css` **逐值对应**。
 *
 * 存在理由：上一轮返工的失败根因是"各屏各自写 dp"，导致同一语义的控件在不同页面有不同尺寸。
 * 这里把设计稿的数值一次性固化，页面只能引用令牌，不能写字面量
 * （抽查方式见 `docs/design/ui-v2-screen-spec.md` 的"实测值"列）。
 */
object PrismSize {
    // ---- 间距（4 的倍数节奏） ----
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s7 = 28.dp
    val s8 = 32.dp

    /** 页面左右边距（`.scroll` 的 padding）。 */
    val page = 20.dp

    /** 标准行高（`.row` 的 min-height）。 */
    val row = 56.dp

    /** 最小触控目标。 */
    val tap = 44.dp

    // ---- 圆角 ----
    val rCard = 14.dp
    val rControl = 10.dp

    /** 模式胶囊 / 能力胶囊 / 分段按钮。 */
    val rChip = 8.dp

    /** 底部弹层顶角。 */
    val rSheet = 20.dp

    /** 输入区外壳。 */
    val rComposer = 22.dp

    /** 发送键 / 形态胶囊。 */
    val rPill = 18.dp

    // ---- 组件尺寸（照设计稿实测） ----
    /** 聊天顶栏模式胶囊高度（`.chat-head .mode-chip`）。 */
    val chipHeight = 34.dp

    /** 聊天顶栏图标按钮（`.head-actions .icon-btn`）。 */
    val headerAction = 38.dp

    /** 能力开关胶囊高度（`.cap-chip`）。 */
    val capChipHeight = 36.dp

    /** 能力开关状态灯（`.cap-chip .led`）。 */
    val led = 5.dp

    /** 输入区附件按钮（`.attach`）。 */
    val attach = 36.dp

    /** 发送键高度 / 圆角（`.send-btn`）。 */
    val sendHeight = 36.dp

    /** 输入区外壳内边距（`.composer-shell` padding）。 */
    val composerPad = 4.dp

    /** 执行轨迹左侧折线偏移（`.trace` padding-left）。 */
    val traceIndent = 12.dp

    /** 轨迹状态点（`.trace .dot`）。 */
    val traceDot = 6.dp

    /** 图标边长（线性图标统一 18dp）。 */
    val icon = 18.dp

    /** 顶栏内小图标（12–14dp）。 */
    val iconSmall = 14.dp
    val iconTiny = 12.dp
}

/** 字号与行高（对应 design 的 `--fs-*`）。 */
object PrismType {
    /** 页面标题 22sp（`.topbar h1`）。 */
    val title = 22.sp
    val titleLine = 26.sp

    /** 二级页标题 17sp（`.detail-head h1` / `.sheet h2`）。 */
    val head = 17.sp
    val headLine = 24.sp

    /** 正文 / 行标题 15sp。 */
    val body = 15.sp
    val bodyLine = 22.sp

    /** 次级 13sp（`.card-title` / 按钮文字）。 */
    val sub = 13.sp
    val subLine = 19.sp

    /** 状态行 12sp（`.row-status` / `.trace-step`）。 */
    val meta = 12.sp
    val metaLine = 17.sp

    /** 元信息 11sp（`.ai-mark` / `.source .meta`）。 */
    val micro = 11.sp
    val microLine = 15.sp
}
