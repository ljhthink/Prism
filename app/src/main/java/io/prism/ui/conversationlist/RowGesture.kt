package io.prism.ui.conversationlist

/**
 * 会话行手势意图（2026-11-22，真机 B2「历史会话页无法下滑」修复）。
 *
 * 背景：本页行内左滑删除曾用 `detectDragGestures`（全向）实现，它在**越过 touch slop 的瞬间
 * 就 `consume()`**（与方向无关）。子节点先于父节点（`LazyColumn` 的 `scrollable`）拿到 `Main`
 * pass 事件，于是纵向拖动被行吞掉 → 列表无法滚动。真机对照证据：手指压在会话行上滑，`uiautomator`
 * dump 前后逐节点一致；改从组标签空隙滑，同一列表正常上移 305px。
 *
 * 修复原则：**只有水平意图才消费事件，纵向一律放行**。判定收敛到本文件的纯函数，
 * 生产手势与单测共用同一判据（避免"测试通过、真机不动"的漂移）。
 */
internal enum class RowGestureIntent {
    /** 未越 slop 的抬手：打开会话。 */
    TapOpen,

    /** 滑开态点击删除区：删除该会话。 */
    TapDelete,

    /** 滑开态点击行本体：只归位，不进会话。 */
    TapCollapse,

    /** 水平拖动占优：由行消费并跟随位移。 */
    DragHorizontal,

    /** 纵向（或方向不明）占优：**不消费**，交给 `LazyColumn` 滚动。 */
    PassThrough
}

internal object RowGesture {

    /**
     * 手势裁决（纯函数）。
     *
     * 判定顺序（不可交换）：
     * 1. 水平占优：`|dx| > slop && |dx| > |dy|` → [RowGestureIntent.DragHorizontal]
     *    （先判水平，保证"略微斜着左滑"也能开删除区；纵向必须严格更大才放行）。
     * 2. 纵向占优：`|dy| > slop && |dy| >= |dx|` → [RowGestureIntent.PassThrough]。
     * 3. 都没越 slop（含手指抖动）→ 按抬手点落在哪判定点击语义。
     *
     * @param dx 自按下以来的水平位移（左负右正）
     * @param dy 自按下以来的垂直位移
     * @param slop 系统 touch slop（`ViewConfiguration.touchSlop`）
     * @param tapX 抬手点 x（仅点击分支使用）
     * @param rowWidth 行宽（删除区贴右边，因此删除区为 `[rowWidth - deleteWidth, rowWidth]`）
     * @param deleteWidth 删除区宽度
     * @param opened 抬手时该行是否已滑开（位移小于 -0.9×删除区宽度）
     */
    fun decide(
        dx: Float,
        dy: Float,
        slop: Float,
        tapX: Float,
        rowWidth: Float,
        deleteWidth: Float,
        opened: Boolean
    ): RowGestureIntent {
        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)
        // 未滑开时只认**左滑**（dx<0）：右滑没有可露出的内容，若也吞掉会让"想在行上右滑
        // 交给列表/系统返回手势"的用户白滑一次（guardrail L-4）。
        if (opened) {
            if (absDx > slop && absDx > absDy) return RowGestureIntent.DragHorizontal
        } else if (dx < -slop && absDx > absDy) {
            return RowGestureIntent.DragHorizontal
        }
        if (absDy > slop && absDy >= absDx) return RowGestureIntent.PassThrough
        if (!opened && absDx > slop) return RowGestureIntent.PassThrough
        if (!opened) return RowGestureIntent.TapOpen
        // 滑开态：点删除区才算删除。删除区在右侧，抬手点必须落在其中，
        // 且不在行外（越界点击按收起处理，避免误删）。
        val inDeleteZone = tapX >= rowWidth - deleteWidth && tapX <= rowWidth
        return if (inDeleteZone) RowGestureIntent.TapDelete else RowGestureIntent.TapCollapse
    }

    /**
     * 行位移（纯函数）：跟随拖动并夹在 `[-deleteWidth, 0]`，右滑不能超过原位。
     *
     * `deleteWidth` 取绝对值（ac-verifier R-2）：负数会让 `coerceIn(-w, 0)` 变成
     * 空区间直接抛 `IllegalArgumentException`。生产侧 `DELETE_WIDTH` 是正常量、不可达，
     * 但纯函数不该有"靠调用方保证"的隐含前提。
     */
    fun applyDrag(current: Float, delta: Float, deleteWidth: Float): Float {
        val limit = kotlin.math.abs(deleteWidth)
        return (current + delta).coerceIn(-limit, 0f)
    }

    /** 是否为"滑开"形态（与既有 0.9 阈值一致，供无障碍节点 gate 与点击判定共用）。 */
    fun isOpened(offset: Float, deleteWidth: Float): Boolean =
        offset < -deleteWidth * 0.9f

    /** 松手后应吸附到打开位还是原位（超过 55% 即打开，与 ADR-079 记录的交互一致）。 */
    fun shouldSnapOpen(offset: Float, deleteWidth: Float): Boolean =
        -offset > deleteWidth * 0.55f
}
