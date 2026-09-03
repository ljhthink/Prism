# 安全与质量审计报告 —— UI 组件与配置弹层变更

> 由 guardrail-enforcer 子 Agent 生成。依 CLAUDE.md 第十节。
> 审查范围：`app/src/main/java/io/prism/ui/` 下全部 UI 源码（含新增未追踪文件，非仅 diff）。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-UI-CONFIG-001 |
| 审计日期 | 2026-08-05 |
| 关联 ADR | ADR-002-prism-chat-ui-architecture、ADR-001-prism-tech-stack |
| 关联代码变更 | `app/src/main/java/io/prism/ui/`（knowledge / capabilities / components / theme / chat / settings / PrismApp.kt） |

## 0. 上下文重建摘要

- 项目阶段：M0 脚手架 / UI 骨架阶段，US-002/003/004/005。数据层为 Mock（Provider/MCP/Skill 配置均未接真实网络）。
- 本次任务目标：审查 v0.4 实体化表面重构 + 知识库导入弹层（ImportSheet）+ MCP/Skill 配置弹层（McpConfigSheet / SkillDetailSheet）的代码质量与安全合规。
- 盲区确认：
  - 主 Agent 对 ImportSheet 中 `PrismSegmented` 无宽度约束的担忧**成立**（详见问题 Q1）。
  - 主 Agent 对 `PrismSheetHost` 双层 `AnimatedVisibility` 的担忧**成立**（详见问题 Q3）。
  - 主题 token 重命名对既有模块的影响**存在隐患**（`PrismGlassStrong` 语义错位，详见问题 Q2）。

## 1. 代码质量审查（TRAE-code-review）

### 1.1 Karpathy Guidelines 符合性

- **命名**：组件/状态命名清晰（`PrismSheet`/`PrismField`/`PrismSegmented`），语义与设计规范一致。数据类与枚举职责单一。✅
- **设计**：组件复用度高（`PrismField` 的 `trailing` 插槽、`PrismCard` 的 `onClick` 可选参数），避免了过度设计。`PrismGlassCard` 保留为 `@Deprecated` 委托，兼容既有调用方，迁移策略合理。✅
- **错误处理**：UI 层无 IO/网络，未发现「吞异常」或空 catch。✅ 但存在不安全强转（问题 Q5）。
- **避免过度设计**：双层 `AnimatedVisibility` 属冗余（问题 Q3）。

### 1.2 逻辑错误 / 性能隐患 / 可维护性

