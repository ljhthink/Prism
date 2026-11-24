package io.prism.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.ui.theme.PrismAccent
import io.prism.ui.theme.PrismMotion
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismType

/**
 * 主页面顶栏 —— 设计稿 `.topbar`（`design/prism-ui-v1/app.css`）。
 *
 * 规格：高度 52dp、左右 20dp 内边距、标题 22sp / Medium / 字距 -0.2、右侧动作槽。
 * **没有副标题**：设计稿的 `.topbar` 只有 `h1` + 动作；补充信息走内容区的 `.count-line`。
 * `subtitle` 因此保留为可选（历史调用点可继续传，但不渲染独立行，避免又长回一行说明）。
 */
@Composable
fun PrismTopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = PrismSize.page),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = PrismText,
            fontSize = PrismType.title,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            letterSpacing = (-0.2f).sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PrismSize.s2)) {
            actions()
        }
    }
}

/** 顶栏图标动作 —— 设计稿 `.icon-btn`：36dp、10dp 圆角、透明底、按下态 surface-2。 */
@Composable
fun PrismTopBarAction(
    icon: @Composable () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (pressed) PrismPanel2 else Color.Transparent,
        animationSpec = PrismMotion.tap(),
        label = "topBarActionBg"
    )
    Box(
        modifier = modifier
            .size(36.dp)
            .background(bg, RoundedCornerShape(PrismSize.rControl))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semanticsLabel(contentDescription),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

/** 顶栏文字动作 —— 设计稿 `.text-action`：13sp 琥珀，右侧留白。 */
@Composable
fun PrismTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = PrismAccent
) {
    Text(
        text = text,
        color = color,
        fontSize = PrismType.sub,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp)
    )
}

/** 线性图标按钮（36dp，透明底），供各页复用同一视觉语言。 */
@Composable
fun PrismIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PrismTextDim,
    size: androidx.compose.ui.unit.Dp = 20.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (pressed) PrismPanel2 else Color.Transparent,
        animationSpec = PrismMotion.tap(),
        label = "iconButtonBg"
    )
    Box(
        modifier = modifier
            .size(36.dp)
            .background(bg, RoundedCornerShape(PrismSize.rControl))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semanticsLabel(contentDescription),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

/** 顶栏次级文字（12sp），用于 `.row-status` 一类的弱化信息。 */
@Composable
fun PrismMetaText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = PrismTextFaint, fontSize = PrismType.meta, modifier = modifier)
}

private fun Modifier.semanticsLabel(label: String?): Modifier =
    if (label == null) this else this.semantics { contentDescription = label }


