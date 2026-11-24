package io.prism.ui.conversationlist

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.prism.PrismApplication
import io.prism.data.Session
import io.prism.ui.components.PrismIconButton
import io.prism.ui.components.PrismIcons
import io.prism.ui.theme.PrismBg
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismType
import io.prism.util.ChatMessageSerializer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 会话历史（设计稿 `history` / `historyRow`，`design/prism-ui-v1/screens.js`）。
 *
 * 结构完全照设计稿：
 * ```
 * ← 历史会话                                     [笔]
 * 3 段会话 · 按最近更新排序
 * 今天
 *   标题                                   时间 · 12 条
 *   标题                                   昨天 · 4 条
 * 更早
 *   ...
 * ```
 * - 行式 `.row`：最小高 56dp、底部 1px 分隔线、无卡片；
 * - 标题 15sp，状态 12sp「相对时间 · N 条」；
 * - **左滑删除**（用户 2026-11-20 明确要求替代文字按钮，属设计稿的有意偏差）：
 *   滑开露出 danger 区，超过 55% 或快速滑动即吸附打开，点击即删。
 *
 * 空态：64dp 圆形图标 + 「还没有历史会话 / 开始一段对话后会出现在这里」。
 *
 * @param onBack 返回聊天页
 * @param onSessionSelected 选中会话
 * @param onCreateNew 新建对话（顶栏笔形按钮）；null 时不渲染该按钮
 */
@Composable
fun ConversationListScreen(
    onBack: () -> Unit,
    onSessionSelected: (Long) -> Unit,
    onCreateNew: (() -> Unit)? = null
) {
    val app = LocalContext.current.applicationContext as? PrismApplication
    val repository = remember { app?.sessionRepository }
    val sessions: List<Session> = repository?.sessions?.collectAsState()?.value ?: emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        DetailHead(onBack = onBack, onCreateNew = onCreateNew)

        if (sessions.isEmpty()) {
            EmptyHistory()
        } else {
            // 消息条数：解析一次存进 Map，避免每帧重复解码（行数少，成本可接受）。
            val counts = remember(sessions) {
                sessions.associate { it.id to messageCountOf(it.messagesJson) }
            }
            val groups = remember(sessions) { groupByDay(sessions) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = PrismSize.page,
                    end = PrismSize.page,
                    top = PrismSize.s2,
                    bottom = PrismSize.s6
                )
            ) {
                item(key = "count") {
                    CountLine("${sessions.size} 段会话 · 按最近更新排序")
                }
                groups.forEach { (label, list) ->
                    item(key = "g-$label") { GroupLabel(label) }
                    items(list, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            messageCount = counts[session.id] ?: 0,
                            onClick = { onSessionSelected(session.id) },
                            onDelete = { repository?.remove(session.id) }
                        )
                    }
                }
            }
        }
    }
}

/** 二级页顶栏 —— 设计稿 `.detail-head`：52dp 高、返回 + 标题 17sp + 右侧动作。 */
@Composable
private fun DetailHead(onBack: () -> Unit, onCreateNew: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = PrismSize.page),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PrismSize.s1)
    ) {
        PrismIconButton(
            icon = PrismIcons.Back,
            contentDescription = "返回",
            onClick = onBack
        )
        Text(
            text = "历史会话",
            color = PrismText,
            fontSize = PrismType.head,
            modifier = Modifier.weight(1f)
        )
        if (onCreateNew != null) {
            PrismIconButton(
                icon = PrismIcons.Edit,
                contentDescription = "新对话",
                onClick = onCreateNew
            )
        }
    }
}

/** 设计稿 `.count-line`：11sp 弱化、上 8 下 16 间距。 */
@Composable
private fun CountLine(text: String) {
    Text(
        text = text,
        color = PrismTextFaint,
        fontSize = PrismType.micro,
        modifier = Modifier.padding(top = PrismSize.s2, bottom = PrismSize.s4)
    )
}

/** 设计稿 `.group-label`：12sp 弱化 + 下 8dp。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        color = PrismTextFaint,
        fontSize = PrismType.meta,
        modifier = Modifier.padding(top = PrismSize.s5, bottom = PrismSize.s2)
    )
}

/**
 * 单行会话 —— 行式 + 左滑删除。
 *
 * **手势实现（单一状态源）**：位移只有一个 `Animatable<Float>`。
 * 拖动期间 `snapTo`（跟手、无动画）；松手时按 55% 阈值 `animateTo` 到打开位或 0。
 * 任何"纵向占优"的移动都立即退出本手势且不消费事件，交由父级 `LazyColumn` 滚动
 * （2026-11-22 真机 B2：旧实现用全向 `detectDragGestures`，越过 slop 即 consume，
 * 子节点先于 LazyColumn 拿到 Main pass 事件 → 纵向拖动被吃掉，表现为历史页无法下滑）。
 */
