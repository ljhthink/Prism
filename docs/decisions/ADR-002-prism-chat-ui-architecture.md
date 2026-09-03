# ADR-002: Prism 聊天 UI 架构（US-005）

> 依 CLAUDE.md 第十七节：引入新依赖 + 确立 UI 架构，必须写 ADR。
> 依 BR-interface-001：UI 设计须用户审核通过后方可实现，本 ADR 记录 2026-08-05 已获用户批准的设计方案。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted（US-005 已实现并通过 guardrail + ac-verifier 验收，2026-08-05；M8 里程碑审计 AUDIT-001 修复，2026-08-12） |
| 日期 | 2026-08-05 |
| 决策者 | 主 Agent + 用户（三项决策点已确认） |
| 关联文档 | [PRD](../PRD.md) / [prd.json US-005](../../prd.json) / [ADR-001](ADR-001-prism-tech-stack.md) |
| 上游调研 | [ADR-001 Context7 调研验证](ADR-001-prism-tech-stack.md#context7-调研验证2026-08-02)（Compose NavHost + StateFlow 标准模式） |
| 风险等级 | P2 跨模块（新增依赖 + 确立 UI 架构） |

## 背景（Context）

US-005「实现聊天 UI 骨架」是 M1 BYOK 聊天核心的 UI 入口。当前 [MainActivity.kt](../../app/src/main/java/io/prism/MainActivity.kt) 仅为 M0 脚手架（MaterialTheme + Greeting("Prism")）。需要确立：

1. 多屏幕导航架构（会话列表 / 聊天 / 设置三个主路由）
2. 聊天屏消息列表 + 输入框的骨架
3. 状态管理方式（StateFlow 暴露消息列表）
4. 新增导航与 ViewModel 相关依赖

经 Context7 在 ADR-001 阶段验证，Compose `NavHost + rememberNavController + StateFlow` 是聊天 UI 的标准模式。用户已确认三项决策点：**不引入 Hilt、M3 动态主题、Provider 选择器仅展示**。

## 决策（Decision）

### 4.1 导航架构：Navigation Compose NavHost 三路由

**决策**：使用 `androidx.navigation:navigation-compose` 建立 NavHost，配置三个主路由。

| 路由 | 屏幕 | 职责 |
|---|---|---|
| `conversation_list` | ConversationListScreen | 会话列表骨架（LazyColumn 占位） |
| `chat` | ConversationScreen | 聊天屏（消息列表 + 输入框，`startDestination`） |
| `settings` | SettingsScreen | 设置骨架（占位列表项） |

底部以 M3 `NavigationBar` 展示三个 Tab 切换。聊天为主入口。

**理由**：官方导航库，与 Compose 深度集成，类型安全路由，后续 US 可在 chat 路由下嵌套会话详情/模型选择等子路由。

### 4.2 状态管理：ViewModel + StateFlow

**决策**：

- 使用 `androidx.lifecycle:lifecycle-viewmodel-compose` 的 `viewModel()` 获取 `ConversationViewModel`
- `ConversationViewModel` 用 `MutableStateFlow<List<ChatMessage>>` 暴露 `messages`
- `sendMessage(text)` 追加用户消息 + 占位 AI 回复，更新 UI（暂不接 AI）

**理由**：StateFlow 是 Compose 官方推荐的响应式状态载体，`collectAsState()` 直接渲染，天然支持后续 US-006 流式 token 更新。

### 4.3 不引入 Hilt（用户确认）

**决策**：本 US 不引入 Hilt 依赖注入，手动 `viewModel()` 获取 ViewModel。

**理由**：骨架阶段避免过度设计（Karpathy Guidelines），ProviderConfigRepository 尚未被 UI 消费（Provider 选择器仅展示读 `activeProviderFlow`）。Hilt 留待后续 US 需要注入真实依赖时引入。

### 4.4 视觉风格：品牌定制色板（2026-08-05 修订，取代原「M3 动态主题」决策）

> 原决策为「M3 动态主题、无品牌色」（见本小节历史记录）。经用户要求按
> Continuous-learning 知识库美术资源索引优化 UI 后，本 ADR 修订为**品牌定制色板**。

**决策**：定义 Prism 品牌色板（[Color.kt](../../app/src/main/java/io/prism/ui/theme/Color.kt)）与
字体规范（[Typography.kt](../../app/src/main/java/io/prism/ui/theme/Typography.kt)），
经 [PrismTheme.kt](../../app/src/main/java/io/prism/ui/theme/PrismTheme.kt) 应用到 `MaterialTheme`。

**配色来源**（依 `wiki/design/color-resources.md` colorhunt 方法论，WCAG AA ≥4.5:1 校验）：
锚定 colorhunt 实时 AI 配色 [Mint Saber Neon](https://colorhunt.co/palette/211951836fff15f5baf0f3ff)（6k 赞）：

- `#836FFF` 靛蓝紫 → 主色（AI 智能）；`#15F5BA` 薄荷青 → 点缀（对话/活力）
- `#211951` 深靛蓝 → 暗色基；`#F0F3FF` 亮白 → 浅色基
- 原始色值饱和度偏高（如 `#836FFF` 对白字仅约 3.7:1），按 Material 3 语义色对主/辅色做明度微调以保证 AA，色相忠实原配色

**字体来源**（依 `wiki/design/font-resources.md`）：中文界面采用 Noto Sans CJK（系统默认
`FontFamily.SansSerif` 已映射 Roboto + Noto Sans CJK，无需打包字体文件，避免 5-15MB 体积膨胀）。

**图像来源（2026-08-05 插画修订，取代原「Unsplash 摄影位图」决策）**：AI 头像与空态
插画由摄影位图改为**原生矢量插画**（详见「插画策略修订」小节）——以 Compose
`Canvas`/`Path` 程序化绘制 + 手写 Lottie JSON 动画，无限分辨率、跟随明暗主题、品牌色一致。

**气泡与布局**：用户（右侧，`primaryContainer`）/ AI（左侧，`surfaceVariant`），
大圆角 `RoundedCornerShape(16.dp)`，消息正文 16sp。

### 4.5 Provider 选择器：仅展示（用户确认）

**决策**：聊天屏顶部显示当前激活 Provider 的下拉（读 `ProviderConfigRepository.activeProviderFlow`），仅展示不实现切换。

**理由**：切换逻辑属 US-007「实现 Provider 切换」，本 US 只在 UI 层展示当前激活状态，为 US-007 预留入口。

### 4.6 消息模型：UI 层数据类（非 ObjectBox 实体）

**决策**：US-005 仅定义 UI 层 `ChatMessage` 数据类（`id/role/content/timestamp`），不建 ObjectBox 实体。

**理由**：本 US 消息为本地 Mock（发送后占位 AI 回复），会话持久化属后续 US（记忆/会话历史）。避免过早引入消息数据模型。

### 4.7 插画策略修订：摄影位图 → 原生矢量插画 + Lottie（2026-08-05 用户要求）

> 原图像取自 Unsplash 摄影位图（`res/drawable-nodpi/*.jpg`）。用户反馈：插画「廉价低质、
> 简单复制粘贴、与技术背景不符」。按 `design-taste-frontend-v1` 反模板美学重绘并修订本决策。

**决策**：删除两张摄影 JPG，改用**原生矢量插画**，锚定 Prism 技术母题（三棱镜折射 /
知识图谱 / 神经连接），采用「几何线稿 + 清新填色」风格：

| 插画 | 实现 | 主题 |
|---|---|---|
| AI 头像 | `PrismAvatar`：Compose `Canvas`/`Path` 绘制正三棱镜剖面（主色描边）+ 薄荷折射光束（tertiary），`primaryContainer` 圆底 | 三棱镜折射 |
| 空态插画 | `KnowledgeGraphEmptyState`：Lottie 微动画（手写 JSON `assets/animations/prism_knowledge_graph.json`）——中央三棱镜 + 5 节点按序脉冲 + 连线渐入 | 知识图谱 |

**美术资源利用方式**（资源作参考/规范，不贴图）：

- `wiki/design/image-resources.md`（undraw.co）—— 矢量构图结构参考
- `wiki/design/icon-resources.md`（lucide/phosphor）—— 圆头 2px 线性描边语言，统一线宽
- `wiki/design/animation-resources.md`（lottiefiles）—— 微动画节奏（0.5–3s、循环、缓动）
- 品牌色板（Color.kt，Mint Saber Neon）—— 主色描边 / 辅色节点 / 薄荷折射光

**理由**：矢量插画无限分辨率、自动适配明暗主题、品牌色计算驱动，彻底消除「复制粘贴位图」
产生的廉价感，并与 Prism 技术背景强关联。

---

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| **Hilt + navigation-hilt** | 类型安全 ViewModel 注入、与导航深度集成 | 骨架阶段过度设计；ProviderConfigRepository 尚未注入点；增加 kapt 处理与构建复杂度 |
| **单一 Activity + 手动状态切换** | 零新增依赖 | 无法扩展多路由；M0 脚手架已需演进；后续会话/模型子路由不可扩展 |
| **Room/MVVM 复杂分层** | 数据层成熟 | 消息持久化属后续 US；US-005 仅需 UI 层 Mock，引入过度分层违背最小可用原则 |
| **M3 动态主题（原决策）** | 零定制成本、符合现代 Android 规范 | 无品牌识别度；经用户要求按美术资源索引优化后，改为品牌定制色板（4.4） |

---

## 后果（Consequences）

- 正面后果：
  - 确立可扩展的三路由导航骨架，后续 US 直接填充
  - StateFlow 状态管理天然支持 US-006 流式 token 实时更新
  - 不引入 Hilt，骨架轻量，编译与构建链路简单
  - M3 品牌定制色板 + 字体规范，视觉差异化强，符合 AI 科技感品牌识别
  - 配色/字体/图像均取自 Continuous-learning 知识库美术资源索引，沿用成熟选型方法论
  - 原生矢量插画 + Lottie 微动画：无限分辨率、明暗主题自适应、品牌色一致，消除位图廉价感
- 负面后果 / 代价：
  - 新增 `navigation-compose` + `lifecycle-viewmodel-compose` + `lottie-compose` 三个依赖（P2 变更，需 ADR）
  - 品牌定制色板取代动态主题，低版本（API 26-30）无系统动态色，仅用品牌色板（行为一致）
  - 配色需维护 WCAG AA 对比度，引入新色时须校验
  - 无 Hilt，后续引入时需重构 `viewModel()` 调用点
  - Verify-in-browser AC 受限（Android Compose 无浏览器渲染，需模拟器）
  - 手写 Lottie JSON 需人工维护透明度/位置关键帧，复杂动画后续可改用 lottiefiles 在线工具
- 需要同步更新的文档或代码：
  - `libs.versions.toml` + `app/build.gradle.kts`（新增 `lottie-compose` 等依赖）
  - 新增 `ui/components/PrismAvatar.kt`、`ui/components/KnowledgeGraphEmptyState.kt`、`assets/animations/prism_knowledge_graph.json`
  - 删除 `res/drawable-nodpi/ai_assistant_avatar.jpg`、`empty_chat_illustration.jpg`
  - `docs/decisions/README.md` 索引
  - `README.md` 文档索引

---

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| navigation-compose 版本与 compileSdk 34 兼容性 | 中 | 选用与 Compose BOM 2024.06.00 兼容的 navigation 版本（2.7.x）；编译验证 |
| Verify-in-browser 受限（无模拟器） | 低 | 该 AC 受限通过，改用 Compose Preview 截图人工核验 |
| 无 Hilt，后续重构 ViewModel 获取点 | 低 | 变更点集中在 ConversationScreen，后续引入 Hilt 时局部替换 |
| 动态主题在 API 26-30 无动态色 | 低 | 品牌定制色板不依赖动态色，浅/深双色板显式定义，行为一致 |
| 手写 Lottie JSON 结构或关键帧错误 | 中 | 用 lottiefiles 预览器/编辑器校验 JSON；若渲染异常回退为 Compose 原生 Canvas 动画 |
| 品牌配色是否满足 WCAG AA | 中 | 主/辅色经型号校验保证对白字 ≥4.5:1；新增色时按 color-resources 方法论复核 |

---

## 参考

- [ADR-001 Context7 调研验证](../../docs/decisions/ADR-001-prism-tech-stack.md#context7-调研验证2026-08-02)：Compose NavHost + StateFlow 标准模式
- [Jetpack Navigation Compose](https://developer.android.com/develop/ui/compose/navigation)
- [AndroidX Lifecycle ViewModel Compose](https://developer.android.com/jetpack/androidx/releases/lifecycle)
- Continuous-learning 知识库美术资源索引（配色/字体/图像选型方法论，本地仓库 `d:\s0611\code\Continuous-learning`）：
  - `wiki/design/color-resources.md` — colorhunt 4 色和谐 + WCAG AA
  - `wiki/design/font-resources.md` — Noto Sans CJK 选字
  - `wiki/design/image-resources.md` — Unsplash 图像选材
- [colorhunt Mint Saber Neon](https://colorhunt.co/palette/211951836fff15f5baf0f3ff) — 品牌配色锚定方案
- [prd.json US-005](../../prd.json)：6 条验收标准
