package io.prism.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.prism.ui.theme.PrismAccent
import io.prism.ui.theme.PrismBg
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismLineStrong
import io.prism.ui.theme.PrismMotion
import io.prism.ui.theme.PrismOk
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismType
import io.prism.ui.theme.PrismWarning

/**
 * Prism UI 基础件 —— 与 `design/prism-ui-v1/app.css` 的结构化类**一一对应**。
 *
 * 为什么单独成文件：设置 5 个区域 + 能力 3 个面板 + 十余个弹层共用同一套
 * `.group` / `.row` / `.kv` / `.card` / `.status-strip` / `.seg` / `.danger-row` 语言。
 * 之前每个页面各自拼 Row/Column，导致"同一语义三种观感"——这是上一轮被判定
 * 与设计稿不符的结构性原因。
 *
 * 所有尺寸取 [PrismSize] / [PrismType] 令牌，禁止页面内写字面量。
 */

/** 状态点（`.dot` / `.dot.ok` / `.dot.warn`）：6dp。 */
enum class DotTone { Neutral, Ok, Warn, Danger, Live }

@Composable
fun PrismDot(tone: DotTone = DotTone.Neutral, size: androidx.compose.ui.unit.Dp = 6.dp) {
    val color = when (tone) {
        DotTone.Neutral -> PrismTextFaint
        DotTone.Ok -> PrismOk
        DotTone.Warn -> PrismWarning
        DotTone.Danger -> PrismDanger
        DotTone.Live -> PrismAccent
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(color, CircleShape)
    )
}

/** 分组（`.group` + `.group-label`）。[label] 为 null 时不渲染标签，只留间距。 */
@Composable
fun PrismGroup(
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = PrismSize.s6)) {
        if (label != null) {
            Text(
                text = label,
                color = PrismTextFaint,
                fontSize = PrismType.meta,
                modifier = Modifier.padding(bottom = PrismSize.s2)
            )
        }
        content()
    }
}

/**
 * 标准行（`.row`）：最小高 56dp、标题 15sp + 状态 12sp、尾部控件、底部 1px 分隔线。
 *
 * [tone] 用于把状态文字染色（如 QQ 已开启 → Ok），[statusTone] 单独控制状态文字色。
 */
@Composable
fun PrismRow(
    title: String,
    status: String? = null,
    onClick: (() -> Unit)? = null,
    statusTone: Color? = null,
    leadingDot: DotTone? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PrismSize.row)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick
                        )
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leadingDot != null) {
                        PrismDot(leadingDot)
                        Spacer(Modifier.width(PrismSize.s2))
                    }
                    Text(
                        text = title,
                        color = PrismText,
                        fontSize = PrismType.body,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (status != null) {
                    Text(
                        text = status,
                        color = statusTone ?: PrismTextFaint,
                        fontSize = PrismType.meta,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = trailing
            )
        }
        PrismHairline()
    }
}

/** 1px 分隔线（`border-bottom: 1px solid var(--line)`）。 */
@Composable
fun PrismHairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(PrismLine)
    )
}

/** 键值行（`.kv`）：11dp 上下、底部分隔线、左键右值。 */
@Composable
fun PrismKv(
    key: String,
    value: String,
    mono: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = key, color = PrismTextFaint, fontSize = PrismType.sub)
            Spacer(Modifier.width(PrismSize.s4))
            Text(
                text = value,
                color = PrismText,
                fontSize = PrismType.sub,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = if (mono) FontFamily.Monospace else null,
                modifier = Modifier.weight(1f)
            )
        }
        PrismHairline()
    }
}

/** 卡片（`.card` + `.card-head` + `.card-note`）。 */
@Composable
fun PrismCardBox(
    title: String? = null,
    headMeta: String? = null,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PrismPanel, RoundedCornerShape(PrismSize.rCard))
            .border(1.dp, PrismLine, RoundedCornerShape(PrismSize.rCard))
            .padding(PrismSize.s4)
    ) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = PrismSize.s2),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(text = title, color = PrismTextDim, fontSize = PrismType.sub)
                if (headMeta != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = headMeta,
                        color = PrismTextDim,
                        fontSize = PrismType.meta,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        if (note != null) {
            Text(
                text = note,
                color = PrismTextFaint,
                fontSize = PrismType.meta,
                lineHeight = PrismType.metaLine
            )
        }
        content()
    }
}