@Composable
private fun SessionRow(
    session: Session,
    messageCount: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember(session.id) { Animatable(0f) }
    val deleteWidthPx = with(LocalDensity.current) { DELETE_WIDTH.toPx() }

    // 手势挂在**未位移的容器**上，由 [RowGesture] 纯函数裁决意图（2026-11-22 真机 B2 修复）。
    //
    // 为什么必须挂在容器而不是行本体：行本体带 `graphicsLayer { translationX }`，滑开时它把
    // **命中区域**一起左移了——露出来的删除区（屏幕右侧 76dp）在行的坐标系里已被移出边界，
    // 真机实测「滑开后点删除」完全无反应（事件落到无手势的绘制层，谁都不接）。
    // 容器不位移，因此删除区的点击坐标始终正确。
    //
    // 曾经的两处错误实现（都已删除，勿回退）：
    // ① 两个叠放的 clickable：依赖 z 序与位移后的命中语义，真机出现"点删除却进会话"；
    // ② 容器 detectTapGestures + 行 detectDragGestures：后者全向检测，**越过 slop 就 consume**，
    //    子节点先于 LazyColumn 拿到 Main pass 事件 → 纵向拖动被吃 → 历史列表无法下滑。
    // 现在：只有水平占优才消费，纵向一律放行（判据见 [RowGesture.decide]）。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(session.id, deleteWidthPx) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var claimed = false
                    // 本地账本（guardrail L-1）：`offsetX.value` 的写入经
                    // `scope.launch` 排队，快速甩动时最后几帧可能还没落地，
                    // 直接读它做阈值判定会"甩到 90% 却弹回"。判定一律用本账本。
                    var applied = offsetX.value
                    var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                up = change
                                break
                            }
                            // 必须先取位移再 consume（consume 后 positionChange 归零）
                            val delta = change.positionChange()
                            dx += delta.x
                            dy += delta.y
                            if (!claimed) {
                                when (
                                    RowGesture.decide(
                                        dx = dx,
                                        dy = dy,
                                        slop = slop,
                                        tapX = change.position.x,
                                        rowWidth = size.width.toFloat(),
                                        deleteWidth = deleteWidthPx,
                                        opened = RowGesture.isOpened(applied, deleteWidthPx)
                                    )
                                ) {
                                    RowGestureIntent.DragHorizontal -> claimed = true
                                    // 纵向占优：立刻退出本手势、不消费任何事件，LazyColumn 接管滚动
                                    RowGestureIntent.PassThrough -> return@awaitEachGesture
                                    else -> Unit
                                }
                            }
                            if (claimed) {
                                change.consume()
                                applied = RowGesture.applyDrag(applied, delta.x, deleteWidthPx)
                                // AwaitPointerEventScope 是受限挂起作用域，不能直接调
                                // Animatable 的挂起成员；用组合作用域提交（按事件顺序排队）。
                                val target = applied
                                scope.launch { offsetX.snapTo(target) }
                            }
                        }
                        if (claimed) {
                            val open = RowGesture.shouldSnapOpen(applied, deleteWidthPx)
                            scope.launch { offsetX.animateTo(if (open) -deleteWidthPx else 0f) }
                        } else if (up != null && !up.isConsumed) {
                            when (
                                RowGesture.decide(
                                    dx = dx,
                                    dy = dy,
                                    slop = slop,
                                    tapX = up.position.x,
                                    rowWidth = size.width.toFloat(),
                                    deleteWidth = deleteWidthPx,
                                    opened = RowGesture.isOpened(applied, deleteWidthPx)
                                )
                            ) {
                                RowGestureIntent.TapDelete -> onDelete()
                                RowGestureIntent.TapCollapse ->
                                    scope.launch { offsetX.animateTo(0f) }
                                RowGestureIntent.TapOpen -> onClick()
                                else -> Unit
                            }
                        }
                    } finally {
                        // guardrail L-2：手势被取消（组合离开 / key 变化 / 系统打断）时，
                        // 半开的行必须归位，否则下次进入该行会停在错位状态。
                        if (claimed && applied != 0f && up == null) {
                            scope.launch { offsetX.animateTo(0f) }
                        }
                    }
                }
            }
    ) {
        // 视觉层：删除区（仅绘制，不接管手势）
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(DELETE_WIDTH)
                .height(PrismSize.row)
                .background(PrismDanger.copy(alpha = 0.14f), RoundedCornerShape(PrismSize.rControl)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = PrismIcons.Close,
                contentDescription = null,
                tint = PrismDanger,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("删除", color = PrismDanger, fontSize = PrismType.sub)
        }

        // 视觉层：行本体（只跟随位移；手势在未位移的容器上，见上）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX.value }
                .background(PrismBg)
                .height(PrismSize.row),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle(session),
                    color = PrismText,
                    fontSize = PrismType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatRelativeTime(session.updatedAt)} · $messageCount 条",
                    color = PrismTextFaint,
                    fontSize = PrismType.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // 分隔线（随行位移）
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .graphicsLayer { translationX = offsetX.value }
                .height(1.dp)
                .background(PrismLine)
        )

        // 无障碍：只有滑开时才存在「删除会话」节点，且挂真实点击动作。
        // 行本身暴露两个动作：打开会话 / 删除（后者供 TalkBack 自定义操作兜底）。
        val opened = RowGesture.isOpened(offsetX.value, deleteWidthPx)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(DELETE_WIDTH)
                .height(PrismSize.row)
                .then(
                    if (opened) {
                        Modifier.semantics {
                            contentDescription = "删除会话"
                            onClick(label = "删除") {
                                onDelete()
                                true
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        )
    }
}

/** 空态 —— 设计稿 `.empty` + `.empty-mark`：64dp 圆、26dp 图标、标题 17sp。 */
@Composable
private fun EmptyHistory() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 56.dp, start = PrismSize.page, end = PrismSize.page),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(PrismPanel2)
                .border(1.dp, PrismLine, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = PrismIcons.History,
                contentDescription = null,
                tint = PrismTextFaint,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("还没有历史会话", color = PrismTextDim, fontSize = PrismType.head)
        Spacer(Modifier.height(6.dp))
        Text("开始一段对话后会出现在这里", color = PrismTextFaint, fontSize = PrismType.sub)
    }
}

