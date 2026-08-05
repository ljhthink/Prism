# 验收测试报告 —— 新配置组件接入知识库/MCP/Skill 配置详情页

> 由 ac-verifier 子 Agent 生成。依 CLAUDE.md 第十一节与 `test-architect` skill 分层验证方法论。
> 变更范围：`app/src/main/java/io/prism/ui/` 下 knowledge / capabilities / components / theme / chat 的 v0.4 配置弹层与组件加固。

| 项目 | 内容 |
|---|---|
| 执行 Agent | ac-verifier |
| 任务令牌 | TKN-UI-CONFIG-003 |
| 验收日期 | 2026-08-05 |
| 关联 PRD | [docs/PRD.md](PRD.md)（US-002 MCP / US-003 知识库 / US-004 Skills / 设计规范 v0.4） |
| 关联 ADR | ADR-001-prism-tech-stack、ADR-002-prism-chat-ui-architecture |
| guardrail 报告 | [docs/reports/2026-08-05-ui-config-guardrail.md](2026-08-05-ui-config-guardrail.md)（TKN-UI-CONFIG-002 已复审通过） |

## 0. 上下文重建摘要

- 项目阶段：M0 脚手架 / UI 骨架，数据层为 Mock（Provider/MCP/Skill 配置均未接真实网络）。本次为**纯 Compose UI 变更，无业务逻辑**。
- 验收标准来源：本次任务的 6 条验收标准（任务描述），对应 PRD US-002/003/004 可视化入口 + 设计规范 v0.4 实体化重构。
- 已知基线：`ConversationViewModelTest` 有 3 个 Main dispatcher 环境既有失败（协程环境，与本次 UI 无关），数据层测试须全绿。

## 1. 验收标准执行结果

