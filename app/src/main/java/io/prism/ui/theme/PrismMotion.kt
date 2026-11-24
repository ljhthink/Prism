package io.prism.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Prism 动效规范（UI v2，ADR-079 D4）。
 *
 * 一个 easing 家族 + 三档时长，避免各屏各自发挥：
 * - [PrismEaseOut]：通用进出（对应 CSS `cubic-bezier(.2,0,0,1)`）；
 * - [PrismEaseSpring]：弹层/滑块（对应 CSS `cubic-bezier(.16,1,.3,1)`）；
 * - 三段时长 [TAP_MS] / [BASE_MS] / [ENTER_MS] 与 [SHEET_MS]。
 *
 * 约束：只动画 `opacity` / `transform`（Compose 中即 `alpha` / `offset` / `scale`），
 * 不对宽高、边距、大范围模糊做动画（弱设备掉帧）。
 */
object PrismMotion {

    /** 通用进出曲线：起步快、收尾稳。 */
    val PrismEaseOut: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 弹性曲线：用于弹层上滑与开关滑块。 */
    val PrismEaseSpring: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 按压反馈。 */
    const val TAP_MS = 110

    /** 状态切换（开关轨道、Tab 下划线、颜色）。 */
    const val BASE_MS = 200

    /** 页面与列表入场。 */
    const val ENTER_MS = 280

    /** 底部弹层上滑。 */
    const val SHEET_MS = 320

    /** 通用短过渡。 */
    fun <T> tap(): androidx.compose.animation.core.TweenSpec<T> =
        tween(durationMillis = TAP_MS, easing = PrismEaseOut)

    /** 状态切换过渡。 */
    fun <T> base(): androidx.compose.animation.core.TweenSpec<T> =
        tween(durationMillis = BASE_MS, easing = PrismEaseOut)

    /** 入场过渡。 */
    fun <T> enter(): androidx.compose.animation.core.TweenSpec<T> =
        tween(durationMillis = ENTER_MS, easing = PrismEaseOut)

    /** 弹层上滑。 */
    fun <T> sheet(): androidx.compose.animation.core.TweenSpec<T> =
        tween(durationMillis = SHEET_MS, easing = PrismEaseSpring)

    /** 开关滑块：轻微回弹但不过冲。 */
    fun <T> thumb(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    /** 列表错峰入场的单条间隔。 */
    const val STAGGER_MS = 16

    /** 运行态循环（呼吸灯/三点/光标）的基础周期。 */
    const val LOOP_MS = 1400

    /** 兼容：旧代码引用的线性缓动改为统一曲线。 */
    val LegacyEase: Easing = FastOutSlowInEasing
}
