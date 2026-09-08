# 批次20：语音通话 P1 + 手机操控后台化 M1 · 产品需求文档（PRD）

> 依 CLAUDE.md 第十一节：ac-verifier 将基于本 PRD 验收标准执行分层测试。
> 上游调研：docs/reports/2026-09-05-voice-call-and-background-control-research.md（已获用户批准）

| 项目 | 内容 |
|---|---|
| 版本 | v1.0 |
| 日期 | 2026-09-05 |
| 作者 | AI（依用户批准的调研报告执行） |
| 关联文档 | ADR-044（语音技术选型）、ADR-045（虚拟屏后台操控）、调研报告 |
| 风险等级 | P1（触及 phone_control 工具协议与无障碍服务执行路径） |

## 1. 背景

1. **手机操控前台独占**：现有 `phone_control__*` 12 个工具经 `PhoneControlAccessibilityService` 操控主屏前台窗口，LLM 执行任务期间用户无法使用手机（触碰即干扰）。
2. **无语音交互**：项目当前完全没有 TTS/STT/AudioRecord 能力（考古：370 个 Kotlin 文件零命中），用户只能文字派活。
3. 用户已批准「端侧 ASR + 复用 sendMessage 管线 + 系统 TTS」的语音方案与「纯无障碍+虚拟屏为主、Shizuku 增强在后」的后台化方案（报告第 13 节建议列生效）。

## 2. 目标与非目标

- **目标**
  - M1：LLM 可在虚拟屏后台执行手机操控任务（launch/tap/type/scroll/back/home/get_ui_state/screenshot），主屏完全留给用户；前台模式行为不变。
  - P1：用户可在语音通话页按住说话，语音转写文本进入现有会话管线（工具调用全生效），AI 回复自动 TTS 播报，轮次落库。
- **非目标（明确不做）**
  - 连续对话 + 打断（barge-in）→ P2；唤醒词 → 不做；Gemini Live 等云端实时 API → 不做；root/app_process 提权 → 不做；多虚拟屏并发 → 不做；虚拟屏内 IME 自动化 → 不做；Shizuku 手势增强与悬浮窗画中画预览 → M2；任务进度语音播报协同 → M3。

## 3. 用户故事与验收标准

### BG-001: VirtualDisplayManager（虚拟屏生命周期）

- 作为 LLM 执行器，我希望有一个管理器创建/复用/销毁虚拟屏并在其上启动目标 App，以便任务在后台屏运行。
- 验收标准：
  - [ ] 新类 `io.prism.phonecontrol.bg.VirtualDisplayManager`：`ensureDisplay()` 以主屏同分辨率/DPI 创建 VirtualDisplay（ImageReader Surface，RGBA_8888）；已存在则复用同一 displayId（幂等）
  - [ ] `launchApp(packageName)` 使用 `ActivityOptions.setLaunchDisplayId` 启动应用；包名纠正复用 `PhoneControlPackageMap`
  - [ ] `collectFrames()` 暴露 ImageReader 帧流（Bitmap，虚拟屏坐标）；`releaseDisplay()` 释放显示并 force-stop 曾启动的包名集合
  - [ ] 单测：幂等创建、包名集合记录、release 清理逻辑（Robolectric 可测部分 + 接口抽象可测逻辑）

### BG-002: 无障碍服务跨屏执行面

- 作为 LLM，我希望 UI 树读取与节点操作能路由到虚拟屏，以便后台任务可感知可操作。
- 验收标准：
  - [ ] `PhoneControlAccessibilityService` 新增 `rootForDisplay`（批次20 实施命名：`executionRoot()`，语义相同）→ **统一为 `executionRoot()`**：API 30+ 经 `getWindowsOnAllDisplays()` 定位虚拟屏窗口根节点；查不到返回 null 并附诊断文案
  - [ ] 节点动作（tap/type/scroll/back/home）在传入虚拟屏根节点时按现有节点路径执行；**坐标手势（dispatchGesture）在后台模式明确返回错误提示「后台模式不支持坐标手势，请用节点锚点或切回前台模式」**
  - [ ] `launch_app` 后台模式走 `VirtualDisplayManager.launchApp`；敏感拦截（`PhoneControlSecurity` 四层）在后台路径同样生效（单测覆盖：后台模式下金融 App 黑名单仍拦截）
  - [ ] API < 30 时后台模式整体不可用并给出明确错误文案

### BG-003: 工具协议 mode 参数

- 作为 LLM，我希望 phone_control 工具带可选 `mode` 参数选择执行面，以便按任务需要选择前台/后台。
- 验收标准：
  - [ ] `PhoneControlLocalToolExecutor.buildToolDefinitions()` 中 get_ui_state/tap/long_press/double_tap/swipe/type/back/home/launch_app/screenshot 增加可选参数 `mode: string("foreground"|"background")`，默认 foreground（schema 为合法 JSON Schema，向后兼容：不带 mode 的调用行为与现状完全一致）
  - [ ] 后台模式调用链：executor → VirtualDisplayManager.ensureDisplay → 服务跨屏方法；前台链路零改动（现有单测全部通过）
  - [ ] `PHONE_CONTROL_GUIDANCE` systemPrompt 追加后台模式使用规则（何时选 background、手势受限说明、API 30+ 前提）
  - [ ] swipe 工具在后台模式返回固定错误文案（与 BG-002 一致），不产生主屏副作用

