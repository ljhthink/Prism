package io.prism.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 玻璃卡片 —— **已废弃（v0.4）**。
 *
 * v0.4 起实体化表面，[PrismGlassCard] 委托给 [PrismCard]（实心 surface 板），
 * 保留名称以避免改动既有调用方。新代码请直接使用 [PrismCard]。
 */
@Deprecated("Use PrismCard (v0.4 实体化表面)")
@Composable
fun PrismGlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    content: @Composable BoxScope.() -> Unit
) {
    PrismCard(modifier = modifier, shape = shape, content = content)
}