package io.prism.phonecontrol

/**
 * 常用 App 包名映射库（v1 批次12，C/D14 —— prd-v1-b10 §8 #5 落地）。
 *
 * **背景**：LLM（尤其 glm-4.6v-flash 等）常给 `launch_app` 传错误包名或应用名/别名——
 * 真机实证 glm 把拼多多写成 `com.pinduoduo.pinduoduo`（正确为 `com.xunmeng.pinduoduo`）
 * → `getLaunchIntentForPackage` 返回 null → launch_app 失败 → 熔断。本库在 launch_app
 * 前把 别名/错包名/应用名（中文+拼音）纠正为正确包名。
 *
 * **设计**：
 * - [resolvePackage]：输入 `package`/`app` 参数值 → 纠正后的包名；无法纠正返回 null（按原值尝试，
 *   最终仍由 `getLaunchIntentForPackage` 决定是否安装）。
 * - **仅收录高度确信的正确包名**（宁缺毋错——错误映射比无映射更糟，会误导模型）。
 *
 * **可测性**：纯函数/纯 Map，JVM 直测。
 */
object PhoneControlPackageMap {

    /**
     * 纠正/解析包名（纯函数可测）。
     *
     * 匹配优先级：① 别名/错包名精确匹配；② 应用名（中文/拼音/常见缩写）精确匹配。
     * 未命中返回 null（调用方按原值尝试）。
     *
     * @param input LLM 给的 package/app 参数值
     * @return 纠正后的正确包名；未命中 null
     */
    fun resolvePackage(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val normalized = input.trim().lowercase()
        ALIAS_TO_PACKAGE[normalized]?.let { return it }
        return NAME_TO_PACKAGE[normalized]
    }

    /**
     * 别名/常见错包名 → 正确包名。
     *
     * 真机实证：glm 给拼多多写 `com.pinduoduo.pinduoduo`。另收录常见"中文域名式"错拼。
     */
    private val ALIAS_TO_PACKAGE: Map<String, String> = mapOf(
        // 拼多多（真机实证 glm 错包名）
        "com.pinduoduo.pinduoduo" to "com.xunmeng.pinduoduo",
        "com.pinduoduo" to "com.xunmeng.pinduoduo",
        // 微信
        "com.wechat" to "com.tencent.mm",
        "com.tencent.wechat" to "com.tencent.mm",
        // 抖音
        "com.douyin" to "com.ss.android.ugc.aweme",
        // 淘宝
        "com.taobao" to "com.taobao.taobao",
        "com.taobao.taobao.taobao" to "com.taobao.taobao",
        // 京东
        "com.jd" to "com.jingdong.app.mall",
        "com.jingdong" to "com.jingdong.app.mall",
        // 支付宝
        "com.alipay" to "com.eg.android.AlipayGphone",
        "com.eg.android.alipaygphone" to "com.eg.android.AlipayGphone",
        // 高德
        "com.amap" to "com.autonavi.minimap",
        // 哔哩哔哩
        "com.bilibili" to "tv.danmaku.bili",
        // 美团
        "com.meituan" to "com.sankuai.meituan",
        // QQ
        "com.qq" to "com.tencent.mobileqq"
    )

