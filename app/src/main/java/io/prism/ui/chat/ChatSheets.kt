package io.prism.ui.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.data.ProviderConfig
import io.prism.rag.RagTarget
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismIcons
import io.prism.ui.components.PrismSheet
import io.prism.ui.theme.PrismAccent
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismLineStrong
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismType

/**
 * 切换端点弹层 —— 对应设计稿 `provider-list`（`design/prism-ui-v1/screens.js:879`）。
 *
 * 与旧实现的差别（旧实现是「带边框卡片 + ◈ 字符 + 当前标签」）：
 * - 行式 `.option`：54dp 行高、底部 1px 分隔线，**没有卡片**；
 * - 左侧 18dp 空心圆环（`.ring`），选中态圆环变琥珀并在行尾显示琥珀对勾；
 * - 名称 15sp，下方等宽字体 `baseUrl · model`（`.url` 用 `--mono`）；
 * - 底部 ghost「＋ 新建自定义 Provider」+ 脚注 `note-line`。
 *
 * **不伪造能力**：`onCreateCustom` 为 null 时不渲染新建按钮（聊天页当前没有创建 Provider 的入口，
 * 创建在设置页）。宁可少一个按钮，也不放一个点了没反应的按钮。
 */
@Composable
fun ProviderListSheet(
    providers: List<ProviderConfig>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: () -> Unit,
    onCreateCustom: (() -> Unit)? = null,
    modelOf: (ProviderConfig) -> String? = { it.models.firstOrNull()?.takeIf { m -> m.isNotBlank() } }
) {
    PrismSheet(
        title = "切换端点",
        subtitle = "已配置 ${providers.size} 个端点 · 点击激活"
    ) {
        if (providers.isEmpty()) {
            Text(
                text = "尚未配置端点，请到「设置 → 模型与密钥」添加。",
                color = PrismTextFaint,
                fontSize = PrismType.meta,
                lineHeight = PrismType.metaLine
            )
            Spacer(Modifier.height(PrismSize.s4))
        }
        providers.forEach { config ->
            val active = config.id == activeId
            OptionRow(
                selected = active,
                onClick = { onSelect(config.id) }
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.name,
                        color = if (active) PrismText else PrismTextDim,
                        fontSize = PrismType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val model = modelOf(config)
                    Text(
                        text = if (model != null) "${config.baseUrl} · $model" else config.baseUrl,
                        color = PrismTextFaint,
                        fontSize = PrismType.micro,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        if (onCreateCustom != null) {
            Spacer(Modifier.height(PrismSize.s4))
            PrismButton(
                text = "＋ 新建自定义 Provider",
                variant = PrismButtonVariant.Ghost,
                onClick = onCreateCustom,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(PrismSize.s3))
        Text(
            text = "从预设添加：OpenAI 兼容 / Anthropic / Ollama / Moonshot / OpenRouter",
            color = PrismTextFaint,
            fontSize = PrismType.micro,
            lineHeight = PrismType.microLine
        )
        Spacer(Modifier.height(PrismSize.s3))
        PrismButton(
            text = "关闭",
            variant = PrismButtonVariant.Ghost,
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 知识库检索弹层 —— 对应设计稿 `rag-mode`（`design/prism-ui-v1/screens.js:1074`）。
 *
 * 三态单选：关闭 / 全库 / 指定库。指定库只在调用方给出库清单时出现
 * （聊天页当前无库清单来源 → 只显示前两项，与真实可选项一致）。
 */
@Composable
fun RagModeSheet(
    current: RagTarget,
    onSelect: (RagTarget) -> Unit,
    onClose: () -> Unit,
    libraries: List<Pair<Long, String>> = emptyList()
) {
    PrismSheet(
        title = "知识库检索",
        subtitle = "关闭后 AI 不会主动查资料"
    ) {
        OptionRow(selected = current is RagTarget.Off, onClick = { onSelect(RagTarget.Off) }) {
            Column(modifier = Modifier.weight(1f)) {
                Text("关闭", color = PrismText, fontSize = PrismType.body)
                Text(
                    "不使用知识库",
                    color = PrismTextFaint,
                    fontSize = PrismType.micro,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        OptionRow(selected = current is RagTarget.AllLibraries, onClick = { onSelect(RagTarget.AllLibraries) }) {
            Column(modifier = Modifier.weight(1f)) {
                Text("全库", color = PrismText, fontSize = PrismType.body)
                Text(
                    "在所有分库中检索",
                    color = PrismTextFaint,
                    fontSize = PrismType.micro,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        libraries.forEach { (id, name) ->
            val selected = (current as? RagTarget.SpecificLibrary)?.kbId == id
            OptionRow(selected = selected, onClick = { onSelect(RagTarget.SpecificLibrary(id)) }) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("指定库 · $name", color = PrismText, fontSize = PrismType.body)
                    Text(
                        "只查这一个库",
                        color = PrismTextFaint,
                        fontSize = PrismType.micro,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(PrismSize.s4))
        PrismButton(
            text = "关闭",
            variant = PrismButtonVariant.Ghost,
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * `.option` 行：54dp 行高 + 底部 1px 分隔线 + 左侧 18dp 圆环 + 行尾选中对勾。
 *
 * 圆环选中时描边变琥珀；对勾用 [PrismIcons.Check]（线性 16dp），不用字符 ✓。
 */
@Composable
private fun OptionRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PrismSize.s3)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(
                        width = 1.dp,
                        color = if (selected) PrismAccent else PrismLineStrong,
                        shape = CircleShape
                    )
                    .background(if (selected) PrismAccent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
            )
            content()
            if (selected) {
                Icon(
                    imageVector = PrismIcons.Check,
                    contentDescription = "已选中",
                    tint = PrismAccent,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Spacer(Modifier.width(16.dp))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PrismLine)
        )
    }
}
