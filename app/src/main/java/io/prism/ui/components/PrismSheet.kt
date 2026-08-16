package io.prism.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
 *
 * BR-ui-002：内容区使用 `weight(1f, fill = false)` + `verticalScroll`，
 * 当 sheet 内容超出 [PrismSheetHost] 限制的最大高度时自动滚动，
 * 防止底部按钮（如"保存配置"）被裁剪到屏幕外不可见（DEF-001 根因）。
 *
 * BR-ui-003：新增 `footer` 参数用于固定底部区域（不参与滚动），
 * 将关键操作按钮（如"保存配置"）放在 footer 中确保始终可见。
 *
 * @param footer 固定底部区域，不参与滚动。适合放置关键操作按钮（如"保存配置"）。
 */
@Composable
fun PrismSheet(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
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
        // BR-ui-002：weight(1f, fill=false) 让内容区在 sheet 高度受限时获得剩余空间，
        // verticalScroll 确保超出部分可滚动；内容少时不强制填满（fill=false）。
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState())
        ) {
            content()
        }
        // BR-ui-003：footer 固定在底部，不参与滚动，确保关键操作按钮始终可见。
        if (footer != null) {
            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                footer()
            }
        }
    }
}