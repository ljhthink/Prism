package io.prism.ui.conversationlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RowGesture] 真值表单测（2026-11-22 真机 B2：历史会话页无法下滑）。
 *
 * 覆盖点必须包含"纵向占优一律 PassThrough（不消费）"——这是本次回归的根因；
 * 以及点击三态（打开会话 / 删除 / 收起）与位移夹取边界。
 */
class RowGestureTest {

    private val slop = 8f
    private val rowWidth = 1000f
    private val deleteWidth = 200f

    // ==================== 纵向：必须放行给 LazyColumn ====================

    @Test
    fun `纯纵向滑动不消费`() {
        assertEquals(
            RowGestureIntent.PassThrough,
            RowGesture.decide(dx = 0f, dy = 60f, slop = slop, tapX = 500f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = false)
        )
    }

    @Test
    fun `纵向略占优且带斜向抖动仍放行`() {
        // |dy| > slop 且 |dy| >= |dx| → 放行（宁可让用户滑列表，也不要吞掉滚动）
        assertEquals(
            RowGestureIntent.PassThrough,
            RowGesture.decide(dx = 40f, dy = 45f, slop = slop, tapX = 500f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = false)
        )
    }

    @Test
    fun `尚未越过 slop 不算滑动`() {
        val intent = RowGesture.decide(dx = 5f, dy = -5f, slop = slop, tapX = 500f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = false)
        assertEquals(RowGestureIntent.TapOpen, intent)
    }

    // ==================== 横向：才允许消费 ====================

    @Test
    fun `横向占优才进入拖动`() {
        assertEquals(
            RowGestureIntent.DragHorizontal,
            RowGesture.decide(dx = -60f, dy = 12f, slop = slop, tapX = 900f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = false)
        )
    }

    @Test
    fun `水平位移不足以越过 slop 不进入拖动`() {
        assertEquals(
            RowGestureIntent.TapOpen,
            RowGesture.decide(dx = -5f, dy = 2f, slop = slop, tapX = 900f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = false)
        )
    }

    @Test
    fun `水平与纵向相等时必须放行`() {
        // 边界：|dx| == |dy| → 不满足"水平严格大于"，交回列表
        assertEquals(
            RowGestureIntent.PassThrough,
            RowGesture.decide(dx = 30f, dy = 30f, slop = slop, tapX = 500f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = false)
        )
    }

    @Test
    fun `水平与纵向相等时滑开态也放行`() {
        assertEquals(
            RowGestureIntent.PassThrough,
            RowGesture.decide(dx = -30f, dy = 30f, slop = slop, tapX = 500f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = true)
        )
    }

    @Test
    fun `未滑开时右滑不消费`() {
        // guardrail L-4：右滑没有可露出的内容，交给列表/系统手势，不要白吞一次
        assertEquals(
            RowGestureIntent.PassThrough,
            RowGesture.decide(dx = 60f, dy = 4f, slop = slop, tapX = 500f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = false)
        )
    }

    @Test
    fun `滑开态右滑仍可拖动归位`() {
        assertEquals(
            RowGestureIntent.DragHorizontal,
            RowGesture.decide(dx = 60f, dy = 4f, slop = slop, tapX = 500f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = true)
        )
    }

    // ==================== 点击三态 ====================

    @Test
    fun `未滑开时点击行打开会话`() {
        assertEquals(
            RowGestureIntent.TapOpen,
            RowGesture.decide(dx = 0f, dy = 0f, slop = slop, tapX = 500f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = false)
        )
    }

    @Test
    fun `滑开态点击删除区执行删除`() {
        assertEquals(
            RowGestureIntent.TapDelete,
            RowGesture.decide(dx = 0f, dy = 0f, slop = slop, tapX = 880f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = true)
        )
    }

    @Test
    fun `滑开态点击行本体只收起`() {
        assertEquals(
            RowGestureIntent.TapCollapse,
            RowGesture.decide(dx = 0f, dy = 0f, slop = slop, tapX = 400f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = true)
        )
    }

    @Test
    fun `滑开态点击删除区右边界之外按收起处理`() {
        assertEquals(
            RowGestureIntent.TapCollapse,
            RowGesture.decide(dx = 0f, dy = 0f, slop = slop, tapX = 1200f, rowWidth = rowWidth, deleteWidth = deleteWidth, opened = true)
        )
    }

    // ==================== 位移夹取与吸附 ====================

    @Test
    fun `拖动位移夹在负删除宽度到零之间`() {
        assertEquals(-200f, RowGesture.applyDrag(0f, -500f, deleteWidth))
        assertEquals(0f, RowGesture.applyDrag(-100f, 500f, deleteWidth))
        assertEquals(-100f, RowGesture.applyDrag(-60f, -40f, deleteWidth))
    }

    @Test
    fun `负删除宽度不应抛异常`() {
        // ac-verifier R-2：coerceIn(-(-100), 0) 曾抛 IllegalArgumentException（空区间）
        assertEquals(-100f, RowGesture.applyDrag(0f, -500f, -100f))
        assertEquals(0f, RowGesture.applyDrag(0f, 500f, -100f))
    }

    @Test
    fun `吸附阈值恰好踩边界`() {
        // 阈值 0.55：-110 恰好等于（不打开），-110.1 才打开（guardrail L-10：防阈值被改成任意值仍绿）
        assertFalse(RowGesture.shouldSnapOpen(-110f, deleteWidth))
        assertTrue(RowGesture.shouldSnapOpen(-111f, deleteWidth))
        assertTrue(RowGesture.shouldSnapOpen(-200f, deleteWidth))
    }

    @Test
    fun `滑开判定恰好踩边界`() {
        // 阈值 0.9：-180 恰好等于（不算滑开），-181 才算
        assertFalse(RowGesture.isOpened(-180f, deleteWidth))
        assertTrue(RowGesture.isOpened(-181f, deleteWidth))
    }

    @Test
    fun `超过百分之五十五才吸附打开`() {
        assertTrue(RowGesture.shouldSnapOpen(-120f, deleteWidth))
        assertFalse(RowGesture.shouldSnapOpen(-100f, deleteWidth))
    }
}