| 编号 | 严重度 | 位置（相对路径） | 描述 | 修复建议 |
|---|---|---|---|---|
| Q1 | 中 | `knowledge/KnowledgeBaseScreen.kt` L181-189、L195-206 | **布局错误（主 Agent 盲区证实）**：ImportSheet 中「来源类型」「目标知识库」的 `PrismSegmented` 作为 `PrismField.trailing` 时未指定宽度。`PrismSegmented` 内部 `BoxWithConstraints` 使用 `fillMaxWidth()`，在 `PrismField` 的 `Row` 中属非 `weight` 子项，会被测量为整行宽度，从而把同 `Row` 的 `BasicTextField`（`weight(1f)`）挤压至近零宽度，弹层字段几乎不可编辑。对比 `capabilities/CapabilitiesScreen.kt` L263 已正确使用 `Modifier.width(160.dp)`。 | 为 ImportSheet 中两个 trailing `PrismSegmented` 增加宽度约束（如 `Modifier.width(...)`），与 MCP 弹层保持一致，或改用 `weight` 布局。 |
| Q2 | 中 | `theme/Color.kt` L142-143；`chat/ConversationScreen.kt` L278；`settings/SettingsScreen.kt` L176；`capabilities/CapabilitiesScreen.kt` L131；`knowledge/KnowledgeBaseScreen.kt` L312 | **跨模块语义错位（视觉回归风险）**：`PrismGlassStrong` 已 `@Deprecated` 并别名映射为 `PrismLineStrong`（`Color(0x1FFFFFFF)`，12% 白，本为描边色）。但既有模块仍将其用作 `background()` 填充（输入栏、顶栏操作钮、图标底、虚线卡）。用「描边色」作「背景填充」会渲染为近乎透明，疑似 v0.2→v0.4 迁移遗漏。 | 背景填充改用 `PrismPanel`/`PrismPanel2`（表面级色调）；仅描边保留 `PrismLineStrong`。需人工视觉确认。 |
| Q3 | 低 | `components/PrismSheetHost.kt` L34-69 | **冗余双层动画**：外层 `AnimatedVisibility(visible)`（fadeIn/fadeOut），内层 `AnimatedVisibility(visible = true)` 恒真。内层 `slideOutVertically` 的 exit 永不触发（visible 恒 true），关闭时仅外层 fadeOut、无下滑退出动画；上滑入场仅首次组合生效。 | 移除内层包装，或让内层 `visible` 跟随外层；若需下滑退出，合并到外层 exit。 |
| Q4 | 低 | `components/PrismSegmented.kt` L47-59 | **防御性边界**：`options.size` 为 0 时 `itemWidth = (maxWidth - pad*2) / 0` 触发除零崩溃；`selected` 不在 `options` 时 `indexOf` 返回 -1 被 `coerceAtLeast(0)` 静默修正，thumb 与高亮错位。当前调用点均传非空列表，属理论边界。 | 在 `options.isEmpty()` 时提前 `return`，并明确处理 `selected` 缺失。 |
| Q5 | 低 | `chat/ConversationScreen.kt` L86 | **不安全强转**：`context.applicationContext as PrismApplication` 在非 App 上下文场景会抛 `ClassCastException`。 | 改用安全强转 `as?` 并降级，或通过依赖注入传入 `PrismApplication`。 |
| Q6 | 低 | `components/PrismStatusDot.kt` L45 | **性能**：所有状态（含非 RUN）都创建 `rememberInfiniteTransition`，仅 RUN 真正触发无限动画，其余状态浪费一次 transition 对象。 | 仅在 `state == PrismDotState.RUN` 时创建 transition。 |

### 1.3 Compose 最佳实践

- **状态提升**：`PrismField`/`PrismSwitch`/`PrismSegmented` 采用单向数据流（value + onValueChange），状态提升正确。✅
- **remember 作用域**：`McpConfigSheet`/`SkillDetailSheet` 用 `remember(server.name)`/`remember(skill.name)` 作 key，切换目标时正确重置；`McpRow`/`SkillRow` 的 `enabled` 为局部状态，满足 Mock 场景。✅
- **重组**：`LazyColumn` 中 `item` 未提供 key（`KnowledgeBaseScreen`、`CapabilitiesScreen`），当前列表静态故无即时影响，但列表动态化后需补 key。列为建议。
- **动画**：`animateDpAsState`/`animateColorAsState`/`graphicsLayer` 用法正确，未发现整棵子树不必要重组。`Chat` 消息气泡恒 `AnimatedVisibility(visible=true)` 包装，滚动回收时可能重放入场动画，列为建议。

### 1.4 测试框架与基础用例充分性

本次审查未收到主 Agent 提供的 UI 测试用例文件清单；`app/src/test/java/io/prism/data/` 存在数据层测试（ProviderConfig 系列）。**UI 组件层（PrismSegmented 边界、PrismSheetHost 动画、PrismField 布局）缺少对应 compose-ui-test 用例**，建议在 ac-verifier 阶段补充（尤其 Q1 布局、Q4 边界）。

## 2. 安全漏洞扫描（TRAE-security-review）

> 说明：本次变更为纯 UI 层（Compose），无网络 IO、无数据库、无命令行执行。数据层为 Mock，未接真实请求。

### 2.1 输入与边界审计