    /**
     * 应用名（中文 / 拼音 / 常见缩写，小写）→ 正确包名。
     *
     * 收录高频：模型最常被要求打开的工具类/社交类/购物类 App。宁缺毋错——仅录入确信包名。
     */
    private val NAME_TO_PACKAGE: Map<String, String> = mapOf(
        // 社交/通讯
        "微信" to "com.tencent.mm", "wechat" to "com.tencent.mm", "weixin" to "com.tencent.mm", "wx" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq", "qq邮箱" to "com.tencent.androidqqmail",
        "企业微信" to "com.tencent.wework", "wecom" to "com.tencent.wework",
        "钉钉" to "com.alibaba.android.rimet", "dingtalk" to "com.alibaba.android.rimet", "dingding" to "com.alibaba.android.rimet",
        "飞书" to "com.ss.android.lark", "feishu" to "com.ss.android.lark", "lark" to "com.ss.android.lark",
        "微博" to "com.sina.weibo", "weibo" to "com.sina.weibo",
        "小红书" to "com.xingin.xhs", "xiaohongshu" to "com.xingin.xhs", "xhs" to "com.xingin.xhs",
        "知乎" to "com.zhihu.android", "zhihu" to "com.zhihu.android",
        "豆瓣" to "com.douban.frodo", "douban" to "com.douban.frodo",
        "telegram" to "org.telegram.messenger",
        // 短视频
        "抖音" to "com.ss.android.ugc.aweme", "douyin" to "com.ss.android.ugc.aweme",
        "抖音极速版" to "com.ss.android.ugc.aweme.lite",
        "快手" to "com.smile.gifmaker", "kuaishou" to "com.smile.gifmaker",
        "b站" to "tv.danmaku.bili", "哔哩哔哩" to "tv.danmaku.bili", "bilibili" to "tv.danmaku.bili",
        "西瓜视频" to "com.ss.android.ugc.live", "xigua" to "com.ss.android.ugc.live",
        // 购物
        "拼多多" to "com.xunmeng.pinduoduo", "pinduoduo" to "com.xunmeng.pinduoduo", "pdd" to "com.xunmeng.pinduoduo",
        "淘宝" to "com.taobao.taobao", "taobao" to "com.taobao.taobao",
        "天猫" to "com.tmall.wireless", "tmall" to "com.tmall.wireless",
        "京东" to "com.jingdong.app.mall", "jingdong" to "com.jingdong.app.mall", "jd" to "com.jingdong.app.mall",
        "闲鱼" to "com.taobao.idlefish", "xianyu" to "com.taobao.idlefish",
        "唯品会" to "com.achievo.vipshop", "vipshop" to "com.achievo.vipshop",
        "苏宁易购" to "com.suning.mobile.ebuy", "suning" to "com.suning.mobile.ebuy",
        "得物" to "com.shizhuang.duapp", "dewuduo" to "com.shizhuang.duapp",
        "小米商城" to "com.xiaomi.shop",
        // 支付/生活
        "支付宝" to "com.eg.android.AlipayGphone", "alipay" to "com.eg.android.AlipayGphone", "zhifubao" to "com.eg.android.AlipayGphone",
        "美团" to "com.sankuai.meituan", "meituan" to "com.sankuai.meituan",
        "美团外卖" to "com.sankuai.meituan.takeoutnew",
        "大众点评" to "com.dianping.v1", "dianping" to "com.dianping.v1",
        "饿了么" to "me.ele", "eleme" to "me.ele",
        "滴滴" to "com.sdu.didi.psnger", "didichuxing" to "com.sdu.didi.psnger", "didi" to "com.sdu.didi.psnger",
        "12306" to "com.MobileTicket", "铁路12306" to "com.MobileTicket", "中国铁路" to "com.MobileTicket",
        "交管12123" to "com.tmri.app.main",
        "个人所得税" to "com.tax.china.app",
        // 地图/出行
        "高德地图" to "com.autonavi.minimap", "gaode" to "com.autonavi.minimap", "amap" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap", "baidumap" to "com.baidu.BaiduMap",
        "携程" to "ctrip.android.view", "xiecheng" to "ctrip.android.view", "ctrip" to "ctrip.android.view",
        "去哪儿" to "com.Qunar", "qunar" to "com.Qunar",
        "飞猪" to "com.taobao.trip", "feizhu" to "com.taobao.trip",
        // 视频/音乐
        "腾讯视频" to "com.tencent.qqlive", "qqlive" to "com.tencent.qqlive",
        "优酷" to "com.youku.phone", "youku" to "com.youku.phone",
        "爱奇艺" to "com.qiyi.video", "iqiyi" to "com.qiyi.video", "aiqiyi" to "com.qiyi.video",
        "芒果tv" to "com.hunantv.imgo.activity", "mangotv" to "com.hunantv.imgo.activity",
        "网易云音乐" to "com.netease.cloudmusic", "cloudmusic" to "com.netease.cloudmusic", "wyy" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic", "qqmusic" to "com.tencent.qqmusic",
        "酷狗音乐" to "com.kugou.android", "kugou" to "com.kugou.android",
        "酷我音乐" to "cn.kuwo.player", "kuwo" to "cn.kuwo.player",
        // 资讯/社区
        "今日头条" to "com.ss.android.article.news", "toutiao" to "com.ss.android.article.news",
        "百度" to "com.baidu.searchbox", "baidu" to "com.baidu.searchbox",
        "夸克" to "com.quark.browser", "quark" to "com.quark.browser",
        "虎扑" to "com.hupu.games", "hupu" to "com.hupu.games",
        "什么值得买" to "com.smzdm.client.android", "smzdm" to "com.smzdm.client.android",
        // 办公/效率
        "wps" to "cn.wps.moffice_eng", "wps office" to "cn.wps.moffice_eng", "wpsoffice" to "cn.wps.moffice_eng",
        "腾讯会议" to "com.tencent.wemeet.app", "tencentmeeting" to "com.tencent.wemeet.app", "voo" to "com.tencent.wemeet.app",
        "百度网盘" to "com.baidu.netdisk", "baiduyun" to "com.baidu.netdisk", "wangpan" to "com.baidu.netdisk",
        "阿里云盘" to "com.alicloud.databox",
        // 招聘/房产
        "boss直聘" to "com.hpbr.bosszhipin", "boss" to "com.hpbr.bosszhipin", "zhipin" to "com.hpbr.bosszhipin",
        "智联招聘" to "com.zhaopin.social", "zhilian" to "com.zhaopin.social",
        "前程无忧" to "com.jianzhi_android", "51job" to "com.jianzhi_android",
        "脉脉" to "com.taou.maimai", "maimai" to "com.taou.maimai",
        "贝壳" to "com.lianjia.beike", "beike" to "com.lianjia.beike",
        "链家" to "com.lianjia", "lianjia" to "com.lianjia",
        "安居客" to "com.appadhoc.anjuke", "anjuke" to "com.appadhoc.anjuke",
        "58同城" to "com.wuba", "wuba" to "com.wuba",
        // 出行/汽车
        "汽车之家" to "com.cubic.autohome", "autohome" to "com.cubic.autohome",
        "懂车帝" to "com.ss.android.auto", "dongchedi" to "com.ss.android.auto",
        "墨迹天气" to "com.moji.mjweather", "moji" to "com.moji.mjweather",
        // 应用商店（真机高频"打开应用商店搜索并下载"）
        "应用商店" to "com.xiaomi.market",
        "小米应用商店" to "com.xiaomi.market", "xiaomi market" to "com.xiaomi.market",
        "华为应用市场" to "com.huawei.appmarket", "huawei appmarket" to "com.huawei.appmarket",
        "应用宝" to "com.tencent.android.qqdownloader", "yyb" to "com.tencent.android.qqdownloader",
        // 金融（敏感黑名单 App 仍被 PhoneControlSecurity 硬拦截；此库仅纠正包名，不改变拦截）
        "招商银行" to "cmb.pb", "zhaoshang" to "cmb.pb",
        "工商银行" to "com.icbc", "gongshang" to "com.icbc",
        "建设银行" to "com.chinamworld.main", "jianshe" to "com.chinamworld.main", "ccb" to "com.chinamworld.main",
        "农业银行" to "com.android.bankabc", "nongye" to "com.android.bankabc",
        "中国银行" to "com.chinamworld.bocmbci", "zhongguoyinhang" to "com.chinamworld.bocmbci", "boc" to "com.chinamworld.bocmbci"
    )
}
