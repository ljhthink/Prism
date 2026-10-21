package io.prism.qq

import android.util.Log
import io.prism.phonecontrol.PhoneControlAccessibilityService
import io.prism.phonecontrol.PhoneControlSessionManager
import io.prism.phonecontrol.bg.ShizukuShell
import io.prism.phonecontrol.bg.VirtualDisplayManager
import io.prism.skill.AskUserLocalToolExecutor
import io.prism.skill.LocalToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * QQ 小号助手本地工具执行器（ADR-062 路线 R1；PRD US-3102 / US-3104 / US-3108）。
 *
 * **工具集**（命名空间 `qq__`）：
 * - `qq__send_message(contact, message)`：以小号身份给好友发消息（US-3102）
 * - `qq__send_voice(contact, message)`：以 Prism 音色包合成语音发送（US-3113；仅协议端）
 * - `qq__set_signature(signature)`：修改小号个性签名（US-3104）
 * - `qq__schedule_message(action=status|create|cancel, ...)`：随机定时主动消息管理（US-3108）
 *
 * **UI 链路**（M0 PoC 实证，docs/reports/2026-10-19-qq-m0-poc.md）：
 * QQ 暴露稳定 resource-id（`id/input`、`id/send_btn`）与可读 text/content-desc——
 * **viewId 优先、text/desc 兜底**。
 *
 * **后台副屏执行**（2026-10-19 改造，PRD US-3102 AC-2「发送在后台完成」）：小号运行于 XSpace
 * 用户 999——经 Shizuku 创建可信副屏（[VirtualDisplayManager.ensureDisplay]）+ `am start
 * --user 999 --display N`（[ShizukuShell.amStartForUserOnDisplay]）把小号拉到副屏顶层，
 * 再把无障碍执行面（[PhoneControlAccessibilityService.setExecutionDisplay]）指向该 display，
 * 节点感知/操作自动路由到副屏小号窗口；**全链路不占用用户前台**（真机 PoC 实证 user-0 无障碍
 * 可读取 user-999 在副屏的 QQ 窗口）。结束后 finally 还原执行面。
 *
 * **安全闸门**（依层）：总开关 + 风险确认（US-3101）→ 节流/熔断（US-3105）→
 * 高风险三态确认（发送类动作，交互路径；定时路径视为用户已显式授权，仍受节流）。
 *
 * **降级契约**（与 [LocalToolExecutor] 一致）：所有失败返回可读字符串，绝不抛异常
 * （`CancellationException` 除外）；每步失败附当前页诊断，LLM 可改用
 * `phone_control__*` 工具接手或 take_over。
 *
 * **可测性**：参数解析 / 结果判定 / 节流编排为 internal 纯函数（JVM 可测）；
 * UI 链路依赖服务单例（真机验证）。
 */
