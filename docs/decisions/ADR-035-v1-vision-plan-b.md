# ADR-035: v1 纯文本模型识图（方案 B：云端视觉旁路 + OCR 兜底）

> 从模板复制新建，补全 prd-uxr8.md 第 4.3 节方案 B（直传 + 云端旁路 + OCR 兜底），让纯文本 LLM 也能识图。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-19 |
| 决策者 | 主 Agent + 用户（D-5 确认"云端旁路 + OCR 都做"） |
| 关联文档 | [prd-v1-features.md](../prd-v1-features.md)（US-301~302）、[prd-uxr8.md](../prd-uxr8.md)（N3 方案 B）、[ADR-030](ADR-030-uxr8-b3-new-features.md) |
| 上游调研 | 纯文本模型识图方案 B 技术选型对比分析报告（TKN-V1-VISION-RESEARCH-001） |
| 风险等级 | P2（跨模块：新依赖 ML Kit + ProviderConfig 数据模型 + StreamEvent 接口 + ViewModel 接线 + 设置 UI） |

## 背景（Context）

Prism 已有 UXR8 N3 方案 A（图片 `image_url` 直传 + 纯文本端点 400 降级提示"当前模型不支持图片"）。用户要求补全方案 B：纯文本模型也能识图。调研结论：OCR 端侧选 **Google ML Kit Text Recognition v2 bundled 中文**（质量优、零 NDK、离线、不依赖 GMS/Firebase，F-Droid 友好；优于停维护的 Tesseract 与无官方 Gradle 库的 PaddleOCR）；云端旁路**复用现有 OpenAI 兼容协议**（chatCompletion 非流式 + image_url 输入 → text 输出），零新增协议层。

## 决策（Decision）

1. **云端视觉旁路（US-301）**：
   - `ProviderConfig` 新增 `isVisionFallback` 辅助角色（**不抢占 isActive**，规避单激活不变式冲突）
   - `StreamEvent.Error` 新增 `visionUnsupported: Boolean = false`（默认值，向后兼容）；`mapHttpError` 在「含图 + 400 + 视觉不支持关键词」时置 true
   - 新增 `VisionBypassConfigRepository`（DataStore：授权 consent + 自动开关 + 连续失败熔断，默认 3 次）
   - 新增 `VisionBypassOrchestrator`：编排「云端视觉旁路（cloudDescriber 注入）→ OCR 兜底（ocrExtractor 注入）→ Unavailable」降级链，云端失败计熔断、成功清零
   - `ConversationViewModel` 在收到 `visionUnsupported` 错误时：改写最后一条带图 user 消息（imageUrl=null + `【图片内容】D` 前缀）→ 移除失败 AI 占位 → 重新 `launchAnswer`（文本模型基于描述回答）
   - 隐私：设置页「识图」分区授权开关（明示图片外发视觉 Provider）+ 自动旁路开关；Provider 编辑弹层「设为视觉旁路 Provider」开关
2. **ML Kit OCR 兜底（US-302）**：
   - 依赖 `com.google.mlkit:text-recognition-chinese:16.0.1`（bundled，离线，+~8MB）
   - 新增 `OcrTextExtractor` 接口 + `MlKitOcrTextExtractor`（`Tasks.await` + Dispatchers.IO）；云端失败后本地提取文字（非空才以 `【图片文字】T` 前缀注入）

**关键设计约束**：旁路仅在用户已授权 + 自动开关开启 + 未熔断时触发；`VisionBypassOrchestrator` 以函数依赖注入（cloudDescriber/ocrExtractor），纯逻辑 JVM 可测。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| Tesseract（tess-two / Tesseract4Android） | 成熟 | 中文复杂场景精度低、2018 后基本停维护；elizaOS 已弃用 |
| PaddleOCR 端侧（Paddle-Lite） | 中文最优 | 无官方 Gradle 库需自编译 native + 模型 ~61MB，兜底路径成本不可接受 |
| 仅云端旁路（不做 OCR） | 零体积 | 无视觉配置时纯文本模型仍无法识图；OCR 离线兜底覆盖 F-Droid 无 GMS 场景 |
| Provider 内部自动旁路重试 | 改动小 | Provider 层无隐私授权/熔断上下文，职责膨胀；在 ViewModel 层编排更清晰 |

## 后果（Consequences）

- 正面后果：
  - 纯文本 LLM（DeepSeek 等）可基于视觉描述/OCR 文字"识图"回答
  - 云端旁路复用现有协议零新增协议层；OCR 离线兜底（F-Droid 友好）
  - 隐私授权明示 + 熔断防限流放大（kimi RPM=3 等）
- 负面后果 / 代价：
  - ML Kit bundled 新增 APK +~8MB
  - `StreamEvent.Error`/`ProviderConfig` 接口扩展（ObjectBox 自动迁移）
  - GLM-4V-Flash 不支持 base64（配置 keyHint 需标注，已避免默认用其作旁路）
- 需要同步更新的文档或代码：AGENTS.md、docs/prd-v1-features.md 执行状态表

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 隐私（图片外发第三方视觉 Provider） | 高 | 设置页常驻明示 + 授权开关（默认未授权）+ 一键关闭 |
| 限流放大（每次 400 旁路 = 2 次 LLM 调用） | 中 | 熔断（连续 3 次失败自动停用自动旁路）+ 复用现有 429 退避 |
| APK +8MB | 中 | bundled 是 F-Droid 刚需，接受；后续可评估 product flavors |
| OCR 仅对含文字图片有效 | 中 | OCR 结果非空才注入；物体/场景照片 OCR 空 → 落到原提示 |

## 参考

- [prd-uxr8.md](../prd-uxr8.md) 方案 B 定义（直传 + 云端旁路 + OCR 兜底）
- 调研报告：ML Kit TR v2 bundled 中文选型（含 F-Droid recall 双 flavor 先例）
- [prd-v1-features.md](../prd-v1-features.md) US-301~302 验收标准
