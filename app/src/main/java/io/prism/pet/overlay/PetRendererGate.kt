package io.prism.pet.overlay

/**
 * BUG-20260913-001：**渲染器就绪门控** —— 纯 Kotlin，可 JVM 单测。
 *
 * ## 背景（真机根因）
 *
 * `PetOverlayService` 的启动序列是"`loadUrl()` 之后立刻下发命令"：
 * ```
 * buildOverlay(): web.loadUrl("https://pet.prism.local/assets/pet/index.html")  // 异步开始
 * showOverlay():  → reloadConfigAndAppearance() → reloadAppearance()
 *                 → eval(webView, "PetRenderer.load(...)")                       // 同步抢先
 * ```
 * `loadUrl` 只是**发起**导航；此刻 HTML 还没解析、4 个脚本还没下载执行，
 * 所以 `PetRenderer` 尚不存在 ⇒ JS 抛 `Uncaught ReferenceError: PetRenderer is not defined`
 * （真机 logcat 实证，出现 3 次）⇒ **描述符从未送达渲染器** ⇒ 模型从未开始加载
 * ⇒ 8 秒后看门狗误判为"形象不受支持"。更糟的是那次 `eval` 的失败**完全无人知晓**。
 *
 * ## 门控语义
 *
 * 页面脚本执行完毕会由 `pet-renderer.js` 末尾主动回报 `{type:"boot"}`；
 * 收到它才代表 `window.PetRenderer` 已可用。在此之前的所有"下发"请求一律压住，
 * 只记一个**待发标记**（描述符与选项都是**全量快照**，多条请求天然合并为一条，无需队列）。
 *
 * 这样"页面多慢都不会丢命令"，而不是靠 sleep 或重试次数去猜时序。
 */
class PetRendererGate {

    private var booted = false
    private var pendingSync = false

    /** 页面脚本是否已就绪（已收到 `boot`）。 */
    val isBooted: Boolean
        get() = booted

    /** 是否有被压住的同步请求（仅用于断言与日志，不参与决策）。 */
    val hasPendingSync: Boolean
        get() = pendingSync

    /** 当前是否可以立即下发。 */
    fun canSendNow(): Boolean = booted

    /**
     * 记录"有一次下发被压住"。
     *
     * **只记布尔不排队**：下发的内容（渲染描述符 + 选项）都是**当前状态的全量快照**，
     * 补发一次即等价于补发全部；排队会让中间态噪音进入渲染器。
     */
    fun markSyncPending() {
        pendingSync = true
    }

    /**
     * 渲染器就绪（收到 `boot`）。
     *
     * @return `true` 表示此前有被压住的请求，调用方**必须立即补发一次全量同步**；
     *   `false` 表示期间没有请求，无需动作（不要无条件补发——那会在每次开窗时多打一次无谓的 eval）
     */
    fun onRendererBooted(): Boolean {
        booted = true
        val had = pendingSync
        pendingSync = false
        return had
    }

    /** 复位（测试用；服务侧每次实例化新对象，无需显式复位）。 */
    fun reset() {
        booted = false
        pendingSync = false
    }
}
