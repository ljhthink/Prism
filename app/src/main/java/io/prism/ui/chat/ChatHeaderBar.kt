package io.prism.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.prism.ui.components.PrismIcons
import io.prism.ui.theme.PrismAccent
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismMotion
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismType

/**
 * 聊天页顶栏 —— 与设计稿 `chat-head`（`design/prism-ui-v1/screens.js` 的 `chatHeader()`）逐项对应。
 *
 * 布局（单行，高度约 50dp）：
 * ```
 * [图层] DeepSeek V3 [▾]  [书] 全库检索 [▾]              [麦] [钟] [笔]
 * ```
 * - 左侧两枚**模式胶囊**：34dp 高、8dp 圆角、1px 细线、14dp 线性图标 + 12sp 文字 + 10dp 下拉箭头；
 * - 右侧三个**图标动作**：38dp 热区、透明底、按下态 surface-2；
 * - 无标题、无副标题——原「Prism / 深空 AI · xxx」占 52dp，不携带信息。
 *
 * 只做展示与回调，状态仍由 `ConversationScreen` 与 ViewModel 持有（不改任何业务契约）。
 */
@Composable
fun ChatHeaderBar(
    providerName: String?,
    ragLabel: String,
    ragActive: Boolean,
    onProviderClick: () -> Unit,
    onRagClick: () -> Unit,
    onVoiceCall: () -> Unit,
    onOpenHistory: (() -> Unit)?,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PrismSize.page, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ModeChip(
            icon = PrismIcons.Layers,
            label = providerName ?: "选择端点",
            active = providerName != null,
            onClick = onProviderClick
        )
        ModeChip(
            icon = PrismIcons.Book,
            label = ragLabel,
            active = ragActive,
            onClick = onRagClick
        )
        Spacer(Modifier.weight(1f))
        HeaderAction(PrismIcons.Mic, "语音通话", onVoiceCall)
        if (onOpenHistory != null) {
            HeaderAction(PrismIcons.History, "历史会话", onOpenHistory)
        }
        HeaderAction(PrismIcons.Edit, "新对话", onNewConversation)
    }
}

/** 模式胶囊：34dp 高、8dp 圆角、1px 细线；激活态文字用琥珀，未激活用次级文本。 */
@Composable
private fun ModeChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(PrismSize.rChip)
    Row(
        modifier = Modifier
            .background(PrismPanel, shape)
            .border(1.dp, PrismLine, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .height(PrismSize.chipHeight)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) PrismAccent else PrismTextFaint,
            modifier = Modifier.size(PrismSize.iconSmall)
        )
        Text(
            text = label,
            color = if (active) PrismText else PrismTextDim,
            fontSize = PrismType.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = PrismIcons.ChevronDown,
            contentDescription = null,
            tint = PrismTextFaint,
            modifier = Modifier.size(10.dp)
        )
    }
}

/** 顶栏图标动作：38dp 热区、8dp 圆角、按下态表面色，无边框。 */
@Composable
private fun HeaderAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by androidx.compose.animation.animateColorAsState(
        targetValue = if (pressed) PrismPanel2 else Color.Transparent,
        animationSpec = PrismMotion.tap(),
        label = "headerActionBg"
    )
    Box(
        modifier = Modifier
            .size(PrismSize.headerAction)
            .background(bg, RoundedCornerShape(PrismSize.rChip))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = PrismTextDim,
            modifier = Modifier.size(20.dp)
        )
    }
}

