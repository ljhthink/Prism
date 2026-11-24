package io.prism.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.prism.ui.chat.ConversationViewModel
import io.prism.ui.theme.PrismBg
import io.prism.ui.theme.PrismIndigo
import io.prism.ui.theme.PrismPanel2
import io.prism.ui.theme.PrismText
import io.prism.ui.theme.PrismTextDim
import io.prism.ui.theme.PrismTextFaint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import io.prism.ui.components.PrismIcons
import io.prism.ui.theme.PrismAccent
import io.prism.ui.theme.PrismDanger
import io.prism.ui.theme.PrismLineStrong
import io.prism.ui.theme.PrismOk
import io.prism.ui.theme.PrismPanel
import io.prism.ui.theme.PrismSize
import io.prism.ui.theme.PrismType

/**
 * 批次20（VC-005，ADR-044）：语音通话全屏覆盖层（聊天页内，非独立路由）。
 *
 * **设计取舍**：覆盖层直接共享 ConversationScreen 的 [ConversationViewModel] 作用域——
 * 语音轮次与文字轮次同会话同历史（PRD VC-005 验收），避免跨路由 VM 隔离；挂断即关闭覆盖层。
 *
 * **交互**（P1，用户批准）：按住大按钮说话 → 松开自动发送 → THINKING/SPEAKING 状态提示 →
 * AI 回复播报。转写文本在回显区可（于发送前）复核——P1 为系统引擎整段识别，松开即发，
 * 识别错误时可在聊天页编辑重发（复用现有编辑链路）。
 *
 * **权限**：RECORD_AUDIO 首次进入运行时申请；拒绝 → 覆盖层提示并禁用录音按钮（不崩溃）。
 */