private val DELETE_WIDTH = 76.dp

/** 标题兜底：会话标题为空时用首条消息或占位词，绝不渲染空行。 */
private fun displayTitle(session: Session): String =
    session.title.trim().takeIf { it.isNotBlank() } ?: "未命名会话"

/** 消息条数（解析失败按 0 —— 列表不因单条脏数据崩溃）。 */
private fun messageCountOf(json: String): Int =
    runCatching { ChatMessageSerializer.decodeList(json).size }.getOrDefault(0)

/**
 * 按自然日分组（今天 / 昨天 / 更早）。
 *
 * 设计稿写的是 `items.slice(0, 2)` 的静态切分；真实数据必须按**实际日期**分，
 * 否则「今天」里会出现三天前的会话——那才是新的状态撒谎。
 */
private fun groupByDay(sessions: List<Session>): List<Pair<String, List<Session>>> {
    val todayStart = startOfDay(System.currentTimeMillis())
    val yesterdayStart = todayStart - 86_400_000L
    val today = mutableListOf<Session>()
    val yesterday = mutableListOf<Session>()
    val earlier = mutableListOf<Session>()
    sessions.forEach { s ->
        when {
            s.updatedAt >= todayStart -> today += s
            s.updatedAt >= yesterdayStart -> yesterday += s
            else -> earlier += s
        }
    }
    return buildList {
        if (today.isNotEmpty()) add("今天" to today)
        if (yesterday.isNotEmpty()) add("昨天" to yesterday)
        if (earlier.isNotEmpty()) add("更早" to earlier)
    }
}

private fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * 相对时间（对齐设计稿的短形态：刚刚 / x 分钟前 / x 小时前 / 昨天 / M月d日）。
 *
 * 组标签已表达「今天/昨天」，行内遇到昨天仍显示「昨天」——与设计稿 `historyRow`
 * 一致（标签是分组，行内是绝对参照，两处不冲突）。
 */
private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 172_800_000L -> "昨天"
        else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(timestamp))
    }
}

/** 保留给旧调用点的等宽时间格式（弹层里展示精确时间时使用）。 */
internal fun formatExactTime(timestamp: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(timestamp))



