package io.prism.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.skill.TodoListState
import io.prism.skill.TodoLocalToolExecutor
import io.prism.ui.theme.PrismCyan
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint

/**
 * TODO 任务清单卡片（v1 批次17 US-1703，ADR-043 D1）。
 *
 * 展示 LLM 通过 todo_write 维护的任务计划：进度 n/m + 三态图标（○ 待办 / ◐ 进行中 / ✓ 完成）
 * + 进行中步骤高亮。置于输入区上方（CapabilityToggleRow 之上），按 [TodoListState.version]
 * 原地更新，不新增聊天气泡；清单为空时不渲染（由调用方判断）。
 */
@Composable
fun TodoCard(state: TodoListState, modifier: Modifier = Modifier) {
    if (state.items.isEmpty()) return
    val done = state.items.count { it.status == TodoLocalToolExecutor.STATUS_COMPLETED }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PrismPanel2)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🗒",
                fontSize = 13.sp,
                color = PrismCyan
            )
            Text(
                text = "  任务计划（$done/${state.items.size}）",
                color = PrismText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(6.dp))
        state.items.forEach { item ->
            val inProgress = item.status == TodoLocalToolExecutor.STATUS_IN_PROGRESS
            val completed = item.status == TodoLocalToolExecutor.STATUS_COMPLETED
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        completed -> "✓"
                        inProgress -> "◐"
                        else -> "○"
                    },
                    color = when {
                        completed -> PrismMint
                        inProgress -> PrismCyan
                        else -> PrismTextFaint
                    },
                    fontSize = 12.sp,
                    fontWeight = if (inProgress) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = if (inProgress && item.activeForm.isNotBlank()) item.activeForm else item.content,
                    color = when {
                        completed -> PrismTextFaint
                        inProgress -> PrismTextDim
                        else -> PrismText
                    },
                    fontSize = 12.sp,
                    fontWeight = if (inProgress) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
