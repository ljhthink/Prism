package io.prism.crossapp

import kotlinx.serialization.Serializable

/**
 * 跨 App 调用兼容性清单条目（M6，ADR-016）。
 *
 * 对应 `assets/cross-app/app_schemes.json` 中的一项配置，描述单个目标 App 的
 * scheme / 包名 / 可用 action / 网页 fallback 等元信息。
 *
 * **配置驱动**（ADR-016 R1 缓解）：scheme 兼容性可能随目标 App 版本变化，
 * 通过 JSON 配置文件驱动便于维护与未来远程更新（复用 M4 SkillDownloader 基建）。
 *
 * @property appId 稳定标识符（如 `wechat`），供 LLM 工具参数引用
 * @property displayName 用户可见名称（如 `微信`），用于确认对话框展示
 * @property packageName 目标 App 包名（如 `com.tencent.mm`），用于安装检测与 `<queries>` 声明
 * @property scheme URL Scheme 前缀（如 `weixin`），用于 `<queries>` intent 过滤器声明
 * @property defaultAction 默认跳转 scheme（如 `weixin://`），无指定 action 时使用
 * @property actions 功能 scheme 映射（如 `scan` → `weixin://scanqrcode`），key 为功能名
 * @property fallbackUrl 未安装时的网页 fallback URL；null 表示无 fallback
 * @property queryScheme `<queries>` 中声明的 scheme（通常与 [scheme] 一致）
 */
@Serializable
data class AppSchemeEntry(
    val appId: String,
    val displayName: String,
    val packageName: String,
    val scheme: String,
    val defaultAction: String,
    val actions: Map<String, String> = emptyMap(),
    val fallbackUrl: String? = null,
    val queryScheme: String = scheme
)
