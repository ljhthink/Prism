# ADR-036: v1 LLM 操控手机（无障碍服务 + 工具集 + 敏感拦截 + 截图增强 + 档位适配）

> 参照 Open-AutoGLM（PC 端 ADB 方案）为 Prism 落地「API 调用 LLM 直接操控手机」能力，基于 Android 本地能力重建（D-1 用户确认：无障碍重建方案，非 ADB 移植）。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-19 |
| 决策者 | 主 Agent + 用户（D-1/D-2/D-3/D-4 确认） |
| 关联文档 | [prd-v1-features.md](../prd-v1-features.md)（US-201~204）、[ADR-016](ADR-016-m6-cross-app-integration.md)（本地工具执行器接口）、[ADR-017](ADR-017-m7-device-adaptation.md)（性能档位）、[ADR-030](ADR-030-uxr8-b3-new-features.md)（ask_user 反问回路） |
| 上游调研 | LLM 操控手机技术选型对比分析报告 + Open-AutoGLM / AutoGLM-Phone 源码实证 |
| 风险等级 | P3（新增系统权限 + 无障碍服务 + 工具回路集成） |

## 背景（Context）

用户要求参照智谱开源 `Open-AutoGLM` 让"通过 API 配置的通用 LLM"直接操控手机。调研事实（D-1）：Open-AutoGLM 是 **PC 端 Python 通过 ADB 控制手机 + 专用视觉 GUI 模型（AutoGLM-Phone-9B）理解截图输出坐标动作**，Prism 运行在手机本地，无法用 ADB 控制自身所在手机。需 Android 本地能力重建。考古现有基建：M6 `LocalToolExecutor` 接口 + SkillExecutor 工具回路（maxRounds/超时/熔断/错误回灌）+ 审批三模式 + UXR8 N2 `ask_user__ask`（StopAtTools）均已就绪，本 ADR 在既有回路上新增 `phone_control__*` 本地工具集。

## 决策（Decision）

采用「**无障碍服务（AccessibilityService）读取 UI 树 + 注入手势**」为主路径（纯文本模型即可感知），「**无障碍截图（API30+）**」作增强（D-1）；P1 MVP + P2 截图增强本版本全做，P4 Shizuku 留后续（D-2）：

1. **无障碍服务（US-201）**：新增 `PhoneControlAccessibilityService`（`BIND_ACCESSIBILITY_SERVICE` + canRetrieveWindowContent + canPerformGestures + flagIncludeNotImportantViews + flagReportViewIds + canTakeScreenshot）。UI 树 BFS 映射为 `UiNode`（nid/text/desc/className/bounds/clickable/editable/password…）→ `AccessibilityUiSerializer` 序列化为结构化文本（节点上限 80 + 文本截断 60 字符）喂给通用 LLM；`performTap/LongPress/DoubleTap/Swipe/Type/GlobalAction/LaunchApp` 执行动作。
2. **工具集（US-201）**：`PhoneControlLocalToolExecutor` 实现 `LocalToolExecutor`，命名空间 `phone_control__`，12 工具：`get_ui_state/tap/long_press/double_tap/swipe/type/back/home/launch_app/wait/screenshot/take_over`。经 `compositeLocalToolExecutor` 注入 SkillExecutor，复用审批门控 + maxRounds + 超时 + 熔断 + 错误回灌。
3. **敏感拦截分层（US-202，代码层，不依赖模型自觉）**：
   - **金融专用 App 启动硬拦截**（支付宝/银行/银联等 `BLOCKED_LAUNCH_PACKAGES`）；**微信/QQ 不在黑名单**——双用途 App，禁启会破坏"打开微信搜索"高频用例（US-201 验收场景），其内部支付按钮由节点层拦截。
   - **敏感目标节点硬拦截**（`isSensitiveTargetText`：支付/转账/付款/红包/余额/充值/密码/验证码/卡号；`isPasswordNode` 含验证码识别）→ 永不执行。
   - **高危动作强制 MANUAL**（`isManualAction`：发送/删除/退出登录/拨号/短信/下单）→ 触发 ask_user（映射 Open-AutoGLM Take_over + StopAtTools），用户「允许/取消」后由答复驱动下一轮。
   - **凭据输入硬拦截**（type 文本含密码/验证码/卡号）。
   - **UI 文本不可信数据源声明**注入 systemPrompt（`PHONE_CONTROL_GUIDANCE`，防 prompt injection 越权）。
