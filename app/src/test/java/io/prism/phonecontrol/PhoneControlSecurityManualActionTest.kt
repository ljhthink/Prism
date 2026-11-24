package io.prism.phonecontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高危动作判定真值表（2026-11-22，B2「选购任务被误判为敏感操作」修复）。
 *
 * 全部样本来自真机 `uiautomator dump` 原文（拼多多商品详情页 / 搜索列表页），
 * 不是编造字符串——这正是"测试通过但真机一直弹确认"最容易漏掉的地方。
 *
 * guardrail M-1/M-2 补充：判定文本有两个口径（硬拦截 / 高危），且纯坐标点击必须有落点回读，
 * 否则安全会静默降级。本文件同时锁住"误判已修"与"保护未丢"两侧。
 */
class PhoneControlSecurityManualActionTest {

    /** 详情页可点击容器的**子树聚合**文本（误判来源；旧实现拿它做判定）。 */
    private val detailContainerAggregate = "优惠剩3件¥13 优惠剩3件 ¥ 13 免拼购买"

    /** 搜索列表页商品卡的子树聚合文本（正常，无高危词）。 */
    private val listCardAggregate =
        "奶龙小七抱枕抱可爱双面枕 奶龙小七抱枕抱可爱双面枕头卧室床上午睡靠垫儿童生日礼物定制 立减10元 退货包运费 券后 ¥ 13"

    /** 商品标题本身（点标题进详情时，目标的**自身**文案）。 */
    private val titleOwnLabel = "新款长条奶龙卡通异形抱枕简约软萌"

    // ==================== 误判回归：标题不该被判高危 ====================

    @Test
    fun `点击商品标题不算高危动作`() {
        assertFalse(PhoneControlSecurity.isManualAction("tap", titleOwnLabel))
        assertNull(PhoneControlSecurity.firstManualKeyword("tap", titleOwnLabel))
    }

    @Test
    fun `搜索列表商品卡聚合文本不含高危词`() {
        assertFalse(PhoneControlSecurity.isManualAction("tap", listCardAggregate))
    }

    /**
     * 这条用例锁住"误判的成因"：同一屏里，**容器聚合文本**含「免拼购买」。
     * 说明只要高危判定文本取聚合值就会误判——修复必须发生在文本来源上，而不是删关键词。
     */
    @Test
    fun `详情页容器聚合文本确实会误命中购买`() {
        assertTrue(PhoneControlSecurity.isManualAction("tap", detailContainerAggregate))
        assertEquals("购买", PhoneControlSecurity.firstManualKeyword("tap", detailContainerAggregate))
    }

    // ==================== 真高危仍要拦 ====================

    @Test
    fun `真实购买按钮自身文案仍判高危`() {
        assertEquals("购买", PhoneControlSecurity.firstManualKeyword("tap", "免拼购买"))
        assertEquals("购买", PhoneControlSecurity.firstManualKeyword("tap", "立即购买"))
        assertEquals("购买", PhoneControlSecurity.firstManualKeyword("tap", "购买"))
    }

    @Test
    fun `下单与提交订单仍判高危`() {
        assertEquals("下单", PhoneControlSecurity.firstManualKeyword("tap", "去下单"))
        assertEquals("确认订单", PhoneControlSecurity.firstManualKeyword("tap", "确认订单"))
    }

    @Test
    fun `发送与删除仍判高危`() {
        assertEquals("发送", PhoneControlSecurity.firstManualKeyword("tap", "发送"))
        assertEquals("删除", PhoneControlSecurity.firstManualKeyword("tap", "删除会话"))
    }

    @Test
    fun `非高危文案不误伤`() {
        // 「取消」「收藏」「加购物车」「进店」「领券」「客服」都是购物任务的常规操作
        listOf("取消", "收藏", "加入购物车", "进店逛逛", "领券", "联系客服", "分享", "立即领取").forEach {
            assertFalse("误伤：$it", PhoneControlSecurity.isManualAction("tap", it))
        }
    }