### BG-004: 后台截图与 OCR 感知

- 作为 LLM，我希望后台模式下 screenshot 返回虚拟屏画面，以便视觉感知与 OCR 锚点可用。
- 验收标准：
  - [ ] 后台模式 `screenshot`：取 `VirtualDisplayManager.collectFrames()` 最新帧 → 复用现有降采样/JPEG 管线与 `OcrTextExtractor`；坐标即虚拟屏坐标，`tap(text=)` 文本锚点先匹配虚拟屏 UI 树（BG-002 根节点）
  - [ ] 虚拟屏帧获取失败时返回明确错误文案（不回退截主屏，避免感知错位）

### BG-005: 权限引导与入口 UI

- 作为用户，我希望有明确的开关与权限引导开启后台操控，以便理解能力边界。
- 验收标准：
  - [ ] 能力开关行（ConversationScreen `CapabilityToggleRow`）新增「后台操控」chip，会话级生效；开启时若 API < 30 或无障碍未启用则不可用并提示
  - [ ] 后台模式需要 `SYSTEM_ALERT_WINDOW`：首次开启时引导跳转悬浮窗授权页（Settings.canDrawOverlays 判定），未授权则后台工具调用返回引导文案
  - [ ] 设置页手机操控区块新增后台模式说明（能力边界：节点操作为主、坐标手势受限、微信类 App 降级）

### VC-001: 语音权限与前台服务基座

- 作为用户，我希望语音功能的权限申请一次到位且锁屏可用。
- 验收标准：
  - [ ] Manifest 新增 `RECORD_AUDIO`、`FOREGROUND_SERVICE_MICROPHONE`；语音页首次进入运行时申请 RECORD_AUDIO（拒绝时功能不可用并提示，不崩溃）
  - [ ] 新建 `VoiceForegroundService`（FGS type=microphone）+ 通知渠道 `prism_voice_call`（IMPORTANCE_LOW）；通话开始启动、结束停止
  - [ ] 单测：渠道创建与 FGS 启停状态机

### VC-002: ASR 引擎（sherpa-onnx 为主 + 系统 STT 降级）

- 作为用户，我希望说话被准确转写为文字且可编辑确认。
- 验收标准：
  - [ ] 新接口 `io.prism.voice.AsrEngine`（start/stop/finalResult）+ `SherpaAsrEngine`（官方 AAR 放 `app/libs`，中文流式 zipformer 模型首次使用下载到 filesDir，下载可取消、断点重试）+ `SystemAsrEngine`（SpeechRecognizer 降级，档位低或模型未就绪时用）
  - [ ] 识别结果在 UI 可编辑后发送；无网络时 sherpa 离线路径可用（真机验证项）
  - [ ] 引擎可替换（接口注入），单测：状态机（空闲/录音/识别完成/取消）与降级选择逻辑

### VC-003: TTS 播报

- 作为用户，我希望 AI 回复自动语音播报。
- 验收标准：
  - [ ] `TtsEngine` 接口 + `SystemTtsEngine`（TextToSpeech）：AI 回复完成后播报；提供停止/重播；（批次20 实施修订：语速调节移至 P2 增强——YAGNI，用户未提出该诉求，`setSpeechRate` 在接口层预留）
  - [ ] TTS 初始化失败（无 TTS 引擎设备）时静默降级为仅文字，不崩溃；单测：播报队列与停止逻辑

### VC-004: 音频采集

- 作为引擎，我希望干净的 16k 单声道 PCM 输入。
- 验收标准：
  - [ ] `AudioInputController`：AudioRecord（16kHz/16bit/mono，`VOICE_COMMUNICATION`）；`AcousticEchoCanceler` 可用即开启；按住说话期间采集、松开停止并输出 PCM
  - [ ] 单测：配置参数与起停状态机（Robolectric）

### VC-005: 语音通话页与会话集成

- 作为用户，我希望一个通话式界面，语音轮次进入会话历史且全功能可用。
- 验收标准：
  - [ ] 新页面 `VoiceCallScreen` + `VoiceCallViewModel`：状态机（空闲/聆听/识别中/思考中/播报中），大按钮按住说话、松开自动发送；轮次文字回显；挂断退出（FGS 停止、资源释放）
  - [ ] （批次20 实施修订 G-3：以**聊天页内全屏覆盖层 `VoiceCallOverlay`** 替代独立路由页——覆盖层直接共享 ConversationScreen 的 ConversationViewModel 作用域，语音轮次与文字轮次同会话同历史且避免跨路由 VM 隔离；入口为聊天顶栏麦克风按钮，挂断即关闭覆盖层，`PrismDestinations.VOICE_CALL` 不新增）
  - [ ] 路由注册：~~`PrismDestinations.VOICE_CALL` + NavHost `composable`~~（见上，覆盖层实现）；聊天页输入框旁麦克风入口跳转 → 顶栏麦克风入口
  - [ ] 转写文本以 USER 消息（含「🎙 语音」标记）+ AI 回复完整落库 ObjectBox 会话；历史页可见、回退/重试/variants 对语音轮次行为与文字轮次一致
  - [ ] 思考中/执行工具期间按住说话被禁用（防并发打断会话管线）；isTyping/activeTool 状态机与现有 finally 清理兼容

