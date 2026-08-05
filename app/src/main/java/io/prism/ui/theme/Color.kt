package io.prism.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Prism 品牌色板 —— AI 科技感（靛蓝紫 + 薄荷青）。
 *
 * 品牌配色取自 Continuous-learning 知识库 `wiki/design/color-resources.md` 推荐的
 * colorhunt 方案（4 色和谐 + WCAG AA 对比度 ≥4.5:1 校验），锚定配色方案：
 * **Mint Saber Neon**（<https://colorhunt.co/palette/211951836fff15f5baf0f3ff>，实时 6k 赞，AI 专用标签）：
 * - `#836FFF` 靛蓝紫 —— AI 智能主色
 * - `#15F5BA` 薄荷青 —— 对话/活力辅色
 * - `#211951` 深靛蓝 —— 暗色主题基色
 * - `#F0F3FF` 亮白 —— 浅色主题基色
 *
 * 说明：colorhunt 原始色值饱和度偏高，直接用于正文/按钮时部分不满足 WCAG AA
 * （如 `#836FFF` 与白字对比仅约 3.7:1）。故按 Material 3 语义色需要对主/辅色做
 * 明度微调以保证可读性，色相仍忠实于原配色。模板来源见 ADR-002。
 */
// ---- Light ----
val PrismPrimary = Color(0xFF5B4BE0) // 靛蓝紫主色（Mint Saber #836FFF 加深，AA 达标）
val PrismOnPrimary = Color(0xFFFFFFFF)
val PrismPrimaryContainer = Color(0xFFE4DFFF)
val PrismOnPrimaryContainer = Color(0xFF1A1060)
val PrismSecondary = Color(0xFF00A8CC) // 青蓝辅色（对话/活力）
val PrismOnSecondary = Color(0xFFFFFFFF)
val PrismSecondaryContainer = Color(0xFFC2F1FF)
val PrismOnSecondaryContainer = Color(0xFF00414D)
val PrismTertiary = Color(0xFF00A876) // 薄荷青点缀（Mint Saber #15F5BA 调深）
val PrismOnTertiary = Color(0xFFFFFFFF)
val PrismTertiaryContainer = Color(0xFFBFF7E9)
val PrismOnTertiaryContainer = Color(0xFF00382B)
val PrismBackground = Color(0xFFFBF8FF)
val PrismOnBackground = Color(0xFF1B1B20)
val PrismSurface = Color(0xFFFBF8FF)
val PrismOnSurface = Color(0xFF1B1B20)
val PrismSurfaceVariant = Color(0xFFE6E0EC)
val PrismOnSurfaceVariant = Color(0xFF49454F)
val PrismOutline = Color(0xFF7A757F)
val PrismOutlineVariant = Color(0xFFCAC4D0)
val PrismError = Color(0xFFB3261E)
val PrismOnError = Color(0xFFFFFFFF)

// ---- Dark ----
val PrismDarkPrimary = Color(0xFFC9BFFF)
val PrismDarkOnPrimary = Color(0xFF2E1E6E)
val PrismDarkPrimaryContainer = Color(0xFF4A3B99)
val PrismDarkOnPrimaryContainer = Color(0xFFE4DFFF)
val PrismDarkSecondary = Color(0xFF6ED7FF)
val PrismDarkOnSecondary = Color(0xFF003548)
val PrismDarkSecondaryContainer = Color(0xFF004E66)
val PrismDarkOnSecondaryContainer = Color(0xFFC2F1FF)
val PrismDarkTertiary = Color(0xFF60D9B0) // 薄荷青暗色点缀
val PrismDarkOnTertiary = Color(0xFF00382B)
val PrismDarkTertiaryContainer = Color(0xFF005238)
val PrismDarkOnTertiaryContainer = Color(0xFF7DF6C9)
val PrismDarkBackground = Color(0xFF141218)
val PrismDarkOnBackground = Color(0xFFE6E1E9)
val PrismDarkSurface = Color(0xFF141218)
val PrismDarkOnSurface = Color(0xFFE6E1E9)
val PrismDarkSurfaceVariant = Color(0xFF49454F)
val PrismDarkOnSurfaceVariant = Color(0xFFCAC4D0)
val PrismDarkOutline = Color(0xFF948F99)
val PrismDarkOutlineVariant = Color(0xFF49454F)
val PrismDarkError = Color(0xFFFFB4AB)
val PrismDarkOnError = Color(0xFF690005)

