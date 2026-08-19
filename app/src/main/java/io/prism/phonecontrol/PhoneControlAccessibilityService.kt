package io.prism.phonecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.concurrent.atomic.AtomicLong

/**
 * v1 US-201（LLM 操控手机）：无障碍服务 —— 读取 UI 树 + 执行动作。
 *
 * **架构**（调研路径 A：无障碍 UI 树 + 文本模型）：
 * - [PhoneControlAccessibilityService.getUiTreeText]：rootInActiveWindow → 映射 [UiNode] →
 *   [AccessibilityUiSerializer.serialize] 结构化文本喂给通用 LLM（纯文本模型即可感知）
 * - [PhoneControlAccessibilityService.performTap/Swipe/Type/GlobalAction]：执行 LLM 决策的动作
 * - 截图（API30+）：[takeScreenshot]（避开 MediaProjection Android14+ 每次授权限制）
 *
 * **执行后校验**（防误触）：每次动作前重新获取 UI 树，校验目标节点可点击性/存在性。
 *
 * **线程安全**：`rootInActiveWindow` 仅在 onAccessibilityEvent 或显式调用时访问；
 * 工具执行在调用方协程（IO）中串行进行。
 *
 * **敏感拦截**：由 [PhoneControlSecurity] 在 [io.prism.phonecontrol.PhoneControlLocalToolExecutor]
 * 层处理（支付包名/密码节点/高危动作），服务层不重复判断。
 */
class PhoneControlAccessibilityService : AccessibilityService() {

