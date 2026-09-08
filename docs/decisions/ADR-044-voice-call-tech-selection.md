# ADR-044: 语音通话技术选型（端侧 sherpa-onnx ASR + 系统 TTS + 复用会话工具管线）

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-09-05 |
| 决策者 | 用户（调研报告第 13 节第 1~5 项批准） |
| 关联文档 | PRD docs/prd-v1-b20-voice-bg-control.md；ADR-036（手机操控）；ADR-017（设备档位） |
| 上游调研 | docs/reports/2026-09-05-voice-call-and-background-control-research.md 第 1~5 节 |
| 风险等级 | P2（全新子系统，纯新增模块，不动现有核心路径） |

## 背景（Context）

用户希望以语音与 Prism 交互并派发任务。项目当前完全没有语音能力（考古：全部 370 个 Kotlin 文件中 TTS/STT/AudioRecord 零命中），属全新子系统。平台定位为「纯端侧 App + BYOK 云 LLM、无自建服务」（AGENTS.md L224-230），排除一切需常驻服务器的方案。

## 决策（Decision）

**采用「端侧 ASR（sherpa-onnx 中文流式）+ 转写文本复用现有 `sendMessage` 会话工具管线 + 系统 TextToSpeech 播报」的分层架构；交互 P1 为按住说话，P2 升级 VAD 连续对话 + 打断；唤醒词与云端实时 API 明确不做。**

一句理由：端侧引擎满足离线/隐私/零自建服务约束，复用会话管线让语音天然继承全部工具生态（搜索/MCP/手机操控/todo），增量风险最小。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 仅系统 SpeechRecognizer | 零依赖、零体积 | 国产 ROM 引擎行为不一（多为云端实现）、离线性不可控、无流式增量 API 一致性；降级保留不为主选 |
| 小智生态（xiaozhi-esp32-server 自建 + 小智协议客户端） | 10.5k★ 成熟方案、打断/全双工开箱即用 | 需自建 Python 服务端，违背无自建服务定位 |
| pipecat / livekit-agents / TEN | 生产级语音编排、打断与轮次检测强 | 均需常驻服务器或 WebRTC 基建；Python 服务栈与 Kotlin 端侧不匹配 |
| Gemini Live API 直连 WebSocket | 无需自建服务、内置 VAD 打断、~$0.023/min | 用户已批准不做（维持 BYOK 现有云 LLM；成本与隐私边界）；留作未来 P3 选项 |
| 云端 STT/TTS（各 Provider BYOK） | 音质好 | 新增 BYOK 类目、隐私上传音频、离线不可用 |

## 后果（Consequences）

- 正面后果：离线可用、音频不出设备；语音轮次与文字轮次共用一套历史/回退/variants/RAG/工具熔断基础设施，实现与维护成本最低；sherpa-onnx 单 SDK 同时覆盖 P2（VAD/KWS）与未来 TTS 音色升级路径。
- 负面后果 / 代价：sherpa AAR + 中文模型带来 ~40-80MB 模型下载与首次等待；系统 TTS 音质与音色受设备影响；打断（barge-in）需 P2 自建（AEC + 能量/VAD 双阈值），复杂度后移。
- 需要同步更新的文档或代码：`app/build.gradle.kts`（libs AAR）、`AndroidManifest.xml`（RECORD_AUDIO / FOREGROUND_SERVICE_MICROPHONE）、`docs/decisions/README.md` 索引、AGENTS.md 批次记录。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 模型下载源可用性（GitHub/HF 在境内不稳） | 中 | 模型下载器支持断点/重试/取消；下载失败可一键切换系统 STT 路径；模型镜像地址做成常量便于替换 |
| TTS 外放被麦克风回采（未来 P2 打断时） | 中 | P1 即用 VOICE_COMMUNICATION + AcousticEchoCanceler 打好基线 |
| sherpa AAR ABI 体积 | 低 | 仅保留 arm64-v8a/armeabi-v7a；档位开关控制预载 |
| 语音派发与手机操控并发冲突 | 中 | 通话页思考中禁用录音入口；isTyping 守卫复用 |

## 参考

- 上游调研报告第 1~5 节（含 sherpa-onnx、小智生态、pipecat/LiveKit 对比、Gemini Live 成本与 AEC 实战全部一手来源）
- https://github.com/k2-fsa/sherpa-onnx （Apache-2.0，Kotlin API + Android 示例）
