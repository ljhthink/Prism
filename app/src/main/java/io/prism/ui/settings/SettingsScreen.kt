package io.prism.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import io.prism.ui.components.PrismCardBox
import io.prism.ui.components.PrismChoiceSheet
import io.prism.ui.components.PrismKv
import io.prism.ui.components.PrismNoteLine
import io.prism.ui.components.PrismFootnote
import io.prism.ui.components.PrismActionsRow
import io.prism.ui.components.PrismMini
import io.prism.ui.components.PrismDangerRow
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismType
import io.prism.ui.components.PrismStatusStrip
import io.prism.ui.components.DotTone
import io.prism.ui.components.PrismDot
import io.prism.ui.components.PrismHairline
import androidx.compose.foundation.layout.heightIn
import io.prism.ui.theme.PrismLineStrong
import io.prism.ui.components.PrismRow
import io.prism.ui.components.PrismIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.prism.data.ProviderConfig
import io.prism.data.ProviderPresets
import io.prism.config.ToolApprovalMode
import io.prism.tier.PerformanceTier
import io.prism.tier.TierConfigRepository
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.prism.ui.theme.PrismOk
import io.prism.ui.theme.PrismWarning
import io.prism.ui.theme.PrismPanel
import io.prism.ui.components.PrismButton
import io.prism.ui.components.PrismButtonVariant
import io.prism.ui.components.PrismField
import io.prism.ui.components.PrismSheet
import io.prism.ui.components.PrismSheetHost
import io.prism.ui.components.PrismSwitch
import io.prism.ui.components.PrismTopBar
import io.prism.ui.theme.PrismAccent
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismLine
import io.prism.ui.theme.PrismMint
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import io.prism.ui.theme.PrismWarning
import java.util.Locale

/** v1 US-201：手机操控无障碍服务状态轮询间隔（毫秒）。 */
private const val PHONE_CONTROL_POLL_MS = 1_000L

/** QQ 小号助手分区轮询间隔（熔断态刷新；guardrail L-04：不复用手机操控常量）。 */
private const val QQ_POLL_MS = 2_000L

/**
 * 设置详情页内的二级子页（ADR-079 D2 承诺的 QQ 四子页 / 语音两子页）。
 *
 * 为什么不用 NavHost 路由：子页不涉及数据层，只需要「改标题 + 换内容 + 返回上一级」；
 * 放进导航图会让 Tab 切换的返回栈条目变多，且 `SettingsScreen` 的 47 项清单会被拆散在两处。
 * 返回链：子页 → 分区首页 → 设置首页（见 [SettingsScreen] 的 BackHandler）。
 */
private enum class SettingsSubPage(val title: String) {
    QQ_SCHEDULE("定时与主动消息"),
    QQ_REPLY("自动回复"),
    QQ_STICKERS("表情库"),
    /**
     * 她的日记（2026-11-22 新增；ADR-080）。
     *
     * 为什么必须有：ADR-077 D23 承诺「设置页可读可删、不可代写」，但实现只落了一个
     * 开关——日记写得进去、用户看不到（真机 `files/diaries/cyrene.json` 已有 12 条，
     * 界面上零入口）。这一页补的就是那个缺口，语义严格保持「只读 + 可删 + 可清空」。
     */
    QQ_DIARY("她的日记"),
    VOICE_PERSONA("角色与桌宠")
}

/**
 * 设置屏幕 —— 深空玻璃肌理（设计规范 v0.4 第 8.4 节）。
 *
 * 分组：模型与端点 / 设备档位 / 手机操控（LLM 操控手机）/ 识图 / 搜索增强 / 关于。
 * Provider 配置与 API Key 两行接入 [SettingsViewModel] 数据层：
 * - Provider 配置 → 已配置列表弹层 + 详情编辑弹层（Base URL / 模型 / 激活 / 删除 / 从预设添加）
 * - API Key → 各 Provider 的 Key 加密读写（掩码 + Keystore 加密）
 */
