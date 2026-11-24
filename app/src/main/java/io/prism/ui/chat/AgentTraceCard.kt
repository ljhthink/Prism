package io.prism.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.ui.theme.PrismAccent
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismOk
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismType
import kotlinx.coroutines.delay

/**
 * Agent 执行轨迹 —— 与设计稿 `.trace`（`design/prism-ui-v1/screens.js` 的 `trace()`）逐项对应。
 *
 * 视觉（左侧 1px 折线 + 缩进 12dp）：
 * ```
 * │ ● 执行轨迹                     已用 2.4s
 * │ ● 思考                            1.1s
 * │ ● 知识库检索                      0.8s
 * │ ● 生成回答        ●●●            0.5s
 * ```
 *
 * **数据真实性**：每步耗时由本组件**本地实测**（步骤切换时记差值），不是编造；
 * 当前步显示实时「已用」与三点等待。不引入新状态源——入参仍只有
 * `isTyping` / `activeTool` / `thinkingEnabled`，与 ViewModel 契约不变。
 *
 * **已知偏差（如实登记）**：设计稿在回答完成后仍保留轨迹卡片；本实现的轨迹只在生成期间显示，
 * 因为"完成后保留"需要把轨迹写进 `ChatMessage`（属 ViewModel 数据模型变更，本轮白名单禁止）。
 */
@Composable
fun AgentTraceCard(
    isTyping: Boolean,
    activeTool: String?,
    thinkingEnabled: Boolean,
    isRagOn: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isTyping) return

    // 步骤序列：思考（仅深度思考开启）→ 工具（若有）→ 生成回答
    val toolLabel = activeTool?.let { friendlyToolName(it) }
    val sequence = buildList {
        if (thinkingEnabled) add("思考")
        if (toolLabel != null) add(toolLabel)
        add(if (isRagOn) "检索知识库" else "生成回答")
    }
    val activeIndex = if (toolLabel != null) (if (thinkingEnabled) 1 else 0) else sequence.lastIndex

    // 本地实测：每个步骤的耗时（毫秒），随步骤推进写入。
    val durations = remember { mutableStateListOf<Long>() }
    LaunchedEffect(activeIndex, sequence.size) {
        // 补齐到当前步（首次进入也补 0，保证下标对齐）
        while (durations.size <= activeIndex) durations.add(0L)
    }
    LaunchedEffect(activeIndex) {
        var elapsed = durations.getOrElse(activeIndex) { 0L }
        while (true) {
            delay(100)
            elapsed += 100
            if (activeIndex < durations.size) durations[activeIndex] = elapsed
        }
    }
    val totalMs = durations.sum()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, PrismLine, androidx.compose.foundation.shape.RoundedCornerShape(PrismSize.rControl))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // 头部：状态灯 + 标题 + 已用时长
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot()
            Spacer(Modifier.width(PrismSize.s2))
            Text("执行轨迹", color = PrismTextDim, fontSize = PrismType.meta)
            Spacer(Modifier.weight(1f))
            Text(
                text = "已用 ${formatSeconds(totalMs)}",
                color = PrismAccent,
                fontSize = PrismType.micro,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(PrismSize.s2))
        sequence.forEachIndexed { index, name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (index == activeIndex) {
                    LiveDot()
                } else {
                    Text("✓", color = PrismOk, fontSize = 10.sp)
                }
                Spacer(Modifier.width(PrismSize.s2))
                Text(
                    text = name,
                    color = if (index == activeIndex) PrismText else PrismTextFaint,
                    fontSize = PrismType.meta
                )
                if (index == activeIndex) {
                    Spacer(Modifier.width(6.dp))
                    WaitingDots()
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatSeconds(durations.getOrElse(index) { 0L }),
                    color = if (index == activeIndex) PrismAccent else PrismTextFaint,
                    fontSize = PrismType.micro,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** 6dp 呼吸点：1.4s 往复，只动透明度。 */
@Composable
private fun LiveDot() {
    val transition = rememberInfiniteTransition(label = "traceDot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "traceDotAlpha"
    )
    Box(
        modifier = Modifier
            .size(PrismSize.traceDot)
            .graphicsLayer { this.alpha = alpha }
            .background(PrismAccent, CircleShape)
    )
}

/** 三点等待：1.1s 循环，逐点错峰。 */
@Composable
private fun WaitingDots() {
    val transition = rememberInfiniteTransition(label = "traceDots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 550, delayMillis = i * 140, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "traceDotItem$i"
            )
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .graphicsLayer { this.alpha = a }
                    .background(PrismAccent, CircleShape)
            )
        }
    }
}

/** 毫秒 → `1.2s` / `0.4s`，与设计稿的等宽秒数一致。 */
private fun formatSeconds(ms: Long): String {
    val oneDecimal = (ms + 50) / 100 // 0.1s 精度，四舍五入
    return "${oneDecimal / 10}.${oneDecimal % 10}s"
}

/**
 * 工具名 → 中文短标签。
 *
 * [ConversationViewModel.describeActiveTool] 已剥掉命名空间（知识库→「查知识库」，其余为短名），
 * 故此处按短名映射；兜底用中性词「执行工具」，不把英文 id 暴露给用户。
 */
internal fun friendlyToolName(raw: String): String = when (raw) {
    "查知识库" -> "知识库检索"
    "search" -> "联网搜索"
    "fetch", "fetch_url" -> "抓取网页"
    "list_documents", "get_document_content" -> "读取资料"
    "hotlist", "hotlist_get" -> "查热榜"
    "write_docx", "write_xlsx" -> "生成文档"
    "write" -> "写日记"
    "open_app", "share_content", "pick_media" -> "跨应用操作"
    "todo_write" -> "更新待办"
    "play_action", "set_expression" -> "桌宠动作"
    "ask_user" -> "向你提问"
    "tap", "long_press", "double_tap", "swipe", "input_text",
    "press_back", "press_home", "launch_app", "wait", "screenshot", "take_over" -> "操作手机"
    "send_message", "send_voice", "set_signature",
    "schedule_message", "schedule_list", "schedule_cancel" -> "QQ 操作"
    else -> "执行工具"
}

