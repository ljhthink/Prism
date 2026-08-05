package io.prism.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Prism 字体规范。
 *
 * 依据 Continuous-learning 知识库 `wiki/design/font-resources.md` 的选字方法论：
 * 应用为中文界面，正文推荐 **Noto Sans SC**（Google Fonts 开源，OFL 可商用，无需署名）。
 *
 * 实现说明：Android 8.0+ 系统默认 `FontFamily.SansSerif` 已自动映射为
 * Roboto（拉丁）+ **Noto Sans CJK**（中文），无需在 APK 中打包字体文件
 * （中文字体通常 5-15MB，子集化/打包会显著增大体积，见知识库「中文字体生态」）。
 * 故此处显式声明 [FontFamily.SansSerif] 并定义 Material 3 字阶，保证
 * 中文渲染走系统 Noto Sans CJK，拉丁走 Roboto，风格统一且体积最优。
 */
private val PrismFontFamily = FontFamily.SansSerif

/** Material 3 字阶（基于 M3 default typography，统一字体族与字重）。 */
val PrismTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PrismFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displaySmall = TextStyle(
        fontFamily = PrismFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = PrismFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = PrismFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = PrismFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = PrismFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PrismFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelMedium = TextStyle(
        fontFamily = PrismFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)