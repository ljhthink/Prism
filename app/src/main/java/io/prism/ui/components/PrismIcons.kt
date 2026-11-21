package io.prism.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Prism 线性图标集（1.5px 描边，24×24 画布）—— 与 `design/prism-ui-v1/screens.js` 的 `ICONS` **逐路径一致**。
 *
 * 为什么不用 Material 默认图标：Material `filled` 是**实心**字形，而设计稿是 1.5px 描边线性图标；
 * 混用会让顶栏出现"实心图标 + 细描边文字"的割裂（上一轮返工被用户判为不符的原因之一）。
 * 这里直接复用设计稿的 SVG path 字符串解析成 [ImageVector]，保证视觉一致。
 */
object PrismIcons {

    private fun line(
        name: String,
        vararg paths: String
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        )
        paths.forEach { d ->
            builder.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return builder.build()
    }

    /** 端点 / 图层（`.mode-chip` 左侧）。 */
    val Layers: ImageVector by lazy {
        line(
            "layers",
            "M12 4 20 8 12 12 4 8 12 4Z",
            "M4 12 12 16 20 12",
            "M4 16 12 20 20 16"
        )
    }

    /** 知识库 / 书（`.mode-chip` 左侧）。 */
    val Book: ImageVector by lazy {
        line(
            "book",
            "M5 4.5 L14 4.5 A2 2 0 0 1 16 6.5 L16 19.5 L7 19.5 A2 2 0 0 1 5 17.5 Z",
            "M8.5 8.5 L12.5 8.5",
            "M8.5 12 L12.5 12"
        )
    }

    /** 语音通话（顶栏第一个动作）。 */
    val Mic: ImageVector by lazy {
        line(
            "mic",
            "M9 7 A3 3 0 0 1 15 7 L15 11 A3 3 0 0 1 9 11 Z",
            "M6 11 A6 6 0 0 0 18 11",
            "M12 17 L12 20"
        )
    }

    /** 历史会话（顶栏第二个动作）。 */
    val History: ImageVector by lazy {
        line(
            "history",
            "M4 12 A8 8 0 1 0 6.3 6.3",
            "M4 4.5 L4 9 L8.5 9",
            "M12 8 L12 12.5 L15 14"
        )
    }

    /** 新对话（顶栏第三个动作）。 */
    val Edit: ImageVector by lazy {
        line(
            "edit",
            "M4.5 19.5 L8.5 19.5 L18.5 9.5 A2.1 2.1 0 0 0 15.5 6.5 L5.5 16.5 L5.5 19.5 Z",
            "M14.5 6.5 L17.5 9.5"
        )
    }

    /** 附件（输入区左侧「＋」）。 */
    val Plus: ImageVector by lazy {
        line("plus", "M12 5 L12 19", "M5 12 L19 12")
    }

    /** 右尖括号（设置行尾 / 知识库行尾）。 */
    val ChevronRight: ImageVector by lazy {
        line("chevron", "M9 6 L15 12 L9 18")
    }

    /** 返回（二级页顶栏）。 */
    val Back: ImageVector by lazy {
        line("back", "M15 5 L8 12 L15 19")
    }

    /** 对勾（轨迹完成态 / 选中态）。 */
    val Check: ImageVector by lazy {
        line("check", "M5 12.5 L9.5 17 L19 7")
    }

    /** 关闭 / 挂断。 */
    val Close: ImageVector by lazy {
        line("close", "M6 6 L18 18", "M18 6 L6 18")
    }

    /** AI 消息标记 `ai-mark` 的闪电（设计稿 `bolt`，13dp）。 */
    val Bolt: ImageVector by lazy {
        line("bolt", "M11.5 3 L5 13 L10 13 L9 21 L16.5 10.5 L11 10.5 L11.5 3 Z")
    }

    /** 端点模式胶囊的下拉箭头（`.mode-chip .chev`，比 [ChevronRight] 更小且朝下）。 */
    val ChevronDown: ImageVector by lazy {
        line("chevronDown", "M6 9 L12 15 L18 9")
    }
}
