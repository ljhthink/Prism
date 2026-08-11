package io.prism.crossapp

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 跨 App 安装状态检测器（M6，ADR-016）。
 *
 * 通过 [PackageManager.getPackageInfo] 检测目标 App 是否已安装。
 *
 * **设计原则**（Karpathy Guidelines §2 简洁优先 + 复用 KnowledgeBaseViewModel
 * inputStreamProvider 注入解耦模式，ADR-016 R6 缓解）：
 * - 通过 [packageChecker] 函数注入解耦 Android 框架，纯 JVM 可测
 * - 生产实现由 [fromContext] 工厂方法创建，注入真实 PackageManager
 * - 测试实现可直接构造 `AppAvailabilityChecker { pkg -> pkg in setOf(...) }`
 *
 * **Android 11+ 包可见性**（ADR-016 R1 缓解）：
 * Android 11+（API 30+）默认限制包可见性，[packageManager.getPackageInfo] 对未在
 * `<queries>` 声明的包会抛 [PackageManager.NameNotFoundException]。本类不处理此限制，
 * 由 AndroidManifest.xml 的 `<queries>` 声明保证目标 App 可见。
 */
class AppAvailabilityChecker(
    private val packageChecker: (String) -> Boolean
) {
    /**
     * 检测目标 App 是否已安装。
     *
     * @param packageName 目标 App 包名（如 `com.tencent.mm`）
     * @return true 已安装；false 未安装或不可见（Android 11+ 未声明 `<queries>`）
     */
    fun isAppInstalled(packageName: String): Boolean = packageChecker(packageName)

    /**
     * 检测 [AppSchemeEntry] 对应的 App 是否已安装。
     *
     * @param entry App 配置条目
     * @return true 已安装；false 未安装
     */
    fun isAppInstalled(entry: AppSchemeEntry): Boolean = isAppInstalled(entry.packageName)

    companion object {
        private const val TAG = "AppAvailabilityChecker"

        /**
         * 从 [Context] 创建生产实现，注入真实 [PackageManager]。
         *
         * **Android 11+ 注意**：[packageManager.getPackageInfo] 对未在 `<queries>`
         * 声明的包会抛 [PackageManager.NameNotFoundException]，本方法捕获并返回 false。
         *
         * @param context Android Context（通常为 Application）
         * @return 生产实现
         */
        fun fromContext(context: Context): AppAvailabilityChecker {
            val pm = context.packageManager
            return AppAvailabilityChecker { packageName ->
                try {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                } catch (e: Exception) {
                    // 兜底：其他异常（如 SecurityException）也视为未安装
                    Log.w(TAG, "check package availability failed: $packageName", e)
                    false
                }
            }
        }
    }
}
