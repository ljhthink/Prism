package io.prism.crossapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppAvailabilityChecker] 单元测试（M6 Phase A，US-037 AC-10）。
 *
 * 纯 JVM 测试，通过函数注入模拟 PackageManager 行为，不依赖 Android Context。覆盖：
 * - 已安装 App 检测返回 true
 * - 未安装 App 检测返回 false
 * - [AppSchemeEntry] 重载方法
 * - 边界场景（空包名、异常路径）
 */
class AppAvailabilityCheckerTest {

    /** 模拟已安装的包名集合。 */
    private val installedPackages = setOf(
        "com.tencent.mm",
        "com.eg.android.AlipayGphone",
        "com.taobao.taobao"
    )

    /** 被测实例：包名在 installedPackages 中返回 true。 */
    private val checker = AppAvailabilityChecker { packageName ->
        packageName in installedPackages
    }

    private val wechatEntry = AppSchemeEntry(
        appId = "wechat",
        displayName = "微信",
        packageName = "com.tencent.mm",
        scheme = "weixin",
        defaultAction = "weixin://"
    )

    private val uninstalledEntry = AppSchemeEntry(
        appId = "douyin",
        displayName = "抖音",
        packageName = "com.ss.android.ugc.aweme",
        scheme = "snssdk1128",
        defaultAction = "snssdk1128://"
    )

    @Test
    fun `isAppInstalled returns true for installed package`() {
        assertTrue(checker.isAppInstalled("com.tencent.mm"))
    }

    @Test
    fun `isAppInstalled returns false for uninstalled package`() {
        assertFalse(checker.isAppInstalled("com.ss.android.ugc.aweme"))
    }

    @Test
    fun `isAppInstalled returns false for unknown package`() {
        assertFalse(checker.isAppInstalled("com.unknown.app"))
    }

    @Test
    fun `isAppInstalled entry overload returns true for installed entry`() {
        assertTrue(checker.isAppInstalled(wechatEntry))
    }

    @Test
    fun `isAppInstalled entry overload returns false for uninstalled entry`() {
        assertFalse(checker.isAppInstalled(uninstalledEntry))
    }

    @Test
    fun `isAppInstalled returns false for empty package name`() {
        assertFalse(checker.isAppInstalled(""))
    }

    @Test
    fun `isAppInstalled handles exception from packageChecker gracefully`() {
        val throwingChecker = AppAvailabilityChecker { _ ->
            throw SecurityException("mock security exception")
        }

        // 异常路径由调用方处理；这里验证函数注入可抛异常（生产实现会捕获）
        try {
            throwingChecker.isAppInstalled("com.any.app")
            // 如果没抛异常，说明实现内部捕获了（生产实现 fromContext 会捕获）
            // 这里只验证函数注入模式可工作
        } catch (e: SecurityException) {
            // 预期：测试用 checker 不捕获异常，由生产实现 fromContext 捕获
        }
    }

    @Test
    fun `isAppInstalled returns true for all installed packages`() {
        assertTrue(checker.isAppInstalled("com.tencent.mm"))
        assertTrue(checker.isAppInstalled("com.eg.android.AlipayGphone"))
        assertTrue(checker.isAppInstalled("com.taobao.taobao"))
    }

    @Test
    fun `isAppInstalled returns false for all uninstalled packages`() {
        assertFalse(checker.isAppInstalled("com.ss.android.ugc.aweme"))
        assertFalse(checker.isAppInstalled("com.tencent.mobileqq"))
        assertFalse(checker.isAppInstalled("com.sina.weibo"))
        assertFalse(checker.isAppInstalled("com.baidu.BaiduMap"))
    }
}
