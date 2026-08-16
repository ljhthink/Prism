package io.prism.ui.conversationlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.PrismApplication
import io.prism.data.Session
import io.prism.ui.components.KnowledgeGraphEmptyState
import io.prism.ui.components.PrismTopBarAction
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话历史列表屏幕（UX-001 问题 4，ADR-021）。
 *
 * 从 [PrismApplication.sessionRepository] 订阅会话列表（按 updatedAt 倒序，最新在前），
 * 每条会话展示：
 * - 标题（持久化时由首条用户消息自动生成，截断到 50 字符）
 * - 相对时间（刚刚 / x 分钟前 / x 小时前 / 昨天 / 日期）
 * - 删除按钮（内层 [clickable] 消费点击，不触发外层选择）
 *
 * 点击会话 → [onSessionSelected]（由导航层负责 `loadSession` + 返回聊天页）。
 * 删除会话 → 直接经仓库 [io.prism.data.SessionRepository.remove] 删除，列表自动刷新。
 *
 * 空态：无会话时展示 [KnowledgeGraphEmptyState] 插画 + 「暂无会话」文案。
 * 配色遵循 Prism 深空玻璃规范（PrismPanel / PrismPanel2 / PrismLine / PrismText 等）。
 *
 * @param onBack 返回上一级（聊天页）回调
 * @param onSessionSelected 点击会话回调，参数为会话 id（Long）
 */
@Composable
fun ConversationListScreen(
    onBack: () -> Unit,
    onSessionSelected: (Long) -> Unit
) {
    val app = LocalContext.current.applicationContext as? PrismApplication
    val repository = remember { app?.sessionRepository }
    val sessions: List<Session> = repository?.sessions?.collectAsState()?.value ?: emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：返回按钮 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrismTopBarAction(
                icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = PrismTextDim) },
                contentDescription = "返回",
                onClick = onBack
            )
            Text(
                text = "历史会话",
                color = PrismText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp
            )
        }

        if (sessions.isEmpty()) {
            // 空态：插画 + 「暂无会话」
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    KnowledgeGraphEmptyState(size = 180.dp)
                    Text(
                        text = "暂无会话",
                        fontSize = 16.sp,
                        color = PrismTextDim,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Text(
                        text = "开始一段新的 AI 对话",
                        fontSize = 14.sp,
                        color = PrismTextFaint,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionListItem(
                        session = session,
                        onClick = { onSessionSelected(session.id) },
                        onDelete = { repository?.remove(session.id) }
                    )
                }
            }
        }
    }
}

/**
 * 单个会话列表项 —— 实心卡片（标题 + 相对时间 + 删除按钮）。
 *
 * 外层 [clickable] 触发会话选择；删除按钮为内层独立 [clickable]，
 * Compose 内层点击会被内层消费，不会误触外层选择。
 */
@Composable
private fun SessionListItem(
    session: Session,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PrismPanel)
            .border(1.dp, PrismLine, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title,
                color = PrismText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatRelativeTime(session.updatedAt),
                color = PrismTextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PrismPanel2)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDelete
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除会话",
                tint = PrismTextFaint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 相对时间格式化（对齐主流 AI 助手的「刚刚 / x 分钟前」惯例）。
 *
 * - <1 分钟：刚刚
 * - <1 小时：x 分钟前
 * - <1 天：x 小时前
 * - <2 天：昨天
 * - 更早：`M月d日 HH:mm`（跨年份会话可能年份混淆，历史列表场景可接受）
 */
private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 172_800_000L -> "昨天"
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
