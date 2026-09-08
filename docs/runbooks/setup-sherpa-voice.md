# Runbook：sherpa-onnx 端侧流式语音（贾维斯形态 V2.1）放置与激活

> 关联：批次22（PRD `docs/prd-v1-b22-voice-v2-and-task-move.md` SZ-201/SZ-202，ADR-047）。
> sherpa-onnx（Apache-2.0，k2-fsa 出品）为 Prism 提供完全离线的端侧流式中文识别——
> 免按压、边说边出字、静音自动断句。Prism 代码采用**条件集成**：AAR/模型文件已放置则自动
> 激活流式形态，缺失时自动降级系统语音引擎（按住说话），构建/运行均不报错。

## 0. ⚠️ 必须使用 static-link-onnxruntime 变体（2026-09-06 真机崩溃教训）

Prism 同时依赖 **onnxruntime-android**（RAG 嵌入，M3）。sherpa 标准 AAR 捆绑**自行裁剪的
libonnxruntime.so**（不导出 `OrtGetApiBase`），与 onnxruntime-android 的同名 so 二选一时
（pickFirsts）会选中 sherpa 版 → RAG 嵌入器 `OrtEnvironment` 类初始化失败毒化 →
**聊天页主线程 NoClassDefFoundError FATAL 循环崩溃**（详见
`docs/reports/2026-09-06-crash-ort-conflict-rca-and-fix.md`；同款案例 sherpa-onnx issue #3261/#3291）。

**因此只允许使用**：`sherpa-onnx-static-link-onnxruntime-<v>.aar`
（arm64-v8a/armeabi-v7a 仅含 libsherpa-onnx-jni.so，ORT 静态内链，不产生 so 冲突；
x86 系仍带共享 ORT 但被本项目 abiFilters 排除）。**禁止**在 packaging 里对
libonnxruntime.so 加 pickFirsts。

## 1. 文件清单与放置位置

> 批次22 修复轮（2026-09-06）：模型升级为 **small-bilingual 中英双语 int8**（识别准确率
> 显著优于 zh-14M 单语小模型；共 47.5MB）。

| 文件 | 放置位置 | 作用 |
|---|---|---|
| `sherpa-onnx-static-link-onnxruntime-<version>.aar`（约 36MB） | `app/libs/sherpa-onnx-android.aar` | 端侧识别运行时（ORT 静态内链，JNI native 库） |
| `encoder-epoch-99-avg-1.int8.onnx`（31.1MB） | `app/src/main/assets/models/streaming-zipformer-small-bilingual-zh-en/` | 流式 zipformer 编码器（int8） |
| `decoder-epoch-99-avg-1.onnx`（13.2MB） | 同上 | 解码器 |
| `joiner-epoch-99-avg-1.int8.onnx`（3.1MB） | 同上 | 联接器 |
| `tokens.txt`（约 0.1MB） | 同上 | 词表 |

> 以上均已放置（2026-09-06），本 runbook 供后续升级版本/换模型时参照。
> 文件不入版本控制（.gitignore 已排除大二进制），克隆仓库后需重新放置。

## 2. 下载（官方发布，国内镜像可用）

```powershell
# 1) AAR——必须 static-link 变体（见第 0 节；release 资产名自 1.13.x 起为 sherpa-onnx-<v>.aar）
curl.exe -L -o app\libs\sherpa-onnx-android.aar `
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-static-link-onnxruntime-1.13.7.aar"
# 直连超时可换镜像前缀：https://ghfast.top/https://github.com/...

# 2) 中文流式模型（small-bilingual 中英双语 mobile 包；文件名与代码常量一致）
curl.exe -L -o small-bilingual.tar.bz2 `
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile.tar.bz2"
tar -xjf small-bilingual.tar.bz2 `
  "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile/encoder-epoch-99-avg-1.int8.onnx" `
  "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile/decoder-epoch-99-avg-1.onnx" `
  "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile/joiner-epoch-99-avg-1.int8.onnx" `
  "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile/tokens.txt"
Copy-Item sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile\*.onnx `
  app\src\main\assets\models\streaming-zipformer-small-bilingual-zh-en\
Copy-Item sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-mobile\tokens.txt `
  app\src\main\assets\models\streaming-zipformer-small-bilingual-zh-en\
# 注意：fp32 的 encoder-epoch-99-avg-1.onnx（~90MB）代码不使用，不要复制（包内含多套格式，
# 仅提取上述 4 个 int8 文件）；旧 zh-14M 目录若存在应删除
```

## 3. 验证激活

1. 重新构建：`gradlew :app:assembleDebug`——构建日志无 sherpa 相关报错；
2. 单测：`gradlew :app:testDebugUnitTest --tests "io.prism.voice.*"`——
   `SherpaAsrEngine - isAvailable 检查 assets` 应执行（未放置时 Assume 跳过）；
3. 真机：进入语音通话——状态文案为「聆听中…（免按压）」且无按住说话按钮 = 流式引擎生效；
   若显示按住说话按钮 = 已降级系统引擎（检查上述文件与构建变体）。

## 4. 行为要点（排障）

- **首装拷贝**：模型从 assets 拷贝到 `filesDir/models/streaming-zipformer-small-bilingual-zh-en/`
  （首次通话约多 1-2 秒，此后直接用）；
- **断句节奏**：说完停顿约 1.2 秒自动提交（`rule2MinTrailingSilence`，代码可调）；
- **播报打断**：AI 播报时直接说话即可打断（RMS 阈值 0.25，环境太吵误打断时可在
  `VoiceSessionController.INTERRUPT_VOLUME_LEVEL` 调高）；
- **回声防护**：录音源为 `VOICE_COMMUNICATION`（系统 AEC），正常不会把 AI 播报识别成
  用户输入；若外放音量大导致误触发，优先戴耳机或调低媒体音量；
- **降级链路**：模型缺失/加载异常 → onError 文案 → 引擎回退系统识别（按住说话保留）。