- `PrismField` 的 `onValueChange` 仅更新本地 Compose 状态，未进入任何危险 sink（无 SQL/命令/URL 拼接）。✅
- `PrismSegmented` 采用类型化枚举/数据类选项，无字符串拼接注入面。✅
- 未发现数组越界/整数溢出/缓冲区问题（Compose 托管内存，记忆安全不在审计范围）。✅

### 2.2 执行安全审计（注入 / 最小权限 / 输出编码）

- **注入**：无 SQL 拼接、无 OS 命令执行、无 `eval`/模板注入面。UI 文本均以 `Text(text=...)` 字面量渲染，无动态 HTML/富文本解析。✅
- **最小权限**：`PrismApp` 仅声明 4 个 Tab 导航，无多余权限请求；`build.gradle.kts` 未发现危险权限声明。✅
- **输出编码**：无 Web 输出面；Compose 文本渲染天然转义，无 XSS 风险。✅

### 2.3 密钥与配置安全

| 编号 | 严重度 | 位置 | 描述 | 修复建议 |
|---|---|---|---|---|
| S1 | 低 | `capabilities/CapabilitiesScreen.kt` L276-284 | **敏感字段明文显示**：`McpConfigSheet` 的「Token / API Key」输入框使用普通 `BasicTextField`，明文展示，存在肩窥/截屏泄露风险。当前 token 仅存于局部状态、未持久化/未日志，非直接泄露，属纵深防御缺口。 | 使用 `PasswordVisualTransformation` 掩码显示，并提供「眼睛」切换明文；确认该字段不进入日志。 |
| S2 | 通过 | `data/ProviderPresets.kt` | **无硬编码密钥**：`apiKeyRef` 均为标识符（"openai" 等），指向 `ApiKeyRepository` 加密存储，无真实 token/key 硬编码。✅ | — |
| S3 | 通过 | `.gitignore` | `.env`、`.env.local`、`*.keystore`、`*.jks`、`keystore/` 均已排除，符合 CLAUDE.md 20.3。✅ | — |
| S4 | 通过 | 全量扫描 | 未发现硬编码 API Key、密码、令牌、内部 IP/域名。`https://api.example.com/mcp` 为占位符，非真实端点泄露。✅ | — |

### 2.4 依赖与供应链风险

- 本次新增依赖 `lottie-compose` v6.4.0（`gradle/libs.versions.toml` L14）。虽在本次 UI 变更中未直接引用（`build.gradle.kts` L67 已声明），仍应在 CI 中纳入依赖漏洞扫描。
- 建议运行：`./gradlew :app:dependencies` 配合 `npm audit` 类工具（Android 侧建议集成 `OSS Index` / `Dependabot`），确认 lottie 6.4.0 无已知高危 CVE。
- ADR 要求：lottie 属 P1 重要依赖，升级前应查看 changelog；本次为固定版本引用，符合 `18.5 固定版本`要求。✅

## 3. OWASP / CWE 发现

| 编号 | 等级 | 位置 | 修复建议 |
|---|---|---|---|
| OWASP-01 / CWE-522 | 低 | `capabilities/CapabilitiesScreen.kt` L276-284 | 敏感凭据展示建议掩码（Credentials Management 弱保护）。 |
| OWASP-02 / CWE-20 | 中 | `knowledge/KnowledgeBaseScreen.kt` L181-206 | 布局边界处理不当导致输入控件不可用（功能正确性，非安全注入）。 |

> 无阻断级安全漏洞（无 SQLi/命令注入/RCE/硬编码密钥/越权）。OWASP 清单中未发现可直接利用的高危项。

## 4. 结论

- [ ] 通过（可进入测试阶段）
- [x] **有条件通过** —— 无阻断级安全漏洞，但需修复/确认以下项后再进入 ac-verifier：

**必须处理（修复或人工确认）：**

1. **Q1（中）**：ImportSheet 两个 `PrismSegmented` 无宽度约束，导致标签输入框被挤压。修复后需验证布局。
2. **Q2（中）**：`PrismGlassStrong`（已别名化为描边色）被误用作背景填充，需改为 `PrismPanel`/`PrismPanel2` 并人工视觉确认。