    // ==================== 判定文本来源（修复核心） ====================

    @Test
    fun `UI 树命中时高危判定用目标自身文案而非聚合文本`() {
        val texts = PhoneControlLocalToolExecutor.resolveJudgementTexts(
            hasNodeId = true,
            ownLabel = titleOwnLabel,
            aggregateLabel = detailContainerAggregate,
            coordinateHitText = null,
            ocrMatched = null,
            anchor = "新款长条奶龙卡通"
        )
        assertEquals(titleOwnLabel, texts.manualText)
        assertFalse(PhoneControlSecurity.isManualAction("tap", texts.manualText.orEmpty()))
    }

    @Test
    fun `容器自身文案是价格时不会把子节点的购买按钮算进高危判定`() {
        // 真机 dump：容器 contentDescription="优惠剩3件¥13"，直接子节点里有「免拼购买」TextView。
        val texts = PhoneControlLocalToolExecutor.resolveJudgementTexts(
            hasNodeId = true,
            ownLabel = "优惠剩3件¥13",
            aggregateLabel = detailContainerAggregate,
            coordinateHitText = null,
            ocrMatched = null,
            anchor = "新款长条奶龙卡通"
        )
        assertEquals("优惠剩3件¥13", texts.manualText)
        assertFalse(PhoneControlSecurity.isManualAction("tap", texts.manualText.orEmpty()))
    }

    // ==================== guardrail M-2：硬拦截不得降级 ====================

    @Test
    fun `自身文案为空时硬拦截回落子树聚合`() {
        // 可点击容器自身无文案、支付按钮文案在子节点（本次真机同构结构）：
        // 高危判定回退查询词（避免误判），但**硬拦截**必须回落聚合文本，否则支付保护归零。
        val texts = PhoneControlLocalToolExecutor.resolveJudgementTexts(
            hasNodeId = true,
            ownLabel = null,
            aggregateLabel = "确认支付 微信支付 ¥13",
            coordinateHitText = null,
            ocrMatched = null,
            anchor = "确认"
        )
        assertEquals("确认", texts.manualText)
        assertEquals("确认支付 微信支付 ¥13", texts.hardBlockText)
        assertTrue(PhoneControlSecurity.isSensitiveTargetText(texts.hardBlockText))
    }

    @Test
    fun `自身文案存在时硬拦截不复用聚合文本`() {
        // 有自身文案时不应把同屏无关的支付说明也算进来（避免"任意点击都被硬拦"）
        val texts = PhoneControlLocalToolExecutor.resolveJudgementTexts(
            hasNodeId = true,
            ownLabel = titleOwnLabel,
            aggregateLabel = "支持微信支付 免拼购买",
            coordinateHitText = null,
            ocrMatched = null,
            anchor = null
        )
        assertEquals(titleOwnLabel, texts.hardBlockText)
        assertFalse(PhoneControlSecurity.isSensitiveTargetText(texts.hardBlockText))
    }

    // ==================== guardrail M-1：纯坐标点击必须回读落点 ====================

    @Test
    fun `纯坐标点击用落点回读文本判定`() {
        val texts = PhoneControlLocalToolExecutor.resolveJudgementTexts(
            hasNodeId = false,
            ownLabel = null,
            aggregateLabel = null,
            coordinateHitText = "确认支付",
            ocrMatched = null,
            anchor = null
        )
        assertEquals("确认支付", texts.manualText)
        assertEquals("确认支付", texts.hardBlockText)
        assertTrue(PhoneControlSecurity.isSensitiveTargetText(texts.manualText))
    }

    @Test
    fun `纯坐标点击命中购买按钮时判高危`() {
        val texts = PhoneControlLocalToolExecutor.resolveJudgementTexts(
            hasNodeId = false,
            ownLabel = null,
            aggregateLabel = null,
            coordinateHitText = "立即购买",
            ocrMatched = null,
            anchor = null
        )
        assertTrue(PhoneControlSecurity.isManualAction("tap", texts.manualText.orEmpty()))
    }

