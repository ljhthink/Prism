# US-019 变更影响自检报告

> 依 CLAUDE.md 第九节「变更影响自检与跨模块通知」执行。
> 本报告为启动 `guardrail-enforcer` 前的强制前置产物。
>
> **版本历史**：
>
> - v1（2026-08-07）：首次自检，启动 guardrail round 1（TKN-US019-RAG-GUARDRAIL-001）
> - v2（2026-08-07）：guardrail round 1 发现 G-01~G-05 后修复，依 CLAUDE.md 7.2.5 执行二次自检，
>   启动 guardrail round 2（TKN-US019-RAG-GUARDRAIL-002）

| 项目 | 内容 |
| --- | --- |
| 任务令牌 | TKN-US019-RAG-INTEGRATION-001（v1） / TKN-US019-RAG-INTEGRATION-002（v2 修复后） |
| 风险等级 | P2 跨模块（改造 ConversationViewModel + ConversationScreen + 扩展 ChatStreamProvider 接口 + ChatMessage 数据模型变更 + 新增 RAG 注入器） |
| 关联 ADR | [ADR-012](../decisions/ADR-012-m3-rag-conversation-integration.md) |
| 关联考古报告 | [2026-08-07-us019-rag-integration-archaeology.md](2026-08-07-us019-rag-integration-archaeology.md) |
| 关联 guardrail round 1 | [2026-08-07-us019-rag-integration-guardrail.md](2026-08-07-us019-rag-integration-guardrail.md) |
| 自检执行者 | 主 Agent |
| 自检时间 | 2026-08-07（v1） / 2026-08-07（v2） |

## 1. 接口/契约变更自问

### 1.1 ChatStreamProvider 接口扩展（破坏性 + 向后兼容）

**变更前**：

```kotlin
fun streamChat(config: ProviderConfig, messages: List<ChatMessage>): Flow<StreamEvent>
```

**变更后**（ADR-012 5.4 方案 C）：

```kotlin
fun streamChat(
    config: ProviderConfig,
    messages: List<ChatMessage>,
    systemPrompt: String? = null,   // 新增，默认 null
    ragContext: String? = null      // 新增，默认 null
): Flow<StreamEvent>
```

**兼容性分析**：

- 两新参数均带默认值 `null`，既有调用零改动（向后兼容）。
- 实现类 `OpenAICompatibleProvider` 已同步实现新签名（[OpenAICompatibleProvider.kt:72](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt)）。
- blast-radius 扫描确认：生产代码仅 `ConversationViewModel.kt:161` 一处调用，已显式传 `systemPrompt` / `ragContext`。
- 测试代码三个 fake provider（`FakeChatStreamProvider` / `RecordingChatStreamProvider` / `MultiRoundRecordingProvider`）均已适配新签名。

**结论**：接口变更对调用方零冲击，所有调用点已同步迁移。

### 1.2 ChatMessage 数据模型变更（破坏性，内存态）

**变更前**：`source: String? = null`（单字段）

**变更后**（ADR-012 5.3）：

```kotlin
data class Citation(
    val index: Int,
    val documentTitle: String,
    val chunkIndex: Int?,
    val similarity: Double
)

data class ChatMessage(
    val id: Long,
    val role: Role,
    val content: String,
    val timestamp: Long,
    val sources: List<Citation> = emptyList()  // 替换 source
)
```

**兼容性分析**：

- `ChatMessage` 仅内存使用（无 ObjectBox 实体，无序列化持久化，见类注释）。
- blast-radius 扫描确认：无残留 `.source` 单字段引用，所有访问点已迁移到 `.sources`。
- `SourceChip` Composable 从接收 `source: String` 改为接收 `citation: Citation`。
- 既有 `ChatMessage(...)` 构造点（测试 sampleMessages）因 `sources` 有默认值 `emptyList()`，零改动。

**结论**：破坏性变更但影响可控（内存态 + 默认值兜底）。

### 1.3 ConversationViewModel 构造签名变更（破坏性）

**变更前**：`ConversationViewModel(providerRepository, provider)`

**变更后**：`ConversationViewModel(providerRepository, provider, embedder, knowledgeBaseRepository, ioDispatcher = Dispatchers.IO)`

**兼容性分析**：

- 生产唯一构造点：`ConversationViewModel.Factory`（[ConversationViewModel.kt:243-253](../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt)），已注入 `app.embedder` + `app.knowledgeBaseRepository`。
- `PrismApplication` 已暴露 `embedder`（[PrismApplication.kt:202](../../app/src/main/java/io/prism/PrismApplication.kt)）与 `knowledgeBaseRepository`（[PrismApplication.kt:176](../../app/src/main/java/io/prism/PrismApplication.kt)），DI 链路完整。
- 测试构造点（`ConversationViewModelTest` 11 处）已全部更新。
- `ioDispatcher` 默认 `Dispatchers.IO`，生产零改动；测试注入 `mainDispatcher` 保证虚拟时钟可控。

