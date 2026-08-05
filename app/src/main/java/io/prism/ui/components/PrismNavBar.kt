package io.prism.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextFaint

/** 底部导航项。 */
data class PrismNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * 贴底实心底部导航 —— 设计规范 v0.4 第 9.1 节 `.nav`。
 *
 * v0.4 关键变更：从「浮动玻璃胶囊」改为**贴底实心底栏**（[PrismPanel] 底 + 顶部 1px 描边），
 * 回归原生 App 稳定底栏心锚。激活项图标底 [PrismPanel2] 胶囊 + 图标 [PrismIndigo]，
 * 文字/图标 spring 明暗过渡。底部预留刘海屏安全区。
 */
@Composable
fun PrismNavBar(
    items: List<PrismNavItem>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PrismPanel)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // 顶部 1px 描边（`--line`）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PrismLine)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                val selected = item.route == currentRoute
                val fg by animateColorAsState(
                    targetValue = if (selected) PrismText else PrismTextFaint,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
                    label = "navFg"
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onNavigate(item.route) }
                        .padding(top = 6.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (selected) PrismPanel2 else Color.Transparent,
                                RoundedCornerShape(13.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) PrismIndigo else fg
                        )
                    }
                    Text(
                        text = item.label,
                        color = fg,
                        fontSize = 10.sp,
                        letterSpacing = 0.2.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}