**建议处理（进入测试前）：**
3. S1：Token/API Key 字段掩码显示。
4. Q3：精简 `PrismSheetHost` 冗余双层动画。
5. Q4：`PrismSegmented` 空 options 防御。
6. Q5：`ConversationScreen` 安全强转。

**列入 ac-verifier 关注：**

- 补充 UI 组件测试（PrismSegmented 边界、PrismSheetHost 动画、PrismField 布局）。
- 依赖审计（lottie-compose 6.4.0）。

## 5. 规则提议（accepted review → behavioral-rules）

以下 review comment 建议转为规则，追加到 `docs/behavioral-rules.md`：

| ID 提议 | 类别 | 规则 | 反例 | 正例 | 来源 |
|---|---|---|---|---|---|
| BR-interface-00X | interface | 可复用组件作为布局插槽（如 `trailing`）时，必须显式约束子控件尺寸（`width`/`weight`），不得依赖 `fillMaxWidth` 隐式撑满导致挤压同辈控件 | `PrismSegmented` 无宽度约束塞入 `PrismField.trailing` | 为 trailing 控件指定 `Modifier.width(...)` 或 `weight` | Q1，accepted review |
| BR-interface-00Y | interface | 废弃别名色 token 不得被用作语义不匹配的填充；背景填充必须用表面级色（`Panel`/`Panel2`），`Line` 系列仅用于描边 | `PrismGlassStrong`（别名=描边色）作 `background` | 背景用 `PrismPanel`，描边用 `PrismLineStrong` | Q2，accepted review |
| BR-security-00Z | security | 敏感凭据输入（Token/API Key）必须掩码显示，且不得写日志 | 明文 `BasicTextField` 展示 Token | `PasswordVisualTransformation` + 可切换 | S1，accepted review |

> 规则经 guardrail-enforcer 确认非重复且可执行后方可入库。

## 6. 自动化建议（CI/CD Integration）

建议在 `.github/workflows/` 增加或扩展：

- **静态安全检查**：集成 Android Lint 安全检查 + `detekt` 规则（检测硬编码密钥、`PrismGlass*` 误用、`as` 强转）。
- **依赖扫描**：`Dependabot`（已配置）+ CI 中 `./gradlew :app:dependencies` 输出依赖树供人工核对 lottie 等 P1 依赖。
- **UI 测试门禁**：`ac-verifier` 阶段加入 `compose-ui-test`（`createComposeRule`）覆盖 PrismSegmented 边界与 PrismSheetHost 动画。
- **视觉回归**：如有截图测试（如 `Roborazzi`），加入 `PrismGlassStrong` 背景误用检测用例。

---

## 复审结论（TKN-UI-CONFIG-002）

> 由 guardrail-enforcer 子 Agent 生成。依 CLAUDE.md 第十节 7.2 回退闭环，对 TKN-UI-CONFIG-001 的「有条件通过」项做逐项修复验证。

| 项目 | 内容 |
|---|---|
| 执行 Agent | guardrail-enforcer |
| 任务令牌 | TKN-UI-CONFIG-002 |
| 复审日期 | 2026-08-05 |
| 复审依据 | TKN-UI-CONFIG-001 审计报告 + 本次代码实态（逐文件 Read + GetDiagnostics） |

### 1. 修复项逐项验证

