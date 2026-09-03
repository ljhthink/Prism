package io.prism.phonecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhoneControlPackageMap] 单元测试（v1 批次12，C/D14 —— 常用 App 包名映射纠正）。
 */
class PhoneControlPackageMapTest {

    @Test
    fun `corrects wrong pinduoduo package from glm`() {
        // 真机实证：glm 给拼多多写 com.pinduoduo.pinduoduo
        assertEquals("com.xunmeng.pinduoduo", PhoneControlPackageMap.resolvePackage("com.pinduoduo.pinduoduo"))
    }

    @Test
    fun `resolves chinese app names`() {
        assertEquals("com.tencent.mm", PhoneControlPackageMap.resolvePackage("微信"))
        assertEquals("com.xunmeng.pinduoduo", PhoneControlPackageMap.resolvePackage("拼多多"))
        assertEquals("com.taobao.taobao", PhoneControlPackageMap.resolvePackage("淘宝"))
        assertEquals("com.jingdong.app.mall", PhoneControlPackageMap.resolvePackage("京东"))
        assertEquals("com.ss.android.ugc.aweme", PhoneControlPackageMap.resolvePackage("抖音"))
    }

    @Test
    fun `resolves pinyin and common abbreviations`() {
        assertEquals("com.tencent.mm", PhoneControlPackageMap.resolvePackage("wechat"))
        assertEquals("com.tencent.mm", PhoneControlPackageMap.resolvePackage("wx"))
        assertEquals("com.xunmeng.pinduoduo", PhoneControlPackageMap.resolvePackage("pdd"))
        assertEquals("com.ss.android.ugc.aweme", PhoneControlPackageMap.resolvePackage("douyin"))
        assertEquals("tv.danmaku.bili", PhoneControlPackageMap.resolvePackage("bilibili"))
    }

    @Test
    fun `unknown returns null so caller uses original value`() {
        // 正确包名不在映射库 → 返回 null，调用方按原值尝试（getLaunchIntentForPackage 决定）
        assertNull(PhoneControlPackageMap.resolvePackage("com.tencent.mm"))
        assertNull(PhoneControlPackageMap.resolvePackage("com.xunmeng.pinduoduo"))
        assertNull(PhoneControlPackageMap.resolvePackage("com.example.unknown"))
        assertNull(PhoneControlPackageMap.resolvePackage("不存在应用"))
        assertNull(PhoneControlPackageMap.resolvePackage(""))
        assertNull(PhoneControlPackageMap.resolvePackage(null))
    }

    @Test
    fun `case insensitive`() {
        assertEquals("com.tencent.mm", PhoneControlPackageMap.resolvePackage("WeChat"))
        assertEquals("com.xunmeng.pinduoduo", PhoneControlPackageMap.resolvePackage("PDD"))
    }

    // ==================== ac-verifier 边界补充（TKN-V1B12-ACCEPTANCE-001） ====================

    @Test
    fun `resolves financial app names to packages that security blacklist blocks`() {
        // AC-S1 组合红线（guardrail P0）：包名映射库纠正后的金融包名必须落入
        // PhoneControlSecurity.BLOCKED_LAUNCH_PACKAGES —— 任一中文名解析后必然命中黑名单。
        // 招商银行 → cmb.pb（真机实证包名）
        assertEquals("cmb.pb", PhoneControlPackageMap.resolvePackage("招商银行"))
        assertTrue("招商银行纠正后必须被黑名单拦截", PhoneControlSecurity.isSensitivePackage("cmb.pb"))
        // 中国银行 → com.chinamworld.bocmbci（主流包名）
        assertEquals("com.chinamworld.bocmbci", PhoneControlPackageMap.resolvePackage("中国银行"))
        assertTrue("中国银行纠正后必须被黑名单拦截", PhoneControlSecurity.isSensitivePackage("com.chinamworld.bocmbci"))
        // 建设银行 → com.chinamworld.main
        assertEquals("com.chinamworld.main", PhoneControlPackageMap.resolvePackage("建设银行"))
        assertTrue("建设银行纠正后必须被黑名单拦截", PhoneControlSecurity.isSensitivePackage("com.chinamworld.main"))
        // 工商银行 → com.icbc / 农业银行 → com.android.bankabc
        assertEquals("com.icbc", PhoneControlPackageMap.resolvePackage("工商银行"))
        assertTrue("工商银行纠正后必须被黑名单拦截", PhoneControlSecurity.isSensitivePackage("com.icbc"))
        assertEquals("com.android.bankabc", PhoneControlPackageMap.resolvePackage("农业银行"))
        assertTrue("农业银行纠正后必须被黑名单拦截", PhoneControlSecurity.isSensitivePackage("com.android.bankabc"))
    }

    @Test
    fun `resolves financial aliases to blocked packages`() {
        // AC-S1：拼音/缩写别名（zhaoshang/ccb/boc/gongshang/nongye）纠正后同样命中黑名单
        assertEquals("cmb.pb", PhoneControlPackageMap.resolvePackage("zhaoshang"))
        assertTrue(PhoneControlSecurity.isSensitivePackage(PhoneControlPackageMap.resolvePackage("zhaoshang")!!))
        assertEquals("com.chinamworld.main", PhoneControlPackageMap.resolvePackage("ccb"))
        assertTrue(PhoneControlSecurity.isSensitivePackage(PhoneControlPackageMap.resolvePackage("ccb")!!))
        assertEquals("com.chinamworld.bocmbci", PhoneControlPackageMap.resolvePackage("boc"))
        assertTrue(PhoneControlSecurity.isSensitivePackage(PhoneControlPackageMap.resolvePackage("boc")!!))
        assertEquals("com.icbc", PhoneControlPackageMap.resolvePackage("gongshang"))
        assertTrue(PhoneControlSecurity.isSensitivePackage(PhoneControlPackageMap.resolvePackage("gongshang")!!))
    }

    @Test
    fun `mixed case alias and app name resolve correctly`() {
        // 边界：mixed case 别名/应用名 → lowercase 归一后命中（LLM 常混用大小写）
        assertEquals("com.xunmeng.pinduoduo", PhoneControlPackageMap.resolvePackage("Com.Pinduoduo.Pinduoduo"))
        assertEquals("com.xunmeng.pinduoduo", PhoneControlPackageMap.resolvePackage("PDD"))
        assertEquals("com.tencent.mm", PhoneControlPackageMap.resolvePackage("WeChat"))
        assertEquals("com.eg.android.AlipayGphone", PhoneControlPackageMap.resolvePackage("Alipay"))
        // 边界：带首尾空白 → trim 后命中
        assertEquals("com.tencent.mm", PhoneControlPackageMap.resolvePackage(" 微信 "))
    }

    @Test
    fun `correct package returns null so blacklist exact check still applies`() {
        // 边界：正确包名不在映射库（返回 null，调用方按原值）→ 黑名单精确判定仍直接生效
        assertNull(PhoneControlPackageMap.resolvePackage("cmb.pb"))
        assertTrue("正确包名即使不入库也应由黑名单精确拦截", PhoneControlSecurity.isSensitivePackage("cmb.pb"))
        assertTrue(PhoneControlSecurity.isSensitivePackage("com.eg.android.AlipayGphone"))
        // 边界补充：AlipayGphone 经小写别名幂等解析回自身（非 null），黑名单精确拦截仍生效
        assertEquals("com.eg.android.AlipayGphone", PhoneControlPackageMap.resolvePackage("com.eg.android.AlipayGphone"))
    }
}