@Composable
fun SettingsScreen(
    section: SettingsSection? = null,
    onOpenSection: (SettingsSection) -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    // 首页：只有 6 个任务入口 + 运行状态。详情见下方按 [section] 过滤的同一份内容，
    // 47 个设置项一个不删（ADR-079 D2/D6）。
    if (section == null) {
        SettingsHome(onOpenSection = onOpenSection, viewModel = viewModel)
        return
    }
    // 二级子页状态（切分区自动复位：`remember(section)`，避免从 QQ 表情库跳到系统页仍显示旧标题）
    var subPage by remember(section) { mutableStateOf<SettingsSubPage?>(null) }
    val backStep = {
        if (subPage != null) subPage = null else onBack()
    }
    // M-05（guardrail）：详情页先拦系统返回键回到设置首页，而不是直接弹出 SETTINGS 回聊天页。
    androidx.activity.compose.BackHandler { backStep() }


    // v1 批次16（US-1603）：「生物识别解锁」M0 起 UI 占位从未实现，已整行移除（连同本地 state）。
    var tierSheetVisible by remember { mutableStateOf(false) }
    var providerListVisible by remember { mutableStateOf(false) }

    // guardrail R4-M-03：待删除端点（非 null 时弹二次确认）
    var deleteProviderTarget by remember { mutableStateOf<io.prism.data.ProviderConfig?>(null) }

    // 设计稿「开源许可」行：点击查看许可与第三方组件清单
    var licenseSheetVisible by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var thinkingSheetVisible by remember { mutableStateOf(false) }
    var toolApprovalSheetVisible by remember { mutableStateOf(false) }
    // UXR8 N1（ADR-030）：用户规则编辑弹层（「关于我」+「如何回答」双字段）
    var userRulesSheetVisible by remember { mutableStateOf(false) }
    // v1 批次15（US-1501/1502/1507）：搜索增强配置弹层（智谱/Tavily Key + SearXNG 端点）
    var searchEnhancementSheetVisible by remember { mutableStateOf(false) }

    val providers by viewModel.providers.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    // 问题 8a（ADR-020）：深度思考开关 + 思考强度（DataStore 持久化）
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val reasoningEffort by viewModel.reasoningEffort.collectAsState()
    // UXR3 问题 10（ADR-023）：工具审批模式（DataStore 持久化）
    val toolApprovalMode by viewModel.toolApprovalMode.collectAsState()
    // UXR8 N1（ADR-030）：用户规则（「关于我」+「如何回答」双字段，DataStore 持久化）
    val userRulesAboutMe by viewModel.userRulesAboutMe.collectAsState()
    val userRulesHowToRespond by viewModel.userRulesHowToRespond.collectAsState()
    // v1 US-301（方案 B 识图）：视觉旁路授权 + 自动开关
    val visionConsent by viewModel.visionConsent.collectAsState()
    val visionAutoBypass by viewModel.visionAutoBypass.collectAsState()
    // ADR-065（US-3114）：隐藏执行面开关（DataStore 持久化）
    val hiddenDisplayEnabled by viewModel.hiddenDisplayEnabled.collectAsState()
    // guardrail r1 Q-08：保存失败必须可见（否则开关"弹回"却无原因）
    val hiddenDisplaySaveError by viewModel.hiddenDisplaySaveError.collectAsState()
    // v1 真机反馈（Issue 4）：云端视觉旁路需一个被标记为视觉回退的 Provider；
    // 未配置时向用户明示（用户此前误以为"在设置里没有显示"、功能不可用）。
    val visionFallbackName = providers.firstOrNull { it.isVisionFallback }?.name
    // v1 US-201（LLM 操控手机）：无障碍服务连接状态（设置页引导）
    val phoneControlConnected by viewModel.phoneControlConnected.collectAsState()
    // v1 真机二次修复（Issue 4b）：手机操控高危动作（发送/删除/拨号）确认策略
    val highRiskApprovalMode by viewModel.highRiskApprovalMode.collectAsState()
    // v1 批次15（US-1501/1502/1507）：搜索增强（智谱/Tavily Key + SearXNG 端点 + WebView 抓取开关）
    val zhipuKeyConfigured by viewModel.zhipuKeyConfigured.collectAsState()
    val tavilyKeyConfigured by viewModel.tavilyKeyConfigured.collectAsState()
    val searxngSettings by viewModel.searxngSettings.collectAsState()
    val preferredEngine by viewModel.preferredEngine.collectAsState()
    val webviewFetchEnabled by viewModel.webviewFetchEnabled.collectAsState()

    // M7 Phase C（US-042）：档位 UI 由 TierViewModel 驱动（替代原 PerfTier 本地 mock state）
    val tierViewModel: TierViewModel = viewModel(factory = TierViewModel.Factory)
    val tierOverride by tierViewModel.override.collectAsState()

    // v1 US-201（LLM 操控手机）：打开系统无障碍设置（用途声明见下方 SetRow 文案）
    val context = LocalContext.current
    val openAccessibilitySettings = {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    // 批次20（BG-005，ADR-045）：悬浮窗授权状态与跳转（后台操控后台启动 Activity 的官方豁免条件）。
    // guardrail P3-4：状态为可刷新 State（组合期快照会过期），在下方既有轮询循环中一并刷新。
    var canDrawOverlays by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    val openOverlaySettings = {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
    // 批次21（SZ-004，ADR-046）：Shizuku 四态（未安装/已装未激活/未授权/就绪）——轮询刷新；未授权点击请求授权。
    // 真机修复 R3：用户装了 Shizuku App 但未激活 server 时，原三态误报"未安装"——拆分 NOT_RUNNING 并给激活指引。
    var shizukuState by remember { mutableStateOf(io.prism.phonecontrol.bg.ShizukuShell.currentState()) }
    // ADR-065（US-3114）：执行面形态说明 / Watch 运行态（轮询刷新，组合期快照会过期）
    var backendNote by remember { mutableStateOf(io.prism.phonecontrol.bg.VirtualDisplayManager.lastBackendNote) }
    // 2026-11-22（guardrail B2）：note 分「回落」与「已建成但降级」两类，UI 文案不同
    var backendNoteKind by remember {
        mutableStateOf(io.prism.phonecontrol.bg.VirtualDisplayManager.lastBackendNoteKind)
    }
    var watchRunning by remember { mutableStateOf(io.prism.phonecontrol.bg.DisplayWatchOverlay.isRunning) }
    var watchError by remember { mutableStateOf("") }
    // 设计稿 `.row-status` 单行：四态各给一句可执行结论（长引导移入对应系统页/App）。
    val shizukuSubtitle = when (shizukuState) {
        io.prism.phonecontrol.bg.ShizukuShell.State.NOT_INSTALLED ->
            "未安装 · 点此下载（免费开源）"
        io.prism.phonecontrol.bg.ShizukuShell.State.NOT_RUNNING ->
            "已安装未激活 · 点此打开 Shizuku 激活"
        io.prism.phonecontrol.bg.ShizukuShell.State.NOT_AUTHORIZED ->
            "已激活未授权 · 点此允许 Prism 使用"
        io.prism.phonecontrol.bg.ShizukuShell.State.READY ->
            "就绪 · 手势/滑动/返回均可用"
    }

    // ADR-065（US-3114）：Watch 开关（运行时；关闭只移除悬浮窗，不中断执行）
    val toggleWatch: (Boolean) -> Unit = { enabled ->
        if (enabled) {
            val result = io.prism.phonecontrol.bg.DisplayWatchOverlay.start(context)
            watchRunning = io.prism.phonecontrol.bg.DisplayWatchOverlay.isRunning
            watchError = if (result.startsWith("错误")) result else ""
        } else {
            io.prism.phonecontrol.bg.DisplayWatchOverlay.stop()
            watchRunning = false
            watchError = ""
        }
    }

    // v1 US-201：UI 侧轮询无障碍服务连接状态（用户在系统设置开关后返回本页自动刷新引导）。
    // VM 侧无循环（避免 JVM 单测 runTest 调度器挂起），轮询放 Composable LaunchedEffect。
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshPhoneControlStatus()
            // 批次20（guardrail P3-4）：同步刷新悬浮窗授权状态（用户从系统授权页返回后生效）
            canDrawOverlays = android.provider.Settings.canDrawOverlays(context)
            // 批次21（SZ-004）：同步刷新 Shizuku 三态
            shizukuState = io.prism.phonecontrol.bg.ShizukuShell.currentState()
            // ADR-065（US-3114）：同步执行面回落说明与 Watch 运行态
            backendNote = io.prism.phonecontrol.bg.VirtualDisplayManager.lastBackendNote
            backendNoteKind = io.prism.phonecontrol.bg.VirtualDisplayManager.lastBackendNoteKind
            watchRunning = io.prism.phonecontrol.bg.DisplayWatchOverlay.isRunning
            kotlinx.coroutines.delay(PHONE_CONTROL_POLL_MS)
        }
    }

    // ==================== QQ 小号助手（ADR-062/063；US-3101/3105/3108） ====================
    // 状态直连仓库 Flow（与 VM 解耦的最小集成；写操作经 scope 调仓库 setter，写侧即钳制）。
    // guardrail L-01：安全转型（applicationContext 理论上恒为 PrismApplication，但不硬崩）
    val qqApp = context.applicationContext as? io.prism.PrismApplication
    val qqConfig by (qqApp?.qqConfigRepository?.config()
        ?: kotlinx.coroutines.flow.flowOf(io.prism.qq.QqConfig.DEFAULT))
        .collectAsState(initial = io.prism.qq.QqConfig.DEFAULT)
    val qqScope = rememberCoroutineScope()
    // 2026-11-16（ADR-077）：QQ 自定义表情库
    // 2026-11-16（guardrail R2-M-03 / R2-L-16 修复）：
    //   ① 所有写盘（importImage / removeUserSticker / setDescription）都**必须**在 IO 线程，
    //      原实现在 onClick 里直调 store（主线程写 2MB 图片 + 整份 JSON 重写）→ jank/StrictMode；
    //   ② 改用 `viewModel.importQqSticker(...)` 等 VM 方法（本就有大小校验 + IO + 可读错误），
    //      不再在 UI 里重复实现一遍——上一批 guardrail 指出过"断言测的是没人用的死路径"。
    val qqStickerMessageSink = remember { mutableStateOf<String?>(null) }
    val qqStickerState = remember { mutableStateOf(qqApp?.qqStickerStore?.userStickers() ?: emptyList()) }
    var qqStickerEmotionDraft by remember { mutableStateOf("") }
    var qqStickerDescDraft by remember { mutableStateOf("") }
    // guardrail R2-M-02：描述必须能作用于**任意一张**（原实现只能改最后一张 →
    // 先批量导入 5 张、再补描述时前 4 张永久不可见）。
    var qqStickerSelectedId by remember { mutableStateOf<String?>(null) }
    val qqStickerPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null && qqApp != null) {
            viewModel.importQqSticker(uri, qqStickerEmotionDraft)
            // VM 在协程内完成写盘；这里在下一帧刷新一次快照（store 内部有内存缓存，读零 IO）
            qqScope.launch {
                kotlinx.coroutines.delay(400)
                qqStickerState.value = qqApp.qqStickerStore.userStickers()
                qqStickerMessageSink.value = viewModel.qqStickerMessage.value
            }
        }
    }
    var qqRiskDialogVisible by remember { mutableStateOf(false) }    // 用户 2026-11-21 裁定：危险项做「关闭小号助手」+ 二次确认（只停用，不删配置数据）
    var qqDisableConfirmVisible by remember { mutableStateOf(false) }
    // 表情库子页：编辑某张表情的描述（原实现是页内常驻输入框，会随列表一起滚走）
    var qqStickerEditVisible by remember { mutableStateOf(false) }
    // 2026-11-22（ADR-080）：她的日记（只读 + 可删 + 可清空；不可代写，ADR-077 D23）
    var qqDiaryRoleId by remember { mutableStateOf("") }
    val qqDiaryEntries = remember { mutableStateOf(emptyList<io.prism.qq.RoleDiaryStore.Entry>()) }
    var qqDiaryClearConfirm by remember { mutableStateOf(false) }
    var qqDiaryMessage by remember { mutableStateOf<String?>(null) }
    // 刷新：切角色 / 删除 / 清空后都走同一条路径（IO 线程读盘）
    val reloadDiary: () -> Unit = {
        val app = qqApp
        if (app != null) {
            qqScope.launch {
                val roleId = runCatching { app.rolePackLoader.effectiveRolePack()?.id }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                    ?: qqDiaryRoleId.takeIf { it.isNotBlank() }
                    ?: io.prism.qq.DiaryLocalToolExecutor.DEFAULT_ROLE_ID
                qqDiaryRoleId = roleId
                val loaded = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        app.roleDiaryStore.all(roleId)
                    }
                }.getOrDefault(emptyList())
                qqDiaryEntries.value = loaded
                // 诊断：没有这条日志时，"看不到日记"无法区分 ①没写进去 ②写进别的角色 id
                // ③写进去了但 UI 没刷出来。只记数量，不记正文（隐私）。
                android.util.Log.i("PrismSettings", "日记可读=role=$roleId 条数=${loaded.size}")
            }
        }
    }
    // 首帧 + 进入日记子页时都刷新（写日记是后台异步的，缓存会过期）
    LaunchedEffect(qqApp) { reloadDiary() }
    LaunchedEffect(subPage) { if (subPage == SettingsSubPage.QQ_DIARY) reloadDiary() }
    val qqDiarySummary: String = run {
        val list = qqDiaryEntries.value
        if (list.isEmpty()) {
            "还没有写过 · 她动心的时候会记一句"
        } else {
            val day = runCatching {
                java.time.Instant.ofEpochMilli(list.last().at)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            }.getOrNull()
            "${list.size} 条" + (day?.let { " · 最近 %02d-%02d".format(it.monthValue, it.dayOfMonth) } ?: "")
        }
    }
    // 语音：本地音色包管理弹层（用户 2026-11-21 裁定：列表收进弹层，页面只留「已安装/当前使用」）
    var voicePackSheetVisible by remember { mutableStateOf(false) }
    // 网络：首选搜索引擎弹层（原在搜索弹层内的 5 选 1 分段，上移到独立行 + 弹层）
    var preferredEngineSheetVisible by remember { mutableStateOf(false) }
    var qqContactDialogVisible by remember { mutableStateOf(false) }
    var qqContactDraft by remember { mutableStateOf("") }
    // ADR-064 / US-3110：协议端连接配置与状态
    var qqOneBotDialogVisible by remember { mutableStateOf(false) }
    var qqBaseUrlDraft by remember { mutableStateOf("") }
    var qqWsUrlDraft by remember { mutableStateOf("") }
    var qqTokenDraft by remember { mutableStateOf("") }
    var qqSelfIdDraft by remember { mutableStateOf("") }
    var qqTargetIdDraft by remember { mutableStateOf("") }
    var qqClearToken by remember { mutableStateOf(false) }
    var qqChannelStatusText by remember { mutableStateOf("") }
    // 2026-10-26（US-3125）：协议端账号在线态（WS 连接 ≠ QQ 在线）
    val qqProtocolStatus by (qqApp?.qqProtocolStatus
        ?: kotlinx.coroutines.flow.flowOf(null))
        .collectAsState(initial = null)
    val qqProtocolStateText: String? = remember(qqProtocolStatus) {
        val s = qqProtocolStatus ?: return@remember null
        when (s.state) {
            io.prism.qq.QqProtocolHealth.State.ONLINE ->
                "QQ 在线：${s.nickname.ifBlank { "未知昵称" }}" +
                    (if (s.selfId > 0) "（${s.selfId}）" else "") +
                    if (s.appVersion.isNotBlank()) " · NapCat ${s.appVersion}" else ""
            io.prism.qq.QqProtocolHealth.State.QQ_OFFLINE ->
                "QQ 已离线（需在 PC 上重新登录协议端）"
            io.prism.qq.QqProtocolHealth.State.UNREACHABLE ->
                "协议端不可达（${s.detail}）"
        }
    }
    // 2026-10-26（ADR-066）：协议端地址单输入 + 真实连接诊断
    var qqHostDraft by remember { mutableStateOf("") }
    var qqDiagText by remember { mutableStateOf("") }
    var qqDiagRunning by remember { mutableStateOf(false) }
    // US-3115：自由编辑（活跃时段 / 上限 / 最小间隔 / 历史条数）
    var qqScheduleDialogVisible by remember { mutableStateOf(false) }
    var qqWindowStartDraft by remember { mutableStateOf("") }
    var qqWindowEndDraft by remember { mutableStateOf("") }
    var qqCapDraft by remember { mutableStateOf("") }
    // 2026-10-31：每日定时上限（0~200，0=不限）草稿
    var qqPerDayDraft by remember { mutableStateOf("") }
    var qqIntervalDraft by remember { mutableStateOf("") }
    var qqHistoryDialogVisible by remember { mutableStateOf(false) }
    var qqHistoryDraft by remember { mutableStateOf("") }
    // D-05：校验错误必须在**弹窗内**可见（写到列表行会被弹窗遮住）
    var qqScheduleError by remember { mutableStateOf("") }
    var qqHistoryError by remember { mutableStateOf("") }
    // 熔断态（读 runtime；仅在进入本页/轮询时刷新）
    var qqCircuitOpen by remember { mutableStateOf(false) }
    // 2026-10-25：上次定时任务的**结果与原因**（PRD US-3115 AC-6；不再只有"熔断/未熔断"）
    var qqLastOutcomeText by remember { mutableStateOf("") }
    // D-01（ac-verifier）：当前**生效**通道与降级原因（所选协议端不可用时如实展示）
    var qqEffectiveChannelText by remember { mutableStateOf("") }
    // guardrail L-04：QQ 分区独立轮询间隔（不复用手机操控的常量，避免语义耦合）
    LaunchedEffect(qqApp, qqConfig.transport) {
        while (qqApp != null) {
            qqCircuitOpen = runCatching { qqApp?.qqRuntimeStateRepository?.get()?.circuitOpen == true }
                .getOrDefault(false)
            qqLastOutcomeText = runCatching {
                val rt = qqApp?.qqRuntimeStateRepository?.get() ?: return@runCatching ""
                if (rt.lastOutcome.isBlank()) return@runCatching ""
                val at = if (rt.lastOutcomeAtMs > 0) {
                    java.time.Instant.ofEpochMilli(rt.lastOutcomeAtMs)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                } else {
                    ""
                }
                val label = when {
                    // guardrail G-r3-07：「未确认」在语义上是"可能已送达"，不能显示成"失败"，
                    // 否则用户会以为没发出去而手动补发，反而制造重复消息。
                    rt.lastOutcome.contains("SEND_UNCONFIRMED") -> "上次未确认（可能已送达）"
                    // US-3118 AC-3（ac-verifier 复核缺口）：账号态不可用必须显示**可执行的原因**，
                    // 否则用户只看到"上次失败"，不知道该去小号上重新验证。
                    rt.lastOutcome.contains("ACCOUNT_STATE_INVALID") ->
                        "上次未发送：小号登录态失效，需要重新验证"
                    rt.lastOutcome.startsWith("SUCCESS") -> "上次成功"
                    rt.lastOutcome.startsWith("SKIP") -> "上次跳过"
                    else -> "上次失败"
                }
                "$label $at${if (rt.lastOutcomeReason.isBlank()) "" else " · ${rt.lastOutcomeReason}"}"
            }.getOrDefault("")
            qqEffectiveChannelText = runCatching {
                val app = qqApp ?: return@runCatching ""
                val active = app.activeQqChannel()
                val configured = app.configuredQqChannelStatus()
                val name = if (active.transport == io.prism.qq.channel.QqTransport.ONEBOT) "协议端" else "无障碍"
                if (configured.connected) "当前生效：$name"
                else "当前生效：$name（降级原因：${configured.detail.ifBlank { "未连接" }}）"
            }.getOrDefault("")
            kotlinx.coroutines.delay(QQ_POLL_MS)
        }
    }


    // US-3101 AC-2：风险告知确认（未确认时开启总开关的唯一入口）
    if (qqRiskDialogVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { qqRiskDialogVisible = false },
            title = { Text("QQ 小号助手 · 风险告知") },
            text = {
                Text(
                    "开启后，Prism 将通过无障碍能力以你的 QQ 小号身份执行自动化操作（发消息、修改签名等）。请知悉并确认：\n\n" +
                        "1. 自动化操作违反平台自动化条款，小号存在被限制功能、冻结甚至关闭的风险，且解封不可控；\n" +
                        "2. 请仅使用可弃用的独立小号，与主号保持隔离（不同设备/网络更佳），切勿用主号承载；\n" +
                        "3. Prism 已内置低频节流、失败熔断与一键停用，但不承诺零风险；\n" +
                        "4. 仅供个人自用，禁止用于营销、群发等批量场景。\n\n" +
                        "确认后即视为你已理解并自愿承担上述风险。"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    qqRiskDialogVisible = false
                    qqScope.launch {
                        qqApp?.qqConfigRepository?.setRiskAccepted(true)
                        qqApp?.qqConfigRepository?.setEnabled(true)
                    }
                }) { Text("我已知悉并承担风险") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { qqRiskDialogVisible = false }) { Text("取消") }
            }
        )
    }
    // US-3108 AC-3：目标联系人（小号对用户主号的备注名）
    if (qqContactDialogVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { qqContactDialogVisible = false },
            title = { Text("定时消息联系人") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = qqContactDraft,
                    onValueChange = { qqContactDraft = it },
                    singleLine = true,
                    label = { Text("小号对你的备注名") }
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    qqContactDialogVisible = false
                    val v = qqContactDraft
                    qqScope.launch {
                        qqApp?.qqConfigRepository?.setTargetContact(v)
                        // 联系人变更必须让协议端解析缓存失效（guardrail B-01）
                        qqApp?.oneBotChannel?.invalidateTarget()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { qqContactDialogVisible = false }) { Text("取消") }
            }
        )
    }

    // ADR-064 / US-3110：协议端连接配置（HTTP/WS/token/self_id/目标 QQ 号）
    if (qqOneBotDialogVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { qqOneBotDialogVisible = false },
            title = { Text("OneBot 11 协议端连接") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        "需由你在 PC/独立主机自行部署 NapCat 或 SnowLuma（个人自用、非商业）。" +
                            "访问令牌必填，仅保存在本机应用私有目录。" +
                            "数据流：消息正文 → 你自建的协议端 → QQ；消息与历史 → 你配置的模型端点。"
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = qqHostDraft,
                        onValueChange = { qqHostDraft = it },
                        singleLine = true,
                        label = { Text("协议端地址（推荐只填这个：IP 或 IP:端口）") },
                        placeholder = { Text("192.168.1.10") }
                    )
                    Text(
                        "填 PC 的局域网 IP（HTTP 默认 3000、WS 自动 3001）。" +
                            "127.0.0.1 是手机自己；协议端在 PC 上时不要填它。",
                        color = PrismTextFaint,
                        fontSize = 12.sp
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = qqBaseUrlDraft,
                        onValueChange = { qqBaseUrlDraft = it },
                        singleLine = true,
                        label = { Text("HTTP 地址（高级；填写上方地址后此项被覆盖）") }
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = qqWsUrlDraft,
                        onValueChange = { qqWsUrlDraft = it },
                        singleLine = true,
                        label = { Text("WS 地址（高级；填写上方地址后此项被覆盖）") }
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = qqTokenDraft,
                        onValueChange = { qqTokenDraft = it; qqClearToken = false },
                        singleLine = true,
                        label = { Text("access_token（留空=保持不变）") }
                    )
                    if (qqConfig.oneBotToken.isNotBlank()) {
                        Text(
                            "已保存令牌（已隐藏）。清空令牌请点击下方文字。",
                            color = PrismTextFaint
                        )
                        Text(
                            if (qqClearToken) "✓ 保存后将清除令牌（再点一次取消）" else "点击此处标记清除令牌",
                            color = PrismDanger,
                            modifier = Modifier.clickable {
                                qqClearToken = !qqClearToken
                            }
                        )
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = qqSelfIdDraft,
                        onValueChange = { qqSelfIdDraft = it },
                        singleLine = true,
                        label = { Text("小号 QQ 号（self_id）") }
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = qqTargetIdDraft,
                        onValueChange = { qqTargetIdDraft = it },
                        singleLine = true,
                        label = { Text("目标（主号）QQ 号；留空则按备注名解析") }
                    )
                    // 2026-10-26（ADR-066）：真实连接诊断——逐阶段给出可执行结论。
                    // 真机踩坑：网络已通但地址仍是回环，旧 UI 只显示"无法连接"，无从定位。
                    androidx.compose.material3.TextButton(onClick = {
                        if (qqDiagRunning) return@TextButton
                        qqDiagRunning = true
                        qqDiagText = "诊断中…"
                        qqScope.launch {
                            val input = qqHostDraft.ifBlank {
                                qqBaseUrlDraft.ifBlank { qqConfig.oneBotBaseUrl }
                            }
                            val token = qqTokenDraft.ifBlank { qqConfig.oneBotToken }
                            val report = runCatching {
                                io.prism.qq.onebot.QqConnectionDiagnostics.create(context)
                                    .run(input, token)
                            }.getOrNull()
                            qqDiagText = if (report == null) {
                                "诊断异常：无法执行"
                            } else {
                                buildString {
                                    appendLine(report.summary())
                                    report.steps.forEach { step ->
                                        val mark = when (step.outcome) {
                                            io.prism.qq.onebot.QqDiagnosticsRules.Outcome.PASS -> "✓"
                                            io.prism.qq.onebot.QqDiagnosticsRules.Outcome.WARN -> "!"
                                            io.prism.qq.onebot.QqDiagnosticsRules.Outcome.FAIL -> "✗"
                                        }
                                        appendLine("$mark ${step.stage}：${step.detail}")
                                    }
                                    io.prism.qq.onebot.QqDiagnosticsRules
                                        .topologyAdvice(report.verdict)
                                        ?.let { append("建议：$it") }
                                }
                            }
                            qqDiagRunning = false
                        }
                    }) { Text(if (qqDiagRunning) "诊断中…" else "连接诊断（推荐先点）") }
                    if (qqDiagText.isNotBlank()) {
                        Text(
                            qqDiagText,
                            color = if (qqDiagText.startsWith("已连接")) {
                                PrismOk
                            } else {
                                PrismWarning
                            },
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    qqOneBotDialogVisible = false
                    qqScope.launch {
                        // 2026-10-26：单地址输入优先——归一化后同时写 HTTP/WS，
                        // 避免"只改了一个框"导致另一半仍指向回环（真机踩坑点）
                        val pair = qqHostDraft.trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { io.prism.qq.onebot.OneBotEndpoint.normalize(it) }
                            ?.takeIf { it.ok }
                        qqApp?.qqConfigRepository?.setOneBotConnection(
                            baseUrl = pair?.baseUrl ?: qqBaseUrlDraft,
                            wsUrl = pair?.wsUrl ?: qqWsUrlDraft,
                            token = qqTokenDraft,
                            selfId = qqSelfIdDraft.trim().toLongOrNull() ?: 0L,
                            targetUserId = qqTargetIdDraft.trim().toLongOrNull() ?: 0L,
                            clearToken = qqClearToken
                        )
                        qqClearToken = false
                        qqTokenDraft = ""
                        qqHostDraft = ""
                        qqApp?.oneBotChannel?.invalidateTarget()
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { qqOneBotDialogVisible = false }) { Text("取消") }
            }
        )
    }

    // US-3115（2026-10-25 修订）：活跃时段改用 HH:mm，并提供常用预设
    if (qqScheduleDialogVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { qqScheduleDialogVisible = false },
            title = { Text("定时参数") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("活跃时段用 24 小时制「时:分」填写，例如 07:00。允许跨零点（如 22:00 - 02:00）。")
                    androidx.compose.material3.OutlinedTextField(
                        value = qqWindowStartDraft,
                        onValueChange = { qqWindowStartDraft = it },
                        singleLine = true,
                        label = { Text("活跃时段起（HH:mm）") },
                        placeholder = { Text("07:00") }
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = qqWindowEndDraft,
                        onValueChange = { qqWindowEndDraft = it },
                        singleLine = true,
                        label = { Text("活跃时段止（HH:mm）") },
                        placeholder = { Text("22:00") }
                    )
                    // 常用预设：一键填入起止，减少打字
                    Text("常用时段", fontSize = 12.sp, color = PrismAccent)
                    androidx.compose.foundation.layout.Row {
                        listOf(
                            "07:00" to "22:00",
                            "08:00" to "23:00",
                            "20:00" to "23:00"
                        ).forEach { (s, e) ->
                            androidx.compose.material3.TextButton(onClick = {
                                qqWindowStartDraft = s
                                qqWindowEndDraft = e
                                qqScheduleError = ""
                            }) { Text("$s-$e", fontSize = 12.sp) }
                        }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = qqCapDraft,
                        onValueChange = { qqCapDraft = it },
                        singleLine = true,
                        label = { Text("每日消息上限（0~200；0=不限）") }
                    )
                    // 2026-10-31（用户裁定）：定时上限由用户自行决定（0~200，0=不限）。
                    // 与上面的"每日消息上限"是两个正交约束：本项只约束**定时主动**消息。
                    androidx.compose.material3.OutlinedTextField(
                        value = qqPerDayDraft,
                        onValueChange = { qqPerDayDraft = it },
                        singleLine = true,
                        label = { Text("每日定时上限（0~200；0=不限）") }
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = qqIntervalDraft,
                        onValueChange = { qqIntervalDraft = it },
                        singleLine = true,
                        label = { Text("最小间隔（分钟，≥1）") }
                    )
                    if (qqScheduleError.isNotBlank()) {
                        Text(text = qqScheduleError, color = PrismDanger, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val start = io.prism.qq.QqTimeOfDay.parse(qqWindowStartDraft)
                    val end = io.prism.qq.QqTimeOfDay.parse(qqWindowEndDraft)
                    val cap = qqCapDraft.trim().toIntOrNull()
                    val perDay = qqPerDayDraft.trim().toIntOrNull()
                    val intervalMin = qqIntervalDraft.trim().toLongOrNull()
                    if (start == null || end == null ||
                        !io.prism.qq.QqTimeOfDay.isValidWindow(start, end)
                    ) {
                        qqScheduleError = "错误：活跃时段非法。请填写 0:00~24:00 的「时:分」，且起止不能相同"
                        return@TextButton
                    }
                    // 2026-10-31：定时上限自由填写（0~200，0=不限）；非法值在弹窗内直接报错，
                    // 不再静默 clamp——静默 clamp 会让用户以为保存成功却仍是旧值。
                    if (qqPerDayDraft.isNotBlank()) {
                        if (perDay == null ||
                            perDay < io.prism.qq.QqConfig.MIN_PER_DAY ||
                            perDay > io.prism.qq.QqConfig.MAX_PER_DAY_CEIL
                        ) {
                            qqScheduleError = "错误：每日定时上限需为 " +
                                "${io.prism.qq.QqConfig.MIN_PER_DAY}~" +
                                "${io.prism.qq.QqConfig.MAX_PER_DAY_CEIL} 的整数（0=不限）"
                            return@TextButton
                        }
                    }
                    qqScheduleDialogVisible = false
                    qqScope.launch {
                        qqApp?.qqConfigRepository?.setWindow(start, end)
                        cap?.let { qqApp?.qqConfigRepository?.setDailyCap(it) }
                        perDay?.let { qqApp?.qqConfigRepository?.setMaxPerDay(it) }
                        intervalMin?.let {
                            qqApp?.qqConfigRepository?.setMinIntervalMs(
                                (it * 60_000L).coerceAtLeast(io.prism.qq.QqConfig.MIN_INTERVAL_MS_FLOOR)
                            )
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { qqScheduleDialogVisible = false }) { Text("取消") }
            }
        )
    }

    // US-3112：历史上下文条数
    if (qqHistoryDialogVisible) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { qqHistoryDialogVisible = false },
            title = { Text("历史上下文条数") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("回复时读取最近 N 条消息（1~200）。真正上限由字符预算兜底：超出时从最旧截断。消息与相关记忆会发送至你配置的模型端点。")
                    androidx.compose.material3.OutlinedTextField(
                        value = qqHistoryDraft,
                        onValueChange = { qqHistoryDraft = it },
                        singleLine = true,
                        label = { Text("N（1~200）") }
                    )
                    if (qqHistoryError.isNotBlank()) {
                        Text(text = qqHistoryError, color = PrismDanger, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val n = qqHistoryDraft.trim().toIntOrNull()
                    if (n == null || n < io.prism.qq.QqConfig.MIN_HISTORY_LIMIT ||
                        n > io.prism.qq.QqConfig.MAX_HISTORY_LIMIT
                    ) {
                        qqHistoryError = "错误：历史条数需在 1~200 之间"
                        return@TextButton
                    }
                    qqHistoryDialogVisible = false
                    qqScope.launch { qqApp?.qqConfigRepository?.setHistoryLimit(n) }
                }) { Text("保存") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { qqHistoryDialogVisible = false }) { Text("取消") }
            }
        )
    }

    // M-1（guardrail R3）：切子页必须**回到顶部**。否则从「表情库」滚到底再切「连接」，
    // 列表沿用旧偏移被钳到底、用户会以为设置项消失。
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 子页切换（QQ 三子页 / 角色与桌宠）与分区切换都要回到顶部。
    LaunchedEffect(subPage, section) { listState.scrollToItem(0) }

    Box {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                SettingsDetailHeader(
                    title = subPage?.title ?: section.title,
                    onBack = backStep
                )
            }
            if (section == SettingsSection.MODEL) {
            // ===== 设计稿 `settingsModel()`：当前端点（.card + .kv）→ 生成 → 密钥 → danger-row =====
            item { SetSection("当前端点") }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    PrismCardBox {
                        val p0 = activeProvider
                        PrismKv(key = "名称", value = p0?.name ?: "未配置")
                        PrismKv(key = "Base URL", value = p0?.baseUrl ?: "—", mono = true)
                        PrismKv(
                            key = "模型",
                            value = p0?.models?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "—",
                            mono = true
                        )
                        PrismKv(
                            key = "API Key",
                            value = if (p0 != null) "已保存 · Keystore" else "未保存"
                        )
                        PrismKv(key = "激活", value = if (p0 != null) "是" else "否")
                        PrismActionsRow {
                            PrismMini(text = "切换端点", onClick = { providerListVisible = true })
                            PrismMini(text = "新建自定义", onClick = { viewModel.newCustomProvider() })
                        }
                    }
                }
            }
            item { SetSection("生成") }
            item {
                SetRow(
                    title = "深度思考",
                    subtitle = if (thinkingEnabled) {
                        "已开启 · 强度 ${effortLabel(reasoningEffort)}"
                    } else {
                        "已关闭 · 开启后模型先推理再回答"
                    },
                    trailing = {
                        PrismSwitch(checked = thinkingEnabled, onCheckedChange = { viewModel.setThinkingEnabled(it) })
                    },
                    onClick = { thinkingSheetVisible = true }
                )
            }
            item { SetSection("密钥") }
            item {
                SetRow(
                    title = "API Key",
                    subtitle = "按端点保存 · Keystore 加密",
                    onClick = { apiKeyVisible = true }
                )
            }
            item {
                SetRow(
                    title = "自定义请求头",
                    subtitle = activeProvider?.headers?.size?.let { "$it 条" } ?: "0 条",
                    onClick = { providerListVisible = true }
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s6)) {
                    // 设计稿 `.danger-row`「删除这个端点」。**必须二次确认**（guardrail R4-M-03）：
                    // 原实现直接 `deleteProvider` → `repository.remove` 立即物理删除，
                    // 删掉的是当前激活端点会连带让聊天没有模型可用、Keystore 里的 apiKey 变孤儿。
                    // 设计稿该处 mock 明确标注「需要二次确认」。
                    PrismDangerRow(
                        text = "删除这个端点",
                        onClick = {
                            activeProvider?.let { deleteProviderTarget = it }
                        }
                    )
                }
            }
            } // MODEL
            if (section == SettingsSection.BEHAVIOR) {
            // UXR8 N1（ADR-030）：用户规则（类 ChatGPT Custom Instructions 双字段）
            item { SetSection("用户规则") }
            item {
                SetRow(
                    title = "AI 行为偏好",
                    subtitle = userRulesSubtitle(userRulesAboutMe, userRulesHowToRespond),
                    onClick = { userRulesSheetVisible = true }
                )
            }
            // v1 批次16（US-1603）：移除「生物识别解锁」占位（从未实现，M0 起 UI 占位造成
            // 「功能存在但无效」的误导）；「隐私与安全」区块仅此一项，一并移除。
            // v1 批次16（US-1604）：安全拦截提示并入「高危操作」行（同属安全拦截功能，
            // 拆成两行造成认知割裂——见下方手机操控区块）。
            item { SetSection("识图（纯文本模型）") }
            item {
                SetRow(
                    title = "云端视觉旁路",
                    subtitle = buildString {
                        if (visionConsent) append("已授权") else append("未授权")
                        val vp = visionFallbackName
                        append(if (vp != null) " · 视觉模型：$vp" else " · 需配置并标记一个视觉 Provider")
                    },
                    trailing = {
                        PrismSwitch(checked = visionConsent, onCheckedChange = { viewModel.setVisionConsent(it) })
                    },
                    onClick = { viewModel.setVisionConsent(!visionConsent) }
                )
            }
            item {
                SetRow(
                    title = "自动旁路",
                    subtitle = if (visionAutoBypass) "开启 · 图片自动转为描述/文字后回答" else "关闭",
                    trailing = {
                        PrismSwitch(checked = visionAutoBypass, onCheckedChange = { viewModel.setVisionAutoBypass(it) })
                    }
                )
            }
            } // BEHAVIOR
            if (section == SettingsSection.PHONE) {
            // 设计稿 `settingsPhone()`：**顶部三格状态条**（无障碍 / Shizuku / 悬浮窗）。
            // 原实现直接从「无障碍自动恢复」一行开始，用户要读完整行文字才知道三个权限各自状态。
            item {
                Column(
                    modifier = Modifier.padding(
                        start = 20.dp, end = 20.dp,
                        top = PrismSize.s2, bottom = PrismSize.s4
                    )
                ) {
                    val a11yOk = phoneControlConnected
                    val shizukuTone = when (shizukuState) {
                        io.prism.phonecontrol.bg.ShizukuShell.State.READY -> DotTone.Ok
                        io.prism.phonecontrol.bg.ShizukuShell.State.NOT_RUNNING,
                        io.prism.phonecontrol.bg.ShizukuShell.State.NOT_AUTHORIZED -> DotTone.Warn
                        else -> DotTone.Neutral
                    }
                    PrismStatusStrip(
                        cells = listOf(
                            Triple("无障碍", if (a11yOk) "已开启" else "未开启", if (a11yOk) DotTone.Ok else DotTone.Warn),
                            Triple(
                                "Shizuku",
                                when (shizukuState) {
                                    io.prism.phonecontrol.bg.ShizukuShell.State.READY -> "就绪"
                                    io.prism.phonecontrol.bg.ShizukuShell.State.NOT_RUNNING -> "未运行"
                                    io.prism.phonecontrol.bg.ShizukuShell.State.NOT_AUTHORIZED -> "未授权"
                                    io.prism.phonecontrol.bg.ShizukuShell.State.NOT_INSTALLED -> "未安装"
                                },
                                shizukuTone
                            ),
                            Triple(
                                "悬浮窗",
                                if (canDrawOverlays) "已授权" else "未授权",
                                if (canDrawOverlays) DotTone.Ok else DotTone.Warn
                            )
                        )
                    )
                }
            }
            // ===== 设计稿 `settingsPhone()` 分组①：权限与安全 =====
            // 行序与设计稿一致：无障碍服务 → 无障碍自动恢复 → 高危操作拦截 → 工具权限。
            // 每行状态**单行**（SetRow → statusMaxLines = 1）；实现细节（写回范围、回收代价、
            // 最近一次结果）移出状态行，仅在异常态给一行可执行结论。
            item { SetSection("权限与安全") }
            // v1 US-201（LLM 操控手机）：无障碍服务引导 + 用途声明（prominent disclosure）
            item {
                // **不再用 trailing PrismButton**：PrismButton 默认 fillWidth=true，
                // 塞进行的尾部会把标题压成 0 宽（真机上这行只剩一个按钮、标题彻底不可见）。
                // 设计稿这里本就是纯状态行。
                SetRow(
                    title = "无障碍服务",
                    subtitle = if (phoneControlConnected) {
                        "已开启 · AI 可读屏并点击/滑动/输入"
                    } else {
                        "未开启 · 点此打开系统无障碍设置"
                    },
                    onClick = openAccessibilitySettings
                )
            }
            // ADR-046 修订（2026-10-26；PRD US-3116）：无障碍自愈 + Shizuku 单实例说明。
            // 正常态只给一行「已开启 · 被系统关闭后自动重连」；只有异常时才展开状态与最近结果。
            item {
                val a11yState = io.prism.phonecontrol.health.AccessibilityHealthMonitor.currentState
                val selfHeal = (context.applicationContext as? io.prism.PrismApplication)
                    ?.isAccessibilitySelfHealEnabled() ?: true
                SetRow(
                    title = "无障碍自动恢复",
                    subtitle = when {
                        a11yState != io.prism.phonecontrol.health.AccessibilityHealth.State.OK ->
                            "已被系统关闭 · " + if (selfHeal) "自动恢复中" else "自动恢复已关，需手动重开"
                        selfHeal -> "已开启 · 被系统关闭后自动重连"
                        else -> "已关闭 · 被系统关闭后需手动重开"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = selfHeal,
                            onCheckedChange = { on ->
                                (context.applicationContext as? io.prism.PrismApplication)
                                    ?.setAccessibilitySelfHealEnabled(on)
                                if (on) {
                                    // 打开开关立即尝试一次（不等待下次前台打点）
                                    Thread {
                                        runCatching {
                                            io.prism.phonecontrol.health.AccessibilityHealthMonitor
                                                .notifyAppActive(context, force = true)
                                        }
                                    }.start()
                                }
                            }
                        )
                    }
                )
            }
            // v1 批次16（US-1604）：安全拦截提示并入高危操作行——两者同属安全拦截功能：
            // 支付/密码/验证码为不可绕过硬拦截（固定行为），发送/删除/拨号策略由本行循环切换。
            item {
                SetRow(
                    title = "高危操作拦截",
                    subtitle = when (highRiskApprovalMode) {
                        io.prism.config.HighRiskApprovalMode.ASK ->
                            "支付/密码/验证码硬拦截 · 购买/下单/发送/删除需确认"
                        io.prism.config.HighRiskApprovalMode.ALLOW ->
                            "支付/密码/验证码硬拦截 · 购买/下单/发送/删除直接执行"
                        io.prism.config.HighRiskApprovalMode.BLOCK ->
                            "支付/密码/验证码硬拦截 · 购买/下单/发送/删除一律拦截"
                    },
                    onClick = { viewModel.setHighRiskApprovalMode(nextHighRiskApprovalMode(highRiskApprovalMode)) }
                )
            }
            // 工具权限（设计稿未画，功能必须保留）：审批模式属于"权限"语义，归入本组。
            // UXR3 问题 10（ADR-023）：工具审批模式（手动审批 / 自动审批 / 禁用）
            item {
                SetRow(
                    title = "工具权限",
                    subtitle = toolApprovalSubtitle(toolApprovalMode),
                    onClick = { toolApprovalSheetVisible = true }
                )
            }
            // ===== 设计稿 `settingsPhone()` 分组②：执行面 =====
            item { SetSection("执行面") }
            // 批次20（BG-005，ADR-045）：后台模式说明 + 悬浮窗权限引导入口（点击跳系统授权页）
            item {
                SetRow(
                    title = "后台操控",
                    subtitle = when {
                        !canDrawOverlays -> "未授权悬浮窗 · 点此去授权"
                        backendNote == null -> if (hiddenDisplayEnabled) {
                            "在副屏执行 · 主屏可继续使用"
                        } else {
                            "兼容模式 · 主屏可能出现小窗"
                        }
                        // guardrail 2026-11-22 B2：note 有两种语义，不能一律说「已回落」——
                        // 「隐藏屏已建成、只是缺 TRUSTED」并不是回落。
                        backendNoteKind ==
                            io.prism.phonecontrol.bg.BackendNoteKind.DEGRADED ->
                            "隐藏执行面已启用 · ${backendNote}"
                        else -> "已回落兼容模式 · ${backendNote}"
                    },
                    onClick = {
                        if (!canDrawOverlays) {
                            openOverlaySettings()
                        }
                    }
                )
            }
            // ADR-065（US-3114 AC-1/AC-5）：隐藏执行面开关（默认开；失败自动回落兼容模式）
            item {
                SetRow(
                    title = "隐藏执行面",
                    subtitle = when {
                        hiddenDisplaySaveError != null -> "保存失败 · $hiddenDisplaySaveError"
                        hiddenDisplayEnabled -> "已开启 · 优先 shell 隐藏虚拟屏"
                        else -> "已关闭 · 使用兼容模式"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = hiddenDisplayEnabled,
                            onCheckedChange = { viewModel.setHiddenDisplayEnabled(it) }
                        )
                    },
                    onClick = { viewModel.setHiddenDisplayEnabled(!hiddenDisplayEnabled) }
                )
            }
            // ADR-065（US-3114 AC-3）：Watch 悬浮窗（≤2fps；关闭不中断执行）
            item {
                SetRow(
                    title = "观看执行",
                    subtitle = when {
                        watchError.isNotEmpty() -> watchError
                        !canDrawOverlays -> "需要悬浮窗权限 · 点此去授权"
                        watchRunning -> "已开启 · ≤2fps，画面会显示在屏幕上"
                        else -> "已关闭 · 关闭不影响执行"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = watchRunning,
                            onCheckedChange = { on ->
                                if (on && !canDrawOverlays) openOverlaySettings() else toggleWatch(on)
                            }
                        )
                    },
                    onClick = {
                        if (!watchRunning && !canDrawOverlays) openOverlaySettings() else toggleWatch(!watchRunning)
                    }
                )
            }
            // 批次21（SZ-004，ADR-046）：Shizuku 四态行（未装→下载指引；已装未激活→打开 Shizuku App；
            // 未授权→点击授权；就绪→提示全能力）
            item {
                SetRow(
                    title = "Shizuku",
                    subtitle = shizukuSubtitle,
                    onClick = {
                        when (shizukuState) {
                            io.prism.phonecontrol.bg.ShizukuShell.State.NOT_AUTHORIZED ->
                                io.prism.phonecontrol.bg.ShizukuShell.requestPermission(2026)
                            io.prism.phonecontrol.bg.ShizukuShell.State.NOT_RUNNING -> {
                                // 已装未激活：直接打开 Shizuku App（用户按其引导完成无线调试配对/adb 激活）
                                val launch = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                if (launch != null) {
                                    context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                            io.prism.phonecontrol.bg.ShizukuShell.State.NOT_INSTALLED ->
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://shizuku.rikka.app/download/")
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            io.prism.phonecontrol.bg.ShizukuShell.State.READY -> Unit
                        }
                    }
                )
            }
            // 设计稿「后台保活」：**只读状态行**（ADR-041：保活是按任务自动启停，没有用户开关）。
            // 上一轮整个漏掉这行，用户看不到"任务期间会被保活"这一事实。
            item {
                SetRow(
                    title = "后台保活",
                    subtitle = "任务期间自动保持 · 结束即释放"
                )
            }
            // 设计稿 `.actions-row`：段末一个 ghost 小按钮跳系统设置
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s5)) {
                    PrismActionsRow {
                        PrismMini(
                            text = "打开系统设置",
                            onClick = {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_SETTINGS
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        )
                    }
                }
            }
            } // PHONE
            if (section == SettingsSection.QQ) {
            // UI v2（ADR-079 D2）：QQ 拆「主页面 + 3 子页」。子页在本函数内以 [subPage] 切换
            // （不新增导航路由），返回键：子页 → 分区首页 → 设置首页。
            if (subPage == null) {
            // ==================== QQ 小号助手（ADR-062/063；US-3101/3105/3108） ====================
            // 设计稿 `settingsQq()` 顶部是一张**总开关状态卡**：状态点 + 「已开启/已关闭」+ 副行 +
            // 右侧总开关。原实现只有一行「QQ 小号助手」文案，用户看不出小号到底开没开。
            item {
                Column(
                    modifier = Modifier.padding(
                        start = 20.dp, end = 20.dp,
                        top = PrismSize.s2, bottom = PrismSize.s2
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PrismPanel, RoundedCornerShape(PrismSize.rCard))
                            .border(1.dp, PrismLine, RoundedCornerShape(PrismSize.rCard))
                            .padding(PrismSize.s4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PrismSize.s3)
                    ) {
                        PrismDot(if (qqConfig.enabled) DotTone.Ok else DotTone.Neutral)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (qqConfig.enabled) "已开启" else "已关闭",
                                color = PrismText,
                                fontSize = PrismType.body
                            )
                            // guardrail R4-M-02：原副标题「协议端在线 · NapCat」只由 enabled 推导，
                            // 是**假的在线状态**（enabled 只表示"功能开着"，不代表协议端连上）。
                            // 改为优先用真实协议端状态（qqProtocolStateText 来自 qqProtocolStatus 轮询）。
                            Text(
                                text = when {
                                    !qqConfig.enabled -> "未开启 · 开启前需确认风险告知"
                                    !qqProtocolStateText.isNullOrBlank() -> qqProtocolStateText
                                    else -> "已开启 · 协议端状态未知"
                                },
                                color = PrismTextFaint,
                                fontSize = PrismType.meta,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        PrismSwitch(
                            checked = qqConfig.enabled,
                            onCheckedChange = { on ->
                                // 沿用原有风险确认路径：开启时若未确认风险，先弹风险告知
                                if (on && !qqConfig.riskAccepted) {
                                    qqRiskDialogVisible = true
                                } else {
                                    qqScope.launch { qqApp?.qqConfigRepository?.setEnabled(on) }
                                }
                            }
                        )
                    }
                }
            }
            // 2026-11-01（US-3128）：协议端管家——把「启动/修复/扫码」变成按钮，并回读校验电源策略。
            // 放在总开关之前是有意的：用户打开小号助手后第一件事就是让协议端跑起来。
            // M-04（guardrail R5）：这里**不再加组标签**——QqProtocolSupervisorCard 自身标题
            // 就是「协议端管家（本机模式）」，再加一个 12sp 的组标签会出现同名标题连出两次。
            item {
                QqProtocolSupervisorSection(
                    supervisor = qqApp?.qqProtocolSupervisor,
                    onOpenTermuxPermission = {
                        context.startActivity(
                            io.prism.qq.QqTermuxBridge.appDetailsIntent(context)
                        )
                    },
                    onOpenWebUi = { url ->
                        // 用系统浏览器打开回环 WebUI：NapCat 的扫码页面是标准网页，
                        // 自建 WebView 会多引入一层 URL 白名单与 Cookie 生命周期风险。
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                )
            }
            // 设计稿 `settingsQq()` 的连接组：3 行，各一行状态。
            item { SetSection("连接") }
            item {
                SetRow(
                    title = "传输通道",
                    subtitle = when {
                        qqConfig.transport != io.prism.qq.channel.QqTransport.ONEBOT -> "无障碍 · 点击切回协议端优先"
                        qqConfig.oneBotToken.isBlank() -> "协议端优先 · 未配 token，实际走无障碍兜底"
                        else -> "协议端 OneBot 11 · 点击切换为仅无障碍"
                    },
                    onClick = {
                        qqScope.launch {
                            val next = if (qqConfig.transport == io.prism.qq.channel.QqTransport.ONEBOT) {
                                io.prism.qq.channel.QqTransport.ACCESSIBILITY
                            } else {
                                io.prism.qq.channel.QqTransport.ONEBOT
                            }
                            qqApp?.qqConfigRepository?.setTransport(next)
                        }
                    }
                )
            }
            // US-3110 AC-1/AC-3/AC-7：协议端连接配置（脱敏展示，不回填 token）
            item {
                SetRow(
                    title = "协议端地址",
                    subtitle = if (qqConfig.oneBotToken.isBlank()) {
                        "未配置 · 点此填写地址与 token"
                    } else {
                        "${qqConfig.oneBotBaseUrl} · token 已保存"
                    },
                    onClick = {
                        qqBaseUrlDraft = qqConfig.oneBotBaseUrl
                        qqWsUrlDraft = qqConfig.oneBotWsUrl
                        // 2026-10-26：单地址输入默认为空（留空=沿用上面的 HTTP/WS），
                        // 诊断结果也每次重开重置，避免看到上一次的过期结论
                        qqHostDraft = ""
                        qqDiagText = ""
                        // 不回填 token（PRD US-3110 AC-3 展示脱敏）；留空=保持原值
                        qqTokenDraft = ""
                        qqClearToken = false
                        qqSelfIdDraft = qqConfig.oneBotSelfId.takeIf { it > 0 }?.toString().orEmpty()
                        qqTargetIdDraft = qqConfig.oneBotTargetUserId.takeIf { it > 0 }?.toString().orEmpty()
                        qqOneBotDialogVisible = true
                    }
                )
            }
            // US-3110 AC-1：连接测试（实时探测当前通道）
            item {
                SetRow(
                    title = "连接测试",
                    subtitle = if (qqChannelStatusText.isBlank()) {
                        "点击检测当前生效通道"
                    } else {
                        qqChannelStatusText.lineSequence().first().take(40)
                    },
                    onClick = {
                        qqScope.launch {
                            // 如实展示**配置所选通道**的状态（协议端失败原因不再被兜底掩盖）
                            qqChannelStatusText = runCatching {
                                qqApp?.configuredQqChannelStatus()?.detail
                            }.getOrNull() ?: "未初始化（请重启应用后重试）"
                        }
                    }
                )
            }
            // 测试结果是多行诊断（HTTP/WS/账号分项），状态行单行会截断——所以完整结果另给一行。
            // `qqEffectiveChannelText` 是 D-01（ac-verifier）要求的「当前**生效**通道与降级原因」，
            // 与所选通道可能不同（协议端不可用时自动走无障碍），必须如实可见。
            // 这与设计稿不冲突：设计稿没有诊断文案，这里只在有结论时出现，且不是常驻说明。
            val qqConnectionNotes = listOfNotNull(
                qqEffectiveChannelText.takeIf { it.isNotBlank() },
                qqChannelStatusText.takeIf { it.isNotBlank() }
            )
            if (qqConnectionNotes.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        PrismNoteLine(qqConnectionNotes.joinToString("\n"))
                    }
                }
            }
            // 消息组：设计稿是 3 个导航行（进入子页），而不是把 20+ 行平铺在主页。
            item { SetSection("消息") }
            item {
                SetRow(
                    title = "定时与主动消息",
                    subtitle = if (qqConfig.scheduleEnabled) {
                        val quota = if (qqConfig.maxPerDay <= 0) "不限次数" else "每天至多 ${qqConfig.maxPerDay} 次"
                        "已开启 · $quota"
                    } else {
                        "已关闭"
                    },
                    onClick = { subPage = SettingsSubPage.QQ_SCHEDULE }
                )
            }
            item {
                SetRow(
                    title = "自动回复",
                    subtitle = if (qqConfig.autoReplyEnabled) "已开启 · 仅私聊" else "已关闭",
                    onClick = { subPage = SettingsSubPage.QQ_REPLY }
                )
            }
            item {
                SetRow(
                    title = "表情库",
                    subtitle = if (qqStickerState.value.isEmpty()) {
                        "未导入 · 导入后她会按语境挑一张发"
                    } else {
                        "${qqStickerState.value.size} 张 · 描述越具体越准"
                    },
                    onClick = { subPage = SettingsSubPage.QQ_STICKERS }
                )
            }
            // 2026-11-22（ADR-080）：日记入口。此前只有"允许她写日记"的开关，
            // 写进去的内容无处可看（ADR-077 D23 承诺的 UI 一直缺位）。
            item {
                SetRow(
                    title = SettingsSubPage.QQ_DIARY.title,
                    subtitle = qqDiarySummary,
                    onClick = { subPage = SettingsSubPage.QQ_DIARY }
                )
            }
            // US-3105 AC-3/AC-4：熔断状态与恢复（熔断时才出现，设计稿「保护」组）
            if (qqCircuitOpen) {
                item { SetSection("保护") }
                item {
                    SetRow(
                        title = "已熔断",
                        subtitle = "连续失败已自动停止 · 点击恢复",
                        onClick = {
                            qqScope.launch {
                                qqApp?.qqRuntimeStateRepository?.resetCircuit()
                                qqCircuitOpen = false
                            }
                        }
                    )
                }
            }
            // 设计稿「最近状态」：一张 kv 卡片，只放上次执行结论
            item { SetSection("最近状态") }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    PrismCardBox {
                        PrismKv(
                            key = "上次执行",
                            value = qqLastOutcomeText.ifBlank { "尚无执行记录" },
                            // guardrail 2026-11-21 B1：尾部是失败/跳过**原因**，必须完整可见
                            maxLines = 4
                        )
                    }
                }
            }
            // 设计稿危险项：**关闭小号助手**（用户 2026-11-21 裁定：只停用、不删配置）。
            // 位置与配色与设计稿一致（独立置底 danger-row），但语义如实：不清除配置数据。
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s6)) {
                    PrismDangerRow(
                        text = if (qqConfig.enabled) "关闭小号助手" else "小号助手已关闭",
                        onClick = { if (qqConfig.enabled) qqDisableConfirmVisible = true }
                    )
                }
            }
            } // subPage == null（QQ 主页面）

            // ==================== QQ 子页：定时与主动消息 ====================
            if (subPage == SettingsSubPage.QQ_SCHEDULE) {
            item { SetSection("主动消息") }
            item {
                SetRow(
                    title = "随机定时主动消息",
                    subtitle = if (qqConfig.scheduleEnabled) {
                        val quota = if (qqConfig.maxPerDay <= 0) "不限制" else "至多 ${qqConfig.maxPerDay} 次"
                        "已开启 · %s 时段内随机联系你（每日%s）".format(
                            io.prism.qq.QqTimeOfDay.describe(
                                qqConfig.windowStartMinute, qqConfig.windowEndMinute
                            ),
                            quota
                        )
                    } else {
                        "已关闭"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = qqConfig.scheduleEnabled,
                            onCheckedChange = { on -> qqScope.launch { qqApp?.qqConfigRepository?.setScheduleEnabled(on) } }
                        )
                    }
                )
            }
            item {
                SetRow(
                    title = "活跃时段",
                    subtitle = "%s - %s".format(
                        io.prism.qq.QqTimeOfDay.format(qqConfig.windowStartMinute),
                        io.prism.qq.QqTimeOfDay.format(qqConfig.windowEndMinute)
                    ),
                    onClick = {
                        qqWindowStartDraft = io.prism.qq.QqTimeOfDay.format(qqConfig.windowStartMinute)
                        qqWindowEndDraft = io.prism.qq.QqTimeOfDay.format(qqConfig.windowEndMinute)
                        qqCapDraft = qqConfig.dailyCap.toString()
                        qqPerDayDraft = qqConfig.maxPerDay.toString()
                        qqIntervalDraft = (qqConfig.minIntervalMs / 60_000L).toString()
                        qqScheduleError = ""
                        qqScheduleDialogVisible = true
                    }
                )
            }
            item {
                SetRow(
                    title = "每日消息上限",
                    subtitle = if (qqConfig.dailyCap <= 0) "0 条 / 天 · 不限" else "${qqConfig.dailyCap} 条 / 天",
                    onClick = {
                        qqWindowStartDraft = io.prism.qq.QqTimeOfDay.format(qqConfig.windowStartMinute)
                        qqWindowEndDraft = io.prism.qq.QqTimeOfDay.format(qqConfig.windowEndMinute)
                        qqCapDraft = qqConfig.dailyCap.toString()
                        qqPerDayDraft = qqConfig.maxPerDay.toString()
                        qqIntervalDraft = (qqConfig.minIntervalMs / 60_000L).toString()
                        qqScheduleError = ""
                        qqScheduleDialogVisible = true
                    }
                )
            }
            item {
                SetRow(
                    title = "每日定时上限",
                    subtitle = if (qqConfig.maxPerDay <= 0) "0 条 · 不限" else "${qqConfig.maxPerDay} 条 / 天",
                    onClick = {
                        qqWindowStartDraft = io.prism.qq.QqTimeOfDay.format(qqConfig.windowStartMinute)
                        qqWindowEndDraft = io.prism.qq.QqTimeOfDay.format(qqConfig.windowEndMinute)
                        qqCapDraft = qqConfig.dailyCap.toString()
                        qqPerDayDraft = qqConfig.maxPerDay.toString()
                        qqIntervalDraft = (qqConfig.minIntervalMs / 60_000L).toString()
                        qqScheduleError = ""
                        qqScheduleDialogVisible = true
                    }
                )
            }
            item {
                SetRow(
                    title = "最小间隔",
                    subtitle = "${qqConfig.minIntervalMs / 60_000L} 分钟",
                    onClick = {
                        qqWindowStartDraft = io.prism.qq.QqTimeOfDay.format(qqConfig.windowStartMinute)
                        qqWindowEndDraft = io.prism.qq.QqTimeOfDay.format(qqConfig.windowEndMinute)
                        qqCapDraft = qqConfig.dailyCap.toString()
                        qqPerDayDraft = qqConfig.maxPerDay.toString()
                        qqIntervalDraft = (qqConfig.minIntervalMs / 60_000L).toString()
                        qqScheduleError = ""
                        qqScheduleDialogVisible = true
                    }
                )
            }
            // US-3108 AC-2；用户裁定"择优真人感"——泊松更突发，均匀更平缓
            item {
                SetRow(
                    title = "随机策略",
                    subtitle = if (qqConfig.usePoisson) "泊松 · 偶发成串" else "均匀 · 平缓随机",
                    onClick = { qqScope.launch { qqApp?.qqConfigRepository?.setUsePoisson(!qqConfig.usePoisson) } }
                )
            }
            // 2026-10-25（US-3115 AC-6）：熄屏策略
            item {
                SetRow(
                    title = "熄屏时点亮屏幕执行",
                    subtitle = if (qqConfig.wakeOnScreenOff) "已开启 · 熄屏时会点亮屏幕发出" else "已关闭 · 熄屏时跳过并记录原因",
                    trailing = {
                        PrismSwitch(
                            checked = qqConfig.wakeOnScreenOff,
                            onCheckedChange = { on ->
                                qqScope.launch { qqApp?.qqConfigRepository?.setWakeOnScreenOff(on) }
                            }
                        )
                    }
                )
            }
            // 执行结果：设计稿是一张 kv 卡片 + 熔断状态行
            item { SetSection("执行结果") }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    PrismCardBox {
                        PrismKv(
                            key = "上次",
                            value = qqLastOutcomeText.ifBlank { "尚无执行记录" },
                            // guardrail 2026-11-21 B1：含失败/跳过原因，必须完整可见
                            maxLines = 4
                        )
                    }
                }
            }
            item {
                SetRow(
                    title = "熔断状态",
                    subtitle = if (qqCircuitOpen) "已熔断 · 需手动恢复" else "正常",
                    onClick = {
                        if (qqCircuitOpen) {
                            qqScope.launch {
                                qqApp?.qqRuntimeStateRepository?.resetCircuit()
                                qqCircuitOpen = false
                            }
                        }
                    }
                )
            }
            } // subPage == QQ_SCHEDULE

            // ==================== QQ 子页：自动回复 ====================
            if (subPage == SettingsSubPage.QQ_REPLY) {
            item { SetSection("回复") }
            item {
                SetRow(
                    title = "双向对话",
                    subtitle = if (qqConfig.autoReplyEnabled) {
                        "已开启 · 你发消息后 AI 以小号身份回你"
                    } else {
                        "已关闭 · 开启后 AI 会像真人一样回你"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = qqConfig.autoReplyEnabled,
                            onCheckedChange = { on ->
                                qqScope.launch { qqApp?.qqConfigRepository?.setAutoReplyEnabled(on) }
                            }
                        )
                    }
                )
            }
            item {
                SetRow(
                    title = "定时消息联系人",
                    subtitle = qqConfig.targetContact.ifBlank { "未配置 · 小号对你的备注名" },
                    onClick = {
                        qqContactDraft = qqConfig.targetContact
                        qqContactDialogVisible = true
                    }
                )
            }
            item {
                SetRow(
                    title = "历史上下文条数",
                    subtitle = "最近 ${qqConfig.historyLimit} 条",
                    onClick = {
                        qqHistoryDraft = qqConfig.historyLimit.toString()
                        qqHistoryError = ""
                        qqHistoryDialogVisible = true
                    }
                )
            }
            item { SetSection("上下文") }
            item {
                SetRow(
                    title = "注入 Prism 记忆",
                    subtitle = if (qqConfig.memoryInjectionEnabled) {
                        "已开启 · 参考跨会话记忆与画像"
                    } else {
                        "已关闭 · 仅用最近对话"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = qqConfig.memoryInjectionEnabled,
                            onCheckedChange = { on ->
                                qqScope.launch { qqApp?.qqConfigRepository?.setMemoryInjectionEnabled(on) }
                            }
                        )
                    }
                )
            }
            // ==================== 2026-11-16（ADR-077）：拟人节奏 ====================
            item {
                SetRow(
                    title = "拟人节奏",
                    subtitle = if (qqConfig.pacingEnabled) {
                        "已开启 · ${"%.1f".format(qqConfig.typingCharsPerSecond)} 字/秒，深夜更慢"
                    } else {
                        "已关闭 · 生成完立即发出"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = qqConfig.pacingEnabled,
                            onCheckedChange = { on ->
                                qqScope.launch { qqApp?.qqConfigRepository?.setPacingEnabled(on) }
                            }
                        )
                    }
                )
            }
            // ==================== 2026-11-16（ADR-077）：能力接入 + 日记 ====================
            item { SetSection("能力") }
            item {
                SetRow(
                    title = "聊天里可以调用 Prism 能力",
                    subtitle = if (qqConfig.replyToolsEnabled) {
                        "已开启 · 联网/热榜/资料库/待办/文档/日记（上限 ${qqConfig.replyToolBudgetMs / 1000}s）"
                    } else {
                        "已关闭 · 只根据上下文回答"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = qqConfig.replyToolsEnabled,
                            onCheckedChange = { on ->
                                qqScope.launch { qqApp?.qqConfigRepository?.setReplyToolsEnabled(on) }
                            }
                        )
                    }
                )
            }
            item {
                SetRow(
                    title = "允许她在聊天里操作手机",
                    subtitle = if (qqConfig.replyPhoneControlEnabled) {
                        "已开启 · 后台建副屏（较高风险）"
                    } else {
                        "已关闭 · 不建副屏、不抢前台"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = qqConfig.replyPhoneControlEnabled,
                            onCheckedChange = { on ->
                                qqScope.launch { qqApp?.qqConfigRepository?.setReplyPhoneControlEnabled(on) }
                            }
                        )
                    }
                )
            }
            item {
                SetRow(
                    title = "允许她写日记",
                    subtitle = if (qqConfig.diaryEnabled) {
                        "已开启 · 每天最多 ${io.prism.qq.RoleDiaryStore.MAX_PER_DAY} 条"
                    } else {
                        "已关闭 · 不写入日记"
                    },
                    trailing = {
                        PrismSwitch(
                            checked = qqConfig.diaryEnabled,
                            onCheckedChange = { on ->
                                qqScope.launch { qqApp?.qqConfigRepository?.setDiaryEnabled(on) }
                            }
                        )
                    }
                )
            }
            } // subPage == QQ_REPLY

            // ==================== QQ 子页：表情库（ADR-077） ====================
            if (subPage == SettingsSubPage.QQ_STICKERS) {
            item {
                val pending = qqStickerState.value.count {
                    it.description.isBlank() && it.emotion.isBlank()
                }
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(
                        text = if (qqStickerState.value.isEmpty()) {
                            "还没有导入表情 · 单张 ≤ 2MB"
                        } else {
                            "${qqStickerState.value.size} 张" +
                                (if (pending > 0) " · $pending 张待补描述" else "") +
                                " · 单张 ≤ 2MB"
                        },
                        color = PrismTextFaint,
                        fontSize = 11.sp
                    )
                }
            }
            item { SetSection("按语境挑选") }
            qqStickerState.value.forEach { sticker ->
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        PrismRow(
                            title = (sticker.emotion.ifBlank { "未标注" }) + " · " + sticker.id,
                            status = sticker.description.ifBlank { "（还没有描述）" },
                            statusMaxLines = 1,
                            onClick = {
                                qqStickerSelectedId = sticker.id
                                qqStickerDescDraft = sticker.description
                                qqStickerEditVisible = true
                            },
                            trailing = {
                                PrismMini(
                                    text = "编辑",
                                    onClick = {
                                        qqStickerSelectedId = sticker.id
                                        qqStickerDescDraft = sticker.description
                                        qqStickerEditVisible = true
                                    }
                                )
                                PrismMini(
                                    text = "删除",
                                    danger = true,
                                    onClick = {
                                        // guardrail R2-M-03/L-16：走 VM（IO 线程 + 可读错误），不在主线程写盘
                                        viewModel.deleteQqSticker(sticker.id)
                                        qqScope.launch {
                                            kotlinx.coroutines.delay(300)
                                            qqStickerState.value =
                                                qqApp?.qqStickerStore?.userStickers() ?: emptyList()
                                            qqStickerMessageSink.value = viewModel.qqStickerMessage.value
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }
            qqStickerMessageSink.value?.let { message ->
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        PrismNoteLine(message)
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s4)) {
                    PrismActionsRow {
                        PrismMini(
                            text = "导入表情",
                            icon = PrismIcons.Plus,
                            onClick = { qqStickerPicker.launch(arrayOf("image/*")) }
                        )
                    }
                    PrismFootnote("描述越具体，她越能挑到贴切的一张；导入的图片只存在应用私有目录。")
                }
            }
            } // subPage == QQ_STICKERS
            // ==================== 2026-11-22（ADR-080）：她的日记 ====================
            // 语义严格按 ADR-077 D23：**只读、可删、可清空，不可代写**。
            // 没有"新增"按钮是刻意的——代写会破坏"这是她自己写的"这一前提。
            if (subPage == SettingsSubPage.QQ_DIARY) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(
                            text = qqDiarySummary +
                                " · 角色 ${qqDiaryRoleId.ifBlank { io.prism.qq.DiaryLocalToolExecutor.DEFAULT_ROLE_ID }}" +
                                " · 每天最多 ${io.prism.qq.RoleDiaryStore.MAX_PER_DAY} 条",
                            color = PrismTextFaint,
                            fontSize = 11.sp
                        )
                    }
                }
                if (qqDiaryEntries.value.isEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s4)) {
                            PrismFootnote(
                                "她还没写下什么。当某个瞬间让她心里动了一下（你说的话、你们的约定、" +
                                    "她忽然想通的事），她会自己记一句——这部分内容不会发给你。"
                            )
                        }
                    }
                } else {
                    // 倒序：最新的在最上面
                    qqDiaryEntries.value.asReversed().forEach { entry ->
                        item {
                            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                                PrismRow(
                                    title = runCatching {
                                        java.time.Instant.ofEpochMilli(entry.at)
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .let {
                                                "%02d-%02d %02d:%02d".format(
                                                    it.monthValue, it.dayOfMonth, it.hour, it.minute
                                                )
                                            }
                                    }.getOrDefault("") +
                                        entry.mood.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                    trailing = {
                                        PrismMini(
                                            text = "删除",
                                            danger = true,
                                            onClick = {
                                                qqScope.launch {
                                                    val ok = runCatching {
                                                        kotlinx.coroutines.withContext(
                                                            kotlinx.coroutines.Dispatchers.IO
                                                        ) {
                                                            qqApp?.roleDiaryStore
                                                                ?.remove(qqDiaryRoleId, entry.at)
                                                        }
                                                    }.getOrDefault(false)
                                                    qqDiaryMessage = if (ok == true) "已删除一条" else "删除失败"
                                                    reloadDiary()
                                                }
                                            }
                                        )
                                    }
                                )
                                // 正文单独渲染：日记是**成段的第一人称文字**，塞进 SetRow 的
                                // 单行状态位会被截断（设计规范 `.row-status` 强制单行）。
                                Text(
                                    text = entry.text,
                                    color = PrismText,
                                    fontSize = PrismType.body,
                                    lineHeight = PrismType.bodyLine,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp, end = 8.dp)
                                )
                            }
                        }
                    }
                }
                qqDiaryMessage?.let { message ->
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                            PrismNoteLine(message)
                        }
                    }
                }
                // 危险操作独立置底（与设计稿规则一致：不与普通行混排）
                if (qqDiaryEntries.value.isNotEmpty()) {
                    item { SetSection("危险") }
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s4)) {
                            PrismActionsRow {
                                PrismMini(
                                    text = "清空她的日记",
                                    danger = true,
                                    onClick = { qqDiaryClearConfirm = true }
                                )
                                PrismMini(
                                    text = "刷新",
                                    onClick = { reloadDiary() }
                                )
                            }
                            PrismFootnote("清空不可恢复。日记只存在应用私有目录，不会上传到任何地方。")
                        }
                    }
                }
                if (qqDiaryClearConfirm) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                            PrismNoteLine("确定清空「${qqDiaryRoleId}」的全部日记？此操作不可恢复。")
                            PrismActionsRow {
                                PrismMini(
                                    text = "确定清空",
                                    danger = true,
                                    onClick = {
                                        qqDiaryClearConfirm = false
                                        qqScope.launch {
                                            val ok = runCatching {
                                                kotlinx.coroutines.withContext(
                                                    kotlinx.coroutines.Dispatchers.IO
                                                ) {
                                                    qqApp?.roleDiaryStore?.clear(qqDiaryRoleId)
                                                }
                                            }.getOrDefault(false)
                                            qqDiaryMessage = if (ok == true) "已清空" else "清空失败"
                                            reloadDiary()
                                        }
                                    }
                                )
                                PrismMini(text = "取消", onClick = { qqDiaryClearConfirm = false })
                            }
                        }
                    }
                }
            } // subPage == QQ_DIARY
            } // QQ
            if (section == SettingsSection.VOICE) {
            // ===== 设计稿 `settingsVoice()`：**单页四组**（识别 / 播报 / 本地音色包 / 角色）=====
            // 上一轮做成「语音 / 角色与桌宠」实心分段 + 「语音引擎」折叠手风琴，与设计稿的
            // 页面骨架不同（设计稿没有分段条，四组直接铺开、从上往下读）。
            // 角色与桌宠在设计稿是**独立页**（`settings-persona`），这里用子页承载。
            if (subPage == null) {
            item {
                VoiceEngineSection(
                    viewModel = viewModel,
                    onOpenPersona = { subPage = SettingsSubPage.VOICE_PERSONA },
                    onOpenVoicePacks = { voicePackSheetVisible = true }
                )
            }
            } // subPage == null（语音主页面）

            // ==================== 子页：角色与桌宠 ====================
            if (subPage == SettingsSubPage.VOICE_PERSONA) {
            item { RolePackSection(viewModel) }
            item { PetSection(viewModel) }
            } // subPage == VOICE_PERSONA
            } // VOICE
            if (section == SettingsSection.SYSTEM) {
            // v1 批次15（US-1501/1502/1507）：搜索增强（智谱 / Tavily / SearXNG）
            item { SetSection("搜索") }
            item {
                SetRow(
                    title = "搜索引擎增强",
                    subtitle = searchEnhancementSubtitle(
                        zhipu = zhipuKeyConfigured,
                        tavily = tavilyKeyConfigured,
                        searxng = searxngSettings != null,
                        preferredEngine = preferredEngine
                    ),
                    onClick = { searchEnhancementSheetVisible = true }
                )
            }
            // 设计稿把「首选引擎」放在弹层里；但弹层因此多出一整组 5 选 1 分段，与设计稿
            // 「三个字段 + 一句说明 + 保存/取消」的结构冲突。这里把它**上移到页面行**
            // （点击开通用单选弹层），弹层恢复设计稿形态，功能零丢失（US-1509）。
            item {
                SetRow(
                    title = "首选引擎",
                    subtitle = preferredEngineLabel(preferredEngine),
                    onClick = { preferredEngineSheetVisible = true }
                )
            }
            item {
                SetRow(
                    title = "WebView 渲染抓取",
                    subtitle = if (webviewFetchEnabled) "已开启 · 仅公网 https" else "已关闭 · 反爬页面抓取率较低",
                    trailing = {
                        PrismSwitch(
                            checked = webviewFetchEnabled,
                            onCheckedChange = { viewModel.setWebviewFetchEnabled(it) }
                        )
                    }
                )
            }
            item { SetSection("设备") }
            item {
                SetRow(
                    title = "性能档位",
                    subtitle = tierSubtitle(
                        override = tierOverride,
                        detectedTier = tierViewModel.detectedTier,
                        currentTier = tierViewModel.currentTier,
                        totalRamBytes = tierViewModel.totalRamBytes
                    ),
                    onClick = { tierSheetVisible = true }
                )
            }
            item { SetSection("关于") }
            item {
                // 设计稿 `settingsSystem()` 的「关于」组是两行：版本 / 开源许可。
                // 版本行状态是「v1.1 · 安装于 <真实日期>」（用户 2026-11-21 裁定：用真实数据，
                // 不照抄设计稿的伪日期），尾部等宽版本号与设计稿一致。
                // DEF-R3-01（ac-verifier B2）已保证版本号取自 PackageManager，不再写死。
                val appVersion = remember { appVersionName(context) }
                val installedOn = remember { appInstallDate(context) }
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    PrismRow(
                        title = "版本",
                        status = listOfNotNull("v$appVersion", installedOn).joinToString(" · "),
                        statusMaxLines = 1,
                        trailing = {
                            Text(
                                text = "v$appVersion",
                                color = PrismTextDim,
                                fontSize = PrismType.meta,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    )
                    PrismRow(
                        title = "开源许可",
                        status = "Apache 2.0",
                        statusMaxLines = 1,
                        onClick = { licenseSheetVisible = true }
                    )
                }
            }
            } // SYSTEM
        }

        // 开源许可弹层（设计稿「开源许可」行）
        PrismSheetHost(visible = licenseSheetVisible, onDismiss = { licenseSheetVisible = false }) {
            PrismSheet(title = "开源许可", subtitle = "Prism ${appVersionName(context)}") {
                Text(
                    text = "Prism 采用 Apache License 2.0 发布。三方组件的完整清单与版本见仓库 NOTICE 文件。",
                    color = PrismTextDim,
                    fontSize = PrismType.sub,
                    lineHeight = PrismType.subLine
                )
                Spacer(Modifier.height(PrismSize.s3))
                PrismKv(key = "本应用", value = "Apache 2.0")
                PrismKv(key = "markdown-renderer", value = "Apache 2.0")
                PrismKv(key = "ObjectBox", value = "Apache 2.0")
                PrismKv(key = "OkHttp", value = "Apache 2.0")
                PrismKv(key = "Apache POI", value = "Apache 2.0")
                Spacer(Modifier.height(PrismSize.s5))
                PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = { licenseSheetVisible = false })
            }
        }

        // M-03：删除端点二次确认
        PrismSheetHost(
            visible = deleteProviderTarget != null,
            onDismiss = { deleteProviderTarget = null }
        ) {
            val target = deleteProviderTarget
            PrismSheet(
                title = "删除端点",
                subtitle = "此操作不可撤销"
            ) {
                Text(
                    text = "确认删除「${target?.name ?: ""}」？删除后该端点的 API Key 将失去引用。",
                    color = PrismTextDim,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
                Spacer(Modifier.height(PrismSize.s5))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PrismButton(
                        text = "确认删除",
                        variant = PrismButtonVariant.Danger,
                        onClick = {
                            target?.let { viewModel.deleteProvider(it) }
                            deleteProviderTarget = null
                        },
                        fillWidth = false,
                        modifier = Modifier.weight(1f)
                    )
                    PrismButton(
                        text = "取消",
                        variant = PrismButtonVariant.Ghost,
                        onClick = { deleteProviderTarget = null },
                        fillWidth = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Provider 列表弹层
        PrismSheetHost(visible = providerListVisible, onDismiss = { providerListVisible = false }) {
            ProviderListSheet(
                providers = providers,
                activeId = activeProvider?.id,
                onSelect = { viewModel.selectProvider(it) },
                onCreateFromPreset = { viewModel.createFromPreset(it) },
                onNewCustom = { viewModel.newCustomProvider() },
                onClose = { providerListVisible = false }
            )
        }
        // Provider 详情编辑弹层
        PrismSheetHost(visible = selectedProvider != null, onDismiss = { viewModel.selectProvider(null) }) {
            selectedProvider?.let { config ->
                ProviderEditSheet(config, viewModel)
            }
        }
        // API Key 管理弹层
        PrismSheetHost(visible = apiKeyVisible, onDismiss = { apiKeyVisible = false }) {
            ApiKeySheet(providers, viewModel)
        }
        // M7 Phase C（US-042）：性能档位选择弹层
        PrismSheetHost(visible = tierSheetVisible, onDismiss = { tierSheetVisible = false }) {
            TierSheet(
                override = tierOverride,
                detectedTier = tierViewModel.detectedTier,
                currentTier = tierViewModel.currentTier,
                totalRamBytes = tierViewModel.totalRamBytes,
                onSelect = { value ->
                    if (value == TierConfigRepository.OVERRIDE_AUTO) {
                        tierViewModel.clearOverride()
                    } else {
                        tierViewModel.setOverride(value)
                    }
                    tierSheetVisible = false
                },
                onClose = { tierSheetVisible = false }
            )
        }
    // 用户 2026-11-21 裁定：QQ 危险项做「关闭小号助手」+ 二次确认，**只停用、不删配置**。
    // 设计稿原文是「关闭并清除配置」，但数据层没有安全的"清除配置"API（会把表情库/日记/联系人
    // 一并抹掉且不可恢复）；这里保持危险项的**位置与配色**一致，语义如实降级为"停用"。
    PrismSheetHost(visible = qqDisableConfirmVisible, onDismiss = { qqDisableConfirmVisible = false }) {
        PrismSheet(
            title = "关闭小号助手",
            subtitle = "停用后不再自动收发消息，配置与表情库保留"
        ) {
            Text(
                text = "关闭后：定时主动消息、自动回复、协议端自动化全部停止；" +
                    "联系人、时段、上限、表情库与日记等配置**不会被删除**，重新打开即可继续使用。",
                color = PrismTextDim,
                fontSize = PrismType.sub,
                lineHeight = PrismType.subLine
            )
            Spacer(Modifier.height(PrismSize.s5))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PrismSize.s2)
            ) {
                PrismButton(
                    text = "确认关闭",
                    variant = PrismButtonVariant.Danger,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        qqDisableConfirmVisible = false
                        qqScope.launch { qqApp?.qqConfigRepository?.setEnabled(false) }
                    }
                )
                PrismButton(
                    text = "取消",
                    variant = PrismButtonVariant.Ghost,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                    onClick = { qqDisableConfirmVisible = false }
                )
            }
        }
    }
    // 表情库子页：编辑某张表情的描述（原实现是列表底部常驻输入框，长列表时会滚出视口）
    PrismSheetHost(visible = qqStickerEditVisible, onDismiss = { qqStickerEditVisible = false }) {
        PrismSheet(
            title = "表情描述",
            subtitle = qqStickerSelectedId?.let { "表情 $it · 描述越具体越容易挑对" } ?: "描述越具体越容易挑对"
        ) {
            PrismField(
                label = "描述",
                value = qqStickerDescDraft,
                onValueChange = { qqStickerDescDraft = it },
                placeholder = "如：粉发小人举着「抱抱」牌子"
            )
            Spacer(Modifier.height(PrismSize.s5))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PrismSize.s2)
            ) {
                PrismButton(
                    text = "保存",
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val targetId = qqStickerSelectedId
                        if (targetId != null) {
                            viewModel.setQqStickerDescription(targetId, qqStickerDescDraft)
                            qqStickerEditVisible = false
                            qqScope.launch {
                                kotlinx.coroutines.delay(300)
                                qqStickerState.value =
                                    qqApp?.qqStickerStore?.userStickers() ?: emptyList()
                                qqStickerMessageSink.value = viewModel.qqStickerMessage.value
                            }
                        } else {
                            qqStickerEditVisible = false
                        }
                    }
                )
                PrismButton(
                    text = "取消",
                    variant = PrismButtonVariant.Ghost,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                    onClick = { qqStickerEditVisible = false }
                )
            }
        }
    }

        // 语音：本地音色包管理弹层（列表从页面收进弹层，页面只留「已安装 / 当前使用」）
        PrismSheetHost(visible = voicePackSheetVisible, onDismiss = { voicePackSheetVisible = false }) {
            VoicePackSheet(viewModel = viewModel, onClose = { voicePackSheetVisible = false })
        }
        // 网络：首选搜索引擎（从搜索弹层上移出来的 5 选 1，功能与原 PrismSegmented 等价）
        PrismSheetHost(
            visible = preferredEngineSheetVisible,
            onDismiss = { preferredEngineSheetVisible = false }
        ) {
            PrismChoiceSheet(
                title = "首选引擎",
                subtitle = "默认顺序：博查 → 智谱 → SearXNG → Tavily → Bing/Baidu",
                options = PREFERRED_ENGINE_LABELS,
                selectedIndex = preferredEngineIndex(preferredEngine),
                descriptions = listOf(
                    "按默认顺序依次尝试",
                    "博查 AI 搜索（国内直连）",
                    "智谱 Web Search",
                    "自建 SearXNG 实例",
                    "Tavily（海外）"
                ),
                onSelect = { index ->
                    viewModel.setPreferredEngine(PREFERRED_ENGINE_VALUES[index])
                    preferredEngineSheetVisible = false
                },
                onDismiss = { preferredEngineSheetVisible = false }
            )
        }
        // 问题 8a（ADR-020）：深度思考设置弹层（开关 + 强度选择）
        PrismSheetHost(visible = thinkingSheetVisible, onDismiss = { thinkingSheetVisible = false }) {
            ThinkingSheet(
                enabled = thinkingEnabled,
                effort = reasoningEffort,
                onToggle = { viewModel.setThinkingEnabled(it) },
                onSelectEffort = { effort ->
                    viewModel.setReasoningEffort(effort)
                    thinkingSheetVisible = false
                },
                onClose = { thinkingSheetVisible = false }
            )
        }
        // UXR3 问题 10（ADR-023）：工具审批模式弹层（手动 / 自动 / 禁用）
        PrismSheetHost(visible = toolApprovalSheetVisible, onDismiss = { toolApprovalSheetVisible = false }) {
            ToolApprovalSheet(
                current = toolApprovalMode,
                onSelect = { mode ->
                    viewModel.setToolApprovalMode(mode)
                    toolApprovalSheetVisible = false
                },
                onClose = { toolApprovalSheetVisible = false }
            )
        }
        // UXR8 N1（ADR-030）：用户规则编辑弹层（「关于我」+「如何回答」双字段）
        PrismSheetHost(visible = userRulesSheetVisible, onDismiss = { userRulesSheetVisible = false }) {
            UserRulesSheet(
                aboutMe = userRulesAboutMe,
                howToRespond = userRulesHowToRespond,
                onSave = { about, respond ->
                    viewModel.saveUserRules(about, respond)
                    userRulesSheetVisible = false
                },
                onClose = { userRulesSheetVisible = false }
            )
        }
        // v1 批次15（US-1501/1502/1507）：搜索增强配置弹层（智谱/Tavily Key + SearXNG 端点）
        PrismSheetHost(visible = searchEnhancementSheetVisible, onDismiss = { searchEnhancementSheetVisible = false }) {
            SearchEnhancementSheet(
                searxngEndpoint = searxngSettings?.endpoint.orEmpty(),
                searxngUsername = searxngSettings?.username.orEmpty(),
                searxngPassword = searxngSettings?.password.orEmpty(),
                viewModel = viewModel,
                onClose = { searchEnhancementSheetVisible = false }
            )
        }
    }
}

