package io.prism.crossapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SchemeRegistry] 单元测试（M6 Phase A，US-037 AC-10）。
 *
 * 纯 JVM 测试，不依赖 Android Context。覆盖：
 * - JSON 字符串加载与解析
 * - appId / packageName / scheme 维度查询
 * - 空配置降级
 * - 无效 JSON 降级
 * - 完整 7 App 配置加载
 */
class SchemeRegistryTest {

    /** 单个 App 的最小合法 JSON 配置。 */
    private val singleAppJson = """
        [
          {
            "appId": "wechat",
            "displayName": "微信",
            "packageName": "com.tencent.mm",
            "scheme": "weixin",
            "defaultAction": "weixin://",
            "actions": {
              "open": "weixin://",
              "scan": "weixin://scanqrcode"
            },
            "fallbackUrl": "https://weixin.qq.com/",
            "queryScheme": "weixin"
          }
        ]
    """.trimIndent()

    /** 多 App 配置（含可选字段缺失场景）。 */
    private val multiAppJson = """
        [
          {
            "appId": "wechat",
            "displayName": "微信",
            "packageName": "com.tencent.mm",
            "scheme": "weixin",
            "defaultAction": "weixin://",
            "actions": {"scan": "weixin://scanqrcode"},
            "fallbackUrl": "https://weixin.qq.com/",
            "queryScheme": "weixin"
          },
          {
            "appId": "alipay",
            "displayName": "支付宝",
            "packageName": "com.eg.android.AlipayGphone",
            "scheme": "alipay",
            "defaultAction": "alipay://"
          }
        ]
    """.trimIndent()

    @Test
    fun `loadFromString returns single entry for valid single app json`() {
        val registry = SchemeRegistry.loadFromString(singleAppJson)

        val apps = registry.getAllApps()
        assertEquals(1, apps.size)

        val wechat = apps[0]
        assertEquals("wechat", wechat.appId)
        assertEquals("微信", wechat.displayName)
        assertEquals("com.tencent.mm", wechat.packageName)
        assertEquals("weixin", wechat.scheme)
        assertEquals("weixin://", wechat.defaultAction)
        assertEquals(2, wechat.actions.size)
        assertEquals("weixin://scanqrcode", wechat.actions["scan"])
        assertEquals("https://weixin.qq.com/", wechat.fallbackUrl)
        assertEquals("weixin", wechat.queryScheme)
    }

    @Test
    fun `loadFromString returns multiple entries for multi app json`() {
        val registry = SchemeRegistry.loadFromString(multiAppJson)

        val apps = registry.getAllApps()
        assertEquals(2, apps.size)
    }

    @Test
    fun `loadFromString handles missing optional fields with defaults`() {
        val registry = SchemeRegistry.loadFromString(multiAppJson)

        val alipay = registry.getAppById("alipay")
        assertNotNull(alipay)
        assertEquals("alipay", alipay!!.appId)
        assertEquals("alipay://", alipay.defaultAction)
        assertTrue("actions should be empty", alipay.actions.isEmpty())
        assertNull("fallbackUrl should be null", alipay.fallbackUrl)
        assertEquals("queryScheme should default to scheme", "alipay", alipay.queryScheme)
    }

    @Test
    fun `getAppById returns matching entry`() {
        val registry = SchemeRegistry.loadFromString(singleAppJson)

        val wechat = registry.getAppById("wechat")
        assertNotNull(wechat)
        assertEquals("微信", wechat!!.displayName)
    }

    @Test
    fun `getAppById returns null for unknown appId`() {
        val registry = SchemeRegistry.loadFromString(singleAppJson)

        assertNull(registry.getAppById("nonexistent"))
    }

    @Test
    fun `getAppByPackageName returns matching entry`() {
        val registry = SchemeRegistry.loadFromString(singleAppJson)

        val entry = registry.getAppByPackageName("com.tencent.mm")
        assertNotNull(entry)
        assertEquals("wechat", entry!!.appId)
    }

    @Test
    fun `getAppByPackageName returns null for unknown package`() {
        val registry = SchemeRegistry.loadFromString(singleAppJson)

        assertNull(registry.getAppByPackageName("com.unknown.app"))
    }

    @Test
    fun `getAppByScheme returns matching entry`() {
        val registry = SchemeRegistry.loadFromString(singleAppJson)

        val entry = registry.getAppByScheme("weixin")
        assertNotNull(entry)
        assertEquals("wechat", entry!!.appId)
    }

