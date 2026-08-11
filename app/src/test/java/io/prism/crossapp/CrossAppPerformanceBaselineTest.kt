package io.prism.crossapp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 Phase A 性能基线测试（TKN-M6-PHASE-A-ACCEPTANCE-001）。
 *
 * Phase A 为新增模块，无既有基线。对核心方法执行计时测试生成初版基线。
 * 基线数据将记录在 acceptance 报告中，供后续 Phase B/C 对比。
 *
 * 纯 JVM 测试，不依赖 Android 框架（除 android.util.Log stub）。
 */
class CrossAppPerformanceBaselineTest {

    /** 7 App 完整配置 JSON（与生产 app_schemes.json 内容一致） */
    private val fullConfigJson = """
        [
          {"appId":"wechat","displayName":"微信","packageName":"com.tencent.mm","scheme":"weixin","defaultAction":"weixin://","actions":{"open":"weixin://","scan":"weixin://scanqrcode"},"fallbackUrl":"https://weixin.qq.com/","queryScheme":"weixin"},
          {"appId":"alipay","displayName":"支付宝","packageName":"com.eg.android.AlipayGphone","scheme":"alipay","defaultAction":"alipay://","actions":{"open":"alipay://","scan":"alipay://platformapi/startapp?saId=10000007","pay":"alipay://platformapi/startapp?appId=20000056"},"fallbackUrl":"https://mobile.alipay.com/","queryScheme":"alipay"},
          {"appId":"taobao","displayName":"淘宝","packageName":"com.taobao.taobao","scheme":"taobao","defaultAction":"taobao://","actions":{"open":"taobao://","item":"taobao://item?id={itemId}"},"fallbackUrl":"https://m.taobao.com/","queryScheme":"taobao"},
          {"appId":"douyin","displayName":"抖音","packageName":"com.ss.android.ugc.aweme","scheme":"snssdk1128","defaultAction":"snssdk1128://","actions":{"open":"snssdk1128://","detail":"snssdk1128://aweme/detail/{awemeId}/"},"fallbackUrl":"https://www.douyin.com/","queryScheme":"snssdk1128"},
          {"appId":"qq","displayName":"QQ","packageName":"com.tencent.mobileqq","scheme":"mqq","defaultAction":"mqq://","actions":{"open":"mqq://","scan":"mqq://qrcode/scan_qrcode?version=1&src_type=app"},"fallbackUrl":"https://im.qq.com/","queryScheme":"mqq"},
          {"appId":"weibo","displayName":"微博","packageName":"com.sina.weibo","scheme":"sinaweibo","defaultAction":"sinaweibo://","actions":{"open":"sinaweibo://","userinfo":"sinaweibo://userinfo?uid={uid}"},"fallbackUrl":"https://m.weibo.cn/","queryScheme":"sinaweibo"},
          {"appId":"baidu_map","displayName":"百度地图","packageName":"com.baidu.BaiduMap","scheme":"baidumap","defaultAction":"baidumap://","actions":{"open":"baidumap://","route":"baidumap://map/direction?origin={origin}&destination={dest}&mode=driving"},"fallbackUrl":"https://map.baidu.com/","queryScheme":"baidumap"}
        ]
    """.trimIndent()

    private val iterations = 1000

    @Test
    fun `baseline - SchemeRegistry loadFromString parses 7-app config under 5ms p50`() {
        val times = LongArray(iterations) {
            val start = System.nanoTime()
            SchemeRegistry.loadFromString(fullConfigJson)
            System.nanoTime() - start
        }
        val sorted = times.sortedArray()
        val p50 = sorted[iterations / 2] / 1000 // μs
        val p95 = sorted[(iterations * 95 / 100)] / 1000
        val p99 = sorted[(iterations * 99 / 100)] / 1000

        println("[baseline] SchemeRegistry.loadFromString (7 apps): p50=${p50}us p95=${p95}us p99=${p99}us")

        // 初版基线断言：p50 < 5000us (5ms)，宽松阈值
        assertTrue("p50 (${p50}us) should be < 5000us", p50 < 5000)
    }

    @Test
    fun `baseline - SchemeRegistry getAppById query under 100us p50`() {
        val registry = SchemeRegistry.loadFromString(fullConfigJson)
        val appIds = listOf("wechat", "alipay", "taobao", "douyin", "qq", "weibo", "baidu_map")

        val times = LongArray(iterations) {
            val idx = it % appIds.size
            val start = System.nanoTime()
            registry.getAppById(appIds[idx])
            System.nanoTime() - start
        }
        val sorted = times.sortedArray()
        val p50 = sorted[iterations / 2] / 1000 // μs
        val p95 = sorted[(iterations * 95 / 100)] / 1000
        val p99 = sorted[(iterations * 99 / 100)] / 1000

        println("[baseline] SchemeRegistry.getAppById (7 apps): p50=${p50}us p95=${p95}us p99=${p99}us")

        // 初版基线断言：p50 < 100us
        assertTrue("p50 (${p50}us) should be < 100us", p50 < 100)
    }

    @Test
    fun `baseline - AppAvailabilityChecker isAppInstalled under 50us p50`() {
        val checker = AppAvailabilityChecker { pkg ->
            pkg == "com.tencent.mm" || pkg == "com.eg.android.AlipayGphone"
        }
        val packages = listOf(
            "com.tencent.mm", "com.eg.android.AlipayGphone",
            "com.taobao.taobao", "com.ss.android.ugc.aweme"
        )

        val times = LongArray(iterations) {
            val idx = it % packages.size
            val start = System.nanoTime()
            checker.isAppInstalled(packages[idx])
            System.nanoTime() - start
        }
        val sorted = times.sortedArray()
        val p50 = sorted[iterations / 2] / 1000 // μs
        val p95 = sorted[(iterations * 95 / 100)] / 1000
        val p99 = sorted[(iterations * 99 / 100)] / 1000

        println("[baseline] AppAvailabilityChecker.isAppInstalled: p50=${p50}us p95=${p95}us p99=${p99}us")

        // 初版基线断言：p50 < 50us
        assertTrue("p50 (${p50}us) should be < 50us", p50 < 50)
    }

    @Test
    fun `baseline - DeepLinkLauncher resolveAction under 50us p50`() {
        val entry = AppSchemeEntry(
            appId = "wechat",
            displayName = "微信",
            packageName = "com.tencent.mm",
            scheme = "weixin",
            defaultAction = "weixin://",
            actions = mapOf("open" to "weixin://", "scan" to "weixin://scanqrcode")
        )

        val actions = listOf("scan", "open", null, "nonexistent", "")

        val times = LongArray(iterations) {
            val idx = it % actions.size
            val start = System.nanoTime()
            DeepLinkLauncher.resolveAction(entry, actions[idx])
            System.nanoTime() - start
        }
        val sorted = times.sortedArray()
        val p50 = sorted[iterations / 2] / 1000 // μs
        val p95 = sorted[(iterations * 95 / 100)] / 1000
        val p99 = sorted[(iterations * 99 / 100)] / 1000

        println("[baseline] DeepLinkLauncher.resolveAction: p50=${p50}us p95=${p95}us p99=${p99}us")

        // 初版基线断言：p50 < 50us
        assertTrue("p50 (${p50}us) should be < 50us", p50 < 50)
    }
}
