package io.prism.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import io.prism.ui.theme.PrismAccent
import io.prism.ui.theme.PrismLineStrong
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismType

/**
 * 单选选项行（设计稿 `.option` + `.ring` + `.check`）：
 * 18dp 空心圆环（选中琥珀描边 + 8dp 实心点）+ 名称 + 一行说明 + 右侧勾。
 *
 * 为什么单独成组件：语音引擎 / 档位 / 播报方式 / 首选引擎 / 桌宠大小等 7 处都是同一形态，
 * 各页各写一份会出现"同一语义三种观感"（ADR-079 背景所述的结构性问题）。
 */
@Composable
fun PrismOptionRow(
    label: String,
    description: String? = null,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.45f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PrismSize.s3)
        ) {
            // `.ring`：18dp 空心圆环；选中时描边琥珀 + 8dp 实心点（`.ring::after`）
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .alpha(alpha)
                    .border(
                        width = 1.dp,
                        color = if (selected) PrismAccent else PrismLineStrong,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(modifier = Modifier.size(8.dp).background(PrismAccent, CircleShape))
                }
            }
            Column(modifier = Modifier.weight(1f).alpha(alpha)) {
                Text(
                    text = label,
                    color = if (enabled) PrismText else PrismTextDim,
                    fontSize = PrismType.body
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = PrismTextFaint,
                        fontSize = PrismType.micro,
                        lineHeight = PrismType.microLine,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = PrismIcons.Check,
                    contentDescription = "已选中",
                    tint = PrismAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        PrismHairline()
    }
}

/**
 * 通用单选弹层：标题 + 副标题 + 若干 [PrismOptionRow] + 「取消」。
 *
 * 只做选择，不做保存：选中即回调并关闭（与设计稿 `pick-provider` 同语义），
 * 需要在关闭前校验的场景由调用方自行用 [PrismSheet] 组装。
 *
 * **必须有「取消」**（guardrail 2026-11-21 建议 4）：遮罩是不可见的裸 `clickable`，
 * TalkBack 用户没有其它可达的关闭路径——只靠点遮罩等于对无障碍用户关不上。
 */
@Composable
fun PrismChoiceSheet(
    title: String,
    subtitle: String? = null,
    options: List<String>,
    selectedIndex: Int,
    descriptions: List<String?> = emptyList(),
    onSelect: (Int) -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    PrismSheet(title = title, subtitle = subtitle) {
        options.forEachIndexed { index, label ->
            PrismOptionRow(
                label = label,
                description = descriptions.getOrNull(index),
                selected = index == selectedIndex,
                onClick = { onSelect(index) }
            )
        }
        if (onDismiss != null) {
            Spacer(Modifier.height(PrismSize.s4))
            PrismButton(
                text = "取消",
                variant = PrismButtonVariant.Ghost,
                onClick = onDismiss
            )
        }
        Spacer(Modifier.height(PrismSize.s4))
    }
}