/**
 * 搜索增强行副标题（v1 批次15，US-1501/1502/1507）。
 *
 * 无任何配置 → "未配置 · 配置 Key/端点提升搜索命中率"；
 * 有配置 → 设计稿写法「Tavily 已配置 · 首选 Tavily」（已配置引擎 + 当前首选）。
 */
private fun searchEnhancementSubtitle(
    zhipu: Boolean,
    tavily: Boolean,
    searxng: Boolean,
    preferredEngine: String
): String {
    // **只列真正配好的引擎**（Key/端点存在）。博查的 Key 不在本弹层编辑，也**没有**
    // `bochaKeyConfigured` 数据源，所以不能把「首选 = 博查」当成「博查已配置」——
    // 那是伪状态（guardrail 2026-11-21 建议 1）。首选引擎在 `·` 之后单独如实陈述。
    val configured = listOfNotNull(
        "智谱".takeIf { zhipu },
        "Tavily".takeIf { tavily },
        "SearXNG".takeIf { searxng }
    )
    return if (configured.isEmpty()) {
        "未配置 · 配置 Key/端点提升搜索命中率"
    } else {
        "${configured.joinToString("、")} 已配置 · 首选 ${preferredEngineLabel(preferredEngine)}"
    }
}

/**
 * 用户规则行副标题（UXR8 N1，ADR-030）。
 *
 * 两字段均空 → "未配置 · 告诉我你的背景与回复偏好"；仅配置一个 → 显示已配置字数；
 * 两个都配置 → "关于我 ✓ · 如何回答 ✓"。
 */
