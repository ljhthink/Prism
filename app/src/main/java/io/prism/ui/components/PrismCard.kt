package io.prism.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismPanel2

/**
 * Prism 实体卡片 —— 设计规范 v0.4 第 5 节 L1 表面。
 *
 * 取代 v0.2 的 [PrismGlassCard]（半透明玻璃）：改为**实心 panel 底 + 1px 描边 +
 * 内顶高光 + 投影**，去玻璃、去渐变，消除塑料感。可点击时按下态升为 [PrismPanel2]。
 */
@Composable
fun PrismCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val base = modifier
        .shadow(
            elevation = 8.dp,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.6f),
            spotColor = Color.Black.copy(alpha = 0.5f)
        )
        .background(if (pressed && onClick != null) PrismPanel2 else PrismPanel, shape)
        .border(1.dp, PrismLine, shape)
        .then(Modifier.innerTopHighlight(shape))

    val clickable = if (onClick != null) {
        base.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        base
    }

    Box(modifier = clickable, content = content)
}

/** 表面必备的内顶部 1px 高光（物理折射边缘）。 */
internal fun Modifier.innerTopHighlight(shape: RoundedCornerShape): Modifier =
    this.then(
        Modifier.background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.03f),
                    Color.White.copy(alpha = 0.0f)
                ),
                startY = 0f,
                endY = 40f
            ),
            shape = shape
        )
    )