package io.prism.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Prism AI 头像 —— 原生矢量插画（几何线稿 + 清新填色）。
 *
 * 取代 ADR-002 原摄影位图：以 Compose [Canvas]/[Path] 程序化绘制，
 * 无限分辨率、跟随明暗主题品牌色，锚定 Prism「三棱镜折射」母题：
 * - 主色描边：正三棱镜剖面（圆头粗线）
 * - 辅色折射光束：顶部顶点射入，穿过棱镜内经折射偏折后射出
 * - 背景：primaryContainer 圆底，营造日蚀光晕感
 *
 * 线宽规范对齐 lucide/phosphor 2px 线性图标语言（见 ADR-002 插画修订）。
 */
@Composable
fun PrismAvatar(
    modifier: Modifier = Modifier,
    avatarSize: Dp = 32.dp
) {
    val strokePrimary = MaterialTheme.colorScheme.primary
    val strokeBeam = MaterialTheme.colorScheme.tertiary
    val container = MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = modifier
            .size(avatarSize)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(avatarSize * 0.22f)
        ) {
            val s = size
            val w = s.width
            val h = s.height
            val lineWidth = w * 0.09f

            // 正三棱镜剖面（顶部顶点 + 底部两角）
            val prism = Path().apply {
                moveTo(w * 0.50f, h * 0.06f)
                lineTo(w * 0.94f, h * 0.82f)
                lineTo(w * 0.06f, h * 0.82f)
                close()
            }
            drawPath(
                path = prism,
                color = strokePrimary,
                style = Stroke(
                    width = lineWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 折射光束：顶点射入 → 棱镜内偏折 → 底部偏右射出
            val beam = Path().apply {
                moveTo(w * 0.50f, h * 0.36f)
                lineTo(w * 0.50f, h * 0.52f)
                lineTo(w * 0.68f, h * 0.70f)
                lineTo(w * 0.68f, h * 0.82f)
            }
            drawPath(
                path = beam,
                color = strokeBeam,
                style = Stroke(width = lineWidth * 0.8f, cap = StrokeCap.Round)
            )

            // 折射后分光点（薄荷色小圆）—— 清新填色点缀
            drawCircle(
                color = strokeBeam,
                radius = lineWidth * 0.9f,
                center = Offset(w * 0.50f, h * 0.32f)
            )
        }
    }
}