package io.prism.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismIndigoSoft
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim

/** 按钮变体 —— 设计规范 v0.4 第 9.5 节。 */
enum class PrismButtonVariant { Primary, Ghost, Danger }

/**
 * Prism 按钮 —— 设计规范 v0.4 第 9.5 节 `.btn`。
 *
 * | 变体 | 外观 |
 * |---|---|
 * | [PrismButtonVariant.Primary] | `primary` 实底 + 白字，按压 scale .98 |
 * | [PrismButtonVariant.Ghost]   | `surface` 底 + `line` 描边 + `text-dim` 字 |
 * | [PrismButtonVariant.Danger]  | 玫红透明底 + 玫红描边 + 玫红字 |
 */
@Composable
fun PrismButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PrismButtonVariant = PrismButtonVariant.Primary,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val bg: Color
    val border: Color
    val fg: Color
    when (variant) {
        PrismButtonVariant.Primary -> {
            bg = if (pressed) PrismIndigoSoft else PrismIndigo
            border = Color.Transparent
            fg = Color.White
        }
        PrismButtonVariant.Ghost -> {
            bg = if (pressed) PrismPanel2 else PrismPanel
            border = PrismLine
            fg = PrismTextDim
        }
        PrismButtonVariant.Danger -> {
            bg = PrismDanger.copy(alpha = if (pressed) 0.12f else 0.08f)
            border = PrismDanger.copy(alpha = 0.25f)
            fg = PrismDanger
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = if (pressed) 0.98f else 1f
                scaleY = if (pressed) 0.98f else 1f
            }
            .background(bg, RoundedCornerShape(11.dp))
            .border(1.dp, border, RoundedCornerShape(11.dp))
            .semantics { Role.Button }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled
            ) { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}