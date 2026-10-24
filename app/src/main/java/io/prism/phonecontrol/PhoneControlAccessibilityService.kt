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
import androidx.annotation.RequiresApi
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

    /**
     * v1 批次10（Bug A1 修复）：最近一次已知的窗口根节点缓存。
     * [onAccessibilityEvent] 在 `TYPE_WINDOW_STATE_CHANGED` 时更新；供
     * [currentRoot] 在 [getRootInActiveWindow] 返回 null（窗口切换过渡期）时兜底使用。
     * 参考 Android 官方 codelab「缓存最后一次已知根节点」方案。
     */
    @Volatile
    private var lastKnownRoot: AccessibilityNodeInfo? = null

    /** 保护 [lastKnownRoot] 读改写（事件线程写入 + 工具协程读取）与回收的锁。 */
    private val cacheLock = Any()

    /**
     * v1 批次11（A 致命修复，OCR 坐标空间还原）：最近一次截图的**原始屏幕尺寸**（未降采样）。
     *
     * OCR 在降采样位图（最长边 ≤1024px）上识别，返回坐标是降采样空间；[captureScreenshot] 在
     * 降采样前记录原始宽高，供 [lastScreenshotScreenSize] 计算缩放因子，把 OCR 坐标还原到
     * 屏幕空间（与 tap/UI 树 bounds 同一坐标系），否则 tap 整体缩放错位（真机"点不到"根因）。
     */
    @Volatile
    private var lastScreenshotOrigW: Int = 0

    @Volatile
    private var lastScreenshotOrigH: Int = 0

    /**
     * 批次20（BG-002，ADR-045）：后台执行面——非 null 时所有节点感知/操作路由到该
     * displayId 的虚拟屏窗口（getWindowsOnAllDisplays），主屏前台窗口不再参与。
     *
     * phone_control 工具为串行执行（SkillExecutor while 循环逐工具），volatile 单值
     * 切换安全；由执行器在后台工具调用前后 set/reset。
     */
    @Volatile
    private var executionDisplayId: Int? = null

    /**
     * 切换执行面（BG-002）。
     *
     * @param displayId 虚拟屏 displayId；null 切回前台（默认主屏活动窗口）
     */
    fun setExecutionDisplay(displayId: Int?) {
        executionDisplayId = displayId
    }

    /** 当前是否处于后台执行面（供服务内手势受限判断与执行器诊断）。 */
    val isBackgroundExecution: Boolean
        get() = executionDisplayId != null

    /**
     * 批次20（BG-002）：当前执行面的根节点。
     *
     * - 后台执行面（executionDisplayId != null，需 API 30+）：从
     *   [getWindowsOnAllDisplays] 定位该 display 的活动窗口根（优先 active 最高 layer）；
     * - 前台执行面：走 [currentRoot]（rootInActiveWindow → lastKnownRoot → getWindows 兜底）。
     *
     * @return 调用方自持根节点引用（可能为 null）；调用方用毕 recycle（同 currentRoot 语义）
     */
    fun executionRoot(): AccessibilityNodeInfo? {
        val displayId = executionDisplayId ?: return currentRoot()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val all = getWindowsOnAllDisplays() ?: return null
        val wins = all.get(displayId) ?: run {
            Log.w(TAG, "executionRoot: displayId=$displayId 无窗口（目标 App 可能未在虚拟屏运行）")
            return null
        }
        val active = wins.filter { it.isActive }
        for (window in (active.ifEmpty { wins }).sortedByDescending { it.layer }) {
            val root = window.root ?: continue
            if (root.packageName != null) return root
            root.recycle()
        }
        Log.w(TAG, "executionRoot: displayId=$displayId 共 ${wins.size} 个窗口均无可用根")
        return null
    }

    /**
     * 批次20（BG-002）：查询指定 display 上最上层活动窗口的包名
     * （后台 launch_app 校验"目标 App 是否真的进入虚拟屏"，对应前台 currentForegroundPackage）。
     *
     * @return 包名；无窗口返回 null
     */
    fun backgroundWindowPackage(displayId: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val wins = getWindowsOnAllDisplays()?.get(displayId) ?: return null
        val best = wins.filter { it.isActive }.maxByOrNull { it.layer } ?: wins.firstOrNull()
            ?: return null
        val root = best.root ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // v1 批次14（TKN-V1B14-KEEPALIVE-BUG-001）：不再在此无条件启动保活前台服务。批次11 F2 的
        // 「连上即常驻」策略经真机取证（docs/reports/2026-08-23-keepalive-bug-debug.md：服务常驻
        // 1d8h10m + 通知不间断 + START_STICKY 自动重启）修订为「任务期动态保活」——由
        // [PhoneControlSessionManager] 在首个 phone_control__* 工具调用时启动、空闲 120s 停止
        // （ADR-041）。任务进行中防 MIUI 回收的能力保持不变。
        // 修复轮八：启动对账——清理进程死亡残留的模拟副屏设置（叠加小窗滞留兜底）；
        // 后台线程执行（Shizuku 绑定 + settings get 最长 5s，不能在主线程）。
        Thread { runCatching { io.prism.phonecontrol.bg.VirtualDisplayManager.reconcileStaleOverlay(this) } }.start()
        Log.i(TAG, "手机操控无障碍服务已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        PhoneControlKeepAliveService.stop(this)
        // v1 批次14：会话状态复位，保证下次连接后首个工具调用重新走 start 分支
        PhoneControlSessionManager.reset()
        // guardrail（批次10）：服务销毁时回收缓存根节点，杜绝 AccessibilityNodeInfo 句柄泄漏
        synchronized(cacheLock) {
            lastKnownRoot?.recycle()
            lastKnownRoot = null
        }
        Log.i(TAG, "手机操控无障碍服务已断开")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 维护最后一次已知根节点缓存（Bug A1）：窗口状态变化时记录源节点为最近根。
        // 仅缓存有效节点（避免把 null/回收节点写入）；nodeCounter 语义与 mapNode 一致。
        // 批次20（guardrail P2-3）：仅缓存**默认屏**事件——虚拟屏窗口事件入缓存会让前台
        // 执行面的 currentRoot() 第二级兜底命中虚拟屏根（跨屏误读主屏内容）。
        if (event != null && event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 批次22 修复轮七（lint NewApi）：AccessibilityRecord.getDisplayId 为 API 33+，
            // 原门 R(30) 在 30-32 上会 NoSuchMethodError——提至 TIRAMISU(33)（目标真机 API 36
            // 行为不变；30-32 退化为不过滤虚拟屏事件入缓存，仅影响该区间的兜底纯度）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && event.displayId != Display.DEFAULT_DISPLAY) {
                return
            }
            val src = event.source
            if (src != null) {
                synchronized(cacheLock) {
                    val prev = lastKnownRoot
                    // guardrail（批次10）：缓存必须用 obtain() 复制一份自持句柄。event.source 由框架
                    // 拥有，回调返回后可能被框架 recycle；直接持有/回收它会误伤框架生命周期。
                    // obtain(src) 返回独立副本，由本缓存负责 recycle。
                    lastKnownRoot = AccessibilityNodeInfo.obtain(src)
                    // 替换缓存时回收旧的自持副本，杜绝句柄泄漏
                    if (prev != null) prev.recycle()
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    /**
     * 采集当前 UI 树并序列化为文本（喂给 LLM）。
     *
     * @param maxNodes 节点上限
     * @return 结构化文本；无活动窗口/服务未连接返回 null
     */
    fun getUiTreeText(maxNodes: Int = AccessibilityUiSerializer.MAX_NODES): String? {
        val root = executionRoot() ?: return null
        try {
            val rootNode = mapNode(root, maxNodes) ?: return null
            return AccessibilityUiSerializer.serialize(rootNode, maxNodes)
        } finally {
            // executionRoot 返回自持硬引用，用毕回收，杜绝句柄泄漏
            root.recycle()
        }
    }

    /**
     * v1 批次10（Bug A1 修复）：获取当前可序列化的根节点，带「缓存 + 窗口遍历」兜底。
     *
     * **背景（真机日志 + 官方调研）**：[getRootInActiveWindow] 在窗口切换过渡期 / 新 App
     * 尚未首绘 / 锁屏 / FLAG_SECURE 安全窗口时返回 null（Android 官方 codelab"缓存最后一次
     * 已知根节点"方案 + auto-mobile #775）。`launch_app` 切到第三方 App 后立即 get_ui_state
     * 极易命中该窗口 → 回灌"无法读取"→ LLM 反复重试（round=38）。
     *
     * **修复（缓存根 + getWindows 遍历）**：
     * 1. 优先 `getRootInActiveWindow()`；
     * 2. 为 null 时用 [lastKnownRoot]（[onAccessibilityEvent] 维护的最近一次有效根）；
     * 3. 仍为 null 时遍历 [getWindows]() 找 `it.isActive()` 且有根的活动窗口（多窗口/分屏兜底）。
     * 三者皆空才返回 null。
     *
     * @return 可用根节点的调用方自持硬引用（可能为 null）；调用方用毕后必须 recycle。
     *   guardrail（批次10）：rootInActiveWindow / getWindows 兜底均为调用方自持引用直接返回；
     *   仅共享缓存 [lastKnownRoot] 返回 obtain 副本（防止事件线程并发 recycle 正在遍历的节点）。
     */
    private fun currentRoot(): AccessibilityNodeInfo? {
        // rootInActiveWindow / getWindows().root 返回的是调用方自持的硬引用（须由调用方 recycle），
        // 直接返回即可；仅共享缓存 lastKnownRoot 需 obtain 副本（防止事件线程并发 recycle 正在遍历的节点）。
        rootInActiveWindow?.let {
            // v1 批次11 诊断：跨 App 读取到根时记录其包名，便于定位"读到哪个窗口"
            Log.i(TAG, "currentRoot: hit rootInActiveWindow pkg=${it.packageName}")
            return it
        }
        Log.w(TAG, "currentRoot: rootInActiveWindow=null（跨 App 读取失败，兜底 lastKnownRoot）")
        synchronized(cacheLock) {
            lastKnownRoot?.let {
                Log.i(TAG, "currentRoot: hit lastKnownRoot pkg=${it.packageName}")
                return AccessibilityNodeInfo.obtain(it)
            }
        }
        Log.w(TAG, "currentRoot: lastKnownRoot=null，遍历 getWindows(${windows?.size ?: -1})")
        // 遍历全部窗口，优先活动且具内容根的窗口（getWindows 需 flagRetrieveInteractiveWindows）
        val allWindows = windows.orEmpty()
        val active = allWindows.filter { it.isActive }
        for (window in active.sortedByDescending { it.layer }) {
            val root = window.root ?: continue
            // AccessibilityWindowInfo 用 getBoundsInScreen 判断是否具可视区域（无直接 width/height getter）
            val bounds = android.graphics.Rect()
            window.getBoundsInScreen(bounds)
            Log.i(TAG, "currentRoot: window layer=${window.layer} pkg=${root.packageName} visible=${!bounds.isEmpty}")
            if (root.packageName != null && !bounds.isEmpty) {
                return root
            }
            // guardrail（批次10/低风险）：未命中（无包名/无可视区）的 root 立即回收，杜绝句柄泄漏
            root.recycle()
        }
        Log.w(TAG, "currentRoot: 全部兜底失败，总窗口=${allWindows.size} active=${active.size}")
        return null
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
        // 批次20（BG-002 纵深防御）+ 批次21（SZ-003，ADR-046）：后台执行面下坐标路径依赖
        // dispatchGesture（仅主屏）——Shizuku 就绪时改经 shell `input -d` 注入到虚拟屏
        //（先吸附可点击中心，语义与前台一致）；否则维持受限文案。
        if (isBackgroundExecution && nodeId == null) {
            if (io.prism.phonecontrol.bg.ShizukuShell.isReady()) {
                val (sx, sy) = snapToClickableCenter(x, y) ?: (x to y)
                return if (io.prism.phonecontrol.bg.ShizukuShell.inputTap(
                        executionDisplayId ?: return bgGestureBlocked("坐标点击"), sx, sy
                    )
                ) "已点击坐标 ($sx,$sy)（shell 注入到虚拟屏）" else "错误：后台手势注入失败（Shizuku 命令执行异常），请改用节点操作或切回前台模式"
            }
            return bgGestureBlocked("坐标点击")
        }
        if (nodeId != null) {
            val node = findNodeByNid(nodeId) ?: return "错误：节点 [$nodeId] 不存在（页面可能已变化），请重新获取 UI 状态"
            if (!node.isClickable) {
                // 点击非 clickable 节点 → 尝试其可点击祖先
                val clickableAncestor = findClickableAncestor(node)
                if (clickableAncestor != null) {
                    // 批次22 修复轮七（guardrail S1）：祖先点击前**复检其聚合文本**——D2 候选
                    // 扩大后"干净查询词/干净子节点 → 敏感可点击祖先"成为常见路径（Compose/
                    // 经典布局普遍"文本挂子节点、click 挂容器"），仅按原节点文本判断会放行
                    // （BFS 子节点精确命中反超敏感祖先成为 bestNid，matchedText 只含子树文本）
                    val ancestorAgg = aggregateNodeText(clickableAncestor)
                    if (PhoneControlSecurity.isSensitiveTargetText(ancestorAgg)) {
                        return "错误：目标所在的可点击容器含敏感内容（${ancestorAgg?.take(30)}），已硬拦截" +
                            "（支付/密码/验证码类操作请用 take_over 交还用户手动）"
                    }
                    if (clickableAncestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return "已点击节点 [$nodeId]（经可点击祖先）"
                    }
                }
                // 批次22 修复轮七（2026-09-07）：不可点击且无可点击祖先（或祖先点击失败）→
                // 按 bounds 中心坐标注入降级（后台走 shell tap/前台走手势），不再直接拒绝——
                // Compose 扁平化树中大量文本节点无 clickable 语义也无 clickable 祖先
                // （DeepSeek 输入框实证），bounds 即点击目标
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    val cx = bounds.centerX()
                    val cy = bounds.centerY()
                    // 批次22 修复轮七（guardrail S2/Q4）：①落点**复检**敏感文本与密码节点——
                    // 服务层内部降级不经 executor 的 nodeTextAt/isPasswordNode 回读，必须在
                    // 服务层补齐；②跳过坐标吸附（snapToClickableCenter 可能把点击重定向到
                    // 邻近节点改变语义），直接点 bounds 中心
                    val landingText = nodeTextAt(cx, cy)
                    if (PhoneControlSecurity.isSensitiveTargetText(landingText)) {
                        return "错误：点击落点含敏感内容（${landingText?.take(30)}），已硬拦截" +
                            "（支付/密码/验证码类操作请用 take_over 交还用户手动）"
                    }
                    nodeAt(null, cx, cy)?.let { landing ->
                        if (PhoneControlSecurity.isPasswordNode(landing)) {
                            return "错误：点击落点为密码/验证码节点，已硬拦截（请用 take_over 交还用户手动输入）"
                        }
                    }
                    return if (isBackgroundExecution) {
                        if (!io.prism.phonecontrol.bg.ShizukuShell.isReady()) return bgGestureBlocked("坐标点击")
                        val bgDisplayId = executionDisplayId ?: return bgGestureBlocked("坐标点击")
                        if (io.prism.phonecontrol.bg.ShizukuShell.inputTap(bgDisplayId, cx, cy)) {
                            "已点击节点 [$nodeId]（bounds 中心坐标注入，$cx,$cy）"
                        } else {
                            "错误：后台手势注入失败（Shizuku 命令执行异常），请改用坐标或切回前台模式"
                        }
                    } else {
                        if (dispatchTap(cx, cy)) "已点击节点 [$nodeId]（bounds 中心坐标，$cx,$cy）"
                        else "错误：手势点击失败"
                    }
                }
                return "错误：节点 [$nodeId] 不可点击，请改用坐标或重新获取 UI 状态"
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "已点击节点 [$nodeId]"
            }
            return "错误：节点 [$nodeId] 点击失败，请改用坐标"
        }
        // v1 批次11（坐标吸附）：LLM 给的坐标常偏离目标中心（尤其 OCR 定位不准），
        // 吸附到最近可点击节点中心（阈值内）降低误触；无命中则原坐标点击。
        val snapped = snapToClickableCenter(x, y)
        return if (snapped != null) {
            val clicked = dispatchTap(snapped.first, snapped.second)
            if (clicked) "已点击坐标 ($x,$y)→吸附到可点击节点中心 (${snapped.first},${snapped.second})"
            else "错误：手势点击失败"
        } else {
            val clicked = dispatchTap(x, y)
            if (clicked) "已点击坐标 ($x, $y)" else "错误：手势点击失败"
        }
    }

    /**
     * QQ 小号助手（ADR-062；2026-10-19 真机 E2E DEF-112）：按节点 bounds 中心**经 Shizuku shell**
     * 注入点击（后台执行面）。
     *
     * **为什么需要它**（真机取证）：后台执行面对**跨用户副屏**（XSpace user 999）窗口调用节点
     * `ACTION_CLICK` **返回成功但页面不跳转**（日志 `tap=已点击节点 [7]` 但会话页 `id/input` 恒
     * 不出现）——疑似框架对"服务所在用户 ≠ 窗口所属用户"的节点动作静默丢弃。真实触摸经
     * shell `input -d <id> tap` 对副屏焦点窗口有效（ADR-046），故后台导航统一走本方法。
     *
     * 前台执行面下等价于普通按 bounds 点击（不影响既有语义）。
     *
     * @param nid 节点序号（[findNodeByTextNid]/[findNodeByViewIdNid] 契约）
     * @return 结果文案（成功/可读失败）
     */
    fun performTapNodeBoundsShell(nid: Int): String {
        val node = findNodeByNid(nid) ?: return "错误：节点 [$nid] 不存在（页面可能已变化）"
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.isEmpty) return "错误：节点 [$nid] 无可用 bounds"
        // guardrail N-5（纵深防御）：与 [performTap] 一致的落点敏感复检——节点自身文本含
        // 支付/密码/验证码等敏感内容时硬拦截（永不执行）
        val nodeText = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ").trim()
        if (PhoneControlSecurity.isSensitiveTargetText(nodeText)) {
            return "错误：目标节点含敏感内容（${nodeText.take(30)}），已硬拦截"
        }
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        if (!isBackgroundExecution) {
            return if (dispatchTap(cx, cy)) "已点击节点 [$nid]（bounds 中心 $cx,$cy）" else "错误：手势点击失败"
        }
        if (!io.prism.phonecontrol.bg.ShizukuShell.isReady()) return bgGestureBlocked("坐标点击")
        val displayId = executionDisplayId ?: return bgGestureBlocked("坐标点击")
        return if (io.prism.phonecontrol.bg.ShizukuShell.inputTap(displayId, cx, cy)) {
            "已点击节点 [$nid]（bounds 中心 $cx,$cy shell 注入）"
        } else {
            "错误：后台手势注入失败（Shizuku 命令执行异常）"
        }
    }

    /**
     * v1 批次11（坐标吸附）：把目标坐标吸附到最近的可点击节点中心。
     *
     * **背景（真机 + 调研）**：LLM 基于 OCR/截图给的坐标常偏离真实可点击中心（OCR 识别框不居中、
     * 模型估算误差），直接点易误触。taproot「坐标吸附」思路：在 [SNAP_THRESHOLD_PX] 阈值内
     * 找距目标最近的可点击节点，点其中心。
     *
     * @param x/y 目标坐标
     * @return 吸附后的 (cx, cy)；无命中返回 null（按原坐标点）
     */
    private fun snapToClickableCenter(x: Int, y: Int): Pair<Int, Int>? {
        val root = executionRoot() ?: return null
        val compat = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(compat)
        var best: AccessibilityNodeInfoCompat? = null
        var bestDistSq = Long.MAX_VALUE
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val bounds = Rect()
            n.getBoundsInScreen(bounds)
            if (n.isClickable && !bounds.isEmpty) {
                val cx = bounds.centerX()
                val cy = bounds.centerY()
                val dx = (cx - x).toLong()
                val dy = (cy - y).toLong()
                val distSq = dx * dx + dy * dy
                // guardrail 低风险（TKN-V1B11-GUARDRAIL-001）：吸附不落到敏感节点（支付/密码等）上，
                // 防止"坐标吸附把点击重定向到邻近敏感节点"绕过敏感拦截（纵深防御）。
                val agg = aggregateNodeText(n)
                if (PhoneControlSecurity.isSensitiveTargetText(agg)) continue
                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    best = n
                }
            }
            for (i in 0 until n.childCount) queue.addLast(n.getChild(i) ?: continue)
        }
        val b = best ?: return null
        val bx = Rect().also { b.getBoundsInScreen(it) }
        val dist = kotlin.math.sqrt(bestDistSq.toDouble())
        if (dist > SNAP_THRESHOLD_PX) return null
        return bx.centerX() to bx.centerY()
    }

    /** 长按节点（通过坐标手势，长按无标准 accessibility action）。 */
    fun performLongPress(nodeId: Int?, x: Int, y: Int): String {
        // 批次21（SZ-003，ADR-046）：后台下长按经 shell 慢速 swipe（同点 600ms）注入虚拟屏
        if (isBackgroundExecution) {
            if (!io.prism.phonecontrol.bg.ShizukuShell.isReady()) return bgGestureBlocked("长按")
            val (px, py) = resolvePoint(nodeId, x, y) ?: return "错误：节点/坐标无效"
            return if (io.prism.phonecontrol.bg.ShizukuShell.inputSwipe(
                    executionDisplayId ?: return bgGestureBlocked("长按"), px, py, px, py, LONG_PRESS_MS
                )
            ) "已长按 ($px, $py)（shell 注入到虚拟屏）" else "错误：后台长按注入失败（Shizuku 命令执行异常）"
        }
        val (px, py) = resolvePoint(nodeId, x, y) ?: return "错误：节点/坐标无效"
        val path = Path().apply { moveTo(px.toFloat(), py.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_MS))
            .build()
        return if (dispatchGesture(gesture, null, null)) "已长按 ($px, $py)" else "错误：长按手势失败"
    }

    /** 双击节点/坐标（Open-AutoGLM 常用「点两下」打开/展开，无标准 accessibility action）。 */
    fun performDoubleTap(nodeId: Int?, x: Int, y: Int): String {
        if (isBackgroundExecution) return bgGestureBlocked("双击")
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
        val root = executionRoot() ?: return null
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
        return aggregateNodeText(t)
    }

    /**
     * 聚合节点自身 + 后代文本/描述（预算上限防膨胀）—— [nodeTextOf] / [findNodeByTextNid]
     * 共享的子树文本抽取（guardrail M-1：可点击容器的标签文本几乎总在子 TextView 上）。
     *
     * @param node 起始节点
     * @return 拼接文本；空返回 null
     */
    private fun aggregateNodeText(node: AccessibilityNodeInfoCompat): String? {
        val agg = StringBuilder()
        var budget = TEXT_AGG_BUDGET
        val q = ArrayDeque<AccessibilityNodeInfoCompat>()
        q.add(node)
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
     * v1 批次11（C 文本锚点）：按文本模糊匹配定位 UI 树**可操作**节点（BFS）。
     *
     * 供 `tap(text=...)` 优先走 UI 树（比 OCR 更可靠）：聚合可点击/可输入节点的子树文本
     * （同 [nodeTextOf] 语义 + 预算上限），按 [textSimilarity] 选最佳匹配，返回其 BFS 序号
     * （nid，与 get_ui_state `[N]` / [findNodeByNid] 一致）。未命中返回 null（调用方兜底 OCR）。
     *
     * @param query 目标文本（如"发送"）
     * @param threshold 相似度阈值（低于不采纳）
     * @return 最佳匹配节点 nid；无命中返回 null
     */
    fun findNodeByTextNid(query: String, threshold: Float = TEXT_MATCH_THRESHOLD): Int? {
        if (query.isBlank()) return null
        val root = executionRoot() ?: return null
        val compat = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(compat)
        var idx = 0
        var bestNid: Int? = null
        var bestScore = threshold
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            // 可操作节点聚合子树文本（既有语义）；批次22 修复轮七（2026-09-07）：**非可操作
            // 节点改用自身文本参与匹配**——Compose 扁平化树中文本常挂在非 clickable 的
            // TextView 上（DeepSeek 输入框「发消息或按住说话」实证），原过滤导致 tap(text)
            // 恒未命中而 get_ui_state 全节点可见（感知/操作域不一致）。自身文本取值 O(1)，
            // 避免子树聚合的 O(n²) 回归；nid 序号对所有节点递增，与 findNodeByNid/get_ui_state
            // 契约不变。
            val agg = if (n.isClickable || n.isEditable) {
                aggregateNodeText(n)
            } else {
                listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
                    .joinToString(" ").trim().takeIf { it.isNotBlank() }
            }
            if (agg != null) {
                val score = textSimilarity(query, agg)
                if (score > bestScore) {
                    bestScore = score
                    bestNid = idx
                }
            }
            idx++
            for (i in 0 until n.childCount) {
                queue.addLast(n.getChild(i) ?: continue)
            }
        }
        return bestNid
    }

    /**
     * QQ 小号助手（ADR-062，M0 PoC 结论）：按 **viewIdResourceName 尾段** 定位节点 nid（BFS）。
     *
     * M0 真机实证 QQ 暴露稳定 resource-id（`id/input`、`id/send_btn`），id 定位比文本锚点
     * 更抗版本漂移（QQ 文案可能变，控件 id 在版本内稳定）。nid 契约与 [findNodeByNid] /
     * [findNodeByTextNid] 一致（全节点 BFS 序号）。
     *
     * @param viewId resource-id 尾段（如 `input`、`send_btn`；匹配 `pkg:id/<viewId>`）
     * @return 命中节点 nid；无命中返回 null
     */
    fun findNodeByViewIdNid(viewId: String): Int? {
        if (viewId.isBlank()) return null
        val root = executionRoot() ?: return null
        val compat = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(compat)
        var idx = 0
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val rid = n.viewIdResourceName
            if (rid != null && rid.substringAfterLast('/') == viewId) {
                return idx
            }
            idx++
            for (i in 0 until n.childCount) {
                queue.addLast(n.getChild(i) ?: continue)
            }
        }
        return null
    }

    /**
     * 读取当前执行面树中**最后一条消息气泡**的方向与文本（ADR-062 双向对话；2026-10-19 真机
     * 结构实证）。
     *
     * 判据（QQ 真机实证）：消息文本节点 resource-id 尾段 = `mjn`（class=TextView），**收到的
     * 消息（对方）左对齐**（气泡左侧留白小），**发出的消息（自己）右对齐**；取 bounds.bottom
     * 最大（最靠下 = 时间上最后一条）的气泡为"最后一条"。方向按左右留白比较判（对任意宽度成立）。
     *
     * @param messageViewId 消息文本节点 resource-id 尾段（QQ=`mjn`）
     * @return (incoming 是否对方发来, 文本)；无消息/树不可读返回 null
     */
    fun lastMessageBubble(messageViewId: String): Pair<Boolean, String>? {
        if (messageViewId.isBlank()) return null
        val root = executionRoot() ?: return null
        try {
            val rootBounds = Rect().also { root.getBoundsInScreen(it) }
            val leftMarginRef = rootBounds.left
            val rightMarginRef = rootBounds.right
            var bestBottom = Int.MIN_VALUE
            var bestIncoming = false
            var bestText: String? = null
            val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
            queue.add(AccessibilityNodeInfoCompat.wrap(root))
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                val rid = n.viewIdResourceName
                val text = n.text?.toString()?.trim().orEmpty()
                if (rid != null && rid.substringAfterLast('/') == messageViewId && text.isNotEmpty()) {
                    val b = Rect().also { n.getBoundsInScreen(it) }
                    if (b.bottom > bestBottom) {
                        // 方向判据（guardrail M-05）：按**左右留白**判而非中心点——宽气泡（长文本）
                        // 中心可越过中线被误判"自己"。收到（对方）气泡左侧留白小、右侧留白大；
                        // 发出（自己）反之。对任意宽度都成立。
                        val leftMargin = b.left - leftMarginRef
                        val rightMargin = rightMarginRef - b.right
                        bestBottom = b.bottom
                        bestIncoming = leftMargin < rightMargin
                        bestText = text
                    }
                }
                for (i in 0 until n.childCount) queue.addLast(n.getChild(i) ?: continue)
            }
            val t = bestText ?: return null
            return bestIncoming to t
        } catch (e: Exception) {
            Log.w(TAG, "lastMessageBubble 失败：${e::class.simpleName}")
            return null
        } finally {
            // executionRoot 返回自持硬引用，用毕回收（杜绝句柄泄漏）
            runCatching { root.recycle() }
        }
    }

    /**
     * QQ 小号助手（ADR-062；2026-10-19 真机 E2E DEF-114）：按节点**自身**文本 / contentDescription
     * **精确**定位（BFS 取最深命中），避免 [findNodeByTextNid] 子树聚合带来的"命中大容器"。
     *
     * **背景（真机取证）**：[findNodeByTextNid] 对可点击节点用**子树聚合**文本匹配——大容器会
     * 聚合其下所有会话项文本，同样满足"包含查询"（0.95）且 BFS 更早 → 返回容器 nid，点击落点
     * 为空隙（真机实测：`tap=已点击节点 [7]` 中心 (360,780) 却未跳转）。
     *
     * @param query 目标文本（如"消息"）：匹配节点**自身** text 或 contentDescription 完全相等
     * @param descPrefix 非 null 时改为匹配"contentDescription **以该前缀开头**"（QQ 会话项格式
     *   "<备注名>, ,[N条未读,]preview,time"）
     * @return BFS 中最深命中节点的 nid；无命中返回 null
     */
    fun findNodeByOwnTextNid(query: String, descPrefix: String? = null): Int? {
        val root = executionRoot() ?: return null
        try {
            val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
            queue.add(AccessibilityNodeInfoCompat.wrap(root))
            var idx = 0
            var best: Int? = null
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                val ownText = n.text?.toString()?.trim().orEmpty()
                val desc = n.contentDescription?.toString()?.trim().orEmpty()
                val hit = if (descPrefix != null) {
                    descPrefix.isNotBlank() && desc.startsWith(descPrefix)
                } else {
                    query.isNotBlank() && (ownText == query || desc == query)
                }
                if (hit) best = idx // BFS 越靠后越深/越具体 → 取最后命中
                idx++
                for (i in 0 until n.childCount) queue.addLast(n.getChild(i) ?: continue)
            }
            return best
        } catch (e: Exception) {
            Log.w(TAG, "findNodeByOwnTextNid 失败：${e::class.simpleName}")
            return null
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * QQ 小号助手（ADR-062）：定位当前执行面树中**第一个可编辑节点**（EditText）的 nid（BFS）。
     *
     * 用于个签编辑页等"输入框无稳定 viewId/text"的场景（M0 PoC：QQ 个签编辑框 resource-id
     * 未知，但页面通常只有一个 EditText）。nid 契约同 [findNodeByNid]。
     *
     * @return 第一个可编辑节点 nid；无命中返回 null
     */
    fun findEditableNid(): Int? {
        val root = executionRoot() ?: return null
        val compat = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(compat)
        var idx = 0
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isEditable || n.className?.toString()?.contains("EditText") == true) {
                return idx
            }
            idx++
            for (i in 0 until n.childCount) {
                queue.addLast(n.getChild(i) ?: continue)
            }
        }
        return null
    }

    /**
     * 查询坐标处最上层节点文本/描述（供坐标点击的敏感目标判断）。
     *
     * @param x/y 屏幕坐标
     * @return 命中节点文本/描述拼接（可为 null）
     */
    fun nodeTextAt(x: Int, y: Int): String? {
        val root = executionRoot() ?: return null
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
     * v1 批次11（B11-2 前台 App 判定）：返回当前最上层（前台）窗口所属应用的包名。
     *
     * 依赖 `flagRetrieveInteractiveWindows`；用于 `get_ui_state` 树不可读时告知 LLM"当前前台是谁"，
     * 从而区分「目标 App 已打开但 UI 树受保护/未就绪（应等待）」与「App 根本没打开（应改动作）」，
     * 避免 LLM 盲目重试、缓解"感知不到"死循环。
     *
     * @return 前台包名；无可判定窗口返回 null
     */
    fun currentForegroundPackage(): String? {
        val best = windows
            ?.filter { it.isActive }
            ?.maxByOrNull { it.layer }
            ?: return null
        val root = best.root ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    /**
     * 查询目标节点为 [UiNode]（供密码/验证码等敏感节点判断）。
     *
     * @param nodeId 节点序号；优先
     * @param x/y 坐标兜底（nodeId 为 null 时按坐标找最上层可交互节点）
     * @return 映射后的 [UiNode]；未命中返回 null
     */
    fun nodeAt(nodeId: Int?, x: Int, y: Int): UiNode? {
        val root = executionRoot() ?: return null
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
        // 批次21（SZ-003，ADR-046）：后台下滑动经 shell `input -d swipe` 注入虚拟屏
        if (isBackgroundExecution) {
            if (!io.prism.phonecontrol.bg.ShizukuShell.isReady()) return bgGestureBlocked("滑动")
            val did = io.prism.phonecontrol.bg.ShizukuShell.inputSwipe(
                executionDisplayId ?: return bgGestureBlocked("滑动"),
                fromX, fromY, toX, toY, durationMs
            )
            return if (did) "已滑动 ($fromX,$fromY)→($toX,$toY)（shell 注入到虚拟屏）"
            else "错误：后台滑动注入失败（Shizuku 命令执行异常）"
        }
        val path = Path().apply { moveTo(fromX.toFloat(), fromY.toFloat()); lineTo(toX.toFloat(), toY.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return if (dispatchGesture(gesture, null, null)) "已滑动 ($fromX,$fromY)→($toX,$toY)" else "错误：滑动手势失败"
    }

    /**
     * 批次22 修复轮六（2026-09-07）：a11y 截屏指定 display（API 34+）——**模拟副屏（trusted）
     * 路线**的截图通道（该路线无 ImageReader）。需在系统无障碍设置中为 Prism 开启
     * 「屏幕截图」能力（一次性授权），否则回调 onError 返回 null。
     */
    suspend fun takeScreenshotForDisplay(displayId: Int): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 34) return null
        // guardrail W2：10s 超时保护（防 ROM 回调双缺时协程无限挂起）
        return kotlinx.coroutines.withTimeout(10_000) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            takeScreenshot(
                displayId,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        cont.resume(
                            runCatching {
                                // HardwareBuffer（GPU）→ 软件位图（JPEG 压缩需要）；
                                // buffer 生命周期由 ScreenshotResult 管理，无需显式 close
                                val hw = android.graphics.Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer, result.colorSpace
                                )
                                hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            }.getOrNull(),
                            onCancellation = null
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot(displayId=$displayId) 失败 code=$errorCode（需开启「屏幕截图」能力？）")
                        cont.resume(null, onCancellation = null)
                    }
                }
            )
            }
        }
    }

    /** 输入文本到节点（ACTION_SET_TEXT；失败降级为剪贴板+长按粘贴的占位提示，由工具层处理）。 */
    fun performType(nodeId: Int?, x: Int, y: Int, text: String): String {
        val node = if (nodeId != null) findNodeByNid(nodeId) else findEditableAt(x, y)
        // 批次22 修复轮四（2026-09-07）：后台执行面 shell 文字注入 fallback——Compose 扁平化
        // App 的虚拟屏 a11y 树常无输入框节点（DeepSeek 实证），ACTION_SET_TEXT 无从落地。
        // 双路径：①剪贴板+焦点节点粘贴（CJK 可靠，无需 IME）；②input text 按键注入（ASCII 兜底）。
        if (node == null && isBackgroundExecution) {
            if (!io.prism.phonecontrol.bg.ShizukuShell.isReady()) {
                return "错误：后台模式输入需要 Shizuku（a11y 树无输入框节点时经 shell 注入）"
            }
            val displayId = executionDisplayId
                ?: return "错误：后台执行面未就绪"
            if (x == 0 && y == 0) {
                return "错误：未找到输入框节点（后台 a11y 树受限）。请先用 mode=background 的 " +
                    "screenshot 获取输入框坐标（OCR），再 type 传 x/y 坐标"
            }
            // 1) tap 聚焦（该 display 的焦点窗口切换）
            if (!io.prism.phonecontrol.bg.ShizukuShell.inputTap(displayId, x, y)) {
                return "错误：点击聚焦失败（shell 注入异常）"
            }
            Thread.sleep(400)  // 聚焦生效窗口
            // 2) 剪贴板 + 焦点节点粘贴（CJK 可靠路径；root/focus 为自持硬引用，用毕回收）
            runCatching {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("prism-type", text))
            }
            var pasted = false
            var root: AccessibilityNodeInfo? = null
            try {
                root = executionRoot()
                // 批次22 修复轮七（2026-09-07）：findFocus 依赖 tap 聚焦已生效（事件被目标屏
                // 接受）；注入被丢弃时恒为 null → 降级按坐标找可编辑节点执行粘贴
                val focusNode = root?.findFocus(
                    android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
                )
                if (focusNode != null) {
                    pasted = focusNode.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE
                    )
                    focusNode.recycle()
                } else {
                    // findEditableAt 返回 compat 类型（与 findNodeByNid 同源），ACTION_PASTE
                    // 语义一致；沿既有约定不回收 compat 节点
                    val editable = findEditableAt(x, y)
                    if (editable != null) {
                        pasted = editable.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { root?.recycle() }
            }
            if (pasted) {
                return "已注入文字（tap 聚焦 + 剪贴板粘贴；坐标 $x,$y）。" +
                    "请用 tap(text=发送) 或坐标点击发送按钮完成提交"
            }
            // 3) input text 按键注入（ASCII/数字兜底；CJK 会被 VIRTUAL_KEYBOARD 键表丢弃）。
            // 批次22 修复轮七：非 ASCII 文本**拒绝执行**并诚实报错——此前 exit==0 即报"已注入"，
            // CJK 被键表静默丢弃造成"假成功"（真机取证：输入框内容恒为空）
            if (text.any { it.code > 127 }) {
                return "错误：文字注入失败——tap 聚焦未生效（粘贴路径无焦点节点），且 input text " +
                    "不支持中文等非 ASCII 文本（系统键表丢弃，平台限制）。请先用 mode=background " +
                    "的 tap 点击输入框坐标使其聚焦后重试 type，或改用 mode=foreground / take_over"
            }
            val typed = io.prism.phonecontrol.bg.ShizukuShell.inputText(displayId, text)
            return if (typed) {
                "已注入文字（tap 聚焦 + input text；坐标 $x,$y）。若输入框内容为空（CJK 可能被丢弃），" +
                    "请改用 mode=foreground 或 take_over。请用 tap 完成发送"
            } else {
                "错误：文字注入失败（input text 执行异常）"
            }
        }
        if (node == null) return "错误：未找到输入框（可尝试先点击输入框聚焦）"
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            return "已在节点输入文本"
        }
        return "错误：节点不支持直接输入（ACTION_SET_TEXT 失败），可降级为粘贴方式"
    }

    /** 全局动作（返回/主页/最近任务）。 */
    fun executeGlobalAction(action: Int): String {
        // 批次20（BG-002）：全局动作作用于**主屏**导航（back/home 会打断用户正在进行的操作），
        // 后台执行面下一律拒绝——**批次21（SZ-003）**：Shizuku 就绪时经 shell
        // `input -d <id> keyevent` 注入到虚拟屏（键事件作用于该屏的焦点窗口）。
        if (isBackgroundExecution) {
            if (io.prism.phonecontrol.bg.ShizukuShell.isReady()) {
                val keyCode = when (action) {
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK -> 4 // KEYCODE_BACK
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME -> 3 // KEYCODE_HOME
                    else -> return "错误：后台模式仅支持返回/主页的 shell 注入，其它全局动作请切回前台模式"
                }
                val displayId = executionDisplayId ?: return bgGestureBlocked("全局动作")
                return if (io.prism.phonecontrol.bg.ShizukuShell.inputKeyevent(displayId, keyCode)) {
                    "已执行全局动作（shell 注入到虚拟屏）"
                } else "错误：后台全局动作注入失败（Shizuku 命令执行异常）"
            }
            return "错误：后台模式不支持全局动作（back/home 作用于主屏导航，会干扰用户正在使用的主屏）。" +
                "虚拟屏内请用页面节点操作（tap 页面返回按钮/标签），或该步骤切回前台模式；" +
                "安装并激活 Shizuku 后可解锁后台 back/home"
        }
        // super.performGlobalAction 是 AccessibilityService 原生 Boolean 返回实现
        return if (super.performGlobalAction(action)) "已执行全局动作" else "错误：全局动作执行失败"
    }

    /**
     * 批次20（BG-002）：后台执行面手势受限统一文案（`dispatchGesture` 仅作用于默认屏，
     * 无法把触摸注入到虚拟屏——调研报告第 7 节 API 事实核验；Shizuku 就绪时各手势方法
     * 已改经 shell 注入，本文案仅在 Shizuku 不可用时出现）。
     */
    private fun bgGestureBlocked(action: String): String =
        "错误：后台模式不支持${action}（触摸注入仅作用于主屏，虚拟屏无法注入；" +
            "且主屏手势会干扰用户）。请改用节点操作（get_ui_state 取节点后 tap(text=.../node_id)、type），" +
            "或该步骤临时切回前台模式执行；安装并激活 Shizuku 可解锁后台手势"

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
            // v1 批次11（A）：降采样前记录原始屏幕尺寸，供 OCR 坐标还原到屏幕空间
            lastScreenshotOrigW = bitmap.width
            lastScreenshotOrigH = bitmap.height
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

    /**
     * v1 批次11（A）：最近一次截图的原始屏幕尺寸（未降采样），供 OCR 坐标还原。
     *
     * @return (宽, 高)；尚无截图或失败返回 null
     */
    fun lastScreenshotScreenSize(): Pair<Int, Int>? {
        val w = lastScreenshotOrigW
        val h = lastScreenshotOrigH
        return if (w > 0 && h > 0) w to h else null
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
     * 将无障碍截图结果转为 Android Bitmap（API 30+，公开 API）。
     *
     * **v1 批次10（Bug B，真机 NoSuchMethodException 根治）**：旧实现用反射调
     * `ScreenshotResult.asBitmap()`（@SystemApi 隐藏 API），国产 ROM（小米/vivo/OPPO 等）
     * 未将该方法暴露为 public → `javaClass.getMethod("asBitmap")` 抛 NoSuchMethodException，
     * 截图功能彻底不可用（真机日志 `captureScreenshot 失败（NoSuchMethodException）`）。
     *
     * **修复**：改用公开 API `Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, colorSpace)`
     *（API 29+ 公开、非隐藏，所有 Android 11+ 设备可用），不依赖 @SystemApi 反射。
     * [AccessibilityService.ScreenshotResult.hardwareBuffer]/[ScreenshotResult.colorSpace]
     * 均为 API 30+ 公开 getter，可直接访问，无 ROM 差异。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun AccessibilityService.ScreenshotResult.asBitmapCompat(): android.graphics.Bitmap {
        val buffer = hardwareBuffer
        try {
            // wrapHardwareBuffer 返回的 Bitmap 与硬件缓冲区共享内存，需配置颜色空间
            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                ?: throw java.io.IOException("wrapHardwareBuffer 返回 null（硬件缓冲区不可用）")
            try {
                // 复制为软件位图，避免依赖硬件缓冲区内存在后续操作/回收时失效
                val copy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    ?: throw java.io.IOException("bitmap.copy 返回 null（软件位图分配失败）")
                return copy
            } finally {
                bitmap.recycle()
            }
        } finally {
            // guardrail（批次10）：try/finally 保证 buffer.close() 在 wrap-null / copy-异常 两条路径都执行，
            // 杜绝 HardwareBuffer 资源泄漏
            buffer.close()
        }
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
        // v1 批次11 诊断：微信/WebView/Flutter 等常对无障碍树暴露受限 → 根节点 childCount=0，
        // BFS 只产出根 → 序列化近似空 → LLM 报"UI 树读取不到内部界面"。记录根节点类型与子节点数。
        if (count <= 1) {
            Log.w(TAG, "mapNode: 仅 ${count} 个节点（根 childCount=${root.childCount} className=${root.className}）—— 疑似无障碍树受限/空树，应走截图+坐标兜底")
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

    /** 按 nid 查找当前执行面树中的节点（重新采集，页面变化时 nid 可能失效）。 */
    internal fun findNodeByNid(nid: Int): AccessibilityNodeInfoCompat? {
        val root = executionRoot() ?: return null
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
        val root = executionRoot() ?: return null
        val compat = AccessibilityNodeInfoCompat.wrap(root)
        val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
        queue.add(compat)
        // 修复轮八：除 isEditable 外，接受**暴露 ACTION_PASTE 能力**的节点——部分 Compose
        // 渲染（DeepSeek 实证）输入框节点不标 isEditable 但支持粘贴。
        // guardrail Q-4：BFS 首匹配会命中覆盖坐标的外层大容器（WebView 根常见）——改为
        // 最小面积优选 + isEditable 命中优先于 ACTION_PASTE-only 命中
        var best: AccessibilityNodeInfoCompat? = null
        var bestArea = Long.MAX_VALUE
        var bestIsEditable = false
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val bounds = Rect()
            n.getBoundsInScreen(bounds)
            if (bounds.contains(x, y)) {
                val editable = n.isEditable
                val canPaste = editable || (n.actions and AccessibilityNodeInfo.ACTION_PASTE) != 0
                if (canPaste) {
                    val area = bounds.width().toLong() * bounds.height().toLong()
                    val better = best == null || (editable && !bestIsEditable) ||
                        (editable == bestIsEditable && area < bestArea)
                    if (better) {
                        best = n
                        bestArea = area
                        bestIsEditable = editable
                    }
                }
            }
            for (i in 0 until n.childCount) queue.addLast(n.getChild(i) ?: continue)
        }
        return best
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
        private const val TEXT_AGG_BUDGET = 12

        /**
         * v1 批次11（坐标吸附）：吸附距离阈值（px，屏幕空间）。LLM 给的坐标（尤其 OCR 定位）
         * 距最近可点击节点中心在此阈值内时吸附到节点中心，降低误触；超出则按原坐标点击。
         * 取屏幕对角线约 4% 量级（1080p 下 ≈ 105px），覆盖 OCR 框偏移而不过度吸附。
         */
        internal const val SNAP_THRESHOLD_PX = 120

        /**
         * v1 批次11（C 文本锚点）：文本匹配相似度阈值。
         *
         * [textSimilarity] 达到该值才采纳为目标。完全相等=1.0 / 目标含查询=0.95 / 查询含目标=0.8
         * 均远高于阈值；0.6 允许 OCR 轻微误读（如"搜 索"→"搜索"）仍可命中，同时拒绝低重叠噪声。
         */
        internal const val TEXT_MATCH_THRESHOLD = 0.6f

        /** [textSimilarity] 归一化用空白正则（OCR 常在中文字符间插入空格）。 */
        private val WHITESPACE_REGEX = Regex("""\s+""")

        /**
         * v1 批次11（C 文本锚点）：文本相似度（纯函数可测）。
         *
         * 匹配策略：完全相等 > 目标包含查询（substring） > 查询包含目标 > 字符级 Dice 系数。
         * 归一化：小写 + 去空白（OCR 常在中文字符间插入空格）。
         *
         * @param query 查询文本（LLM 给的锚点）
         * @param target 候选文本（UI 树节点聚合 / OCR 行文本）
         * @return 0..1 相似度
         */
        internal fun textSimilarity(query: String, target: String): Float {
            val q = query.trim().lowercase().replace(WHITESPACE_REGEX, "")
            val t = target.trim().lowercase().replace(WHITESPACE_REGEX, "")
            if (q.isEmpty() || t.isEmpty()) return 0f
            if (q == t) return 1f
            if (t.contains(q)) return 0.95f
            if (q.contains(t)) return 0.8f
            val qChars = q.toSet()
            val tChars = t.toSet()
            val inter = qChars.intersect(tChars).size.toFloat()
            return (2f * inter) / (qChars.size + tChars.size)
        }
    }
}