    @Test
    fun `getAppByScheme returns null for unknown scheme`() {
        val registry = SchemeRegistry.loadFromString(singleAppJson)

        assertNull(registry.getAppByScheme("unknown"))
    }

    @Test
    fun `loadFromString returns empty registry for invalid json`() {
        val registry = SchemeRegistry.loadFromString("not a valid json")

        assertTrue("invalid json should yield empty registry", registry.getAllApps().isEmpty())
    }

    @Test
    fun `loadFromString returns empty registry for empty json array`() {
        val registry = SchemeRegistry.loadFromString("[]")

        assertTrue(registry.getAllApps().isEmpty())
    }

    @Test
    fun `empty factory creates empty registry`() {
        val registry = SchemeRegistry.empty()

        assertTrue(registry.getAllApps().isEmpty())
        assertNull(registry.getAppById("any"))
    }

    @Test
    fun `loadFromString ignores unknown keys for forward compatibility`() {
        val jsonWithExtraFields = """
            [
              {
                "appId": "wechat",
                "displayName": "微信",
                "packageName": "com.tencent.mm",
                "scheme": "weixin",
                "defaultAction": "weixin://",
                "futureField": "some future value",
                "version": 2
              }
            ]
        """.trimIndent()

        val registry = SchemeRegistry.loadFromString(jsonWithExtraFields)

        val apps = registry.getAllApps()
        assertEquals(1, apps.size)
        assertEquals("wechat", apps[0].appId)
    }

    @Test
    fun `loadFromString loads all 7 apps from full config`() {
        val fullConfigJson = """
            [
              {"appId":"wechat","displayName":"微信","packageName":"com.tencent.mm","scheme":"weixin","defaultAction":"weixin://","actions":{"open":"weixin://","scan":"weixin://scanqrcode"},"fallbackUrl":"https://weixin.qq.com/","queryScheme":"weixin"},
              {"appId":"alipay","displayName":"支付宝","packageName":"com.eg.android.AlipayGphone","scheme":"alipay","defaultAction":"alipay://","actions":{"open":"alipay://","scan":"alipay://platformapi/startapp?saId=10000007"},"fallbackUrl":"https://mobile.alipay.com/","queryScheme":"alipay"},
              {"appId":"taobao","displayName":"淘宝","packageName":"com.taobao.taobao","scheme":"taobao","defaultAction":"taobao://","actions":{"open":"taobao://"},"fallbackUrl":"https://m.taobao.com/","queryScheme":"taobao"},
              {"appId":"douyin","displayName":"抖音","packageName":"com.ss.android.ugc.aweme","scheme":"snssdk1128","defaultAction":"snssdk1128://","actions":{"open":"snssdk1128://"},"fallbackUrl":"https://www.douyin.com/","queryScheme":"snssdk1128"},
              {"appId":"qq","displayName":"QQ","packageName":"com.tencent.mobileqq","scheme":"mqq","defaultAction":"mqq://","actions":{"open":"mqq://"},"fallbackUrl":"https://im.qq.com/","queryScheme":"mqq"},
              {"appId":"weibo","displayName":"微博","packageName":"com.sina.weibo","scheme":"sinaweibo","defaultAction":"sinaweibo://","actions":{"open":"sinaweibo://"},"fallbackUrl":"https://m.weibo.cn/","queryScheme":"sinaweibo"},
              {"appId":"baidu_map","displayName":"百度地图","packageName":"com.baidu.BaiduMap","scheme":"baidumap","defaultAction":"baidumap://","actions":{"open":"baidumap://"},"fallbackUrl":"https://map.baidu.com/","queryScheme":"baidumap"}
            ]
        """.trimIndent()

        val registry = SchemeRegistry.loadFromString(fullConfigJson)

        val apps = registry.getAllApps()
        assertEquals(7, apps.size)
        assertNotNull(registry.getAppById("wechat"))
        assertNotNull(registry.getAppById("alipay"))
        assertNotNull(registry.getAppById("taobao"))
        assertNotNull(registry.getAppById("douyin"))
        assertNotNull(registry.getAppById("qq"))
        assertNotNull(registry.getAppById("weibo"))
        assertNotNull(registry.getAppById("baidu_map"))
    }
}
