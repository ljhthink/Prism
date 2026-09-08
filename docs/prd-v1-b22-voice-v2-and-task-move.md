# 批次22：语音通话 V2（贾维斯形态）与后台迁移法 · 产品需求文档（PRD）

> 依 CLAUDE.md 第十一节：ac-verifier 将基于本 PRD 验收标准执行分层测试。
> 上游：调研报告 docs/reports/2026-09-06-voice-full-duplex-and-vd-render-research.md（**用户已批准**：层1 实验证实 untrusted display 检测为根因；批准按 V2.0 → V2.1 顺序开发）。

| 项目 | 内容 |
|---|---|
| 版本 | v1.0 |
| 日期 | 2026-09-06 |
| 作者 | AI（依用户批准的调研方案执行） |
| 关联文档 | ADR-047、ADR-046、调研报告（v2）、批次21 全套 |
| 风险等级 | P1（Shizuku 提权新增任务迁移能力 + 语音会话状态机重构） |

## 1. 目标

- **V2.0 快修包**：①语音识别引擎权限自动修复（Shizuku `pm grant`）；②后台 launch_app 改**迁移法**（主屏真实启动 → shell 反射 moveTaskToDisplay 迁移到虚拟屏），绕过风控 App 的 untrusted display 启动期检测。
- **V2.1 贾维斯形态**：端侧流式语音对话——sherpa-onnx 流式 zipformer 中文 ASR + VAD 自动断句（免按压）+ 播报打断（barge-in）+ 连续对话循环；按住说话保留为降级模式。

## 2. 非目标

- 云端 ASR/TTS（V2.2 另批评估）；唤醒词（KWS，未来增强）；iOS/Web 平台。

## 3. 用户故事与验收标准

### SZ-101: 引擎权限自动修复（语音 V2.0）

- [ ] `ShizukuShell.pmGrant(pkg, permission)`：shell `pm grant`（幂等，已授权返回成功）
- [ ] 语音通话启动时（Shizuku READY）自动给当前识别引擎包授 RECORD_AUDIO；未 READY 不影响原流程
- [ ] `SystemAsrEngine.currentEnginePackage(): String?` 暴露当前引擎包名
- [ ] 单测：pm grant 命令拼装；真机：code=9 消失（引擎授权后识别可用）

### SZ-102: 后台迁移法（操控 V2.0）

- [ ] `IShizukuUserService.moveTaskToDisplay(packageName, displayId): Int`：shell 进程内经 taskId 查询 + 反射 IActivityTaskManager（moveRootTaskToDisplay/moveTaskToDisplay 按版本探测）执行迁移
- [ ] `VirtualDisplayManager.launchApp(bg)` 三路：**迁移法优先**（主屏正常启动 → 等 ~400ms 渲染 → 迁移）→ fallback `am start --display` → 诚实文案
- [ ] 迁移失败/迁移后虚拟屏无窗口不误报成功；GUIDANCE 说明"主屏可能闪现目标 App 瞬间"（迁移法固有）
- [ ] 单测：迁移法路由逻辑；真机：拼多多在虚拟屏渲染（screenshot 非桌面帧）

### SZ-201: sherpa-onnx 流式引擎（语音 V2.1）

- [ ] `SherpaAsrEngine : AsrEngine`：流式 zipformer 中文（模型 int8 打包 assets 或首次下载）+ endpoint 自动断句
- [ ] 引擎选择：可用时优先 SherpaAsrEngine（V2.1 默认），失败/未就绪降级 SystemAsrEngine（按住说话降级模式保留）
- [ ] 单测：引擎工厂选择逻辑；真机：说话免按压、松口自动断句提交

### SZ-202: VAD 断句与打断（语音 V2.1）

- [ ] VAD（silero，sherpa 内置）驱动连续对话：IDLE→聆听（VAD 起点）→识别→提交→AI 回复→播报→**播报中 VAD 命中人声立即 stopTts 并回聆听**→循环
- [ ] 覆盖层 UI 适配免按压形态（状态提示"聆听中/思考中/播报中（可打断）"）；按住说话按钮保留为降级
- [ ] 真机：不按任何键说话即可触发；播报中说"停"可打断

## 4. 非功能需求

- ~~sherpa-onnx AAR + 模型合计 ≤45MB~~（**修订 2026-09-06 修复轮**：AAR 换 static-link 变体 36.1MB + 模型升级 small-bilingual int8 47.5MB ≈ 83.6MB——准确率优先于体积；onnxruntime-android（RAG 既有依赖）另计）；离线运行；音频不上云（隐私声明保留）
- ~~迁移法（SZ-102）~~（**修订 2026-09-06 修复轮三**：迁移法废除——真机实证任务级迁移成功但第三方窗口无法挂载 untrusted 显示面（DeepSeek 非风控 App 也 0 窗口）；SZ-102 改为 shell `am start --display` 直启 + 轮询 backgroundWindowPackage 窗口验证 + 未渲染诚实降级）；迁移法仅 Shizuku READY 时可用；未 READY 行为与批次21 完全一致（降级兼容）
- shell 命令仍仅白名单拼装（pm grant / am start --display / input -d（~~input --display~~ 修复轮八勘误：input 官方用法仅 `-d DISPLAY_ID`，长选项报 Unknown command 且 exit=0）/ am force-stop / dumpsys 诊断，无自由文本）

## 5. 里程碑与验收

| 里程碑 | 验收标准 | 风险等级 |
|---|---|---|
| V2.0 | SZ-101 + SZ-102 真机通过（语音可识别、拼多多虚拟屏渲染） | P1 |
| V2.1 | SZ-201 + SZ-202 真机通过（免按压全双工对话） | P1 |
| 回归 | 全量单测 0 失败；guardrail 审查通过 | — |

## 6. 待确认事项

- [x] 方案已由用户批准（调研报告 v2 审核通过，含 +40MB 模型与"风控 App 不保证 100%"边界确认）。
