package io.prism.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Prism 形状体系 —— 深空玻璃肌理（设计规范 v0.4 第四节）。
 *
 * v0.4 降圆角：卡片 12dp（medium/large），小组件 8dp（small），弹层底部 sheet 顶角 18dp。
 * 低圆角更「硬朗/工具感」，消除 v0.2 的 18dp 大圆角玩具感。
 */
val PrismShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(18.dp)
)