    /** 节点序号生成器（UI 树采集时分配稳定 nid）。 */
    private val nodeCounter = AtomicLong(0L)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "手机操控无障碍服务已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Log.i(TAG, "手机操控无障碍服务已断开")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 按需拉取状态（工具调用时实时采集），此处仅保持服务活跃
    }

    override fun onInterrupt() = Unit

    /**
     * 采集当前 UI 树并序列化为文本（喂给 LLM）。
     *
     * @param maxNodes 节点上限
     * @return 结构化文本；无活动窗口/服务未连接返回 null
     */
    fun getUiTreeText(maxNodes: Int = AccessibilityUiSerializer.MAX_NODES): String? {
        val root = rootInActiveWindow ?: return null
        val rootNode = mapNode(root, maxNodes) ?: return null
        return AccessibilityUiSerializer.serialize(rootNode, maxNodes)
    }

    /**
     * 执行点击：优先节点（[AccessibilityNodeInfoCompat.performAction(ACTION_CLICK)]），
     * 兜底坐标手势。
     *
     * @param nodeId 节点序号（get_ui_state 输出中的 [N]）；null 时用坐标
     * @param x/y 点击坐标（nodeId 为 null 时使用）
     * @return 成功描述
     */
    fun performTap(nodeId: Int?, x: Int, y: Int): String {
        if (nodeId != null) {
            val node = findNodeByNid(nodeId) ?: return "错误：节点 [$nodeId] 不存在（页面可能已变化），请重新获取 UI 状态"
            if (!node.isClickable) {
                // 点击非 clickable 节点 → 尝试其可点击祖先
                val clickableAncestor = findClickableAncestor(node)
                if (clickableAncestor != null && clickableAncestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return "已点击节点 [$nodeId]（经可点击祖先）"
                }
                return "错误：节点 [$nodeId] 不可点击，请改用坐标或重新获取 UI 状态"
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "已点击节点 [$nodeId]"
            }
            return "错误：节点 [$nodeId] 点击失败，请改用坐标"
        }
        val clicked = dispatchTap(x, y)
        return if (clicked) "已点击坐标 ($x, $y)" else "错误：手势点击失败"
    }

    /** 长按节点（通过坐标手势，长按无标准 accessibility action）。 */
    fun performLongPress(nodeId: Int?, x: Int, y: Int): String {
        val (px, py) = resolvePoint(nodeId, x, y) ?: return "错误：节点/坐标无效"
        val path = Path().apply { moveTo(px.toFloat(), py.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_MS))
            .build()
        return if (dispatchGesture(gesture, null, null)) "已长按 ($px, $py)" else "错误：长按手势失败"
    }

    /** 双击节点/坐标（Open-AutoGLM 常用「点两下」打开/展开，无标准 accessibility action）。 */
    fun performDoubleTap(nodeId: Int?, x: Int, y: Int): String {
        val (px, py) = resolvePoint(nodeId, x, y) ?: return "错误：节点/坐标无效"
        val path = Path().apply { moveTo(px.toFloat(), py.toFloat()) }
        val stroke1 = GestureDescription.StrokeDescription(path, 0L, TAP_MS)
        val stroke2 = GestureDescription.StrokeDescription(path, TAP_MS + DOUBLE_TAP_GAP_MS, TAP_MS)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        return if (dispatchGesture(gesture, null, null)) "已双击 ($px, $py)" else "错误：双击手势失败"
    }

    /**
     * 查询节点及其子树文本/描述（供 [io.prism.phonecontrol.PhoneControlSecurity] 敏感目标判断）。
     *
     * **guardrail M-1 修复**：真实 UI 中按钮/列表行的标签文本几乎总在子 TextView 上，可点击
     * 容器自身 text 常为 null。仅读节点自身文本会让 node_id 点击绕过硬拦截 → 此处 BFS 聚合
     * 后代文本（受 [AccessibilityUiSerializer.MAX_TEXT_CHARS] 截断，防上下文膨胀）。
     *
     * @param nodeId 节点序号；null 时返回 null
     * @return 节点+子树文本/描述拼接（可为 null）
     */
    fun nodeTextOf(nodeId: Int?): String? {
        if (nodeId == null) return null
        val root = rootInActiveWindow ?: return null
        // 按 nid 定位节点（BFS 计数器语义与 findNodeByNid / mapNode 一致）
        val rootCompat = AccessibilityNodeInfoCompat.wrap(root)
        val locate = ArrayDeque<AccessibilityNodeInfoCompat>()
        locate.add(rootCompat)
        var idx = 0
        var target: AccessibilityNodeInfoCompat? = null
        while (locate.isNotEmpty()) {
            val n = locate.removeFirst()
            if (idx == nodeId) {
                target = n
                break
            }
            idx++
            for (i in 0 until n.childCount) {
                locate.addLast(n.getChild(i) ?: continue)
            }
        }
        val t = target ?: return null
        // BFS 聚合自身 + 后代文本（预算上限防膨胀）
        val agg = StringBuilder()
        var budget = SENSITIVE_TEXT_AGG_BUDGET
        val q = ArrayDeque<AccessibilityNodeInfoCompat>()
        q.add(t)
        while (q.isNotEmpty() && budget > 0) {
            val n = q.removeFirst()
            val text = n.text?.toString()?.trim()
            val desc = n.contentDescription?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                agg.append(text).append(' ')
                budget--
            }
            if (!desc.isNullOrBlank() && budget > 0) {
                agg.append(desc).append(' ')
                budget--
            }
            for (i in 0 until n.childCount) {
                q.addLast(n.getChild(i) ?: continue)
            }
        }
        return agg.toString().trim().takeIf { it.isNotBlank() }
    }

    /**
     * 查询坐标处最上层节点文本/描述（供坐标点击的敏感目标判断）。
     *
     * @param x/y 屏幕坐标
     * @return 命中节点文本/描述拼接（可为 null）
     */
    fun nodeTextAt(x: Int, y: Int): String? {
        val root = rootInActiveWindow ?: return null
        val compat = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(compat)
        var best: AccessibilityNodeInfoCompat? = null
        var bestArea = Long.MAX_VALUE
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val bounds = Rect()
            n.getBoundsInScreen(bounds)
            if (bounds.contains(x, y)) {
                // 取包含该坐标的最小可见节点（面积最小者优先，最接近点击点）
                val area = bounds.width().toLong() * bounds.height().toLong()
                if (area < bestArea) {
                    bestArea = area
                    best = n
                }
            }
            for (i in 0 until n.childCount) queue.addLast(n.getChild(i) ?: continue)
        }
        val b = best ?: return null
        return listOfNotNull(b.text?.toString(), b.contentDescription?.toString())
            .joinToString(" ").takeIf { it.isNotBlank() }
    }

    /**
     * 查询目标节点为 [UiNode]（供密码/验证码等敏感节点判断）。
     *
     * @param nodeId 节点序号；优先
     * @param x/y 坐标兜底（nodeId 为 null 时按坐标找最上层可交互节点）
     * @return 映射后的 [UiNode]；未命中返回 null
     */
    fun nodeAt(nodeId: Int?, x: Int, y: Int): UiNode? {
        val root = rootInActiveWindow ?: return null
        if (nodeId != null) {
            val compat = findNodeByNid(nodeId) ?: return null
            return mapSingleNode(compat, nodeId)
        }
        // 坐标兜底：找最上层可交互（可点击/可输入）节点
        val c = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(c)
        var best: AccessibilityNodeInfoCompat? = null
        var bestArea = Long.MAX_VALUE
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val bounds = Rect()
            n.getBoundsInScreen(bounds)
            if (bounds.contains(x, y) && (n.isClickable || n.isEditable)) {
                val area = bounds.width().toLong() * bounds.height().toLong()
                if (area < bestArea) {
                    bestArea = area
                    best = n
                }
            }
            for (i in 0 until n.childCount) queue.addLast(n.getChild(i) ?: continue)
        }
        val b = best ?: return null
        return mapSingleNode(b, 0)
    }

    /** 滑动（start→end 坐标）。 */
    fun performSwipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = SWIPE_MS): String {
        val path = Path().apply { moveTo(fromX.toFloat(), fromY.toFloat()); lineTo(toX.toFloat(), toY.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return if (dispatchGesture(gesture, null, null)) "已滑动 ($fromX,$fromY)→($toX,$toY)" else "错误：滑动手势失败"
    }

    /** 输入文本到节点（ACTION_SET_TEXT；失败降级为剪贴板+长按粘贴的占位提示，由工具层处理）。 */
    fun performType(nodeId: Int?, x: Int, y: Int, text: String): String {
        val node = if (nodeId != null) findNodeByNid(nodeId) else findEditableAt(x, y)
        if (node == null) return "错误：未找到输入框（可尝试先点击输入框聚焦）"
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            return "已在节点输入文本"
        }
        return "错误：节点不支持直接输入（ACTION_SET_TEXT 失败），可降级为粘贴方式"
    }

    /** 全局动作（返回/主页/最近任务）。 */
    fun executeGlobalAction(action: Int): String {
        // super.performGlobalAction 是 AccessibilityService 原生 Boolean 返回实现
        return if (super.performGlobalAction(action)) "已执行全局动作" else "错误：全局动作执行失败"
    }

    /** 启动应用（包名，launcher intent）。 */
    fun launchApp(packageName: String): String {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return "错误：未找到应用 $packageName 的启动入口"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            "已启动应用 $packageName"
        } catch (e: Exception) {
            "错误：启动应用失败（${e::class.simpleName}）"
        }
    }

    /**
     * 截图（API30+ 无障碍截图）。返回 data URL（`data:image/jpeg;base64,...`）。
     *
     * **US-203 降采样**：复用 N3 降采样链路思想（最长边 [SCREENSHOT_MAX_EDGE_PX] 等比缩放 +
     * JPEG q80），控 base64 体积（防随会话 JSON 膨胀）；超过 [SCREENSHOT_MAX_BASE64_LEN]
     * 时返回 null（工具层提示跳过，不阻塞）。
     *
     * @return data URL；不支持/失败/超限返回 null
     */
    fun captureScreenshot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            // API30+：takeScreenshot(displayId, executor, callback)（无 context/handler 参数，
            // 早期错误签名已修正）。主线程执行器运行回调（快速 complete），协程侧 await 结果。
            val future = java.util.concurrent.CompletableFuture<AccessibilityService.ScreenshotResult>()
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        future.complete(screenshot)
                    }

                    override fun onFailure(errorCode: Int) {
                        future.completeExceptionally(
                            java.io.IOException("takeScreenshot onFailure code=$errorCode")
                        )
                    }
                }
            )
            val screenshot = future.get(SCREENSHOT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            val bitmap = screenshot.asBitmapCompat()
            val scaled = downsampleBitmap(bitmap)
            if (scaled !== bitmap) bitmap.recycle()
            val bytes = java.io.ByteArrayOutputStream().apply {
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, this)
            }
            if (scaled !== bitmap) scaled.recycle()
            val base64 = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
            if (base64.length > SCREENSHOT_MAX_BASE64_LEN) {
                Log.w(TAG, "captureScreenshot 体积超限（${base64.length} > $SCREENSHOT_MAX_BASE64_LEN），跳过")
                return null
            }
            "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {
            Log.w(TAG, "captureScreenshot 失败（${e::class.simpleName}）")
            null
        }
    }

    /** 等比降采样（最长边 ≤ [SCREENSHOT_MAX_EDGE_PX]；已达标则原样返回）。 */
    internal fun downsampleBitmap(bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val scale = computeScreenshotScale(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /**
     * [AccessibilityService.ScreenshotResult.asBitmap] 为 @SystemApi 隐藏 API，直接调用编译失败，
     * 经反射取 Bitmap（Android 11+ 无障碍截图，SDK_INT ≥ R 时安全）。
     */
    private fun AccessibilityService.ScreenshotResult.asBitmapCompat(): android.graphics.Bitmap {
        @Suppress("DEPRECATION")
        val method = javaClass.getMethod("asBitmap")
        return method.invoke(this) as android.graphics.Bitmap
    }

    /**
     * 计算截图缩放因子（纯函数可测）：最长边缩放到 [SCREENSHOT_MAX_EDGE_PX]。
     * 已达标（最长边 ≤ 上限）返回 1.0（不缩放）。
     */
    internal fun computeScreenshotScale(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 1f
        val maxEdge = maxOf(width, height)
        if (maxEdge <= SCREENSHOT_MAX_EDGE_PX) return 1f
        return SCREENSHOT_MAX_EDGE_PX.toFloat() / maxEdge.toFloat()
    }

    // ==================== 内部工具 ====================

    /** 将 AccessibilityNodeInfo 映射为 [UiNode]（BFS，分配稳定 nid）。 */
    internal fun mapNode(
        root: AccessibilityNodeInfo,
        maxNodes: Int = AccessibilityUiSerializer.MAX_NODES
    ): UiNode? {
        val compat = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(compat)
        val mapped = HashMap<AccessibilityNodeInfoCompat, UiNode>()
        var count = 0
        while (queue.isNotEmpty() && count < maxNodes) {
            val n = queue.removeFirst()
            if (mapped.containsKey(n)) continue
            val node = mapSingleNode(n, count)
            mapped[n] = node
            count++
            // 子节点
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                queue.addLast(child)
            }
        }
        // 组装 children（依据 AccessibilityNodeInfoCompat 引用）
        val rootCompat = AccessibilityNodeInfoCompat.wrap(root)
        fun attach(parent: AccessibilityNodeInfoCompat, out: UiNode): UiNode {
            val children = mutableListOf<UiNode>()
            for (i in 0 until parent.childCount) {
                val child = parent.getChild(i) ?: continue
                val childUi = mapped[child] ?: continue
                if (childUi.nid != out.nid) children.add(attach(child, childUi))
            }
            return out.copy(children = children)
        }
        val rootUi = mapped[rootCompat] ?: return null
        return attach(rootCompat, rootUi)
    }

    /** 单个节点映射（纯逻辑）。 */
    internal fun mapSingleNode(node: AccessibilityNodeInfoCompat, nid: Int): UiNode {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return UiNode(
            nid = nid,
            viewId = node.viewIdResourceName?.substringAfterLast('/'),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            bounds = intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable || node.className?.toString()?.contains("EditText") == true,
            password = node.isPassword,
            children = emptyList()
        )
    }

    /** 按 nid 查找当前树中的节点（重新采集，页面变化时 nid 可能失效）。 */
    internal fun findNodeByNid(nid: Int): AccessibilityNodeInfoCompat? {
        val root = rootInActiveWindow ?: return null
        val compat = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(compat)
        var idx = 0
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (idx == nid) return n
            idx++
            for (i in 0 until n.childCount) {
                queue.addLast(n.getChild(i) ?: continue)
            }
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    private fun findEditableAt(x: Int, y: Int): AccessibilityNodeInfoCompat? {
        val root = rootInActiveWindow ?: return null
        val compat = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(compat)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val bounds = Rect()
            n.getBoundsInScreen(bounds)
            if (n.isEditable && bounds.contains(x, y)) return n
            for (i in 0 until n.childCount) queue.addLast(n.getChild(i) ?: continue)
        }
        return null
    }

    private fun resolvePoint(nodeId: Int?, x: Int, y: Int): IntArray? {
        if (nodeId == null) return intArrayOf(x, y)
        val node = findNodeByNid(nodeId) ?: return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return null
        return intArrayOf(bounds.centerX(), bounds.centerY())
    }

    private fun dispatchTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAG = "PhoneControl"

        /** 当前服务实例（工具执行器经此访问；未连接为 null）。 */
        @Volatile
        var instance: PhoneControlAccessibilityService? = null

        /**
         * 查询**系统真实的无障碍启用状态**（v1 真机二次修复 Issue 4）。
         *
         * 进程内 [instance] 是本地 static，服务 unbind/进程被杀重建/冷启动重绑前会短暂为 null，
         * 但系统设置里无障碍**仍已启用**——打开重内存 App（如微信）时极易落入该窗口，
         * 误报"未启用"。本函数读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
         * （分号分隔的"包名/服务组件"列表）判断本服务是否已被系统启用，用于区分
         * "已启用但实例未连（重连中）"与"未启用"。
         */
        fun isEnabledInSystem(context: android.content.Context): Boolean {
            return try {
                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.split(':').any { entry ->
                    entry.contains(context.packageName) && entry.contains("PhoneControlAccessibilityService")
                }
            } catch (e: Exception) {
                Log.w(TAG, "isEnabledInSystem 查询失败（${e::class.simpleName}）")
                false
            }
        }

        private const val TAP_MS = 60L
        private const val LONG_PRESS_MS = 600L
        private const val SWIPE_MS = 300L

        /** 双击两击间隔（毫秒）。 */
        private const val DOUBLE_TAP_GAP_MS = 80L

        /** 截图最长边（像素）——降采样目标，控 base64 体积（US-203）。 */
        const val SCREENSHOT_MAX_EDGE_PX = 1024

        /** 截图 JPEG 质量。 */
        const val SCREENSHOT_JPEG_QUALITY = 80

        /** 截图 base64 长度上限（超限跳过，防会话 JSON 膨胀）。 */
        const val SCREENSHOT_MAX_BASE64_LEN = 400_000

        /** 截图超时（毫秒）——防 takeScreenshot 回调永不到达导致 future.get 永久阻塞。 */
        private const val SCREENSHOT_TIMEOUT_MS = 10_000L

        /** 节点子树文本聚合预算（guardrail M-1：聚合后代文本时最多取 N 条，防上下文膨胀）。 */
        private const val SENSITIVE_TEXT_AGG_BUDGET = 12
    }
}
