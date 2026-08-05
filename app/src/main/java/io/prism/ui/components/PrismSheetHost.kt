package io.prism.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 底部弹层宿主 —— 设计规范 v0.4 第 6 节 sheet 动效。
 *
 * 遮罩淡入 + [PrismSheet] 底部上滑（spring）。visible 切换时整体淡入/淡出，
 * sheet 以 slideInVertically 上移入场。点击遮罩 [onDismiss] 关闭。
 */
@Composable
fun PrismSheetHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
            // 底部弹层（上滑入场 / 下滑退出）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Transparent)
            ) {
                AnimatedVisibility(
                    visible = visible,
                    enter = slideInVertically(tween(260)) { it },
                    exit = slideOutVertically(tween(260)) { it },
                    modifier = Modifier
                ) {
                    content()
                }
            }
        }
    }
}