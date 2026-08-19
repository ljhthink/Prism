package io.prism.phonecontrol

/**
 * 无障碍 UI 树节点（v1 US-201，LLM 操控手机）—— 纯数据模型，供序列化与工具执行。
 *
 * 由 [io.prism.phonecontrol.PhoneControlAccessibilityService] 从 AccessibilityNodeInfo
 * 映射而来；[AccessibilityUiSerializer] 将其序列化为喂给 LLM 的结构化文本。
 *
 * @property nid 节点序号（get_ui_state 输出中的 [N] 索引，供 tap(node_id) 引用）
 * @property viewId 资源 view id（如 `com.xxx:id/btn`，可为 null）
 * @property text 节点文本
 * @property contentDescription 内容描述
 * @property className 类名（判断输入框/列表等）
 * @property bounds 可视边界（点击坐标用）
 * @property clickable 可点击
 * @property longClickable 可长按
 * @property scrollable 可滚动
 * @property editable 可输入
 * @property password 密码框（敏感拦截用）
 * @property children 子节点
 */
data class UiNode(
    val nid: Int,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: IntArray?, // [left, top, right, bottom]
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val children: List<UiNode> = emptyList()
)

/**
 * UI 树序列化器（v1 US-201，纯函数可测）—— 将 [UiNode] 树转为喂给 LLM 的结构化文本。
 *
 * **输出格式**（每节点一行，带 [N] 序号与关键属性）：
 * ```
 * [0] Text="..."  desc="..."  class=android.widget.Button  bounds=[l,t,r,b]  clickable
 * [1] ...
 * ```
 * 非空文本/描述优先展示；无文本但可交互的节点也保留（仅展示 class/type/bounds）。
 *
 * **预算**：节点上限 [MAX_NODES]（防 token 溢出）；文本/描述截断 [MAX_TEXT_CHARS]。
 */
object AccessibilityUiSerializer {

    /** 单次 UI 树节点上限（防上下文膨胀，对应 get_ui_state 工具描述）。 */
    const val MAX_NODES = 80

    /** 单节点文本/描述截断上限。 */
    const val MAX_TEXT_CHARS = 60

    /**
     * 序列化整棵 UI 树（纯函数可测）。
     *
     * @param root 根节点（含 children）
     * @param maxNodes 节点上限（BFS 截断）
     * @return 结构化文本（每节点一行）；根为 null 或空树返回 null
     */
    fun serialize(root: UiNode?, maxNodes: Int = MAX_NODES): String? {
        if (root == null) return null
        val sb = StringBuilder()
        var count = 0
        // BFS（按层级遍历，节点序号稳定）
        val queue = ArrayDeque<UiNode>()
        queue.add(root)
        while (queue.isNotEmpty() && count < maxNodes) {
            val node = queue.removeFirst()
            sb.append(serializeNode(node))
                .append('\n')
            count++
            if (count < maxNodes) {
                node.children.forEach { queue.addLast(it) }
            }
        }
        val result = sb.toString().trim()
        return result.ifEmpty { null }
    }

    /**
     * 序列化单个节点为一行文本（纯函数可测）。
     *
     * @param node 节点
     * @return 一行描述
     */
    internal fun serializeNode(node: UiNode): String {
        val sb = StringBuilder()
        sb.append('[').append(node.nid).append("] ")
        val label = node.text?.take(MAX_TEXT_CHARS)?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.take(MAX_TEXT_CHARS)?.takeIf { it.isNotBlank() }
        if (label != null) sb.append("Text=\"").append(label).append("\" ")
        // 仅当无文本/描述时补类名，帮助 LLM 推断节点类型
        if (label == null && !node.className.isNullOrBlank()) {
            sb.append("class=").append(node.className.substringAfterLast('.')).append(' ')
        }
        if (!node.viewId.isNullOrBlank()) {
            sb.append("id=").append(node.viewId.substringAfterLast('/')).append(' ')
        }
        node.bounds?.let { b ->
            if (b.size == 4) sb.append("bounds=[").append(b[0]).append(',').append(b[1]).append(',').append(b[2]).append(',').append(b[3]).append("] ")
        }
        if (node.clickable) sb.append("clickable ")
        if (node.longClickable) sb.append("longClickable ")
        if (node.scrollable) sb.append("scrollable ")
        if (node.editable) sb.append("editable ")
        if (node.password) sb.append("password ")
        return sb.toString().trimEnd()
    }
}