    @Test
    fun `纯坐标且无回读时用OCR命中文本`() {
        val texts = PhoneControlLocalToolExecutor.resolveJudgementTexts(
            hasNodeId = false,
            ownLabel = null,
            aggregateLabel = null,
            coordinateHitText = null,
            ocrMatched = "确认订单",
            anchor = "确认"
        )
        assertEquals("确认订单", texts.manualText)
    }

    @Test
    fun `全部为空时两口径都为空`() {
        val texts = PhoneControlLocalToolExecutor.resolveJudgementTexts(
            hasNodeId = true,
            ownLabel = null,
            aggregateLabel = null,
            coordinateHitText = null,
            ocrMatched = null,
            anchor = null
        )
        assertNull(texts.manualText)
        assertNull(texts.hardBlockText)
    }

    // ==================== 硬拦截口径不回归 ====================

    @Test
    fun `支付类目标仍硬拦截`() {
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("确认支付"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("余额支付"))
        assertTrue(PhoneControlSecurity.isSensitiveTargetText("微信支付"))
    }

    @Test
    fun `商品标题不含支付关键词`() {
        assertFalse(PhoneControlSecurity.isSensitiveTargetText(titleOwnLabel))
    }

    @Test
    fun `isManualAction 与 firstManualKeyword 语义一致`() {
        listOf("立即购买", "发送", "取消", titleOwnLabel, detailContainerAggregate).forEach { text ->
            assertEquals(
                "不一致：$text",
                PhoneControlSecurity.firstManualKeyword("tap", text) != null,
                PhoneControlSecurity.isManualAction("tap", text)
            )
        }
    }

    @Test
    fun `密码与凭据判定未受影响`() {
        // 语义钉住（与本轮修复无关的既有口径）：
        // ① 纯数字串**不**算凭据——关键词表只覆盖"密码/验证码/卡号/cvv/otp/pin"等语义词，
        //    不猜数字形态，否则"20块预算"会被当验证码拦掉；
        // ② 英文关键词用词边界匹配，`password123` 这类**紧贴数字**的写法不命中
        //    （`(?![a-z0-9])` 边界）。这是既有边界，属已知限制（见 bug 报告 §七 R-1），
        //    本轮不改：放宽边界会同时影响所有调用方的高危判定，需单独立项评估。
        assertTrue(PhoneControlSecurity.isCredentialInput("密码：abc123"))
        assertTrue(PhoneControlSecurity.isCredentialInput("password"))
        assertTrue(PhoneControlSecurity.isCredentialInput("验证码 8899"))
        assertFalse(PhoneControlSecurity.isCredentialInput("奶龙抱枕"))
        assertFalse(PhoneControlSecurity.isCredentialInput("123456"))
        assertFalse(PhoneControlSecurity.isCredentialInput("password123"))
    }

    // ==================== selfLabel：只取自身，绝不递归（guardrail M-2 变异守卫） ====================

    @Test
    fun `selfLabel 取自身文本与描述`() {
        assertEquals(
            "优惠剩3件¥13 免拼购买",
            PhoneControlAccessibilityService.selfLabel(" 优惠剩3件¥13 ", "免拼购买")
        )
    }

    @Test
    fun `selfLabel 忽略空白`() {
        assertNull(PhoneControlAccessibilityService.selfLabel(null, null))
        assertNull(PhoneControlAccessibilityService.selfLabel("   ", ""))
        assertEquals("标题", PhoneControlAccessibilityService.selfLabel("标题", "  "))
    }

    @Test
    fun `selfLabel 不会把子节点文案算进来`() {
        // 容器自身只有价格描述；「免拼购买」在子 TextView 里（真机结构），
        // 因此 selfLabel 的结果必须不含「购买」——这是本次修复的核心不变量。
        val label = PhoneControlAccessibilityService.selfLabel(null, "优惠剩3件¥13")
        assertEquals("优惠剩3件¥13", label)
        assertFalse(PhoneControlSecurity.isManualAction("tap", label.orEmpty()))
    }
}