**结论**：构造签名变更但所有构造点已同步。

### 1.4 OpenAICompatibleProvider.buildRequestBody 签名扩展

**变更前**：`buildRequestBody(config, messages)`

**变更后**：`buildRequestBody(config, messages, systemPrompt = null, ragContext = null)`

**兼容性分析**：`internal` 可见性，仅同类内 `streamChat` 与同模块测试调用，所有调用点已更新。

## 2. 依赖与环境变更检查

| 项 | 变更 | 说明 |
| --- | --- | --- |
| 第三方依赖 | 无新增 | US-019 复用既有 `onnxruntime`（US-014）、`ObjectBox`（US-002）、`Ktor SSE`（US-006） |
| 锁文件 | 无改动 | `libs.versions.toml` 未触碰 |
| 环境变量 | 无 | RAG 配置当前仅内存 StateFlow，DataStore 持久化延后（ADR-012 5.2 备注） |
| `.env.example` | 无改动 | - |
| `Dockerfile` / CI 配置 | 无改动 | - |
| AndroidManifest | 无改动 | - |
| ProGuard 规则 | 无改动 | - |

**结论**：依赖与环境零变更，无供应链风险。

## 3. 依赖模块扫描（blast-radius）

### 3.1 ChatStreamProvider 接口

| 类型 | 位置 | 状态 |
| --- | --- | --- |
| 接口定义 | [ChatStreamProvider.kt:20](../../app/src/main/java/io/prism/network/ChatStreamProvider.kt) | 已扩展 |
| 唯一实现 | [OpenAICompatibleProvider.kt:53](../../app/src/main/java/io/prism/network/OpenAICompatibleProvider.kt) | 已适配 |
| 生产调用 | [ConversationViewModel.kt:161](../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt) | 已传新参数 |
| 测试 fake | `FakeChatStreamProvider` / `RecordingChatStreamProvider` / `MultiRoundRecordingProvider` | 已适配 |
| KDoc 引用 | [McpToolProvider.kt:6](../../app/src/main/java/io/prism/network/McpToolProvider.kt) | 仅注释，无影响 |

### 3.2 ChatMessage / Citation

| 类型 | 位置 | 状态 |
| --- | --- | --- |
| 数据类定义 | [ChatMessage.kt:28-54](../../app/src/main/java/io/prism/ui/model/ChatMessage.kt) | 已变更 |
| 生产使用 | [ConversationViewModel.kt](../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt)（多处构造 + `.sources` 访问） | 已迁移 |
| 生产使用 | [ConversationScreen.kt:427,432](../../app/src/main/java/io/prism/ui/chat/ConversationScreen.kt)（`message.sources` 渲染） | 已迁移 |
| 测试使用 | `ConversationViewModelTest` / `OpenAICompatibleProviderTest` | 已迁移 |
| 残留 `.source` 单字段引用 | **无** | 全部清除 |

### 3.3 ConversationViewModel 构造点

| 类型 | 位置 | 状态 |
| --- | --- | --- |
| 生产 Factory | [ConversationViewModel.kt:243](../../app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt) | 已注入新依赖 |
| 测试构造 | `ConversationViewModelTest` 11 处 | 已适配 |

### 3.4 新增组件 RagContextBuilder / RagTarget

| 类型 | 位置 | 状态 |
| --- | --- | --- |
| RagContextBuilder | [rag/RagContextBuilder.kt](../../app/src/main/java/io/prism/rag/RagContextBuilder.kt) | US-019 新增 |
| RagTarget | [rag/RagTarget.kt](../../app/src/main/java/io/prism/rag/RagTarget.kt) | US-019 新增 |
| 生产引用 | `ConversationViewModel` 引用两者 | 已集成 |
| 测试引用 | `RagContextBuilderTest` / `ConversationViewModelTest` | 已覆盖 |

### 3.5 SourceChip（ConversationScreen 内 private Composable）

| 类型 | 位置 | 状态 |
| --- | --- | --- |
| 定义 | [ConversationScreen.kt:451](../../app/src/main/java/io/prism/ui/chat/ConversationScreen.kt) | 已改为接收 `Citation` |
| 调用 | [ConversationScreen.kt:433](../../app/src/main/java/io/prism/ui/chat/ConversationScreen.kt)（`message.sources.forEach { SourceChip(it) }`） | 已迁移 |

