package io.prism.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
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
    val controller = controllerHolder ?: run {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(PrismBg, Color(0xFF141A2E), PrismBg))
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                "语音引擎准备中…",
                color = PrismTextFaint,
                fontSize = 14.sp
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(PrismBg, Color(0xFF141A2E), PrismBg))
            )
            .pointerInput(Unit) { } // 消费点击，不透传聊天页
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部：标题 + 挂断
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    androidx.compose.material3.Text("语音通话", color = PrismText, fontSize = 20.sp)
                    androidx.compose.material3.Text(
                        // 批次22（SZ-202）：流式引擎为免按压连续对话形态——状态文案区分
                        when {
                            controller.isStreamingCall && phase == VoiceSessionController.Phase.IDLE ->
                                "聆听中…（免按压，说完稍停即发送）"
                            controller.isStreamingCall && phase == VoiceSessionController.Phase.RECORDING ->
                                "聆听中…（说完稍停自动发送）"
                            controller.isStreamingCall && phase == VoiceSessionController.Phase.SPEAKING ->
                                "播报中…（说\"停\"可打断，其余语音不打断播报）"
                            else -> when (phase) {
                                VoiceSessionController.Phase.IDLE -> "按住下方按钮说话"
                                VoiceSessionController.Phase.RECORDING -> "聆听中…"
                                VoiceSessionController.Phase.RECOGNIZING -> "识别中…"
                                VoiceSessionController.Phase.THINKING -> "思考中（工具调用照常执行）"
                                VoiceSessionController.Phase.SPEAKING -> "播报中…"
                            }
                        },
                        color = PrismTextDim,
                        fontSize = 13.sp
                    )
                }
                // 挂断按钮
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFFFF5A5A).copy(alpha = 0.18f), CircleShape)
                        .border(1.dp, Color(0xFFFF5A5A).copy(alpha = 0.6f), CircleShape)
                        .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                        .semantics {
                            contentDescription = "挂断语音通话"
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = null,
                        tint = Color(0xFFFF5A5A),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 中部：状态/回显
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 音量波形环（RECORDING 时随音量呼吸）
                val scale by animateFloatAsState(
                    targetValue = if (phase == VoiceSessionController.Phase.RECORDING) {
                        1f + volume * 0.35f
                    } else 1f,
                    label = "micPulse"
                )
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(scale)
                        .background(PrismIndigo.copy(alpha = 0.12f), CircleShape)
                        .border(2.dp, PrismIndigo.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        tint = PrismIndigo,
                        modifier = Modifier.size(48.dp)
                    )
                }
                androidx.compose.material3.Text(
                    text = when {
                        !micGranted -> "未授予麦克风权限，无法语音输入"
                        phase == VoiceSessionController.Phase.RECORDING ||
                            phase == VoiceSessionController.Phase.RECOGNIZING ->
                            partial.ifBlank { "请说话…" }
                        // 批次20 真机修复（RCA 问题2）：transcript 承载两类内容——已发送的转写
                        // 与引擎不可用等提示（括号包裹），均直接展示
                        transcript.isNotBlank() -> transcript
                        else -> "你的语音将转为文字并执行任务指令"
                    },
                    color = if (transcript.startsWith("（") || !micGranted) PrismTextFaint else PrismText,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(if (phase == VoiceSessionController.Phase.RECORDING) 1f else 0.75f)
                )
                // 批次20 真机修复 R2：权限被拒后的"重新授权"入口（此前拒绝即死路——按住无效且无重试）
                if (!micGranted) {
                    Box(
                        modifier = Modifier
                            .background(PrismIndigo.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .border(1.dp, PrismIndigo.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .pointerInput(Unit) { detectTapGestures { micLauncher.launch(Manifest.permission.RECORD_AUDIO) } }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .semantics { contentDescription = "重新申请麦克风权限"; role = Role.Button },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Text("重新授权麦克风", color = PrismIndigo, fontSize = 14.sp)
                    }
                }
                androidx.compose.material3.Text(
                    text = if (!controller.ttsAvailable) "本机无语音合成引擎，回复将以文字展示" else "",
                    color = PrismTextFaint,
                    fontSize = 12.sp
                )
                // 批次22（SZ-201）：隐私声明随引擎形态区分——端侧流式=完全离线，
                // 系统引擎=厂商引擎可能云端处理
                // 批次23-A（ADR-048）：云端 ASR 手选时明示音频实时推流（音频出设备）
                androidx.compose.material3.Text(
                    text = when {
                        controller.isCloudAsr ->
                            "云端语音识别（阿里云百炼）：音频实时推流至你配置的服务处理，不落盘存储"
                        controller.isStreamingCall ->
                            "端侧离线语音识别（模型在本机运行，音频不出设备、不落盘存储）"
                        else ->
                            "语音识别使用本机系统识别服务（部分厂商引擎会在云端处理音频），不落盘存储"
                    },
                    color = PrismTextFaint,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }

            // 底部：按住说话按钮（批次22：仅非流式引擎显示——流式引擎免按压连续对话；
            // 按住说话作为系统引擎的降级模式保留，PRD SZ-202）
            if (!controller.isStreamingCall) {
                val pressable = phase == VoiceSessionController.Phase.IDLE && micGranted
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .alpha(if (pressable || phase == VoiceSessionController.Phase.RECORDING) 1f else 0.4f)
                        .background(PrismIndigo, CircleShape)
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
                    androidx.compose.material3.Text(
                        text = "按住\n说话",
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