4. **人工接管（US-202）**：`take_over` 与高危动作返回 `【需要用户回答】` + AskUserPayload JSON（复用 UXR8 N2 协议），SkillExecutor 检测标记 → 发 `StreamEvent.AskUser` + StopAtTools 中断回路，UI 提问卡片等待用户接管。
5. **截图增强（US-203）**：API30+ `takeScreenshot(displayId, executor, callback)` + `ScreenshotResult.asBitmap()` 反射调用（@SystemApi 隐藏 API）；**复用 N3 降采样链路**（最长边 ≤1024px + JPEG q80 + base64 上限 400KB）控会话 JSON 膨胀；工具描述引导「仅多模态模型 + UI 树不足时使用」。
6. **性能档位（US-204）**：`PerformanceTier.isPhoneControlEnabled`（FULL/STANDARD 启用，MINIMAL/CHAT_ONLY 禁用），Factory 按 `tier.isPhoneControlEnabled` 注入；设置页「手机操控」区块引导开启无障碍 + 用途声明（prominent disclosure）+ 安全拦截说明。**保活说明**：无障碍服务由系统保活（M7 无前台服务机制，本功能无需新增前台服务）。
7. **失败识别（US-202 配套）**：`SkillExecutor.isFailureResult` 新增 `错误：` 与 `⚠️ ` 前缀（手机控制失败/硬拦截纳入重复工具熔断，防 LLM 同参数反复重试）；AskUser 接管结果（`【需要用户回答】`）不纳入失败识别（由 executeLoop 单独处理）。

## 备选方案（Alternatives）

| 方案 | 优点 | 缺点 / 否决理由 |
|---|---|---|
| 移植 Open-AutoGLM ADB 方案 | 与上游一致、可注入任意 App | Prism 是手机本地 App，无法用 ADB 控制自身所在手机（D-1 核心异议） |
| MediaProjection 截图 | 视觉增强 | Android 14+ 每次会话需用户授权弹窗，无法静默自动截图；无障碍截图免授权 |
| 自定义 IME 中文输入 | 输入质量高 | 工程量大；P1 用 ACTION_SET_TEXT + 剪贴板粘贴降级（D-4 确认留后续） |
| 整包支付 App 黑名单（含微信/QQ） | 更严格 | 破坏"打开微信搜索"核心用例；改为金融专用 App 启动拦截 + 节点级支付拦截 |
| 硬拦截高危动作后让 LLM 自行重试 | 简单 | 不可控；改为强制 MANUAL（ask_user）由用户显式决策 |

## 后果（Consequences）

- 正面后果：
  - 通用文本模型（DeepSeek 等纯文本 BYOK）可完成「打开 App + 搜索 + 点击 + 返回」高频任务。
  - 敏感操作代码层硬拦截 + 高危强制人工确认，安全边界不依赖模型自觉。
  - UI 文本不可信声明 + take_over 接管，防 prompt injection 越权操作。
  - 截图降采样控上下文膨胀，低端档位禁用防拖垮。
- 负面后果 / 代价：
  - 新增无障碍权限（系统设置手动开启，Android 12+ 无法代码拉起授权页）——设置页显著披露用途。
  - 通用模型对复杂长链路（跨 App 多步）规划成功率中等（SOTA 视觉模型复杂基准 35-47%，社区实测常见任务 85-95%）。
  - 无障碍截图仅 Android 11+（API30+），低版本自动降级（工具返回提示）。
  - `isFailureResult` 前缀扩展：`错误：`/`⚠️ ` 属通用前缀，若未来其它工具正常结果以此开头会误判失败（已知局限，与既有前缀策略一致）。