private fun userRulesSubtitle(aboutMe: String, howToRespond: String): String = when {
    aboutMe.isBlank() && howToRespond.isBlank() -> "未配置 · 告诉我你的背景与回复偏好"
    aboutMe.isBlank() -> "仅「如何回答」已配置 · 最高优先级约束 LLM"
    howToRespond.isBlank() -> "仅「关于我」已配置 · 最高优先级约束 LLM"
    else -> "「关于我」+「如何回答」已配置 · 最高优先级约束 LLM"
}

/**
 * 格式化档位行副标题（US-042）。
 *
 * - 覆盖为 AUTO：显示「自动检测 · {RAM}GB · 当前 {tier}」
 * - 覆盖为手动：显示「手动覆盖 · {tier}」
 *
 * @param override 用户覆盖值（[TierConfigRepository.OVERRIDE_AUTO] 或档位枚举名）
 * @param detectedTier RAM 检测到的档位
 * @param currentTier 当前生效档位（运行中，重启后反映新覆盖）
 * @param totalRamBytes 设备 RAM 总量（字节）
 */
private fun tierSubtitle(
    override: String,
    detectedTier: PerformanceTier,
    currentTier: PerformanceTier,
    totalRamBytes: Long
): String {
    val ramGb = totalRamBytes.toDouble() / (1024L * 1024L * 1024L)
    val ramStr = String.format(Locale.US, "%.1f", ramGb) + "GB"
    return if (override == TierConfigRepository.OVERRIDE_AUTO) {
        "自动检测 · $ramStr · ${currentTier.label}"
    } else {
        "手动覆盖 · ${currentTier.label}"
    }
}