**孤立残留清单**：无。所有调用点已同步迁移到新签名/新字段。

## 4. 跨模块影响表达（提交信息 footer 计划）

提交信息将使用 Conventional Commits + footer 表达跨模块影响：

```text
feat(rag): RAG 对话集成 (US-019)

- 扩展 ChatStreamProvider 接口支持 systemPrompt / ragContext 注入（ADR-012 5.4）
- ChatMessage.source 单字段 → sources: List<Citation> 多引用（ADR-012 5.3）
- ConversationViewModel 集成 RAG 检索 + 三级降级（ADR-012 5.5）
- ConversationScreen 新增 RAG 模式切换 + 多引用胶囊渲染 + 文案修正（ADR-012 5.8）
- 新增 RagContextBuilder / RagTarget 组件

BREAKING CHANGE: ChatStreamProvider.streamChat 接口签名变更（新增两可选参数，
默认 null 向后兼容）；ChatMessage.source → sources: List<Citation>（内存态，
无持久化影响）。
Refs: US-019
Relates-to: ADR-012, M3-RAG
```

## 5. README.md 索引更新检查

| 文档 | 状态 |
| --- | --- |
| `docs/decisions/README.md` | 已添加 ADR-012 索引（git diff 确认） |
| `README.md` 报告索引 | 待 commit 前补充本报告 + guardrail + acceptance 报告链接 |
| `prd.json` | 待 commit 前标记 US-019 状态 |

## 6. 已知预存问题（与本 US 无关，记录备查）

### 6.1 mergeDebugJavaResource 打包失败（US-014 引入 PDFBox 遗留）

**症状**：`gradlew assembleDebug` 在 `mergeDebugJavaResource` 阶段失败：

```text
4 files found with path 'META-INF/DEPENDENCIES' from inputs:
  - org.apache.pdfbox:pdfbox:3.0.8
  - org.apache.logging.log4j:log4j-api:2.24.3
  - org.apache.pdfbox:fontbox:3.0.8
  - org.apache.pdfbox:pdfbox-io:3.0.8
```

**根因**：US-014 引入 PDFBox 时未在 `app/build.gradle.kts` 添加 `packaging { resources { excludes += ... } }` 块排除重复 META-INF 资源。

**影响**：APK 打包失败，但单元/集成测试不触发该任务，不阻塞 ac-verifier。

**与 US-019 关系**：无关。US-019 未触碰 PDFBox / build.gradle.kts。

**处置建议**：作为独立 P0 配置修复（添加 packaging excludes），不在 US-019 范围内处理。需用户决策是否纳入本轮或单独修复。

## 7. 主 Agent 自问（CLAUDE.md 7.3）

启动 `guardrail-enforcer` 前，主 Agent 自答以下两问：

1. **眼下最没有把握的事情是什么？**
   - RAG 三级降级中「search 返回非空但阈值过滤后为空」这一分支，单元测试仅覆盖了「search 返回空 list」（空库场景），未覆盖「search 返回 N 条但全部 similarity < 0.3」场景。该分支走的是 `filtered.isEmpty() return@withContext null`，逻辑与空库分支相同，但测试覆盖有缺口。
   - **缺失正向测试**：没有任何测试覆盖「RAG 检索成功 → 注入 systemPrompt + ragContext + citations」的快乐路径。当前 4 个 RAG 测试全是降级/关闭场景。这是 AC-3（标注引用来源）的最大验证缺口，需 ac-verifier 补充。
   - `ConversationScreen` 的 RAG 模式切换 UI 与多引用胶囊渲染无 Compose UI 测试（项目当前无 UI 测试框架，guardrail 需评估是否豁免）。

2. **关于当前情况，最大的遗憾是什么？没有意识到什么？**
   - **遗憾**：`RagModeSelectorSheet` 中「指定库检索」选项被标注为「暂未开放」，但 `RagTarget.SpecificLibrary(kbId)` 数据模型与 `buildRagPlan` 分支已完整实现——UI 层只是没接入知识库选择器。这是「数据层就绪、UI 未接入」的半成品状态，需在 ADR 或 PRD 中明确标注范围。
   - **未意识到**：`buildRagPlan` 在 `RagTarget.SpecificLibrary` 模式下若 `kbId <= 0` 仍会传给 `search`，触发 `require(knowledgeBaseId >= 0)` 抛 `IllegalArgumentException`，被外层 `runCatching` 捕获降级。这是隐式容错，但未在测试中显式验证。
   - **未意识到**：RAG 检索 ~100ms（embed 串行持锁）会延迟首 token 到达，用户感知为「打字指示器持续显示 ~100ms 后才开始流式」。ADR-012 5.6 已记录此延迟，但未做用户体验缓解（如「检索中」与「思考中」分阶段提示）。当前 `TypingIndicator(isRagOn = ragTarget !is RagTarget.Off)` 仅静态显示「正在检索知识库…」，未区分检索阶段与流式阶段。

