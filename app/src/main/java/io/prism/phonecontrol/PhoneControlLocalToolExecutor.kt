package io.prism.phonecontrol

import android.util.Log
import io.prism.skill.AskUserLocalToolExecutor
import io.prism.skill.LocalToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v1 US-201/202/203：手机操控本地工具执行器 —— 让 LLM 通过 AccessibilityService 操控手机。
 *
 * **工具集**（命名空间 `phone_control__`）：
 * - `get_ui_state`：采集当前 UI 树 → 结构化文本（文本模型主感知路径）
 * - `tap` / `long_press` / `double_tap`：点击/长按/双击（node_id 优先，坐标兜底）
 * - `swipe`：滑动（start/end 坐标）
 * - `type`：输入文本到节点
 * - `back` / `home`：全局返回/主页
 * - `launch_app`：启动应用（包名）
 * - `wait`：等待（页面加载）
 * - `screenshot`：截图（API30+ 无障碍截图，US-203 降采样）
 * - `take_over`：请求人工接管（映射 Open-AutoGLM Take_over，经 ask_user）
 *
 * **敏感拦截分层（US-202，代码层硬拦截 + 人工确认，不依赖模型自觉）**：
 * 1. **金融专用 App 启动**（支付宝/银行/银联等，[PhoneControlSecurity.isSensitivePackage]）
 *    → 硬拦截（⚠️ 前缀，SkillExecutor 按 isFailureResult 回灌）
 * 2. **敏感目标节点**（目标文本含支付/转账/红包/密码/验证码/卡号 或密码节点，
 *    [PhoneControlSecurity.isSensitiveTargetText] / [PhoneControlSecurity.isPasswordNode]）
 *    → 硬拦截（永不执行，建议 LLM 调 take_over 交还用户手动）
 * 3. **高危动作强制 MANUAL**（发送/删除/退出登录/拨号/短信等，
 *    [PhoneControlSecurity.isManualAction]）→ 触发 ask_user（映射 Take_over + StopAtTools），
 *    用户显式确认「允许/取消」后由用户答复驱动下一轮
 * 4. **凭据输入**（type 文本含密码/验证码/卡号，[PhoneControlSecurity.isCredentialInput]）
 *    → 硬拦截
 *
 * **人工接管（take_over）**：返回 `【需要用户回答】` + AskUserPayload JSON（复用 UXR8 N2
 * AskUserLocalToolExecutor.RESULT_MARKER 协议），SkillExecutor 检测到标记 → 发射
 * [io.prism.network.StreamEvent.AskUser] + StopAtTools 中断回路，UI 展示提问卡片等待用户接管。
 *
 * **降级**：无障碍服务未连接（[PhoneControlAccessibilityService.instance] == null）→ 所有
 * 执行类工具返回「请先在系统设置中开启 Prism 无障碍服务」。
 *
 * **可测性**：参数解析 [parseInt]/[parseString]、敏感判断 [PhoneControlSecurity] 与
 * ask_user 载荷构造 [askUserJson] 为纯逻辑，JVM 可测；执行路径依赖服务实例
 * （仅模拟器/真机验证）。
 */