/** 档位中文标签（仅 UI 展示，与 [PerformanceTier] 枚举名解耦）。 */
private val PerformanceTier.label: String
    get() = when (this) {
        PerformanceTier.FULL -> "全功能"
        PerformanceTier.STANDARD -> "标准"
        PerformanceTier.MINIMAL -> "极简"
        PerformanceTier.CHAT_ONLY -> "仅聊天"
    }

/** 思考强度中文标签（问题 8a，仅 UI 展示，与 [ThinkingConfigRepository] 值解耦）。 */
private fun effortLabel(effort: String): String = when (effort) {
    "low" -> "轻"
    "high" -> "标准"
    "max" -> "最强"
    else -> effort
}

/** 工具审批模式中文标签（UXR3 问题 10，仅 UI 展示，与 [ToolApprovalMode] 枚举值解耦）。 */
private fun ToolApprovalMode.label(): String = when (this) {
    ToolApprovalMode.MANUAL -> "手动审批"
    ToolApprovalMode.AUTO -> "自动审批"
    ToolApprovalMode.DISABLED -> "禁用工具"
}

/** 工具权限设置行副标题（UXR3 问题 10）。 */
private fun toolApprovalSubtitle(mode: ToolApprovalMode): String = when (mode) {
    ToolApprovalMode.MANUAL -> "每次调用工具需确认（默认）"
    ToolApprovalMode.AUTO -> "所有工具直接放行"
    ToolApprovalMode.DISABLED -> "不向 AI 提供任何工具"
}

/** 高危操作策略循环顺序：BLOCK → ALLOW → ASK → BLOCK…（点击切换）。 */
private fun nextHighRiskApprovalMode(mode: io.prism.config.HighRiskApprovalMode): io.prism.config.HighRiskApprovalMode =
    when (mode) {
        io.prism.config.HighRiskApprovalMode.BLOCK -> io.prism.config.HighRiskApprovalMode.ALLOW
        io.prism.config.HighRiskApprovalMode.ALLOW -> io.prism.config.HighRiskApprovalMode.ASK
        io.prism.config.HighRiskApprovalMode.ASK -> io.prism.config.HighRiskApprovalMode.BLOCK
    }

/**
 * 工具审批模式弹层（UXR3 问题 10，ADR-023）—— 三选一。
 *
 * - MANUAL（手动审批）：LLM 每次调用工具都询问用户（白名单只读工具免审批）
 * - AUTO（自动审批）：所有工具直接放行，不需用户审核
 * - DISABLED（禁用）：不向 LLM 注入任何工具，AI 无法调用工具
 *
 * 运行时即时生效（无需重启）：ConversationViewModel 与 SkillExecutor 均从配置仓库实时读取。
 */
@Composable
private fun ToolApprovalSheet(
    current: ToolApprovalMode,
    onSelect: (ToolApprovalMode) -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "工具权限",
        subtitle = "控制 AI 调用工具（搜索 / 文件 / MCP）的权限策略"
    ) {
        ToolApprovalOptionRow(
            label = "手动审批",
            description = "每次调用工具都需你确认（推荐）",
            selected = current == ToolApprovalMode.MANUAL,
            onClick = { onSelect(ToolApprovalMode.MANUAL) }
        )
        Spacer(Modifier.height(8.dp))
        ToolApprovalOptionRow(
            label = "自动审批",
            description = "所有工具直接放行，不需审核",
            selected = current == ToolApprovalMode.AUTO,
            onClick = { onSelect(ToolApprovalMode.AUTO) }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = " 自动审批只放行工具调用本身；手机操控里「购买/下单/发送/删除」等高危动作" +
                "仍按上方的「高危操作拦截」处理（默认需确认）。",
            color = PrismWarning,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = " 自动审批下 AI 可直接读写已授权文件、打开应用、联网抓取，请谨慎开启。",
            color = PrismWarning,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        ToolApprovalOptionRow(
            label = "禁用工具",
            description = "不向 AI 提供任何工具，仅普通对话",
            selected = current == ToolApprovalMode.DISABLED,
            onClick = { onSelect(ToolApprovalMode.DISABLED) }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "切换后即时生效，无需重启。",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(12.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/** 工具审批模式选项行（单选样式，选中态高亮，复用 [TierOptionRow] 视觉）。 */
@Composable
private fun ToolApprovalOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrismMint.copy(alpha = 0.08f) else PrismPanel2)
            .border(
                1.dp,
                if (selected) PrismMint.copy(alpha = 0.4f) else PrismLine,
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (selected) PrismMint.copy(alpha = 0.12f) else PrismPanel2,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓" else "◇",
                color = if (selected) PrismMint else PrismTextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = PrismTextFaint, fontSize = 11.sp)
        }
    }
}

/**
 * 深度思考设置弹层（问题 8a，ADR-020）—— 开关 + 思考强度选择。
 *
 * **强度说明**（DeepSeek API 文档）：
 * - low：轻度推理，适合简单任务、快速响应
 * - high：标准推理（默认），效果与延迟平衡
 * - max：最强推理，复杂 Agent 场景推荐（推理 token 占比高，可能压缩正文空间）
 *
 * **兼容性提示**：thinking/reasoning_effort 为 DeepSeek 专有参数，不兼容端点可能返回 400。
 * 弹层内明示该限制，避免用户误用后困惑。
 *
 * @param enabled 当前深度思考开关状态
 * @param effort 当前思考强度（low/high/max）
 * @param onToggle 开关切换回调
 * @param onSelectEffort 强度选择回调（选中后由调用方关闭弹层）
 * @param onClose 关闭按钮回调
 */