/** 状态条（`.status-strip` + `.status-cell`）：三格等分、细线分隔。 */
@Composable
fun PrismStatusStrip(cells: List<Triple<String, String, DotTone>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PrismPanel, RoundedCornerShape(PrismSize.rCard))
            .border(1.dp, PrismLine, RoundedCornerShape(PrismSize.rCard))
    ) {
        cells.forEachIndexed { index, (key, value, tone) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(PrismSize.s3),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(text = key, color = PrismTextFaint, fontSize = PrismType.micro)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrismDot(tone)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = value,
                        color = PrismText,
                        fontSize = PrismType.meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (index < cells.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(56.dp)
                        .background(PrismLine)
                )
            }
        }
    }
}

/**
 * 下划线分段（`.segmented` + `.seg` + `.seg-line`）。
 *
 * 与旧的实底胶囊分段的差别：设计稿是**文字 + 底部琥珀细线**，不是彩色块。
 */
@Composable
fun PrismSegUnderline(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PrismSize.page),
            horizontalArrangement = Arrangement.spacedBy(PrismSize.s5)
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val alpha by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = PrismMotion.base(),
                    label = "segLine$index"
                )
                Column(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(index) }
                        )
                        .padding(top = 14.dp, bottom = 15.dp)
                ) {
                    Text(
                        text = label,
                        color = if (selected) PrismText else PrismTextFaint,
                        fontSize = PrismType.sub
                    )
                    // 下划线：用独立 1dp Box 的 alpha 过渡，不做布局动画
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(PrismAccent.copy(alpha = alpha))
                    )
                }
            }
        }
        PrismHairline()
    }
}

/** 危险行（`.danger-row`）：上下细线、左侧 danger 文字、48dp 高。 */
@Composable
fun PrismDangerRow(text: String, onClick: () -> Unit) {
    Column {
        PrismHairline()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(text = text, color = PrismDanger, fontSize = PrismType.sub)
        }
        PrismHairline()
    }
}

/** 动作行（`.actions-row`）：ghost 小按钮横向排列。 */
@Composable
fun PrismActionsRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = PrismSize.s3),
        horizontalArrangement = Arrangement.spacedBy(PrismSize.s2),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** 小按钮（`.mini` / `.btn-sm`）：34dp 高、8dp 圆角、透明底。 */
@Composable
fun PrismMini(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    icon: ImageVector? = null
) {
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .background(Color.Transparent, RoundedCornerShape(PrismSize.rChip))
            .border(
                1.dp,
                if (danger) PrismDanger.copy(alpha = 0.3f) else PrismLineStrong,
                RoundedCornerShape(PrismSize.rChip)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) PrismDanger else PrismTextDim,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = text,
            color = if (danger) PrismDanger else PrismTextDim,
            fontSize = PrismType.sub
        )
    }
}

/** 脚注（`.footnote`）：11sp faint、上 20dp、行高 1.6。 */
@Composable
fun PrismFootnote(text: String) {
    Text(
        text = text,
        color = PrismTextFaint,
        fontSize = PrismType.micro,
        lineHeight = PrismType.microLine,
        modifier = Modifier.padding(top = PrismSize.s5)
    )
}

/** 说明行（`.note-line`）：11sp faint、上 8dp。 */
@Composable
fun PrismNoteLine(text: String) {
    Text(
        text = text,
        color = PrismTextFaint,
        fontSize = PrismType.micro,
        lineHeight = PrismType.microLine,
        modifier = Modifier.padding(top = PrismSize.s2)
    )
}

/** 主按钮（`.btn-primary`）：白底深墨字、44dp 高、10dp 圆角。 */
@Composable
fun PrismPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .height(PrismSize.tap)
            .background(
                if (enabled) PrismText else PrismPanel,
                RoundedCornerShape(PrismSize.rControl)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) PrismBg else PrismTextFaint,
            fontSize = PrismType.body,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
    }
}

/** 次按钮（`.btn-ghost`）：透明底 + line-strong 描边 + 正文色。 */
@Composable
fun PrismGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .height(PrismSize.tap)
            .background(Color.Transparent, RoundedCornerShape(PrismSize.rControl))
            .border(
                1.dp,
                if (enabled) PrismLineStrong else PrismLine,
                RoundedCornerShape(PrismSize.rControl)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) PrismText else PrismTextFaint,
            fontSize = PrismType.body
        )
    }
}