| AC ID | 验收标准 | 验证方法 | 结果 | 证据 |
|---|---|---|---|---|
| AC-1 | 编译通过：`:app:compileDebugKotlin` 无错误 | 编译 | **通过** | `.\gradlew.bat :app:compileDebugKotlin` → `BUILD SUCCESSFUL in 3s`，exit 0；同时 `:app:testDebugUnitTest` 已隐式完成编译（未见编译错误） |
| AC-2 | 知识库导入弹层可被顶栏「+」与「导入新文档」卡触发，含来源类型/目标库/路径/分片设置/开始导入/取消 | 静态+编译 | **通过** | 顶栏「+」[KnowledgeBaseScreen.kt L120-124](app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt#L120-L124) 置 `importVisible=true`；「导入新文档」卡 [L142](app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt#L142) 同触发；`ImportSheet()` [L168-240](app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt#L168-L240) 含来源类型(PrismSegmented)/目标库(PrismSegmented)/路径(PrismField)/分片大小(PrismField)/开始导入(PrismButton)/取消(Ghost) |
| AC-3 | MCP 配置弹层含传输类型/Base URL/Token（掩码）/测试连接/启用/删除 | 静态+编译 | **通过** | `McpConfigSheet()` [CapabilitiesScreen.kt L243-298](app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt#L243-L298)：传输类型(PrismSegmented)/Base URL(PrismField)/Token(`secret=true`)/测试连接(PrismButton)/启用(PrismSwitch)/删除(Danger 按钮) |
| AC-4 | Skill 详情弹层含安装参数/启用/更新/卸载 | 静态+编译 | **通过** | `SkillDetailSheet()` [CapabilitiesScreen.kt L352-379](app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt#L352-L379)：安装参数(PrismField)/启用(PrismSwitch)/更新配置(PrismButton)/卸载 Skill(Danger) |
| AC-5 | 组件视觉符合 v0.4（实体面板色、12dp 圆角、克制动画） | 静态代码核对 | **通过** | 实体色板 `PrismPanel/Panel2/Panel3`（Color.kt L80-86）；PrismSegmented 12dp 圆角 [PrismSegmented.kt L52](app/src/main/java/io/prism/ui/components/PrismSegmented.kt#L52)；PrismSheet 顶角 18dp 实体面板 [PrismSheet.kt L39](app/src/main/java/io/prism/ui/components/PrismSheet.kt#L39)；PrismField 输入 10dp [PrismField.kt L58](app/src/main/java/io/prism/ui/components/PrismField.kt#L58)；PrismSheetHost 仅 opacity/transform 动画 [PrismSheetHost.kt L34-69](app/src/main/java/io/prism/ui/components/PrismSheetHost.kt#L34-L69) |
| AC-6 | 无回归：既有数据层测试（ProviderConfig/KnowledgeChunk/ApiKey）全部通过 | 单元测试回归 | **通过** | 数据层 101 个用例全绿（见 §2.2） |

## 2. 分层测试

### 2.1 静态分析

| 检查项 | 命令/方法 | 结果 | 证据 |
|---|---|---|---|
| 编译 | `.\gradlew.bat :app:compileDebugKotlin` | **通过** | `BUILD SUCCESSFUL in 3s`，exit 0 |
| 硬编码密钥扫描 | Grep `(api_key|token|secret|password)\s*=\s*["']...` | **通过** | `app/src/main` 无匹配 |
| PrismGlassStrong 误用 | Grep `PrismGlassStrong` | **通过** | 仅 [Color.kt L143](app/src/main/java/io/prism/ui/theme/Color.kt#L143) 废弃别名定义本身，无 background 使用点残留（Q2 修复落地） |
| 内部 IP/域名泄露 | Grep `10.x/192.168.x/172.x/127.0.0.1/localhost` | **通过** | 仅 ProviderPresets.kt L36 `http://localhost:11434`（Ollama 默认本地端点，非密钥） |
| 敏感字段明文 | Grep `password/secret/api_key` 字面量 | **通过** | `app/src/main` 无字面量密钥 |
| 不安全强转 | Grep `as PrismApplication` | **通过** | 无残留，已改 `as?`（Q5 修复落地） |

### 2.2 单元测试（`:app:testDebugUnitTest`，118 用例 / 3 失败 / 13 跳过）

**数据层测试 —— 全部通过（AC-6 依赖）：**

| 测试类 | 用例数 | 通过 | 失败 | 结果 |
|---|---|---|---|---|
| ProviderConfigRepositoryTest | 35 | 35 | 0 | 通过 |
| ProviderConfigEdgeCaseTest | 17 | 17 | 0 | 通过 |
| ProviderConfigDemo | 1 | 1 | 0 | 通过 |
| KnowledgeChunkCrudTest | 9 | 9 | 0 | 通过 |
| KnowledgeChunkEdgeCaseTest | 9 | 9 | 0 | 通过 |
| ApiKeyRepositoryTest | 14 | 14 | 0 | 通过 |
| ApiKeyEdgeCaseTest | 16 | 16 | 0 | 通过 |
| **数据层合计** | **101** | **101** | **0** | **通过** |

**UI 层测试（ConversationViewModelTest，4 用例 / 1 通过 / 3 失败）：**

| 测试 | 结果 | 失败原因 |
|---|---|---|
| `ignores blank input` | 通过 | 提前 return，未触及协程 |
| `appends user and assistant messages` | 失败 | Main dispatcher 未初始化 |
| `trims whitespace` | 失败 | 同上 |
| `consecutive sends assign increasing ids` | 失败 | 同上 |

**3 个失败的根因归类（与本次变更无关）：**
- 异常：`IllegalStateException: Module with the Main dispatcher had failed to initialize`，`Caused by: Method getMainLooper in android.os.Looper not mocked`。
- 触发点：[ConversationViewModel.sendMessage L54](app/src/main/java/io/prism/ui/chat/ConversationViewModel.kt#L54) 的 `viewModelScope.launch { ... }`。`viewModelScope` 依赖 `Dispatchers.Main`，而该测试为纯 JUnit（无 Robolectric、未调用 `Dispatchers.setMain`），故 Main dispatcher 无法初始化。
- 与本次变更关系：本次变更为纯 Compose UI 配置弹层，**未修改 ConversationViewModel 任何逻辑**；失败源于测试环境缺 Main dispatcher 设置（`kotlinx-coroutines-test` 已引入但未 setMain），属**既有环境性失败**。判定为**与本次变更无关**。

**跳过（13 个）：** 三个 *PerformanceBenchmark*（KnowledgeChunk 4 / ProviderConfig 5 / ApiKey 4）按设计跳过，非失败。

**覆盖率：** UI 层本次为纯 Compose 声明式组件，无可直接单测的纯函数/分支逻辑；数据层覆盖率由 guardrail 与既有基准覆盖。本次不适用覆盖率门禁（语句≥90%/分支≥80%），因无新增业务逻辑代码。

### 2.3 集成测试

本次变更为纯 UI（Compose 声明式），无网络/数据库/外部服务/事务边界。不存在可测的跨模块接口契约变更（guardrail 已验证 import/依赖无破坏）。**不适用。**

### 2.4 E2E 测试

**compose-ui-test 环境不支持**，降级为静态 + 编译验证。原因：
- `app/build.gradle.kts` 未声明 `androidx.compose.ui:ui-test-junit4` / `ui-test-manifest` 依赖；
- 无 `androidTest` 源集（`Glob app/src/androidTest/**` 无文件）；
- 无设备/模拟器可运行 instrumented 测试。

因此 ImportSheet/McpConfigSheet/SkillDetailSheet 的交互行为（点击触发展示、字段输入、掩码显示）通过**源码级静态验证**（事件绑定、状态提升、触发路径）与**编译通过**共同确认，未做运行时 UI 点击验证。此为环境限制导致的覆盖缺口，见 §7 风险。

## 3. 极端/边缘场景补充

| 场景 | 验证方式 | 结果 |
|---|---|---|
| `PrismSegmented` 空 options | 静态 | **通过**：`if (options.isEmpty()) return` 早退位于除法之前 [PrismSegmented.kt L48](app/src/main/java/io/prism/ui/components/PrismSegmented.kt#L48)，避免除零崩溃（Q4 修复落地） |
| `PrismSegmented` selected 不在 options | 静态 | **通过**：`indexOf().coerceAtLeast(0)` 收敛，不触及除法 |
| `PrismField` secret 掩码 | 静态 | **通过**：`PasswordVisualTransformation()` 作用于 `BasicTextField.visualTransformation` [PrismField.kt L70](app/src/main/java/io/prism/ui/components/PrismField.kt#L70)；未传 `secret` 的字段默认 `VisualTransformation.None`，行为与修复前一致 |
| `PrismSheetHost` 退出动画 | 静态 | **通过**：内层 `visible = visible`，关闭时 `slideOutVertically`(260ms) 与遮罩 `fadeOut`(200ms) 并发，实现下滑+淡出退出（Q3 修复落地） |
| ImportSheet `PrismSegmented` 尺寸 | 静态 | **通过**：两处 trailing 均加 `Modifier.width(160.dp)` [KnowledgeBaseScreen.kt L188/L207](app/src/main/java/io/prism/ui/knowledge/KnowledgeBaseScreen.kt#L188)，避免挤压输入框（Q1 修复落地） |
| Token 字段无日志/未持久化 | 静态 | **通过**：token 仅存于局部 Compose 状态，未进入任何 sink |

## 4. 性能回退检查

本次为纯 UI 变更，无接口/函数执行路径变更，不涉及数据层性能基线（`docs/reports/perf/` 下 ObjectBox/ApiKey/ProviderConfig 基线均针对数据层，未受影响）。既有性能基准视为未回退。**不适用新计时。**

## 5. 安全检查

| 检查项 | 结果 | 证据 |
|---|---|---|
| 敏感凭据掩码（Token/API Key） | **通过** | `McpConfigSheet` Token 字段 `secret = true` [CapabilitiesScreen.kt L281](app/src/main/java/io/prism/ui/capabilities/CapabilitiesScreen.kt#L281) → `PasswordVisualTransformation`；纵深防御确认（S1 修复落地） |
| 无硬编码密钥/令牌/密码 | **通过** | `app/src/main` grep 无匹配；`ProviderPresets.apiKeyRef` 均为标识符，指向 ApiKeyRepository 加密存储 |
| 无内部 IP/占位符真实端点泄露 | **通过** | 仅 `https://api.example.com/mcp`（占位）与 `localhost:11434`（Ollama 默认），非真实端点 |
| 注入面 | **通过** | 纯 Compose，无 SQL/命令/URL 拼接；文本均以 `Text(...)` 字面量渲染，无 XSS/注入 sink |
| 敏感操作权限 | 不适用 | 数据层为 Mock，无真实删除/修改后端操作；UI 删除按钮为占位 `onClick={}` |
| 日志泄露 | **通过** | 本次变更无日志输出；token 不进日志（guardrail 已确认） |

## 6. 回归测试

| 套件 | 总数 | 通过 | 失败 | 跳过 | 结果 |
|---|---|---|---|---|---|
| 全部单元测试 | 118 | 102 | 3 | 13 | 通过（3 失败为既有 Main dispatcher 环境问题，与本次无关） |
| 数据层（ProviderConfig/KnowledgeChunk/ApiKey） | 101 | 101 | 0 | 0 | **通过（AC-6）** |

**回归判定：** 数据层 101 个用例全绿，无因本次 UI 变更引入的回归。3 个失败均为 `ConversationViewModelTest` 既有的 Main dispatcher 环境性失败（见 §2.2），**非本次变更所致**，不触发「回归一票否决」。

## 7. 未覆盖项与风险

| 项 | 原因 | 风险 |
|---|---|---|
| 运行时 UI 交互验证（弹层触发/字段输入/掩码显示） | compose-ui-test 未配置且无 androidTest 环境 | 中：交互行为仅经静态验证，未经真实点击/渲染；建议后续引入 compose-ui-test（Roborazzi 截图含视觉回归）覆盖 PrismSegmented 边界、PrismSheetHost 动画、PrismField 布局 |
| v0.4 视觉一致性（颜色/圆角/动画观感） | 无截图测试工具 | 低：静态代码 token 与规范一致，但观感需真机/截图人工确认（guardrail 亦标注需人工视觉确认） |
| 3 个 Main dispatcher 既有失败 | 测试环境未 setMain | 低：既有问题，与本次 UI 无关；建议后续为 ConversationViewModelTest 补 `Dispatchers.setMain(StandardTestDispatcher)` 修复 |
| lottie-compose 6.4.0 依赖 | 未集成依赖漏洞扫描 | 低：guardrail 建议 CI 集成 Dependabot/OSS Index；本次未直接引用 |

## 8. 结论

- [x] **通过**

6 条验收标准全部验证通过（AC-1 编译通过、AC-2/3/4 三个配置弹层结构完整、AC-5 视觉规范代码一致、AC-6 数据层回归全绿）。3 个单元测试失败经证据确认为 `ConversationViewModelTest` 既有的 Main dispatcher 协程环境问题，与本次纯 UI 变更无关。无阻断级缺陷，无安全漏洞，无新增回归。compose-ui-test 因环境不支持而按规范降级为静态+编译验证，运行时交互验证列为后续建议项。