@Composable
private fun ThinkingSheet(
    enabled: Boolean,
    effort: String,
    onToggle: (Boolean) -> Unit,
    onSelectEffort: (String) -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "深度思考",
        subtitle = "模型先推理再回答 · 运行时即时生效"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "启用深度思考", color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            PrismSwitch(checked = enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "开启后请求携带 thinking + reasoning_effort 参数（DeepSeek 等支持思考模式的端点）。" +
                "不兼容的端点可能返回 400，请确认你的 Provider 支持。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(text = "思考强度", color = PrismTextDim, fontSize = 12.sp, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = "low",
            description = "轻度推理 · 快速响应",
            selected = effort == "low",
            onClick = { onSelectEffort("low") }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = "high",
            description = "标准推理 · 推荐",
            selected = effort == "high",
            onClick = { onSelectEffort("high") }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = "max",
            description = "最强推理 · 复杂任务",
            selected = effort == "max",
            onClick = { onSelectEffort("max") }
        )
        Spacer(Modifier.height(16.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/**
 * 性能档位选择弹层（US-042）。
 *
 * 列出 5 个选项（自动 + 四档），每个选项显示档位名 + RAM 范围 + 功能简述。
 * 底部固定提示「修改后需重启 App 生效」（ADR-017 4.4：覆盖仅持久化，运行档位需重启才反映）。
 *
 * @param override 当前用户覆盖值（用于标记选中项）
 * @param detectedTier RAM 检测到的档位（用于「自动」选项描述）
 * @param currentTier 当前生效档位（运行中，弹层顶部展示）
 * @param totalRamBytes 设备 RAM 总量（字节，用于弹层副标题展示）
 * @param onSelect 选项点击回调，参数为覆盖值（[TierConfigRepository.OVERRIDE_AUTO] 或档位枚举名）
 * @param onClose 关闭按钮回调
 */
@Composable
private fun TierSheet(
    override: String,
    detectedTier: PerformanceTier,
    currentTier: PerformanceTier,
    totalRamBytes: Long,
    onSelect: (String) -> Unit,
    onClose: () -> Unit
) {
    val ramGb = totalRamBytes.toDouble() / (1024L * 1024L * 1024L)
    val ramStr = String.format(Locale.US, "%.1f", ramGb) + "GB"
    PrismSheet(
        title = "性能档位",
        subtitle = "检测 $ramStr · 当前 ${currentTier.label}"
    ) {
        TierOptionRow(
            label = "自动",
            description = "按 RAM 检测结果（当前 ${detectedTier.label}）",
            selected = override == TierConfigRepository.OVERRIDE_AUTO,
            onClick = { onSelect(TierConfigRepository.OVERRIDE_AUTO) }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = PerformanceTier.FULL.label,
            description = "≥6GB · RAG 标准批次 + 嵌入常驻 + L2 跨会话",
            selected = override == PerformanceTier.FULL.name,
            onClick = { onSelect(PerformanceTier.FULL.name) }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = PerformanceTier.STANDARD.label,
            description = "4-6GB · RAG 小批次 + 嵌入按需 2min 卸载",
            selected = override == PerformanceTier.STANDARD.name,
            onClick = { onSelect(PerformanceTier.STANDARD.name) }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = PerformanceTier.MINIMAL.label,
            description = "3-4GB · 禁用 RAG/L2，嵌入不加载",
            selected = override == PerformanceTier.MINIMAL.name,
            onClick = { onSelect(PerformanceTier.MINIMAL.name) }
        )
        Spacer(Modifier.height(8.dp))
        TierOptionRow(
            label = PerformanceTier.CHAT_ONLY.label,
            description = "<3GB · 仅聊天 + BYOK",
            selected = override == PerformanceTier.CHAT_ONLY.name,
            onClick = { onSelect(PerformanceTier.CHAT_ONLY.name) }
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "修改后需重启 App 生效",
            color = PrismTextFaint,
            fontSize = 11.sp,
            letterSpacing = 0.3.sp
        )
        Spacer(Modifier.height(12.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/** 档位选项行（单选样式，选中态高亮）。 */
@Composable
private fun TierOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrismMint.copy(alpha = 0.08f) else PrismPanel2)
            .border(
                1.dp,
                if (selected) PrismMint.copy(alpha = 0.4f) else PrismLine,
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    if (selected) PrismMint.copy(alpha = 0.12f) else PrismPanel2,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓" else "▣",
                color = if (selected) PrismMint else PrismTextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = PrismTextFaint, fontSize = 11.sp)
        }
    }
}

/** Provider 已配置列表 + 预设添加。 */
@Composable
private fun ProviderListSheet(
    providers: List<ProviderConfig>,
    activeId: Long?,
    onSelect: (ProviderConfig) -> Unit,
    onCreateFromPreset: (ProviderConfig) -> Unit,
    onNewCustom: () -> Unit,
    onClose: () -> Unit
) {
    PrismSheet(
        title = "Provider 配置",
        subtitle = "已配置 ${providers.size} · 点击编辑 / 激活"
    ) {
        if (providers.isEmpty()) {
            Text(text = "尚未配置 Provider，可从下方预设添加。", color = PrismTextFaint, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
        providers.forEach { config ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrismPanel2)
                    .border(1.dp, PrismLine, RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(config) }
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(PrismIndigo.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "◈", color = PrismIndigo, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = config.name, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = config.baseUrl, color = PrismTextFaint, fontSize = 11.sp)
                }
                if (config.id == activeId) {
                    Text(text = "激活", color = PrismMint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(12.dp))
        PrismButton(
            text = "＋ 新建自定义 Provider",
            variant = PrismButtonVariant.Primary,
            onClick = onNewCustom
        )
        Spacer(Modifier.height(12.dp))
        Text(text = "从预设添加", color = PrismTextDim, fontSize = 12.sp, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(8.dp))
        ProviderPresets.all.forEach { preset ->
            val exists = providers.any { it.name.equals(preset.name, ignoreCase = true) }
            PrismButton(
                text = "添加 ${preset.name}",
                variant = PrismButtonVariant.Ghost,
                enabled = !exists,
                onClick = { onCreateFromPreset(preset) }
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/** Provider 详情编辑弹层（config.id=0 为新建自定义模式）。含自定义请求头编辑（US-007）与输入校验（N1）。 */
@Composable
private fun ProviderEditSheet(config: ProviderConfig, viewModel: SettingsViewModel) {
    val isNew = config.id == 0L
    var name by remember(config.id) { mutableStateOf(config.name) }
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl) }
    var models by remember(config.id) { mutableStateOf(config.models.joinToString(", ")) }
    var apiKey by remember(config.id) { mutableStateOf("") }
    var apiKeyLoaded by remember(config.id) { mutableStateOf(false) }
    // BR-naming-002：原变量名 `enabled` 与 PrismButton.enabled 参数同名导致语义混淆（DEF-001 考古报告 §3）。
    // 重命名为 `activateAfterSave` 明确表达「保存后是否激活此 Provider」的意图。
    var activateAfterSave by remember(config.id) { mutableStateOf(config.isActive) }
    // v1 US-301（方案 B 识图）：是否设为「视觉旁路 Provider」——主模型不支持图片时，
    // 图片会发往该 Provider 生成描述（隐私：设置开启前需在「识图」设置中授权）。
    var visionFallback by remember(config.id) { mutableStateOf(config.isVisionFallback) }
    // v1 批次13（B）：该 Provider 是否支持视觉（多模态图片输入）——手机操控截图直接以图片
    // 注入会话供模型查看（发挥多模态），否则走 OCR 文字+坐标。保存时按模型名自动提示。
    var supportsVision by remember(config.id) { mutableStateOf(config.supportsVision) }
    // v1 批次13（B/D16b）：supportsVision 是否被用户**显式触碰**（隐私语义：显式关闭不得被
    // 模型名自动检测覆盖，防截图内容静默外发）。初始取 config.supportsVisionSet 保持跨编辑持久。
    var supportsVisionTouched by remember(config.id) { mutableStateOf(config.supportsVisionSet) }
    // 自定义请求头用 SnapshotStateList：原地改值（headers[index]=…）会触发重组，
    // 与删除/新增重建列表行为一致，避免丢输入（guardrail Q2）。
    val headers = remember(config.id) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(config.headers.entries.map { it.key to it.value })
        }
    }
    var showValidation by remember(config.id) { mutableStateOf(false) }
    // guardrail L-28：保存被安全策略拒绝时的可读错误（如远端明文 http）
    val saveError by viewModel.providerSaveError.collectAsState()

    // 仅首次进入弹层时回显已存 Key，避免重组覆盖用户输入（与 ApiKeySheet 守卫一致）。
    // **guardrail R3**：原写法在组合体里 `if (!apiKeyLoaded) loadApiKey(...)`，每次重组都会
    // 重新判定、且首帧就发起异步读——改为 LaunchedEffect(key) 只跑一次（同 ApiKeySheet/VoiceKeyField）。
    LaunchedEffect(config.apiKeyRef) {
        viewModel.loadApiKey(config.apiKeyRef) { loaded ->
            if (loaded != null) apiKey = loaded
            apiKeyLoaded = true
        }
    }

    // 输入校验（N1）：名称非空 + Base URL 为合法 http(s) 地址。
    // CRLF 纵深防御（guardrail S1）：baseUrl / header key/value 拒绝含 \r\n 的输入，
    // 防止经 buildEndpoint / applyCustomHeaders 注入首部（OkHttp 引擎虽已 fail-closed，此处兜底）。
    val nameValid = name.trim().isNotEmpty()
    val baseUrlTrimmed = baseUrl.trim()
    val urlValid = (baseUrlTrimmed.startsWith("http://") || baseUrlTrimmed.startsWith("https://")) &&
        io.prism.network.PrivateNetwork.isCleartextAllowed(baseUrlTrimmed)
    val urlSafe = urlValid && !baseUrlTrimmed.contains('\r') && !baseUrlTrimmed.contains('\n')
    val canSave = nameValid && urlValid && urlSafe
    val validHeaders = headers
        .map { it.first.trim() to it.second.trim() }
        .filter { it.first.isNotEmpty() && !it.first.contains('\r') && !it.first.contains('\n') && !it.second.contains('\r') && !it.second.contains('\n') }

    PrismSheet(
        title = if (isNew) "新建 Provider" else "编辑 ${config.name}",
        subtitle = "端点 · 模型 · 请求头 · 激活",
        // BR-ui-003：保存配置按钮放在 footer 固定底部，确保始终可见可点击（DEF-001 根因修复）。
        footer = {
            PrismButton(
                text = "保存配置",
                enabled = canSave,
                onClick = {
                    if (!canSave) { showValidation = true; return@PrismButton }
                    viewModel.saveApiKey(config.apiKeyRef, apiKey)
                    // 激活态绝不直接写入 isActive（会绕过单激活不变式），统一经 setActive 事务处理
                    val savedId = viewModel.saveProvider(
                        config.copy(
                            name = name.trim().ifEmpty { config.name },
                            baseUrl = baseUrl.trim(),
                            models = models.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            headers = validHeaders.toMap(),
                            isActive = false,
                            isVisionFallback = visionFallback,
                            // v1 批次13（B）：多模态开关（保存时若用户未显式改过，可经模型名自动提示——
                            // 此处取 UI 当前值 + 是否被显式触碰；SettingsViewModel.saveProvider 内做自动提示合并）
                            supportsVision = supportsVision,
                            supportsVisionSet = supportsVisionTouched
                        )
                    )
                    // savedId=null 表示被安全策略拒绝（错误文案经 providerSaveError 展示）
                    if (savedId != null && activateAfterSave) {
                        viewModel.setActive(savedId)
                    }
                }
            )
            if (saveError != null) {
                Spacer(Modifier.height(8.dp))
                ValidationError(saveError!!)
            }
            if (showValidation && !canSave) {
                Spacer(Modifier.height(8.dp))
                ValidationError("请修正以上无效输入后再保存")
            }
        }
    ) {
        PrismField(label = "名称", value = name, onValueChange = { name = it }, placeholder = "自定义 Provider 名称")
        if (showValidation && !nameValid) {
            ValidationError("名称不能为空")
        }
        Spacer(Modifier.height(16.dp))
        PrismField(label = "Base URL", value = baseUrl, onValueChange = { baseUrl = it }, placeholder = "https://…")
        if (showValidation && !urlValid) {
            ValidationError("Base URL 需以 http:// 或 https:// 开头")
        }
        Spacer(Modifier.height(16.dp))
        PrismField(label = "模型（逗号分隔）", value = models, onValueChange = { models = it })
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "API Key",
            value = apiKey,
            onValueChange = { apiKey = it },
            placeholder = "sk-…",
            secret = true,
            hint = "Keystore 加密存储 · 明文仅在内存短暂存在"
        )
        Spacer(Modifier.height(20.dp))

        // 自定义请求头编辑器（US-007，上轮决策纳入）
        Text(text = "自定义请求头", color = PrismTextDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(8.dp))
        if (headers.isEmpty()) {
            Text(text = "无需额外请求头，通常由 Provider 自动填充。", color = PrismTextFaint, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
        }
        headers.forEachIndexed { index, (k, v) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrismField(
                    label = null,
                    value = k,
                    onValueChange = { headers[index] = it to v },
                    placeholder = "Header 名（如 X-API-Key）",
                    modifier = Modifier.weight(1f)
                )
                PrismField(
                    label = null,
                    value = v,
                    onValueChange = { headers[index] = k to it },
                    placeholder = "值",
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrismPanel2)
                        .border(1.dp, PrismLine, RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { headers.removeAt(index) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "✕", color = PrismTextDim, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        PrismButton(
            text = "＋ 添加请求头",
            variant = PrismButtonVariant.Ghost,
            onClick = { headers.add("" to "") }
        )
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "设为激活 Provider", color = PrismText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            PrismSwitch(checked = activateAfterSave, onCheckedChange = { activateAfterSave = it })
        }
        Spacer(Modifier.height(12.dp))
        // v1 US-301（方案 B 识图）：视觉旁路 Provider 开关（主模型不支持图片时，图片发往该
        // Provider 生成描述。需在「识图」设置中授权后方可自动触发，隐私提示见下）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = "设为视觉旁路 Provider", color = PrismText, fontSize = 14.sp)
                Text(
                    text = "主模型不支持图片时，图片将发送到此视觉模型生成描述（需在「识图」设置授权）",
                    color = PrismTextFaint, fontSize = 10.sp
                )
            }
            PrismSwitch(checked = visionFallback, onCheckedChange = { visionFallback = it })
        }
        Spacer(Modifier.height(12.dp))
        // v1 批次13（B）：支持视觉（多模态）开关 —— 手机操控截图直接以图片注入会话供模型查看，
        // 否则走 OCR 文字+坐标。保存时按模型名自动提示（detectVisionSupport）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text = "支持视觉（多模态）", color = PrismText, fontSize = 14.sp)
                Text(
                    text = "手机操控截图将直接以图片发送给该模型查看（需模型支持图片输入），否则用 OCR 文字+坐标",
                    color = PrismTextFaint, fontSize = 10.sp
                )
                // 2026-10-26 真机反馈：用户配置 deepseek-v4-flash（实际支持多模态），
                // 但开关显示"关"，因为 supportsVision 存的是"用户显式选择"，
                // 而运行时是 `显式 || 模型名自动判定`。这里把**实际生效**的结论显式写出来，
                // 否则用户会以为"我明明支持视觉，为什么显示 false"。
                val autoDetected = remember(models, supportsVisionTouched) {
                    !supportsVisionTouched &&
                        models.split(",").any {
                            io.prism.data.ProviderConfig.detectVisionSupport(it.trim())
                        }
                }
                if (autoDetected && !supportsVision) {
                    Text(
                        text = "ℹ 已按模型名自动判定为支持视觉（当前实际生效：开）；" +
                            "打开此开关可固化为显式设置",
                        color = PrismIndigo, fontSize = 10.sp
                    )
                }
            }
            PrismSwitch(
                checked = supportsVision,
                onCheckedChange = {
                    supportsVision = it
                    // 用户触碰开关 → 标记显式设置（保存时不再被模型名自动检测覆盖）
                    supportsVisionTouched = true
                }
            )
        }
        Spacer(Modifier.height(12.dp))
        PrismButton(
            text = "激活",
            variant = PrismButtonVariant.Ghost,
            enabled = !isNew && !activateAfterSave,
            onClick = { viewModel.setActive(config.id) }
        )
        if (!isNew) {
            Spacer(Modifier.height(12.dp))
            PrismButton(
                text = "删除 Provider",
                variant = PrismButtonVariant.Danger,
                onClick = { viewModel.deleteProvider(config) }
            )
        }
    }
}

/** 校验错误提示（薄荷红系警示文案）。 */
@Composable
private fun ValidationError(text: String) {
    Text(
        text = text,
        color = PrismDanger,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** API Key 管理弹层。 */
@Composable
private fun ApiKeySheet(providers: List<ProviderConfig>, viewModel: SettingsViewModel) {
    PrismSheet(
        title = "API Key",
        subtitle = "Keystore 加密 · 明文不落盘"
    ) {
        if (providers.isEmpty()) {
            Text(text = "请先在 Provider 配置中添加 Provider。", color = PrismTextFaint, fontSize = 12.sp)
        }
        providers.forEach { config ->
            var key by remember(config.id) { mutableStateOf("") }
            var loaded by remember(config.id) { mutableStateOf(false) }

            // **guardrail R2-6（B2）**：原实现在组合体里直接 `loadApiKey`（异步）+ 保存按钮
            // 不等回填就可用 —— 弹层一打开立刻点「保存」时 `key` 还是空串，
            // 按 BR-security-006 的"空值 = 删除"语义会**静默删掉已存 Key**。
            // 与 [VoiceKeyField] / 搜索弹层（R5-M-02）同族，必须一并门控。
            LaunchedEffect(config.apiKeyRef) {
                viewModel.loadApiKey(config.apiKeyRef) { v ->
                    key = v.orEmpty()
                    loaded = true
                }
            }

            PrismField(
                label = config.name,
                value = key,
                onValueChange = { key = it },
                placeholder = if (key.isEmpty()) "未设置 API Key" else "",
                secret = true,
                hint = if (loaded) "加密保存到 DataStore" else "读取中…"
            )
            Spacer(Modifier.height(8.dp))
            PrismButton(
                text = if (loaded) "保存 ${config.name} Key" else "读取中…",
                variant = PrismButtonVariant.Ghost,
                enabled = loaded,
                onClick = {
                    // BR-security-006：空值时删除已存密钥，非空时加密保存。
                    // 修复 guardrail-enforcer B-2：原实现空值时 saveApiKey 静默跳过，
                    // 导致用户清空输入框后保存无法清除已存密钥。
                    if (key.isEmpty()) {
                        viewModel.removeApiKey(config.apiKeyRef)
                    } else {
                        viewModel.saveApiKey(config.apiKeyRef, key)
                    }
                }
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 读取本应用 versionName（失败时退回 "1.1"，不抛异常影响设置页渲染）。 */
private fun appVersionName(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.1"

/**
 * 本应用安装/更新时间（`yyyy-MM-dd`），失败返回 null（调用方据此省略这一段，不写死日期）。
 *
 * 设计稿的「1.1 · 2026-11-20」是原型伪值；用户 2026-11-21 裁定用**真实**时间。
 */
private fun appInstallDate(context: android.content.Context): String? =
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val millis = if (info.lastUpdateTime > 0) info.lastUpdateTime else info.firstInstallTime
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(millis))
    }.getOrNull()

/** 首选搜索引擎的取值（空串 = 跟随默认顺序；顺序与引擎链一致）。 */
private val PREFERRED_ENGINE_VALUES = listOf("", "bocha", "zhipu", "searxng", "tavily")

/** 首选搜索引擎的显示名（与 [PREFERRED_ENGINE_VALUES] 一一对应）。 */
private val PREFERRED_ENGINE_LABELS = listOf("默认顺序", "博查", "智谱", "SearXNG", "Tavily")

/** 首选引擎在选项中的下标（未知值退回 0 = 默认顺序）。 */
private fun preferredEngineIndex(value: String): Int =
    PREFERRED_ENGINE_VALUES.indexOf(value).takeIf { it >= 0 } ?: 0

/** 首选引擎行的一行状态（设计稿「首选 Tavily」的写法）。 */
private fun preferredEngineLabel(value: String): String =
    PREFERRED_ENGINE_LABELS[preferredEngineIndex(value)]

/** 分组标题（UI v2：小字、无字距、弱化）。 */
@Composable
private fun SetSection(title: String) {
    // 设计稿 `.group-label`：12sp faint、组上间距 24dp、标签下 8dp。
    Text(
        text = title,
        color = PrismTextFaint,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
    )
}

/**
 * 设置首页 —— 6 个任务入口 + 一行运行状态（ADR-079 D2）。
 *
 * 只回答「我要改哪一类」，不解释功能；每个入口的状态行是**当前真实值**，
 * 让用户不进去也知道现状。47 个设置项全部在详情页，这里一项都不放。
 */
@Composable
private fun SettingsHome(
    onOpenSection: (SettingsSection) -> Unit,
    viewModel: SettingsViewModel
) {
    val providers by viewModel.providers.collectAsState()
    val activeProvider by viewModel.activeProvider.collectAsState()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val visionConsent by viewModel.visionConsent.collectAsState()
    val phoneControlConnected by viewModel.phoneControlConnected.collectAsState()
    val tierViewModel: TierViewModel = viewModel(factory = TierViewModel.Factory)
    val tierOverride by tierViewModel.override.collectAsState()
    val context = LocalContext.current
    val qqApp = context.applicationContext as? io.prism.PrismApplication
    val qqConfig by (qqApp?.qqConfigRepository?.config()
        ?: kotlinx.coroutines.flow.flowOf(io.prism.qq.QqConfig.DEFAULT))
        .collectAsState(initial = io.prism.qq.QqConfig.DEFAULT)

    Column(modifier = Modifier.fillMaxSize()) {
        PrismTopBar(title = "设置", subtitle = null)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                SettingsStatusCard(
                    providerName = activeProvider?.name,
                    qqEnabled = qqConfig.enabled,
                    phoneReady = phoneControlConnected,
                    providerCount = providers.size
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    SettingsNavRow(
                        title = SettingsSection.MODEL.title,
                        status = buildString {
                            append(activeProvider?.name ?: "未配置端点")
                            append(" · ")
                            append(providers.size)
                            append(" 个端点")
                        }
                    ) { onOpenSection(SettingsSection.MODEL) }
                    SettingsNavRow(
                        title = SettingsSection.BEHAVIOR.title,
                        status = buildString {
                            append(if (thinkingEnabled) "深度思考开" else "深度思考关")
                            append(" · ")
                            append(if (visionConsent) "视觉旁路已授权" else "视觉旁路未授权")
                        }
                    ) { onOpenSection(SettingsSection.BEHAVIOR) }
                    SettingsNavRow(
                        title = SettingsSection.PHONE.title,
                        status = if (phoneControlConnected) "无障碍已开启" else "无障碍未开启",
                        tone = if (phoneControlConnected) PrismOk else PrismWarning
                    ) { onOpenSection(SettingsSection.PHONE) }
                    SettingsNavRow(
                        title = SettingsSection.QQ.title,
                        status = if (qqConfig.enabled) "已开启" else "已关闭",
                        tone = if (qqConfig.enabled) PrismOk else null
                    ) { onOpenSection(SettingsSection.QQ) }
                    SettingsNavRow(
                        title = SettingsSection.VOICE.title,
                        status = "语音引擎 · 角色包 · 桌宠"
                    ) { onOpenSection(SettingsSection.VOICE) }
                    SettingsNavRow(
                        title = SettingsSection.SYSTEM.title,
                        status = tierSubtitle(
                            override = tierOverride,
                            detectedTier = tierViewModel.detectedTier,
                            currentTier = tierViewModel.currentTier,
                            totalRamBytes = tierViewModel.totalRamBytes
                        )
                    ) { onOpenSection(SettingsSection.SYSTEM) }
                }
            }
            item {
                Text(
                    text = "Prism 1.1 · Apache 2.0",
                    color = PrismTextFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp)
                )
            }
        }
    }
}

/** 运行状态卡：一句话结论 + 两个计数，不堆指标。 */
@Composable
private fun SettingsStatusCard(
    providerName: String?,
    qqEnabled: Boolean,
    phoneReady: Boolean,
    providerCount: Int
) {
    val ok = providerName != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .background(PrismPanel, RoundedCornerShape(14.dp))
            .border(1.dp, PrismLine, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .background(if (ok) PrismOk else PrismWarning, CircleShape)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (ok) "运行正常" else "还没有可用端点",
                color = PrismText,
                fontSize = 15.sp
            )
            Text(
                text = buildString {
                    append(providerName ?: "未配置")
                    append(" · ")
                    // M-02（guardrail）：不伪造知识库数量；知识库信息归知识页。
                    append(if (qqEnabled) "QQ 开" else "QQ 关")
                    append(" · ")
                    append(if (phoneReady) "无障碍开" else "无障碍关")
                },
                color = PrismTextFaint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        if (providerCount > 1) {
            Text(
                text = "$providerCount 个端点",
                color = PrismTextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 设置首页导航行：标题 + 一行状态 + 箭头，无图标。 */
@Composable
private fun SettingsNavRow(
    title: String,
    status: String,
    tone: Color? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = PrismText, fontSize = 15.sp)
            Text(
                text = status,
                color = tone ?: PrismTextFaint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PrismTextFaint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 详情页顶栏：返回 + 页面名（居中），右侧留 36dp 保持标题居中。 */
@Composable
private fun SettingsDetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = PrismTextDim,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = title,
            color = PrismText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.size(36.dp))
    }
}

/**
 * 设置行 —— 设计稿 `.row`（`design/prism-ui-v1/app.css`）：标题 15sp + 状态 12sp faint，
 * 尾部控件，底边 1px 分隔线，最小高 56dp，**无卡片、无图标**。
 *
 * **状态固定单行**（`statusMaxLines = 1`）：设计稿 `.row-status` 是 `nowrap + ellipsis`，
 * 原实现 2 行会把 56dp 行撑到 100px 以上、整页变文字墙（2026-11-21 真机比对确认）。
 * 实现细节因此必须移出状态行（子页 / 弹层 / 仅异常时的一行提示），见 ADR-079 D3。
 */
@Composable
private fun SetRow(
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    // 设计稿 `.row` 位于 `main.scroll`（左右 20dp）之内，分隔线随之**内缩**而非通栏。
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        PrismRow(
            title = title,
            status = subtitle.takeIf { it.isNotBlank() },
            onClick = onClick,
            statusMaxLines = 1,
            trailing = {
                trailing?.invoke()
                if (onClick != null) {
                    Icon(
                        imageVector = PrismIcons.ChevronRight,
                        contentDescription = null,
                        tint = PrismTextFaint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        )
    }
}

/**
 * 用户规则编辑弹层（UXR8 N1，ADR-030）—— 类 ChatGPT Custom Instructions 双字段。
 *
 * - 「关于我」：身份/背景/场景（如"我是 Python 开发者，主要做后端"）
 * - 「如何回答」：语气/格式/禁忌（如"回答用中文，简洁，先给结论再解释"）
 *
 * 两字段分别 ≤ [io.prism.config.UserRulesRepository.MAX_RULE_LEN] 字符，本地先截断
 * （纵深防御，仓库层 fail-fast 兜底）。保存后下一轮对话生效（最高优先级约束 LLM）。
 *
 * @param aboutMe 当前「关于我」内容
 * @param howToRespond 当前「如何回答」内容
 * @param onSave 保存回调（aboutMe, howToRespond）
 * @param onClose 关闭按钮回调
 */
@Composable
private fun UserRulesSheet(
    aboutMe: String,
    howToRespond: String,
    onSave: (String, String) -> Unit,
    onClose: () -> Unit
) {
    val maxLen = io.prism.config.UserRulesRepository.MAX_RULE_LEN
    var about by remember { mutableStateOf(aboutMe) }
    var respond by remember { mutableStateOf(howToRespond) }
    PrismSheet(
        title = "AI 行为偏好",
        subtitle = "用户规则 · 除安全限制外最高优先级"
    ) {
        Text(
            text = "类 ChatGPT Custom Instructions：告诉 AI 关于你的背景与回复偏好，每轮对话自动生效。",
            color = PrismTextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(16.dp))
        PrismField(
            label = "关于我",
            value = about,
            onValueChange = { about = it.take(maxLen) },
            placeholder = "如：我是 Python 开发者，主要做后端服务"
        )
        Spacer(Modifier.height(8.dp))
        PrismField(
            label = "如何回答",
            value = respond,
            onValueChange = { respond = it.take(maxLen) },
            placeholder = "如：用中文，简洁，先给结论再解释"
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "每字段最多 $maxLen 字符 · 本地存储 · 仅发送到你自配的模型端点",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(20.dp))
        PrismButton(text = "保存", onClick = { onSave(about, respond) })
        Spacer(Modifier.height(8.dp))
        PrismButton(text = "关闭", variant = PrismButtonVariant.Ghost, onClick = onClose)
    }
}

/**
 * 搜索引擎增强配置弹层（v1 批次15，US-1501/1502/1507）。
 *
 * - **智谱 API Key**（apiKeyRef=zhipu）：到 bigmodel.cn 注册获取；Keystore 加密存储。
 * - **Tavily API Key**（apiKeyRef=tavily）：到 tavily.com 注册获取；Keystore 加密存储。
 * - **SearXNG 端点**：用户自建服务地址（http/https），可选 Basic Auth 用户名/密码；
 *   部署教程见 docs/runbooks/searxng-selfhost.md。端点为用户显式配置，可达性由用户自担。
 *
 * Key 保存语义与 [ApiKeySheet] 一致（BR-security-006：清空保存 = 删除已存密钥）；
 * SearXNG 端点填空保存 = 清除配置（引擎链完全跳过该引擎）。
 *
 * @param searxngEndpoint 当前 SearXNG 端点（空 = 未配置）
 * @param searxngUsername 当前 Basic Auth 用户名
 * @param searxngPassword 当前 Basic Auth 密码
 * @param viewModel 设置 ViewModel（Key/SearXNG 读写）
 * @param onClose 关闭按钮回调
 */
@Composable
private fun SearchEnhancementSheet(
    searxngEndpoint: String,
    searxngUsername: String,
    searxngPassword: String,
    viewModel: SettingsViewModel,
    onClose: () -> Unit
) {
    var zhipuKey by remember { mutableStateOf("") }
    var zhipuLoaded by remember { mutableStateOf(false) }
    var tavilyKey by remember { mutableStateOf("") }
    var tavilyLoaded by remember { mutableStateOf(false) }
    var endpoint by remember(searxngEndpoint) { mutableStateOf(searxngEndpoint) }
    var username by remember(searxngUsername) { mutableStateOf(searxngUsername) }
    var password by remember(searxngPassword) { mutableStateOf(searxngPassword) }

    // **guardrail R5-M-02**：两次 loadApiKey 原本写在组合体里（每次重组都判定一次），
    // 且保存按钮不等它们完成就可用——「打开弹层立刻点保存」会在 Key 尚未回填时把空串
    // 当作"清空"，静默删掉已存 Key。改为 LaunchedEffect（只跑一次）+ 保存按钮以 loaded 状态门控。
    LaunchedEffect(Unit) {
        viewModel.loadApiKey(SettingsViewModel.ZHIPU_API_KEY_REF) { v ->
            zhipuKey = v.orEmpty()
            zhipuLoaded = true
        }
    }
    LaunchedEffect(Unit) {
        viewModel.loadApiKey(SettingsViewModel.TAVILY_API_KEY_REF) { v ->
            tavilyKey = v.orEmpty()
            tavilyLoaded = true
        }
    }
    val keysReady = zhipuLoaded && tavilyLoaded
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // ===== 严格按设计稿 `search`（`screens.js:1146`）排列 =====
    //
    // 设计稿结构：标题 + 副标题 + **三个字段直接排列**（Tavily Key / 智谱 Key / SearXNG 端点）
    // + 一句 `.note-line` + 底部 `.btn-row`（保存 / 取消）。弹层里**不再有第四组控件**。
    //
    // 「首选引擎」（US-1509）已上移到系统页的独立行（点击开 5 选 1 弹层），
    // 因此这里不再有「高级」组；SearXNG 的 Basic Auth 仅在填写了端点时出现
    // （自建实例的实际需要，且默认视图与设计稿一致）。"保存"一次写全部三项。
    PrismSheet(
        title = "搜索引擎增强",
        subtitle = "按 博查 → 智谱 → SearXNG → Tavily → Bing/Baidu 顺序尝试"
    ) {
        PrismField(
            label = "Tavily API Key",
            value = tavilyKey,
            onValueChange = { tavilyKey = it },
            secret = true
        )
        Spacer(Modifier.height(PrismSize.s2))
        PrismField(
            label = "智谱 API Key",
            value = zhipuKey,
            onValueChange = { zhipuKey = it },
            secret = true
        )
        Spacer(Modifier.height(PrismSize.s2))
        PrismField(
            label = "SearXNG 端点",
            value = endpoint,
            onValueChange = { endpoint = it },
            placeholder = "如 http://192.168.1.10:8080"
        )
        // 设计稿正文最后一句
        PrismNoteLine("端点留空保存即清除配置；自建服务可达性由你自担。")

        // SearXNG Basic Auth：仅在填写端点后出现（用同一弹层的"保存"一次写入）
        if (endpoint.isNotBlank()) {
            Spacer(Modifier.height(PrismSize.s3))
            PrismField(
                label = "SearXNG 用户名（可选 Basic Auth）",
                value = username,
                onValueChange = { username = it },
                placeholder = "未启用认证可留空"
            )
            Spacer(Modifier.height(PrismSize.s2))
            PrismField(
                label = "SearXNG 密码（可选 Basic Auth）",
                value = password,
                onValueChange = { password = it },
                placeholder = "未启用认证可留空",
                secret = true
            )
        }

        if (saveError != null) {
            Spacer(Modifier.height(PrismSize.s3))
            Text(
                text = "保存失败：$saveError",
                color = PrismDanger,
                fontSize = PrismType.meta,
                lineHeight = PrismType.metaLine
            )
        }

        Spacer(Modifier.height(PrismSize.s5))
        // 设计稿 `.btn-row`：保存 / 取消 **并排**，一次写全部三项
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PrismSize.s2)
        ) {
            PrismButton(
                // 未加载完成前不可点（否则空串=删除，静默丢 Key）
                text = when {
                    saving -> "保存中…"
                    !keysReady -> "读取中…"
                    else -> "保存"
                },
                enabled = keysReady && !saving,
                fillWidth = false,
                modifier = Modifier.weight(1f),
                onClick = {
                    saving = true
                    saveError = null
                    // 一次写入全部三项（任一步失败 → 不关弹层 + 显示错误）
                    viewModel.saveSearchEnhancementAll(
                        tavilyKey = tavilyKey,
                        zhipuKey = zhipuKey,
                        searxngEndpoint = endpoint,
                        searxngUsername = username,
                        searxngPassword = password
                    ) { error ->
                        saving = false
                        if (error == null) onClose() else saveError = error
                    }
                }
            )
            PrismButton(
                text = "取消",
                variant = PrismButtonVariant.Ghost,
                fillWidth = false,
                modifier = Modifier.weight(1f),
                onClick = onClose
            )
        }
    }
}
/**
 * 批次22 修复轮五（2026-09-07，SZ-201 同音字优化）：语音热词编辑区。
 *
 * 用户维护"人名/专有名词"热词表（每行一个），保存到 filesDir/voice_hotwords.txt；
 * SherpaAsrEngine 下次启动识别循环时以 modified_beam_search + hotwordsScore=2.0 偏置解码
 *（sherpa-onnx 官方 contextual biasing，greedy_search 不支持热词）。
 * 行业实践：个性化热词是同音字/专名识别优化的标准手段（讯飞/会议转写均提供用户词库）。
 */
@Composable
fun VoiceHotwordsEditor() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val file = java.io.File(context.filesDir, io.prism.voice.VOICE_HOTWORDS_FILE)
    var text by remember { mutableStateOf("") }
    var hotwordCount by remember { mutableStateOf(0) }
    // 批次22 修复轮六：默认**收缩态**（已有热词时折叠为一行摘要，点击展开编辑——与其他
    // 设置行形态一致；无热词时直接展开引导填写）。保存后自动收缩。
    var expanded by remember { mutableStateOf(false) }

    // 进入时读取现有热词文件（IO 线程）
    LaunchedEffect(Unit) {
        val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        }
        text = content
        hotwordCount = content.lines().count { it.isNotBlank() }
        expanded = hotwordCount == 0  // 无热词时展开引导；有热词时收缩
    }

    if (!expanded) {
        SetRow(
            title = "语音热词",
            subtitle = if (hotwordCount > 0) {
                "已配置 $hotwordCount 个 · 识别时加权提升同音字区分 · 点此编辑"
            } else {
                "未配置 · 添加人名/专有名词可提升同音字识别 · 点此编辑"
            },
            onClick = { expanded = true }
        )
        return
    }

    // M-01a（guardrail R5）：容器横向改为 **20dp**（设计稿 page padding），
    // 与 SetSection 的 20dp 同源；此前是 16dp，导致「播报」标签比「识别」左 4dp。
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        androidx.compose.material3.Text(
            "语音热词（每行一个：人名/联系人/专有名词）",
            color = PrismTextDim,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                androidx.compose.material3.Text("张三\n某公司简称…", color = PrismTextFaint, fontSize = 13.sp)
            },
            minLines = 3,
            maxLines = 6,
            textStyle = androidx.compose.ui.text.TextStyle(color = PrismText, fontSize = 14.sp)
        )
        Spacer(Modifier.height(8.dp))
        //  BR-ui-003 ② / BUG-20260924-001 同类漏网点：同排两个 PrismButton 必须各给
        // `weight(1f)` 并关掉组件内部的 `fillMaxWidth()`，否则**第一个吃满整行、第二个被压成 0 宽**
        // （独立审查 H-1：本行改造前"取消"按钮实际不可见/不可点）。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismButton(
                text = "保存",
                variant = PrismButtonVariant.Primary,
                fillWidth = false,
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            if (text.isBlank()) file.delete() else file.writeText(text)
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            hotwordCount = text.lines().count { it.isNotBlank() }
                            expanded = false  // 保存后收缩
                        }
                    }
                }
            )
            PrismButton(
                text = "取消",
                variant = PrismButtonVariant.Ghost,
                fillWidth = false,
                modifier = Modifier.weight(1f),
                onClick = { expanded = false }
            )
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.Text(
            "保存后下次语音通话生效；识别时将加权匹配这些词，提升同音字区分",
            color = PrismTextFaint,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun VoiceEngineSection(
    viewModel: SettingsViewModel,
    onOpenPersona: () -> Unit,
    onOpenVoicePacks: () -> Unit
) {
    val asrChoice by viewModel.voiceAsrEngine.collectAsState()
    val ttsChoice by viewModel.voiceTtsEngine.collectAsState()
    val dashScopeReady by viewModel.dashScopeKeyConfigured.collectAsState()
    val minimaxKeyReady by viewModel.minimaxKeyConfigured.collectAsState()
    val minimaxGroupReady by viewModel.minimaxGroupConfigured.collectAsState()
    val asrCheck by viewModel.asrSelfCheck.collectAsState()
    val ttsCheck by viewModel.ttsSelfCheck.collectAsState()
    // LVC-1（ADR-053）：本地音色包安装状态（null = 尚未检测，与"未安装"区分呈现）
    val localVoiceStatus by viewModel.localVoiceStatus.collectAsState()
    // TKN-LVC-016（ADR-056/057）：档位、播报模式、已安装音色包
    val localTier by viewModel.localVoiceTier.collectAsState()
    val playbackMode by viewModel.localPlaybackMode.collectAsState()
    val installedPacks by viewModel.localVoicePacks.collectAsState()
    val effectiveVoiceId by viewModel.effectiveLocalVoiceId.collectAsState()
    // 角色组两行的状态来自真实数据（与子页共用同一份 VM 状态）
    val rolePacks by viewModel.rolePacks.collectAsState()
    val selectedRolePackId by viewModel.selectedRolePackId.collectAsState()
    val petConfig by viewModel.petConfig.collectAsState()
    val petRuntime by viewModel.petRuntime.collectAsState()

    // 页面常驻（设计稿无折叠）：进入即检测一次本地音色状态与音色包列表
    LaunchedEffect(Unit) {
        viewModel.refreshLocalVoiceStatus()
        viewModel.refreshLocalVoicePacks()
    }

    val activePackName = installedPacks.firstOrNull { it.voiceId == effectiveVoiceId }?.name
        ?: effectiveVoiceId
    val rolePackStatus = when {
        rolePacks.isEmpty() -> "未导入 · 使用出厂默认助手"
        else -> {
            val name = rolePacks.firstOrNull { it.id == selectedRolePackId }?.name
            "已导入 ${rolePacks.size} 个" + (if (name != null) " · 当前：$name" else "")
        }
    }
    val petStatus = when {
        !petConfig.enabled -> "已关闭"
        petRuntime == io.prism.pet.PetManager.RuntimeState.RUNNING -> "已开启 · 桌面显示中"
        petRuntime == io.prism.pet.PetManager.RuntimeState.FAILED -> "启动失败 · 点此查看"
        else -> "已开启 · 等待生效"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ===== 设计稿 `settingsVoice()` 组①：识别 =====
        SetSection("识别")
        VoiceEngineOptionRow(
            label = "端侧识别（默认）",
            description = "完全离线 · 音频不出设备",
            selected = asrChoice == io.prism.voice.AsrEngineChoice.SYSTEM,
            onClick = { viewModel.setVoiceAsrEngine(io.prism.voice.AsrEngineChoice.SYSTEM) }
        )
        VoiceEngineOptionRow(
            label = "云端识别 · 阿里云百炼",
            description = if (dashScopeReady) {
                "Paraformer 实时流式 · 音频实时推流到阿里云"
            } else {
                "需先配置并保存 DashScope API Key"
            },
            selected = asrChoice == io.prism.voice.AsrEngineChoice.ALI_CLOUD,
            enabled = dashScopeReady,
            onClick = { viewModel.setVoiceAsrEngine(io.prism.voice.AsrEngineChoice.ALI_CLOUD) }
        )
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s2)) {
            VoiceKeyField(
                label = "DashScope API Key",
                placeholder = "到阿里云百炼控制台获取",
                keyRef = io.prism.voice.cloud.AliCloudAsrEngine.KEY_REF,
                secret = true,
                viewModel = viewModel
            )
            PrismActionsRow {
                PrismMini(
                    text = if (asrCheck?.running == true) "自检中…" else "自检",
                    onClick = { viewModel.runAsrSelfCheck() }
                )
            }
            asrCheck?.let { check ->
                Text(
                    text = "识别自检：${check.message}",
                    color = voiceSelfCheckColor(check.message),
                    fontSize = PrismType.micro,
                    lineHeight = PrismType.microLine,
                    modifier = Modifier.padding(top = PrismSize.s2)
                )
            }
        }

        // ===== 组②：播报 =====
        SetSection("播报")
        VoiceEngineOptionRow(
            label = "系统播报（默认）",
            description = "零依赖 · 本机 TTS",
            selected = ttsChoice == io.prism.voice.TtsEngineChoice.SYSTEM,
            onClick = { viewModel.setVoiceTtsEngine(io.prism.voice.TtsEngineChoice.SYSTEM) }
        )
        VoiceEngineOptionRow(
            label = "云端播报 · MiniMax",
            description = if (minimaxKeyReady && minimaxGroupReady) {
                "T2A v2 流式合成 · 音色/语速可由角色包指定"
            } else {
                "需先配置 MiniMax Key 与 GroupId"
            },
            selected = ttsChoice == io.prism.voice.TtsEngineChoice.MINIMAX,
            enabled = minimaxKeyReady && minimaxGroupReady,
            onClick = { viewModel.setVoiceTtsEngine(io.prism.voice.TtsEngineChoice.MINIMAX) }
        )
        VoiceEngineOptionRow(
            label = "本地音色 · 离线",
            description = localVoiceDescription(localVoiceStatus),
            selected = ttsChoice == io.prism.voice.TtsEngineChoice.LOCAL_CLONE,
            enabled = isLocalVoiceSelectable(localVoiceStatus),
            onClick = { viewModel.setVoiceTtsEngine(io.prism.voice.TtsEngineChoice.LOCAL_CLONE) }
        )
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s2)) {
            VoiceKeyField(
                label = "MiniMax API Key",
                placeholder = "到 MiniMax 开放平台获取",
                keyRef = io.prism.voice.cloud.MiniMaxTtsConfig.KEY_API_KEY,
                secret = true,
                viewModel = viewModel
            )
            VoiceKeyField(
                label = "MiniMax GroupId",
                placeholder = "MiniMax 开放平台账号信息页查看",
                keyRef = io.prism.voice.cloud.MiniMaxTtsConfig.KEY_GROUP_ID,
                secret = true,
                viewModel = viewModel
            )
            PrismActionsRow {
                PrismMini(
                    text = if (ttsCheck?.running == true) "自检中…" else "自检",
                    onClick = { viewModel.runTtsSelfCheck() }
                )
            }
            ttsCheck?.let { check ->
                Text(
                    text = "播报自检：${check.message}",
                    color = voiceSelfCheckColor(check.message),
                    fontSize = PrismType.micro,
                    lineHeight = PrismType.microLine,
                    modifier = Modifier.padding(top = PrismSize.s2)
                )
            }
        }
        // 设计稿：档位 / 播报方式 / 热词都是**一行**（点击切换或编辑），不铺成多行单选。
        SetRow(
            title = "本地音色档位",
            subtitle = when (localTier) {
                io.prism.voice.LocalVoiceTier.QUALITY -> "质量优先"
                io.prism.voice.LocalVoiceTier.BALANCED -> "均衡 · 约快一倍"
                io.prism.voice.LocalVoiceTier.SPEED -> "速度优先"
            },
            onClick = {
                // 循环：质量 → 均衡 → 速度 → 质量（与设计稿「点击切换」同语义）
                val next = when (localTier) {
                    io.prism.voice.LocalVoiceTier.QUALITY -> io.prism.voice.LocalVoiceTier.BALANCED
                    io.prism.voice.LocalVoiceTier.BALANCED -> io.prism.voice.LocalVoiceTier.SPEED
                    io.prism.voice.LocalVoiceTier.SPEED -> io.prism.voice.LocalVoiceTier.QUALITY
                }
                viewModel.setLocalVoiceTier(next)
            }
        )
        SetRow(
            title = "播报方式",
            subtitle = when (playbackMode) {
                io.prism.voice.LocalClonePlaybackMode.CONTINUOUS -> "连续播报 · 首声最快"
                io.prism.voice.LocalClonePlaybackMode.WHOLE -> "整段播报 · 绝不中断"
            },
            onClick = {
                viewModel.setLocalPlaybackMode(
                    if (playbackMode == io.prism.voice.LocalClonePlaybackMode.CONTINUOUS) {
                        io.prism.voice.LocalClonePlaybackMode.WHOLE
                    } else {
                        io.prism.voice.LocalClonePlaybackMode.CONTINUOUS
                    }
                )
            }
        )
        VoiceHotwordsEditor()

        // ===== 组③：本地音色包（设计稿是 card：已安装 / 当前使用 + 导入）=====
        SetSection("本地音色包")
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s2)) {
            PrismCardBox {
                PrismKv(key = "已安装", value = "${installedPacks.size} 个")
                PrismKv(
                    key = "当前使用",
                    value = activePackName ?: "未选择（由角色包决定）",
                    onClick = onOpenVoicePacks
                )
                PrismActionsRow {
                    PrismMini(text = "导入音色包", onClick = onOpenVoicePacks)
                    PrismMini(text = "管理", onClick = onOpenVoicePacks)
                }
            }
            PrismFootnote("音色包存放在应用私有目录，卸载 App 后一并清除，不上传、不联网。")
        }

        // ===== 组④：角色（设计稿：两个导航行，进入「角色与桌宠」）=====
        SetSection("角色")
        SetRow(title = "角色包", subtitle = rolePackStatus, onClick = onOpenPersona)
        SetRow(title = "桌宠", subtitle = petStatus, onClick = onOpenPersona)
    }
}