@Composable
fun VoiceCallOverlay(
    conversation: ConversationViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 批次23-A（ADR-048，A4/A5）：引擎装配异步化 —— 按 VoiceEnginePrefs 手选云端/端侧
    //（读 Key/RolePack 为 suspend），就绪前显示"准备中"占位。装配含：云端 Key 缺失静默
    // 回退端侧（装配提示 toast）+ 降级链端侧引擎供给注入。
    var controllerHolder by remember { mutableStateOf<VoiceSessionController?>(null) }
    LaunchedEffect(Unit) {
        val app = context.applicationContext as io.prism.PrismApplication
        controllerHolder = io.prism.voice.VoiceEngineAssembly.create(
            context = context,
            conversation = conversation,
            rolePackLoader = app.rolePackLoader,
            voiceEnginePrefs = app.voiceEnginePrefs,
            apiKeyRepository = app.apiKeyRepository
        )
    }
    // 装配期占位（约 100–200ms）。
    //
    // **回归修复**：原实现用 `Brush.verticalGradient(PrismBg, #141A2E, PrismBg)` ——
    // `#141A2E` 是**旧靛蓝深空主题**的第二档底色，UI v2 改暖灰后它成了残留。
    // 用户点「语音通话」先看到这个靛蓝框、再切到暖灰主界面，体感就是"闪过之前的 UI"。
    // 现在：底用与主界面完全相同的两层渐变（无第二色相），且叠 fadeIn 与主界面同节奏，
    // 装配完成时视觉上只是内容淡入，不再有整层换色。
    val controller = controllerHolder ?: run {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(PrismBg)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1A1C18), PrismBg, PrismBg)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                "语音引擎准备中…",
                color = PrismTextFaint,
                fontSize = 13.sp
            )
        }
        return
    }
    val phase by controller.phase.collectAsState()
    val partial by controller.partialText.collectAsState()
    val transcript by controller.lastTranscript.collectAsState()
    val volume by controller.volume.collectAsState()

    // 麦克风权限（运行时申请；拒绝后提供"重新授权"入口——批次20 真机修复 R2：拒绝原先是
    // 静默路径，用户无重试入口造成"无反应"体感）
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) {
            controller.startCall(context)
        }
    }

    // 批次23-A（ADR-048，A5）：降级链 toast —— 层②启动探测失败 / 层③会话中失败自动切端侧
    //（控制器内部完成引擎切换与当前动作重试），此处仅消费提示文案（展示一次即清除）。
    val fallbackNotice by controller.fallbackNotice.collectAsState()
    LaunchedEffect(fallbackNotice) {
        fallbackNotice?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            controller.consumeFallbackNotice()
        }
    }

    // 进入覆盖层 = 开启通话（FGS 启动）；权限未授予先申请
    // 批次22（SZ-101，ADR-047）：通话启动前自动修复识别引擎的麦克风权限（code=9 根因）——
    // Shizuku 就绪时 pm grant 引擎包（幂等）；未就绪静默跳过（走原有引导文案）。
    // guardrail R1#2：**仅对系统默认识别引擎授权**（Settings.Secure 比对）——queryIntentServices
    // 可枚举到第三方自声明的 RecognitionService，若不加约束，恶意 App 抢占首位时会被
    // 以 shell 身份静默授予麦克风（绕过用户授权弹窗）。
    LaunchedEffect(Unit) {
        if (io.prism.phonecontrol.bg.ShizukuShell.isReady()) {
            val enginePkg = controller.peekEnginePackage()
            // 键名 "voice_recognition_service" 为 @hide 常量 Settings.Secure.VOICE_RECOGNITION_SERVICE
            // 的实际键（API 8 起稳定存在，公开 SDK 未导出）——值为扁平化 ComponentName
            val defaultPkg = android.provider.Settings.Secure.getString(
                context.contentResolver, "voice_recognition_service"
            )?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }
            if (enginePkg != null && enginePkg == defaultPkg) {
                io.prism.phonecontrol.bg.ShizukuShell.pmGrant(
                    enginePkg, android.Manifest.permission.RECORD_AUDIO
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        if (micGranted) {
            controller.startCall(context)
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 覆盖层卸载时挂断 + 释放引擎（防 FGS 泄漏）
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            controller.hangUp(context)
            controller.release()
        }
    }

    val status = voiceStatusText(controller, phase, micGranted)

    Box(
        modifier = modifier
            .fillMaxSize()
            // 设计稿 `.voiceroom`：radial-gradient(120% 70% at 50% 28%, #1a1c18 0%, bg 62%)
            // 设计稿 `.voiceroom` 的 radial-gradient 用垂直渐变近似（radial + 无限半径在
            // 部分 ROM 的 Skia 上不生效，实测整层被绘制成透明 → 覆盖层"看不见"）。
            // 先铺一层不透明底色，再叠顶部亮区，保证任何情况下都不可能出现"透明覆盖层"。
            .background(PrismBg)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A1C18), PrismBg, PrismBg)
                )
            )
            .pointerInput(Unit) { } // 消费点击，不透传聊天页
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 头部：标题 + 状态 + 挂断（`.voice-head`）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PrismSize.page, end = PrismSize.page, top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "语音通话",
                        color = PrismText,
                        fontSize = PrismType.head
                    )
                    Text(
                        text = status,
                        color = PrismTextFaint,
                        fontSize = PrismType.meta,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
                // 挂断（`.hangup`：44dp、danger 14% 底 + 38% 描边、线性 ✕）
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(PrismDanger.copy(alpha = 0.14f), CircleShape)
                        .border(1.dp, PrismDanger.copy(alpha = 0.38f), CircleShape)
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                        .semantics {
                            contentDescription = "挂断语音通话"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = PrismIcons.Close,
                        contentDescription = null,
                        tint = PrismDanger,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ---- 舞台：orb / 转写 / 波形 / 提示（`.voice-stage`）----
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                VoiceOrb(phase = phase, volume = volume, micGranted = micGranted)
                Spacer(Modifier.height(22.dp))
                // 转写（`.voice-transcript`：17sp、居中、最大宽 300dp、最小高 52dp）
                Text(
                    text = when {
                        !micGranted -> "未授予麦克风权限，无法语音输入"
                        phase == VoiceSessionController.Phase.RECORDING ||
                            phase == VoiceSessionController.Phase.RECOGNIZING ->
                            partial.ifBlank { "请说话…" }
                        // 批次20 真机修复（RCA 问题2）：transcript 承载转写与引擎不可用提示
                        transcript.isNotBlank() -> transcript
                        else -> "说点什么，说完稍停即发送"
                    },
                    color = if (transcript.startsWith("（") || !micGranted) PrismTextFaint else PrismText,
                    fontSize = PrismType.head,
                    lineHeight = 25.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .heightIn(min = 52.dp)
                )
                Spacer(Modifier.height(22.dp))
                VoiceWave(phase = phase)
                // 异常态才提示（正常状态不再挂任何说明文字——用户明确要求删除常驻说明）
                val abnormal = when {
                    !micGranted -> null
                    !controller.ttsAvailable -> "本机无语音合成引擎，回复将以文字展示"
                    else -> null
                }
                if (abnormal != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = abnormal,
                        color = PrismTextFaint,
                        fontSize = PrismType.meta,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ---- 底部：形态胶囊 / 按住说话（`.voice-foot`）----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PrismSize.page, end = PrismSize.page, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!controller.isStreamingCall) {
                    val pressable = phase == VoiceSessionController.Phase.IDLE && micGranted
                    // `.hold-talk`：88dp 白底圆 + 深墨文字
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .alpha(if (pressable || phase == VoiceSessionController.Phase.RECORDING) 1f else 0.4f)
                            .background(PrismText, CircleShape)
                            .pointerInput(pressable) {
                                if (!pressable) return@pointerInput
                                detectTapGestures(
                                    onPress = {
                                        controller.startRecording()
                                        try {
                                            awaitRelease()
                                        } finally {
                                            controller.stopRecording()
                                        }
                                    }
                                )
                            }
                            .semantics {
                                contentDescription = "按住说话"
                                role = Role.Button
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "按住\n说话",
                            color = PrismBg,
                            fontSize = PrismType.body,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // `.voice-mode`：44dp 高胶囊 + 实时点 + 13sp 文字
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .background(PrismPanel, RoundedCornerShape(22.dp))
                            .border(1.dp, PrismLineStrong, RoundedCornerShape(22.dp))
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(PrismOk, CircleShape)
                        )
                        Text(
                            text = "端侧流式 · 免按压",
                            color = PrismTextDim,
                            fontSize = PrismType.sub
                        )
                    }
                }
                // 权限被拒后的唯一出路：重新申请
                if (!micGranted) {
                    Spacer(Modifier.height(PrismSize.s3))
                    Box(
                        modifier = Modifier
                            .background(PrismAccent.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .border(1.dp, PrismAccent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .pointerInput(Unit) {
                                detectTapGestures { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .semantics { contentDescription = "重新申请麦克风权限"; role = Role.Button },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("重新授权麦克风", color = PrismAccent, fontSize = PrismType.sub)
                    }
                }
            }
        }
    }
}

/**
 * 状态文案（`.voice-sub`）。
 *
 * 流式引擎是"免按压连续对话"，与按压形态的状态含义不同（非流式 IDLE 表示"等你按住"），
 * 因此**必须分开表述**，否则用户会以为没在听。
 */
private fun voiceStatusText(
    controller: VoiceSessionController,
    phase: VoiceSessionController.Phase,
    micGranted: Boolean
): String = when {
    !micGranted -> "需要麦克风权限"
    controller.isStreamingCall -> when (phase) {
        VoiceSessionController.Phase.SPEAKING -> "播报中…（说「停」可打断）"
        VoiceSessionController.Phase.THINKING -> "思考中（工具调用照常执行）"
        else -> "聆听中…（说完稍停自动发送）"
    }
    else -> when (phase) {
        VoiceSessionController.Phase.IDLE -> "按住下方按钮说话"
        VoiceSessionController.Phase.RECORDING -> "聆听中…"
        VoiceSessionController.Phase.RECOGNIZING -> "识别中…"
        VoiceSessionController.Phase.THINKING -> "思考中（工具调用照常执行）"
        VoiceSessionController.Phase.SPEAKING -> "播报中…"
    }
}

/**
 * 呼吸球（`.orb` 148dp：两圈错峰扩散 + 92dp 核心）。
 *
 * 动画只用 scale / alpha，不用位移；核心 2.8s 呼吸、外圈 2.8s / 1.6s 两个相位。
 * 通话全程保持可见（`listening` 恒真）——AI 思考时若球体消失，用户会以为通话断了。
 */
@Composable
private fun VoiceOrb(
    phase: VoiceSessionController.Phase,
    volume: Float,
    micGranted: Boolean
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val ring1 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1"
    )
    val ring1Alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1Alpha"
    )
    val ring2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2800,
                delayMillis = 900,
                easing = androidx.compose.animation.core.LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2"
    )
    val ring2Alpha by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2800,
                delayMillis = 900,
                easing = androidx.compose.animation.core.LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2Alpha"
    )
    // 核心呼吸 + 录音时随音量放大（两者相乘，避免多套状态）
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "coreBreathe"
    )
    val voiceScale = if (phase == VoiceSessionController.Phase.RECORDING) 1f + volume * 0.3f else 1f
    val coreScale = breathe * voiceScale

    Box(
        modifier = Modifier.size(148.dp),
        contentAlignment = Alignment.Center
    ) {
        // 外圈 ×2（错峰）：只画描边环，scale 扩散 + alpha 淡出
        listOf(ring1 to ring1Alpha, ring2 to ring2Alpha).forEachIndexed { index, (scale, alpha) ->
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = if (micGranted) alpha else alpha * 0.4f
                    }
                    .border(1.dp, PrismAccent.copy(alpha = 0.5f), CircleShape)
            )
        }
        // 核心（`.orb-core`：92dp、surface-2、琥珀 34% 描边、34dp 麦克风）
        Box(
            modifier = Modifier
                .size(92.dp)
                .graphicsLayer {
                    scaleX = coreScale
                    scaleY = coreScale
                }
                .background(PrismPanel2, CircleShape)
                .border(1.dp, PrismAccent.copy(alpha = 0.34f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = PrismIcons.Mic,
                contentDescription = null,
                tint = PrismAccent,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

/**
 * 波形（`.wave`：28 根 3dp 宽、3dp 间距、30dp 高，1.6s 交替呼吸）。
 *
 * 高度按正弦分布做静态包络，再用统一相位做呼吸——视觉上是"声波"，但**不伪造音量**
 * （真实音量只体现在核心球的 scale 上）。
 */
@Composable
private fun VoiceWave(phase: VoiceSessionController.Phase) {
    val transition = rememberInfiniteTransition(label = "wave")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wavePhase"
    )
    val active = phase == VoiceSessionController.Phase.RECORDING ||
        phase == VoiceSessionController.Phase.SPEAKING
    Row(
        modifier = Modifier.height(30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(28) { i ->
            // 静态包络：中间高、两端低；再叠加相位偏移，形成相邻起伏
            val envelope = kotlin.math.sin(Math.PI * (i + 1) / 29.0).toFloat()
            val wobble = 0.45f + 0.55f * kotlin.math.abs(
                kotlin.math.sin(Math.PI * (t + i * 0.07f))
            ).toFloat()
            val h = (6f + 22f * envelope * wobble).dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h)
                    .background(
                        if (active) PrismAccent.copy(alpha = 0.75f) else PrismTextFaint.copy(alpha = 0.55f),
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}




