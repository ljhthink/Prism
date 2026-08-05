package io.prism.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Prism 深空玻璃主题 —— 深色专属（设计规范 v0.2）。
 *
 * 取代原明暗双色板：深空玻璃肌理为深色设计，固定 `darkColorScheme`，
 * 不随系统明暗切换。背景/表面使用 [Space1] 深空基底，
 * 品牌强调色靛蓝紫 [PrismIndigo]、功能光青 [PrismCyan] / 薄荷 [PrismMint] / 玫红 [PrismDanger]。
 *
 * 依据 Continuous-learning 知识库 `wiki/design/color-resources.md` 选色方法论，
 * 主文本对深空基底对比度约 12:1、次级文本约 6:1，均满足 WCAG AA。
 */
@Composable
fun PrismTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PrismDeepSpaceColorScheme,
        typography = PrismTypography,
        shapes = PrismShapes,
        content = content
    )
}

/** 深空玻璃暗色板（[darkTheme] 恒为 true，深空设计不随系统切换）。 */
private val PrismDeepSpaceColorScheme = darkColorScheme(
    primary = PrismIndigo,
    onPrimary = Color.White,
    primaryContainer = PrismPanel2,
    onPrimaryContainer = PrismText,
    secondary = PrismCyan,
    onSecondary = Color.White,
    secondaryContainer = PrismPanel2,
    onSecondaryContainer = PrismText,
    tertiary = PrismMint,
    onTertiary = Color.White,
    tertiaryContainer = PrismMint.copy(alpha = 0.12f),
    onTertiaryContainer = PrismMint,
    background = PrismBg,
    onBackground = PrismText,
    surface = PrismBg,
    onSurface = PrismText,
    surfaceVariant = PrismPanel2,
    onSurfaceVariant = PrismTextDim,
    outline = PrismLine,
    outlineVariant = PrismLine,
    error = PrismDanger,
    onError = Color.White,
    errorContainer = PrismDanger.copy(alpha = 0.14f),
    onErrorContainer = PrismDanger
)