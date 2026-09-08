# ADR-047: 语音 V2（端侧流式全双工）与后台迁移法选型

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-09-06 |
| 决策者 | 用户（调研报告 v2 审核批准，含层1 实验排除 MIUI 权限层） |
| 关联文档 | PRD docs/prd-v1-b22-voice-v2-and-task-move.md；调研报告 v2 |
| 风险等级 | P1 |

## 背景

三轮真机实测：①语音 code=9 根因为**引擎进程无麦克风权限**（与调用方权限无关，联网案例印证）；②用户明确形态预期为"贾维斯/豆包式全双工"而非按压式语音识别（方向偏差确认）；③拼多多等风控 App 在 untrusted virtual display 启动成功但不渲染（层1 实验：开启"模拟辅助显示设备"仍失败 → 坐实 App 自身 display 检测）。

## 决策（Decision）

1. **语音 V2.0**：Shizuku `pm grant` 自动给识别引擎授 RECORD_AUDIO（现有架构立即恢复可用）。
2. **语音 V2.1**：sherpa-onnx 端侧流式 zipformer 中文 ASR（~25MB 量化）+ VAD 自动断句 + barge-in 打断 + 连续对话循环；按住说话保留为降级模式。VAD 候选 TEN VAD（Android JNI）与 sherpa 内置 silero，优先后者（集成面最小，一个 AAR 搞定 ASR+VAD）。
3. **后台迁移法**：launch_app(bg) 改"主屏真实启动 → Shizuku shell 反射 moveRootTaskToDisplay 迁移到虚拟屏"（ShadowAuto/Operator/miui+ 同源手法），fallback 保留 am start --display 与诚实降级。

## 备选方案（否决理由）

- 云端实时 ASR（Cyrene/豆包 RealtimeAPI 同款）：需 Key + 音频上云（隐私）+ 网络依赖——列为 V2.2 可选项。
- 容器虚拟化（VirtualApp 类）把风控 App 跑进容器：兼容性风险极高（拼多多对容器强检测）、维护成本不可控——否决。
- 仅诚实降级（风控 App 前台提示）：不满足用户核心诉求——降级仅作 fallback。

## 后果

- 正面：语音形态达标（免按压/全双工/离线）；风控 App 后台可用性大幅提升（迁移窗口期绕过启动期检测）。
- 代价：APK 增量 ~40MB（AAR+模型）；迁移法对 onResume 后二次检测的 App 仍可能失败（诚实边界）；会话状态机重构（hands-free 模式）。
- 文档同步：PRD v1-b22、AGENTS.md、decisions/README。

## 参考

调研报告 v2（15 源）：TEN Framework/TEN VAD、豆包 RealtimeAPI、sherpa-onnx 官方、AppDisplayMover、车载双屏 safeMoveTaskToDisplay、厂商兼容性图谱（MIUI 无边界窗口）、miui+ 逆向、scrcpy、SimpleAOA。