class PhoneControlLocalToolExecutor(
    /**
     * 无障碍启用检测（v1 真机二次修复 Issue 4）：默认为"进程内已有服务实例"；
     * 生产由 PrismApplication 注入系统级 [PhoneControlAccessibilityService.isEnabledInSystem]，
     * 用于区分"系统已启用但实例未连（重连中）"与"未启用"，消除微信等重内存 App 打开时误报。
     */
    private val accessibilityEnabledProvider: () -> Boolean = { PhoneControlAccessibilityService.instance != null },
    /**
     * 高危动作（发送/删除/拨号/短信）确认策略（v1 真机二次修复 Issue 4b）：BLOCK 全拦截 / ALLOW
     * 全放行 / ASK 逐次询问（默认）。生产经 PrismApplication 注入 [HighRiskApprovalRepository.mode] 秒读。
     */
    private val highRiskApprovalProvider: suspend () -> io.prism.config.HighRiskApprovalMode =
        { io.prism.config.HighRiskApprovalMode.ASK },
    /**
     * 手机操控高危确认通知桥（v1 真机二次修复 Issue 5）：LLM 操控手机时 Prism 常在后台，
     * 发送/删除/拨号的「逐次询问」提问卡片用户看不见。注入后，每次触发强制 MANUAL（ask_user）
     * 时额外发一条高优先级通知（含 允许/拒绝 操作按钮），让用户在其它 App 也能作答。
     *
     * null 时降级为仅聊天页提问卡片（向后兼容既有测试）。
     */
    private val askUserNotifier: PhoneControlAskUserNotifier? = null,
    /**
     * 端侧 OCR 提取器（v1 批次11 D9）：UI 树受限（微信/WebView/Flutter）时，截图后
     * 提取「屏幕文字 + 坐标」供**纯文本模型**（deepseek 等读不了图片）继续用坐标 tap/swipe。
     * null 时截图仅返回 data URL（多模态模型用）。
     */
    private val ocrTextExtractor: io.prism.vision.OcrTextExtractor? = null,
    /**
     * v1 批次13（B/D16b，多模态）：当前激活 Provider 是否支持视觉（多模态图片输入）。
     *
     * true 时 `phone_control__screenshot` **跳过 OCR**、返回图片标记（[SCREENSHOT_IMAGE_MARKER]），
     * 由 [io.prism.skill.SkillExecutor] 把截图**图片**以 image_url 注入会话供模型直接查看——
     * 发挥多模态能力（glm-4.6v-flash 等看真图而非 OCR 文本），且**不再内嵌 base64 文本**
     * （防会话 JSON 膨胀 + 渲染 ANR，真机崩溃根因）。false 时走 OCR 文字+坐标（纯文本模型）。
     * 生产由 [io.prism.PrismApplication] 从当前激活 Provider 的 `supportsVision` 注入。
     */
    private val visionCapableProvider: () -> Boolean = { false },
    /**
     * 批次20（BG-003，ADR-045）：后台操控用户开关（会话级「后台操控」chip，VM 注入）。
     * 关闭时 mode=background 的工具调用一律拒绝（防 LLM 绕过用户意图）。
     */
    private val backgroundControlEnabledProvider: () -> Boolean = { false },
    /**
     * 批次20（BG-001/003）：应用上下文（后台虚拟屏创建/启动 App/回收需要）。生产由
     * PrismApplication 注入 applicationContext；null 时后台模式返回明确错误（保持既有测试兼容）。
     */
    private val appContext: android.content.Context? = null,
    /**
     * 批次20（guardrail P1-3）：悬浮窗授权判定注入点（默认
     * [io.prism.phonecontrol.bg.VirtualDisplayManager.canLaunchBackground]——Robolectric 下
     * Settings.canDrawOverlays 不可控，测试注入常量 true 以覆盖后台路径拦截同源性）。
     */
    private val overlayPermissionProvider: (android.content.Context) -> Boolean =
        { io.prism.phonecontrol.bg.VirtualDisplayManager.canLaunchBackground(it) }
) : LocalToolExecutor {

    // ==================== v1 批次12（D/E，D15）屏幕状态跟踪 ====================

    /**
     * v1 批次13（B/D16c，多模态降级）：视觉路径是否已失效（端点不支持图片）。
     *
     * 由 [onVisionUnsupported] 在 SkillExecutor 收到 400 visionUnsupported 错误时置 true——
     * 截图**图片注入**对本端点不可用，后续 `phone_control__screenshot` 转回 OCR 文字+坐标
     * （纯文本路径），任务以文本模式继续而非中断。进程级持久（模型能力不会中途恢复）。
     */
    @Volatile
    internal var visionDegraded = false

    /** v1 批次13（B/D16c）：视觉降级信号——见 [io.prism.skill.LocalToolExecutor.onVisionUnsupported]。 */
    override fun onVisionUnsupported() {
        if (!visionDegraded) {
            visionDegraded = true
            Log.w(TAG, "onVisionUnsupported: 视觉模型端点不支持图片 → 截图降级为 OCR/UI 树路径")
        }
    }

    /** 最近一次感知到的屏幕状态签名（Stuck 检测用；launch_app 成功时重置）。 */
    private var lastScreenSig: String? = null

    /** 屏幕状态连续未变化计数（≥ [STUCK_THRESHOLD] 时返回恢复引导）。 */
    private var stuckStreak = 0

    /**
     * 计算屏幕状态轻量签名（before/after 校验 + Stuck 检测共用）。
     *
     * 取「前台包名 + UI 树文本 hashCode」（微信等空树时回退前台包名），成本低、可比性足够。
     *
     * @param service 服务实例
     * @return 签名（非空）
     */
    private fun screenSignature(service: PhoneControlAccessibilityService): String {
        // 批次20（BG-002）：后台执行面签名 = 虚拟屏 UI 树 hash（前台包名无意义——主屏属于用户）
        if (service.isBackgroundExecution) {
            val treeHash = runCatching { service.getUiTreeText(maxNodes = 40)?.hashCode() }.getOrNull()
            return "bg|${treeHash ?: "empty"}"
        }
        val fg = runCatching { service.currentForegroundPackage() }.getOrNull() ?: ""
        val treeHash = runCatching { service.getUiTreeText(maxNodes = 40)?.hashCode() }.getOrNull()
        return "$fg|${treeHash ?: "empty"}"
    }

    /**
     * Stuck 检测（D）：跟踪连续 N 步屏幕无变化，超过阈值返回恢复引导文案（prd-v1-b10 §8 #1）。
     *
     * @param sig 当前屏幕签名
     * @return 无变化达到阈值时的恢复引导；否则 null
     */
    private fun trackStuck(sig: String): String? {
        if (sig == lastScreenSig) {
            stuckStreak++
        } else {
            stuckStreak = 0
            lastScreenSig = sig
        }
        return if (stuckStreak >= STUCK_THRESHOLD) {
            "⚠️ 屏幕状态已连续 $stuckStreak 步无变化（可能卡死/页面未加载/操作未生效）。建议：用 back 返回上一页、home 回桌面后重新 launch_app，或 take_over 人工接管。不要盲目重复相同操作"
        } else {
            null
        }
    }

    /** 重置 Stuck 状态（launch_app 成功进入新 App 时调用——新任务上下文）。 */
    private fun resetStuckState() {
        stuckStreak = 0
        lastScreenSig = null
    }

    /**
     * v1 批次12（E，D15）动作后 before/after 校验（prd-v1-b10 §8 #3）：动作执行后若屏幕签名
     * 未变化，附软提示"可能未命中/未生效"供 LLM 纠偏（get_ui_state/screenshot 复核）。
     *
     * 仅在动作**确实执行**（非错误/拦截/ask_user）且前后签名一致时提示（软提示非硬失败——
     * 部分合法点击不改变屏幕，如点击已选中的 Tab）。
     *
     * @param before 动作前屏幕签名（[screenSignature]；null 跳过校验）
     * @param block 实际执行动作
     * @return 动作结果（可能追加未生效提示）
     */
    private suspend fun performWithStateCheck(before: String?, block: suspend () -> String): String {
        val result = block()
        val after = withServiceOrNull { screenSignature(it) }
        val executed = !result.startsWith("错误") && !result.startsWith("⚠️") &&
            !result.startsWith(AskUserLocalToolExecutor.RESULT_MARKER)
        if (executed && before != null && after != null && before == after) {
            Log.i(TAG, "动作后屏幕无变化（before/after 签名一致）")
            // 批次22 修复轮五：后台模式补充**息屏/焦点限制**诊断（Display OFF 时注入事件被
            // 系统输入策略丢弃，shell exit=0 但无效果——真机实证），并区分两种处置建议。
            // 批次22 修复轮七：overlay（trusted）与自建（untrusted）执行面**分文案**——untrusted
            // 屏 touch NONE，系统直接丢弃合成输入，"重试/重新定位"对它完全无效（真机取证
            // DeepSeek 会话：LLM 连续 6 次盲试坐标），必须明确劝阻并指向 foreground/take_over。
            val bgHint = if (PhoneControlAccessibilityService.instance?.isBackgroundExecution == true) {
                when {
                    // ADR-065：隐藏虚拟屏（shell 自建 PUBLIC+TRUSTED，主屏零窗口）——输入可用
                    io.prism.phonecontrol.bg.VirtualDisplayManager.isHiddenMode ->
                        "\n【后台模式诊断】当前执行面为隐藏虚拟屏（trusted，主屏无小窗）。" +
                            "若多次尝试仍无变化，说明目标 App/显示面不接受合成输入——改用 mode=foreground 或 take_over"
                    io.prism.phonecontrol.bg.VirtualDisplayManager.isOverlayMode ->
                        "\n【后台模式诊断】虚拟屏注入可能受息屏/焦点限制（亮屏锁已在会话期保持，若用户仍处于息屏状态请先亮屏）。" +
                            "若多次尝试仍无变化，说明目标 App/显示面不接受合成输入——改用 mode=foreground 或 take_over"
                    else ->
                        "\n【后台模式诊断】当前执行面为**自建虚拟屏（untrusted，touch NONE）**——系统会丢弃指向它的合成输入（tap/type 均无效，重试无意义）。" +
                            "请改用 mode=foreground 或 take_over；结束本会话后重新发起任务（Shizuku 就绪时将优先创建隐藏虚拟屏 trusted，输入可用）"
                }
            } else {
                "请先用 get_ui_state 或 screenshot 确认当前屏幕，若仍未变化可尝试：换一个更精确的目标（tap(text=...) 或 node_id）、" +
                    "先 back 返回再重试、或 take_over 人工接管。不要盲目重复相同操作"
            }
            return result + "\n⚠️ 动作后屏幕状态无变化，可能未命中目标或未生效。$bgHint"
        }
        return result
    }

    override fun handles(toolName: String): Boolean = toolName.startsWith(NAMESPACE)

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        // v1 批次14（TKN-V1B14-KEEPALIVE-BUG-001）：任务期动态保活——每次 phone_control__* 工具
        // 调用刷新保活会话（首个调用启动前台服务，空闲 IDLE_TIMEOUT_MS 自动停止）。替代批次11 F2
        // 的「无障碍启用期常驻」策略（真机证据见 docs/reports/2026-08-23-keepalive-bug-debug.md）。
        PhoneControlSessionManager.onPhoneToolInvoked()
        val tool = toolName.removePrefix(NAMESPACE)
        // 批次20（BG-003，ADR-045）：mode 执行面参数。foreground（默认，行为与旧版完全一致）/
        // background（虚拟屏后台执行，主屏留给用户）。非法值直接拒绝，防 LLM 拼错悄然回退前台。
        // 批次20 真机修复（RCA 问题1，2026-09-05）：chip 开启且 LLM 未显式传 mode 时默认
        // **background**（后台优先语义直接生效——真机实证 LLM 自主选择几乎从不传 mode，
        // 导致"后台化完全无效"）；LLM 仍可显式传 foreground 覆盖（主屏手势类操作）。
        val explicitMode = parseString(arguments, "mode")
        val mode = if (explicitMode.isNullOrBlank() && backgroundControlEnabledProvider()) {
            io.prism.phonecontrol.bg.VirtualDisplayManager.MODE_BACKGROUND
        } else {
            io.prism.phonecontrol.bg.VirtualDisplayManager.normalizeMode(explicitMode)
        }
        if (mode == null) {
            return "错误：mode 参数非法（仅支持 foreground/background）"
        }
        // 真机取证日志（RCA 问题1）：下次真机问题可直接确认 LLM 实际使用的执行面
        Log.i(TAG, "tool=$tool mode=$mode")
        val background = mode == io.prism.phonecontrol.bg.VirtualDisplayManager.MODE_BACKGROUND
        return try {
            if (background) {
                if (!backgroundControlEnabledProvider()) {
                    return blocked("后台操控开关未开启（聊天输入框上方「后台操控」）。请开启后重试，或去掉 mode 参数改用前台模式")
                }
                backgroundGuard()?.let { return it }
                // guardrail H-01（2026-10-19）：后台执行面（executionDisplayId）为全局单值，
                // 须与 QQ 后台循环等其它切换方串行化——整段（ensureDisplay + 执行面切换 + 工具）
                // 持 [VirtualDisplayManager.executionFaceMutex]，避免并发切换相互覆盖执行面。
                return io.prism.phonecontrol.bg.VirtualDisplayManager.executionFaceMutex.withLock {
                    // 批次21 真机修复 R3（RCA 问题2 续）：bg 工具路径统一先 ensureDisplay（幂等）——
                    // 真机实证 LLM 首个 bg 操作若不是 launch_app（如直接 get_ui_state/screenshot），
                    // 虚拟屏不存在 → 工具失败 → LLM 误判"后台不可用"而显式切 foreground（占用前台）。
                    // 上下文经服务的 applicationContext（服务实例即 bg 执行上下文持有者）。
                    withServiceOrNull { svc ->
                        // guardrail Q2（修复轮七）：中断类异常必须透传（恢复中断位 + 转 Cancellation）
                        // ——原 runCatching 会静默吞掉 InterruptedException 后带 INVALID displayId
                        // 继续执行工具；其余异常维持既有语义（创建失败走工具层诚实报错）
                        try {
                            io.prism.phonecontrol.bg.VirtualDisplayManager.ensureDisplay(svc.applicationContext)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw java.util.concurrent.CancellationException("ensureDisplay interrupted")
                        } catch (e: java.util.concurrent.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 创建失败：后续工具按"虚拟屏未创建"诚实报错
                        }
                    }
                    withBackgroundExecution { dispatchTool(tool, arguments) }
                }
            }
            dispatchTool(tool, arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "execute($tool) 失败：${e::class.simpleName}")
            "错误：手机操控工具执行失败（${e::class.simpleName}）"
        }
    }

    /**
     * 按工具名分派（前台/后台共用同一套方法；执行面差异由服务的
     * [PhoneControlAccessibilityService.isBackgroundExecution] 驱动，方法内部按需分支）。
     */
    private suspend fun dispatchTool(tool: String, arguments: Map<String, Any?>): String {
        return when (tool) {
        "get_ui_state" -> getUiState()
        "tap" -> runTargetAction("tap", arguments) { svc, nodeId, x, y ->
            svc.performTap(nodeId, x, y)
        }
        "long_press" -> runTargetAction("long_press", arguments) { svc, nodeId, x, y ->
            svc.performLongPress(nodeId, x, y)
        }
        "double_tap" -> runTargetAction("double_tap", arguments) { svc, nodeId, x, y ->
            svc.performDoubleTap(nodeId, x, y)
        }
        "swipe" -> {
            // 批次20（guardrail P3-6）+ 批次21（SZ-003）：bg 且 Shizuku 不可用时短路（执行面下
            // nodeTextAt 坐标预检错位无意义）；Shizuku 就绪时放行到服务层 shell 注入
            if (withServiceOrNull { it.isBackgroundExecution } == true &&
                !io.prism.phonecontrol.bg.ShizukuShell.isReady()
            ) {
                return "错误：后台模式不支持滑动（触摸注入仅作用于主屏，虚拟屏无法注入）。" +
                    "安装并激活 Shizuku 可解锁后台滑动；或先用节点操作，或该步骤切回前台模式"
            }
            val fx = parseInt(arguments, "from_x", 0) ?: 0
            val fy = parseInt(arguments, "from_y", 0) ?: 0
            val tx = parseInt(arguments, "to_x", 0) ?: 0
            val ty = parseInt(arguments, "to_y", 0) ?: 0
            // guardrail L-1：滑动本身无点击语义，但起点命中敏感目标（支付/密码等）时
            // 仍硬拦截（防"从敏感按钮处滑动触发"的组合绕过）
            val fromText = withServiceOrNull { it.nodeTextAt(fx, fy) }
            if (PhoneControlSecurity.isSensitiveTargetText(fromText)) {
                return blocked("滑动起点含敏感内容（${fromText?.take(30)}），已硬拦截")
            }
            // v1 批次12（E，D15）：滑动后 before/after 校验（滑到底/未加载时提示）
            val beforeSig = withServiceOrNull { screenSignature(it) }
            performWithStateCheck(beforeSig) { withService { it.performSwipe(fx, fy, tx, ty) } }
        }
        "type" -> runTypeAction(arguments)
        "back" -> withService { it.executeGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
        "home" -> withService { it.executeGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }
        "launch_app" -> runLaunchAction(arguments)
        "wait" -> {
            val ms = (parseInt(arguments, "ms", 800) ?: 800).coerceAtLeast(0)
            if (ms > MAX_WAIT_MS) return "错误：wait 超过上限 ${MAX_WAIT_MS}ms"
            kotlinx.coroutines.delay(ms.toLong())
            "已等待 ${ms}ms"
        }
        "screenshot" -> runScreenshot()
        "take_over" -> runTakeOver(arguments)
        else -> "错误：未知手机操控工具 $tool"
        }
    }

    // ==================== 工具实现 ====================

    /** 目标型动作（tap/long_press/double_tap）：先做文本锚点解析 + 敏感目标拦截，再执行。 */
    private suspend fun runTargetAction(
        action: String,
        arguments: Map<String, Any?>,
        perform: (PhoneControlAccessibilityService, Int?, Int, Int) -> String
    ): String {
        // v1 批次11（C 文本锚点）：LLM 可用 text 描述目标（"点发送"），由系统解析为 node_id 或坐标——
        // 不依赖模型猜像素坐标（业界共识：坐标输出失败率远高于选文本/编号，Set-of-Mark 5/5 vs 0/5）。
        // 解析优先级：UI 树文本匹配（可靠）→ 截图 OCR 模糊匹配（兜底，微信/WebView 等空树场景）。
        val anchorText = parseString(arguments, "text")?.trim()?.takeIf { it.isNotBlank() }
        var nodeId = parseInt(arguments, "node_id")
        var x = parseInt(arguments, "x", 0) ?: 0
        var y = parseInt(arguments, "y", 0) ?: 0
        // 锚点解析命中后的真实文本（OCR 行文本 / UI 树节点聚合文本，供敏感判断）
        var resolvedMatchedText: String? = null
        if (anchorText != null && nodeId == null) {
            val resolved = resolveTextAnchor(anchorText)
            if (resolved == null) {
                return "错误：未能在当前屏幕找到文本「${anchorText.take(20)}」对应的目标（UI 树与 OCR 均未命中）。请先 screenshot 或 get_ui_state 确认当前屏幕内容后再操作"
            }
            nodeId = resolved.nodeId
            x = resolved.x
            y = resolved.y
            resolvedMatchedText = resolved.matchedText
        }
        // guardrail R-1（TKN-V1B11-GUARDRAIL-002）：敏感判断必须**始终优先命中节点/坐标/OCR 的真实文本**。
        // 覆盖 node_id + text 双传场景（query 可能不敏感但节点真实文本如"确认支付"敏感 → 必须拦截）。
        // 优先级：锚点命中文本（OCR/UI 树）> nodeTextOf(nodeId) 回读 > nodeTextAt(x,y) 回读 > 查询词（兜底）。
        val realTargetText = resolvedMatchedText
            ?.takeIf { it.isNotBlank() }
            ?: withServiceOrNull { it.nodeTextOf(nodeId) }
            ?: withServiceOrNull { it.nodeTextAt(x, y) }
        val effectiveTargetText = realTargetText?.takeIf { it.isNotBlank() } ?: anchorText
        // 附加防御纵深：查询词本身含敏感词（支付/转账/密码等）也一律硬拦截，不因回读失败而放行
        if (anchorText != null && PhoneControlSecurity.isSensitiveTargetText(anchorText)) {
            return blocked("目标文本含敏感内容（${anchorText.take(40)}），已硬拦截（支付/密码/验证码类操作请用 take_over 交还用户手动）")
        }
        if (PhoneControlSecurity.isSensitiveTargetText(effectiveTargetText)) {
            return blocked("目标含敏感内容（${effectiveTargetText?.take(40)}），已硬拦截（支付/密码/验证码类操作请用 take_over 交还用户手动）")
        }
        val node = withServiceOrNull { it.nodeAt(nodeId, x, y) }
        if (node != null && PhoneControlSecurity.isPasswordNode(node)) {
            return blocked("目标为密码/验证码节点，已硬拦截（请用 take_over 交还用户手动输入）")
        }
        // v1 批次12（E，D15）：动作前捕获屏幕签名，供执行后 before/after 校验
        val beforeSig = withServiceOrNull { screenSignature(it) }
        if (PhoneControlSecurity.isManualAction(action, effectiveTargetText.orEmpty())) {
            // v1 真机二次修复 Issue 4b：高危动作按用户三态策略处置（BLOCK 拦截 / ALLOW 放行 / ASK 询问）
            return when (highRiskApprovalProvider()) {
                io.prism.config.HighRiskApprovalMode.BLOCK ->
                    blocked("高危操作已在设置中被拦截（${action}「${effectiveTargetText?.take(30)}」）。如需执行请调整「高危操作」设置或使用 take_over 手动操作")
                io.prism.config.HighRiskApprovalMode.ALLOW ->
                    performWithStateCheck(beforeSig) { withService { perform(it, nodeId, x, y) } }
                io.prism.config.HighRiskApprovalMode.ASK ->
                    manualConfirm("检测到敏感操作：${action}「${effectiveTargetText?.take(30)}」，是否允许继续？")
            }
        }
        return performWithStateCheck(beforeSig) { withService { perform(it, nodeId, x, y) } }
    }

    /** 文本锚点解析结果（node_id 与坐标二选一 + 命中文本供敏感判断）。 */
    private data class AnchorTarget(
        val nodeId: Int? = null,
        val x: Int = 0,
        val y: Int = 0,
        val matchedText: String? = null
    )

    /**
     * v1 批次11（C 文本锚点）：把目标文本解析为「节点 nid 或 屏幕坐标」。
     *
     * 1. **UI 树优先**：[PhoneControlAccessibilityService.findNodeByTextNid]（比 OCR 可靠，
     *    微信/WebView 空树时无命中）；
     * 2. **OCR 兜底**：截图 → [io.prism.vision.OcrTextExtractor.extractElements] → 按
     *    [PhoneControlAccessibilityService.textSimilarity] 模糊匹配 → 命中元素中心（屏幕空间，
     *    经 [PhoneControlAccessibilityService.lastScreenshotScreenSize] 坐标还原）。
     * 坐标最终经 tap 的坐标吸附（snapToClickableCenter）落在可点击节点中心。
     *
     * @param text 目标文本（LLM 锚点）
     * @return 解析结果；未命中（树与 OCR 均无）返回 null
     */
    private suspend fun resolveTextAnchor(text: String): AnchorTarget? {
        val treeNid = withServiceOrNull { it.findNodeByTextNid(text) }
        if (treeNid != null) {
            // guardrail H-1（TKN-V1B11-GUARDRAIL-001）修复：敏感判断必须用**命中节点的真实聚合文本**
            // （如 tap(text="确认") 命中「确认支付」按钮 → matchedText="确认支付" → isSensitiveTargetText=true
            // → 支付类硬拦截/人工确认不绕过），而非 LLM 查询词（"确认" 本身非敏感）。
            val realText = withServiceOrNull { it.nodeTextOf(treeNid) }
            Log.i(TAG, "tap text 锚点 UI 树命中 nid=$treeNid（query=${maskLogText(text)}）")
            return AnchorTarget(nodeId = treeNid, matchedText = anchorSecurityText(text, realText))
        }
        // 批次20（BG-004）：后台执行面下 OCR 兜底不可用（主屏截图≠虚拟屏画面，坐标错位）——
        // 引导 LLM 用后台 screenshot（虚拟屏帧 OCR）获取坐标后再操作。
        val service = PhoneControlAccessibilityService.instance
        if (service?.isBackgroundExecution == true) {
            Log.i(TAG, "后台执行面：文本锚点 UI 树未命中，跳过主屏 OCR 兜底")
            return null
        }
        val extractor = ocrTextExtractor ?: return null
        val dataUrl = service?.captureScreenshot() ?: return null
        val size = service.lastScreenshotScreenSize()
        val sw = size?.first ?: 0
        val sh = size?.second ?: 0
        val elements = runCatching { extractor.extractElements(dataUrl, sw, sh) }.getOrNull() ?: return null
        var best: io.prism.vision.OcrElement? = null
        var bestScore = PhoneControlAccessibilityService.TEXT_MATCH_THRESHOLD
        for (el in elements) {
            if (el.text.isBlank() || el.text == io.prism.vision.MlKitOcrTextExtractor.ICON_PLACEHOLDER_TEXT) continue
            val score = PhoneControlAccessibilityService.textSimilarity(text, el.text)
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        val target = best ?: return null
        // CWE-532：日志脱敏（截断 + 数字串掩码），屏幕文本可能含 PII
        Log.i(TAG, "tap text 锚点 OCR 命中「${maskLogText(target.text)}」（score=$bestScore）@(${target.centerX},${target.centerY})")
        return AnchorTarget(x = target.centerX, y = target.centerY, matchedText = target.text)
    }

    /** 输入型动作（type）：密码/验证码节点或凭据文本硬拦截；发送等高危文本强制人工确认。 */
    private suspend fun runTypeAction(arguments: Map<String, Any?>): String {
        val nodeId = parseInt(arguments, "node_id")
        val x = parseInt(arguments, "x", 0) ?: 0
        val y = parseInt(arguments, "y", 0) ?: 0
        val text = parseString(arguments, "text").orEmpty()
        val node = withServiceOrNull { it.nodeAt(nodeId, x, y) }
        if (node != null && PhoneControlSecurity.isPasswordNode(node)) {
            return blocked("目标为密码/验证码输入框，已硬拦截（请用 take_over 交还用户手动输入）")
        }
        if (PhoneControlSecurity.isCredentialInput(text)) {
            return blocked("输入内容含凭据（密码/验证码/卡号），已硬拦截（请用 take_over 交还用户手动输入）")
        }
        if (PhoneControlSecurity.isManualAction("type", text)) {
            // v1 真机二次修复 Issue 4b：高危输入按用户三态策略处置
            return when (highRiskApprovalProvider()) {
                io.prism.config.HighRiskApprovalMode.BLOCK ->
                    blocked("高危操作已在设置中被拦截（输入「${text.take(30)}」）。如需执行请调整「高危操作」设置或使用 take_over 手动操作")
                io.prism.config.HighRiskApprovalMode.ALLOW ->
                    performWithStateCheck(withServiceOrNull { screenSignature(it) }) {
                        withService { it.performType(nodeId, x, y, text) }
                    }
                io.prism.config.HighRiskApprovalMode.ASK ->
                    manualConfirm("检测到敏感输入：向目标输入「${text.take(30)}」，是否允许继续？")
            }
        }
        // v1 批次13（D，D16）：type 也做 before/after 校验（输入未生效时软提示）
        return performWithStateCheck(withServiceOrNull { screenSignature(it) }) {
            withService { it.performType(nodeId, x, y, text) }
        }
    }

    /** 启动应用：金融专用 App 硬拦截。成功后 settle 等待目标 App 渲染。 */
    private suspend fun runLaunchAction(arguments: Map<String, Any?>): String {
        val rawPkg = parseString(arguments, "package") ?: parseString(arguments, "app")
        if (rawPkg.isNullOrBlank()) return "错误：缺少 package 参数"
        // v1 批次12（C，D14）：包名/别名纠正 —— LLM（尤其 glm-4.6v-flash）常传错误包名或应用名
        //（真机实证：拼多多写成 com.pinduoduo.pinduoduo，正确 com.xunmeng.pinduoduo）。
        // resolvePackage 未命中则按原值尝试（最终由 getLaunchIntentForPackage 决定是否安装）。
        val pkg = PhoneControlPackageMap.resolvePackage(rawPkg) ?: rawPkg
        if (pkg != rawPkg) {
            Log.i(TAG, "launch_app 包名纠正：${rawPkg.take(40)} → $pkg")
        }
        // guardrail P0（TKN-V1B12-GUARDRAIL-001）：敏感判定对**原始输入与纠正后包名双重**检查——
        // prompt 注入可让模型传"招商银行"等中文名经映射解析成 cmb.pb，若只查纠正后包名而黑名单
        // 漏配则绕过金融硬拦截。双重检查确保中文名/别名/错包名任一路径命中敏感即拦截。
        if (PhoneControlSecurity.isSensitivePackage(rawPkg) || PhoneControlSecurity.isSensitivePackage(pkg)) {
            return blocked("禁止启动金融专用应用（$rawPkg → $pkg），已硬拦截（请用 take_over 交还用户手动操作）")
        }
        val svc = PhoneControlAccessibilityService.instance ?: return serviceUnavailableMessage()
        // 批次20（BG-003）：后台分支——启动到虚拟屏（ActivityOptions.setLaunchDisplayId），
        // 校验用 backgroundWindowPackage（虚拟屏窗口包名）替代前台包名轮询。
        if (svc.isBackgroundExecution) {
            val ctx = appContext ?: return "错误：后台模式上下文未初始化"
            val vdm = io.prism.phonecontrol.bg.VirtualDisplayManager
            val displayId = vdm.currentDisplayId
            val result = vdm.launchApp(ctx, pkg)
            if (result.startsWith("已在后台")) {
                kotlinx.coroutines.delay(LAUNCH_SETTLE_MS)
                for (i in 1..LAUNCH_VERIFY_TRIES) {
                    val bgPkg = runCatching { svc.backgroundWindowPackage(displayId) }.getOrNull()
                    if (bgPkg == pkg) {
                        resetStuckState()
                        return "已在后台虚拟屏启动并确认进入 $pkg（主屏已交还用户，可继续后台节点操作）"
                    }
                    kotlinx.coroutines.delay(LAUNCH_VERIFY_GAP_MS)
                }
                val bgPkg = runCatching { svc.backgroundWindowPackage(displayId) }.getOrNull() ?: "无窗口"
                return "已在后台虚拟屏启动 $pkg，但当前虚拟屏前台为 $bgPkg（可能仍在启动）。请用 get_ui_state 确认后再继续"
            }
            return result
        }
        val result = svc.launchApp(pkg)
        if (result.startsWith("已启动")) {
            // v1 批次10：启动后 settle 等待目标 App 首绘（避免 get_ui_state 命中窗口切换过渡期空树）。
            kotlinx.coroutines.delay(LAUNCH_SETTLE_MS)
            // v1 批次11（F5 前台包名校验）：Open-AutoGLM `get_current_app` 思路——启动后轮询校验
            // 当前前台包名是否确实变为目标 App，回灌给 LLM 明确的「已确认进入 / 仍在过渡 / 跳偏」信号，
            // 避免 LLM 对"打开应用商店后是否成功"懵懂而误判（问题①）。
            return confirmLaunchForeground(svc, pkg)
        }
        return result
    }

    /**
     * v1 批次11（F5）：launch_app 后轮询校验目标包名是否真正进入前台。
     *
     * 最多轮询 [LAUNCH_VERIFY_TRIES] 次、每次间隔 [LAUNCH_VERIFY_GAP_MS]，期间若前台包名 == [pkg]
     * 即确认成功；否则返回当前前台包名让 LLM 自行判断（可能是系统过渡页/其它 App，指示应改动作或等待）。
     */
    private suspend fun confirmLaunchForeground(svc: PhoneControlAccessibilityService, pkg: String): String {
        var lastFg: String? = null
        for (i in 1..LAUNCH_VERIFY_TRIES) {
            lastFg = runCatching { svc.currentForegroundPackage() }.getOrNull()
            if (lastFg == pkg) {
                // v1 批次12（D）：确认进入新 App → 重置 Stuck 状态（新任务上下文）
                resetStuckState()
                return "已启动并确认进入目标应用 $pkg"
            }
            kotlinx.coroutines.delay(LAUNCH_VERIFY_GAP_MS)
        }
        val fg = lastFg ?: "未知"
        return if (fg == pkg) {
            "已启动并确认进入目标应用 $pkg"
        } else {
            "已启动应用 $pkg，但当前前台为 $fg（可能仍在过渡或跳转到其它页面）。请用 get_ui_state 确认当前屏幕后再继续"
        }
    }

    /** 截图：多模态模型读 data URL；纯文本模型（UI 树受限时）回灌 OCR 文字+坐标（+图标区域）。 */
    private suspend fun runScreenshot(): String {
        val service = PhoneControlAccessibilityService.instance
            ?: return serviceUnavailableMessage()
        // 批次20（BG-004）：后台执行面——截虚拟屏帧（ImageReader Surface，免 MediaProjection），
        // OCR 坐标即虚拟屏坐标（与后台 tap 同一坐标系）。失败不回退截主屏（感知错位比失败更危险）。
        val dataUrl: String?
        val screenW: Int
        val screenH: Int
        if (service.isBackgroundExecution) {
            val vdm = io.prism.phonecontrol.bg.VirtualDisplayManager
            // 批次22 修复轮六：模拟副屏（trusted）路线 → a11y takeScreenshot(displayId)；
            // 自建虚拟屏路线 → ImageReader 帧缓存
            val frame = if (vdm.isOverlayMode) {
                service.takeScreenshotForDisplay(vdm.currentDisplayId)
                    ?: return "错误：模拟副屏截图失败。需 Android 14+，且在系统「设置 → 无障碍 → Prism」中开启「屏幕截图」能力后重试"
            } else {
                vdm.latestFrame()
                    ?: return "错误：后台虚拟屏暂无可用画面（目标 App 可能尚未渲染）。可稍后重试或用 get_ui_state（节点路径）"
            }
            val size = vdm.frameSize()
            screenW = size?.first ?: frame.width
            screenH = size?.second ?: frame.height
            val scaled = service.downsampleBitmap(frame)
            val bytes = java.io.ByteArrayOutputStream().apply {
                scaled.compress(
                    android.graphics.Bitmap.CompressFormat.JPEG,
                    PhoneControlAccessibilityService.SCREENSHOT_JPEG_QUALITY,
                    this
                )
            }
            if (scaled !== frame) scaled.recycle()
            frame.recycle()
            val base64 = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
            if (base64.length > PhoneControlAccessibilityService.SCREENSHOT_MAX_BASE64_LEN) {
                return "错误：后台截图体积超限，请改用 get_ui_state（节点路径）"
            }
            dataUrl = "data:image/jpeg;base64,$base64"
        } else {
            dataUrl = service.captureScreenshot()
                ?: return "错误：截图失败（需 Android 11+；页面含敏感内容、体积超限或系统拒绝时不可用）。若主模型不支持图片，请改用 UI 树 get_ui_state"
            val screenSize = service.lastScreenshotScreenSize()
            screenW = screenSize?.first ?: 0
            screenH = screenSize?.second ?: 0
        }
        // v1 批次13（A/B，D16）：**不再内嵌 base64 文本进工具结果/会话历史**（真机 ANR 崩溃根因：
        // 400KB base64 单行渲染阻塞主线程 >5s）。
        // - 视觉模型（多模态，如 glm-4.6v-flash）：跳过 OCR（看真图更准更快），返回图片标记，
        //   由 SkillExecutor 把图片以 image_url 注入会话供模型查看；
        // - 纯文本模型：返回 OCR 文字+坐标（无 base64）。
        val url = dataUrl ?: return "错误：截图数据为空"
        // v1 批次13（B/D16c）：视觉降级后即使 supportsVision=true 也走 OCR（端点实测不支持图片）
        if (visionCapableProvider() && !visionDegraded) {
            Log.i(TAG, "runScreenshot（视觉模型）：返回图片标记，由 SkillExecutor 以 image_url 注入会话")
            return SCREENSHOT_IMAGE_MARKER + url
        }
        // v1 批次11（A 致命修复）：把 OCR/图标坐标还原到屏幕空间（与 tap 同一坐标系）
        val extractor = ocrTextExtractor
        val ocr = extractor?.let { e ->
            runCatching { e.extractElements(url, screenW, screenH) }.getOrNull() ?: emptyList()
        } ?: emptyList()
        // v1 批次11（F，D12）：图标区域检测（纯图标按钮无文字时，供模型按编号/坐标 tap）
        val icons = if (extractor != null) {
            runCatching { extractor.detectIcons(url, ocr, screenW, screenH) }.getOrNull() ?: emptyList()
        } else {
            emptyList()
        }
        // v1 批次11（B）+ 批次13（C 精简）：编号列表（SoM 文本版），条目数上限防上下文膨胀
        val textItems = ocr.take(SCREENSHOT_OCR_MAX_ITEMS).mapIndexed { index, el ->
            "[${index + 1}] ${el.text}（坐标 ${el.centerX},${el.centerY}）"
        }
        val iconItems = icons.take(SCREENSHOT_ICON_MAX_ITEMS).mapIndexed { index, el ->
            "[${index + 1}] ${el.text}（坐标 ${el.centerX},${el.centerY}）"
        }
        val screenText = buildString {
            if (textItems.isNotEmpty()) {
                append("\n【屏幕文字（OCR，编号+坐标，可 tap(text=文字) 精确点击）】\n")
                append(textItems.joinToString("\n"))
            }
            if (iconItems.isNotEmpty()) {
                append("\n【屏幕图标区域（无文字标签，按编号/坐标 tap）】\n")
                append(iconItems.joinToString("\n"))
            }
            if (ocr.size > SCREENSHOT_OCR_MAX_ITEMS || icons.size > SCREENSHOT_ICON_MAX_ITEMS) {
                append("\n（其余元素省略，共 OCR ${ocr.size} / 图标 ${icons.size} 项）")
            }
        }
        Log.i(TAG, "runScreenshot: OCR ${ocr.size} 项 / 图标 ${icons.size} 项（屏幕 ${screenW}x$screenH）")
        return "截图成功（已附屏幕文字+坐标，供 tap(text=文字) 定位）$screenText"
    }

    /** 人工接管：返回 ask_user 标记 + 载荷，触发提问卡片 + StopAtTools。 */
    private suspend fun runTakeOver(arguments: Map<String, Any?>): String {
        val reason = parseString(arguments, "reason") ?: "遇到需要人工处理的步骤"
        // ADR-065（US-3114 AC-4）：后台执行时把目标应用临时迁到主屏，便于用户真正接管
        // （下次后台操作由 VirtualDisplayManager 自动迁回执行面并恢复 Agent）。
        val migration = runCatching {
            val ctx = appContext
            if (ctx != null && io.prism.phonecontrol.bg.VirtualDisplayManager.isCreated) {
                io.prism.phonecontrol.bg.VirtualDisplayManager.takeOverToMainScreen(ctx)
            } else {
                null
            }
        }.getOrNull()
        val note = migration?.let { "\n$it" } ?: ""
        return manualConfirm("需要人工接管：$reason。请手动完成该步骤。$note")
    }

    // ==================== 批次20 后台执行面（BG-001/002/004，ADR-045） ====================

    /**
     * 后台模式前置守卫（BG-003）：API 30+ → 无障碍实例 → 悬浮窗豁免 → 虚拟屏创建。
     *
     * @return null=通过；否则返回给 LLM 的可诊断错误文案
     */
    private fun backgroundGuard(): String? {
        val vdm = io.prism.phonecontrol.bg.VirtualDisplayManager
        if (!vdm.isSupported()) {
            return "错误：后台操控需要 Android 11（API 30）及以上系统（跨屏无障碍能力），本机不支持。请改用前台模式"
        }
        if (PhoneControlAccessibilityService.instance == null) return serviceUnavailableMessage()
        val ctx = appContext ?: return "错误：后台模式上下文未初始化，请改用前台模式"
        if (!overlayPermissionProvider(ctx)) {
            // guardrail R3 P3-A：chip 开启时"去掉 mode 参数"已默认路由后台——指引必须指向
            // 显式 foreground 覆盖（否则 LLM 去掉 mode 后仍进后台守卫，空转重试）
            return "错误：后台启动需要「显示在其他应用上层」（悬浮窗）权限——这是系统对后台启动 Activity 的官方豁免条件。" +
                "请在系统设置中授权 Prism 该权限后重试；或对该次调用显式传 mode=\"foreground\" 改用前台模式"
        }
        if (vdm.ensureDisplay(ctx) == android.view.Display.INVALID_DISPLAY) {
            return "错误：后台虚拟屏创建失败（系统可能限制虚拟显示器）。请改用前台模式"
        }
        return null
    }

    /**
     * 在后台执行面中执行 [block]（BG-002）：把无障碍服务切换到虚拟屏 displayId，
     * 节点感知/操作自动路由到虚拟屏窗口；结束后**必须**还原前台执行面（finally）。
     */
    private suspend fun withBackgroundExecution(block: suspend () -> String): String {
        val svc = PhoneControlAccessibilityService.instance
        val displayId = io.prism.phonecontrol.bg.VirtualDisplayManager.currentDisplayId
        svc?.setExecutionDisplay(displayId)
        try {
            return block()
        } finally {
            svc?.setExecutionDisplay(null)
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 读取当前 UI 树并序列化。v1 批次10：跨 App 启动后 UI 树常**瞬时空**（窗口切换过渡期 /
     * 目标 App 未首绘），且切重内存 App（如微信）后进程被杀重连无障碍服务也有短暂空窗。
     *
     * 参考 Open-AutoGLM `wait_after` / iot-book「树判空后短暂 sleep 重试 N 次」：工具内部
     * 自愈重试，避免返回一次性失败让 LLM 在 round 级反复 get_ui_state（真机曾 round=38）
     * → 轮次耗尽 / kimi RPM 限流 / 表现为「无法感知当前状态 / 超时」。
     *
     * @return UI 树文本；连续 [UI_STATE_RETRY_COUNT] 次为空返回可诊断失败文案
     */
    private suspend fun getUiState(): String {
        var lastService: PhoneControlAccessibilityService? = null
        for (attempt in 1..UI_STATE_RETRY_COUNT) {
            val service = PhoneControlAccessibilityService.instance
            if (service != null) {
                lastService = service
                val tree = service.getUiTreeText()
                if (tree != null) {
                    // v1 批次12（D，D15）Stuck 检测：连续 N 步同屏无变化 → 附恢复引导
                    val stuck = trackStuck(screenSignature(service))
                    val treeOut = "当前屏幕 UI 树（节点编号 [N] 供 tap/type 引用）：\n$tree"
                    return if (stuck != null) "$treeOut\n\n$stuck" else treeOut
                }
            } else if (!accessibilityEnabledProvider()) {
                // 系统未启用：无需重试，直接引导开启
                return serviceUnavailableMessage()
            }
            // 树为空 / 实例重连中：短暂等待后重试（下一轮 self-heal）
            if (attempt < UI_STATE_RETRY_COUNT) {
                kotlinx.coroutines.delay(UI_STATE_RETRY_DELAY_MS)
            }
        }
        // v1 批次11（进程重启感知）：区分"系统已启用但实例未连（进程被 MIUI 回收重启，重连中）"——
        // 这是可恢复瞬态，回灌给 LLM 让它等待/重试而非当永久失败弃疗（也是之前反复 get_ui_state 死循环的出口）。
        val serviceStillNull = PhoneControlAccessibilityService.instance == null && accessibilityEnabledProvider()
        if (serviceStillNull) {
            return SERVICE_REBINDING_MSG + "（如果持续失败，请使用 take_over 人工接管）"
        }
        // 实例存活但树不可读：B11-2 前台 App 判定——告知 LLM"当前前台是谁"，区分
        // "目标 App 已打开但 UI 树受保护/未就绪（应等待）" 与 "App 未打开（应改动作）"。
        val fg = lastService?.let { svc ->
            runCatching { svc.currentForegroundPackage() }.getOrNull()
        }
        Log.w(TAG, "getUiState: 8 次重试后仍无法读取 UI 树；前台包名=$fg（诊断：见 currentRoot/mapNode 分支日志）")
        val fgHint = fg?.let { "（当前前台应用: $it）" } ?: ""
        // 批次22 修复轮（2026-09-06）：**后台执行面诚实判定**——先区分"虚拟屏完全无窗口"
        // （目标 App 拒绝渲染，风控 App 常态，重试无效）与"有窗口但 UI 树受保护"（应走视觉兜底）
        if (lastService?.isBackgroundExecution == true) {
            val displayId = io.prism.phonecontrol.bg.VirtualDisplayManager.currentDisplayId
            val bgPkg = if (displayId != android.view.Display.INVALID_DISPLAY) {
                runCatching { lastService?.backgroundWindowPackage(displayId) }.getOrNull()
            } else {
                null
            }
            if (bgPkg == null) {
                Log.w(TAG, "getUiState: 后台虚拟屏无窗口 → 诚实判定风控拒绝渲染")
                return "错误：目标应用未在后台虚拟屏渲染（虚拟屏无任何窗口）——大概率该 App 风控拒绝在" +
                    "非物理屏运行（电商/支付类常见），**重试后台启动与 get_ui_state 均无效**。" +
                    "请改用 mode=foreground 在主屏继续任务（会打断用户当前操作，先告知用户），" +
                    "或调用 take_over 交还用户手动处理。不要再对同一应用重试后台启动"
            }
            Log.w(TAG, "getUiState: 后台虚拟屏有窗口（$bgPkg）但 UI 树不可读 → 视觉兜底")
            return "后台虚拟屏上目标应用（$bgPkg）已渲染但对无障碍树不开放。请使用 mode=background 的 " +
                "screenshot 查看虚拟屏画面：多模态模型直接看图用 tap(text=目标文字) 定位；" +
                "纯文本模型注意 screenshot 会附带【OCR 文字+坐标】（即虚拟屏坐标），据此 tap(type 皆传 mode=background)。不要再次调用 get_ui_state"
        }
        // v1 批次11（D5 自动切视觉/坐标兜底）：微信/WebView/Flutter 等对无障碍树完全屏蔽
        // （根拿到了但 childCount=0 → getUiTreeText 返回 null）。UI 树方案对此类 App 本质不可用，
        // 直接引导 LLM 用 screenshot(多模态可读图 / 纯文本模型经 OCR 得文字+坐标)+坐标 tap/swipe 继续。
        return "当前屏幕无法通过 UI 树读取（$fgHint 该应用对无障碍树不开放，常见于微信/WebView/Flutter）。" +
            "【自动切视觉兜底】请使用 screenshot 截屏查看当前界面：多模态模型直接看图用 tap(text=目标文字) 或坐标操作；" +
            "纯文本模型注意 screenshot 会附带【OCR 文字+坐标】与【图标区域】，据此用 tap(text=...) 精确定位目标。" +
            "不要再次调用 get_ui_state"
    }

    /** 携带服务实例执行；未连接返回错误提示。 */
    private inline fun withService(block: (PhoneControlAccessibilityService) -> String): String {
        val service = PhoneControlAccessibilityService.instance
            ?: return serviceUnavailableMessage()
        return block(service)
    }

    /** 携带服务实例查询；未连接返回 null。 */
    private inline fun <T> withServiceOrNull(block: (PhoneControlAccessibilityService) -> T?): T? {
        val service = PhoneControlAccessibilityService.instance ?: return null
        return block(service)
    }

    /** 硬拦截结果（⚠️ 前缀，SkillExecutor 按 isFailureResult 回灌失败）。 */
    private fun blocked(reason: String): String = "$BLOCKED_PREFIX$reason"

    /**
     * 无障碍不可用时的差异化文案（v1 真机二次修复 Issue 4）：系统已启用但实例未连 → 提示
     * "重连中（稍后重试）"，避免 LLM 误判为"未启用"而放弃；系统未启用 → 引导开启。
     */
    private fun serviceUnavailableMessage(): String =
        if (accessibilityEnabledProvider()) SERVICE_REBINDING_MSG else SERVICE_DISABLED_MSG

    /**
     * 强制 MANUAL：返回 ask_user 标记 + 载荷 → SkillExecutor 发射 AskUser + StopAtTools。
     *
     * **Issue 5（后台可见）**：同时通过 [askUserNotifier] 发一条高优先级系统通知（含 允许/拒绝
     * 操作按钮）。用户即使处于其它 App 也能作答；答案经 notifier.answers 流由
     * ConversationViewModel 消费并作为下一条 user 消息回灌工具回路（等价于在提问卡片上作答）。
     * 通知发送失败（如未授予通知权限）不影响主流程，提问卡片仍可用。
     */
    private suspend fun manualConfirm(question: String): String {
        askUserNotifier?.let { notifier ->
            runCatching { notifier.request(question) }
                .onFailure { Log.w(TAG, "高危确认通知发送失败（不影响提问卡片冲突）") }
        }
        return AskUserLocalToolExecutor.RESULT_MARKER + askUserJson(
            question = question,
            options = listOf("允许" to "确认执行该操作", "取消" to "阻止该操作")
        )
    }

    /** 提取动作参数文本（供敏感关键词判断，保留兼容）。 */
    internal fun paramText(arguments: Map<String, Any?>): String =
        arguments.values.filterNotNull().joinToString(" ").take(200)

    /**
     * 构造 ask_user 载荷 JSON（纯函数可测）——与 AskUserLocalToolExecutor.execute 的
     * 返回载荷结构一致（`{"questions":[{question, options, multiSelect}]}`），
     * SkillExecutor.parseAskUserPayload 可解析。
     */
    internal fun askUserJson(question: String, options: List<Pair<String, String?>>): String {
        val optionArray = buildJsonArray {
            options.forEach { (label, desc) ->
                add(
                    buildJsonObject {
                        put("label", label)
                        desc?.let { put("description", it) }
                    }
                )
            }
        }
        val questions = buildJsonArray {
            add(
                buildJsonObject {
                    put("question", question.take(200))
                    put("options", optionArray)
                    put("multiSelect", false)
                }
            )
        }
        return buildJsonObject { put("questions", questions) }.toString()
    }

    companion object {
        private const val TAG = "PhoneControlTool"

        /** 工具命名空间前缀。 */
        const val NAMESPACE = "phone_control__"

        /** 被拦截结果前缀（SkillExecutor 按 isFailureResult 识别为失败并回灌）。 */
        const val BLOCKED_PREFIX = "⚠️ "

        /** 无障碍未启用：引导开启。 */
        private const val SERVICE_DISABLED_MSG =
            "错误：手机操控无障碍服务未开启。请在系统「设置 → 无障碍」中开启 Prism 的无障碍服务，然后重试"

        /** 无障碍已在系统开启但服务实例暂未连接（重连中）：提示稍后重试，避免 LLM 误判放弃。
         *  修复轮九：补充**恢复指引**——系统一键清理（force-stop 语义）后系统不自动重绑，
         *  打开一次 Prism 应用即可触发重连（真机 19:56:34 OneKeyClean 后卡重连实证）。 */
        private const val SERVICE_REBINDING_MSG =
            "错误：无障碍服务已在系统中开启，但服务实例暂未连接（重连中，通常数秒内自动恢复）。" +
                "请等待 10-30 秒后重试同一操作；若连续 2 次仍失败，说明系统清理已停止应用进程——" +
                "请用户手动打开一次 Prism 应用（无障碍将自动重连，无需其他操作），然后继续任务"

        /** wait 工具最大等待（防 LLM 无限等待拖死回路）。 */
        const val MAX_WAIT_MS = 10_000

        /** get_ui_state 瞬时空树/重连自愈重试次数（v1 批次11，进程重启重连可达秒级）。 */
        private const val UI_STATE_RETRY_COUNT = 8

        /** get_ui_state 空树/重连重试间隔（毫秒）。8 次 × 500ms ≈ 4s 预算（远低于外层 30s 超时）。 */
        private const val UI_STATE_RETRY_DELAY_MS = 500L

        /** launch_app 启动后 settle 等待（毫秒），让目标 App 完成首绘/渲染再供 get_ui_state 读取。 */
        private const val LAUNCH_SETTLE_MS = 700L

        /** launch_app 前台包名校验（F5）：轮询次数与间隔。合计 ≤ 5×350ms+settle ≈ 2.5s，远低于外层 30s 超时。 */
        private const val LAUNCH_VERIFY_TRIES = 5
        private const val LAUNCH_VERIFY_GAP_MS = 350L

        /**
         * v1 批次12（D，D15）：Stuck 检测阈值——屏幕签名连续 N 步无变化即附恢复引导。
         * 参考 DroidClaw / Mobile-Agent-v3（N=3）；数值可再调（过高响应慢、过低误报）。
         */
        private const val STUCK_THRESHOLD = 3

        /**
         * v1 批次13（A/B，D16）：手机截图图片标记。视觉模型截图返回 `标记 + dataUrl`，
         * [io.prism.skill.SkillExecutor] 检测该前缀后：把图片以 image_url 注入会话（模型看真图）、
         * 并把 base64 从持久化工具结果中剥离（防 ANR/历史膨胀）。
         */
        internal const val SCREENSHOT_IMAGE_MARKER = "【手机截图图片】"

        /** 截图 OCR 文字条目上限（v1 批次13 C 精简，防上下文膨胀）。 */
        internal const val SCREENSHOT_OCR_MAX_ITEMS = 40

        /** 截图图标区域条目上限（v1 批次13 C 精简）。 */
        internal const val SCREENSHOT_ICON_MAX_ITEMS = 15

        /**
         * guardrail H-1（TKN-V1B11-GUARDRAIL-001）：文本锚点命中后用于敏感/高危判断的目标文本。
         *
         * 必须用**命中节点/元素的真实文本**（如 `tap(text="确认")` 命中「确认支付」按钮 →
         * 返回 "确认支付"，[PhoneControlSecurity.isSensitiveTargetText] 命中 → 拦截），
         * 而非 LLM 查询词（"确认" 本身非敏感 → 否则支付类敏感拦截被查询词绕过）。
         * UI 树分支传 [PhoneControlAccessibilityService.nodeTextOf] 结果，OCR 分支传匹配元素文本。
         *
         * @param query LLM 锚点查询词
         * @param realText 命中节点/元素的真实聚合文本（可为 null）
         * @return 用于敏感判断的文本：realText 优先，null 时回退 query（防御性）
         */
        internal fun anchorSecurityText(query: String, realText: String?): String = realText ?: query

        /**
         * guardrail R-4（TKN-V1B11-GUARDRAIL-002）：日志文本脱敏（CWE-532）。
         *
         * 屏幕 OCR/锚点查询文本可能含验证码/OTP/PII，写入 logcat 前：截断至 8 字符 +
         * 4 位以上连续数字串替换为「***」（验证码/手机号形态）。
         *
         * @param text 原始文本
         * @return 脱敏后的日志片段（≤8 字符）
         */
        internal fun maskLogText(text: String): String =
            text.replace(DIGIT_RUN_REGEX, "***").take(8)

        /** [maskLogText] 用：≥4 位连续数字（验证码/OTP 形态）。 */
        private val DIGIT_RUN_REGEX = Regex("""\d{4,}""")

        /**
         * 解析整数参数（纯函数可测）。
         *
         * @param arguments 工具参数
         * @param key 参数键
         * @param default 缺失/非法时的默认值
         * @return 解析值
         */
        internal fun parseInt(arguments: Map<String, Any?>, key: String, default: Int = -1): Int? {
            val value = arguments[key] ?: return if (default < 0) null else default
            return when (value) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull() ?: (if (default < 0) null else default)
                else -> if (default < 0) null else default
            }
        }

        /** 解析字符串参数（纯函数可测）。 */
        internal fun parseString(arguments: Map<String, Any?>, key: String): String? {
            val value = arguments[key] ?: return null
            return when (value) {
                is String -> value
                else -> value.toString()
            }
        }

        /**
         * 生成手机操控工具定义（v1 US-201，供 ConversationViewModel.buildTools 合并）。
         *
         * 纯函数可测；JSON Schema 参数格式（type:object + properties），避免 400。
         * 批次20（BG-003）：get_ui_state/tap/long_press/double_tap/swipe/type/back/home/
         * launch_app/screenshot 增加可选 mode 执行面参数（默认 foreground 向后兼容）。
         */
        internal fun buildToolDefinitions(): List<io.prism.network.ToolDefinition> = listOf(
            tool("get_ui_state", "读取当前屏幕 UI 树为结构化文本（节点带 [N] 序号），用于理解屏幕内容。执行任何点击/输入前先调用本工具获取节点；若返回引导使用 screenshot（UI 树受限，常见微信/WebView/Flutter），则改用 screenshot 获取 OCR 文字+坐标。后台操控开关开启时默认读后台虚拟屏。", """{"type":"object","properties":{"mode":{"type":"string","enum":["foreground","background"],"description":"执行面：不传时由「后台操控」开关决定（开=background，关=foreground）；显式 foreground=主屏前台（手势可用）；显式 background=后台虚拟屏（仅节点操作，需 Android 11+ 与悬浮窗）"}},"required":[]}"""),
            tool("tap", "点击屏幕元素。**优先用 text 参数描述目标文字（最可靠，自动定位；支持 UI 树与 OCR 双通道）**；有 node_id（get_ui_state 的 [N] 编号）时用 node_id；仅当两者都不可用时才用坐标 x/y（坐标需精确到目标中心）。禁止点击支付/转账/密码/验证码等敏感内容（已被拦截器硬拦截）。mode=background 时仅支持 text/node_id（坐标手势不可用）。", """{"type":"object","properties":{"text":{"type":"string","description":"目标可见文字（如“搜索”“发送”，优先，系统自动定位并点击）"},"node_id":{"type":"integer","description":"UI 树节点序号 [N]（优先于坐标）"},"x":{"type":"integer","description":"点击 x 坐标（仅当无 text 且无 node_id 时用）"},"y":{"type":"integer","description":"点击 y 坐标（仅当无 text 且无 node_id 时用）"},"mode":{"type":"string","enum":["foreground","background"],"description":"执行面：foreground=主屏前台（默认）；background=后台虚拟屏（仅 text/node_id）"}},"required":[]}"""),
            tool("long_press", "长按屏幕元素（优先 text 描述目标；或 node_id / 坐标）。后台模式在 Shizuku 就绪时经 shell 注入可用。", """{"type":"object","properties":{"text":{"type":"string"},"node_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"},"mode":{"type":"string","enum":["foreground","background"],"description":"执行面：不传时由「后台操控」开关决定；background 下长按需 Shizuku 就绪"}},"required":[]}"""),
            tool("double_tap", "双击屏幕元素（优先 text 描述目标；或 node_id / 坐标），用于打开/展开等需要点两下的场景。仅前台模式支持（后台 shell 双击时序不可靠，后台请改用两次 tap 或前台模式）。", """{"type":"object","properties":{"text":{"type":"string"},"node_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"},"mode":{"type":"string","enum":["foreground","background"],"description":"执行面（默认由后台操控开关决定；background 不支持双击）"}},"required":[]}"""),
            tool("swipe", "滑动屏幕（from_x,from_y 到 to_x,to_y）。后台模式在 Shizuku 就绪时经 shell 注入可用（后台翻页也可用可点击标签）。", """{"type":"object","properties":{"from_x":{"type":"integer"},"from_y":{"type":"integer"},"to_x":{"type":"integer"},"to_y":{"type":"integer"},"mode":{"type":"string","enum":["foreground","background"],"description":"执行面：不传时由「后台操控」开关决定；background 下滑动需 Shizuku 就绪"}},"required":["from_x","from_y","to_x","to_y"]}"""),
            tool("type", "向输入框输入文本（node_id 或点击坐标定位输入框）。禁止输入密码/验证码/卡号；发送消息类文本需用户确认。mode=background 时用虚拟屏 UI 树的 node_id/text 定位。", """{"type":"object","properties":{"node_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"},"text":{"type":"string","description":"要输入的文本"},"mode":{"type":"string","enum":["foreground","background"],"description":"执行面：不传时由「后台操控」开关决定（开=background，关=foreground）；显式 foreground=主屏前台；显式 background=后台虚拟屏"}},"required":["text"]}"""),
            tool("back", "返回上一页。后台模式在 Shizuku 就绪时经 shell keyevent 注入虚拟屏可用；未装 Shizuku 时会拒绝（防干扰主屏）。", """{"type":"object","properties":{"mode":{"type":"string","enum":["foreground","background"],"description":"执行面：不传时由「后台操控」开关决定；background 下 back 需 Shizuku 就绪"}},"required":[]}"""),
            tool("home", "返回桌面。后台模式在 Shizuku 就绪时经 shell keyevent 注入虚拟屏可用；未装 Shizuku 时会拒绝（防干扰主屏）。", """{"type":"object","properties":{"mode":{"type":"string","enum":["foreground","background"],"description":"执行面：不传时由「后台操控」开关决定；background 下 home 需 Shizuku 就绪"}},"required":[]}"""),
            tool("launch_app", "启动应用（package 为应用包名）。mode=background 时把应用启动到后台虚拟屏（用户主屏不受影响），随后用 mode=background 的 get_ui_state/tap 继续操作。禁止启动支付/银行等金融专用应用。", """{"type":"object","properties":{"package":{"type":"string","description":"应用包名，如 com.tencent.mm"},"mode":{"type":"string","enum":["foreground","background"],"description":"执行面：foreground=主屏前台（默认）；background=后台虚拟屏（用户可同时使用手机）"}},"required":["package"]}"""),
            tool("wait", "等待页面加载（ms 毫秒，默认 800，上限 10000）。", """{"type":"object","properties":{"ms":{"type":"integer","description":"等待毫秒数"}},"required":[]}"""),
            tool("screenshot", "截取当前屏幕（需 Android 11+）。**多模态模型：直接看图用 tap(text/坐标) 操作；纯文本模型：截图会附带【OCR 文字+坐标】和【图标区域】，据此用 tap(text=...) 或 tap(x,y) 定位目标**。UI 树受限（微信/WebView/Flutter）或需要确认屏幕状态时调用；支付/密码等受保护页面会失败。mode=background 时截后台虚拟屏画面（OCR 坐标即虚拟屏坐标）。", """{"type":"object","properties":{"mode":{"type":"string","enum":["foreground","background"],"description":"执行面：foreground=主屏前台（默认）；background=后台虚拟屏"}},"required":[]}"""),
            tool("take_over", "请求人工接管：遇到登录/验证码/支付/无法自动完成的情况时调用，暂停自动操作并交还用户手动处理。", """{"type":"object","properties":{"reason":{"type":"string","description":"需要人工接管的原因"}},"required":["reason"]}""")
        )

        /** 构造单个工具定义。 */
        private fun tool(name: String, description: String, parametersJson: String): io.prism.network.ToolDefinition {
            val parameters = kotlinx.serialization.json.Json.parseToJsonElement(parametersJson)
            return io.prism.network.ToolDefinition(
                function = io.prism.network.ToolDefinition.FunctionDef(
                    name = "$NAMESPACE$name",
                    description = description,
                    parameters = parameters
                )
            )
        }
    }
}
