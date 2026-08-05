package io.prism.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.prism.data.ProviderConfig
import io.prism.ui.components.PrismAvatar
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismGlassCard
import io.prism.ui.components.PrismSheet
import io.prism.ui.components.PrismSheetHost
import io.prism.ui.components.PrismTopBar
import io.prism.ui.components.PrismTopBarAction
import io.prism.ui.model.ChatMessage
import io.prism.ui.model.Role
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismIndigoSoft
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint

/**
 * 聊天屏幕 —— 深空玻璃肌理（设计规范 v0.2 第 8.1 节）。
 *
 * 布局（自上而下）：
 * 1. 顶栏 [PrismTopBar]：主标题「Prism」+ 当前 Provider 副标题 + 新会话 / 能力操作
 * 2. 消息列表：AI 玻璃气泡（含引用胶囊）/ 用户靛蓝渐变气泡，入场上浮 + 瀑布错峰
 * 3. 打字指示：AI 回复中三点呼吸 + 「正在调用 MCP 检索知识库…」
 * 4. 玻璃胶囊输入框 + 靛蓝渐变圆形发送钮（带光晕）
 */
@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel = viewModel(factory = ConversationViewModel.Factory)
) {
    val messages by viewModel.messages.collectAsState()
    val isTyping by viewModel.isTyping.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val providers by viewModel.providers.collectAsState()
    var providerSelectorVisible by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrismTopBar(
                title = "Prism",
                subtitle = "深空 AI · ${activeProvider?.name ?: "未配置"}",
                actions = {
                    PrismTopBarAction(icon = { Icon(Icons.Filled.Add, null, tint = PrismTextDim) }, contentDescription = "新会话")
                    PrismTopBarAction(icon = { Icon(Icons.Filled.Bolt, null, tint = PrismTextDim) }, contentDescription = "能力")
                }
            )

            // Provider 选择器胶囊（US-007）：点击弹出切换列表
            ProviderChip(
                name = activeProvider?.name,
                onClick = { providerSelectorVisible = true }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                if (isTyping) {
                    item { TypingIndicator() }
                }
            }

            MessageInputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    viewModel.sendMessage(input)
                    input = ""
                }
            )
        }

        // Provider 切换弹层（US-007）
        PrismSheetHost(visible = providerSelectorVisible, onDismiss = { providerSelectorVisible = false }) {
            ProviderSelectorSheet(
                providers = providers,
                activeId = activeProvider?.id,
                onSelect = { viewModel.setActiveProvider(it); providerSelectorVisible = false },
                onClose = { providerSelectorVisible = false }
            )
        }
    }
}

/** 当前 Provider 胶囊 —— 点击弹出切换列表（US-007）。 */
@Composable
private fun ProviderChip(name: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PrismPanel2)
            .border(1.dp, PrismLine, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "◈", color = PrismMint, fontSize = 12.sp)
        Text(
            text = name ?: "选择 Provider",
            color = if (name != null) PrismText else PrismTextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Text(text = "切换 ▾", color = PrismTextFaint, fontSize = 10.sp)
    }
}

/** Provider 切换列表弹层（US-007）。 */
@Composable
private fun ProviderSelectorSheet(
    providers: List<ProviderConfig>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "切换 Provider",
        subtitle = "切换后保留对话历史，新消息走新 Provider"
    ) {
        if (providers.isEmpty()) {
            Text(text = "尚未配置 Provider，请到「设置」中添加并激活。", color = PrismTextFaint, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
        providers.forEach { config ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrismPanel2)
                    .border(1.dp, if (config.id == activeId) PrismMint.copy(alpha = 0.4f) else PrismLine, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(config.id) }
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "◈", color = PrismIndigo, fontSize = 14.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = config.name, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = config.baseUrl, color = PrismTextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (config.id == activeId) {
                    Text(text = "当前", color = PrismMint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/**
 * 单个消息气泡（入场上浮 + 透明度）。
 *
 * - 用户消息：右侧，靛蓝紫渐变 + 指向侧 6dp 圆角
 * - AI 消息：左侧，玻璃气泡 + [PrismAvatar] + 引用来源胶囊（[ChatMessage.source]）
 */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == Role.USER

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(
            animationSpec = tween(durationMillis = 400)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isUser) {
                PrismAvatar(
                    modifier = Modifier.padding(end = 10.dp),
                    avatarSize = 30.dp
                )
            }
            Column(
                modifier = Modifier.widthIn(max = 280.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                if (isUser) {
                    Box(
                        modifier = Modifier
                            .background(
                                Brush.linearGradient(listOf(PrismIndigo.copy(alpha = 0.45f), PrismIndigoSoft.copy(alpha = 0.35f))),
                                RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                            )
                            .border(1.dp, PrismIndigo.copy(alpha = 0.4f), RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                            .padding(horizontal = 15.dp, vertical = 11.dp)
                    ) {
                        Text(
                            text = message.content,
                            color = PrismText,
                            fontSize = 14.sp,
                            lineHeight = 23.sp
                        )
                    }
                } else {
                    PrismGlassCard(
                        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                    ) {
                        Text(
                            text = message.content,
                            color = PrismText,
                            fontSize = 14.sp,
                            lineHeight = 23.sp,
                            modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp)
                        )
                        message.source?.let { src ->
                            SourceChip(src, Modifier.padding(start = 15.dp, bottom = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 引用来源胶囊（薄荷色 + 边框，US-003 防幻觉 UI 呈现）。 */
@Composable
private fun SourceChip(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PrismMint.copy(alpha = 0.08f))
            .border(1.dp, PrismMint.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "◈  $text",
            color = PrismMint,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** AI 打字指示 —— 三点呼吸 + 状态文案。 */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        PrismAvatar(modifier = Modifier.padding(end = 10.dp), avatarSize = 30.dp)
        PrismGlassCard(
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i ->
                        val alpha = transition.animateFloat(
                            initialValue = 0.25f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 700 + i * 150, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dot$i"
                        ).value
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(PrismTextDim.copy(alpha = alpha))
                        )
                    }
                }
                Text(
                    text = "正在调用 MCP 检索知识库…",
                    color = PrismTextFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/** 底部输入栏 —— 玻璃胶囊输入框 + 靛蓝渐变圆形发送钮（带光晕）。 */
@Composable
private fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(PrismPanel, RoundedCornerShape(24.dp))
                .border(1.dp, PrismLine, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            if (value.isEmpty()) {
                Text(
                    text = "输入问题，@知识库 检索…",
                    color = PrismTextFaint,
                    fontSize = 14.sp
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = PrismText,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(PrismCyan),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(PrismIndigo, PrismIndigoSoft)),
                    CircleShape
                )
                .border(1.dp, PrismIndigo.copy(alpha = 0.5f), CircleShape)
                .clickable(enabled = value.isNotBlank()) { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "➤",
                color = Color.White,
                fontSize = 17.sp
            )
        }
    }
}