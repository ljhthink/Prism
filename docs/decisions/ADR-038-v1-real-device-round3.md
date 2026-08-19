# ADR-038: v1 真机三次问题修复（Fetch 反爬 / 搜索命中 / 视觉旁路 / 手机操控无障碍判定+三态 / 后台确认通知）

> 落实 v1（v1.0.0）真机手动测试暴露的 5 项问题的架构决策与修复。
> 参照调研：Fetch 反爬最佳实践（tech-selection-researcher）、Open-AutoGLM 后台确认诉求。

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-08-19 |
| 决策者 | 主 Agent + 用户确认（真机反馈 5 项问题） |
| 关联文档 | [PRD](../PRD.md)、[ADR-033 UXR11](ADR-033-uxr11-real-device-fixes.md)、[ADR-035 视觉方案B](ADR-035-v1-vision-plan-b.md)、[ADR-036 手机操控](ADR-036-v1-phone-control.md)、[ADR-037 真机二次修复](ADR-037-v1-real-device-round2.md) |
| 风险等级 | P2（跨模块：请求指纹 / 搜索相关性 / 视觉旁路熔断 / 无障碍检测与高危策略 / 通知确认链） |

## 背景（Context）

v1.0.0 真机手动测试发现 5 项问题：

1. **Fetch 工具仍被反爬限制**：浏览器请求头 + 手动重定向 + SSRF 方案已存在，但部分目标站（Cloudflare/登录墙）仍 403/挑战壳。
2. **搜索无法直接命中**：参考来源只"大概相关"，与搜索内容直接联系不大（Bing RSS 对无空格中文整句分词坍缩，核心词=整句 → 条目过滤/相关性都要求字面整句命中）。
3. **视觉旁路即使激活视觉模型仍只有 OCR**：Cloud 从未执行——`isBypassAvailable`（授权+开关+熔断）有一环不过；且旁路连续失败 3 次后熔断卡死，配置修好前 Cloud 永不重试。
4. **手机操控误报"未启用无障碍"**：打开重内存 App（如微信）时进程内资源实例短暂为 null，被当"未启用"；同时用户希望**发送/删除/拨号高危动作**改为由用户选择「全部拦截 / 全部放行 / 逐次确认」。
5. **后台确认不可见**：LLM 操控手机时 Prism 常在后台，发送/删除/拨号的「逐次询问」提问卡片用户看不见 → 静默超时安全拒绝，体验差。

## 决策（Decision）

### 子决策 A：Fetch 反爬增强（问题 1）
- 在既有完整浏览器指纹头（UA Chrome/126 + `Sec-CH-UA` 系列）基础上，**新增 `Referer: https://cn.bing.com/`**（编译期常量，无注入面）——贴近"搜索 → 点进结果页"的正常来源，降低 Referer 校验型站点的 403。
- SSRF 纵深不破坏：`followRedirects=false` + 逐跳 `isPublicHttpUrl` 复检 + 3 跳上限 + 内容纯度判定（`isAntiBotOrEmpty`）全部原样保留（ADR-033/037 不变量）。

### 子决策 B：搜索直接命中（问题 2，`stripTrailingQuerySuffix` 实体提取）
- **根因**：无空格/标点中文句（如"梧州一中是什么学校"）被正则 `[\u4e00-\u9fff]{2,}` 当成**一个**连续 CJK run，核心词=整句；`filterRelevantItems`/`isRelevant` 都要求字面整句命中 → 只返回"大概相关"。且降级重试用 `term==query` 跳过了唯一候选（整句），无真实降级。
- **修复**：`extractCoreTerms` 对每个 CJK run 再剥尾部疑问/泛化后缀（`stripTrailingQuerySuffix`，最长后缀优先：`是什么学校/怎么/如何/…`），衍生前置实体候选（"梧州一中是什么学校"→"梧州一中"）。实体候选进入 `coreTerms`，主查询命中率提升；仍不相关时按实体短整词降级重试（original `term==query` 跳过整句，实体词才真正重试）。

### 子决策 C：视觉旁路 Cloud 可启用 + 熔断可恢复（问题 3）
- **consent 自动授权（已有）**：把 Provider 标记为 `isVisionFallback` 本身即用户"图片外发到该端点"的明确意图 → `saveProvider` 同步 `setConsent(true)` + `setAutoBypassEnabled(true)`，避免"激活了视觉模型仍只见 OCR"。
- **熔断可恢复（本次新增）**：`saveProvider` 在 `isVisionFallback` 时**额外 `resetFailures()`** —— 清零云端旁路连续失败计数。旁路"连续失败 3 次自动停用"熔断后，配置修好前 Cloud 永不触发、只剩 OCR；激活/保存动作代表用户"期望云端旁路可用"的信号，应重置熔断让 Cloud 重试。

