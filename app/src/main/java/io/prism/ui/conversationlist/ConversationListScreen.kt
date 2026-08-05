package io.prism.ui.conversationlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.ui.components.KnowledgeGraphEmptyState

/**
 * 会话列表屏幕（骨架）—— US-005 占位。
 *
 * 本 US 不建立会话数据模型，仅展示空态（配空态插画）。
 * 会话持久化与列表渲染属后续 US（记忆 / 会话历史）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen() {
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("会话") }) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                KnowledgeGraphEmptyState(size = 180.dp)
                Text(
                    text = "暂无会话",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = "开始一段新的 AI 对话",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}