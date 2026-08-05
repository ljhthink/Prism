package io.prism.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.prism.ui.theme.PrismLineStrong
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextFaint

/**
 * 底部弹层（L3 表面）—— 设计规范 v0.4 第 5 节 / `.sheet`。
 *
 * 实心 [PrismPanel] 底 + 顶部 18dp 圆角 + 顶部 1px `line-strong` 描边 + 顶部手柄（grip）。
 * 用法：搭配上层遮罩与 `AnimatedVisibility` 实现底部上滑（见规范第 6 节动效）。
 */
@Composable
fun PrismSheet(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PrismPanel, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(top = 8.dp, bottom = 28.dp)
    ) {
        // 顶部手柄
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 40.dp, height = 4.dp)
                .background(PrismLineStrong, RoundedCornerShape(2.dp))
        )
        if (title != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            ) {
                Column(modifier = Modifier.align(Alignment.CenterStart)) {
                    Text(
                        text = title,
                        color = PrismText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = -0.2.sp
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            color = PrismTextFaint,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                headerTrailing?.invoke()
            }
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            content()
        }
    }
}