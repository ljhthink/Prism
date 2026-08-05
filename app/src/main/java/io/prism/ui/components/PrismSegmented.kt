package io.prism.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismPanel3
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim

/**
 * 分段切换 —— 设计规范 v0.4 第 9.4 节。
 *
 * v0.4：容器 `surface` 实底 + 1px 描边 + 12dp 圆角；激活项为滑动 **surface-3** 实心 thumb
 * （带内高光），文字明暗 spring 过渡。取代 v0.2 的渐变激活胶囊。
 */
@Composable
fun <T> PrismSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier
) {
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    if (options.isEmpty()) return
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(PrismPanel, RoundedCornerShape(12.dp))
            .border(1.dp, PrismLine, RoundedCornerShape(12.dp))
    ) {
        val pad = 3.dp
        val itemWidth = (maxWidth - pad * 2) / options.size
        val thumbX by animateDpAsState(
            targetValue = pad + itemWidth * selectedIndex,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
            label = "segThumb"
        )
        // 滑动 thumb（surface-3 实体块）
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(vertical = pad)
                .background(PrismPanel3, RoundedCornerShape(9.dp))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = pad)
        ) {
            options.forEachIndexed { index, option ->
                val isOn = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(option) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelOf(option),
                        color = if (isOn) PrismText else PrismTextDim,
                        fontSize = 13.sp,
                        fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}