/**
 * 本地音色包管理弹层（用户 2026-11-21 裁定：列表从页面收进弹层，页面只留「已安装 / 当前使用」）。
 *
 * 功能与页面内列表**完全等价**：导入（SAF）、选用（点击设为当前播报音色）、删除、清除选择。
 * 覆盖同名包的二次确认也在这里（ADR-057 决策 5：不静默覆盖）。
 */
@Composable
private fun VoicePackSheet(viewModel: SettingsViewModel, onClose: () -> Unit) {
    val installedPacks by viewModel.localVoicePacks.collectAsState()
    val effectiveVoiceId by viewModel.effectiveLocalVoiceId.collectAsState()
    val activeVoiceId by viewModel.activeLocalVoiceId.collectAsState()
    val packBusy by viewModel.voicePackBusy.collectAsState()
    val packMessage by viewModel.voicePackMessage.collectAsState()
    val pendingOverwrite by viewModel.pendingVoicePackOverwrite.collectAsState()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.refreshLocalVoicePacks() }

    // 音色包导入（TKN-LVC-016）：SAF 选 `.voicepack` → 拷到 cache（可 seek，便于按中央目录预检）
    // → 交 VM 校验/落盘。**上限与导入器同源**：超限的归档不进入应用私有目录之外的任何路径。
    val packImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch {
                val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val tmp = java.io.File(
                            context.cacheDir,
                            SettingsViewModel.VOICEPACK_PICK_PREFIX +
                                "${System.currentTimeMillis()}.voicepack"
                        )
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取所选文件")
                        tmp
                    }
                }
                copied.fold(
                    onSuccess = { file -> viewModel.importVoicePack(file) },
                    onFailure = { e ->
                        viewModel.onVoicePackPickFailed(e.message ?: e::class.simpleName.orEmpty())
                    }
                )
            }
        }
    }

    // 覆盖二次确认（ADR-057 决策 5：**不静默覆盖**同名音色包）
    pendingOverwrite?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.cancelVoicePackOverwrite() },
            title = { Text("已存在同名音色包") },
            text = { Text("已安装「${pending.voiceId}」。覆盖后旧包会被替换，且无法撤销。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.confirmVoicePackOverwrite() }
                ) { Text("覆盖") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.cancelVoicePackOverwrite() }
                ) { Text("取消") }
            }
        )
    }

    PrismSheet(
        title = "音色包管理",
        subtitle = "点击某个包即设为当前播报音色；未选择时由角色包决定"
    ) {
        if (installedPacks.isEmpty()) {
            Text(
                text = "尚未检测到任何音色包。点下方「导入音色包」选择 .voicepack 文件即可。",
                color = PrismTextFaint,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(PrismSize.s3))
        } else {
            installedPacks.forEach { pack ->
                VoicePackRow(
                    pack = pack,
                    busy = packBusy,
                    selected = effectiveVoiceId != null && pack.voiceId == effectiveVoiceId,
                    onSelect = { viewModel.selectLocalVoicePack(pack.voiceId) },
                    onDelete = { viewModel.deleteVoicePack(pack.voiceId) }
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        packMessage?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                color = if (message.startsWith("导入成功") || message.startsWith("已覆盖") ||
                    message.startsWith("已删除") || message.startsWith("已选择") ||
                    message.startsWith("已清除")
                ) PrismMint else PrismDanger,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        if (activeVoiceId != null) {
            Spacer(Modifier.height(PrismSize.s2))
            PrismButton(
                text = "清除选择（改由角色包声明决定）",
                variant = PrismButtonVariant.Ghost,
                enabled = !packBusy,
                onClick = { viewModel.selectLocalVoicePack(null) }
            )
        }
        Spacer(Modifier.height(PrismSize.s5))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PrismSize.s2)
        ) {
            PrismButton(
                text = if (packBusy) "处理中…" else "导入音色包",
                variant = PrismButtonVariant.Ghost,
                enabled = !packBusy,
                fillWidth = false,
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.consumeVoicePackMessage()
                    // MIME 无法可靠表达 `.voicepack`（zip），用 application/zip + 通配兜底
                    packImportLauncher.launch(
                        arrayOf("application/zip", "application/octet-stream", "*/*")
                    )
                }
            )
            PrismButton(
                text = "关闭",
                fillWidth = false,
                modifier = Modifier.weight(1f),
                onClick = onClose
            )
        }
        PrismFootnote(
            "音色包存放在应用私有目录，卸载 App 后一并清除，不上传、不联网。"
        )
    }
}


