package io.prism.phonecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccessibilityUiSerializer] 单元测试（v1 US-201，纯函数可测）。
 *
 * 覆盖 [AccessibilityUiSerializer.serializeNode] 单节点行格式（文本/类名/id/bounds/标志）
 * 与 [AccessibilityUiSerializer.serialize] 整树 BFS 序列化（节点上限 / 截断 / 空树）。
 */
class AccessibilityUiSerializerTest {

    private fun node(
        nid: Int,
        text: String? = null,
        desc: String? = null,
        className: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        children: List<UiNode> = emptyList()
    ) = UiNode(
        nid = nid, viewId = null, text = text, contentDescription = desc,
        className = className, bounds = intArrayOf(0, 0, 100, 50),
        clickable = clickable, longClickable = false, scrollable = false,
        editable = editable, password = password, children = children
    )

    // ==================== serializeNode ====================

    @Test
    fun `node with text serialized with text and bounds`() {
        val line = AccessibilityUiSerializer.serializeNode(node(3, text = "搜索"))
        assertTrue(line.startsWith("[3] "))
        assertTrue(line.contains("Text=\"搜索\""))
        assertTrue(line.contains("bounds=[0,0,100,50]"))
    }

    @Test
    fun `node without text falls back to class name`() {
        val line = AccessibilityUiSerializer.serializeNode(node(0, className = "android.widget.Button", clickable = true))
        assertTrue(line.contains("class=Button"))
        assertTrue(line.contains("clickable"))
    }

    @Test
    fun `node with flags serialized`() {
        val line = AccessibilityUiSerializer.serializeNode(
            node(1, text = "输入框", className = "android.widget.EditText", editable = true, password = true)
        )
        assertTrue(line.contains("editable"))
        assertTrue(line.contains("password"))
    }

    @Test
    fun `node with desc uses desc when no text`() {
        val line = AccessibilityUiSerializer.serializeNode(node(2, desc = "返回按钮"))
        assertTrue(line.contains("Text=\"返回按钮\""))
    }

    @Test
    fun `long text is truncated`() {
        val long = "长".repeat(100)
        val line = AccessibilityUiSerializer.serializeNode(node(0, text = long))
        // 截断上限 + 引号包裹
        assertTrue(line.length < 100 + 32)
        assertTrue(line.contains("Text=\""))
    }

    // ==================== serialize ====================

    @Test
    fun `tree serialized in BFS order`() {
        val root = node(
            0, text = "root",
            children = listOf(
                node(1, text = "child1"),
                node(2, text = "child2", children = listOf(node(3, text = "grandchild")))
            )
        )
        val out = AccessibilityUiSerializer.serialize(root)!!
        val lines = out.split('\n')
        assertTrue(lines[0].contains("Text=\"root\""))
        assertTrue(lines[1].contains("Text=\"child1\""))
        assertTrue(lines[2].contains("Text=\"child2\""))
        // BFS：grandchild 在第 4 行（child1 → child2 → grandchild）
        assertTrue(lines[3].contains("Text=\"grandchild\""))
    }

    @Test
    fun `serialize respects max nodes cap`() {
        val children = (1..200).map { node(it, text = "n$it") }
        val root = node(0, text = "root", children = children)
        val out = AccessibilityUiSerializer.serialize(root, maxNodes = 10)!!
        assertEquals(10, out.split('\n').size)
    }

    @Test
    fun `serialize returns null for null root`() {
        assertNull(AccessibilityUiSerializer.serialize(null))
    }

    @Test
    fun `blank text node still serialized with bounds but no text`() {
        // 空白文本节点：文本不展示，但 bounds/序号仍输出（保留可交互信息），结果非 null
        val blank = node(0, text = "   ", desc = "  ")
        val out = AccessibilityUiSerializer.serialize(blank)!!
        assertFalse(out.contains("Text="))
        assertTrue(out.startsWith("[0] "))
        assertTrue(out.contains("bounds="))
    }

    @Test
    fun `serialize includes nid for tap reference`() {
        val out = AccessibilityUiSerializer.serialize(node(7, text = "确定", clickable = true))!!
        assertTrue(out.startsWith("[7] "))
        assertFalse(out.contains("bounds=null"))
    }
}