class QqLocalToolExecutor(
    /** 配置仓库（读 + 写定时任务配置；生产：QqConfigRepositoryHolder）。 */
    private val configRepoProvider: suspend () -> QqConfigRepository?,
    /** 运行时状态仓库（节流/熔断记录；生产：QqRuntimeStateRepositoryHolder）。 */
    private val runtimeRepoProvider: suspend () -> QqRuntimeStateRepository?,
    /** 高危动作确认策略（US-3103/3104 AC-4；生产：HighRiskApprovalRepository.mode）。 */
    private val highRiskApprovalProvider: suspend () -> io.prism.config.HighRiskApprovalMode =
        { io.prism.config.HighRiskApprovalMode.ASK },
    /** 高危确认通知桥（后台可见；与手机操控同范式）。 */
    private val askUserNotifier: io.prism.phonecontrol.PhoneControlAskUserNotifier? = null,
    /**
     * 应用上下文（后台副屏创建/跨用户启动需要）。生产由 PrismApplication 注入
     * applicationContext；null 时后台执行返回可读错误（保持既有 JVM 测试兼容）。
     */
    private val appContextProvider: () -> android.content.Context? = { null },
    /**
     * 协议端通道（ADR-064；默认主通道）。返回 null 或未连接时，本执行器回落到 UI 链路
     * （无障碍 + 后台副屏）。连接判定在每次发送前实时进行，使协议端掉线可即时降级。
     */
    private val oneBotChannelProvider: suspend () -> io.prism.qq.channel.QqChannel? = { null },
    /**
     * 语音合成器（PRD US-3113）。协议端可用时把文本合成 Prism 音色的 PCM 并以语音发送；
     * 不可用或合成失败时如实提示并降级为文本。
     */
    private val voiceSynthesizerProvider: suspend () -> QqVoiceSource? = { null },
    /** XSpace 用户 id（小号所在用户；MIUI 应用双开固定 999，M0 实证）。 */
    private val xSpaceUserId: Int = 999
) : LocalToolExecutor {

    /**
     * 定时主动消息专用发送入口（US-3108；[QqProactiveGateway.sender] 装配调用）。
     *
     * 与 LLM 交互路径的区别：**不走逐次高风险确认**——定时任务的创建本身即用户显式授权
     * （PRD US-3101 风险确认 + US-3108 开关），无人值守场景 ask_user 会永久挂起；
     * 仍受配置闸门与节流/熔断约束（不豁免）。
     */
    suspend fun sendProactive(contact: String, message: String): String =
        sendMessage(mapOf("contact" to contact, "message" to message), confirmRequired = false)

    /**
     * 无逐次确认的签名修改（ADR-064）：供 [io.prism.qq.channel.AccessibilityQqChannel]
     * 在交互路径已完成三态确认后调用；闸门/节流仍在内部生效。
     */
    suspend fun setSignatureUnconfirmed(signature: String): String =
        setSignature(mapOf("signature" to signature), confirmRequired = false)

    /**
     * 返回**已连接的协议端通道**；null 表示应回落 UI 链路。
     *
     * 只在 `transport=ONEBOT` 且连接健康时命中；任何异常按不可用处理（不阻断发送）。
     */
    private suspend fun protocolChannelOrNull(): io.prism.qq.channel.QqChannel? {
        val channel = runCatching { oneBotChannelProvider() }.getOrNull() ?: return null
        if (channel.transport != io.prism.qq.channel.QqTransport.ONEBOT) return null
        return if (runCatching { channel.status().connected }.getOrDefault(false)) channel else null
    }

    override fun handles(toolName: String): Boolean = toolName.startsWith(NAMESPACE)

    override suspend fun execute(toolName: String, arguments: Map<String, Any?>): String {
        PhoneControlSessionManager.onPhoneToolInvoked()
        val tool = toolName.removePrefix(NAMESPACE)
        return try {
            when (tool) {
                "send_message" -> sendMessage(arguments, confirmRequired = true)
                "send_voice" -> sendVoice(arguments, confirmRequired = true)
                "set_signature" -> setSignature(arguments, confirmRequired = true)
                "schedule_message" -> scheduleMessage(arguments)
                else -> "错误：未知 QQ 工具 $tool"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "execute($tool) 失败：${e::class.simpleName}")
            "错误：QQ 工具执行失败（${e::class.simpleName}）"
        }
    }

    // ==================== qq__send_message ====================

    private suspend fun sendMessage(arguments: Map<String, Any?>, confirmRequired: Boolean): String {
        val contact = parseString(arguments, "contact")?.trim().orEmpty()
        val message = parseString(arguments, "message")?.trim().orEmpty()
        if (contact.isEmpty()) return "错误：缺少 contact 参数（好友备注名）"
        if (message.isEmpty()) return "错误：缺少 message 参数"
        // guardrail B-01（2026-10-19）：凭据硬拦截**必须先于一切确认/发送路径**——对齐
        // PhoneControlSecurity 契约（验证码/密码/卡号"永不执行，即使人工确认也不放行"）。
        // 定时路径（sendProactive）同样经过本入口，无绕过面。
        if (io.prism.phonecontrol.PhoneControlSecurity.isCredentialInput(message)) {
            return "⚠️ 消息内容含凭据（密码/验证码/卡号），已硬拦截（不可执行，即便人工确认也不放行）"
        }
        // guardrail M-01（US-3102 AC-4）：消息长度上限——超长拒绝并提示分条（不做静默截断，
        // 静默截断会造成"发出的话不完整"，拟人场景下比拒绝更伤真实感）
        if (message.length > MESSAGE_MAX_LEN) {
            return "错误：消息过长（${message.length} 字，上限 $MESSAGE_MAX_LEN）。请拆分成多条发送（像真人那样分几条说更自然）"
        }

        val gate = configAndThrottleGate()
        if (gate != null) return gate

        if (confirmRequired) {
            val confirm = highRiskGate("向「${contact.take(20)}」发送消息", message.take(40))
            if (confirm != null) return confirm
        }

        // 协议端主通道（ADR-064 / US-3110 AC-2/AC-4）：连接健康时直接经 OneBot 发送，
        // 不创建副屏、不启动 QQ UI；未连接则继续走下方无障碍后台链路（自动降级）。
        protocolChannelOrNull()?.let { channel ->
            return channel.sendText(contact, message)
        }

        // 后台副屏执行（US-3102 AC-2：发送在后台完成，不占用用户前台）。不占用前台的
        // 关键：小号在 XSpace 用户 999，App 自身无法把它启动到副屏——须 Shizuku（见 withQqBackground）。
        return withQqBackground { displayId, svc ->
            // 归位到「消息」列表页（后台副屏内；QQ 可能停在抽屉/会话页/任意页）。
            ensureMessageList(svc, displayId)

            // 1) 进入会话：联系人节点定位（desc/text 模糊匹配），未命中回退 back 重试
            if (!openConversation(svc, contact)) {
                return@withQqBackground "错误：未能在 QQ 消息列表找到「${contact.take(20)}」。" +
                    "请确认该联系人存在（小号的好友列表），或核实小号 QQ 登录态后重试"
            }

            // 2) 输入：viewId=id/input（M0 实证聊天页专属 id；DEF-104 后不再用 findEditableNid
            // 兜底——列表页搜索框同样是 EditText，误命中会把消息打进搜索框）
            val inputNid = svc.findNodeByViewIdNid(VIEW_ID_INPUT)
            if (inputNid == null) {
                return@withQqBackground "错误：当前不在 QQ 聊天页（未找到输入框）。请确认小号 QQ 已登录且能进入与目标的会话后重试"
            }
            val typed = svc.performType(inputNid, 0, 0, message)
            if (typed.startsWith("错误")) return@withQqBackground "错误：消息输入失败（$typed）"

            // 3) 发送：viewId=id/send_btn 优先，text=发送 兜底
            delay(SETTLE_SHORT_MS)
            val sendNid = svc.findNodeByViewIdNid(VIEW_ID_SEND_BTN) ?: svc.findNodeByTextNid("发送")
            if (sendNid == null) {
                return@withQqBackground "错误：消息已输入但未找到「发送」按钮（QQ 界面可能变化）。" +
                    "消息仍在输入框中，可稍后重试"
            }
            val tapped = svc.performTapNodeBoundsShell(sendNid)
            if (tapped.startsWith("错误") || tapped.startsWith("⚠️")) {
                return@withQqBackground "错误：点击发送失败（$tapped）"
            }

            // 4) 校验：聊天页**最后一条气泡**应为「自己发出」且含消息片段。
            // DEF-116/117（2026-10-19 真机 E2E）：树文本受节点预算截断（新气泡在列表末端常超出）
            // 致单次检查假阴性（真机实证：消息已成功送达对端，日志却报"未能确认"）——改用
            // [PhoneControlAccessibilityService.lastMessageBubble]（按 bounds 取最后一条，不受预算影响）轮询确认。
            val fragment = QqLocalToolExecutor.firstCodePoints(message, VERIFY_FRAGMENT_LEN)
            var confirmed = false
            var tries = 0
            while (tries < 6 && !confirmed) {
                delay(SETTLE_SHORT_MS)
                val bubble = svc.lastMessageBubble(VIEW_ID_MSG_TEXT)
                confirmed = bubble != null && !bubble.first && bubble.second.contains(fragment)
                tries++
            }
            if (confirmed) {
                "已发送消息给「${contact.take(20)}」（已在校验中确认出现在聊天记录）"
            } else {
                "⚠️ 已点击发送，但未能从聊天记录确认消息已出现（可能因屏幕刷新延迟）。" +
                    "不要盲目重发（重复消息会显得不自然）"
            }
        }
    }

    // ==================== qq__send_voice ====================

    /**
     * 以 Prism 音色包合成语音并通过协议端发送（PRD US-3113）。
     *
     * **仅协议端可用**（AC-1）：无障碍通道无法注入预生成音频；此时如实返回「不支持」并建议文本。
     * **失败降级**（AC-4）：音色包未配置/合成失败时改发文本并在结果中明示，不静默丢失。
     * **节流/熔断**（AC-5）：与文本发送共用 [configAndThrottleGate]。
     */
    private suspend fun sendVoice(arguments: Map<String, Any?>, confirmRequired: Boolean): String {
        val contact = parseString(arguments, "contact")?.trim().orEmpty()
        val text = (parseString(arguments, "message") ?: parseString(arguments, "text"))
            ?.trim().orEmpty()
        if (contact.isEmpty()) return "错误：缺少 contact 参数（好友备注名）"
        if (text.isEmpty()) return "错误：缺少 message 参数（要朗读的文本）"
        if (io.prism.phonecontrol.PhoneControlSecurity.isCredentialInput(text)) {
            return "⚠️ 语音内容含凭据（密码/验证码/卡号），已硬拦截（不可执行，即便人工确认也不放行）"
        }
        if (text.length > VOICE_TEXT_MAX_LEN) {
            return "错误：语音文本过长（${text.length} 字，上限 $VOICE_TEXT_MAX_LEN）。" +
                "语音适合短句，请精简或改用 qq__send_message 发送文本"
        }

        val gate = configAndThrottleGate()
        if (gate != null) return gate

        if (confirmRequired) {
            val confirm = highRiskGate("向「${contact.take(20)}」发送语音", text.take(40))
            if (confirm != null) return confirm
        }

        val channel = protocolChannelOrNull()
            ?: return "错误：语音消息仅支持协议端通道（当前无可用的 OneBot 连接）。" +
                "无障碍通道无法注入音频——请改用 qq__send_message 发送文本"

        val audio = try {
            voiceSynthesizerProvider()?.synthesize(text)
        } catch (e: CancellationException) {
            throw e // guardrail M-05：中断必须透传
        } catch (e: Exception) {
            Log.w(TAG, "语音合成异常：${e::class.simpleName}")
            null
        }
        if (audio == null || audio.samples.isEmpty()) {
            // AC-4：合成失败降级文本并如实提示（不静默丢失）
            return sendVoiceTextFallback(channel, contact, text, "语音合成失败（音色包未配置或不可用）")
        }
        val sent = channel.sendVoice(contact, audio.samples, audio.sampleRate)
        // guardrail C2：只有**协议端明确拒绝**（retcode≥0 的响应）才降级文本；
        // 传输异常/超时（retcode=-1）可能已送达，自动改发文本会造成重复，故不降级。
        if (sent.startsWith(VOICE_SEND_REJECTED_PREFIX)) {
            return sendVoiceTextFallback(channel, contact, text, "协议端拒绝语音")
        }
        if (sent.startsWith("已发送语音")) {
            // guardrail C3/M-02：语音成功发送同样计入当日总数与最小间隔（不占定时配额）
            recordVoiceSend()
        } else if (sent.startsWith(VOICE_SEND_UNCONFIRMED_PREFIX)) {
            // guardrail M-09/L-23：仅「未确认」记账（可能已送达）；解析失败/编码失败等
            // **确定未发送**的错误不得消耗配额
            recordVoiceSend()
        }
        return sent
    }

    /** 语音失败后的文本降级（同一联系人/同一通道；失败时如实拼接原因）。 */
    private suspend fun sendVoiceTextFallback(
        channel: io.prism.qq.channel.QqChannel,
        contact: String,
        text: String,
        reason: String
    ): String {
        val fallback = channel.sendText(contact, text)
        return if (fallback.startsWith("错误") || fallback.startsWith("⚠️")) {
            "⚠️ $reason，文本降级亦未成功：$fallback"
        } else {
            // guardrail M-09：降级文本同样是实发消息，必须记账（防音色包缺失时无限循环）
            recordVoiceSend()
            "⚠️ $reason，已改为发送文本（$fallback）"
        }
    }

    /** 记录一次语音/降级文本发送（当日总数 + 最小间隔基准；不占定时配额）。 */
    private suspend fun recordVoiceSend() {
        runCatching {
            val now = System.currentTimeMillis()
            runtimeRepoProvider()?.recordReplied(
                now,
                QqThrottle.todayKey(now, java.time.ZoneId.systemDefault())
            )
        }
    }

    // ==================== qq__set_signature ====================

    private suspend fun setSignature(arguments: Map<String, Any?>, confirmRequired: Boolean): String {
        val signature = parseString(arguments, "signature")?.trim().orEmpty()
        if (signature.isEmpty()) return "错误：缺少 signature 参数"
        // guardrail B-01：签名是**公开可见**字段，凭据泄漏后果更重——硬拦截同消息路径
        if (io.prism.phonecontrol.PhoneControlSecurity.isCredentialInput(signature)) {
            return "⚠️ 签名内容含凭据（密码/验证码/卡号），已硬拦截（不可执行，即便人工确认也不放行）"
        }
        if (signature.length > SIGNATURE_MAX_LEN) {
            return "错误：签名过长（${signature.length} 字，上限 $SIGNATURE_MAX_LEN）"
        }

        val gate = configAndThrottleGate()
        if (gate != null) return gate

        if (confirmRequired) {
            val confirm = highRiskGate("修改小号个性签名", signature.take(40))
            if (confirm != null) return confirm
        }

        // 协议端主通道（ADR-064）：连接健康时经 OneBot 直接修改，不建屏。
        protocolChannelOrNull()?.let { channel ->
            return channel.setSignature(signature)
        }

        // 后台副屏执行（US-3102 AC-2 同理：资料修改同样不占用用户前台）。
        return withQqBackground { _, svc ->
            // 1) 回到 QQ 主界面（至多 2 次 back；后台走 shell keyevent 注入）
            backToRoot(svc, times = 2)

            // 2) 左上头像入口 → 抽屉（M0：desc='账户及设置'）
            val drawerNid = svc.findNodeByTextNid("账户及设置")
            if (drawerNid == null || svc.performTapNodeBoundsShell(drawerNid).startsWith("错误")) {
                return@withQqBackground "错误：未找到 QQ 左上角头像入口（当前不在 QQ 主界面？）。" +
                    "请核实小号 QQ 登录态后重试"
            }
            delay(SETTLE_MEDIUM_MS)

            // 3) 抽屉页头像 → 个人资料页（M0：id=pr9，desc='头像'）
            val avatarNid = svc.findNodeByViewIdNid(VIEW_ID_AVATAR) ?: svc.findNodeByTextNid("头像")
            if (avatarNid == null || svc.performTapNodeBoundsShell(avatarNid).startsWith("错误")) {
                return@withQqBackground "错误：已打开侧边栏但未找到头像入口（QQ 界面可能变化）"
            }
            delay(SETTLE_MEDIUM_MS)

            // 4) 资料页「编辑个签」（M0 实证文本："编辑个签，展示我的独特态度。"）
            val sigNid = svc.findNodeByTextNid("编辑个签")
            if (sigNid == null || svc.performTapNodeBoundsShell(sigNid).startsWith("错误")) {
                return@withQqBackground "错误：已进入资料页但未找到「编辑个签」入口。请核实小号 QQ 状态后重试"
            }
            delay(SETTLE_MEDIUM_MS)

            // 5) 编辑框输入（ACTION_SET_TEXT 替换全文）→ 保存
            val editNid = svc.findEditableNid()
            if (editNid == null) return@withQqBackground "错误：个签编辑页未找到输入框"
            val typed = svc.performType(editNid, 0, 0, signature)
            if (typed.startsWith("错误")) return@withQqBackground "错误：签名输入失败（$typed）"
            delay(SETTLE_SHORT_MS)
            val saveNid = svc.findNodeByTextNid("保存") ?: svc.findNodeByTextNid("完成")
            if (saveNid == null || svc.performTapNodeBoundsShell(saveNid).startsWith("错误")) {
                return@withQqBackground "错误：签名已输入但未找到「保存/完成」按钮。签名可能未生效，请稍后重试"
            }
            delay(SETTLE_SEND_MS)

            // DEF-116 同理：轮询确认（副屏渲染延迟易致单次检查假阴性）
            val fragment = QqLocalToolExecutor.firstCodePoints(signature, VERIFY_FRAGMENT_LEN)
            var confirmed = false
            var tries = 0
            while (tries < 6 && !confirmed) {
                delay(SETTLE_SHORT_MS)
                val tree = svc.getUiTreeText(maxNodes = 160)
                confirmed = tree != null && tree.contains(fragment)
                tries++
            }
            if (confirmed) {
                "已修改小号个性签名（已在校验中确认生效）"
            } else {
                "⚠️ 已点击保存，但未能确认签名已生效。请稍后核实"
            }
        }
    }

    // ==================== qq__schedule_message ====================

    private suspend fun scheduleMessage(arguments: Map<String, Any?>): String {
        // fail-fast：action 白名单先于环境检查（非法参数与仓库状态无关）
        val action = parseString(arguments, "action")?.trim()?.lowercase() ?: "status"
        if (action !in setOf("status", "create", "cancel")) {
            return "错误：action 仅支持 status/create/cancel"
        }
        val cfgRepo = configRepoProvider() ?: return "错误：QQ 配置仓库未装配"
        return when (action) {
            "status" -> {
                val cfg = cfgRepo.get()
                val rt = runtimeRepoProvider()?.get()
                buildString {
                    append("定时主动消息状态：总开关=${cfg.enabled}，风险确认=${cfg.riskAccepted}，")
                    append("定时开关=${cfg.scheduleEnabled}，目标联系人=${cfg.targetContact.ifBlank { "未配置" }}，")
                    append("时间窗=${cfg.windowStartMinute / 60}:${"%02d".format(cfg.windowStartMinute % 60)}-")
                    append("${cfg.windowEndMinute / 60}:${"%02d".format(cfg.windowEndMinute % 60)}，")
                    append("每日上限=${cfg.maxPerDay}，策略=${if (cfg.usePoisson) "泊松" else "均匀"}")
                    if (rt != null && rt.plannedTriggerAt > 0) {
                        append("；下次计划=${java.time.Instant.ofEpochMilli(rt.plannedTriggerAt)}")
                    }
                    if (rt?.circuitOpen == true) append("；【已熔断，需在设置中恢复】")
                }
            }
            "create" -> {
                val current = cfgRepo.get()
                if (!current.enabled || !current.riskAccepted) {
                    return "错误：QQ 小号助手未开启或未完成风险确认（设置 → QQ 小号助手）。" +
                        "定时主动消息依赖该前置"
                }
                // guardrail H-01（2026-10-19）：create 是"开启无人值守自动发送"的高危授权——
                // 必须与交互路径同受三态策略（BLOCK 全拦截 / ALLOW 放行 / ASK 询问），
                // 否则 BLOCK 模式下 LLM 仍可绕过用户授权开启定时发送
                val confirm = highRiskGate(
                    "创建定时主动消息",
                    "将在设定时段无人值守地自动向「${parseString(arguments, "target_contact") ?: current.targetContact}」发消息"
                )
                if (confirm != null) return confirm
                val start = parseInt(arguments, "window_start_min") ?: QqConfig.DEFAULT_WINDOW_START_MINUTE
                val end = parseInt(arguments, "window_end_min") ?: QqConfig.DEFAULT_WINDOW_END_MINUTE
                if (start < 0 || end > QqConfig.MINUTES_PER_DAY || start >= end) {
                    return "错误：时间窗非法（0 <= start < end <= ${QqConfig.MINUTES_PER_DAY}，当日分钟）"
                }
                val contact = parseString(arguments, "target_contact")?.trim().orEmpty()
                if (contact.isEmpty() && current.targetContact.isBlank()) {
                    return "错误：缺少 target_contact（小号对你的备注名），无法确定发送目标"
                }
                if (contact.isNotEmpty()) cfgRepo.setTargetContact(contact)
                cfgRepo.setWindow(start, end)
                parseInt(arguments, "max_per_day")?.let { cfgRepo.setMaxPerDay(it) }
                cfgRepo.setUsePoisson(parseString(arguments, "use_poisson")?.lowercase() == "true")
                cfgRepo.setScheduleEnabled(true)
                val cfg = cfgRepo.get()
                "已创建定时主动消息：时间窗 ${cfg.windowStartMinute / 60}:${"%02d".format(cfg.windowStartMinute % 60)}-" +
                    "${cfg.windowEndMinute / 60}:${"%02d".format(cfg.windowEndMinute % 60)}，" +
                    "每日至多 ${cfg.maxPerDay} 次，发给「${cfg.targetContact}」。" +
                    "Prism 将在该时段内随机时刻主动发消息（受节流与熔断约束）"
            }
            "cancel" -> {
                cfgRepo.setScheduleEnabled(false)
                "已取消定时主动消息（调度服务将自动停止）"
            }
            else -> "错误：action 仅支持 status/create/cancel"
        }
    }

    // ==================== 闸门与辅助 ====================

    /** 配置闸门（US-3101）+ 节流闸门（US-3105）。null=通过；否则返回可读拒绝文案。 */
    private suspend fun configAndThrottleGate(): String? {
        val repo = configRepoProvider() ?: return "错误：QQ 小号助手配置未装配"
        val cfg = repo.get()
        if (!cfg.enabled) {
            return "QQ 小号助手当前未开启（设置 → QQ 小号助手）。请先开启并完成风险确认后再使用"
        }
        val runtimeRepo = runtimeRepoProvider()
        if (runtimeRepo != null) {
            val now = System.currentTimeMillis()
            when (val d = QqThrottle.decide(cfg, runtimeRepo.get(), now)) {
                is QqThrottle.Decision.Deny -> return "错误：${d.reason}（节流保护，稍后再试；" +
                    "频繁操作易触发平台风控）"
                QqThrottle.Decision.Allow -> Unit
            }
        }
        return null
    }

    /**
     * 高风险三态确认（交互路径）。返回 null=放行；否则返回 ask_user 标记或拦截文案。
     */
    private suspend fun highRiskGate(action: String, detail: String): String? {
        return when (highRiskApprovalProvider()) {
            io.prism.config.HighRiskApprovalMode.BLOCK ->
                "错误：$action 已被「高危操作拦截」设置阻止（设置 → 手机操控 → 高危操作拦截）"
            io.prism.config.HighRiskApprovalMode.ALLOW -> null
            io.prism.config.HighRiskApprovalMode.ASK -> {
                askUserNotifier?.let { n ->
                    runCatching { n.request("QQ 小号助手：$action「$detail」，是否允许？") }
                }
                // guardrail M-02：主号/小号实例同为 com.tencent.mobileqq，a11y 层不可区分——
                // 确认文案必须显式要求用户核对"当前前台是小号"，作为实例混淆的最后防线
                AskUserLocalToolExecutor.RESULT_MARKER + askUserJson(
                    "QQ 小号助手：$action「$detail」。请确认当前手机前台是【QQ 双开（小号）】而非主号，是否允许？",
                    listOf("允许（已确认是小号）" to "确认执行", "取消" to "阻止本次操作")
                )
            }
        }
    }

    /**
     * 确保**后台副屏执行面**就绪，随后在 [block] 内执行（finally 还原前台执行面）。
     *
     * 步骤（PRD US-3102 AC-2「发送在后台完成，不占用用户前台」）：
     * 1. 前置守卫：无障碍实例 → 应用上下文 → API 30+ → 悬浮窗权限 → Shizuku 就绪；
     * 2. [VirtualDisplayManager.ensureDisplay] 幂等创建**可信副屏**（Shizuku `overlay_display_devices`）；
     * 3. [VirtualDisplayManager.launchAppForUser] 经 `am start --user 999 --display N` 把小号 QQ
     *    拉到副屏顶层（跨用户 + 跨屏；force-stop 确保迁屏，杜绝落到主屏占用前台）；
     * 4. `setExecutionDisplay(displayId)` 把节点感知/操作路由到副屏，[block] 执行，finally 还原。
     *
     * 任一步失败均返回可读错误（不占用用户前台、不静默失败）。副屏生命周期由
     * [PhoneControlSessionManager] 空闲释放兜底（本方法首行刷新保活会话）。
     */
    private suspend fun prepareQqBackground(): Pair<Int, String?> {
        PhoneControlSessionManager.onPhoneToolInvoked()
        PhoneControlAccessibilityService.instance ?: return INVALID_DISPLAY to serviceUnavailable()
        val ctx = appContextProvider() ?: return INVALID_DISPLAY to "错误：后台执行上下文未初始化（请重启应用后重试）"
        if (!VirtualDisplayManager.isSupported()) {
            return INVALID_DISPLAY to "错误：QQ 小号后台执行需要 Android 11（API 30）及以上系统（跨屏无障碍能力），本机不支持"
        }
        if (!VirtualDisplayManager.canLaunchBackground(ctx)) {
            return INVALID_DISPLAY to "错误：后台执行需要「显示在其他应用上层」（悬浮窗）权限——系统对后台启动 Activity 的官方豁免条件。" +
                "请在系统设置中授权 Prism 该权限后重试"
        }
        if (!ShizukuShell.isReady()) {
            return INVALID_DISPLAY to "错误：QQ 小号后台执行需要 Shizuku（已安装并激活）——后台手势注入与跨用户副屏启动均依赖它。" +
                "请在 Shizuku 中授权 Prism 后重试"
        }
        val displayId = VirtualDisplayManager.ensureDisplay(ctx)
        if (displayId == INVALID_DISPLAY) {
            return INVALID_DISPLAY to "错误：后台虚拟屏创建失败（系统可能限制虚拟显示器）。请稍后重试"
        }
        // 小号已在副屏顶层**且确实由本进程以小号身份启动过**则复用（免 force-stop 重启——
        // 双向对话轮询节奏下尤其重要）。guardrail M-03：仅比对包名不够——主/小号同为
        // com.tencent.mobileqq，若副屏顶层恰为主号实例会误判（故要求本进程小号启动记录匹配本 displayId）。
        val topPkg = runCatching {
            PhoneControlAccessibilityService.instance?.backgroundWindowPackage(displayId)
        }.getOrNull()
        val alreadyOnDisplay = topPkg == QQ_PACKAGE && lastLaunchedDisplayId == displayId
        if (!alreadyOnDisplay) {
            val launched = VirtualDisplayManager.launchAppForUser(ctx, QQ_PACKAGE, xSpaceUserId)
            if (launched.startsWith("错误")) return INVALID_DISPLAY to launched
            lastLaunchedDisplayId = displayId
            // DEF-110（2026-10-19 真机 E2E）：`launchAppForUser` 只等到窗口渲染（可能仍在启动页），
            // 紧随其后的节点查询常命中"未就绪"——补一段启动 settle，等小号进入主界面（消息列表）。
            delay(SETTLE_LAUNCH_MS)
        }
        return displayId to null
    }

    /** 本进程最近一次以小号身份把 QQ 启动到的副屏 displayId（M-03 复用判定用；线程可见）。 */
    @Volatile
    private var lastLaunchedDisplayId: Int = android.view.Display.INVALID_DISPLAY

    private suspend fun withQqBackground(
        block: suspend (displayId: Int, svc: PhoneControlAccessibilityService) -> String
    ): String = VirtualDisplayManager.executionFaceMutex.withLock {
        val (displayId, err) = prepareQqBackground()
        if (err != null) return@withLock err
        val svc = PhoneControlAccessibilityService.instance ?: return@withLock serviceUnavailable()
        svc.setExecutionDisplay(displayId)
        try {
            block(displayId, svc)
        } finally {
            svc.setExecutionDisplay(null)
        }
    }

    /**
     * 双向对话（US-3109 新增）：读取与 [contact] 会话中**最后一条消息**的方向与文本（后台副屏）。
     *
     * 供 [QqProactiveGateway.autoReply] 链路判定"是否有新收到的消息需要回应"。全程后台
     * （不占用用户前台），失败/无会话返回 null（调用方按"无可回应"处理，绝不误判）。
     *
     * @return [LastMessage]；读取失败或无消息返回 null
     */
    suspend fun lastMessageWith(contact: String): LastMessage? =
        VirtualDisplayManager.executionFaceMutex.withLock {
            val (displayId, err) = prepareQqBackground()
            if (err != null) {
                Log.w(TAG, "lastMessageWith: 后台准备失败（${err.take(60)}）")
                return@withLock null
            }
            val svc = PhoneControlAccessibilityService.instance ?: return@withLock null
            svc.setExecutionDisplay(displayId)
            try {
                // DEF-110（2026-10-19 真机 E2E）：与 sendMessage 一致先归位到「消息」列表页——
                // 否则小号停在会话页/抽屉/启动页时 openConversation 必失败（实测恒"未读取到会话消息"）。
                ensureMessageList(svc, displayId)
                if (!openConversation(svc, contact)) {
                    Log.i(TAG, "lastMessageWith: 未进入「${maskName(contact)}」会话（列表未就绪或联系人不存在）")
                    null
                } else {
                    val bubble = svc.lastMessageBubble(VIEW_ID_MSG_TEXT)
                    if (bubble == null) Log.i(TAG, "lastMessageWith: 会话页无可读消息气泡（id=$VIEW_ID_MSG_TEXT）")
                    bubble?.let { LastMessage(it.first, it.second) }
                }
            } finally {
                svc.setExecutionDisplay(null)
            }
        }

    /**
     * 归位到 QQ「消息」列表页（后台副屏内）。
     *
     * DEF-106/107（2026-10-19 M2 真机实测；2026-10-19 改造为后台版）：QQ 可能停在抽屉/会话页/
     * 任意页。判定"是否在会话页"以**聊天输入框 `id/input` 是否存在**为准（真机 DEF-115 取证：
     * 「消息」tab 文本定位在会话页会命中无关节点，不可作为判据）：在会话页则 back 回列表
     * （至多 2 次），再校验副屏顶层包名仍是 QQ——否则经 Shizuku 重新拉起小号，杜绝空转。
     */
    private suspend fun ensureMessageList(svc: PhoneControlAccessibilityService, displayId: Int) {
        // DEF-115（2026-10-19 真机 E2E）：原实现用「消息」tab 文本定位后点击——真机取证该定位
        // 在**会话页**会命中无关节点（返回非 null）导致"已在会话页却不按返回"，后续会话项定位
        // 落到会话页的错误节点（中心 175,138 落空）。改为**按聊天页特征（id/input 存在）判定**：
        // 在会话页则 back 回列表（至多 2 次）；再校正副屏顶层包名（必要时重新拉起小号）。
        var backs = 0
        while (backs < 2 && svc.findNodeByViewIdNid(VIEW_ID_INPUT) != null) {
            svc.executeGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            delay(SETTLE_MEDIUM_MS)
            backs++
        }
        val topPkg = runCatching { svc.backgroundWindowPackage(displayId) }.getOrNull()
        if (topPkg != QQ_PACKAGE) {
            ShizukuShell.amStartForUserOnDisplay(
                QQ_MAIN_ACTIVITY, xSpaceUserId, displayId, forceStopFirst = true
            )
            delay(SETTLE_LAUNCH_MS)
        }
    }

    /** 在消息列表中定位并进入联系人会话；未命中 back 一次重试。返回是否进入聊天页。 */
    private suspend fun openConversation(svc: PhoneControlAccessibilityService, contact: String): Boolean {
        repeat(2) { attempt ->
            // DEF-114：会话项以 contentDescription 前缀精确定位（"<备注名>, ,…"），
            // 聚合匹配会命中大容器导致点击落空；前缀法不可用时回退到原有文本匹配。
            val nid = svc.findNodeByOwnTextNid(contact, descPrefix = "$contact,")
                ?: svc.findNodeByTextNid(contact)
            Log.i(TAG, "openConversation: attempt=$attempt contact=${maskName(contact)} nid=$nid")
            if (nid != null) {
                val tap = svc.performTapNodeBoundsShell(nid)
                Log.i(TAG, "openConversation: tap=$tap")
                if (!tap.startsWith("错误")) {
                    // DEF-111（2026-10-19 真机 E2E）：副屏上冷启动的 QQ 会话页渲染慢于固定 1s——
                    // 原单次 `delay(1s)` 后即查 id/input 会"已进入却判未进入"（实测恒失败）。
                    // 改为轮询等待聊天页专属 id/input 出现（500ms ×6 ≈ 3s）。
                    repeat(6) {
                        delay(SETTLE_SHORT_MS)
                        val inNid = svc.findNodeByViewIdNid(VIEW_ID_INPUT)
                        Log.i(TAG, "openConversation: poll input nid=$inNid")
                        if (inNid != null) return true
                    }
                }
            }
            if (attempt == 0) {
                svc.executeGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                delay(SETTLE_SHORT_MS)
            }
        }
        // guardrail L-02：不落屏内文本，只记长度用于诊断
        val treeLen = runCatching { svc.getUiTreeText(60)?.length }.getOrDefault(0)
        Log.i(TAG, "openConversation 失败；副屏树长度=$treeLen")
        return false
    }

    private suspend fun backToRoot(svc: PhoneControlAccessibilityService, times: Int) {
        repeat(times) {
            svc.executeGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            delay(SETTLE_SHORT_MS)
        }
    }

    private fun serviceUnavailable(): String =
        "错误：手机操控无障碍服务未开启（QQ 小号助手依赖它读取并操作 QQ 界面）。" +
            "请在系统「设置 → 无障碍」中开启 Prism"

    /** ask_user 载荷（与 PhoneControlLocalToolExecutor.askUserJson 协议一致）。 */
    internal fun askUserJson(question: String, options: List<Pair<String, String?>>): String {
        val optionArray = JsonArray(options.map { (label, desc) ->
            buildJsonObject {
                put("label", label)
                desc?.let { put("description", it) }
            }
        })
        val questions = JsonArray(listOf(buildJsonObject {
            put("question", question.take(200))
            put("options", optionArray)
            put("multiSelect", false)
        }))
        return JsonObject(mapOf("questions" to questions)).toString()
    }

    /** 会话中的最后一条消息（双向对话判定用；incoming=对方发来）。 */
    data class LastMessage(val incoming: Boolean, val text: String)

    companion object {
        private const val TAG = "QqTool"

        /** 工具命名空间前缀。 */
        const val NAMESPACE = "qq__"

        const val QQ_PACKAGE = "com.tencent.mobileqq"
        const val QQ_MAIN_ACTIVITY = "com.tencent.mobileqq/.activity.SplashActivity"

        /** M0 实证的稳定 resource-id 尾段。 */
        const val VIEW_ID_INPUT = "input"
        const val VIEW_ID_SEND_BTN = "send_btn"
        const val VIEW_ID_AVATAR = "pr9"

        /** 消息气泡文本节点 resource-id 尾段（2026-10-19 真机结构实证；双向对话判定用）。 */
        const val VIEW_ID_MSG_TEXT = "mjn"

        /** [VirtualDisplayManager.ensureDisplay] 失败哨兵（Display.INVALID_DISPLAY 非 const）。 */
        private val INVALID_DISPLAY = android.view.Display.INVALID_DISPLAY

        const val SIGNATURE_MAX_LEN = 120

        /** 消息长度上限（US-3102 AC-4；超长拒绝并提示分条）。 */
        const val MESSAGE_MAX_LEN = 500

        /** 语音文本上限（US-3113；语音适合短句，过长合成耗时与失败率上升）。 */
        const val VOICE_TEXT_MAX_LEN = 200

        /** 协议端**明确拒绝**语音的前缀（仅此情况可安全降级文本；传输异常/超时不得重发）。 */
        internal const val VOICE_SEND_REJECTED_PREFIX = "错误：协议端语音发送失败"

        /** 「可能已送达」的前缀（传输异常/超时）：不降级文本，但必须记账以限制重试频率。 */
        internal const val VOICE_SEND_UNCONFIRMED_PREFIX = "⚠️ 语音发送未确认"
        private const val VERIFY_FRAGMENT_LEN = 10

        /** 联系人在日志中脱敏（guardrail L-02）。 */
        internal fun maskName(name: String): String =
            if (name.length <= 1) "***" else "${name.first()}***"

        /** guardrail L-02：按 Unicode code point 截取前 n 个字符（避免代理对被拆导致匹配假阴性）。 */
        internal fun firstCodePoints(s: String, n: Int): String =
            StringBuilder().also { sb ->
                s.codePoints().limit(n.toLong()).forEach { sb.appendCodePoint(it) }
            }.toString()

        private const val SETTLE_SHORT_MS = 500L
        private const val SETTLE_MEDIUM_MS = 1_000L
        private const val SETTLE_SEND_MS = 1_500L
        private const val SETTLE_LAUNCH_MS = 3_000L

        internal fun parseString(arguments: Map<String, Any?>, key: String): String? {
            val v = arguments[key] ?: return null
            return when (v) {
                is String -> v
                else -> v.toString()
            }
        }

        internal fun parseInt(arguments: Map<String, Any?>, key: String): Int? = when (val v = arguments[key]) {
            null -> null
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }

        /**
         * 生成 `qq__*` 工具定义（恒注入——QQ 未开启时执行器返回可读说明，
         * 不让工具集中途变化破坏 provider 前缀缓存；与桌宠范式一致）。
         */
        fun buildToolDefinitions(): List<io.prism.network.ToolDefinition> = listOf(
            tool(
                "send_message",
                "以 QQ 小号身份给指定好友发送一条消息（用于像真人一样主动联系用户）。" +
                    "默认经 OneBot 协议端发送；协议端不可用时自动回落后台 UI 链路。" +
                    "contact 为小号对该好友的备注名；message 为消息正文。未开启 QQ 小号助手时会返回说明。",
                """{"type":"object","properties":{"contact":{"type":"string","description":"好友备注名（小号视角）"},"message":{"type":"string","description":"消息正文（口语化，像真人）"}},"required":["contact","message"]}"""
            ),
            tool(
                "send_voice",
                "以 QQ 小号身份给指定好友发送一条**语音**（用 Prism 音色包合成，声音与你的播报音色一致）。" +
                    "仅协议端（OneBot）可用；无障碍通道不支持语音，会建议改用 send_message。" +
                    "文本上限 200 字；语音合成失败时会自动改发文本并在结果中说明。" +
                    "contact 为小号对该好友的备注名；message 为要朗读的文本。",
                """{"type":"object","properties":{"contact":{"type":"string","description":"好友备注名（小号视角）"},"message":{"type":"string","description":"要朗读的文本（口语化短句，≤200 字）"}},"required":["contact","message"]}"""
            ),
            tool(
                "set_signature",
                "修改 QQ 小号的个性签名（按生活实际更新，如心情/近况）。长度上限 120 字。",
                """{"type":"object","properties":{"signature":{"type":"string","description":"新的个性签名"}},"required":["signature"]}"""
            ),
            tool(
                "schedule_message",
                "管理随机定时主动消息：status 查看状态；create 创建（在时间窗内随机时刻主动发消息）；cancel 取消。" +
                    "**无需提供消息内容**——到点触发时由你（角色）结合人设与当前时段即兴生成一条像真人的消息发出，" +
                    "这不是替用户传话，而是你主动想起对方时的问候/分享。",
                """{"type":"object","properties":{"action":{"type":"string","enum":["status","create","cancel"]},"window_start_min":{"type":"integer","description":"时间窗起（当日分钟，如 1200=20:00）"},"window_end_min":{"type":"integer","description":"时间窗止（当日分钟，不含，如 1380=23:00）"},"max_per_day":{"type":"integer","description":"每日次数上限（1-20）"},"use_poisson":{"type":"string","enum":["true","false"],"description":"true=泊松随机间隔（更突发），false=窗内均匀采样"},"target_contact":{"type":"string","description":"create 时：小号对目标好友的备注名"}},"required":["action"]}"""
            )
        )

        private fun tool(name: String, description: String, parametersJson: String): io.prism.network.ToolDefinition =
            io.prism.network.ToolDefinition(
                function = io.prism.network.ToolDefinition.FunctionDef(
                    name = "$NAMESPACE$name",
                    description = description,
                    parameters = kotlinx.serialization.json.Json.parseToJsonElement(parametersJson)
                )
            )
    }
}