## 8. 自检结论

| 检查项 | 结果 |
| --- | --- |
| 接口/契约变更已识别 | ✅ 全部同步迁移 |
| 依赖与环境无变更 | ✅ |
| 依赖模块扫描完成 | ✅ 无孤立残留 |
| 跨模块影响表达就绪 | ✅ commit footer 已拟定 |
| README 索引更新计划就绪 | ✅ 待 commit 前补 |
| 已知预存问题已记录 | ✅ mergeDebugJavaResource |
| 主 Agent 自问已答 | ✅ 3 项薄弱点已识别，供 guardrail 重点审查 |

**自检通过，可启动 `guardrail-enforcer`。**

待 guardrail 重点关注的薄弱点（主 Agent 自问结论）：

1. RAG 正向快乐路径测试缺失（AC-3 验证缺口）
2. `RagTarget.SpecificLibrary` 半成品状态（数据就绪 UI 未接入）
3. `SpecificLibrary(kbId <= 0)` 隐式容错未显式测试
4. RAG 检索延迟用户体验缓解缺失

---

## 9. v2 修复后二次自检（CLAUDE.md 7.2.5 强制）

guardrail round 1（TKN-US019-RAG-GUARDRAIL-001）发现 G-01~G-05（1 HIGH + 4 MEDIUM），主 Agent 已修复。依 CLAUDE.md 7.2.5，重新提交 guardrail 前必须再次执行完整影响自检。

### 9.1 修复清单

| 编号 | 严重度 | 修复内容 | 影响文件 |
| --- | --- | --- | --- |
| G-01 | HIGH | `buildRagPlan` 内层 `runCatching{}.getOrElse{}` 改为显式 try-catch，先 `catch(CancellationException){throw e}` 再 `catch(Exception)`（embed + search 两处） | `ConversationViewModel.kt` |
| G-02 | MEDIUM | `buildRagPlan` 返回类型从 `RagPlan?` 改为 sealed `RagBuildResult`（Success/EmbedFailed/NormalChat）；`sendMessage` 按 when 分支差异化用户感知（EmbedFailed → appendDelta 提示） | `ConversationViewModel.kt` |
| G-03 | MEDIUM | 整个 RAG 异常分支移除 `simpleName` 暴露到 appendDelta；改为 `android.util.Log.w` 记录，用户侧无感 | `ConversationViewModel.kt` |
| G-04 | MEDIUM | `RagTarget.SpecificLibrary` 添加 `init { require(kbId > 0) }` 校验，与 KDoc 一致 | `RagTarget.kt` |
| G-05 | MEDIUM | 新增 4 测试（正向快乐路径 / 阈值过滤空 / SpecificLibrary kbId 校验 / SpecificLibrary 检索）+ 更新 embed 失败测试断言 | `ConversationViewModelTest.kt` |
| 配套 | - | 历史过滤器扩展：`filterNot { it.id == aiId \|\| (ASSISTANT && empty) }`，防止 embed 失败降级提示进请求历史 | `ConversationViewModel.kt` |

### 9.2 二次接口/契约变更自问

| 变更项 | 类型 | 影响面 | 兼容性 |
| --- | --- | --- | --- |
| `RagBuildResult` sealed interface | 新增 private | 仅 `ConversationViewModel.kt` 内部 | 无外部影响（private） |
| `buildRagPlan` 返回类型 `RagPlan?` → `RagBuildResult` | private 方法签名变更 | 仅 `sendMessage` 调用 | 无外部影响（private） |
| `RagTarget.SpecificLibrary` init 块 | 破坏性（kbId<=0 抛异常） | 所有构造 `SpecificLibrary(kbId)` 处 | 见 9.3 扫描 |
| `TAG` 常量 + `Log` import | 新增 | 仅 `ConversationViewModel` | 无影响 |
| 历史过滤器 `it.id == aiId \|\|` | 行为变更 | 所有 `sendMessage` 调用 | 更正确（排除当前消息），既有测试全过 |

### 9.3 二次依赖模块扫描（G-04 SpecificLibrary init 块影响）

`RagTarget.SpecificLibrary` 现在要求 `kbId > 0`，扫描所有构造点：

