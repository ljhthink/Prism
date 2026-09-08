# ADR-048：语音通话 v2.2 云端语音双轨与 RolePack 角色包框架

- 状态：已批准（2026-09-08，用户批复方案 docs/reports/2026-09-08-voice-v22-cloud-and-rolepack-research.md）
- 关联：ADR-044（语音技术选型）、ADR-047（语音v2与任务迁移）

## 决策

1. **云端 ASR**：阿里云百炼 Paraformer-realtime-v2（纯 WebSocket，DashScope Key 单件鉴权）；ISI 老版（AppKey+AccessKey+Token）不做。实现 `AliCloudAsrEngine implements AsrEngine`（isStreaming=true）。
2. **云端 TTS**：MiniMax T2A v2 WS（默认云端 TTS）+ GPT-SoVITS api_v2（用户自建 URL 高级项）；火山/Mossland 后置。
3. **双轨降级链**：用户手选引擎 → 启动探测失败自动回端侧（3s）→ 会话中断流当句回端侧。
4. **RolePack 角色包框架**：Schema v1（persona/style/voice/expression/memory）+ Loader（出厂仅内置通用人设 B0"Prism 助手"，第三方角色一律用户导入，存 filesDir/rolepacks/）。
5. **通用人设 B0**：中性"Prism 助手"人格（专业简洁/短句/TTS 友好/敏感操作确认），模板自带。
6. **边界**：昔涟角色导入为模板完成后的用户个人需求批次（23-C，按 Cyrene-Agent 原设计提炼，产物仅入用户设备，不入公开仓库）；Live2D 不做。

## 后果

- `AsrEngine/TtsEngine` 接口零改动（新增实现 + Factory 分支）；VoiceSessionController 流式状态机复用。
- 新增依赖：无（OkHttp 已有）。
- 风险：云端延迟/可用性依赖网络——由降级链兜底；Key 安全——复用 ApiKeyRepository（本地加密存储）。
