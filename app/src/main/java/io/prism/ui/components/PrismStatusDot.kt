package io.prism.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismWarning

/** 连接状态：已连接（薄荷）/ 运行中（青，呼吸）/ 待配置（琥珀）/ 错误（玫红）。 */
enum class PrismDotState { OK, RUN, WARN, ERR }

/**
 * 状态光点 —— 设计规范 v0.2「光即信息」原则。
 *
 * 8dp 圆 + 同色光晕；[PrismDotState.RUN]（青，MCP 运行中）以 1.4s 呼吸，
 * 其余为静态发光。仅动 `alpha`，符合性能红线。
 */
@Composable
fun PrismStatusDot(
    state: PrismDotState,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp
) {
    val color: Color = when (state) {
        PrismDotState.OK -> PrismMint
        PrismDotState.RUN -> PrismCyan
        PrismDotState.WARN -> PrismWarning
        PrismDotState.ERR -> PrismDanger
    }
    val breathing = state == PrismDotState.RUN
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha = if (breathing) {
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        ).value
    } else 1f

    Canvas(
        modifier = modifier.size(size)
    ) {
        val r = this.size.minDimension / 2f
        // 光晕
        drawCircle(color.copy(alpha = 0.35f * alpha), radius = r * 2.2f)
        // 实心
        drawCircle(color.copy(alpha = alpha), radius = r)
    }
}