package io.prism.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.Space1
import kotlin.math.sin

/**
 * 深空背景 —— 三处径向光晕 + 微星点（设计规范 v0.2 第 2.1 节）。
 *
 * 背景非纯色：叠加顶部靛蓝紫、右上青、底部薄荷三处光晕，营造「深空」纵深。
 * 星点亮度在组合阶段用单一全局相位（0..1 循环）驱动，绘制阶段按每颗星
 * 的错峰偏移计算呼吸亮度，避免在 draw 作用域调用 @Composable 动画。
 * 星点为固定种子伪随机确定性分布（避免重组合抖动），仅动 `opacity`。
 */
@Composable
fun StarField(
    modifier: Modifier = Modifier,
    starCount: Int = 26
) {
    val transition = rememberInfiniteTransition(label = "starlight")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "starPhase"
    )
    val stars = remember(starCount) { StarData.generate(starCount) }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 深空基底
        drawRect(Brush.verticalGradient(listOf(Space1, Color(0xFF0A0814))))

        // 三处径向光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PrismIndigo.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(w * 0.20f, h * 0.08f),
                radius = w * 0.9f
            ),
            radius = w * 0.9f,
            center = Offset(w * 0.20f, h * 0.08f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PrismCyan.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(w * 0.95f, h * 0.10f),
                radius = w * 0.75f
            ),
            radius = w * 0.75f,
            center = Offset(w * 0.95f, h * 0.10f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PrismMint.copy(alpha = 0.09f), Color.Transparent),
                center = Offset(w * 0.50f, h * 1.05f),
                radius = w * 0.9f
            ),
            radius = w * 0.9f,
            center = Offset(w * 0.50f, h * 1.05f)
        )

        // 微星点（确定性伪随机，亮度按错峰相位呼吸）
        stars.forEachIndexed { i, s ->
            val localPhase = (phase + s.offset) - ((phase + s.offset).toInt())
            val breathe = 0.15f + (0.55f * (sin((localPhase * 2f - 1f) * 1.5708f) + 1f) / 2f)
            drawCircle(
                color = Color.White.copy(alpha = s.peakAlpha * breathe),
                radius = s.sizePx,
                center = Offset(s.x * w, s.y * h)
            )
        }
    }
}

/** 确定性星点数据（固定种子，避免重组合随机抖动）。 */
private data class Star(
    val x: Float,
    val y: Float,
    val sizePx: Float,
    val peakAlpha: Float,
    val offset: Float
)

private object StarData {
    fun generate(count: Int): List<Star> {
        var seed = 20260805L
        fun next(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val rand = ((seed ushr 33).toFloat()) / 2147483648f
            return rand * 0.5f + 0.5f
        }
        return List(count) {
            Star(
                x = next(),
                y = next(),
                sizePx = 1f + next() * 1.2f,
                peakAlpha = 0.25f + next() * 0.45f,
                offset = next()
            )
        }
    }
}