### 子决策 D：无障碍系统判定 + 高危三态（问题 4）
- **误报根治**：新增 `PhoneControlAccessibilityService.isEnabledInSystem(context)`，读 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 判断本服务是否被系统启用（真实、不受进程实例空窗影响）。设置页 `phoneControlStatusProvider` 与工具执行器 `accessibilityEnabledProvider` 均改用它，从而区分「系统已启用但实例重连中」（SERVICE_REBINDING_MSG，提示稍后重试，不让 LLM 误判放弃）与「未启用」（引导开启）。
- **高危三态**：新增 `HighRiskApprovalMode`（`BLOCK` 全部拦截 / `ALLOW` 全部放行 / `ASK` 逐次确认，默认 ASK）+ `HighRiskApprovalRepository`（独立 DataStore 持久化）。`PhoneControlLocalToolExecutor.runTargetAction`/`runTypeAction` 对命中发送/删除/拨号/短信的高危动作按三态处置：BLOCK 返回 ⚠️ 硬拦截、ALLOW 直接放行执行、ASK 触发 `manualConfirm`（ask_user 提问卡片）。设置页新增「高危操作」三态循环选择行。

### 子决策 E：后台确认系统通知（问题 5）
- **方案选型**：用**高优先级通知 + 允许/拒绝操作按钮**（免 `SYSTEM_ALERT_WINDOW` 悬浮窗权限，走系统标准通知通道，Android 13+ 需通知运行时权限）让确认在后台也可见。
- `PhoneControlAskUserNotifier`：
  - `request(question)` → 分配 `askId` + 发高优先级通知（`BigTextStyle`、`CATEGORY_CALL`、`VISIBILITY_PRIVATE` 锁屏不泄详情、点通知本体默认拒绝=安全）；发新通知前**先撤旧通知**（`activeAskId` 单槽 + 防陈旧按钮误批新确认，guardrail LOW-1）。
  - `ConfirmActionReceiver`（`android:exported=false` + `PendingIntent.FLAG_IMMUTABLE` 显式组件广播）→ 回调单例 `resolve(askId, allow/deny)` → 撤通知 + 推送到 `answers: SharedFlow<Answer>`。
  - `ensureChannel` 建渠道（API26+）；`PrismApplication` 注册 `holder` + 注入 `PhoneControlLocalToolExecutor.askUserNotifier`（`manualConfirm` 用 `runCatching` 发通知，失败不阻塞主流程）。
- **消费**：`ConversationViewModel` 新增 `phoneControlAskUserNotifier` 注入，init collect `answers`；`resolveAskUserFromNotification` 把 `allow`→「允许」/ `deny`→「取消」（固定白名单映射，无任意注入；未知动作忽略）作为下一条 user 消息 `sendMessage` 回灌工具回路。判空：`_pendingAskUser` 为空则忽略（已作答/超时）；等待 `isTyping` 复位带 `withTimeoutOrNull(15s)`（防与工具回路 finally 竞态导致答案被守卫静默丢弃）。
- **权限**：`MainActivity` 在 API33+ 运行时请求 `POST_NOTIFICATIONS`（拒绝不阻塞）；`AndroidManifest` 声明该权限。

## 结果（Consequences）

- 全量回归通过（BUILD SUCCESSFUL，0 失败）。
- 新增/更新单测：
  - 搜索实体提取：`extractCoreTerms` 剥"梧州一中是什么学校"→"梧州一中"；`filterRelevantItems` 按实体保留直接命中条目、剔除仅含子串条目（ac-verifier P2）。
  - 高危三态：BLOCK 拦截前缀 / ALLOW 放行（服务不可用引导）/ ASK 提问卡片标记（ac-verifier P4b）。
  - 通知链（Robolectric）：`request` 发通知、`resolve` 推送答案到流且撤通知、新 `request` 撤旧通知（ac-verifier P5）。
- guardrail-enforcer（TKN-V1B5-GUARDRAIL-001）：**通过**（0 阻断），1 项 LOW（陈旧通知 askId 不匹配→已用"发新先撤旧"闭环）；无注入/越权 PendingIntent/CWE-209 回归；`exported=false` + `FLAG_IMMUTABLE` + 锁屏 `VISIBILITY_PRIVATE` 保护完好。
- ac-verifier（TKN-V1B5-ACCEPTANCE-001）：**5/5 通过**（初判 Conditional PASS，补齐 P2/P4b/P5 测试后关闭）。
- 模拟器/APK：`assembleDebug` 成功，安装可运行。
- 已知待真机补测：Bing 真实关键词命中、"梧州一中"类实体实际命中、Fetch 真实反爬站点、视觉旁路端到端（激活后 Cloud）、高危三态设置交互、后台确认通知真实弹出与作答、微信打开无障碍状态。

## 后续跟踪

- Fetch 对强 WAF（Cloudflare JS 挑战）仍不可绕（受 TLS/JA3 与无头渲染限制），如需突破需引入云渲染/OCR 旁路，超出当前零依赖与隐私边界；当前以可诊断文案 + 引导换来源为边界。
- `AskUserQuestion` 单槽 + 通知先撤旧的关联由 `activeAskId` 维护；若未来支持多路并发确认，需把 `askId` 并入提问槽做一一对应校验。