| 编号 | 原问题 | 修复位置（相对路径） | 验证结果 | 结论 |
|---|---|---|---|---|
| Q1（中） | ImportSheet 两个 `PrismSegmented` 无宽度约束，挤压输入框 | `knowledge/KnowledgeBaseScreen.kt` L188、L207 均加 `Modifier.width(160.dp)` | 与 `capabilities/CapabilitiesScreen.kt` L263 已验证的可运行模式完全一致；`PrismSegmented` 内部 `modifier.fillMaxWidth()` 在 `width(160.dp)` 限定约束下正确收敛到 160dp | ✅ 已修复 |
| Q2（中） | `PrismGlassStrong`（描边色别名）误作背景填充 | 顶栏钮 `capabilities/CapabilitiesScreen.kt` L131 → `PrismPanel2`；输入框 `chat/ConversationScreen.kt` L279 → `PrismPanel`；图标底 `settings/SettingsScreen.kt` L176 → `PrismPanel2`；添加卡底 `knowledge/KnowledgeBaseScreen.kt` L315 → `PrismPanel2.copy(alpha=0.6f)`、图标底 L329 → `PrismPanel2` | grep 全 `ui/` 仅剩 `theme/Color.kt` L143 的别名定义本身，无任何使用点残留；四文件 import 均已替换为 `PrismPanel`/`PrismPanel2`（Capabilities L55、ConversationScreen L65、Settings L34、KnowledgeBase L52），无冗余 import | ✅ 已修复 |
| S1（低） | Token/API Key 明文显示 | `components/PrismField.kt` L43 新增 `secret: Boolean = false`，L70 对 `BasicTextField` 应用 `PasswordVisualTransformation()`；`capabilities/CapabilitiesScreen.kt` L281 Token 字段 `secret = true` | 掩码正确作用于 `BasicTextField`（非 `Text`），符合 Compose 掩码语义；**未传 `secret` 的调用点（Base URL、安装参数、ImportSheet 全部字段）默认 `false` → `VisualTransformation.None`，行为与修复前完全一致** | ✅ 已修复 |
| Q3（低） | 内层 `AnimatedVisibility` 恒 `true`，下滑退出不触发 | `components/PrismSheetHost.kt` L59 内层 `visible = visible` | 关闭时内层 `slideOutVertically`（260ms）与遮罩 `fadeOut`（200ms）并发执行，实现「下滑 + 淡出」退出；两层动画一为淡出遮罩、一为下滑 sheet，互补不重复，布局无异常（整屏 `Box` 覆盖层） | ✅ 已修复 |
| Q4（低） | 空 options 除零崩溃 | `components/PrismSegmented.kt` L48 `if (options.isEmpty()) return` | 早退位于 `BoxWithConstraints` 及除法 `(maxWidth - pad*2)/options.size`（L56）之前；空列表时 `indexOf` 返回 -1 被 `coerceAtLeast(0)` 收敛为 0，不触及除法 | ✅ 已修复 |

### 2. 安全复扫（TRAE-security-review）

- **S1 掩码**：`PasswordVisualTransformation`（`components/PrismField.kt` L70）作用于 `BasicTextField` 的 `visualTransformation` 参数，非装饰性 `Text`，语义正确。Token 仍仅存局部 Compose 状态、未持久化/未写日志、无进入任何危险 sink，符合纵深防御要求。✅
- **无新增注入面**：本次修复均为 UI 布局/掩码/动画/边界防御，未引入 SQL/命令/代码执行、无硬编码密钥、无最小权限变更。✅
- **GetDiagnostics**：PrismField / PrismSheetHost / PrismSegmented / CapabilitiesScreen / SettingsScreen / ConversationScreen / KnowledgeBaseScreen 全部返回空诊断，import 调整无编译错误。✅

### 3. 附带发现（不在本次修复清单内，记录备查）

- `chat/ConversationScreen.kt` L86 已由 `as PrismApplication` 改为安全强转 `as? PrismApplication` 并降级为 null（即上一轮 Q5 已一并修复），为正向改进，无回归。
- 上一轮 Q6（`PrismStatusDot` 无限动画）未列入本次修复清单，仍为开放建议项，不阻塞本次通过判定。

### 4. 最终结论

- [x] **通过** —— 五项修复（Q1/Q2/S1/Q3/Q4）均正确落地、无遗留、无新引入问题，import 无冗余缺失，编译通过，无阻断级与高危级缺陷。

后续可进入 ac-verifier 阶段；建议将 ac-verifier 关注项（PrismSegmented 边界、PrismSheetHost 动画、PrismField 布局 compose-ui-test）与 Q6 一并纳入验证。