/**
 * LVC-1（ADR-053）：本地音色选项的描述文案（把"为什么不可选"直接说清楚）。
 *
 * [status] 为 null = **尚未检测**（与"未安装"是两回事，不能等同显示）。
 * 说明：不展示绝对路径（对齐 rules/07 §19.3 日志安全口径，UI 也不必暴露内部布局）。
 */
private fun localVoiceDescription(status: io.prism.voice.local.LocalVoiceStatus?): String = when (status) {
    null -> "检测中…（进入本区即自动检测）"
    is io.prism.voice.local.LocalVoiceStatus.Ready ->
        "端侧 ZipVoice 克隆 · 完全离线 · ${status.pack.references.size} 条参考 · " +
            "${status.pack.totalBytes / (1024 * 1024)} MB"
    is io.prism.voice.local.LocalVoiceStatus.Incomplete ->
        "音色包不完整：${status.problems.joinToString("；")}"
    is io.prism.voice.local.LocalVoiceStatus.NotInstalled ->
        "未安装音色包，或当前角色未声明本地音色（侧载后重新进入本页刷新）"
}

/** LVC-1：本地音色选项是否可选（仅"就绪"可为真）。 */
private fun isLocalVoiceSelectable(status: io.prism.voice.local.LocalVoiceStatus?): Boolean =
    status is io.prism.voice.local.LocalVoiceStatus.Ready

/** 自检结果文案颜色（"自检通过"前缀 = 薄荷绿；失败原因 = 警示色）。 */
private fun voiceSelfCheckColor(message: String): Color =
    if (message.startsWith("自检通过")) PrismMint else PrismWarning

/**
 * 引擎单选项 —— **设计稿 `.option` / `radioRow()`**（`screens.js:830` `settingsVoice()`）。
 *
 * 结构：18dp 空心圆环 + 名称 15sp + 副标题 11sp 等宽 + 行尾琥珀对勾 + 底部分隔线。
 * 旧实现是「34dp 方块里放 ✓/◈/— 字形 + 彩色实底 + 圆角边框」，属另一套视觉语言
 * （用户反馈「语音与形象与设计存在较大出入」的直接原因之一）。
 *
 * 保留 `enabled` 能力（云端项需先配 Key），未启用时整体降透明度且不可点。
 */
@Composable
private fun VoiceEngineOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.alpha(if (enabled) 1f else 0.45f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PrismSize.s3)
        ) {
            // `.ring`：18dp 空心圆环，选中时描边琥珀 + 12% 填充
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(
                        width = 1.dp,
                        color = if (selected) PrismAccent else PrismLineStrong,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
                    .background(
                        if (selected) PrismAccent.copy(alpha = 0.12f)
                        else androidx.compose.ui.graphics.Color.Transparent,
                        androidx.compose.foundation.shape.CircleShape
                    )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (enabled) PrismText else PrismTextDim,
                    fontSize = PrismType.body
                )
                Text(
                    text = description,
                    color = PrismTextFaint,
                    fontSize = PrismType.micro,
                    lineHeight = PrismType.microLine,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (selected) {
                Icon(
                    imageVector = PrismIcons.Check,
                    contentDescription = "已选中",
                    tint = PrismAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        PrismHairline()
    }
}

/**
 * 已安装音色包一行（TKN-LVC-016，ADR-057；BUG-20260924-001/002 改造）：
 * 包名 + 引擎 + 体积 + 就绪状态 + 「使用中」+ 删除。
 *
 * **尾控件必须是与 [RolePackRow] 同款的 32dp ✕，不得使用 [PrismButton]**：后者内部
 * `fillMaxWidth()`，会让 `Row` 先给尾控件分配整行宽度，`Modifier.weight(1f)` 的名称列被压成
 * **0 宽**——真机现象就是"列表只剩一个占满整行的删除按钮、看不到装了哪些包"（BUG-20260924-001）。
 *
 * 为什么把"未就绪"的包也列出来（带问题原因）：导入链路校验通过的包才会落盘，但**用户手动
 * 修改过**或**侧载残留**的目录同样会出现在扫描结果里。若这类目录直接不显示，用户会看到
 * "明明有目录却选不了"而无从排查；显示 + 给出问题原因才能自证。
 *
 * @param selected 由**生效包 id**（用户选择 or 角色包声明）判定，不是"用户选择"本身
 * @param onSelect 点击整行设为当前播报音色（未就绪的包不可点，避免让用户选到一个用不了的包）
 */
@Composable
private fun VoicePackRow(
    pack: SettingsViewModel.InstalledVoicePack,
    busy: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val engineLabel = when (pack.ttsWire) {
        "zipvoice" -> "ZipVoice"
        "vits" -> "VITS"
        else -> "未知引擎"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrismMint.copy(alpha = 0.08f) else PrismPanel2)
            .border(
                1.dp,
                when {
                    !pack.ready -> PrismDanger.copy(alpha = 0.4f)
                    selected -> PrismMint.copy(alpha = 0.4f)
                    else -> PrismLine
                },
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = pack.ready && !busy,
                onClick = onSelect
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = pack.name, color = PrismText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = buildString {
                    append(pack.voiceId)
                    append(" · ")
                    append(engineLabel)
                    if (pack.totalBytes > 0L) {
                        append(" · ")
                        append("%.0fMB".format(pack.totalBytes / 1024.0 / 1024.0))
                    }
                    if (!pack.ready) {
                        append(" · 不可用：")
                        append(pack.problems.joinToString("；").take(60))
                    }
                },
                color = if (pack.ready) PrismTextFaint else PrismDanger,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        if (selected) {
            Text(
                text = "使用中",
                color = PrismMint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PrismPanel2)
                .border(1.dp, PrismLine, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !busy,
                    onClick = onDelete
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "✕", color = PrismTextDim, fontSize = 12.sp)
        }
    }
}

/**
 * 语音引擎 Key 配置输入行（批次23-A，A4）—— 复用 [PrismField] 遮蔽样式 + ApiKeyRepository
 * 加密读写（与 [SearchEnhancementSheet] 的 Key 行同模式：仅首次进入回显已存 Key 防重组覆盖；
 * 清空保存 = 删除已存密钥，BR-security-006）。
 */
@Composable
private fun VoiceKeyField(
    label: String,
    placeholder: String,
    keyRef: String,
    secret: Boolean,
    viewModel: SettingsViewModel
) {
    var value by remember(keyRef) { mutableStateOf("") }
    var loaded by remember(keyRef) { mutableStateOf(false) }

    // **guardrail 2026-11-21（建议 2 / 与 R5-M-02 同类）**：原实现在组合体里直接触发
    // `loadApiKey`（每次重组都可能判定一次），且"保存"按钮不等回填就可用——
    // 打开语音页立刻点保存，会把尚未回填的空串当"清空"，**静默删掉已存 Key**。
    // 本轮语音区从折叠改为常驻，暴露窗口变大，必须修：LaunchedEffect 只跑一次 + 按钮以 loaded 门控。
    LaunchedEffect(keyRef) {
        viewModel.loadApiKey(keyRef) { v ->
            value = v.orEmpty()
            loaded = true
        }
    }

    PrismField(
        label = label,
        value = value,
        onValueChange = { value = it },
        placeholder = placeholder,
        secret = secret,
        hint = if (loaded) "Keystore 加密存储 · 清空后保存即删除" else "读取中…"
    )
    Spacer(Modifier.height(8.dp))
    PrismButton(
        text = when {
            !loaded -> "读取中…"
            else -> "保存 $label"
        },
        variant = PrismButtonVariant.Ghost,
        enabled = loaded,
        onClick = {
            if (value.isEmpty()) {
                viewModel.removeApiKey(keyRef)
            } else {
                viewModel.saveApiKey(keyRef, value)
            }
        }
    )
}

/**
 * 「角色与桌宠」子页 · 角色包（批次23-C ADR-048）。
 *
 * - **导入**：SAF 选 `.json` → 复制到缓存再读文本 → [SettingsViewModel.importRolePack]
 *   （先解析校验再落盘，文件名以包 id 规范化）；结果就地展示（成功/失败原因）；
 * - **列表**：已导入角色包 + 出厂默认行，点击切换（热切换，下次语音轮次生效），行尾删除；
 * - 装配语义在 ConversationViewModel / VoiceSessionController 内，本区只调 loader。
 *
 * 视觉按设计稿 `settingsPersona()`：卡片（已导入 / 当前）+ 导入按钮 + 脚注，列表在其下。
 */
@Composable
private fun RolePackSection(viewModel: SettingsViewModel) {
    val packs by viewModel.rolePacks.collectAsState()
    val selectedId by viewModel.selectedRolePackId.collectAsState()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var importOk by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    // 进入子页即刷新（卡片与列表都依赖真实列表；loader 自带 IO 调度）
    LaunchedEffect(Unit) { viewModel.refreshRolePacks() }

    // SAF 文件选择（批次23-C）：选中后复制到缓存再读文本，交 VM 解析导入
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch {
                val read = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val tmp = java.io.File(context.cacheDir, "rolepack_import.tmp")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("无法读取所选文件")
                        val text = tmp.readText()
                        tmp.delete()
                        text
                    }
                }
                read.fold(
                    onSuccess = { text ->
                        viewModel.importRolePack(text) { ok, message ->
                            importOk = ok
                            importMessage = message
                        }
                    },
                    onFailure = { e ->
                        importOk = false
                        importMessage = "导入失败：${e.message ?: e::class.simpleName}"
                    }
                )
            }
        }
    }

    val selectedName = packs.firstOrNull { it.id == selectedId }?.name
    SetSection("角色包")
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s2)) {
        PrismCardBox {
            PrismKv(key = "已导入", value = "${packs.size} 个")
            PrismKv(key = "当前", value = selectedName ?: "出厂默认助手")
            PrismActionsRow {
                PrismMini(
                    text = "导入角色包",
                    onClick = { importLauncher.launch(arrayOf("*/*")) }
                )
            }
        }
        importMessage?.let { msg ->
            Text(
                text = msg,
                color = if (importOk) PrismMint else PrismWarning,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
        PrismFootnote(
            "角色包为本地 JSON（prism-rolepack/1），存在应用私有目录，随卸载清除；" +
                "选中后作用于语音通话人设与播报风格。"
        )
    }

    // 已导入列表：点击切换，尾部删除（出厂默认行不可删）
    SetRow(
        title = "出厂默认助手",
        subtitle = if (selectedId == null) "生效中" else "点击切换",
        onClick = { viewModel.clearRolePackSelection() }
    )
    packs.forEach { pack ->
        SetRow(
            title = pack.name,
            subtitle = if (pack.id == selectedId) "生效中" else "点击切换",
            trailing = {
                PrismMini(
                    text = "删除",
                    danger = true,
                    onClick = { viewModel.deleteRolePack(pack.id) }
                )
            },
            onClick = { viewModel.selectRolePack(pack.id) }
        )
    }
}

/**
 * 「角色与桌宠」子页 · 桌宠（批次24 ADR-051）。
 *
 * 视觉按设计稿 `settingsPersona()` 的「桌宠」组：每项一行（标题 + 一行状态 + 控件/chevron），
 * 形象选择、大小、不透明度、表情回正各自进一个弹层（设计稿也是行 + 弹层，不是页内长列表）。
 *
 * 权限状态**必须轮询**（guardrail M-3）：用户点「前往授权」回到 App 后，
 * 立刻重读必然还是 false（系统设置页是另一个 Task，此刻还没走完）。
 */
@Composable
private fun PetSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val config by viewModel.petConfig.collectAsState()
    val appearances by viewModel.petAppearances.collectAsState()
    val runtime by viewModel.petRuntime.collectAsState()
    val lastError by viewModel.petLastError.collectAsState()
    val message by viewModel.petMessage.collectAsState()

    var canOverlay by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var appearanceSheet by remember { mutableStateOf(false) }
    var sizeSheet by remember { mutableStateOf(false) }
    var opacitySheet by remember { mutableStateOf(false) }
    var resetSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshPet() }
    LaunchedEffect(Unit) {
        while (true) {
            canOverlay = android.provider.Settings.canDrawOverlays(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    val packLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) viewModel.importPetAsset(uri, isPack = true)
    }
    val imageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) viewModel.importPetAsset(uri, isPack = false)
    }

    val currentAppearance = appearances.firstOrNull { it.id == config.appearanceId }
    val sizeLabel = when {
        config.scale <= 0.85f -> "小"
        config.scale >= 1.15f -> "大"
        else -> "中"
    }
    val opacityLabel = "${(config.opacity * 100).toInt()}%"
    val resetLabel = if (config.expressionResetSeconds <= 0) "关闭" else "${config.expressionResetSeconds} 秒"

    SetSection("桌宠")
    SetRow(
        title = "显示桌宠",
        subtitle = when {
            !config.enabled -> "已关闭"
            runtime == io.prism.pet.PetManager.RuntimeState.RUNNING -> "已开启 · 桌面显示中"
            runtime == io.prism.pet.PetManager.RuntimeState.FAILED ->
                "启动失败 · ${lastError ?: "未知原因"}"
            else -> "已开启 · 等待生效"
        },
        trailing = {
            PrismSwitch(
                checked = config.enabled,
                enabled = canOverlay,
                onCheckedChange = { viewModel.setPetEnabled(it) }
            )
        },
        onClick = { if (canOverlay) viewModel.setPetEnabled(!config.enabled) }
    )
    if (!canOverlay) {
        SetRow(
            title = "需要「显示在其他应用上层」权限",
            subtitle = "未授权 · 点此去系统设置开启",
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                canOverlay = android.provider.Settings.canDrawOverlays(context)
            }
        )
    }
    SetRow(
        title = "桌宠形象",
        subtitle = currentAppearance?.let {
            it.name + if (it.kind == io.prism.pet.model.PetAppearanceKind.LIVE2D) " · Live2D" else " · 图片"
        } ?: "未导入 · 点此选择",
        onClick = { appearanceSheet = true }
    )
    SetRow(title = "大小", subtitle = sizeLabel, onClick = { sizeSheet = true })
    SetRow(title = "不透明度", subtitle = opacityLabel, onClick = { opacitySheet = true })
    SetRow(title = "表情回正", subtitle = "$resetLabel 后恢复默认", onClick = { resetSheet = true })
    SetRow(
        title = "待机小动作",
        subtitle = if (config.idleEnabled) "没人理时偶尔动一下" else "已关闭",
        trailing = {
            PrismSwitch(
                checked = config.idleEnabled,
                onCheckedChange = { viewModel.setPetIdleEnabled(it) }
            )
        },
        onClick = { viewModel.setPetIdleEnabled(!config.idleEnabled) }
    )
    SetRow(
        title = "减少动效",
        subtitle = if (config.reduceMotion) "停止待机动作与呼吸" else "已关闭 · 保留呼吸与待机动作",
        trailing = {
            PrismSwitch(
                checked = config.reduceMotion,
                onCheckedChange = { viewModel.setPetReduceMotion(it) }
            )
        },
        onClick = { viewModel.setPetReduceMotion(!config.reduceMotion) }
    )
    SetRow(
        title = "显示对话气泡",
        subtitle = if (config.bubbleEnabled) "回复时在形象旁显示文字" else "已关闭",
        trailing = {
            PrismSwitch(
                checked = config.bubbleEnabled,
                onCheckedChange = { viewModel.setPetBubbleEnabled(it) }
            )
        },
        onClick = { viewModel.setPetBubbleEnabled(!config.bubbleEnabled) }
    )
    SetRow(
        title = "低功耗模式",
        subtitle = if (config.lowPowerMode) "15fps · 省电优先" else "已关闭 · 正常帧率",
        trailing = {
            PrismSwitch(
                checked = config.lowPowerMode,
                onCheckedChange = { viewModel.setPetLowPowerMode(it) }
            )
        },
        onClick = { viewModel.setPetLowPowerMode(!config.lowPowerMode) }
    )
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = PrismSize.s4)) {
        message?.let { PrismNoteLine(it) }
        PrismActionsRow {
            PrismMini(
                text = "重置位置",
                onClick = { viewModel.resetPetPosition() }
            )
        }
        PrismFootnote(
            "形象资产保存在 filesDir/pets/，不上传。请确认你拥有所导入形象的使用权" +
                "（第三方形象需保留署名）。"
        )
    }

    // ---- 形象选择弹层（选择 / 导入 / 删除；署名就地在弹层内展示）----
    PrismSheetHost(visible = appearanceSheet, onDismiss = { appearanceSheet = false }) {
        PrismSheet(
            title = "桌宠形象",
            subtitle = "点选立即切换"
        ) {
            if (appearances.isEmpty()) {
                Text(
                    text = "尚未导入形象。可导入 .petpack 形象包（含 Live2D 模型），或直接选一张图片。",
                    color = PrismTextFaint,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(PrismSize.s3))
            }
            appearances.forEach { appearance ->
                PetAppearanceRow(
                    name = appearance.name,
                    detail = if (appearance.kind == io.prism.pet.model.PetAppearanceKind.LIVE2D) {
                        "Live2D · ${appearance.actions.size} 个动作"
                    } else {
                        "图片"
                    },
                    selected = appearance.id == config.appearanceId,
                    onSelect = {
                        viewModel.setPetAppearance(appearance.id)
                        appearanceSheet = false
                    },
                    onDelete = { viewModel.deletePetAppearance(appearance.id) }
                )
                Spacer(Modifier.height(8.dp))
            }
            // 署名展示（ADR-051；guardrail M-5 指出数据已解析但从未接线）。
            // 合规意义：昔涟模型的原作者授权**以署名为条件**。
            currentAppearance?.attribution?.let { attribution ->
                Text(
                    text = attribution,
                    color = PrismTextFaint,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
                Spacer(Modifier.height(8.dp))
            }
            PrismActionsRow {
                PrismMini(
                    text = "导入形象包",
                    onClick = { packLauncher.launch(arrayOf("*/*")) }
                )
                PrismMini(
                    text = "导入图片",
                    onClick = { imageLauncher.launch(arrayOf("image/*")) }
                )
            }
            Spacer(Modifier.height(PrismSize.s5))
            PrismButton(text = "关闭", onClick = { appearanceSheet = false })
        }
    }

    PrismSheetHost(visible = sizeSheet, onDismiss = { sizeSheet = false }) {
        PrismChoiceSheet(
            title = "大小",
            selectedIndex = when (sizeLabel) { "小" -> 0; "大" -> 2; else -> 1 },
            options = listOf("小", "中", "大"),
            onSelect = { index ->
                viewModel.setPetScale(listOf(0.7f, 1.0f, 1.3f)[index])
                sizeSheet = false
            },
            onDismiss = { sizeSheet = false }
        )
    }
    PrismSheetHost(visible = opacitySheet, onDismiss = { opacitySheet = false }) {
        PrismChoiceSheet(
            title = "不透明度",
            selectedIndex = listOf(0.7f, 0.85f, 1.0f)
                .indexOfFirst { kotlin.math.abs(it - config.opacity) < 0.01f }
                .coerceAtLeast(0),
            options = listOf("70%", "85%", "100%"),
            onSelect = { index ->
                viewModel.setPetOpacity(listOf(0.7f, 0.85f, 1.0f)[index])
                opacitySheet = false
            },
            onDismiss = { opacitySheet = false }
        )
    }
    PrismSheetHost(visible = resetSheet, onDismiss = { resetSheet = false }) {
        val resetOptions = listOf(0, 5, 8, 15)
        PrismChoiceSheet(
            title = "表情回正",
            subtitle = "表情在多少秒后回到默认",
            selectedIndex = resetOptions.indexOf(config.expressionResetSeconds).coerceAtLeast(0),
            options = listOf("关闭", "5 秒", "8 秒", "15 秒"),
            onSelect = { index ->
                viewModel.setPetExpressionResetSeconds(resetOptions[index])
                resetSheet = false
            },
            onDismiss = { resetSheet = false }
        )
    }
}

/** 桌宠形象行：点击切换选中，行尾 ✕ 删除（视觉与角色包行一致）。 */
@Composable
private fun PetAppearanceRow(
    name: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) PrismMint.copy(alpha = 0.08f) else PrismPanel2)
            .border(
                1.dp,
                if (selected) PrismMint.copy(alpha = 0.4f) else PrismLine,
                RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = if (selected) PrismMint else PrismText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = detail, color = PrismTextFaint, fontSize = 11.sp)
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PrismPanel2)
                .border(1.dp, PrismLine, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDelete
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "✕", color = PrismTextDim, fontSize = 12.sp)
        }
    }
}
