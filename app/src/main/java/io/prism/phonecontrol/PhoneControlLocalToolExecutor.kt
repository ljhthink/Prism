package io.prism.phonecontrol

import android.util.Log
import io.prism.skill.AskUserLocalToolExecutor
import io.prism.skill.LocalToolExecutor
import kotlinx.coroutines.CancellationException
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
    private val askUserNotifier: PhoneControlAskUserNotifier? = null
) : LocalToolExecutor {

    override fun handles(toolName: String): Boolean = toolName.startsWith(NAMESPACE)

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        val tool = toolName.removePrefix(NAMESPACE)
        return try {
            when (tool) {
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
                    withService { it.performSwipe(fx, fy, tx, ty) }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "execute($tool) 失败：${e::class.simpleName}")
            "错误：手机操控工具执行失败（${e::class.simpleName}）"
        }
    }

    // ==================== 工具实现 ====================

    /** 目标型动作（tap/long_press/double_tap）：先做敏感目标拦截，再执行。 */
    private suspend fun runTargetAction(
        action: String,
        arguments: Map<String, Any?>,
        perform: (PhoneControlAccessibilityService, Int?, Int, Int) -> String
    ): String {
        val nodeId = parseInt(arguments, "node_id")
        val x = parseInt(arguments, "x", 0) ?: 0
        val y = parseInt(arguments, "y", 0) ?: 0
        // 敏感目标判断：优先节点文本（node_id），坐标兜底回读 UI 树
        val targetText = withServiceOrNull { it.nodeTextOf(nodeId) ?: it.nodeTextAt(x, y) }
        if (PhoneControlSecurity.isSensitiveTargetText(targetText)) {
            return blocked("目标含敏感内容（${targetText?.take(40)}），已硬拦截（支付/密码/验证码类操作请用 take_over 交还用户手动）")
        }
        val node = withServiceOrNull { it.nodeAt(nodeId, x, y) }
        if (node != null && PhoneControlSecurity.isPasswordNode(node)) {
            return blocked("目标为密码/验证码节点，已硬拦截（请用 take_over 交还用户手动输入）")
        }
        if (PhoneControlSecurity.isManualAction(action, targetText.orEmpty())) {
            // v1 真机二次修复 Issue 4b：高危动作按用户三态策略处置（BLOCK 拦截 / ALLOW 放行 / ASK 询问）
            return when (highRiskApprovalProvider()) {
                io.prism.config.HighRiskApprovalMode.BLOCK ->
                    blocked("高危操作已在设置中被拦截（${action}「${targetText?.take(30)}」）。如需执行请调整「高危操作」设置或使用 take_over 手动操作")
                io.prism.config.HighRiskApprovalMode.ALLOW ->
                    withService { perform(it, nodeId, x, y) }
                io.prism.config.HighRiskApprovalMode.ASK ->
                    manualConfirm("检测到敏感操作：${action}「${targetText?.take(30)}」，是否允许继续？")
            }
        }
        return withService { perform(it, nodeId, x, y) }
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
                    withService { it.performType(nodeId, x, y, text) }
                io.prism.config.HighRiskApprovalMode.ASK ->
                    manualConfirm("检测到敏感输入：向目标输入「${text.take(30)}」，是否允许继续？")
            }
        }
        return withService { it.performType(nodeId, x, y, text) }
    }

    /** 启动应用：金融专用 App 硬拦截。 */
    private fun runLaunchAction(arguments: Map<String, Any?>): String {
        val pkg = parseString(arguments, "package") ?: parseString(arguments, "app")
        if (pkg.isNullOrBlank()) return "错误：缺少 package 参数"
        if (PhoneControlSecurity.isSensitivePackage(pkg)) {
            return blocked("禁止启动金融专用应用 $pkg（支付/银行），已硬拦截（请用 take_over 交还用户手动操作）")
        }
        return withService { it.launchApp(pkg) }
    }

    /** 截图：仅多模态模型可用；失败/超限返回提示，不阻塞。 */
    private fun runScreenshot(): String {
        val service = PhoneControlAccessibilityService.instance
            ?: return serviceUnavailableMessage()
        val dataUrl = service.captureScreenshot()
            ?: return "错误：截图失败（需 Android 11+；页面含敏感内容、体积超限或系统拒绝时不可用）。若主模型不支持图片，请改用 UI 树 get_ui_state"
        return "截图成功（data URL，仅多模态模型可解读）：$dataUrl"
    }

    /** 人工接管：返回 ask_user 标记 + 载荷，触发提问卡片 + StopAtTools。 */
    private suspend fun runTakeOver(arguments: Map<String, Any?>): String {
        val reason = parseString(arguments, "reason") ?: "遇到需要人工处理的步骤"
        return manualConfirm("需要人工接管：$reason。请手动完成该步骤。")
    }

    // ==================== 内部工具 ====================

    private fun getUiState(): String {
        val service = PhoneControlAccessibilityService.instance
            ?: return serviceUnavailableMessage()
        val tree = service.getUiTreeText()
            ?: return "错误：无法读取当前屏幕内容（可能无活动窗口或页面受保护）"
        return "当前屏幕 UI 树（节点编号 [N] 供 tap/type 引用）：\n$tree"
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

        /** 无障碍已在系统开启但服务实例暂未连接（重连中）：提示稍后重试，避免 LLM 误判放弃。 */
        private const val SERVICE_REBINDING_MSG =
            "错误：无障碍已在系统中开启，但服务实例暂未连接（重连中）。请稍等片刻后重试同一操作"

        /** wait 工具最大等待（防 LLM 无限等待拖死回路）。 */
        const val MAX_WAIT_MS = 10_000

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
         */
        internal fun buildToolDefinitions(): List<io.prism.network.ToolDefinition> = listOf(
            tool("get_ui_state", "读取当前屏幕 UI 树为结构化文本（节点带 [N] 序号），用于理解屏幕内容。执行任何点击/输入前先调用本工具获取节点。", """{"type":"object","properties":{},"required":[]}"""),
            tool("tap", "点击屏幕元素：优先 node_id（get_ui_state 返回的 [N]），也可用坐标 x/y。禁止点击支付/转账/密码/验证码等敏感内容（已被拦截器硬拦截）。", """{"type":"object","properties":{"node_id":{"type":"integer","description":"UI 树节点序号 [N]"},"x":{"type":"integer","description":"点击 x 坐标"},"y":{"type":"integer","description":"点击 y 坐标"}},"required":[]}"""),
            tool("long_press", "长按屏幕元素（node_id 或坐标）。", """{"type":"object","properties":{"node_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":[]}"""),
            tool("double_tap", "双击屏幕元素（node_id 或坐标），用于打开/展开等需要点两下的场景。", """{"type":"object","properties":{"node_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"}},"required":[]}"""),
            tool("swipe", "滑动屏幕（from_x,from_y 到 to_x,to_y）。", """{"type":"object","properties":{"from_x":{"type":"integer"},"from_y":{"type":"integer"},"to_x":{"type":"integer"},"to_y":{"type":"integer"}},"required":["from_x","from_y","to_x","to_y"]}"""),
            tool("type", "向输入框输入文本（node_id 或点击坐标定位输入框）。禁止输入密码/验证码/卡号；发送消息类文本需用户确认。", """{"type":"object","properties":{"node_id":{"type":"integer"},"x":{"type":"integer"},"y":{"type":"integer"},"text":{"type":"string","description":"要输入的文本"}},"required":["text"]}"""),
            tool("back", "返回上一页。", """{"type":"object","properties":{},"required":[]}"""),
            tool("home", "返回桌面。", """{"type":"object","properties":{},"required":[]}"""),
            tool("launch_app", "启动应用（package 为应用包名）。禁止启动支付/银行等金融专用应用。", """{"type":"object","properties":{"package":{"type":"string","description":"应用包名，如 com.tencent.mm"}},"required":["package"]}"""),
            tool("wait", "等待页面加载（ms 毫秒，默认 800，上限 10000）。", """{"type":"object","properties":{"ms":{"type":"integer","description":"等待毫秒数"}},"required":[]}"""),
            tool("screenshot", "截取当前屏幕为图片（需 Android 11+；**仅当主模型支持图片（多模态）且 UI 树信息不足时使用**；纯文本模型请勿调用以免上下文膨胀；支付/密码等受保护页面会失败）。", """{"type":"object","properties":{},"required":[]}"""),
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
