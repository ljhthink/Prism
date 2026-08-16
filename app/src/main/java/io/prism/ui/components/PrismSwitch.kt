package io.prism.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismPanel3

/**
 * 启用开关 —— 设计规范 v0.4 第 9.3 节 `.toggle`。
 *
 * 40×24 实心轨：关闭态 [PrismPanel3] + 1px 描边；开启态 [PrismIndigo] 实底。
 * 20dp 白色滑块 + spring 物理滑动。
 */
@Composable
fun PrismSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val offset by animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "switchOffset"
    )
    val trackShape = RoundedCornerShape(13.dp)

    Box(
        modifier = modifier
            .size(width = 40.dp, height = 24.dp)
            .clip(trackShape)
            .background(if (checked) PrismIndigo else PrismPanel3)
            .border(1.dp, if (checked) PrismIndigo else PrismLine, trackShape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                // BR-animation-001: spring DampingRatioMediumBouncy (ζ=0.5) 欠阻尼过冲约 16.3%，
                // checked true→false 时 offset 从 18.dp→2.dp 过冲最低值约 -0.6dp，
                // Compose padding 不允许负值会抛 IllegalArgumentException 闪退（B3 致命）。
                // coerceIn 截断过冲到非负范围，保留 spring 物理滑动视觉（设计规范 v0.4 第 9.3 节）。
                .padding(start = offset.coerceIn(0.dp, 18.dp))
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}