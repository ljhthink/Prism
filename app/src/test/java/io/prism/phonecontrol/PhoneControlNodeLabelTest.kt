package io.prism.phonecontrol

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PhoneControlAccessibilityService.nodeLabelFrom] 的节点树用例（2026-11-22）。
 *
 * **这条测试存在的唯一理由**：ac-verifier 的变异核对发现，`nodeLabelOf` 在全仓测试中
 * 引用数为 0 —— 把它从"只取自身文案"改成"递归子树聚合"，所有测试仍然全绿，
 * 而那正是本次 B2 误判（点商品标题被判成"购买"）的回归点。
 *
 * 这里用 Robolectric 构造真机同构的节点树（容器自身只有价格描述，子节点是购买按钮），
 * 断言标签**不含**子节点文案。改回聚合 → 本用例立刻红。
 */
@RunWith(RobolectricTestRunner::class)
// 显式指定空 Application：默认会实例化 PrismApplication.onCreate（建 ObjectBox、拉起 Shizuku
// 自愈线程），本用例只需要一条合成节点树；带上真 App 会让 JVM 侧去加载 ObjectBox native 库
// 而 LinkageError（与本次修复无关的环境噪声）。
@Config(sdk = [33], application = android.app.Application::class)
class PhoneControlNodeLabelTest {

    private fun raw(text: String? = null, desc: String? = null): AccessibilityNodeInfo {
        val info = AccessibilityNodeInfo.obtain()
        if (text != null) info.text = text
        if (desc != null) info.contentDescription = desc
        return info
    }

    /**
     * 追加子节点。
     *
     * `AccessibilityNodeInfoCompat.addChild(View)` 只接受 View；挂"合成节点"需走
     * Robolectric 的 [ShadowAccessibilityNodeInfo.addChild(AccessibilityNodeInfo)]。
     */
    private fun AccessibilityNodeInfo.addChildUnchecked(child: AccessibilityNodeInfo) {
        org.robolectric.Shadows.shadowOf(this).addChild(child)
    }

    /** 真机结构：容器本人 = 价格描述；直接子节点 = 价格文本 + 「免拼购买」按钮。 */
    private fun detailPageContainer(): AccessibilityNodeInfoCompat {
        val info = raw(desc = "优惠剩3件¥13")
        // 走 Compat.addChild(View) 会要求 View；这里用底层 API 追加子节点（语义一致）
        info.addChildUnchecked(raw(text = "优惠剩3件 ¥ 13"))
        info.addChildUnchecked(raw(text = "免拼购买"))
        return AccessibilityNodeInfoCompat.wrap(info)
    }

    @Test
    fun `容器自身无文案时标签只取描述 不含子节点的购买按钮`() {
        val label = PhoneControlAccessibilityService.nodeLabelFrom(detailPageContainer())
        assertEquals("优惠剩3件¥13", label)
        assertTrue(
            "标签不应包含子节点文案（否则点标题进详情会被判成购买）",
            label?.contains("免拼购买") != true
        )
    }

    @Test
    fun `子节点文案不会被聚合进标签`() {
        val info = raw()
        info.addChildUnchecked(raw(text = "立即购买"))
        info.addChildUnchecked(raw(text = "确认订单"))
        // 自身文案全空 → 返回 null（调用方回退到查询词/落点），绝不能变成子节点文案
        assertNull(PhoneControlAccessibilityService.nodeLabelFrom(AccessibilityNodeInfoCompat.wrap(info)))
    }

    @Test
    fun `节点自身 text 与 desc 都保留`() {
        val label = PhoneControlAccessibilityService.nodeLabelFrom(
            AccessibilityNodeInfoCompat.wrap(raw(text = "奶龙抱枕", desc = "商品"))
        )
        assertEquals("奶龙抱枕 商品", label)
    }

    @Test
    fun `空节点返回 null`() {
        assertNull(PhoneControlAccessibilityService.nodeLabelFrom(null))
        assertNull(PhoneControlAccessibilityService.nodeLabelFrom(AccessibilityNodeInfoCompat.wrap(raw())))
    }
}