// ---------------------------------------------------------------------------
// 深空玻璃肌理（Deep Space Glass v0.4）—— 深色专属扩展色板（ADR 设计规范 v0.4）
// 锚定 HTML 原型 deep-space-glass-prototype.html 的 CSS 变量，Compose 直接映射。
// 设计为深空深色主题，不随系统明暗切换。
//
// v0.4 关键变更：实体化表面（去半透明玻璃）、近黑冷灰背景、沉稳化主色。
// ---------------------------------------------------------------------------

/** 屏底（最深层）—— `--bg`。 */
val PrismBg = Color(0xFF0C0C11)

/** L1 卡片 / 底栏 / 输入 —— `--surface`。 */
val PrismPanel = Color(0xFF14141A)

/** L2 浮层 / hover / 图标底 —— `--surface-2`。 */
val PrismPanel2 = Color(0xFF1A1A22)

/** L3 弹层 / 分段 thumb / 开关轨 —— `--surface-3`。 */
val PrismPanel3 = Color(0xFF20202A)

/** 品牌强调色（光）—— 靛蓝紫 `--primary`。 */
val PrismIndigo = Color(0xFF6E62FF)

/** 靛蓝紫深一档（按压态）—— `--primary-strong`。 */
val PrismIndigoSoft = Color(0xFF5A4EFF)

/** 功能光 —— 青（MCP 运行中 / 知识检索 / 连接光）`--cyan`。 */
val PrismCyan = Color(0xFF22C7E0)

/** 功能光 —— 薄荷（成功 / 已连接 / 引用来源）`--mint`。 */
val PrismMint = Color(0xFF2FBF8F)

/** 功能光 —— 琥珀（需配置 / 待处理）`--warning`。 */
val PrismWarning = Color(0xFFD99A2B)

/** 功能光 —— 玫红（错误 / 连接失败）`--danger`。 */
val PrismDanger = Color(0xFFE5484D)

/** 主文本。 */
val PrismText = Color(0xFFEAEAF0)

/** 次级文本。 */
val PrismTextDim = Color(0xFFA0A0AC)

/** 弱化 / 占位 / 摘要。 */
val PrismTextFaint = Color(0xFF6E6E7A)

/** 描边分隔 `--line`（rgba(255,255,255,.07)）。 */
val PrismLine = Color(0x12FFFFFF)

/** 强调描边 / 输入框 `--line-strong`（rgba(255,255,255,.12)）。 */
val PrismLineStrong = Color(0x1FFFFFFF)

// ---------------------------------------------------------------------------
// 向后兼容别名（已废弃，v0.4 起使用上述 v0.4 语义色）
// ---------------------------------------------------------------------------

/** @deprecated v0.4 起使用 [PrismBg]。 */
@Deprecated("Use PrismBg")
val Space0 = PrismBg

/** @deprecated v0.4 起使用 [PrismBg]。 */
@Deprecated("Use PrismBg")
val Space1 = PrismBg

/** @deprecated v0.4 起使用 [PrismPanel2]。 */
@Deprecated("Use PrismPanel2")
val Space2 = PrismPanel2

/** @deprecated v0.4 起使用 [PrismLine]。 */
@Deprecated("Use PrismLine")
val PrismGlass = PrismLine

/** @deprecated v0.4 起使用 [PrismLineStrong]。 */
@Deprecated("Use PrismLineStrong")
val PrismGlassStrong = PrismLineStrong