package io.prism.phonecontrol.bg

import android.graphics.Bitmap

/**
 * 帧编码期间的**位图归属与回收**（2026-10-25 帧健康整改；guardrail **G-r3-02**）。
 *
 * **为什么单独抽出来**：`encodeHiddenFrame` 的降级过程会临时分配多张位图（每张 720×1560
 * RGBA ≈ 4.5MB；缩放中间态 1.4~2.9MB）。这段清理逻辑若放在 `finally` 里内联，JVM 单测
 * 无法断言（r2 复审实测：把 `finally` 回退成 r1 的 `scaled?.recycle(); source.recycle()`
 * 后，全部 27 个测试套件仍然全绿——等于没有回归锁）。现在清理逻辑是一个可注入、可断言的
 * 纯对象：Robolectric 用真实 Bitmap 断言"创建的都回收了"。
 */
internal object FrameBitmapRecycler {

    /**
     * 回收本次调用**拥有的**全部位图 + 源位图，且保证：
     * - 同一对象只回收一次（`owned` 里可能有别名，如 `base === source`）；
     * - 已回收的对象跳过（`recycle()` 对已回收对象是 no-op，但某些 ROM 会告警）；
     * - 任何单张回收失败不影响其余（回收失败不能反过来中断主流程）。
     *
     * @param owned 本次调用分配的位图（含缩放中间态；可含别名）
     * @param source 从缓存复制出来的源位图（可能同时出现在 `owned` 中）
     */
    fun recycleAll(owned: Collection<Bitmap?>, source: Bitmap?) {
        val seen = java.util.IdentityHashMap<Bitmap, Boolean>()
        (owned + listOf(source)).forEach { bmp ->
            if (bmp == null) return@forEach
            if (seen.put(bmp, true) != null) return@forEach // 同一对象（含别名）只处理一次
            runCatching { if (!bmp.isRecycled) bmp.recycle() }
        }
    }
}