## 4. 非功能需求

- **性能**：后台模式虚拟屏帧降采样沿用最长边 1024/JPEG q80；sherpa 流式识别首字延迟真机验收 < 800ms（记录到 acceptance 报告）；虚拟屏常驻内存增量 < 80MB（主屏同分辨率 ImageReader 2 帧 buffer）
- **安全**：敏感拦截四层在后台路径全量生效；语音音频不落盘、不写入应用自有上传通道（**分路径**：sherpa 端侧路径音频不出设备；P1 主链路系统 SpeechRecognizer 的音频由厂商识别服务处理，多数国产 ROM 为云端实现——已在语音入口向用户明示）；RECORD_AUDIO 拒绝即功能停用
- **隐私**：无新增云依赖（sherpa 模型下载除外，下载源固定官方 release，ADR-044 记录）
- **兼容性**：后台模式要求 API 30+（UI 明示）；语音功能 API 26+（项目 minSdk）；RECORD_AUDIO 拒绝、TTS 引擎缺失均优雅降级
- **可观测性**：后台模式关键路径结构化日志（displayId/包名/节点动作结果）；语音引擎切换/失败日志

## 5. 风险与依赖

| 风险/依赖 | 等级 | 缓解/管控 |
|---|---|---|
| 无障碍跨屏窗口在部分 ROM 上不暴露虚拟屏窗口 | 高 | `rootForDisplay` 查不到即返回明确错误文案；真机验证清单（MIUI 优先）；失败路径可一键切回前台模式 |
| 后台启动 Activity 受限（Android 10+/14+） | 高 | 悬浮窗权限豁免引导（BG-005）；启动失败重试一次后报告 |
| 微信类 App 无节点树，后台能力降级 | 中 | 文档/UI 明示边界；OCR 感知仍可用；手势受限文案 |
| sherpa-onnx AAR 体积与模型下载 | 中 | AAR 放 libs 不入 Maven；模型按需下载可取消；低档位默认系统 STT |
| MIUI 杀后台虚拟屏任务 | 中 | 沿用 PhoneControlSessionManager 任务期 FGS 保活 + 完成通知兜底 |
| 工具协议变更破坏现有调用 | 中 | mode 可选默认 foreground；现有单测全量回归 |

## 6. 里程碑

| 里程碑 | 验收标准 | 风险等级 |
|---|---|---|
| M-A 功能二 M1（BG-001~005） | LLM 后台模式完成一次「launch → get_ui_state → tap 节点 → type → screenshot」全链路，期间主屏被用户自由使用；前台模式回归通过 | P1 |
| M-B 功能一 P1（VC-001~005） | 真机：按住说话 → 转写 → 发送 → AI 回复 TTS 播报 → 轮次落库可回退；含一次语音派发手机操控任务（与 M-A 联动） | P2 |

## 7. 验收标准汇总（供 ac-verifier）

| 验收项 | 验证方法 | 通过标准 | 关联用户故事 |
|---|---|---|---|
| 虚拟屏生命周期 | 单测 + 真机 logcat | 幂等创建/release 清理 force-stop 集合为空 | BG-001 |
| 跨屏执行面 | 单测（Robolectric + API 30 分支）+ 真机 | 虚拟屏窗口根节点可获取、节点动作生效、坐标手势返回固定错误文案 | BG-002 |
| 工具协议兼容 | 现有单测全量 + 新 schema 校验单测 | 不带 mode 行为与现状一致；schema 合法 JSON Schema | BG-003 |
| 后台感知 | 真机 | screenshot 返回虚拟屏帧且 OCR 文本与画面一致 | BG-004 |
| 权限引导 | 真机 UI 走查 | 未授权悬浮窗时后台调用返回引导文案 | BG-005 |
| 语音基座 | 单测 | FGS/渠道/权限状态机正确 | VC-001 |
| ASR | 单测 + 真机 | 状态机正确；真机离线识别一句话文本可编辑发送 | VC-002 |
| TTS | 单测 + 真机 | 回复播报、可停止；无引擎降级不崩溃 | VC-003 |
| 采集 | 单测 | 16k/16bit/mono + AEC 配置正确 | VC-004 |
| 通话页集成 | 真机端到端 | 语音轮次落库、回退/重试一致、并发守卫生效 | VC-005 |

## 8. 待确认事项

- [x] 已全部经调研报告第 13 节批准（10 项建议列生效），无遗留开放问题。
