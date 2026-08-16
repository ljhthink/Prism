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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite

/**
 * 底部弹层宿主 —— 设计规范 v0.4 第 6 节 sheet 动效。
 *
 * 遮罩淡入 + [PrismSheet] 底部上滑（spring）。visible 切换时整体淡入/淡出，
 * sheet 以 slideInVertically 上移入场。点击遮罩 [onDismiss] 关闭。
 *
 * BR-ui-002：sheet 最大高度限制为**可用空间** 90%，配合 [PrismSheet] 内的 verticalScroll，
 * 防止内容超长时按钮被裁剪到屏幕外不可见（DEF-001 根因）。
 *
 * **UXR8 Bug3 + OBS-2 修复（ADR-028，约束探针像素证据见
 * docs/reports/2026-08-16-uxr8-b1-bug3-obs2-debug.md）**：
 *
 * IME 适配必须**双模式自适应**，因为 Android 存在两种互斥的键盘处理机制：
 *
 * 1. **resize 模式**（adjustResize 的 window resize 生效，本 app 模拟器实测）：
 *    ComposeView 在键盘弹出时已被系统压缩（实测约束 2340px→1146px）。
 *    此时 `WindowInsets.ime` **仍报告键盘完整物理高度**（912px），
 *    若再套 `imePadding()` 会**双重扣除**（1146−912=234px），弹层塌缩至窄带
 *    —— 即 OBS-2「内容列塌陷」根因。
 * 2. **insets 模式**（decorFitsSystemWindows=false 完全生效的设备）：
 *    window 不压缩，约束≈全屏，必须 `imePadding()` 单一来源平移到键盘上方。
 *
 * 判定式（约束探针实测标定）：`parentMax < screen − ime/2` → resize 模式。
 * 两模式差距 >600px（234 vs 2208），阈值容差充足。
 *
 * `navigationBarsPadding()` 独立在 BoxWithConstraints 外层：两模式均实际扣除
 * 导航栏高度（resize 模式实测仍读 48dp，1146−132=1014px 数值吻合，
 * 见 debug 报告附带发现），弹层底部落于键盘+导航栏上方，无双重扣除风险。
 */
@Composable
fun PrismSheetHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val imeHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
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
            // navigationBarsPadding 先行：insets 模式扣导航栏；resize 模式自动读 0（自适应）。
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            ) {
                // 父级实际可用高度（resize 模式下已含系统扣除；约束无限时回退全屏）
                val parentMax = if (maxHeight.isFinite) maxHeight else screenHeight
                // OBS-2 根因修复：判定 IME 是否已被外层（window resize）扣除。
                // 无限约束（不可判定）时保守假设已扣除（不加 imePadding，防双重扣除回归）
                val imeAppliedByParent = if (maxHeight.isFinite) {
                    with(density) {
                        maxHeight.roundToPx() <
                            screenHeight.roundToPx() - imeHeight.roundToPx() / 2
                    }
                } else {
                    true
                }
                // BR-ui-002：maxSheetHeight 基于实际可用空间 90%（最终下限 240dp）
                val maxSheetHeight = (
                    (if (imeAppliedByParent) parentMax else parentMax - imeHeight) * 0.9f
                    ).coerceAtLeast(240.dp)
                Box(
                    modifier = Modifier
                        // resize 模式：外层已扣 IME，不再 imePadding（防双重扣除）；
                        // insets 模式：imePadding 单一来源平移到键盘上方。
                        .then(if (imeAppliedByParent) Modifier else Modifier.imePadding())
                        .heightIn(max = maxSheetHeight)
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
}