- 需要同步更新的文档或代码：
  - `AGENTS.md` 进度记录（批次3 完成）、`docs/prd-v1-features.md` 执行状态表、本 ADR 索引。

## 风险与缓解

| 风险 | 等级 | 缓解措施 |
|---|---|---|
| 无障碍服务被厂商/系统收紧（Android 17 高级保护模式） | 中/高 | 自发布渠道影响可控；UI 显著披露用途；DISABLED 审批档兜底；执行器接口抽象可插拔（Shizuku 后置） |
| 通用文本模型对 UI 树规划能力波动 | 中/中 | 分阶段：先高频短任务；关键路径结果回读校验；可降级视觉模型 |
| 安全边界被绕过（UI 注入/坐标绕过） | 低/高 | 敏感域代码层硬拦截；高危强制 MANUAL；坐标点击回读 UI 树校验节点文本；UI 文本标注不可信；每步动作可见 |
| 截图 base64 随会话 JSON 膨胀 | 中 | 最长边 ≤1024 + JPEG q80 + base64 上限 400KB + 工具描述引导纯文本模型勿用 |
| 无障碍服务未开启时工具不可用 | 中 | 工具返回引导文案；设置页状态轮询 + 「去开启」入口 |

## 审查与修复记录（guardrail-enforcer + ac-verifier）

批次3 经 guardrail-enforcer 安全审查 + ac-verifier 验收，发现并修复以下问题（本轮修复后全量回归 0 失败）：

| 编号 | 等级 | 发现 | 修复 |
|---|---|---|---|
| I-1 | 功能接线缺口（严重） | 运行时 `buildTools` 未传 `phoneControlEnabled`（默认 false）→ phone_control__* 工具实际未暴露给 LLM（systemPrompt 声明了但工具不在列表） | `sendMessage` 的 `Companion.buildTools(...)` 补传 `phoneControlEnabled = phoneControlEnabled` |
| M-1 | 中 | `nodeTextOf(nodeId)` 仅读节点自身文本 → 真实 UI 按钮标签在子 TextView，node_id 点击可绕过硬拦截 | `nodeTextOf` 改为 BFS 聚合自身+后代文本（预算 12 条防膨胀） |
| M-2 | 中 | 敏感关键词全中文 → 英文/国际 UI 可绕过全部检测 | 补充英文关键词 + 词边界正则（`(?i)(?<![a-z0-9])kw(?:s)?(?![a-z0-9])`）防 "pay" 误伤 "display/payment"；`isPasswordNode` 复用语言无关 `isPassword` 标志 |
| L-1 | 低 | `swipe` 完全绕过敏感检查（与 tap 保护不对称） | swipe 起点坐标回读 `nodeTextAt` + 敏感目标硬拦截 |
| L-2 | 低 | `launch_app` denylist 有限（9 包名） | 已知限制，保留 denylist + 节点层兜底，ADR 记录 |
| D1 | B1 | US-204「前台服务保活开关」未实现 | 判定**不适用**：无障碍服务由系统 BIND 保活，M7 无前台服务机制；PRD 标注 N/A |
| D2 | B1 | PerformanceTierTest 缺 isPhoneControlEnabled 档位矩阵 | 补 4 档直接断言测试 |
| D3 | B0 | PRD「节点上限 ~800」与实现 80 不一致 | PRD 同步为 80（更保守防膨胀设计） |

**ac-verifier 结论**：US-201/202/203 验收标准在代码实现 + 单测层核验通过；US-204 档位适配实现正确（D2 已补测）；真机链路（10 条高频指令成功率 ≥70%、无障碍手势实操、敏感拦截实际命中、take_over 接管、截图）为已知限制，待真机验证。

## 参考

- [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM)（PC 端 ADB + AutoGLM-Phone-9B 视觉 GUI 模型）
- Android `AccessibilityService` / `GestureDescription` / `TakeScreenshotCallback`（API30+）
- [prd-v1-features.md](../prd-v1-features.md) US-201~204 验收标准
