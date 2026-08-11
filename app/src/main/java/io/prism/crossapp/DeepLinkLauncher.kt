package io.prism.crossapp

import android.content.Intent
import android.net.Uri

/**
 * Deep Link 跳转 Intent 构造器（M6，ADR-016）。
 *
 * 纯函数 object，负责构造 [Intent.ACTION_VIEW] Intent，由 [CrossAppLocalToolExecutor]
 * 通过 [AppLauncherBridge.requestIntent] 发送到 UI 层执行 `launcher.launch(intent)`。
 *
 * **设计原则**（Karpathy Guidelines §2 简洁优先）：
 * - 只负责构造 Intent，不负责启动（启动由 AppLauncherBridge + UI 层处理）
 * - 纯函数，无副作用，可纯 JVM 测试（验证 Intent action/data/flags）
 *
 * **scheme 解析**（ADR-016 5.2）：
 * - [resolveAction] 从 [AppSchemeEntry.actions] 查找功能 scheme，无匹配时回退 [AppSchemeEntry.defaultAction]
 * - 参数模板替换（如 `{itemId}` → 实际值）计划在 Phase B 的 CrossAppLocalToolExecutor 中实现，
 *   届时须遵循 BR-security-006（使用 Uri.Builder 或 URLEncoder.encode 防止 Intent 注入）
 */
object DeepLinkLauncher {

    /**
     * 解析 [AppSchemeEntry] 的功能 scheme。
     *
     * @param entry App 配置条目
     * @param action 功能名（如 `scan` / `open`）；null 或空字符串时返回 [AppSchemeEntry.defaultAction]
     * @return 解析后的 scheme（如 `weixin://scanqrcode`）；action 无匹配时回退 defaultAction
     */
    fun resolveAction(entry: AppSchemeEntry, action: String?): String {
        if (action.isNullOrBlank()) return entry.defaultAction
        return entry.actions[action] ?: entry.defaultAction
    }

    /**
     * 构造 Deep Link 跳转 Intent。
     *
     * @param scheme 目标 scheme（如 `weixin://scanqrcode`）
     * @return [Intent.ACTION_VIEW] Intent，带 [Intent.FLAG_ACTIVITY_NEW_TASK]（允许从非 Activity 上下文启动）
     */
    fun buildIntent(scheme: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 构造 Deep Link 跳转 Intent（从 [AppSchemeEntry] + action 名称）。
     *
     * @param entry App 配置条目
     * @param action 功能名（如 `scan`）；null 时使用 [entry.defaultAction]
     * @return [Intent.ACTION_VIEW] Intent
     */
    fun buildIntent(entry: AppSchemeEntry, action: String?): Intent {
        val scheme = resolveAction(entry, action)
        return buildIntent(scheme)
    }
}