| 位置 | kbId 值 | 影响 |
| --- | --- | --- |
| `ConversationViewModelTest.kt:408` `setRagTarget switches` | `42L` | ✅ >0，通过 |
| `ConversationViewModelTest.kt:537` `SpecificLibrary rejects` | `0L` / `-1L`（故意触发） | ✅ 测试预期抛异常 |
| `ConversationViewModelTest.kt:572` `specific library retrieves` | `1L` | ✅ >0，通过 |
| 生产代码 `ConversationScreen.kt` RagModeSelectorSheet | 不构造 SpecificLibrary（UI 标注「暂未开放」） | ✅ 无影响 |

**结论**：G-04 init 块不破坏任何既有调用点。生产 UI 未接入 SpecificLibrary 构造，测试全部使用合法 kbId 或故意测试异常路径。

### 9.4 二次测试验证

```text
:app:testDebugUnitTest --tests io.prism.rag.RagContextBuilderTest
                       --tests io.prism.network.OpenAICompatibleProviderTest
                       --tests io.prism.ui.chat.ConversationViewModelTest
BUILD SUCCESSFUL
```

| 测试类 | 用例数 | 通过 | 跳过 | 失败 |
| --- | --- | --- | --- | --- |
| `RagContextBuilderTest` | 7 | 7 | 0 | 0 |
| `OpenAICompatibleProviderTest` | 22 | 22 | 0 | 0 |
| `ConversationViewModelTest` | 18 | 18 | 0 | 0 |
| **合计** | **47** | **47** | **0** | **0** |

新增/更新测试：

- `rag on with matching chunks injects system prompt rag context and citations`（G-05 正向快乐路径）✅
- `rag on with below threshold results degrades to normal chat`（G-07 阈值过滤空）✅
- `SpecificLibrary rejects non positive kbId`（G-04 校验）✅
- `rag on with specific library retrieves only that library chunks`（G-04 指定库检索）✅
- `rag on with embedder failure degrades to normal chat`（G-02 断言更新）✅

### 9.5 二次自检结论

| 检查项 | v1 结论 | v2 结论 |
| --- | --- | --- |
| 接口/契约变更已识别 | ✅ | ✅（新增 private sealed interface，无外部影响） |
| 依赖与环境无变更 | ✅ | ✅（仅新增 android.util.Log，Android SDK 内置） |
| 依赖模块扫描完成 | ✅ | ✅（SpecificLibrary init 块不破坏既有调用） |
| 跨模块影响表达就绪 | ✅ | ✅（commit footer 不变） |
| README 索引更新计划就绪 | ✅ | ✅ |
| 已知预存问题已记录 | ✅ | ✅（mergeDebugJavaResource 仍未修复，与本 US 无关） |
| 主 Agent 自问已答 | ✅ | ✅（v1 薄弱点 1/3 已通过 G-04/G-05 修复消除；薄点 2/4 为设计取舍，guardrail 已认可） |

**二次自检通过，可重新启动 `guardrail-enforcer`（round 2）。**

### 9.6 round 2 主 Agent 自问（CLAUDE.md 7.3）

1. **眼下最没有把握的事情是什么？**
   - `RagBuildResult` sealed interface 是修复 G-01/G-02 引入的新抽象。虽为 private 且职责单一，但 guardrail 可能质疑是否过度设计（Karpathy「Simplicity First」）。我的判断：sealed interface 比「RagPlan? + 外层 runCatching 区分降级原因」更清晰，且 Kotlin idiom，不算过度。
   - 历史过滤器扩展 `it.id == aiId ||` 是 G-02 配套修复，改变了所有 sendMessage 调用的历史构建行为。虽既有测试全过，但 round 1 guardrail 未审查此变更，round 2 需重点确认无回归。
   - `android.util.Log.w` 是项目首次引入 Android 日志 API（之前无结构化日志基建）。CLAUDE.md 19.1 要求结构化 JSON 日志，但项目暂无基建，`Log.w` 是过渡方案。guardrail 可能建议是否应建一个轻量 Logger 抽象。

2. **最大的遗憾是什么？没有意识到什么？**
   - **遗憾**：G-01 修复本应在 v1 编码时就避免——`OpenAICompatibleProvider` 已有正确的 `catch(CancellationException){throw e}` 模式，ViewModel 层应一致。这是「同一项目内模式不一致」的典型，应在编码时对照既有模式。
   - **未意识到**：G-02 配套的历史过滤器变更（`it.id == aiId`）实际上修复了一个 v1 就存在的潜在 bug——如果上一轮 AI 消息因任何原因非空（如 embed 失败提示），它会被错误地纳入下一轮请求历史。这个修复是 guardrail 倒逼发现的，值得记入 behavioral-rules（BR-interface-004 提议：请求历史必须排除当前 aiId）。
